// Przypadki negatywne wektorów zgodności (spec 16.3, doc/vectors_design.md): sterownik jako serwer na ramkach c2s
// z oczekiwanym skutkiem (`<przypadek>_expect`), kodery snapshotu i ACK, składanie fragmentów. Te same pliki
// sprawdza telefon (`:protocol`, NegativeVectorsTest).
#include <stdio.h>
#include <string.h>
#include "unity.h"
#include "brama_err.h"
#include "brama_session.h"
#include "test_registry.h"
#include "test_vectors.h"

enum { EXPECT_IGNORED = 0x00, EXPECT_INVALIDATED = 0x01, EXPECT_REPLY = 0x02, EXPECT_COMMAND = 0x03 };

// --- Środowisko sesji (jak test_session.c, bez PAKE z klientem mbedTLS) ---------------------------------------------

static struct {
    uint8_t device_id[16], k_setup[32], client_id[16], k_client[32];
    bool window;
    uint8_t rng_queue[128];
    size_t rng_len, rng_pos;
} ne;

static brama_session_env_t env;
static brama_session_t ss;
static brama_step_t step;
static const uint8_t PIN[6] = {'4', '8', '2', '9', '1', '5'};

static void rng_fill(void *ctx, uint8_t *buf, size_t len)
{
    (void)ctx;
    for (size_t i = 0; i < len; i++) buf[i] = ne.rng_pos < ne.rng_len ? ne.rng_queue[ne.rng_pos++] : (uint8_t)(0xA5 ^ i);
}

static void rng_push(const uint8_t *b, size_t len)
{
    memcpy(ne.rng_queue + ne.rng_len, b, len);
    ne.rng_len += len;
}

static bool client_key(void *ctx, const uint8_t id[16], uint8_t out[32])
{
    (void)ctx;
    if (memcmp(id, ne.client_id, 16) != 0) return false;
    memcpy(out, ne.k_client, 32);
    return true;
}

static uint8_t pair_admit(void *ctx, const uint8_t id[16], bool via_pin) { (void)ctx, (void)id, (void)via_pin; return 0; }
static uint8_t session_admit(void *ctx, const uint8_t id[16], bool *repair) { (void)ctx, (void)id; *repair = false; return 0; }
static bool window_open(void *ctx) { (void)ctx; return ne.window; }
static uint8_t attempt_blocker(void *ctx) { (void)ctx; return 0; }

static void snapshot_for(void *ctx, const uint8_t id[16], brama_snapshot_t *out)
{
    (void)ctx, (void)id;
    *out = (brama_snapshot_t){1, BRAMA_PHASE_DISARMED, BRAMA_OWNER_NONE, BRAMA_REASON_NONE, 0};
}

/** Świeży kontekst z wartościami z ostatnio wczytanego pliku. */
static void fresh(void)
{
    memset(&ne, 0, sizeof ne);
    memcpy(ne.device_id, vec("device_id", NULL), 16);
    memcpy(ne.client_id, vec("client_id", NULL), 16);
    memcpy(ne.k_client, vec("k_client", NULL), 32);
    memset(ne.k_setup, 0x77, 32);
    ne.window = true;
    env = (brama_session_env_t){
        .device_id = ne.device_id, .k_setup = ne.k_setup, .pin = PIN, .rng = {rng_fill, NULL},
        .client_key = client_key, .pair_admit = pair_admit, .session_admit = session_admit, .window_open = window_open,
        .attempt_blocker = attempt_blocker, .snapshot_for = snapshot_for, .ctx = NULL};
    brama_session_init(&ss);
}

static void send_key(const char *key)
{
    size_t len;
    const uint8_t *f = vec(key, &len);
    brama_session_on_frame(&ss, &env, f, len, &step);
}

static void expect_frame(const char *key, const uint8_t *actual, size_t actual_len)
{
    size_t len;
    const uint8_t *expected = vec(key, &len);
    TEST_ASSERT_EQUAL_MESSAGE(len, actual_len, key);
    TEST_ASSERT_EQUAL_HEX8_ARRAY_MESSAGE(expected, actual, len, key);
}

/** Sesja READY z wymiany bazowej pliku (c2s_1..s2c_2). */
static void establish(void)
{
    fresh();
    rng_push(vec("session_id", NULL), 8);
    rng_push(vec("server_nonce", NULL), 32);
    send_key("c2s_1");
    expect_frame("s2c_1", step.reply, step.reply_len);
    send_key("c2s_2");
    expect_frame("s2c_2", step.reply, step.reply_len);
    TEST_ASSERT_EQUAL(BRAMA_EV_SESSION_READY, step.event);
}

static uint8_t expect_code(const char *name)
{
    char key[48];
    snprintf(key, sizeof key, "%s_expect", name);
    return vec(key, NULL)[0];
}

