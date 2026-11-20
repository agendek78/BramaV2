#include <string.h>
#include "unity.h"
#include "brama_actor.h"
#include "brama_err.h"
#include "fake_nvs.h"
#include "test_actor_env.h"
#include "test_registry.h"
#include "test_vectors.h"

// --- Fałszywe porty i rejestr wyjść (wspólne z test_ota.c przez test_actor_env.h) -----------------------------------

fake_world_t W;
brama_actor_t ACT;

static int64_t now_mono(void *ctx) { (void)ctx; return W.now_us; }
static int64_t now_rtc(void *ctx) { (void)ctx; return W.now_us + 1000000000LL; }
static bool rtc_load(void *ctx, void *buf, size_t len) { (void)ctx; memcpy(buf, &W.rtc, len); return W.rtc_written; }
static void rtc_store(void *ctx, const void *buf, size_t len) { (void)ctx; memcpy(&W.rtc, buf, len); W.rtc_written = true; W.rtc_writes++; }

static bool start_pulse(void *ctx, brama_output_t out, uint32_t ms)
{
    (void)ctx;
    if (W.io_fail) return false;
    W.pulses[W.npulses].out = out;
    W.pulses[W.npulses].at_us = W.now_us;
    W.pulses[W.npulses].ms = ms;
    W.npulses++;
    return true;
}

static bool interlock(void *ctx) { (void)ctx; return W.interlock; }
static void stop_pulse(void *ctx) { (void)ctx; W.stops++; }

static void send(void *ctx, int link, uint64_t token, uint64_t req_seq, uint8_t type, const uint8_t *p, size_t len)
{
    (void)ctx;
    sent_t *s = &W.sent[W.nsent++];
    s->link = link;
    s->token = token;
    s->req_seq = req_seq;
    s->type = type;
    memcpy(s->payload, p, len);
    s->len = len;
}

static void pair_result(void *ctx, int link, uint64_t token, bool ok)
{
    (void)ctx, (void)token;
    W.pair_link = link;
    W.pair_ok = ok;
    W.pair_results++;
}

static void repair_result(void *ctx, int link, uint64_t token, uint8_t code, const brama_bond_t *replaced)
{
    (void)ctx;
    W.repair_replaced = replaced != NULL;
    W.repair_replaced_addr = replaced ? replaced->id_addr[0] : 0;
    W.repair_link = link;
    W.repair_token = token;
    W.repair_code = code;
    W.repair_results++;
}

static void disconnect(void *ctx, int link) { (void)ctx; W.disconnected[link] = true; }

static void client_removed(void *ctx, const brama_bond_t *bond)
{
    (void)ctx;
    W.bonds_removed++;
    W.bond_removed_addr = bond->id_addr[0];
}

const uint8_t CA[16] = {0xA1}, CB[16] = {0xB2}, CC[16] = {0xC3};

void world_init(int clients)
{
    memset(&W, 0, sizeof W);
    W.now_us = 100LL * 1000 * 1000;
    fake_nvs_init(&W.fnvs, &W.nvs);
    brama_image_t img;
    brama_image_init_empty(&img);
    const uint8_t *ids[3] = {CA, CB, CC};
    for (int i = 0; i < clients; i++) {
        brama_client_rec_t c = {0};
        memcpy(c.client_id, ids[i], 16);
        memset(c.k_client, 0x10 + i, 32);
        brama_image_put_client(&img, &c);
    }
    brama_image_commit(&W.nvs, &img);
    W.fnvs.writes = 0;
    brama_localcfg_t cfg = {500, true, 50};
    brama_rule_t rule;
    memset(&rule, 0, sizeof rule);
    rule.phase = BRAMA_PHASE_DISARMED;
    rule.revision = 1;
    rule.auth_generation = img.auth_generation;
    brama_actor_env_t env = {
        .clock = {now_mono, NULL}, .rtc_clock = {now_rtc, NULL}, .nvs = W.nvs, .rtc = {rtc_load, rtc_store, NULL},
        .outputs = {start_pulse, interlock, stop_pulse, NULL}, .send = send, .pair_result = pair_result, .repair_result = repair_result,
        .disconnect = disconnect, .client_removed = client_removed};
    brama_actor_init(&ACT, &env, &img, &cfg, &rule, true);
}

void run_msg(brama_actor_msg_t *m)
{
    if (!m->auth_generation) m->auth_generation = ACT.image.auth_generation;
    TEST_ASSERT_TRUE(brama_actor_post(&ACT, m));
    while (brama_actor_step(&ACT)) {}
}

void connect_ready(int link, const uint8_t *client)
{
    brama_actor_msg_t m = {.type = BRAMA_AMSG_SESSION_READY, .link = link, .link_token = 100 + link,
                           .session_token = 200 + link};
    memcpy(m.client_id, client, 16);
    run_msg(&m);
}

void command(int link, uint8_t type, const uint8_t *payload, size_t len, uint64_t seq)
{
    brama_actor_msg_t m = {.type = BRAMA_AMSG_COMMAND, .link = link, .link_token = 100 + link,
                           .session_token = 200 + link, .cmd_type = type, .payload_len = len, .seq = seq};
    if (len) memcpy(m.payload, payload, len);
    memcpy(m.client_id, ACT.links[link].client_id, 16);
    run_msg(&m);
}

void open_cmd(int link, bool auto_close, uint64_t seq)
{
    uint8_t p = auto_close ? 1 : 0;
    command(link, BRAMA_MSG_OPEN, &p, 1, seq);
}

void tick_to(int64_t us)
{
    W.now_us = us;
    brama_actor_msg_t m = {.type = BRAMA_AMSG_TICK};
    run_msg(&m);
}

void disconnect_link(int link)
{
    brama_actor_msg_t m = {.type = BRAMA_AMSG_DISCONNECT, .link = link, .link_token = 100 + link};
    run_msg(&m);
}

/** Ostatni ACK do łącza: wynik i snapshot. */
const sent_t *last_to(int link, uint8_t type)
{
    for (int i = W.nsent - 1; i >= 0; i--) if (W.sent[i].link == link && W.sent[i].type == type) return &W.sent[i];
    return NULL;
}

uint8_t last_ack_result(int link)
{
    const sent_t *s = last_to(link, BRAMA_MSG_COMMAND_ACK);
    TEST_ASSERT_NOT_NULL(s);
    return s->payload[1];
}

// --- Testy --------------------------------------------------------------------------------------------------------

static void open_true_arms_and_triggers(void)
{
    world_init(1);
    connect_ready(0, CA);
    open_cmd(0, true, 1);
    TEST_ASSERT_EQUAL(BRAMA_ACK_OUTPUT_TRIGGERED, last_ack_result(0));
    const sent_t *ack = last_to(0, BRAMA_MSG_COMMAND_ACK);
    TEST_ASSERT_EQUAL(1, ack->req_seq);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_MONITORING, ack->payload[18]);
    TEST_ASSERT_EQUAL(BRAMA_OWNER_SELF, ack->payload[19]);
    TEST_ASSERT_EQUAL(1, W.npulses);
    TEST_ASSERT_EQUAL(BRAMA_OUTPUT_OPEN, W.pulses[0].out);
    TEST_ASSERT_EQUAL(500, W.pulses[0].ms);
    // kopia RTC zapisana jako ARMED przed impulsem
    TEST_ASSERT_TRUE(W.rtc_written);
    TEST_ASSERT_EQUAL(1, W.rtc.state);
}

