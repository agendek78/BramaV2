#include <stdio.h>
#include <string.h>
#include "brama_crypto.h"
#include "brama_err.h"
#include "brama_session.h"

#define TRANSCRIPT_LEN (2 * BRAMA_ID_LEN + 2 * BRAMA_NONCE_LEN + 8)
#define BEGIN_BODY_LEN (BRAMA_ID_LEN + BRAMA_NONCE_LEN)
#define CHALLENGE_BODY_LEN (2 * BRAMA_ID_LEN + 2 * BRAMA_NONCE_LEN)

void brama_session_init(brama_session_t *s)
{
    memset(s, 0, sizeof *s);
}

void brama_session_reset(brama_session_t *s)
{
    if (s->jpake_active) brama_jpake_free(&s->jpake);
    brama_zeroize(s, sizeof *s);
}

bool brama_session_ready(const brama_session_t *s)
{
    return s->state == BRAMA_SS_READY;
}

static void clear_step(brama_step_t *out)
{
    out->reply_len = 0;
    out->event = BRAMA_EV_NONE;
    out->type = 0;
    out->payload = NULL;
    out->payload_len = 0;
    out->seq = 0;
    out->via_pin = false;
    out->auth_failure = false;
    out->pin_failure = false;
}

static void reply(brama_step_t *out, const uint8_t *frame, size_t len)
{
    memcpy(out->reply, frame, len);
    out->reply_len = len;
}

static void reject(brama_step_t *out, uint64_t session_id, uint8_t code)
{
    brama_frame_header_t h = {BRAMA_MSG_HANDSHAKE_REJECT, session_id, 0, 1};
    brama_header_encode(&h, out->reply);
    out->reply[BRAMA_FRAME_HEADER_LEN] = code;
    out->reply_len = BRAMA_FRAME_HEADER_LEN + 1;
}

/** Kończy próbę/sesję: unieważnia kontekst; w READY także zdarzenie dla aktora. */
static void invalidate(brama_session_t *s, brama_step_t *out)
{
    bool was_ready = s->state == BRAMA_SS_READY;
    brama_session_reset(s);
    if (was_ready) out->event = BRAMA_EV_INVALIDATED;
}

static void fail_attempt(brama_session_t *s, brama_step_t *out, uint64_t session_id)
{
    brama_session_reset(s);
    reject(out, session_id, BRAMA_REJECT_AUTH_FAILED);
    out->auth_failure = true;
}

static uint64_t random_session_id(const brama_session_env_t *env)
{
    uint8_t b[8];
    uint64_t sid = 0;
    while (sid == 0) {
        env->rng.fill(env->rng.ctx, b, sizeof b);
        sid = brama_get_le(b, 8);
    }
    return sid;
}

static void transcript(const brama_session_t *s, const uint8_t *device_id, uint8_t t[TRANSCRIPT_LEN])
{
    memcpy(t, device_id, BRAMA_ID_LEN);
    memcpy(t + 16, s->client_id, BRAMA_ID_LEN);
    memcpy(t + 32, s->client_nonce, BRAMA_NONCE_LEN);
    memcpy(t + 64, s->server_nonce, BRAMA_NONCE_LEN);
    brama_put_le(t + 96, s->session_id, 8);
}

static void derive_keys(brama_session_t *s, const uint8_t t[TRANSCRIPT_LEN])
{
    uint8_t salt[32], prk[32];
    char label[40];
    const char *kind = s->pair ? "PAIR" : "SESSION";
    brama_sha256(t, TRANSCRIPT_LEN, salt);
    brama_hkdf_extract(salt, sizeof salt, s->hs_key, BRAMA_KEY_LEN, prk);
    struct { const char *suffix; uint8_t *out; size_t len; } parts[] = {
        {"c2s/key", s->c2s_key, 32}, {"s2c/key", s->s2c_key, 32}, {"c2s/iv", s->c2s_iv, 4}, {"s2c/iv", s->s2c_iv, 4}};
    for (size_t i = 0; i < 4; i++) {
        int n = snprintf(label, sizeof label, "BRAMA/2/%s/%s", kind, parts[i].suffix);
        brama_hkdf_expand(prk, (const uint8_t *)label, (size_t)n, parts[i].out, parts[i].len);
    }
    brama_zeroize(prk, sizeof prk);
}

