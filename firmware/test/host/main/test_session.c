// Klient mbedTLS EC-JPAKE w teście PIN (prywatne API, jak test_ecjpake.c).
#include <stdio.h>
#define MBEDTLS_ALLOW_PRIVATE_ACCESS
#include <string.h>
#include "unity.h"
#include "brama_crypto.h"
#include "brama_err.h"
#include "brama_session.h"
#include "mbedtls/private/ecjpake.h"
#include "test_registry.h"
#include "test_vectors.h"

// --- Fałszywe środowisko -------------------------------------------------------------------------------------------

typedef struct {
    uint8_t device_id[16];
    uint8_t k_setup[32];
    uint8_t client_id[16];
    uint8_t k_client[32];
    bool client_known;
    bool window;
    uint8_t admit;
    uint8_t session_admit; // kod odmowy SESSION_BEGIN po poprawnym HMAC
    bool repair;           // łącze naprawy bondu (9.5)
    int session_admits;
    uint8_t blocker;
    uint8_t rng_queue[128];
    size_t rng_len, rng_pos;
    brama_snapshot_t snapshot;
} fake_env_t;

static fake_env_t fe;
static brama_session_env_t env;
static brama_session_t ss;
static brama_step_t step;

static void rng_fill(void *ctx, uint8_t *buf, size_t len)
{
    (void)ctx;
    for (size_t i = 0; i < len; i++) buf[i] = fe.rng_pos < fe.rng_len ? fe.rng_queue[fe.rng_pos++] : (uint8_t)(0xA5 ^ i);
}

static void rng_push(const uint8_t *b, size_t len)
{
    memcpy(fe.rng_queue + fe.rng_len, b, len);
    fe.rng_len += len;
}

static bool client_key(void *ctx, const uint8_t id[16], uint8_t out[32])
{
    (void)ctx;
    if (!fe.client_known || memcmp(id, fe.client_id, 16) != 0) return false;
    memcpy(out, fe.k_client, 32);
    return true;
}

static uint8_t pair_admit(void *ctx, const uint8_t id[16], bool via_pin)
{
    (void)ctx, (void)id, (void)via_pin;
    return fe.admit;
}

static uint8_t session_admit(void *ctx, const uint8_t id[16], bool *repair)
{
    (void)ctx, (void)id;
    fe.session_admits++;
    *repair = fe.repair;
    return fe.session_admit;
}

static bool window_open(void *ctx) { (void)ctx; return fe.window; }
static uint8_t attempt_blocker(void *ctx) { (void)ctx; return fe.blocker; }

static void snapshot_for(void *ctx, const uint8_t id[16], brama_snapshot_t *out)
{
    (void)ctx, (void)id;
    *out = fe.snapshot;
}

static const uint8_t PIN[6] = {'4', '8', '2', '9', '1', '5'};

static void setup_env(const char *file)
{
    memset(&fe, 0, sizeof fe);
    vec_load(file);
    memcpy(fe.device_id, vec("device_id", NULL), 16);
    memcpy(fe.client_id, vec("client_id", NULL), 16);
    memset(fe.k_setup, 0x77, 32);
    if (vec_has("k_client")) memcpy(fe.k_client, vec("k_client", NULL), 32);
    fe.window = true;
    fe.snapshot = (brama_snapshot_t){1, BRAMA_PHASE_DISARMED, BRAMA_OWNER_NONE, BRAMA_REASON_NONE, 0};
    rng_push(vec("session_id", NULL), 8);
    rng_push(vec("server_nonce", NULL), 32);
    env = (brama_session_env_t){
        .device_id = fe.device_id, .k_setup = fe.k_setup, .pin = PIN,
        .rng = {rng_fill, NULL}, .client_key = client_key, .pair_admit = pair_admit, .session_admit = session_admit,
        .window_open = window_open, .attempt_blocker = attempt_blocker, .snapshot_for = snapshot_for, .ctx = NULL};
    brama_session_init(&ss);
}

static void send_vec(const char *key)
{
    size_t len;
    const uint8_t *f = vec(key, &len);
    brama_session_on_frame(&ss, &env, f, len, &step);
}