static void close_disarms_then_pulses(void)
{
    world_init(2);
    connect_ready(0, CA);
    connect_ready(1, CB);
    open_cmd(0, true, 1);
    W.now_us += 2000000;
    command(1, BRAMA_MSG_CLOSE, NULL, 0, 1); // CLOSE dowolnego klienta
    TEST_ASSERT_EQUAL(BRAMA_ACK_OUTPUT_TRIGGERED, last_ack_result(1));
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, ACT.rule.phase);
    TEST_ASSERT_EQUAL(BRAMA_OUTPUT_CLOSE, W.pulses[1].out);
    TEST_ASSERT_EQUAL(0, W.rtc.state);
}

static void other_client_open_is_lease_owned_by_other(void)
{
    world_init(2);
    connect_ready(0, CA);
    connect_ready(1, CB);
    open_cmd(0, true, 1);
    W.now_us += 2000000;
    open_cmd(1, false, 1);
    TEST_ASSERT_EQUAL(BRAMA_ACK_LEASE_OWNED_BY_OTHER, last_ack_result(1));
    TEST_ASSERT_EQUAL(1, W.npulses);
    TEST_ASSERT_EQUAL(BRAMA_OWNER_OTHER, last_to(1, BRAMA_MSG_COMMAND_ACK)->payload[19]);
}

static void interlock_blocks_both_commands(void)
{
    world_init(1);
    connect_ready(0, CA);
    W.interlock = true;
    open_cmd(0, true, 1);
    TEST_ASSERT_EQUAL(BRAMA_ACK_INTERLOCK_BLOCKED, last_ack_result(0));
    command(0, BRAMA_MSG_CLOSE, NULL, 0, 2);
    TEST_ASSERT_EQUAL(BRAMA_ACK_INTERLOCK_BLOCKED, last_ack_result(0));
    TEST_ASSERT_EQUAL(0, W.npulses);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, ACT.rule.phase); // bez zmiany reguły
}

static void secondCommandWithinGapIsBusy(void)
{
    world_init(2);
    connect_ready(0, CA);
    connect_ready(1, CB);
    ACT.cfg.pulse_ms = 1000; // odstęp max(1000, 1000 + 200) = 1200 ms
    open_cmd(0, false, 1);
    W.now_us += 1199000;
    command(1, BRAMA_MSG_CLOSE, NULL, 0, 1);
    TEST_ASSERT_EQUAL(BRAMA_ACK_BUSY, last_ack_result(1));
    TEST_ASSERT_EQUAL(1, W.npulses);
    W.now_us += 1000;
    command(1, BRAMA_MSG_CLOSE, NULL, 0, 2);
    TEST_ASSERT_EQUAL(BRAMA_ACK_OUTPUT_TRIGGERED, last_ack_result(1));
    TEST_ASSERT_EQUAL(1200000, W.pulses[1].at_us - W.pulses[0].at_us);
}

static void staleSessionTokenIsDropped(void)
{
    world_init(1);
    connect_ready(0, CA);
    brama_actor_msg_t m = {.type = BRAMA_AMSG_COMMAND, .link = 0, .link_token = 100, .session_token = 199,
                           .cmd_type = BRAMA_MSG_CLOSE};
    memcpy(m.client_id, CA, 16);
    run_msg(&m);
    TEST_ASSERT_EQUAL(0, W.nsent);
    TEST_ASSERT_EQUAL(0, W.npulses);
    m.session_token = 200;
    m.auth_generation = ACT.image.auth_generation + 1; // stara/obca generacja
    run_msg(&m);
    TEST_ASSERT_EQUAL(0, W.npulses);
}

static void invalid_argument_and_unsupported_type(void)
{
    world_init(1);
    connect_ready(0, CA);
    uint8_t two = 2;
    command(0, BRAMA_MSG_OPEN, &two, 1, 1);
    TEST_ASSERT_EQUAL(BRAMA_ACK_INVALID_ARGUMENT, last_ack_result(0));
    command(0, BRAMA_MSG_CLOSE, &two, 1, 2);
    TEST_ASSERT_EQUAL(BRAMA_ACK_INVALID_ARGUMENT, last_ack_result(0));
    command(0, 0x77, NULL, 0, 3);
    TEST_ASSERT_EQUAL(BRAMA_ACK_UNSUPPORTED_TYPE, last_ack_result(0));
    TEST_ASSERT_EQUAL(0x77, last_to(0, BRAMA_MSG_COMMAND_ACK)->payload[0]);
    command(0, BRAMA_MSG_PING, NULL, 0, 4);
    const sent_t *pong = last_to(0, BRAMA_MSG_PONG);
    TEST_ASSERT_NOT_NULL(pong);
    TEST_ASSERT_EQUAL(15, pong->len);
    TEST_ASSERT_EQUAL(0, W.npulses);
}

static void io_fault_reports_and_sets_fault(void)
{
    world_init(1);
    connect_ready(0, CA);
    W.io_fail = true;
    open_cmd(0, true, 1);
    TEST_ASSERT_EQUAL(BRAMA_ACK_IO_FAULT, last_ack_result(0));
    TEST_ASSERT_EQUAL(BRAMA_PHASE_FAULT, ACT.rule.phase);
    TEST_ASSERT_EQUAL(2, W.rtc.state);
}

static void auto_close_after_waiting_deadline(void)
{
    world_init(2);
    connect_ready(0, CA);
    connect_ready(1, CB);
    open_cmd(0, true, 1);
    int64_t t0 = W.now_us;
    disconnect_link(0);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_WAITING, ACT.rule.phase);
    TEST_ASSERT_NOT_NULL(last_to(1, BRAMA_MSG_RULE_STATUS)); // inni READY dostają zmianę
    tick_to(t0 + 29999999);
    TEST_ASSERT_EQUAL(1, W.npulses);
    tick_to(t0 + 30000000);
    TEST_ASSERT_EQUAL(2, W.npulses);
    TEST_ASSERT_EQUAL(BRAMA_OUTPUT_CLOSE, W.pulses[1].out);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, ACT.rule.phase);
    tick_to(t0 + 90000000);
    TEST_ASSERT_EQUAL(2, W.npulses); // jedna próba
}

static void auto_close_waits_for_busy_at_most_2s(void)
{
    world_init(2);
    connect_ready(0, CA);
    connect_ready(1, CB);
    open_cmd(0, true, 1);
    disconnect_link(0);
    int64_t deadline = ACT.rule.deadline_us;
    W.now_us = deadline - 500000;
    command(1, BRAMA_MSG_PING, NULL, 0, 1);
    // ręczny impuls B tuż przed terminem — auto-CLOSE musi poczekać na odstęp, ale nie dłużej niż 2 s
    W.now_us = deadline - 100000;
    brama_actor_msg_t m = {0};
    (void)m;
    ACT.last_pulse_start_us = W.now_us; // symulacja: impuls rozpoczęty 0,1 s przed terminem
    tick_to(deadline);
    TEST_ASSERT_EQUAL(1, W.npulses);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_WAITING, ACT.rule.phase);
    tick_to(deadline - 100000 + 1000000); // koniec odstępu 1000 ms od początku
    TEST_ASSERT_EQUAL(2, W.npulses);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, ACT.rule.phase);
}