static void gcm_nonce(const uint8_t iv[4], uint64_t seq, uint8_t nonce[12])
{
    memcpy(nonce, iv, 4);
    brama_put_le(nonce + 4, seq, 8);
}

static int seal(brama_session_t *s, uint8_t type, uint64_t seq, const uint8_t *payload, size_t len, uint8_t *out,
                size_t *out_len)
{
    if (BRAMA_FRAME_HEADER_LEN + len + BRAMA_GCM_TAG_LEN > BRAMA_FRAME_MAX) return BRAMA_ERR_ARG;
    brama_frame_header_t h = {type, s->session_id, seq, (uint16_t)len};
    brama_header_encode(&h, out);
    uint8_t nonce[12];
    gcm_nonce(s->s2c_iv, seq, nonce);
    int rc = brama_gcm_seal(s->s2c_key, nonce, out, BRAMA_FRAME_HEADER_LEN, payload, len, out + BRAMA_FRAME_HEADER_LEN);
    if (rc == BRAMA_OK) *out_len = BRAMA_FRAME_HEADER_LEN + len + BRAMA_GCM_TAG_LEN;
    return rc;
}

static int open_c2s(brama_session_t *s, const brama_frame_t *f, uint8_t *plain)
{
    uint8_t nonce[12];
    gcm_nonce(s->c2s_iv, f->h.seq, nonce);
    return brama_gcm_open(s->c2s_key, nonce, f->header, BRAMA_FRAME_HEADER_LEN, f->body,
                          f->h.payload_len + f->tag_len, plain);
}

static bool hmac_ok(const uint8_t key[32], const char *domain, const uint8_t *a, size_t al, const uint8_t *b, size_t bl,
                    const uint8_t *tag)
{
    uint8_t buf[BRAMA_FRAME_HEADER_LEN + CHALLENGE_BODY_LEN + TRANSCRIPT_LEN], mac[32];
    memcpy(buf, a, al);
    memcpy(buf + al, b, bl);
    brama_mac(key, domain, buf, al + bl, mac);
    bool ok = brama_ct_equal(mac, tag, 32);
    brama_zeroize(mac, sizeof mac);
    return ok;
}

// --- Handshake -------------------------------------------------------------------------------------------------------