/** Ramka `<name>_frame` na bieżącym kontekście i skutek zgodny z `<name>_expect`. */
static void check_case(const char *name)
{
    char key[48];
    snprintf(key, sizeof key, "%s_frame", name);
    bool was_ready = brama_session_ready(&ss);
    send_key(key);
    switch (expect_code(name)) {
    case EXPECT_IGNORED:
        TEST_ASSERT_EQUAL_MESSAGE(BRAMA_EV_NONE, step.event, name);
        TEST_ASSERT_EQUAL_MESSAGE(0, step.reply_len, name);
        TEST_ASSERT_EQUAL_MESSAGE(was_ready, brama_session_ready(&ss), name);
        break;
    case EXPECT_INVALIDATED:
        TEST_ASSERT_EQUAL_MESSAGE(BRAMA_EV_INVALIDATED, step.event, name);
        TEST_ASSERT_EQUAL_MESSAGE(0, step.reply_len, name);
        TEST_ASSERT_FALSE_MESSAGE(brama_session_ready(&ss), name);
        break;
    case EXPECT_REPLY:
        snprintf(key, sizeof key, "%s_reply", name);
        expect_frame(key, step.reply, step.reply_len);
        break;
    case EXPECT_COMMAND: {
        TEST_ASSERT_EQUAL_MESSAGE(BRAMA_EV_COMMAND, step.event, name);
        snprintf(key, sizeof key, "%s_type", name);
        TEST_ASSERT_EQUAL_HEX8_MESSAGE(vec(key, NULL)[0], step.type, name);
        snprintf(key, sizeof key, "%s_plain", name);
        expect_frame(key, step.payload, step.payload_len);
        size_t pl, olen;
        snprintf(key, sizeof key, "%s_ack_plain", name);
        const uint8_t *ack = vec(key, &pl);
        uint8_t out[BRAMA_FRAME_MAX];
        TEST_ASSERT_EQUAL(BRAMA_OK, brama_session_respond(&ss, step.seq, BRAMA_MSG_COMMAND_ACK, ack, pl, out, &olen));
        snprintf(key, sizeof key, "%s_ack", name);
        expect_frame(key, out, olen);
        break;
    }
    default: TEST_FAIL_MESSAGE(name);
    }
}

// --- negative_session.txt -------------------------------------------------------------------------------------------

static void session_cases_match_expectations(void)
{
    vec_load("vectors/negative_session.txt");
    const char *cases[] = {"header_bit", "payload_bit", "tag_bit", "seq_gap", "prev_session", "version1",
                           "wrong_direction", "reject_via_rx", "open_bool_02", "close_extra_byte", "unknown_type"};
    for (size_t i = 0; i < sizeof cases / sizeof cases[0]; i++) {
        establish();
        check_case(cases[i]);
    }
}

static void same_seq_with_other_content_invalidates(void)
{
    vec_load("vectors/negative_session.txt");
    establish();
    send_key("same_seq_changed_first");
    TEST_ASSERT_EQUAL(BRAMA_EV_COMMAND, step.event);
    check_case("same_seq_changed");
}

static void rekey_on_ready_link(void)
{
    vec_load("vectors/negative_session.txt");
    establish();
    rng_push(vec("rekey_session_id", NULL), 8);
    rng_push(vec("rekey_server_nonce", NULL), 32);
    send_key("rekey_begin");
    expect_frame("rekey_challenge", step.reply, step.reply_len);
    TEST_ASSERT_EQUAL(BRAMA_EV_INVALIDATED, step.event); // stara sesja zastąpiona (12.1)
    send_key("rekey_confirm");
    expect_frame("rekey_ready", step.reply, step.reply_len);
    TEST_ASSERT_EQUAL(BRAMA_EV_SESSION_READY, step.event);
}

// --- negative_handshake.txt -----------------------------------------------------------------------------------------

static void handshake_cases_are_auth_failed(void)
{
    vec_load("vectors/negative_handshake.txt");
    const char *cases[] = {"begin_wrong_device_id", "begin_unknown_client", "begin_wrong_key", "confirm_without_begin",
                           "reflection", "pake_bad_zkp"};
    for (size_t i = 0; i < sizeof cases / sizeof cases[0]; i++) {
        fresh();
        check_case(cases[i]);
    }
    fresh();
    ne.window = false; // 11.4: PAKE poza oknem
    check_case("pake_outside_window");
}

// --- negative_client.txt: bajty generowane przez sterownik ---------------------------------------------------------

static void status_before_ack_bytes(void)
{
    vec_load("vectors/negative_client.txt");
    establish();
    send_key("status_before_ack_c2s");
    TEST_ASSERT_EQUAL(BRAMA_EV_COMMAND, step.event);
    uint8_t out[BRAMA_FRAME_MAX];
    size_t pl, olen;
    const uint8_t *status = vec("status_before_ack_status_plain", &pl);
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_session_seal_event(&ss, BRAMA_MSG_RULE_STATUS, status, pl, out, &olen));
    expect_frame("status_before_ack_status", out, olen);
    const uint8_t *ack = vec("status_before_ack_ack_plain", &pl);
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_session_respond(&ss, step.seq, BRAMA_MSG_COMMAND_ACK, ack, pl, out, &olen));
    expect_frame("status_before_ack_ack", out, olen);
}

static const char *PHASES[] = {"disarmed", "monitoring", "waiting", "boot_wait", "blocked", "fault"};
static const char *OWNERS[] = {"none", "self", "other"};
static const char *REASONS[] = {"none", "link_lost", "reboot", "interlock", "io_fault", "storage_failure", "busy_timeout"};