static void busy_over_2s_is_fault(void)
{
    world_init(1);
    connect_ready(0, CA);
    open_cmd(0, true, 1);
    disconnect_link(0);
    int64_t deadline = ACT.rule.deadline_us;
    ACT.cfg.pulse_ms = 1000;
    ACT.last_pulse_start_us = deadline + 5000000; // sztucznie: BUSY przez cały czas oczekiwania
    tick_to(deadline);
    tick_to(deadline + 2000001);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_FAULT, ACT.rule.phase);
    TEST_ASSERT_EQUAL(BRAMA_REASON_BUSY_TIMEOUT, ACT.rule.reason);
    TEST_ASSERT_EQUAL(1, W.npulses);
}

static void interlock_at_deadline_blocks(void)
{
    world_init(1);
    connect_ready(0, CA);
    open_cmd(0, true, 1);
    disconnect_link(0);
    W.interlock = true;
    tick_to(ACT.rule.deadline_us);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_BLOCKED, ACT.rule.phase);
    TEST_ASSERT_EQUAL(1, W.npulses);
    W.interlock = false;
    brama_actor_msg_t m = {.type = BRAMA_AMSG_INTERLOCK, .interlock_active = false};
    run_msg(&m);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_WAITING, ACT.rule.phase); // właściciel nieobecny: nowe 30 s
}

static void pair_commit_writes_image_once(void)
{
    world_init(1);
    brama_actor_msg_t m = {.type = BRAMA_AMSG_PAIR_COMMIT, .link = 8, .link_token = 108, .session_token = 308};
    memcpy(m.client_id, CB, 16);
    memset(m.k_client, 0x42, 32);
    m.bond.len = 2;
    run_msg(&m);
    TEST_ASSERT_EQUAL(1, W.pair_results);
    TEST_ASSERT_TRUE(W.pair_ok);
    TEST_ASSERT_EQUAL(1, W.fnvs.writes);
    TEST_ASSERT_EQUAL(2, ACT.image.count);
    uint8_t key[32];
    TEST_ASSERT_TRUE(brama_actor_client_key(&ACT, CB, key));
    TEST_ASSERT_EQUAL_HEX8(0x42, key[0]);
    // istniejący ACTIVE tego client_id: odmowa bez zapisu
    run_msg(&m);
    TEST_ASSERT_FALSE(W.pair_ok);
    TEST_ASSERT_EQUAL(1, W.fnvs.writes);
}

__attribute__((noinline)) static void dirty_stack(void)
{
    volatile uint8_t b[8192];
    for (size_t i = 0; i < sizeof b; i++) b[i] = 0xFF;
}

static void pair_commit_gives_empty_name_regardless_of_stack_contents(void)
{
    world_init(1);
    brama_actor_msg_t m = {.type = BRAMA_AMSG_PAIR_COMMIT, .link = 8, .link_token = 108, .session_token = 308};
    memcpy(m.client_id, CB, 16);
    memset(m.k_client, 0x42, 32);
    m.bond.len = 2;
    dirty_stack(); // rekord klienta budowany w aktorze nie może dziedziczyć śmieci ze stosu (nazwa 0xFF… = zły UTF-8)
    run_msg(&m);
    TEST_ASSERT_TRUE(W.pair_ok);
    TEST_ASSERT_EQUAL(0, ACT.image.clients[1].name_len);
    uint8_t zeros[BRAMA_CLIENT_NAME_MAX] = {0};
    TEST_ASSERT_EQUAL_MEMORY(zeros, ACT.image.clients[1].name, sizeof zeros);
}

static void pair_commit_storage_failure_keeps_image(void)
{
    world_init(1);
    W.fnvs.fail_next_write = true;
    brama_actor_msg_t m = {.type = BRAMA_AMSG_PAIR_COMMIT, .link = 8, .link_token = 108, .session_token = 308};
    memcpy(m.client_id, CB, 16);
    m.bond.len = 2; // z bondem, aby test dotarł do zapisu NVS
    run_msg(&m);
    TEST_ASSERT_FALSE(W.pair_ok);
    TEST_ASSERT_EQUAL(1, ACT.image.count);
    uint8_t key[32];
    TEST_ASSERT_FALSE(brama_actor_client_key(&ACT, CB, key));
}

static void pair_commit_without_bond_is_rejected(void)
{
    // Rejestracja bez bondu (staging pusty) zostawiała martwy wpis klienta, którego SESSION_BEGIN nigdy nie znajdował.
    world_init(1);
    brama_actor_msg_t m = {.type = BRAMA_AMSG_PAIR_COMMIT, .link = 8, .link_token = 108, .session_token = 308};
    memcpy(m.client_id, CB, 16);
    memset(m.k_client, 0x42, 32);
    run_msg(&m);
    TEST_ASSERT_EQUAL(1, W.pair_results);
    TEST_ASSERT_FALSE(W.pair_ok);
    TEST_ASSERT_EQUAL(0, W.fnvs.writes);
    TEST_ASSERT_EQUAL(1, ACT.image.count);
}

static void normal_operation_writes_no_nvs(void)
{
    // A59: cykle OPEN/CLOSE z obiema wartościami, auto-CLOSE, reconnect — zero zapisów NVS.
    world_init(2);
    connect_ready(0, CA);
    connect_ready(1, CB);
    for (int i = 0; i < 1000; i++) {
        W.now_us += 1300000;
        open_cmd(0, i % 2 == 0, 2 * i + 1);
        W.now_us += 1300000;
        command(1, BRAMA_MSG_CLOSE, NULL, 0, i + 1);
    }
    W.now_us += 1300000;
    open_cmd(0, true, 5000);
    disconnect_link(0);
    tick_to(W.now_us + 31000000);
    connect_ready(0, CA);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, ACT.rule.phase);
    TEST_ASSERT_EQUAL(0, W.fnvs.writes);
    TEST_ASSERT_EQUAL(2002, W.npulses);
}

static void reset_invalidates_tokens_and_disarms_without_pulse(void)
{
    world_init(2);
    connect_ready(0, CA);
    connect_ready(1, CB);
    open_cmd(0, true, 1);
    uint64_t gen = ACT.image.auth_generation;
    brama_actor_msg_t r = {.type = BRAMA_AMSG_BUTTON_RESET};
    run_msg(&r);
    TEST_ASSERT_EQUAL(0, ACT.image.count);
    TEST_ASSERT_EQUAL(gen + 1, ACT.image.auth_generation);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, ACT.rule.phase);
    TEST_ASSERT_EQUAL(1, W.npulses); // bez impulsu
    TEST_ASSERT_TRUE(W.disconnected[0]);
    TEST_ASSERT_TRUE(W.disconnected[1]);
    TEST_ASSERT_TRUE(ACT.control_enabled);
    // stare tokeny po resecie: bez impulsu i bez odpowiedzi; disconnect nie tworzy WAITING
    int sent = W.nsent;
    W.now_us += 5000000;
    brama_actor_msg_t m = {.type = BRAMA_AMSG_COMMAND, .link = 1, .link_token = 101, .session_token = 201,
                           .auth_generation = gen, .cmd_type = BRAMA_MSG_CLOSE};
    memcpy(m.client_id, CB, 16);
    run_msg(&m);
    disconnect_link(0);
    TEST_ASSERT_EQUAL(sent, W.nsent);
    TEST_ASSERT_EQUAL(1, W.npulses);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, ACT.rule.phase);
}

static void reset_has_priority_over_queued_commands(void)
{
    world_init(1);
    connect_ready(0, CA);
    brama_actor_msg_t c = {.type = BRAMA_AMSG_COMMAND, .link = 0, .link_token = 100, .session_token = 200,
                           .auth_generation = ACT.image.auth_generation, .cmd_type = BRAMA_MSG_CLOSE, .seq = 1};
    memcpy(c.client_id, CA, 16);
    brama_actor_post(&ACT, &c);
    brama_actor_msg_t r = {.type = BRAMA_AMSG_BUTTON_RESET};
    brama_actor_post(&ACT, &r);
    while (brama_actor_step(&ACT)) {}
    TEST_ASSERT_EQUAL(0, W.npulses); // nierozpoczęta komenda z kolejki odrzucona
}