static void on_begin(brama_session_t *s, const brama_session_env_t *env, const brama_frame_t *f, const uint8_t hash[32],
                     brama_step_t *out)
{
    bool pair = f->h.type == BRAMA_MSG_PAIR_BEGIN;
    bool ready = s->state == BRAMA_SS_READY;
    if (s->state >= BRAMA_SS_AWAIT_CONFIRM && memcmp(hash, s->begin_hash, 32) == 0) {
        reply(out, s->challenge, sizeof s->challenge); // identyczny BEGIN: zachowane CHALLENGE (11.3, 13.4)
        return;
    }
    // Działająca sesja nie dostaje odmów: nieudany BEGIN na niej jest ignorowany (12.4).
    uint8_t blocker = env->attempt_blocker(env->ctx);
    if (blocker) {
        if (!ready) reject(out, 0, blocker);
        return;
    }
    if (f->h.payload_len != BEGIN_BODY_LEN) {
        if (!ready) fail_attempt(s, out, 0);
        return;
    }
    const uint8_t *client_id = f->body;
    uint8_t key[BRAMA_KEY_LEN];
    bool via_pin = pair && s->has_k_pin;
    if (pair) {
        memcpy(key, via_pin ? s->k_pin : env->k_setup, BRAMA_KEY_LEN);
    } else if (!env->client_key(env->ctx, client_id, key)) {
        out->auth_failure = true;
        if (!ready) fail_attempt(s, out, 0);
        return;
    }
    const char *domain = pair ? "BRAMA/2/PAIR_BEGIN" : "BRAMA/2/SESSION_BEGIN";
    bool ok = hmac_ok(key, domain, f->header, BRAMA_FRAME_HEADER_LEN + BEGIN_BODY_LEN, env->device_id, BRAMA_ID_LEN, f->tag);
    if (!ok) {
        brama_zeroize(key, sizeof key);
        out->auth_failure = true;
        out->pin_failure = via_pin;
        if (via_pin) {
            // K_pin jednorazowy: pierwsze nieudane potwierdzenie usuwa go (11.4).
            brama_zeroize(s->k_pin, sizeof s->k_pin);
            s->has_k_pin = false;
        }
        if (!ready) {
            brama_session_reset(s);
            reject(out, 0, BRAMA_REJECT_AUTH_FAILED);
        }
        return;
    }
    bool repair = false;
    uint8_t code = pair ? env->pair_admit(env->ctx, client_id, via_pin) : env->session_admit(env->ctx, client_id, &repair);
    if (code) {
        // Kod szczegółowy dopiero po poprawnym HMAC (12.4). Działająca sesja zostaje nienaruszona (9.5: starą sesję
        // blokuje dopiero przyjęta próba). Odmowę dostaje PAIR_BEGIN (ponowna rejestracja na zachowanym łączu, 9.5 —
        // jak przed tą zmianą), a nieudany SESSION_BEGIN na niej jest ignorowany; ogólne blokady i zły HMAC na READY
        // pozostają milczące (12.4, 12.1).
        brama_zeroize(key, sizeof key);
        if (!ready) brama_session_reset(s);
        if (!ready || pair) reject(out, 0, code);
        return;
    }
    // Nowa próba zastępuje poprzednią (także działającą sesję po poprawnym BEGIN, 12.1); aktor odrzuca wtedy
    // zaległe komendy starej sesji (EV_INVALIDATED), ale nie traktuje tego jak utraty łącza.
    if (ready) out->event = BRAMA_EV_INVALIDATED;
    uint8_t k_pin[BRAMA_KEY_LEN];
    bool had_pin = s->has_k_pin;
    memcpy(k_pin, s->k_pin, sizeof k_pin);
    brama_session_reset(s);
    s->has_k_pin = had_pin && pair;
    memcpy(s->k_pin, k_pin, sizeof k_pin);
    brama_zeroize(k_pin, sizeof k_pin);
    s->pair = pair;
    s->via_pin = via_pin;
    s->repair = repair;
    memcpy(s->hs_key, key, BRAMA_KEY_LEN);
    brama_zeroize(key, sizeof key);
    memcpy(s->client_id, client_id, BRAMA_ID_LEN);
    memcpy(s->client_nonce, client_id + BRAMA_ID_LEN, BRAMA_NONCE_LEN);
    s->session_id = random_session_id(env);
    env->rng.fill(env->rng.ctx, s->server_nonce, BRAMA_NONCE_LEN);

    uint8_t *c = s->challenge;
    brama_frame_header_t h = {pair ? BRAMA_MSG_PAIR_CHALLENGE : BRAMA_MSG_SESSION_CHALLENGE, s->session_id, 0,
                              CHALLENGE_BODY_LEN};
    brama_header_encode(&h, c);
    memcpy(c + 22, env->device_id, BRAMA_ID_LEN);
    memcpy(c + 38, s->client_id, BRAMA_ID_LEN);
    memcpy(c + 54, s->client_nonce, BRAMA_NONCE_LEN);
    memcpy(c + 86, s->server_nonce, BRAMA_NONCE_LEN);
    brama_mac(s->hs_key, pair ? "BRAMA/2/PAIR_CHALLENGE" : "BRAMA/2/SESSION_CHALLENGE", c,
              BRAMA_FRAME_HEADER_LEN + CHALLENGE_BODY_LEN, c + BRAMA_FRAME_HEADER_LEN + CHALLENGE_BODY_LEN);
    memcpy(s->begin_hash, hash, 32);
    s->state = BRAMA_SS_AWAIT_CONFIRM;
    reply(out, c, sizeof s->challenge);
}