static void expect_reply_vec(const char *key)
{
    size_t len;
    const uint8_t *f = vec(key, &len);
    TEST_ASSERT_EQUAL_MESSAGE(len, step.reply_len, key);
    TEST_ASSERT_EQUAL_HEX8_ARRAY_MESSAGE(f, step.reply, len, key);
}

static void expect_reject(uint8_t code)
{
    TEST_ASSERT_EQUAL(23, step.reply_len);
    TEST_ASSERT_EQUAL_HEX8(BRAMA_MSG_HANDSHAKE_REJECT, step.reply[3]);
    TEST_ASSERT_EQUAL_HEX8(code, step.reply[22]);
}

static void establish_session(const char *file)
{
    setup_env(file);
    fe.client_known = true;
    send_vec("c2s_1");
    expect_reply_vec("s2c_1");
    send_vec("c2s_2");
    expect_reply_vec("s2c_2");
    TEST_ASSERT_EQUAL(BRAMA_EV_SESSION_READY, step.event);
    TEST_ASSERT_TRUE(brama_session_ready(&ss));
}

// --- Testy ----------------------------------------------------------------------------------------------------------

static void session_handshake_matches_vectors(void)
{
    establish_session("vectors/session.txt");
    TEST_ASSERT_EQUAL_HEX8_ARRAY(fe.client_id, step.client_id, 16);
}

static void commands_match_vectors(void)
{
    establish_session("vectors/commands.txt");
    const char *pairs[][2] = {{"c2s_3", "s2c_3"}, {"c2s_4", "s2c_4"}, {"c2s_5", "s2c_5"}};
    for (int i = 0; i < 3; i++) {
        send_vec(pairs[i][0]);
        TEST_ASSERT_EQUAL(BRAMA_EV_COMMAND, step.event);
        char pk[24];
        snprintf(pk, sizeof pk, "%s_plain", pairs[i][0]);
        size_t pl;
        const uint8_t *plain = vec(pk, &pl);
        TEST_ASSERT_EQUAL(pl, step.payload_len);
        if (pl) TEST_ASSERT_EQUAL_HEX8_ARRAY(plain, step.payload, pl);
        snprintf(pk, sizeof pk, "%s_plain", pairs[i][1]);
        const uint8_t *resp = vec(pk, &pl);
        size_t rlen;
        const uint8_t *expected = vec(pairs[i][1], &rlen);
        uint8_t out[BRAMA_FRAME_MAX];
        size_t olen;
        uint8_t type = expected[3];
        TEST_ASSERT_EQUAL(BRAMA_OK, brama_session_respond(&ss, step.seq, type, resp, pl, out, &olen));
        TEST_ASSERT_EQUAL(rlen, olen);
        TEST_ASSERT_EQUAL_HEX8_ARRAY(expected, out, olen);
    }
    // RULE_STATUS bez żądania, potem CLOSE i jego ACK
    size_t pl, rlen, olen;
    const uint8_t *st = vec("s2c_6_plain", &pl);
    const uint8_t *expected = vec("s2c_6", &rlen);
    uint8_t out[BRAMA_FRAME_MAX];
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_session_seal_event(&ss, BRAMA_MSG_RULE_STATUS, st, pl, out, &olen));
    TEST_ASSERT_EQUAL_HEX8_ARRAY(expected, out, rlen);
    send_vec("c2s_6");
    TEST_ASSERT_EQUAL(BRAMA_EV_COMMAND, step.event);
    TEST_ASSERT_EQUAL_HEX8(BRAMA_MSG_CLOSE, step.type);
    const uint8_t *ack = vec("s2c_7_plain", &pl);
    expected = vec("s2c_7", &rlen);
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_session_respond(&ss, step.seq, BRAMA_MSG_COMMAND_ACK, ack, pl, out, &olen));
    TEST_ASSERT_EQUAL_HEX8_ARRAY(expected, out, rlen);
    // OPEN_PAIRING_WINDOW (0x03, 0 B) i ACK WINDOW_OPENED (phone_window_design §3)
    send_vec("c2s_7");
    TEST_ASSERT_EQUAL(BRAMA_EV_COMMAND, step.event);
    TEST_ASSERT_EQUAL_HEX8(BRAMA_MSG_OPEN_PAIRING_WINDOW, step.type);
    TEST_ASSERT_EQUAL(0, step.payload_len);
    ack = vec("s2c_8_plain", &pl);
    TEST_ASSERT_EQUAL_HEX8(BRAMA_ACK_WINDOW_OPENED, ack[1]);
    expected = vec("s2c_8", &rlen);
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_session_respond(&ss, step.seq, BRAMA_MSG_COMMAND_ACK, ack, pl, out, &olen));
    TEST_ASSERT_EQUAL_HEX8_ARRAY(expected, out, rlen);
}

