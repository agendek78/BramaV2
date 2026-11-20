#include <string.h>
#include "brama_actor.h"
#include "brama_err.h"

static int64_t now_us(const brama_actor_t *a)
{
    return a->env.clock.now_us(a->env.clock.ctx);
}

void brama_actor_init(brama_actor_t *a, const brama_actor_env_t *env, const brama_image_t *image,
                      const brama_localcfg_t *cfg, const brama_rule_t *rule, bool control_enabled)
{
    memset(a, 0, sizeof *a);
    a->env = *env;
    a->image = *image;
    a->cfg = *cfg;
    a->rule = *rule;
    a->control_enabled = control_enabled;
    a->reset_restores_control = true;
    brama_auth_limits_init(&a->limits);
}

bool brama_actor_post(brama_actor_t *a, const brama_actor_msg_t *m)
{
    if (a->count == BRAMA_ACTOR_QUEUE) return false;
    if (m->type == BRAMA_AMSG_BUTTON_RESET) {
        // Reset ma pierwszeństwo przed nierozpoczętymi operacjami z kolejki (9.6).
        a->head = (a->head + BRAMA_ACTOR_QUEUE - 1) % BRAMA_ACTOR_QUEUE;
        a->queue[a->head] = *m;
    } else {
        a->queue[(a->head + a->count) % BRAMA_ACTOR_QUEUE] = *m;
    }
    a->count++;
    return true;
}

bool brama_actor_busy(const brama_actor_t *a, int64_t t)
{
    if (!a->pulsed) return false;
    int64_t gap_ms = a->cfg.pulse_ms + BRAMA_PULSE_REST_MS;
    if (gap_ms < BRAMA_PULSE_GAP_MIN_MS) gap_ms = BRAMA_PULSE_GAP_MIN_MS;
    return t < a->last_pulse_start_us + gap_ms * 1000;
}

bool brama_actor_client_key(const brama_actor_t *a, const uint8_t client_id[16], uint8_t key_out[32])
{
    int i = brama_image_find(&a->image, client_id);
    if (i < 0) return false;
    memcpy(key_out, a->image.clients[i].k_client, 32);
    return true;
}

bool brama_actor_client_key_for_peer(const brama_actor_t *a, const uint8_t client_id[16], const uint8_t peer[7],
                                     uint8_t key_out[32])
{
    // Jeden zatwierdzony bond — jeden klient (9.5); inny client_id nie przejmuje sesji cudzego łącza (12.1).
    int i = brama_image_find(&a->image, client_id);
    if (i < 0) return false;
    const brama_bond_t *b = &a->image.clients[i].bond;
    if (!b->len || b->id_addr_type != peer[0] || memcmp(b->id_addr, peer + 1, 6) != 0) return false;
    memcpy(key_out, a->image.clients[i].k_client, 32);
    return true;
}

static bool same_identity(const brama_bond_t *a, const brama_bond_t *b)
{
    return a->len && b->len && a->id_addr_type == b->id_addr_type && memcmp(a->id_addr, b->id_addr, 6) == 0;
}

uint8_t brama_actor_repair_blocker(const brama_actor_t *a, int link, const uint8_t client_id[16], const brama_bond_t *bond)
{
    for (int i = 0; i < BRAMA_MAX_LINKS; i++) {
        const brama_link_t *l = &a->links[i];
        if (i != link && l->used && l->authenticated && memcmp(l->client_id, client_id, 16) == 0) {
            return BRAMA_REJECT_REPLACEMENT_BLOCKED; // dotychczasowe łącze klienta nadal działa (9.5, A57)
        }
    }
    for (int i = 0; i < a->image.count; i++) {
        // Jeden zatwierdzony bond — jeden klient: naprawa nie przejmuje tożsamości innego ACTIVE (decyzja projektu).
        if (memcmp(a->image.clients[i].client_id, client_id, 16) != 0 && same_identity(&a->image.clients[i].bond, bond)) {
            return BRAMA_REJECT_REPLACEMENT_BLOCKED;
        }
    }
    return 0;
}

brama_snapshot_t brama_actor_snapshot_for(const brama_actor_t *a, const uint8_t client_id[16])
{
    return brama_rule_snapshot_for(&a->rule, client_id, now_us(a));
}