static void on_confirm(brama_session_t *s, const brama_session_env_t *env, const brama_frame_t *f,
                       const uint8_t hash[32], brama_step_t *out)
{
    if (s->final_len && memcmp(hash, s->confirm_hash, 32) == 0) {
        reply(out, s->final_resp, s->final_len); // identyczny CONFIRM: zachowane READY/GRANT
        return;
    }
    if (s->state == BRAMA_SS_REPAIR_COMMITTING && memcmp(hash, s->confirm_hash, 32) == 0) return; // transakcja trwa
    bool pair = f->h.type == BRAMA_MSG_PAIR_CONFIRM;
    if (s->state == BRAMA_SS_READY) {
        invalidate(s, out); // znany typ w złej fazie (13.1)
        return;
    }
    if (s->state != BRAMA_SS_AWAIT_CONFIRM || s->pair != pair || f->h.session_id != s->session_id
        || f->h.payload_len != 0) {
        fail_attempt(s, out, f->h.session_id);
        return;
    }
    uint8_t t[TRANSCRIPT_LEN];
    transcript(s, env->device_id, t);
    if (!hmac_ok(s->hs_key, pair ? "BRAMA/2/PAIR_CONFIRM" : "BRAMA/2/SESSION_CONFIRM", f->header,
                 BRAMA_FRAME_HEADER_LEN, t, sizeof t, f->tag)) {
        fail_attempt(s, out, f->h.session_id);
        return;
    }
    derive_keys(s, t);
    s->last_c2s = 0;
    if (pair) {
        env->rng.fill(env->rng.ctx, s->k_client, BRAMA_KEY_LEN);
        seal(s, BRAMA_MSG_PAIR_GRANT, 1, s->k_client, BRAMA_KEY_LEN, s->final_resp, &s->final_len);
        s->state = BRAMA_SS_PAIR_GRANTED;
    } else if (s->repair) {
        // Naprawa bondu: READY dopiero po zatwierdzeniu nowego bondu przez aktora (9.5).
        s->state = BRAMA_SS_REPAIR_COMMITTING;
        out->event = BRAMA_EV_REPAIR_COMMIT;
        memcpy(out->client_id, s->client_id, BRAMA_ID_LEN);
        memcpy(s->confirm_hash, hash, 32);
        return;
    } else {
        brama_snapshot_t snap;
        uint8_t sb[BRAMA_SNAPSHOT_LEN];
        env->snapshot_for(env->ctx, s->client_id, &snap);
        brama_snapshot_encode(&snap, sb);
        seal(s, BRAMA_MSG_SESSION_READY, 1, sb, sizeof sb, s->final_resp, &s->final_len);
        s->state = BRAMA_SS_READY;
        out->event = BRAMA_EV_SESSION_READY;
        memcpy(out->client_id, s->client_id, BRAMA_ID_LEN);
    }
    s->next_s2c = 2;
    memcpy(s->confirm_hash, hash, 32);
    reply(out, s->final_resp, s->final_len);
}

static void on_commit(brama_session_t *s, const brama_frame_t *f, const uint8_t hash[32], brama_step_t *out)
{
    if (memcmp(hash, s->commit_hash, 32) == 0 && s->state >= BRAMA_SS_PAIR_COMMITTING) {
        // Identyczny COMMIT: zachowany DONE albo nic, gdy transakcja aktora jeszcze trwa (11.3).
        if (s->state == BRAMA_SS_PAIR_DONE) reply(out, s->done_resp, sizeof s->done_resp);
        return;
    }
    if (s->state == BRAMA_SS_READY) {
        invalidate(s, out);
        return;
    }
    if (s->state != BRAMA_SS_PAIR_GRANTED || f->h.session_id != s->session_id || f->h.seq != 1
        || f->h.payload_len != 0 || open_c2s(s, f, s->plain) != BRAMA_OK) {
        fail_attempt(s, out, f->h.session_id);
        return;
    }
    s->last_c2s = 1;
    memcpy(s->commit_hash, hash, 32);
    s->state = BRAMA_SS_PAIR_COMMITTING;
    out->event = BRAMA_EV_PAIR_COMMIT;
    out->via_pin = s->via_pin;
    memcpy(out->client_id, s->client_id, BRAMA_ID_LEN);
    memcpy(out->k_client, s->k_client, BRAMA_KEY_LEN);
}