static void pair_qr_handshake_matches_vectors(void)
{
    setup_env("vectors/pair_qr.txt");
    memcpy(fe.k_setup, vec("k_handshake", NULL), 32);
    rng_push(vec("k_client", NULL), 32);
    send_vec("c2s_1");
    expect_reply_vec("s2c_1");
    send_vec("c2s_2");
    expect_reply_vec("s2c_2"); // PAIR_GRANT z K_client
    send_vec("c2s_3");
    TEST_ASSERT_EQUAL(BRAMA_EV_PAIR_COMMIT, step.event);
    TEST_ASSERT_EQUAL(0, step.reply_len);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(vec("k_client", NULL), step.k_client, 32);
    TEST_ASSERT_FALSE(step.via_pin);
    uint8_t out[BRAMA_FRAME_MAX];
    size_t olen, rlen;
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_session_pair_finish(&ss, true, out, &olen));
    const uint8_t *done = vec("s2c_3", &rlen);
    TEST_ASSERT_EQUAL(rlen, olen);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(done, out, olen);
    // identyczny COMMIT po zatwierdzeniu dostaje zachowany DONE, bez nowego zdarzenia
    send_vec("c2s_3");
    TEST_ASSERT_EQUAL(BRAMA_EV_NONE, step.event);
    expect_reply_vec("s2c_3");
}

static void duplicate_frame_returns_cached_response(void)
{
    establish_session("vectors/commands.txt");
    // identyczny BEGIN i CONFIRM po READY dostają zachowane CHALLENGE/READY (13.4)
    send_vec("c2s_2");
    expect_reply_vec("s2c_2");
    TEST_ASSERT_EQUAL(BRAMA_EV_NONE, step.event);
    send_vec("c2s_3");
    size_t pl, olen;
    const uint8_t *ack = vec("s2c_3_plain", &pl);
    uint8_t out[BRAMA_FRAME_MAX];
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_session_respond(&ss, step.seq, BRAMA_MSG_COMMAND_ACK, ack, pl, out, &olen));
    send_vec("c2s_3");
    TEST_ASSERT_EQUAL(BRAMA_EV_NONE, step.event); // bez ponownego wykonania
    expect_reply_vec("s2c_3");
    TEST_ASSERT_TRUE(brama_session_ready(&ss));
}