int64_t brama_actor_next_deadline(const brama_actor_t *a)
{
    int64_t next = INT64_MAX;
    if (a->rule.close_kind != BRAMA_CLOSE_NONE) next = a->rule.deadline_us;
    if (a->close_wait_since_us) {
        int64_t gap_ms = a->cfg.pulse_ms + BRAMA_PULSE_REST_MS;
        if (gap_ms < BRAMA_PULSE_GAP_MIN_MS) gap_ms = BRAMA_PULSE_GAP_MIN_MS;
        int64_t free_at = a->last_pulse_start_us + gap_ms * 1000;
        int64_t give_up = a->close_wait_since_us + BRAMA_BUSY_WAIT_US + 1;
        int64_t t = free_at < give_up ? free_at : give_up;
        if (t < next) next = t;
    }
    if (a->limits.window_open && a->limits.window_until_ms * 1000 < next) next = a->limits.window_until_ms * 1000;
    return next;
}

// --- Publikacja ------------------------------------------------------------------------------------------------------

static void store_rtc(brama_actor_t *a)
{
    if (a->rule.rtc_frozen) return;
    brama_rtc_copy_t c;
    brama_rule_to_rtc(&a->rule, now_us(a), a->env.rtc_clock.now_us(a->env.rtc_clock.ctx), &c);
    a->env.rtc.store(a->env.rtc.ctx, &c, sizeof c);
}

/** RULE_STATUS do każdej sesji READY (owner_relation względem odbiorcy), poza `except` (dostaje ACK). */
static void broadcast(brama_actor_t *a, int except)
{
    for (int i = 0; i < BRAMA_MAX_LINKS; i++) {
        const brama_link_t *l = &a->links[i];
        if (!l->used || !l->ready || i == except) continue;
        brama_snapshot_t s = brama_actor_snapshot_for(a, l->client_id);
        uint8_t b[BRAMA_SNAPSHOT_LEN];
        brama_snapshot_encode(&s, b);
        a->env.send(a->env.ctx, i, l->session_token, 0, BRAMA_MSG_RULE_STATUS, b, sizeof b);
    }
}

/** Po zmianie reguły: kopia RTC w tej samej operacji co RAM i zdarzenie dla READY. */
static void publish(brama_actor_t *a, bool changed, int except)
{
    store_rtc(a);
    if (changed) broadcast(a, except);
}

static void drop_all_links(brama_actor_t *a)
{
    for (int i = 0; i < BRAMA_MAX_LINKS; i++) {
        if (a->links[i].used) a->env.disconnect(a->env.ctx, i);
        memset(&a->links[i], 0, sizeof a->links[i]);
    }
}

// --- Komendy (13.3) --------------------------------------------------------------------------------------------------

static bool link_current(const brama_actor_t *a, const brama_actor_msg_t *m)
{
    if (m->link < 0 || m->link >= BRAMA_MAX_LINKS) return false;
    const brama_link_t *l = &a->links[m->link];
    return l->used && l->ready && l->link_token == m->link_token && l->session_token == m->session_token
           && m->auth_generation == a->image.auth_generation && brama_image_find(&a->image, l->client_id) >= 0;
}

static void drop_links_of_removed_clients(brama_actor_t *a);

static void ack(brama_actor_t *a, const brama_actor_msg_t *m, uint8_t result)
{
    const brama_link_t *l = &a->links[m->link];
    brama_snapshot_t s = brama_actor_snapshot_for(a, l->client_id);
    uint8_t b[BRAMA_ACK_LEN];
    brama_ack_encode(m->cmd_type, result, m->seq, &s, b);
    a->env.send(a->env.ctx, m->link, l->session_token, m->seq, BRAMA_MSG_COMMAND_ACK, b, sizeof b);
}

static bool interlock_blocks(brama_actor_t *a)
{
    return a->cfg.interlock_enabled && a->env.outputs.interlock_active(a->env.outputs.ctx);
}

static bool is_ota(uint8_t type)
{
    return type == BRAMA_MSG_OTA_BEGIN || type == BRAMA_MSG_OTA_DATA || type == BRAMA_MSG_OTA_END
           || type == BRAMA_MSG_OTA_ABORT;
}