static void failed_reset_schedules_single_close_after_30s(void)
{
    world_init(1);
    connect_ready(0, CA);
    open_cmd(0, true, 1);
    W.fnvs.fail_next_write = true;
    int64_t t0 = W.now_us;
    brama_actor_msg_t r = {.type = BRAMA_AMSG_BUTTON_RESET};
    run_msg(&r);
    TEST_ASSERT_FALSE(ACT.control_enabled);
    TEST_ASSERT_EQUAL(1, ACT.image.count); // obraz bez zmian
    TEST_ASSERT_EQUAL(BRAMA_PHASE_FAULT, ACT.rule.phase);
    TEST_ASSERT_EQUAL(BRAMA_REASON_STORAGE_FAILURE, ACT.rule.reason);
    TEST_ASSERT_TRUE(W.disconnected[0]);
    disconnect_link(0); // disconnect nie tworzy WAITING
    TEST_ASSERT_EQUAL(BRAMA_PHASE_FAULT, ACT.rule.phase);
    tick_to(t0 + 30000000);
    TEST_ASSERT_EQUAL(2, W.npulses);
    TEST_ASSERT_EQUAL(BRAMA_OUTPUT_CLOSE, W.pulses[1].out);
    tick_to(t0 + 90000000);
    TEST_ASSERT_EQUAL(2, W.npulses);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_FAULT, ACT.rule.phase);
    // bez control_enabled komendy klientów nie działają
    connect_ready(0, CA);
    command(0, BRAMA_MSG_CLOSE, NULL, 0, 1);
    TEST_ASSERT_EQUAL(2, W.npulses);
}

static void rule_status_sent_to_every_ready_session(void)
{
    world_init(3);
    connect_ready(0, CA);
    connect_ready(1, CB);
    connect_ready(2, CC);
    open_cmd(0, true, 1);
    const sent_t *b = last_to(1, BRAMA_MSG_RULE_STATUS), *c = last_to(2, BRAMA_MSG_RULE_STATUS);
    TEST_ASSERT_NOT_NULL(b);
    TEST_ASSERT_NOT_NULL(c);
    TEST_ASSERT_EQUAL(0, b->req_seq);
    TEST_ASSERT_EQUAL(BRAMA_OWNER_OTHER, b->payload[9]);
    TEST_ASSERT_EQUAL(201, b->token);
    // ACK niesie snapshot wnioskodawcy; bez zdublowanego RULE_STATUS do niego
    TEST_ASSERT_NULL(last_to(0, BRAMA_MSG_RULE_STATUS));
}

static void owner_return_cancels_waiting(void)
{
    world_init(1);
    connect_ready(0, CA);
    open_cmd(0, true, 1);
    disconnect_link(0);
    W.now_us += 10000000;
    connect_ready(0, CA);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_MONITORING, ACT.rule.phase);
    tick_to(W.now_us + 60000000);
    TEST_ASSERT_EQUAL(1, W.npulses);
}

static void invalidated_session_drops_queued_command(void)
{
    // I1: sesja unieważniona/rekey w warstwie BLE — komenda z kolejki ze starym tokenem nie podaje impulsu (12.1).
    world_init(1);
    connect_ready(0, CA);
    open_cmd(0, true, 1);
    disconnect_link(0); // WAITING
    connect_ready(0, CA);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_MONITORING, ACT.rule.phase);
    brama_actor_msg_t c = {.type = BRAMA_AMSG_COMMAND, .link = 0, .link_token = 100, .session_token = 200,
                           .auth_generation = ACT.image.auth_generation, .cmd_type = BRAMA_MSG_CLOSE, .seq = 2};
    memcpy(c.client_id, CA, 16);
    brama_actor_msg_t inv = {.type = BRAMA_AMSG_SESSION_INVALIDATED, .link = 0, .link_token = 100};
    W.now_us += 2000000;
    brama_actor_post(&ACT, &inv);
    brama_actor_post(&ACT, &c);
    while (brama_actor_step(&ACT)) {}
    TEST_ASSERT_EQUAL(1, W.npulses);
    // unieważnienie sesji nie jest utratą łącza: bez WAITING
    TEST_ASSERT_EQUAL(BRAMA_PHASE_MONITORING, ACT.rule.phase);    // późniejsza rzeczywista utrata radia właściciela nadal uruchamia WAITING (14.1)
    disconnect_link(0);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_WAITING, ACT.rule.phase);
}

static void reset_stops_running_pulse(void)
{
    // I7: reset wyłącza trwający impuls bez rozpoczęcia innego (9.6 krok 1).
    world_init(1);
    connect_ready(0, CA);
    open_cmd(0, false, 1);
    brama_actor_msg_t r = {.type = BRAMA_AMSG_BUTTON_RESET};
    run_msg(&r);
    TEST_ASSERT_EQUAL(1, W.stops);
    TEST_ASSERT_EQUAL(1, W.npulses);
}

static void client_key_requires_matching_bond(void)
{
    // I8: sesja klienta tylko na łączu jego zatwierdzonego bondu (9.5, 12.1).
    world_init(0);
    brama_client_rec_t c = {0};
    memcpy(c.client_id, CA, 16);
    memset(c.k_client, 7, 32);
    c.bond.id_addr_type = 1;
    memset(c.bond.id_addr, 0xAA, 6);
    c.bond.len = 88;
    brama_image_put_client(&ACT.image, &c);
    uint8_t key[32], peer[7] = {1, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA}, other[7] = {1, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB};
    TEST_ASSERT_TRUE(brama_actor_client_key_for_peer(&ACT, CA, peer, key));
    TEST_ASSERT_FALSE(brama_actor_client_key_for_peer(&ACT, CA, other, key));
    TEST_ASSERT_FALSE(brama_actor_client_key_for_peer(&ACT, CB, peer, key));
}

// --- Okno rejestracji z telefonu (phone_window_design) ---------------------------------------------------------------

static void phone_opens_pairing_window(void)
{
    world_init(1);
    connect_ready(0, CA);
    TEST_ASSERT_FALSE(ACT.limits.window_open);
    command(0, BRAMA_MSG_OPEN_PAIRING_WINDOW, NULL, 0, 1);
    TEST_ASSERT_EQUAL(BRAMA_ACK_WINDOW_OPENED, last_ack_result(0));
    TEST_ASSERT_EQUAL(BRAMA_MSG_OPEN_PAIRING_WINDOW, last_to(0, BRAMA_MSG_COMMAND_ACK)->payload[0]);
    TEST_ASSERT_TRUE(brama_auth_window_is_open(&ACT.limits, W.now_us / 1000 + 59999));
    TEST_ASSERT_FALSE(brama_auth_window_is_open(&ACT.limits, W.now_us / 1000 + 60000));
    TEST_ASSERT_EQUAL(0, W.npulses);       // to nie jest komenda ruchu
    TEST_ASSERT_EQUAL(0, W.fnvs.writes);   // tylko RAM
    // ponowne otwarcie uruchamia okno od nowa
    W.now_us += 30000000;
    command(0, BRAMA_MSG_OPEN_PAIRING_WINDOW, NULL, 0, 2);
    TEST_ASSERT_EQUAL(BRAMA_ACK_WINDOW_OPENED, last_ack_result(0));
    TEST_ASSERT_TRUE(brama_auth_window_is_open(&ACT.limits, W.now_us / 1000 + 59999));
}