int brama_session_pair_finish(brama_session_t *s, bool ok, uint8_t *out, size_t *out_len)
{
    if (s->state != BRAMA_SS_PAIR_COMMITTING) return BRAMA_ERR_STATE;
    if (!ok) {
        brama_step_t tmp;
        reject(&tmp, s->session_id, BRAMA_REJECT_STORAGE_FAILURE);
        memcpy(out, tmp.reply, tmp.reply_len);
        *out_len = tmp.reply_len;
        brama_session_reset(s);
        return BRAMA_OK;
    }
    size_t len = 0;
    int rc = seal(s, BRAMA_MSG_PAIR_DONE, 2, NULL, 0, s->done_resp, &len);
    if (rc != BRAMA_OK) return rc;
    brama_zeroize(s->k_client, sizeof s->k_client);
    s->state = BRAMA_SS_PAIR_DONE;
    memcpy(out, s->done_resp, len);
    *out_len = len;
    return BRAMA_OK;
}

int brama_session_repair_finish(brama_session_t *s, const brama_session_env_t *env, uint8_t code, uint8_t *out,
                                size_t *out_len)
{
    if (s->state != BRAMA_SS_REPAIR_COMMITTING) return BRAMA_ERR_STATE;
    if (code) {
        brama_step_t tmp;
        reject(&tmp, s->session_id, code);
        memcpy(out, tmp.reply, tmp.reply_len);
        *out_len = tmp.reply_len;
        brama_session_reset(s);
        return BRAMA_OK;
    }
    brama_snapshot_t snap;
    uint8_t sb[BRAMA_SNAPSHOT_LEN];
    env->snapshot_for(env->ctx, s->client_id, &snap);
    brama_snapshot_encode(&snap, sb);
    int rc = seal(s, BRAMA_MSG_SESSION_READY, 1, sb, sizeof sb, s->final_resp, &s->final_len);
    if (rc != BRAMA_OK) return rc;
    s->state = BRAMA_SS_READY;
    s->next_s2c = 2;
    memcpy(out, s->final_resp, s->final_len);
    *out_len = s->final_len;
    return BRAMA_OK;
}

// --- PAKE (11.4) ------------------------------------------------------------------------------------------------------

static size_t pake_frame(uint8_t type, uint64_t sid, const uint8_t *payload, size_t len, uint8_t *out)
{
    brama_frame_header_t h = {type, sid, 0, (uint16_t)len};
    brama_header_encode(&h, out);
    memcpy(out + BRAMA_FRAME_HEADER_LEN, payload, len);
    return BRAMA_FRAME_HEADER_LEN + len;
}

static void on_pake1(brama_session_t *s, const brama_session_env_t *env, const brama_frame_t *f, const uint8_t hash[32],
                     brama_step_t *out)
{
    if (s->pake_reply1_len && memcmp(hash, s->pake_r1_hash, 32) == 0) {
        reply(out, s->pake_reply1, s->pake_reply1_len);
        return;
    }
    if (s->state == BRAMA_SS_READY) return; // PAKE nie przerywa działającej sesji
    uint8_t blocker = env->attempt_blocker(env->ctx);
    if (blocker) {
        reject(out, 0, blocker);
        return;
    }
    // Poza oknem bez ujawniania WINDOW_CLOSED — brak jeszcze uwierzytelnienia (11.4).
    if (!env->window_open(env->ctx) || f->h.payload_len > 340) {
        fail_attempt(s, out, 0);
        return;
    }
    brama_session_reset(s);
    if (brama_jpake_init(&s->jpake, env->pin, 6) != BRAMA_OK) {
        reject(out, 0, BRAMA_REJECT_UNAVAILABLE);
        return;
    }
    s->jpake_active = true;
    uint8_t r[340];
    size_t rl = 0;
    if (brama_jpake_read_round1(&s->jpake, f->body, f->h.payload_len) != BRAMA_OK
        || brama_jpake_write_round1(&s->jpake, r, sizeof r, &rl) != BRAMA_OK) {
        fail_attempt(s, out, 0);
        return;
    }
    s->session_id = random_session_id(env);
    memcpy(s->pake_transcript, f->body, f->h.payload_len);
    memcpy(s->pake_transcript + f->h.payload_len, r, rl);
    s->pake_transcript_len = f->h.payload_len + rl;
    s->pake_reply1_len = pake_frame(BRAMA_MSG_PAKE_ROUND1_REPLY, s->session_id, r, rl, s->pake_reply1);
    memcpy(s->pake_r1_hash, hash, 32);
    s->state = BRAMA_SS_PAKE_R1;
    reply(out, s->pake_reply1, s->pake_reply1_len);
}