static void ota_ack(brama_actor_t *a, const brama_actor_msg_t *m, uint8_t result)
{
    const brama_link_t *l = &a->links[m->link];
    uint32_t next = brama_ota_owned_by(a->ota, l->session_token) ? a->ota->next_offset : 0;
    uint8_t b[BRAMA_OTA_ACK_LEN];
    brama_ota_ack_encode(m->cmd_type, result, m->seq, next, b);
    a->env.send(a->env.ctx, m->link, l->session_token, m->seq, BRAMA_MSG_OTA_ACK, b, sizeof b);
}

/** OTA (15.5): BEGIN i END wymagają reguły DISARMED — powrót do starszego obrazu mógłby unieważnić kopię RTC. */
static void on_ota(brama_actor_t *a, const brama_actor_msg_t *m)
{
    uint64_t token = a->links[m->link].session_token;
    bool disarmed = a->rule.phase == BRAMA_PHASE_DISARMED;
    int64_t t = now_us(a);
    switch (m->cmd_type) {
    case BRAMA_MSG_OTA_BEGIN: {
        uint32_t size;
        const uint8_t *sha;
        if (brama_ota_begin_decode(m->payload, m->payload_len, &size, &sha) != BRAMA_OK) {
            ota_ack(a, m, BRAMA_OTA_INVALID_ARGUMENT);
        } else if (!disarmed) {
            ota_ack(a, m, BRAMA_OTA_RULE_ACTIVE);
        } else {
            ota_ack(a, m, brama_ota_begin(a->ota, token, m->mtu, size, sha, t));
        }
        return;
    }
    case BRAMA_MSG_OTA_DATA: {
        uint32_t offset, next;
        const uint8_t *data;
        size_t len;
        brama_ota_result_t r = brama_ota_data_decode(m->payload, m->payload_len, &offset, &data, &len) == BRAMA_OK
                                   ? brama_ota_data(a->ota, token, offset, data, len, t, &next)
                                   : BRAMA_OTA_INVALID_ARGUMENT;
        ota_ack(a, m, r);
        return;
    }
    case BRAMA_MSG_OTA_END: {
        brama_ota_result_t r;
        if (m->payload_len != 0) r = BRAMA_OTA_INVALID_ARGUMENT;
        else if (!brama_ota_owned_by(a->ota, token)) r = BRAMA_OTA_NOT_IN_PROGRESS;
        else if (!disarmed) {
            // Przyjęte dane zostają; END można ponowić po rozbrojeniu — także po ponad 30 s od ostatniego DATA (A77).
            r = BRAMA_OTA_RULE_ACTIVE;
            brama_ota_touch(a->ota, token, t);
        } else {
            r = brama_ota_end(a->ota, token);
        }
        ota_ack(a, m, r);
        if (r == BRAMA_OTA_OK) {
            // Do restartu żadna komenda nie zmienia reguły: restart po aktualizacji zastaje DISARMED (15.5).
            a->control_enabled = false;
            a->reset_restores_control = false;
            if (a->env.ota_restart) a->env.ota_restart(a->env.ctx, m->link);
        }
        return;
    }
    default: {
        brama_ota_result_t r = BRAMA_OTA_NOT_IN_PROGRESS;
        if (m->payload_len != 0) {
            r = BRAMA_OTA_INVALID_ARGUMENT;
        } else if (brama_ota_owned_by(a->ota, token)) {
            brama_ota_abort(a->ota);
            r = BRAMA_OTA_OK;
        }
        ota_ack(a, m, r);
        return;
    }
    }
}

static void abort_ota_of(brama_actor_t *a, uint64_t session_token)
{
    if (a->ota && session_token && brama_ota_owned_by(a->ota, session_token)) brama_ota_abort(a->ota);
}

// --- Zarządzanie telefonami (client_management_design.md) ---------------------------------------------------------------

#define CLIENT_ENTRY_LEN 38

static bool ota_busy(const brama_actor_t *a) { return a->ota && a->ota->active; }