static void duplicate_list_clients_is_executed_again_instead_of_invalidating(void)
{
    establish_session("vectors/commands.txt");
    // seq 1–6: OPEN, OPEN, PING, CLOSE, OPEN_PAIRING_WINDOW, SET_CLIENT_NAME (odpowiedzi małe, w cache)
    const char *early[] = {"c2s_3", "c2s_4", "c2s_5", "c2s_6", "c2s_7", "c2s_8"};
    uint8_t out[BRAMA_FRAME_MAX], ack[25] = {0};
    size_t olen;
    for (int i = 0; i < 6; i++) {
        send_vec(early[i]);
        TEST_ASSERT_EQUAL(BRAMA_EV_COMMAND, step.event);
        TEST_ASSERT_EQUAL(BRAMA_OK, brama_session_respond(&ss, step.seq, BRAMA_MSG_COMMAND_ACK, ack, sizeof ack, out, &olen));
    }
    size_t pl;
    const uint8_t *list = vec("s2c_10_plain", &pl);
    send_vec("c2s_9"); // LIST_CLIENTS, seq 7
    TEST_ASSERT_EQUAL(BRAMA_EV_COMMAND, step.event);
    TEST_ASSERT_EQUAL(BRAMA_MSG_LIST_CLIENTS, step.type);
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_session_respond(&ss, step.seq, BRAMA_MSG_CLIENT_LIST, list, pl, out, &olen));
    TEST_ASSERT_TRUE(olen > 64); // nie mieści się w cache odpowiedzi (13.4)
    // retransmisja identycznego żądania po T_response: bezstanowy odczyt wykonywany ponownie, bez unieważnienia sesji
    send_vec("c2s_9");
    TEST_ASSERT_EQUAL(BRAMA_EV_COMMAND, step.event);
    TEST_ASSERT_EQUAL(BRAMA_MSG_LIST_CLIENTS, step.type);
    TEST_ASSERT_EQUAL(7, step.seq);
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_session_respond(&ss, step.seq, BRAMA_MSG_CLIENT_LIST, list, pl, out, &olen));
    TEST_ASSERT_TRUE(brama_session_ready(&ss));
    // odpowiedź jeszcze w toku: kolejny duplikat jest ignorowany, nie wykonywany trzeci raz
    send_vec("c2s_9");
    TEST_ASSERT_EQUAL(BRAMA_EV_COMMAND, step.event);
    send_vec("c2s_9");
    TEST_ASSERT_EQUAL(BRAMA_EV_NONE, step.event);
    TEST_ASSERT_TRUE(brama_session_ready(&ss));
}

static void duplicate_of_other_large_response_still_invalidates(void)
{
    // Wyjątek dotyczy wyłącznie LIST_CLIENTS: inne żądania bez zachowanej odpowiedzi nadal unieważniają sesję.
    establish_session("vectors/commands.txt");
    uint8_t out[BRAMA_FRAME_MAX], ack[25] = {0};
    size_t olen;
    send_vec("c2s_3");
    brama_session_respond(&ss, step.seq, BRAMA_MSG_COMMAND_ACK, ack, sizeof ack, out, &olen);
    for (int i = 0; i < 40; i++) { // wypchnięcie wpisu z cache (16 pozycji)
        brama_session_t *p = &ss;
        p->cache[i % BRAMA_SESSION_CACHE].used = false;
    }
    send_vec("c2s_3");
    TEST_ASSERT_EQUAL(BRAMA_EV_INVALIDATED, step.event);
}

static void seq_gap_invalidates_session(void)
{
    establish_session("vectors/commands.txt");
    send_vec("c2s_4"); // seq 2 bez seq 1
    TEST_ASSERT_EQUAL(BRAMA_EV_INVALIDATED, step.event);
    TEST_ASSERT_EQUAL(0, step.reply_len);
    TEST_ASSERT_FALSE(brama_session_ready(&ss));
}

static void changed_frame_with_same_seq_invalidates(void)
{
    establish_session("vectors/commands.txt");
    send_vec("c2s_3");
    uint8_t out[BRAMA_FRAME_MAX];
    size_t olen, len;
    uint8_t ack[25] = {0};
    brama_session_respond(&ss, step.seq, BRAMA_MSG_COMMAND_ACK, ack, 25, out, &olen);
    uint8_t f[64];
    const uint8_t *src_f = vec("c2s_3", &len);
    memcpy(f, src_f, len);
    f[len - 1] ^= 1; // ten sam seq, inne bajty (zły tag)
    brama_session_on_frame(&ss, &env, f, len, &step);
    TEST_ASSERT_EQUAL(BRAMA_EV_INVALIDATED, step.event);
}

static void unknown_client_and_bad_hmac_are_auth_failed(void)
{
    setup_env("vectors/session.txt");
    fe.client_known = false;
    send_vec("c2s_1");
    expect_reject(BRAMA_REJECT_AUTH_FAILED);
    TEST_ASSERT_TRUE(step.auth_failure);
    fe.client_known = true;
    fe.k_client[0] ^= 1;
    send_vec("c2s_1");
    expect_reject(BRAMA_REJECT_AUTH_FAILED);
}