static void phone_window_rejections(void)
{
    world_init(1);
    connect_ready(0, CA);
    uint8_t extra = 0;
    command(0, BRAMA_MSG_OPEN_PAIRING_WINDOW, &extra, 1, 1);
    TEST_ASSERT_EQUAL(BRAMA_ACK_INVALID_ARGUMENT, last_ack_result(0));
    TEST_ASSERT_FALSE(ACT.limits.window_open);
    // nieaktualna sesja: bez skutku i bez odpowiedzi
    int sent = W.nsent;
    brama_actor_msg_t m = {.type = BRAMA_AMSG_COMMAND, .link = 0, .link_token = 100, .session_token = 999,
                           .cmd_type = BRAMA_MSG_OPEN_PAIRING_WINDOW, .seq = 2};
    run_msg(&m);
    TEST_ASSERT_EQUAL(sent, W.nsent);
    TEST_ASSERT_FALSE(ACT.limits.window_open);
    // tryb błędu / po OTA_END: control_enabled = false
    ACT.control_enabled = false;
    command(0, BRAMA_MSG_OPEN_PAIRING_WINDOW, NULL, 0, 3);
    TEST_ASSERT_EQUAL(sent, W.nsent);
    TEST_ASSERT_FALSE(ACT.limits.window_open);
}

// --- Zarządzanie telefonami (client_management_design.md) -------------------------------------------------------------

static brama_bond_t bond_of(uint8_t addr, uint8_t fill);

static void client_list_matches_kotlin_vectors(void)
{
    // commands.txt (generowany przez :protocol): trzy telefony, nazwy "Pixel 8" i "Ania", pierwszy żądający, drugi połączony.
    vec_load("vectors/commands.txt");
    size_t n;
    const uint8_t *me = vec("client_id", &n), *other = vec("other_client_id", &n), *third = vec("third_client_id", &n);
    world_init(0);
    const uint8_t *ids[3] = {me, other, third};
    const char *names[3] = {"Pixel 8", "Ania", ""};
    for (int i = 0; i < 3; i++) {
        brama_client_rec_t c = {0};
        memcpy(c.client_id, ids[i], 16);
        c.name_len = (uint8_t)strlen(names[i]);
        memcpy(c.name, names[i], c.name_len);
        TEST_ASSERT_EQUAL(BRAMA_OK, brama_image_put_client(&ACT.image, &c));
    }
    connect_ready(0, me);
    connect_ready(1, other);
    command(0, BRAMA_MSG_LIST_CLIENTS, NULL, 0, 7);
    const sent_t *r = last_to(0, BRAMA_MSG_CLIENT_LIST);
    TEST_ASSERT_NOT_NULL(r);
    const uint8_t *expected = vec("s2c_10_plain", &n);
    TEST_ASSERT_EQUAL(n, r->len);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(expected, r->payload, n);
    // po usunięciu trzeciego telefonu (seq 8) druga lista (seq 9) ma dwa wpisy
    command(0, BRAMA_MSG_REMOVE_CLIENT, third, 16, 8);
    TEST_ASSERT_EQUAL(BRAMA_ACK_CLIENT_REMOVED, last_ack_result(0));
    command(0, BRAMA_MSG_LIST_CLIENTS, NULL, 0, 9);
    r = last_to(0, BRAMA_MSG_CLIENT_LIST);
    expected = vec("s2c_12_plain", &n);
    TEST_ASSERT_EQUAL(n, r->len);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(expected, r->payload, n);
}


#define ENTRY(p, i) ((p) + 9 + (i) * 38)

static void set_name_cmd(int link, const char *name, size_t len, uint64_t seq)
{
    uint8_t p[1 + 32];
    p[0] = (uint8_t)len;
    memcpy(p + 1, name, len);
    command(link, BRAMA_MSG_SET_CLIENT_NAME, p, 1 + len, seq);
}

static void remove_cmd(int link, const uint8_t *client_id, uint64_t seq)
{
    command(link, BRAMA_MSG_REMOVE_CLIENT, client_id, 16, seq);
}

static void list_reports_clients_with_flags_and_names(void)
{
    world_init(3);
    connect_ready(0, CA);
    connect_ready(1, CB);
    set_name_cmd(0, "Telefon A", 9, 1);
    open_cmd(1, true, 1); // CB staje się właścicielem reguły
    int writes = W.fnvs.writes;
    command(0, BRAMA_MSG_LIST_CLIENTS, NULL, 0, 2);
    const sent_t *r = last_to(0, BRAMA_MSG_CLIENT_LIST);
    TEST_ASSERT_NOT_NULL(r);
    TEST_ASSERT_EQUAL(2, r->req_seq);
    TEST_ASSERT_EQUAL(9 + 3 * 38, r->len);
    TEST_ASSERT_EQUAL(2, brama_get_le(r->payload, 8));
    TEST_ASSERT_EQUAL(3, r->payload[8]);
    TEST_ASSERT_EQUAL_MEMORY(CA, ENTRY(r->payload, 0), 16);
    TEST_ASSERT_EQUAL(0x03, ENTRY(r->payload, 0)[16]); // żądający + READY
    TEST_ASSERT_EQUAL(9, ENTRY(r->payload, 0)[17]);
    TEST_ASSERT_EQUAL_MEMORY("Telefon A", ENTRY(r->payload, 0) + 18, 9);
    TEST_ASSERT_EQUAL_MEMORY(CB, ENTRY(r->payload, 1), 16);
    TEST_ASSERT_EQUAL(0x06, ENTRY(r->payload, 1)[16]); // READY + właściciel reguły
    TEST_ASSERT_EQUAL(0, ENTRY(r->payload, 1)[17]);
    TEST_ASSERT_EQUAL_MEMORY(CC, ENTRY(r->payload, 2), 16);
    TEST_ASSERT_EQUAL(0x00, ENTRY(r->payload, 2)[16]); // rozłączony
    TEST_ASSERT_EQUAL(writes, W.fnvs.writes);          // lista nie zapisuje NVS
}

static void list_with_payload_is_invalid_argument(void)
{
    world_init(1);
    connect_ready(0, CA);
    uint8_t extra = 0;
    command(0, BRAMA_MSG_LIST_CLIENTS, &extra, 1, 1);
    TEST_ASSERT_EQUAL(BRAMA_ACK_INVALID_ARGUMENT, last_ack_result(0));
    TEST_ASSERT_NULL(last_to(0, BRAMA_MSG_CLIENT_LIST));
}

static void set_name_commits_without_changing_auth_generation(void)
{
    world_init(2);
    connect_ready(0, CA);
    uint64_t auth = ACT.image.auth_generation, imgen = ACT.image.image_generation;
    set_name_cmd(0, "Ania", 4, 1);
    TEST_ASSERT_EQUAL(BRAMA_ACK_NAME_SET, last_ack_result(0));
    TEST_ASSERT_EQUAL(BRAMA_MSG_SET_CLIENT_NAME, last_to(0, BRAMA_MSG_COMMAND_ACK)->payload[0]);
    TEST_ASSERT_EQUAL(1, W.fnvs.writes);
    TEST_ASSERT_EQUAL(auth, ACT.image.auth_generation);
    TEST_ASSERT_EQUAL(imgen + 1, ACT.image.image_generation);
    TEST_ASSERT_EQUAL(4, ACT.image.clients[0].name_len);
    TEST_ASSERT_EQUAL(0, ACT.image.clients[1].name_len); // cudzy rekord bez zmian
    brama_image_t loaded;
    TEST_ASSERT_EQUAL(BRAMA_IMG_OK, brama_image_load(&W.nvs, &loaded));
    TEST_ASSERT_EQUAL_MEMORY("Ania", loaded.clients[0].name, 4);
    set_name_cmd(0, "", 0, 2); // pusta nazwa czyści
    TEST_ASSERT_EQUAL(BRAMA_ACK_NAME_SET, last_ack_result(0));
    TEST_ASSERT_EQUAL(0, ACT.image.clients[0].name_len);
}