static void on_list_clients(brama_actor_t *a, const brama_actor_msg_t *m)
{
    if (m->payload_len != 0) {
        ack(a, m, BRAMA_ACK_INVALID_ARGUMENT);
        return;
    }
    const uint8_t *self = a->links[m->link].client_id;
    uint8_t b[8 + 1 + BRAMA_MAX_CLIENTS * CLIENT_ENTRY_LEN];
    brama_put_le(b, m->seq, 8);
    b[8] = (uint8_t)a->image.count;
    for (int i = 0; i < a->image.count; i++) {
        const brama_client_rec_t *c = &a->image.clients[i];
        uint8_t *e = b + 9 + i * CLIENT_ENTRY_LEN;
        uint8_t flags = 0;
        if (memcmp(c->client_id, self, 16) == 0) flags |= 1;
        for (int k = 0; k < BRAMA_MAX_LINKS; k++) {
            const brama_link_t *l = &a->links[k];
            if (l->used && l->ready && memcmp(l->client_id, c->client_id, 16) == 0) flags |= 2;
        }
        if (a->rule.has_owner && memcmp(a->rule.owner_id, c->client_id, 16) == 0) flags |= 4;
        memcpy(e, c->client_id, 16);
        e[16] = flags;
        e[17] = c->name_len;
        memcpy(e + 18, c->name, BRAMA_CLIENT_NAME_MAX);
    }
    a->env.send(a->env.ctx, m->link, a->links[m->link].session_token, m->seq, BRAMA_MSG_CLIENT_LIST, b,
                (size_t)(9 + a->image.count * CLIENT_ENTRY_LEN));
}

static void on_set_client_name(brama_actor_t *a, const brama_actor_msg_t *m)
{
    if (m->payload_len < 1 || m->payload[0] > BRAMA_CLIENT_NAME_MAX || m->payload_len != (size_t)1 + m->payload[0]
        || !brama_client_name_valid(m->payload + 1, m->payload[0])) {
        ack(a, m, BRAMA_ACK_INVALID_ARGUMENT);
        return;
    }
    if (ota_busy(a)) {
        ack(a, m, BRAMA_ACK_BUSY);
        return;
    }
    int idx = brama_image_find(&a->image, a->links[m->link].client_id);
    brama_image_t next;
    if (idx < 0 || brama_image_next(&a->image, &next) != BRAMA_OK) {
        ack(a, m, BRAMA_ACK_STORAGE_FAILURE);
        return;
    }
    brama_client_rec_t *c = &next.clients[idx];
    c->name_len = m->payload[0];
    memset(c->name, 0, sizeof c->name);
    memcpy(c->name, m->payload + 1, c->name_len);
    if (brama_image_commit(&a->env.nvs, &next) != BRAMA_OK) {
        ack(a, m, BRAMA_ACK_STORAGE_FAILURE);
    } else {
        a->image = next;
        ack(a, m, BRAMA_ACK_NAME_SET);
    }
    memset(&next, 0, sizeof next);
}

static void on_remove_client(brama_actor_t *a, const brama_actor_msg_t *m)
{
    if (m->payload_len != 16) {
        ack(a, m, BRAMA_ACK_INVALID_ARGUMENT);
        return;
    }
    if (ota_busy(a)) {
        ack(a, m, BRAMA_ACK_BUSY);
        return;
    }
    if (memcmp(m->payload, a->links[m->link].client_id, 16) == 0) {
        ack(a, m, BRAMA_ACK_CANNOT_REMOVE_SELF);
        return;
    }
    int idx = brama_image_find(&a->image, m->payload);
    if (idx < 0) {
        ack(a, m, BRAMA_ACK_CLIENT_NOT_FOUND);
        return;
    }
    if (a->rule.has_owner && memcmp(a->rule.owner_id, m->payload, 16) == 0) {
        ack(a, m, BRAMA_ACK_CLIENT_OWNS_RULE);
        return;
    }
    // auth_generation bez zmian: rule i sesje pozostałych telefonów trwają, a komendy usuwanego klienta odrzuca link_current.
    brama_image_t next;
    if (brama_image_next(&a->image, &next) != BRAMA_OK || brama_image_remove_client(&next, idx) != BRAMA_OK
        || brama_image_commit(&a->env.nvs, &next) != BRAMA_OK) {
        memset(&next, 0, sizeof next);
        ack(a, m, BRAMA_ACK_STORAGE_FAILURE);
        return;
    }
    brama_bond_t bond = a->image.clients[idx].bond;
    a->image = next;
    memset(&next, 0, sizeof next);
    ack(a, m, BRAMA_ACK_CLIENT_REMOVED); // najpierw odpowiedź dla żądającego, potem rozłączenie usuniętego
    if (bond.len && a->env.client_removed) a->env.client_removed(a->env.ctx, &bond);
    drop_links_of_removed_clients(a);
    memset(&bond, 0, sizeof bond);
}