static void reject_codes_follow_disclosure_rules(void)
{
    // Kod szczegółowy (WINDOW_CLOSED/NO_CAPACITY) tylko po poprawnym HMAC; inaczej AUTH_FAILED (12.4).
    setup_env("vectors/pair_qr.txt");
    memcpy(fe.k_setup, vec("k_handshake", NULL), 32);
    fe.admit = BRAMA_REJECT_WINDOW_CLOSED;
    send_vec("c2s_1");
    expect_reject(BRAMA_REJECT_WINDOW_CLOSED);
    fe.admit = BRAMA_REJECT_NO_CAPACITY;
    send_vec("c2s_1");
    expect_reject(BRAMA_REJECT_NO_CAPACITY);
    fe.k_setup[0] ^= 1;
    send_vec("c2s_1");
    expect_reject(BRAMA_REJECT_AUTH_FAILED);
    // UNAVAILABLE/RATE_LIMITED zawsze dopuszczalne, przed weryfikacją
    fe.blocker = BRAMA_REJECT_RATE_LIMITED;
    send_vec("c2s_1");
    expect_reject(BRAMA_REJECT_RATE_LIMITED);
}

static void pair_confirm_without_begin_is_auth_failed(void)
{
    setup_env("vectors/pair_qr.txt");
    send_vec("c2s_2");
    expect_reject(BRAMA_REJECT_AUTH_FAILED);
}

static void ready_session_ignores_bad_begin(void)
{
    establish_session("vectors/commands.txt");
    fe.k_client[0] ^= 1; // BEGIN z kluczem, który już nie pasuje
    uint8_t f[128];
    size_t len;
    const uint8_t *src_f = vec("c2s_1", &len);
    memcpy(f, src_f, len);
    f[30] ^= 1; // inne client_nonce → nie jest duplikatem
    brama_session_on_frame(&ss, &env, f, len, &step);
    TEST_ASSERT_EQUAL(0, step.reply_len); // bez odmowy na działającym łączu (12.4)
    TEST_ASSERT_TRUE(brama_session_ready(&ss));
}

static void rekey_begin_on_ready_reports_invalidation(void)
{
    // I1: nowy poprawny SESSION_BEGIN na READY zastępuje sesję — aktor musi się o tym dowiedzieć.
    establish_session("vectors/session.txt");
    uint8_t f[128];
    size_t len;
    const uint8_t *src = vec("c2s_1", &len);
    memcpy(f, src, len);
    f[40] ^= 1; // inny client_nonce (bajty 38–69) → nowa próba, nie duplikat
    // poprawny HMAC dla zmienionych bajtów: przelicz tag kluczem klienta
    uint8_t mac_in[22 + 48 + 16];
    memcpy(mac_in, f, 70);
    memcpy(mac_in + 70, fe.device_id, 16);
    brama_mac(fe.k_client, "BRAMA/2/SESSION_BEGIN", mac_in, sizeof mac_in, f + 70);
    brama_session_on_frame(&ss, &env, f, len, &step);
    TEST_ASSERT_EQUAL_HEX8(BRAMA_MSG_SESSION_CHALLENGE, step.reply[3]);
    TEST_ASSERT_EQUAL(BRAMA_EV_INVALIDATED, step.event);
    TEST_ASSERT_FALSE(brama_session_ready(&ss));
}

// --- Naprawa bondu (9.5) --------------------------------------------------------------------------------------------