static void set_name_rejections_leave_image_untouched(void)
{
    world_init(1);
    connect_ready(0, CA);
    uint8_t p[40] = {0};
    // 21 B, zły UTF-8, znak sterujący, długość niezgodna z payloadem, brak payloadu
    set_name_cmd(0, "123456789012345678901", 21, 1);
    TEST_ASSERT_EQUAL(BRAMA_ACK_INVALID_ARGUMENT, last_ack_result(0));
    set_name_cmd(0, "\xC3", 1, 2);
    TEST_ASSERT_EQUAL(BRAMA_ACK_INVALID_ARGUMENT, last_ack_result(0));
    set_name_cmd(0, "a\x01", 2, 3);
    TEST_ASSERT_EQUAL(BRAMA_ACK_INVALID_ARGUMENT, last_ack_result(0));
    p[0] = 5;
    memcpy(p + 1, "abc", 3);
    command(0, BRAMA_MSG_SET_CLIENT_NAME, p, 4, 4); // deklaruje 5 B, ma 3
    TEST_ASSERT_EQUAL(BRAMA_ACK_INVALID_ARGUMENT, last_ack_result(0));
    command(0, BRAMA_MSG_SET_CLIENT_NAME, NULL, 0, 5);
    TEST_ASSERT_EQUAL(BRAMA_ACK_INVALID_ARGUMENT, last_ack_result(0));
    TEST_ASSERT_EQUAL(0, W.fnvs.writes);
    TEST_ASSERT_EQUAL(0, ACT.image.clients[0].name_len);
}

static void set_name_storage_failure_keeps_old_image(void)
{
    world_init(1);
    connect_ready(0, CA);
    set_name_cmd(0, "Stara", 5, 1);
    W.fnvs.fail_next_write = true;
    set_name_cmd(0, "Nowa", 4, 2);
    TEST_ASSERT_EQUAL(BRAMA_ACK_STORAGE_FAILURE, last_ack_result(0));
    TEST_ASSERT_EQUAL(5, ACT.image.clients[0].name_len);
    TEST_ASSERT_EQUAL_MEMORY("Stara", ACT.image.clients[0].name, 5);
}

static void remove_disconnected_client_commits_and_keeps_generation(void)
{
    world_init(3);
    ACT.image.clients[2].bond = bond_of(0xC3, 7);
    connect_ready(0, CA);
    uint64_t auth = ACT.image.auth_generation;
    remove_cmd(0, CC, 1);
    TEST_ASSERT_EQUAL(BRAMA_ACK_CLIENT_REMOVED, last_ack_result(0));
    TEST_ASSERT_EQUAL(BRAMA_MSG_REMOVE_CLIENT, last_to(0, BRAMA_MSG_COMMAND_ACK)->payload[0]);
    TEST_ASSERT_EQUAL(2, ACT.image.count);
    TEST_ASSERT_EQUAL(-1, brama_image_find(&ACT.image, CC));
    TEST_ASSERT_EQUAL(auth, ACT.image.auth_generation);
    TEST_ASSERT_EQUAL(1, W.fnvs.writes);
    TEST_ASSERT_EQUAL(1, W.bonds_removed);
    TEST_ASSERT_EQUAL(0xC3, W.bond_removed_addr);
    brama_image_t loaded;
    TEST_ASSERT_EQUAL(BRAMA_IMG_OK, brama_image_load(&W.nvs, &loaded));
    TEST_ASSERT_EQUAL(2, loaded.count);
    TEST_ASSERT_FALSE(W.disconnected[0]); // własne łącze żądającego nietknięte
}

static void remove_connected_client_drops_link_and_keeps_other_rule(void)
{
    world_init(3);
    connect_ready(0, CA);
    connect_ready(1, CB);
    open_cmd(0, true, 1); // reguła należy do CA
    uint64_t rev = ACT.rule.revision;
    remove_cmd(0, CB, 2);
    TEST_ASSERT_EQUAL(BRAMA_ACK_CLIENT_REMOVED, last_ack_result(0));
    TEST_ASSERT_TRUE(W.disconnected[1]);
    TEST_ASSERT_FALSE(ACT.links[1].used);
    TEST_ASSERT_TRUE(ACT.links[0].ready);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_MONITORING, ACT.rule.phase);
    TEST_ASSERT_TRUE(ACT.rule.has_owner);
    TEST_ASSERT_EQUAL_MEMORY(CA, ACT.rule.owner_id, 16);
    TEST_ASSERT_EQUAL(rev, ACT.rule.revision);
    // dalej działa: CLOSE właściciela (po odstępie między impulsami)
    tick_to(W.now_us + 3000000);
    command(0, BRAMA_MSG_CLOSE, NULL, 0, 3);
    TEST_ASSERT_EQUAL(BRAMA_ACK_OUTPUT_TRIGGERED, last_ack_result(0));
}

static void commands_of_removed_client_queued_before_commit_are_dropped(void)
{
    world_init(2);
    connect_ready(0, CA);
    connect_ready(1, CB);
    brama_actor_msg_t rm = {.type = BRAMA_AMSG_COMMAND, .link = 0, .link_token = 100, .session_token = 200,
                            .cmd_type = BRAMA_MSG_REMOVE_CLIENT, .payload_len = 16, .seq = 1,
                            .auth_generation = ACT.image.auth_generation};
    memcpy(rm.payload, CB, 16);
    memcpy(rm.client_id, CA, 16);
    brama_actor_msg_t op = {.type = BRAMA_AMSG_COMMAND, .link = 1, .link_token = 101, .session_token = 201,
                            .cmd_type = BRAMA_MSG_OPEN, .payload_len = 1, .seq = 1,
                            .auth_generation = ACT.image.auth_generation};
    memcpy(op.client_id, CB, 16);
    TEST_ASSERT_TRUE(brama_actor_post(&ACT, &rm));
    TEST_ASSERT_TRUE(brama_actor_post(&ACT, &op));
    while (brama_actor_step(&ACT)) {}
    TEST_ASSERT_EQUAL(0, W.npulses);
    TEST_ASSERT_NULL(last_to(1, BRAMA_MSG_COMMAND_ACK));
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, ACT.rule.phase);
}