static void on_pake2(brama_session_t *s, const brama_frame_t *f, const uint8_t hash[32], brama_step_t *out)
{
    if (s->pake_reply2_len && memcmp(hash, s->pake_r2_hash, 32) == 0) {
        reply(out, s->pake_reply2, s->pake_reply2_len);
        return;
    }
    if (s->state == BRAMA_SS_READY) return;
    if (s->state != BRAMA_SS_PAKE_R1 || f->h.session_id != s->session_id || f->h.payload_len > 180) {
        fail_attempt(s, out, f->h.session_id);
        return;
    }
    uint8_t r[180], secret[32];
    size_t rl = 0;
    if (brama_jpake_read_round2(&s->jpake, f->body, f->h.payload_len) != BRAMA_OK
        || brama_jpake_write_round2(&s->jpake, r, sizeof r, &rl) != BRAMA_OK
        || brama_jpake_derive_secret(&s->jpake, secret) != BRAMA_OK) {
        fail_attempt(s, out, f->h.session_id);
        return;
    }
    brama_jpake_free(&s->jpake);
    s->jpake_active = false;
    uint8_t *pt = s->pake_transcript;
    memcpy(pt + s->pake_transcript_len, f->body, f->h.payload_len);
    s->pake_transcript_len += f->h.payload_len;
    memcpy(pt + s->pake_transcript_len, r, rl);
    s->pake_transcript_len += rl;
    brama_pin_setup_key(secret, pt, s->pake_transcript_len, s->k_pin);
    brama_zeroize(secret, sizeof secret);
    s->has_k_pin = true;
    s->pake_reply2_len = pake_frame(BRAMA_MSG_PAKE_ROUND2_REPLY, s->session_id, r, rl, s->pake_reply2);
    memcpy(s->pake_r2_hash, hash, 32);
    s->state = BRAMA_SS_PAKE_DONE;
    reply(out, s->pake_reply2, s->pake_reply2_len);
}

// --- Sesja READY -----------------------------------------------------------------------------------------------------

static const brama_cache_entry_t *cache_find(const brama_session_t *s, const uint8_t hash[32])
{
    for (int i = 0; i < BRAMA_SESSION_CACHE; i++) {
        if (s->cache[i].used && memcmp(s->cache[i].req_hash, hash, 32) == 0) return &s->cache[i];
    }
    return NULL;
}

static void on_session_message(brama_session_t *s, const brama_frame_t *f, const uint8_t hash[32], brama_step_t *out)
{
    if (s->state != BRAMA_SS_READY || f->h.session_id != s->session_id) return;
    const brama_cache_entry_t *c = cache_find(s, hash);
    if (c) {
        reply(out, c->resp, c->resp_len); // duplikat: zachowana odpowiedź, bez ponownego wykonania (13.4)
        return;
    }
    if (f->h.seq == s->last_c2s && memcmp(hash, s->pending_hash, 32) == 0) return; // odpowiedź jeszcze w toku
    if (f->h.type == BRAMA_MSG_LIST_CLIENTS && s->list_hash_set && f->h.seq == s->last_c2s
        && memcmp(hash, s->list_hash, 32) == 0 && open_c2s(s, f, s->plain) == BRAMA_OK) {
        // CLIENT_LIST (do 351 B) nie mieści się w cache odpowiedzi (64 B), a żądanie jest bezstanowym odczytem:
        // retransmisję po T_response wykonujemy ponownie zamiast unieważniać sesję (client_management_design.md §4.2).
        memcpy(s->pending_hash, hash, 32);
        out->event = BRAMA_EV_COMMAND;
        out->type = f->h.type;
        out->payload = s->plain;
        out->payload_len = f->h.payload_len;
        out->seq = f->h.seq;
        return;
    }
    brama_msg_info_t info = brama_msg_info(f->h.type);
    if (f->h.seq != s->last_c2s + 1 || (info.known && info.phase != BRAMA_PHASE_SESSION)
        || open_c2s(s, f, s->plain) != BRAMA_OK) {
        invalidate(s, out);
        return;
    }
    s->last_c2s = f->h.seq;
    memcpy(s->pending_hash, hash, 32);
    out->event = BRAMA_EV_COMMAND;
    out->type = f->h.type;
    out->payload = s->plain;
    out->payload_len = f->h.payload_len;
    out->seq = f->h.seq;
}