static void repair_withholds_ready_until_commit(void)
{
    setup_env("vectors/session.txt");
    fe.client_known = true;
    fe.repair = true;
    send_vec("c2s_1");
    expect_reply_vec("s2c_1");
    send_vec("c2s_2");
    TEST_ASSERT_EQUAL(BRAMA_EV_REPAIR_COMMIT, step.event);
    TEST_ASSERT_EQUAL(0, step.reply_len); // SESSION_READY dopiero po commit
    TEST_ASSERT_EQUAL_HEX8_ARRAY(fe.client_id, step.client_id, 16);
    TEST_ASSERT_FALSE(brama_session_ready(&ss));
    send_vec("c2s_2"); // identyczny CONFIRM w trakcie transakcji: bez odpowiedzi i zdarzenia
    TEST_ASSERT_EQUAL(BRAMA_EV_NONE, step.event);
    TEST_ASSERT_EQUAL(0, step.reply_len);
    uint8_t out[BRAMA_FRAME_MAX];
    size_t olen, rlen;
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_session_repair_finish(&ss, &env, 0, out, &olen));
    const uint8_t *ready = vec("s2c_2", &rlen);
    TEST_ASSERT_EQUAL(rlen, olen);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(ready, out, olen);
    TEST_ASSERT_TRUE(brama_session_ready(&ss));
    send_vec("c2s_2"); // po READY identyczny CONFIRM dostaje zachowane READY
    expect_reply_vec("s2c_2");
    TEST_ASSERT_EQUAL(BRAMA_EV_NONE, step.event);
    TEST_ASSERT_EQUAL(BRAMA_ERR_STATE, brama_session_repair_finish(&ss, &env, 0, out, &olen));
}

static void repair_failure_rejects_with_code(void)
{
    setup_env("vectors/session.txt");
    fe.client_known = true;
    fe.repair = true;
    send_vec("c2s_1");
    send_vec("c2s_2");
    uint8_t out[BRAMA_FRAME_MAX];
    size_t olen;
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_session_repair_finish(&ss, &env, BRAMA_REJECT_STORAGE_FAILURE, out, &olen));
    TEST_ASSERT_EQUAL(23, olen);
    TEST_ASSERT_EQUAL_HEX8(BRAMA_MSG_HANDSHAKE_REJECT, out[3]);
    TEST_ASSERT_EQUAL_HEX8(BRAMA_REJECT_STORAGE_FAILURE, out[22]);
    brama_frame_t f;
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_frame_decode(out, olen, &f));
    TEST_ASSERT_EQUAL(brama_get_le(vec("session_id", NULL), 8), f.h.session_id); // session_id próby (12.4)
    TEST_ASSERT_FALSE(brama_session_ready(&ss));
    TEST_ASSERT_EQUAL(BRAMA_ERR_STATE, brama_session_repair_finish(&ss, &env, 0, out, &olen));
}

static void session_admit_codes_only_after_valid_hmac(void)
{
    // WINDOW_CLOSED/REPLACEMENT_BLOCKED tylko po poprawnym HMAC K_client (12.4).
    setup_env("vectors/session.txt");
    fe.client_known = true;
    fe.session_admit = BRAMA_REJECT_WINDOW_CLOSED;
    send_vec("c2s_1");
    expect_reject(BRAMA_REJECT_WINDOW_CLOSED);
    TEST_ASSERT_FALSE(step.auth_failure);
    fe.session_admit = BRAMA_REJECT_REPLACEMENT_BLOCKED;
    send_vec("c2s_1");
    expect_reject(BRAMA_REJECT_REPLACEMENT_BLOCKED);
    int admits = fe.session_admits;
    fe.k_client[0] ^= 1;
    send_vec("c2s_1");
    expect_reject(BRAMA_REJECT_AUTH_FAILED);
    TEST_ASSERT_EQUAL(admits, fe.session_admits);
}

static void ordinary_session_is_not_a_repair(void)
{
    establish_session("vectors/session.txt"); // repair = false: READY od razu (bez zmian)
    TEST_ASSERT_EQUAL(1, fe.session_admits);
}

// --- PIN: klient mbedTLS ↔ brama_session -----------------------------------------------------------------------------

static int test_rng(void *ctx, unsigned char *out, size_t len)
{
    (void)ctx;
    return psa_generate_random(out, len) == PSA_SUCCESS ? 0 : -1;
}