static void on_command(brama_actor_t *a, const brama_actor_msg_t *m)
{
    // Nieaktualne tokeny, generacja albo control_enabled=false: bez impulsu i bez odpowiedzi (13.3, 15.2.1).
    if (!a->control_enabled || !link_current(a, m)) return;
    const brama_link_t *l = &a->links[m->link];
    if (is_ota(m->cmd_type)) {
        if (a->ota) on_ota(a, m);
        else ack(a, m, BRAMA_ACK_UNSUPPORTED_TYPE);
        return;
    }
    if (m->cmd_type == BRAMA_MSG_PING) {
        if (m->payload_len != 0) {
            ack(a, m, BRAMA_ACK_INVALID_ARGUMENT);
            return;
        }
        brama_snapshot_t s = brama_actor_snapshot_for(a, l->client_id);
        uint8_t b[BRAMA_SNAPSHOT_LEN];
        brama_snapshot_encode(&s, b);
        a->env.send(a->env.ctx, m->link, l->session_token, m->seq, BRAMA_MSG_PONG, b, sizeof b);
        return;
    }
    if (m->cmd_type == BRAMA_MSG_OPEN_PAIRING_WINDOW) {
        // To samo okno co przycisk 3 s (9.4), bez impulsu i zapisu NVS; licznik PIN-u wg brama_auth (phone_window_design).
        if (m->payload_len != 0) {
            ack(a, m, BRAMA_ACK_INVALID_ARGUMENT);
            return;
        }
        brama_auth_window_open_remote(&a->limits, now_us(a) / 1000);
        ack(a, m, BRAMA_ACK_WINDOW_OPENED);
        return;
    }
    if (m->cmd_type == BRAMA_MSG_LIST_CLIENTS) {
        on_list_clients(a, m);
        return;
    }
    if (m->cmd_type == BRAMA_MSG_SET_CLIENT_NAME) {
        on_set_client_name(a, m);
        return;
    }
    if (m->cmd_type == BRAMA_MSG_REMOVE_CLIENT) {
        on_remove_client(a, m);
        return;
    }
    bool open = m->cmd_type == BRAMA_MSG_OPEN, close = m->cmd_type == BRAMA_MSG_CLOSE;
    if (!open && !close) {
        ack(a, m, BRAMA_ACK_UNSUPPORTED_TYPE);
        return;
    }
    if ((open && (m->payload_len != 1 || m->payload[0] > 1)) || (close && m->payload_len != 0)) {
        ack(a, m, BRAMA_ACK_INVALID_ARGUMENT);
        return;
    }
    bool auto_close = open && m->payload[0] == 1;
    int64_t t = now_us(a);
    // Odmowy przed zmianą reguły, w kolejności z 13.3: BUSY, uprawnienie do reguły, interlock.
    if (brama_actor_busy(a, t)) {
        ack(a, m, BRAMA_ACK_BUSY);
        return;
    }
    if (open && !brama_rule_open_allowed(&a->rule, l->client_id)) {
        ack(a, m, BRAMA_ACK_LEASE_OWNED_BY_OTHER);
        return;
    }
    if (interlock_blocks(a)) {
        ack(a, m, BRAMA_ACK_INTERLOCK_BLOCKED);
        return;
    }
    brama_rule_t before = a->rule;
    bool changed = open ? brama_rule_apply_open(&a->rule, l->client_id, auto_close) : brama_rule_apply_close(&a->rule);
    a->close_wait_since_us = 0;
    store_rtc(a); // ARMED przed OPEN(true), DISARMED przed CLOSE/OPEN(false) (14.5)
    uint8_t result = BRAMA_ACK_OUTPUT_TRIGGERED;
    if (a->env.outputs.start_pulse(a->env.outputs.ctx, open ? BRAMA_OUTPUT_OPEN : BRAMA_OUTPUT_CLOSE, a->cfg.pulse_ms)) {
        a->pulsed = true;
        a->last_pulse_start_us = t;
    } else {
        changed |= brama_rule_io_fault_after(&a->rule, &before, m->cmd_type, auto_close);
        store_rtc(a);
        result = BRAMA_ACK_IO_FAULT;
    }
    ack(a, m, result);
    if (changed) broadcast(a, m->link);
}