int brama_session_respond(brama_session_t *s, uint64_t request_seq, uint8_t type, const uint8_t *payload, size_t len,
                          uint8_t *out, size_t *out_len)
{
    if (s->state != BRAMA_SS_READY || request_seq != s->last_c2s) return BRAMA_ERR_STATE;
    int rc = seal(s, type, s->next_s2c, payload, len, out, out_len);
    if (rc != BRAMA_OK) return rc;
    s->next_s2c++;
    if (*out_len <= BRAMA_CACHED_RESPONSE_MAX) {
        brama_cache_entry_t *e = &s->cache[s->cache_next];
        s->cache_next = (s->cache_next + 1) % BRAMA_SESSION_CACHE;
        memcpy(e->req_hash, s->pending_hash, 32);
        e->req_seq = request_seq;
        memcpy(e->resp, out, *out_len);
        e->resp_len = (uint8_t)*out_len;
        e->used = true;
    }
    if (type == BRAMA_MSG_CLIENT_LIST && *out_len > BRAMA_CACHED_RESPONSE_MAX) {
        memcpy(s->list_hash, s->pending_hash, 32);
        s->list_hash_set = true;
    }
    memset(s->pending_hash, 0, sizeof s->pending_hash);
    return BRAMA_OK;
}

int brama_session_seal_event(brama_session_t *s, uint8_t type, const uint8_t *payload, size_t len, uint8_t *out,
                             size_t *out_len)
{
    if (s->state != BRAMA_SS_READY) return BRAMA_ERR_STATE;
    int rc = seal(s, type, s->next_s2c, payload, len, out, out_len);
    if (rc == BRAMA_OK) s->next_s2c++;
    return rc;
}

// --- Wejście ---------------------------------------------------------------------------------------------------------

void brama_session_on_frame(brama_session_t *s, const brama_session_env_t *env, const uint8_t *frame, size_t len,
                            brama_step_t *out)
{
    clear_step(out);
    brama_frame_t f;
    if (brama_frame_decode(frame, len, &f) != BRAMA_OK) {
        // Zła ramka: w READY błąd protokołu, w handshake nieudana próba bez odpowiedzi (10.1).
        if (s->state == BRAMA_SS_READY) invalidate(s, out);
        else out->auth_failure = true;
        return;
    }
    brama_msg_info_t info = brama_msg_info(f.h.type);
    if (info.known && info.dir == BRAMA_DIR_S2C) {
        // Ramka s2c przez RX (np. ACK, HANDSHAKE_REJECT) to znany typ w złym kierunku (13.1, 12.4).
        if (s->state == BRAMA_SS_READY) invalidate(s, out);
        else fail_attempt(s, out, f.h.session_id);
        return;
    }
    uint8_t hash[32];
    brama_sha256(frame, len, hash);
    switch (f.h.type) {
    case BRAMA_MSG_SESSION_BEGIN:
    case BRAMA_MSG_PAIR_BEGIN: on_begin(s, env, &f, hash, out); break;
    case BRAMA_MSG_SESSION_CONFIRM:
    case BRAMA_MSG_PAIR_CONFIRM: on_confirm(s, env, &f, hash, out); break;
    case BRAMA_MSG_PAIR_COMMIT: on_commit(s, &f, hash, out); break;
    case BRAMA_MSG_PAKE_ROUND1: on_pake1(s, env, &f, hash, out); break;
    case BRAMA_MSG_PAKE_ROUND2: on_pake2(s, &f, hash, out); break;
    default: on_session_message(s, &f, hash, out); break;
    }
}