static size_t frame_no_tag(uint8_t type, uint64_t sid, const uint8_t *p, size_t pl, uint8_t *out)
{
    brama_frame_header_t h = {type, sid, 0, (uint16_t)pl};
    brama_header_encode(&h, out);
    memcpy(out + 22, p, pl);
    return 22 + pl;
}

static size_t pair_begin(const uint8_t key[32], uint8_t *out)
{
    uint8_t body[48], mac_in[22 + 48 + 16];
    memcpy(body, fe.client_id, 16);
    memset(body + 16, 0x3C, 32);
    brama_frame_header_t h = {BRAMA_MSG_PAIR_BEGIN, 0, 0, 48};
    brama_header_encode(&h, out);
    memcpy(out + 22, body, 48);
    memcpy(mac_in, out, 70);
    memcpy(mac_in + 70, fe.device_id, 16);
    brama_mac(key, "BRAMA/2/PAIR_BEGIN", mac_in, sizeof mac_in, out + 70);
    return 102;
}

/** PAKE z klientem mbedTLS; zwraca K_pin klienta (poprawny, gdy client_pin == PIN sterownika). */
static void run_pake(const uint8_t *client_pin, uint8_t k_pin[32])
{
    mbedtls_ecjpake_context cli;
    mbedtls_ecjpake_init(&cli);
    TEST_ASSERT_EQUAL(0, mbedtls_ecjpake_setup(&cli, MBEDTLS_ECJPAKE_CLIENT, MBEDTLS_MD_SHA256, MBEDTLS_ECP_DP_SECP256R1, client_pin, 6));
    uint8_t r[512], f[600], pt[1100];
    size_t rl, fl, ptl = 0;
    TEST_ASSERT_EQUAL(0, mbedtls_ecjpake_write_round_one(&cli, r, sizeof r, &rl, test_rng, NULL));
    memcpy(pt + ptl, r, rl), ptl += rl;
    fl = frame_no_tag(BRAMA_MSG_PAKE_ROUND1, 0, r, rl, f);
    brama_session_on_frame(&ss, &env, f, fl, &step);
    TEST_ASSERT_EQUAL_HEX8_MESSAGE(BRAMA_MSG_PAKE_ROUND1_REPLY, step.reply[3], step.reply_len == 23 ? (step.reply[22] == 1 ? "AUTH_FAILED" : "inny kod") : "?");
    brama_frame_t rf;
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_frame_decode(step.reply, step.reply_len, &rf));
    uint64_t sid = rf.h.session_id;
    TEST_ASSERT_TRUE(sid != 0);
    memcpy(pt + ptl, rf.body, rf.h.payload_len), ptl += rf.h.payload_len;
    TEST_ASSERT_EQUAL(0, mbedtls_ecjpake_read_round_one(&cli, rf.body, rf.h.payload_len));
    TEST_ASSERT_EQUAL(0, mbedtls_ecjpake_write_round_two(&cli, r, sizeof r, &rl, test_rng, NULL));
    memcpy(pt + ptl, r, rl), ptl += rl;
    fl = frame_no_tag(BRAMA_MSG_PAKE_ROUND2, sid, r, rl, f);
    brama_session_on_frame(&ss, &env, f, fl, &step);
    TEST_ASSERT_EQUAL_HEX8(BRAMA_MSG_PAKE_ROUND2_REPLY, step.reply[3]);
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_frame_decode(step.reply, step.reply_len, &rf));
    TEST_ASSERT_EQUAL(sid, rf.h.session_id);
    memcpy(pt + ptl, rf.body, rf.h.payload_len), ptl += rf.h.payload_len;
    TEST_ASSERT_EQUAL(0, mbedtls_ecjpake_read_round_two(&cli, rf.body, rf.h.payload_len));
    uint8_t s[32];
    size_t sl;
    TEST_ASSERT_EQUAL(0, mbedtls_ecjpake_derive_secret(&cli, s, sizeof s, &sl, test_rng, NULL));
    brama_pin_setup_key(s, pt, ptl, k_pin);
    mbedtls_ecjpake_free(&cli);
}