// --- Łącza i reguła --------------------------------------------------------------------------------------------------

static void on_session_ready(brama_actor_t *a, const brama_actor_msg_t *m)
{
    if (!a->control_enabled || m->link < 0 || m->link >= BRAMA_MAX_LINKS) return;
    if (brama_image_find(&a->image, m->client_id) < 0) return;
    for (int i = 0; i < BRAMA_MAX_LINKS; i++) {
        // Najwyżej jedno łącze klienta: starsze traci status READY (14.3).
        if (i != m->link && a->links[i].ready && memcmp(a->links[i].client_id, m->client_id, 16) == 0) {
            a->links[i].ready = false;
        }
    }
    brama_link_t *l = &a->links[m->link];
    if (l->used && l->session_token != m->session_token) abort_ota_of(a, l->session_token); // nowa sesja na łączu
    l->used = true;
    l->ready = true;
    l->authenticated = true;
    l->link_token = m->link_token;
    l->session_token = m->session_token;
    memcpy(l->client_id, m->client_id, 16);
    bool changed = brama_rule_on_authenticated(&a->rule, m->client_id, now_us(a));
    publish(a, changed, -1);
}

static void on_disconnect(brama_actor_t *a, const brama_actor_msg_t *m)
{
    if (m->link < 0 || m->link >= BRAMA_MAX_LINKS) return;
    brama_link_t *l = &a->links[m->link];
    // Disconnect nieaktualnego uchwytu nie dotyczy nowego łącza (15.2.1).
    if (!l->used || l->link_token != m->link_token) return;
    bool was_authenticated = l->authenticated;
    abort_ota_of(a, l->session_token);
    uint8_t client[16];
    memcpy(client, l->client_id, 16);
    memset(l, 0, sizeof *l);
    if (!was_authenticated) return;
    for (int i = 0; i < BRAMA_MAX_LINKS; i++) {
        if (a->links[i].authenticated && memcmp(a->links[i].client_id, client, 16) == 0) return; // klient ma nowsze łącze
    }
    bool changed = brama_rule_on_owner_disconnected(&a->rule, client, now_us(a));
    publish(a, changed, -1);
}

/** Sesja unieważniona lub zastąpiona na tym samym łączu: zaległe operacje odrzucone, radio bez zmian (12.1). */
static void on_session_invalidated(brama_actor_t *a, const brama_actor_msg_t *m)
{
    if (m->link < 0 || m->link >= BRAMA_MAX_LINKS) return;
    brama_link_t *l = &a->links[m->link];
    if (!l->used || l->link_token != m->link_token) return;
    abort_ota_of(a, l->session_token);
    l->ready = false;
    l->session_token = 0;
}

static void on_interlock(brama_actor_t *a, const brama_actor_msg_t *m)
{
    a->interlock_active = m->interlock_active;
    if (m->interlock_active || !a->cfg.interlock_enabled) return;
    bool changed = brama_rule_on_interlock_cleared(&a->rule, now_us(a));
    if (changed) publish(a, true, -1);
}