static void remove_refusals_change_nothing(void)
{
    world_init(3);
    connect_ready(0, CA);
    connect_ready(1, CB);
    open_cmd(1, true, 1); // CB właścicielem
    int writes = W.fnvs.writes;
    remove_cmd(0, CA, 2);
    TEST_ASSERT_EQUAL(BRAMA_ACK_CANNOT_REMOVE_SELF, last_ack_result(0));
    uint8_t unknown[16] = {0x77};
    remove_cmd(0, unknown, 3);
    TEST_ASSERT_EQUAL(BRAMA_ACK_CLIENT_NOT_FOUND, last_ack_result(0));
    remove_cmd(0, CB, 4);
    TEST_ASSERT_EQUAL(BRAMA_ACK_CLIENT_OWNS_RULE, last_ack_result(0));
    command(0, BRAMA_MSG_REMOVE_CLIENT, CC, 15, 5);
    TEST_ASSERT_EQUAL(BRAMA_ACK_INVALID_ARGUMENT, last_ack_result(0));
    TEST_ASSERT_EQUAL(writes, W.fnvs.writes);
    TEST_ASSERT_EQUAL(3, ACT.image.count);
    TEST_ASSERT_EQUAL(0, W.bonds_removed);
    // po CLOSE właściciela (reguła rozbrojona) usunięcie CB przechodzi
    tick_to(W.now_us + 3000000);
    command(1, BRAMA_MSG_CLOSE, NULL, 0, 6);
    TEST_ASSERT_EQUAL(BRAMA_ACK_OUTPUT_TRIGGERED, last_ack_result(1));
    remove_cmd(0, CB, 6);
    TEST_ASSERT_EQUAL(BRAMA_ACK_CLIENT_REMOVED, last_ack_result(0));
}

static void remove_waiting_owner_is_refused_until_auto_close(void)
{
    world_init(2);
    connect_ready(0, CA);
    connect_ready(1, CB);
    open_cmd(1, true, 1);
    disconnect_link(1); // właściciel traci łącze: WAITING, ale nadal ma regułę
    remove_cmd(0, CB, 2);
    TEST_ASSERT_EQUAL(BRAMA_ACK_CLIENT_OWNS_RULE, last_ack_result(0));
}

static void remove_is_busy_during_ota(void)
{
    world_init(2);
    connect_ready(0, CA);
    brama_ota_t ota = {.active = true};
    ACT.ota = &ota;
    remove_cmd(0, CB, 1);
    TEST_ASSERT_EQUAL(BRAMA_ACK_BUSY, last_ack_result(0));
    TEST_ASSERT_EQUAL(2, ACT.image.count);
    ACT.ota = NULL;
}

static void reset_notifies_removal_of_every_bond(void)
{
    // Reset przyciskiem usuwa wszystkich klientów: wpisy ich tożsamości muszą zniknąć z resolving list kontrolera
    // (stary wpis powodował DHKey check failed przy ponownym parowaniu tego samego telefonu).
    world_init(3);
    ACT.image.clients[0].bond = bond_of(0xA1, 1);
    ACT.image.clients[2].bond = bond_of(0xC3, 3); // klient [1] bez bondu: bez wywołania
    brama_actor_msg_t m = {.type = BRAMA_AMSG_BUTTON_RESET};
    run_msg(&m);
    TEST_ASSERT_EQUAL(0, ACT.image.count);
    TEST_ASSERT_EQUAL(2, W.bonds_removed);
}

static void remove_storage_failure_keeps_everything(void)
{
    world_init(2);
    ACT.image.clients[1].bond = bond_of(0xB2, 5);
    connect_ready(0, CA);
    connect_ready(1, CB);
    W.fnvs.fail_next_write = true;
    remove_cmd(0, CB, 1);
    TEST_ASSERT_EQUAL(BRAMA_ACK_STORAGE_FAILURE, last_ack_result(0));
    TEST_ASSERT_EQUAL(2, ACT.image.count);
    TEST_ASSERT_EQUAL(0, W.bonds_removed);
    TEST_ASSERT_FALSE(W.disconnected[1]);
    TEST_ASSERT_TRUE(ACT.links[1].ready);
}

// --- Naprawa bondu przy zachowanym K_client (9.5) ------------------------------------------------------------------

static brama_bond_t bond_of(uint8_t addr, uint8_t fill)
{
    brama_bond_t b = {.id_addr_type = 1, .len = 88};
    memset(b.id_addr, addr, 6);
    memset(b.data, fill, 88);
    return b;
}

/** Klienci CA i CB z bondami pod adresami 0xAA i 0xBB (po commit — obraz zatwierdzony). */
static void world_with_bonds(void)
{
    world_init(2);
    ACT.image.clients[0].bond = bond_of(0xAA, 1);
    ACT.image.clients[1].bond = bond_of(0xBB, 2);
    brama_image_commit(&W.nvs, &ACT.image);
    W.fnvs.writes = 0;
}

static void repair(int link, const uint8_t *client, brama_bond_t bond)
{
    brama_actor_msg_t m = {.type = BRAMA_AMSG_REPAIR_COMMIT, .link = link, .link_token = 100 + link,
                           .session_token = 300 + link, .bond = bond};
    memcpy(m.client_id, client, 16);
    run_msg(&m);
}

static void repair_replaces_only_bond(void)
{
    // A33/A42: nowy bond tego samego klienta, K_client i reguła bez zmian, jeden zapis NVS.
    world_with_bonds();
    connect_ready(1, CB);
    open_cmd(1, true, 1);
    brama_rule_t rule = ACT.rule;
    uint8_t key_before[32], key_after[32];
    brama_actor_client_key(&ACT, CA, key_before);
    repair(8, CA, bond_of(0xAA, 9)); // ten sam adres tożsamości, nowe klucze
    TEST_ASSERT_EQUAL(1, W.repair_results);
    TEST_ASSERT_EQUAL(8, W.repair_link);
    TEST_ASSERT_EQUAL(308, W.repair_token);
    TEST_ASSERT_EQUAL(0, W.repair_code);
    TEST_ASSERT_FALSE(W.repair_replaced); // ten sam adres tożsamości: nic do usunięcia ze stosu
    TEST_ASSERT_EQUAL(1, W.fnvs.writes);
    TEST_ASSERT_EQUAL(2, ACT.image.count);
    TEST_ASSERT_EQUAL_HEX8(9, ACT.image.clients[brama_image_find(&ACT.image, CA)].bond.data[0]);
    TEST_ASSERT_EQUAL_HEX8(2, ACT.image.clients[brama_image_find(&ACT.image, CB)].bond.data[0]);
    brama_actor_client_key(&ACT, CA, key_after);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(key_before, key_after, 32);
    TEST_ASSERT_EQUAL(rule.phase, ACT.rule.phase);
    TEST_ASSERT_EQUAL(rule.revision, ACT.rule.revision);
    TEST_ASSERT_FALSE(W.disconnected[1]);
    // obraz trwały: ponowny odczyt NVS daje nowy bond
    brama_image_t loaded;
    TEST_ASSERT_EQUAL(BRAMA_IMG_OK, brama_image_load(&W.nvs, &loaded));
    TEST_ASSERT_EQUAL_HEX8(9, loaded.clients[brama_image_find(&loaded, CA)].bond.data[0]);
}

static void repair_with_new_identity_address(void)
{
    world_with_bonds();
    repair(8, CA, bond_of(0xCC, 7));
    TEST_ASSERT_EQUAL(0, W.repair_code);
    TEST_ASSERT_TRUE(W.repair_replaced); // stary peer do usunięcia z resolving list, także gdy łącza już nie ma
    TEST_ASSERT_EQUAL_HEX8(0xAA, W.repair_replaced_addr);
    const brama_bond_t *b = &ACT.image.clients[brama_image_find(&ACT.image, CA)].bond;
    TEST_ASSERT_EQUAL_HEX8(0xCC, b->id_addr[0]); // stary bond (0xAA) usunięty razem z rekordem
    uint8_t key[32], peer[7] = {1, 0xCC, 0xCC, 0xCC, 0xCC, 0xCC, 0xCC};
    TEST_ASSERT_TRUE(brama_actor_client_key_for_peer(&ACT, CA, peer, key));
}