static void pair_pin_with_pake_reaches_challenge(void)
{
    setup_env("vectors/session.txt");
    fe.rng_len = 0; // losowość serwera z PSA (rundy) i z bufora domyślnego
    uint8_t k_pin[32], f[128];
    run_pake(PIN, k_pin);
    size_t fl = pair_begin(k_pin, f);
    brama_session_on_frame(&ss, &env, f, fl, &step);
    TEST_ASSERT_EQUAL_HEX8(BRAMA_MSG_PAIR_CHALLENGE, step.reply[3]);
}

static void k_pin_is_single_use(void)
{
    setup_env("vectors/session.txt");
    fe.rng_len = 0;
    uint8_t k_pin[32], f[128];
    run_pake((const uint8_t *)"111111", k_pin); // zły PIN: PAKE przechodzi, klucze różne
    size_t fl = pair_begin(k_pin, f);
    brama_session_on_frame(&ss, &env, f, fl, &step);
    expect_reject(BRAMA_REJECT_AUTH_FAILED);
    TEST_ASSERT_TRUE(step.pin_failure);
    // ponowny BEGIN tym samym kluczem: K_pin usunięty, więc weryfikacja K_setup — też AUTH_FAILED
    brama_session_on_frame(&ss, &env, f, fl, &step);
    expect_reject(BRAMA_REJECT_AUTH_FAILED);
    TEST_ASSERT_FALSE(step.pin_failure);
}

static void pake_outside_window_is_auth_failed(void)
{
    setup_env("vectors/session.txt");
    fe.window = false;
    vec_load("ecjpake/interop.txt");
    size_t len;
    const uint8_t *r1 = vec("cli_one", &len);
    uint8_t f[600];
    size_t fl = frame_no_tag(BRAMA_MSG_PAKE_ROUND1, 0, r1, len, f);
    brama_session_on_frame(&ss, &env, f, fl, &step);
    expect_reject(BRAMA_REJECT_AUTH_FAILED);
}

static void pake_bad_proof_is_auth_failed_and_counted(void)
{
    setup_env("vectors/session.txt");
    vec_load("ecjpake/interop.txt");
    size_t len;
    uint8_t r1[400];
    const uint8_t *src_r1 = vec("cli_one", &len);
    memcpy(r1, src_r1, len);
    r1[len - 1] ^= 1;
    uint8_t f[600];
    size_t fl = frame_no_tag(BRAMA_MSG_PAKE_ROUND1, 0, r1, len, f);
    brama_session_on_frame(&ss, &env, f, fl, &step);
    expect_reject(BRAMA_REJECT_AUTH_FAILED);
    TEST_ASSERT_TRUE(step.auth_failure);
}

void run_session_tests(void)
{
    RUN_TEST(session_handshake_matches_vectors);
    RUN_TEST(commands_match_vectors);
    RUN_TEST(pair_qr_handshake_matches_vectors);
    RUN_TEST(duplicate_frame_returns_cached_response);
    RUN_TEST(duplicate_list_clients_is_executed_again_instead_of_invalidating);
    RUN_TEST(duplicate_of_other_large_response_still_invalidates);
    RUN_TEST(seq_gap_invalidates_session);
    RUN_TEST(changed_frame_with_same_seq_invalidates);
    RUN_TEST(unknown_client_and_bad_hmac_are_auth_failed);
    RUN_TEST(reject_codes_follow_disclosure_rules);
    RUN_TEST(pair_confirm_without_begin_is_auth_failed);
    RUN_TEST(ready_session_ignores_bad_begin);
    RUN_TEST(rekey_begin_on_ready_reports_invalidation);
    RUN_TEST(repair_withholds_ready_until_commit);
    RUN_TEST(repair_failure_rejects_with_code);
    RUN_TEST(session_admit_codes_only_after_valid_hmac);
    RUN_TEST(ordinary_session_is_not_a_repair);
    RUN_TEST(pair_pin_with_pake_reaches_challenge);
    RUN_TEST(k_pin_is_single_use);
    RUN_TEST(pake_outside_window_is_auth_failed);
    RUN_TEST(pake_bad_proof_is_auth_failed_and_counted);
}