static void on_tick(brama_actor_t *a)
{
    int64_t t = now_us(a);
    if (a->ota) brama_ota_tick(a->ota, t);
    if (a->limits.window_open && !brama_auth_window_is_open(&a->limits, t / 1000)) brama_auth_window_close(&a->limits);
    brama_close_kind_t kind = brama_rule_due(&a->rule, t);
    if (kind == BRAMA_CLOSE_NONE) return;
    // Auto-CLOSE wymaga control_enabled; wyjątki: próba po nieudanym resecie i w trybie błędu obrazu (15.2.1).
    if (kind == BRAMA_CLOSE_AUTO && !a->control_enabled) return;
    brama_close_outcome_t outcome;
    if (interlock_blocks(a)) {
        outcome = BRAMA_CLOSE_INTERLOCK;
    } else if (brama_actor_busy(a, t)) {
        if (!a->close_wait_since_us) a->close_wait_since_us = t;
        if (t - a->close_wait_since_us <= BRAMA_BUSY_WAIT_US) return; // czekamy ≤ 2 s na dozwolony początek (14.4)
        outcome = BRAMA_CLOSE_BUSY_TIMEOUT;
    } else {
        // DISARMED (RAM + RTC) przed impulsem, jak przy ręcznym CLOSE (14.1).
        brama_rule_t before = a->rule;
        bool changed = brama_rule_close_attempt_done(&a->rule, BRAMA_CLOSE_STARTED);
        store_rtc(a);
        a->close_wait_since_us = 0;
        if (a->env.outputs.start_pulse(a->env.outputs.ctx, BRAMA_OUTPUT_CLOSE, a->cfg.pulse_ms)) {
            a->pulsed = true;
            a->last_pulse_start_us = t;
            if (changed) broadcast(a, -1);
            return;
        }
        a->rule = before;
        outcome = BRAMA_CLOSE_IO_FAIL;
    }
    a->close_wait_since_us = 0;
    bool changed = brama_rule_close_attempt_done(&a->rule, outcome);
    publish(a, changed, -1);
}

// --- Autoryzacja (9.6, 11.3, 15.2.1) ---------------------------------------------------------------------------------

static void drop_links_of_removed_clients(brama_actor_t *a)
{
    for (int i = 0; i < BRAMA_MAX_LINKS; i++) {
        brama_link_t *l = &a->links[i];
        if (l->used && l->ready && brama_image_find(&a->image, l->client_id) < 0) {
            a->env.disconnect(a->env.ctx, i);
            memset(l, 0, sizeof *l);
        }
    }
}

static void on_pair_commit(brama_actor_t *a, const brama_actor_msg_t *m)
{
    bool ok = false;
    // Rejestracja bez bondu jest bezużyteczna (sesja wymaga zatwierdzonego bondu, 9.5) i zostawiałaby martwy wpis klienta.
    if (a->control_enabled && m->bond.len && m->auth_generation == a->image.auth_generation
        && brama_image_find(&a->image, m->client_id) < 0) {
        // Ponowna rejestracja na bond dawnego ACTIVE zastępuje wyłącznie ten rekord (9.5).
        int replaced = -1;
        for (int i = 0; i < a->image.count; i++) {
            if (same_identity(&a->image.clients[i].bond, &m->bond)) replaced = i;
        }
        bool blocked = replaced >= 0 && a->rule.has_owner
                       && memcmp(a->rule.owner_id, a->image.clients[replaced].client_id, 16) == 0;
        brama_image_t next;
        if (!blocked && brama_image_next(&a->image, &next) == BRAMA_OK) {
            if (replaced >= 0) brama_image_remove_client(&next, replaced);
            brama_client_rec_t c = {0}; // nazwa pusta; telefon ustawia ją po rejestracji (SET_CLIENT_NAME)
            memcpy(c.client_id, m->client_id, 16);
            memcpy(c.k_client, m->k_client, 32);
            c.bond = m->bond;
            if (brama_image_put_client(&next, &c) == BRAMA_OK && brama_image_commit(&a->env.nvs, &next) == BRAMA_OK) {
                a->image = next; // publikacja dopiero po potwierdzonym commit
                ok = true;
                drop_links_of_removed_clients(a);
            }
            memset(&c, 0, sizeof c);
        }
        memset(&next, 0, sizeof next);
    }
    a->env.pair_result(a->env.ctx, m->link, m->session_token, ok);
}

/**
 * Naprawa bondu (9.5): podmiana wyłącznie bondu klienta; K_client i reguła bez zmian (A42). Dowodem jest uwierzytelniony
 * SESSION_CONFIRM i aktualna generacja; commit nie zależy od tego, czy łącze naprawy jeszcze trwa — telefon ma już nowy
 * bond u siebie, a zachowanie starego zostawiłoby go bez działającego szyfrowania (decyzja projektu).
 */