static void repair_blocked_by_active_link_of_client(void)
{
    // A57: naprawa przy aktywnym łączu dotychczasowego klienta — odmowa bez przerywania radia.
    world_with_bonds();
    connect_ready(0, CA);
    TEST_ASSERT_EQUAL(BRAMA_REJECT_REPLACEMENT_BLOCKED, brama_actor_repair_blocker(&ACT, 8, CA, &(brama_bond_t){0}));
    repair(8, CA, bond_of(0xCC, 7));
    TEST_ASSERT_EQUAL(BRAMA_REJECT_REPLACEMENT_BLOCKED, W.repair_code);
    TEST_ASSERT_EQUAL(0, W.fnvs.writes);
    TEST_ASSERT_FALSE(W.disconnected[0]);
    TEST_ASSERT_EQUAL_HEX8(0xAA, ACT.image.clients[0].bond.id_addr[0]);
    // łącze naprawy samo nie blokuje (np. wcześniejsza sesja na nim)
    TEST_ASSERT_EQUAL(0, brama_actor_repair_blocker(&ACT, 0, CA, &(brama_bond_t){0}));
}

static void repair_blocked_when_identity_belongs_to_other_client(void)
{
    world_with_bonds();
    brama_bond_t foreign = bond_of(0xBB, 7);
    TEST_ASSERT_EQUAL(BRAMA_REJECT_REPLACEMENT_BLOCKED, brama_actor_repair_blocker(&ACT, 8, CA, &foreign));
    repair(8, CA, foreign);
    TEST_ASSERT_EQUAL(BRAMA_REJECT_REPLACEMENT_BLOCKED, W.repair_code);
    TEST_ASSERT_FALSE(W.repair_replaced);
    TEST_ASSERT_EQUAL(0, W.fnvs.writes);
}

static void repair_storage_failure_keeps_old_bond(void)
{
    // A25: błąd NVS — stary ACTIVE/bond zostaje.
    world_with_bonds();
    W.fnvs.fail_next_write = true;
    repair(8, CA, bond_of(0xAA, 9));
    TEST_ASSERT_EQUAL(BRAMA_REJECT_STORAGE_FAILURE, W.repair_code);
    TEST_ASSERT_EQUAL_HEX8(1, ACT.image.clients[0].bond.data[0]);
}

static void repair_rejected_after_reset_or_unknown_client(void)
{
    // A43: reset przed transakcją zmienia generację — dowód nieaktualny.
    world_with_bonds();
    brama_actor_msg_t m = {.type = BRAMA_AMSG_REPAIR_COMMIT, .link = 8, .link_token = 108, .session_token = 308,
                           .bond = bond_of(0xAA, 9)};
    memcpy(m.client_id, CA, 16);
    m.auth_generation = ACT.image.auth_generation;
    brama_actor_msg_t r = {.type = BRAMA_AMSG_BUTTON_RESET};
    TEST_ASSERT_TRUE(brama_actor_post(&ACT, &m));
    run_msg(&r); // reset ma pierwszeństwo, naprawa przetwarzana po nim
    TEST_ASSERT_EQUAL(1, W.repair_results);
    TEST_ASSERT_TRUE(W.repair_code != 0);
    TEST_ASSERT_EQUAL(0, ACT.image.count);
    // nieznany klient i brak bondu w wiadomości
    world_with_bonds();
    repair(8, CC, bond_of(0xCC, 7));
    TEST_ASSERT_TRUE(W.repair_code != 0);
    repair(8, CA, (brama_bond_t){0});
    TEST_ASSERT_TRUE(W.repair_code != 0);
    TEST_ASSERT_EQUAL(0, W.fnvs.writes);
}

static void reset_keeps_control_disabled_when_config_broken(void)
{
    // I9: tryb błędu z konfiguracji lokalnej lub danych fabrycznych — reset nie przywraca sterowania.
    world_init(1);
    ACT.control_enabled = false;
    ACT.reset_restores_control = false;
    brama_actor_msg_t r = {.type = BRAMA_AMSG_BUTTON_RESET};
    run_msg(&r);
    TEST_ASSERT_EQUAL(0, ACT.image.count);
    TEST_ASSERT_FALSE(ACT.control_enabled);
}

void run_actor_tests(void)
{
    RUN_TEST(open_true_arms_and_triggers);
    RUN_TEST(close_disarms_then_pulses);
    RUN_TEST(other_client_open_is_lease_owned_by_other);
    RUN_TEST(interlock_blocks_both_commands);
    RUN_TEST(secondCommandWithinGapIsBusy);
    RUN_TEST(staleSessionTokenIsDropped);
    RUN_TEST(invalid_argument_and_unsupported_type);
    RUN_TEST(io_fault_reports_and_sets_fault);
    RUN_TEST(auto_close_after_waiting_deadline);
    RUN_TEST(auto_close_waits_for_busy_at_most_2s);
    RUN_TEST(busy_over_2s_is_fault);
    RUN_TEST(interlock_at_deadline_blocks);
    RUN_TEST(pair_commit_writes_image_once);
    RUN_TEST(pair_commit_gives_empty_name_regardless_of_stack_contents);
    RUN_TEST(pair_commit_storage_failure_keeps_image);
    RUN_TEST(normal_operation_writes_no_nvs);
    RUN_TEST(reset_invalidates_tokens_and_disarms_without_pulse);
    RUN_TEST(reset_has_priority_over_queued_commands);
    RUN_TEST(failed_reset_schedules_single_close_after_30s);
    RUN_TEST(rule_status_sent_to_every_ready_session);
    RUN_TEST(owner_return_cancels_waiting);
    RUN_TEST(invalidated_session_drops_queued_command);
    RUN_TEST(pair_commit_without_bond_is_rejected);
    RUN_TEST(reset_stops_running_pulse);
    RUN_TEST(client_key_requires_matching_bond);
    RUN_TEST(reset_keeps_control_disabled_when_config_broken);
    RUN_TEST(phone_opens_pairing_window);
    RUN_TEST(phone_window_rejections);
    RUN_TEST(list_reports_clients_with_flags_and_names);
    RUN_TEST(list_with_payload_is_invalid_argument);
    RUN_TEST(client_list_matches_kotlin_vectors);
    RUN_TEST(set_name_commits_without_changing_auth_generation);
    RUN_TEST(set_name_rejections_leave_image_untouched);
    RUN_TEST(set_name_storage_failure_keeps_old_image);
    RUN_TEST(remove_disconnected_client_commits_and_keeps_generation);
    RUN_TEST(remove_connected_client_drops_link_and_keeps_other_rule);
    RUN_TEST(commands_of_removed_client_queued_before_commit_are_dropped);
    RUN_TEST(remove_refusals_change_nothing);
    RUN_TEST(remove_waiting_owner_is_refused_until_auto_close);
    RUN_TEST(remove_is_busy_during_ota);
    RUN_TEST(remove_storage_failure_keeps_everything);
    RUN_TEST(reset_notifies_removal_of_every_bond);
    RUN_TEST(repair_replaces_only_bond);
    RUN_TEST(repair_with_new_identity_address);
    RUN_TEST(repair_blocked_by_active_link_of_client);
    RUN_TEST(repair_blocked_when_identity_belongs_to_other_client);
    RUN_TEST(repair_storage_failure_keeps_old_bond);
    RUN_TEST(repair_rejected_after_reset_or_unknown_client);
}