static void every_snapshot_combination(void)
{
    vec_load("vectors/negative_client.txt");
    int valid = 0;
    for (uint8_t p = 0; p < 6; p++) {
        for (uint8_t o = 0; o < 3; o++) {
            for (uint8_t r = 0; r < 7; r++) {
                bool deadline = p == BRAMA_PHASE_WAITING || p == BRAMA_PHASE_BOOT_WAIT;
                brama_snapshot_t s = {0x0102030405060708ull, p, o, r, deadline ? 12345 : 0};
                char key[48];
                snprintf(key, sizeof key, "snapshot_%s_%s_%s", PHASES[p], OWNERS[o], REASONS[r]);
                // Wektor istnieje dokładnie dla kombinacji poprawnych po obu stronach (12.2).
                TEST_ASSERT_EQUAL_MESSAGE(brama_snapshot_valid(&s), vec_has(key), key);
                if (!vec_has(key)) continue;
                valid++;
                uint8_t out[BRAMA_SNAPSHOT_LEN];
                brama_snapshot_encode(&s, out);
                expect_frame(key, out, sizeof out);
            }
        }
    }
    TEST_ASSERT_EQUAL(16, valid);
    const char *invalid[] = {"disarmed_self", "monitoring_none", "waiting_reboot", "monitoring_remaining", "phase", "owner", "reason"};
    for (size_t i = 0; i < sizeof invalid / sizeof invalid[0]; i++) {
        char key[48];
        snprintf(key, sizeof key, "snapshot_invalid_%s", invalid[i]);
        size_t len;
        const uint8_t *b = vec(key, &len);
        TEST_ASSERT_EQUAL(BRAMA_SNAPSHOT_LEN, len);
        uint64_t rev = 0;
        for (int j = 7; j >= 0; j--) rev = (rev << 8) | b[j];
        brama_snapshot_t s = {rev, b[8], b[9], b[10], (uint32_t)b[11] | (uint32_t)b[12] << 8 | (uint32_t)b[13] << 16 | (uint32_t)b[14] << 24};
        TEST_ASSERT_FALSE_MESSAGE(brama_snapshot_valid(&s), key);
    }
}

static void every_ack_result(void)
{
    vec_load("vectors/negative_client.txt");
    const char *names[] = {"output_triggered", "busy", "lease_owned_by_other", "interlock_blocked",
                           "storage_failure", "io_fault", "invalid_argument", "unsupported_type", "window_opened",
                           "client_removed", "name_set", "client_not_found", "cannot_remove_self", "client_owns_rule"};
    brama_snapshot_t s = {0x0102030405060708ull, BRAMA_PHASE_FAULT, BRAMA_OWNER_OTHER, BRAMA_REASON_IO_FAULT, 0};
    for (uint8_t r = 0; r < sizeof names / sizeof names[0]; r++) {
        uint8_t out[BRAMA_ACK_LEN];
        brama_ack_encode(BRAMA_MSG_OPEN, r, 0x1112131415161718ull, &s, out);
        char key[48];
        snprintf(key, sizeof key, "ack_%s", names[r]);
        expect_frame(key, out, sizeof out);
    }
}

// --- negative_fragments.txt -----------------------------------------------------------------------------------------

static void fragment_restart_rules(void)
{
    vec_load("vectors/negative_fragments.txt");
    brama_budget_t b = brama_budget(23, 30, 0);
    const char *prefixes[] = {"restart_new_tid_in", "restart_same_tid_in"};
    for (int c = 0; c < 2; c++) {
        brama_reasm_t r;
        brama_reasm_init(&r);
        int completes = 0;
        for (int i = 0; i < 4; i++) {
            char key[48];
            snprintf(key, sizeof key, "%s_%d", prefixes[c], i);
            size_t len, dlen = 0;
            const uint8_t *frag = vec(key, &len);
            const uint8_t *done = NULL;
            brama_reasm_result_t rc = brama_reasm_accept(&r, frag, len, 1000 + i, &b, &done, &dlen);
            if (c == 1 && i == 1) TEST_ASSERT_EQUAL_MESSAGE(BRAMA_REASM_DROPPED, rc, "ten sam transfer_id: błąd kolejności");
            if (rc == BRAMA_REASM_COMPLETE) {
                completes++;
                TEST_ASSERT_EQUAL(3, i);
                expect_frame("frame", done, dlen);
            }
        }
        TEST_ASSERT_EQUAL_MESSAGE(c == 0 ? 1 : 0, completes, prefixes[c]);
    }
}

void run_negative_tests(void)
{
    RUN_TEST(session_cases_match_expectations);
    RUN_TEST(same_seq_with_other_content_invalidates);
    RUN_TEST(rekey_on_ready_link);
    RUN_TEST(handshake_cases_are_auth_failed);
    RUN_TEST(status_before_ack_bytes);
    RUN_TEST(every_snapshot_combination);
    RUN_TEST(every_ack_result);
    RUN_TEST(fragment_restart_rules);
}