static void on_repair_commit(brama_actor_t *a, const brama_actor_msg_t *m)
{
    uint8_t code;
    brama_bond_t old = {0};
    int i = brama_image_find(&a->image, m->client_id);
    if (!a->control_enabled) {
        code = BRAMA_REJECT_UNAVAILABLE;
    } else if (m->auth_generation != a->image.auth_generation || i < 0 || !m->bond.len) {
        code = BRAMA_REJECT_AUTH_FAILED; // dowód unieważniony resetem albo zastąpieniem poświadczenia
    } else {
        code = brama_actor_repair_blocker(a, m->link, m->client_id, &m->bond);
    }
    if (!code) {
        brama_image_t next;
        code = BRAMA_REJECT_STORAGE_FAILURE;
        if (brama_image_next(&a->image, &next) == BRAMA_OK) {
            old = a->image.clients[i].bond;
            next.clients[i].bond = m->bond;
            if (brama_image_commit(&a->env.nvs, &next) == BRAMA_OK) {
                a->image = next; // stary bond znika z publikacją obrazu
                code = 0;
            }
        }
        memset(&next, 0, sizeof next);
    }
    bool replaced = !code && old.len && !same_identity(&old, &m->bond);
    if (a->env.repair_result) a->env.repair_result(a->env.ctx, m->link, m->session_token, code, replaced ? &old : NULL);
    memset(&old, 0, sizeof old);
}

static void on_reset(brama_actor_t *a)
{
    a->control_enabled = false;
    if (a->env.outputs.stop_pulse) a->env.outputs.stop_pulse(a->env.outputs.ctx); // trwający impuls wyłączony (9.6)
    brama_auth_window_close(&a->limits);
    if (a->ota) brama_ota_abort(a->ota); // reset odcina wszystkie operacje, także transfer OTA
    a->close_wait_since_us = 0;
    brama_image_t next;
    int64_t t = now_us(a);
    bool ok = brama_image_reset(&a->image, &next) == BRAMA_OK && brama_image_commit(&a->env.nvs, &next) == BRAMA_OK;
    if (ok) {
        // Wpisy tożsamości usuniętych telefonów znikają z resolving list kontrolera (jak przy REMOVE_CLIENT).
        if (a->env.client_removed) {
            for (int i = 0; i < a->image.count; i++) {
                if (a->image.clients[i].bond.len) a->env.client_removed(a->env.ctx, &a->image.clients[i].bond);
            }
        }
        a->image = next;
        brama_rule_disarm(&a->rule, a->image.auth_generation);
        store_rtc(a);
        drop_all_links(a); // disconnect starych kontekstów nie tworzy WAITING — łącza już usunięte
        brama_auth_limits_init(&a->limits);
        // Reset naprawia tylko obraz autoryzacji; błąd konfiguracji lokalnej lub danych fabrycznych zostaje (14.5).
        a->control_enabled = a->reset_restores_control;
    } else {
        // Nieudany commit: zachowany obraz, FAULT/STORAGE_FAILURE przy uzbrojonej regule i jedna próba CLOSE (9.6).
        brama_rule_reset_failed(&a->rule, t);
        store_rtc(a);
        drop_all_links(a);
    }
    memset(&next, 0, sizeof next);
}

bool brama_actor_step(brama_actor_t *a)
{
    if (a->count == 0) return false;
    brama_actor_msg_t *m = &a->queue[a->head];
    a->head = (a->head + 1) % BRAMA_ACTOR_QUEUE;
    a->count--;
    switch (m->type) {
    case BRAMA_AMSG_COMMAND: on_command(a, m); break;
    case BRAMA_AMSG_SESSION_READY: on_session_ready(a, m); break;
    case BRAMA_AMSG_DISCONNECT: on_disconnect(a, m); break;
    case BRAMA_AMSG_PAIR_COMMIT: on_pair_commit(a, m); break;
    case BRAMA_AMSG_BUTTON_WINDOW:
        if (a->control_enabled) brama_auth_window_open(&a->limits, now_us(a) / 1000);
        break;
    case BRAMA_AMSG_BUTTON_RESET: on_reset(a); break;
    case BRAMA_AMSG_INTERLOCK: on_interlock(a, m); break;
    case BRAMA_AMSG_TICK: on_tick(a); break;
    case BRAMA_AMSG_SESSION_INVALIDATED: on_session_invalidated(a, m); break;
    case BRAMA_AMSG_REPAIR_COMMIT: on_repair_commit(a, m); break;
    }
    memset(m->k_client, 0, sizeof m->k_client);
    memset(&m->bond, 0, sizeof m->bond); // klucze LTK/IRK nie zostają w kolejce
    return true;
}
