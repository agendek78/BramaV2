#include <string.h>
#include "unity.h"
#include "brama_actor.h"
#include "brama_crypto.h"
#include "brama_err.h"
#include "brama_ota.h"
#include "test_actor_env.h"
#include "test_registry.h"

// --- Fałszywy port OTA -----------------------------------------------------------------------------------------------

static struct {
    uint8_t slot[4096];
    size_t written;
    int begins, writes, ends, set_boots, aborts;
    bool fail_write, fail_end;
    uint32_t running_sv;
} P;

static esp_err_t p_begin(void *c, uint32_t size) { (void)c, (void)size; P.begins++; P.written = 0; return ESP_OK; }
static esp_err_t p_write(void *c, const uint8_t *d, size_t len)
{
    (void)c;
    P.writes++;
    if (P.fail_write) return ESP_FAIL;
    memcpy(P.slot + P.written, d, len);
    P.written += len;
    return ESP_OK;
}
static esp_err_t p_end(void *c) { (void)c; P.ends++; return P.fail_end ? ESP_FAIL : ESP_OK; }
static esp_err_t p_set_boot(void *c) { (void)c; P.set_boots++; return ESP_OK; }
static void p_abort(void *c) { (void)c; P.aborts++; }
static uint32_t p_sv(void *c) { (void)c; return P.running_sv; }

static brama_ota_t OTA;
static uint8_t IMG[1000];
static uint8_t SHA[32];
#define T0 (10LL * 1000 * 1000)

static void build_image(uint32_t secure_version, const char *project, uint8_t chip)
{
    memset(IMG, 0x5A, sizeof IMG);
    IMG[0] = 0xE9;
    IMG[12] = chip;
    IMG[13] = 0;
    const uint32_t magic = 0xABCD5432;
    memcpy(IMG + 32, &magic, 4);
    memcpy(IMG + 36, &secure_version, 4);
    memset(IMG + 80, 0, 32);
    memcpy(IMG + 80, project, strlen(project));
    brama_sha256(IMG, sizeof IMG, SHA);
}

static void ota_setup(void)
{
    memset(&P, 0, sizeof P);
    P.running_sv = 3;
    brama_ota_port_t port = {p_begin, p_write, p_end, p_set_boot, p_abort, p_sv, NULL};
    brama_ota_init(&OTA, &port);
    build_image(3, "brama", 0);
}

static brama_ota_result_t send_all(uint64_t token)
{
    uint32_t next = 0;
    for (uint32_t off = 0; off < sizeof IMG; off += 470) {
        size_t n = sizeof IMG - off < 470 ? sizeof IMG - off : 470;
        brama_ota_result_t r = brama_ota_data(&OTA, token, off, IMG + off, n, T0 + off, &next);
        if (r != BRAMA_OTA_OK) return r;
    }
    TEST_ASSERT_EQUAL(sizeof IMG, next);
    return BRAMA_OTA_OK;
}

static void full_transfer_sets_boot_partition(void)
{
    ota_setup();
    TEST_ASSERT_EQUAL(BRAMA_OTA_OK, brama_ota_begin(&OTA, 7, 247, sizeof IMG, SHA, T0));
    TEST_ASSERT_EQUAL(BRAMA_OTA_OK, send_all(7));
    TEST_ASSERT_EQUAL(BRAMA_OTA_OK, brama_ota_end(&OTA, 7));
    TEST_ASSERT_EQUAL(1, P.ends);
    TEST_ASSERT_EQUAL(1, P.set_boots);
    TEST_ASSERT_EQUAL(0, P.aborts);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(IMG, P.slot, sizeof IMG);
    TEST_ASSERT_FALSE(OTA.active);
}

static brama_ota_result_t first_chunk_result(void)
{
    brama_ota_begin(&OTA, 7, 247, sizeof IMG, SHA, T0);
    uint32_t next;
    return brama_ota_data(&OTA, 7, 0, IMG, 470, T0, &next);
}

static void header_rejects_foreign_project_chip_magic(void)
{
    ota_setup();
    build_image(3, "inny", 0);
    TEST_ASSERT_EQUAL(BRAMA_OTA_IMAGE_REJECTED, first_chunk_result());
    build_image(3, "brama", 5); // ESP32-C3
    TEST_ASSERT_EQUAL(BRAMA_OTA_IMAGE_REJECTED, first_chunk_result());
    build_image(3, "brama", 0);
    IMG[0] = 0xE8;
    TEST_ASSERT_EQUAL(BRAMA_OTA_IMAGE_REJECTED, first_chunk_result());
    build_image(3, "brama", 0);
    IMG[32] = 0; // zły magic esp_app_desc_t
    TEST_ASSERT_EQUAL(BRAMA_OTA_IMAGE_REJECTED, first_chunk_result());
    build_image(3, "bramaX", 0); // prefiks nie wystarcza
    TEST_ASSERT_EQUAL(BRAMA_OTA_IMAGE_REJECTED, first_chunk_result());
    TEST_ASSERT_EQUAL(0, P.writes);
    TEST_ASSERT_FALSE(OTA.active);
}

static void downgrade_rejected_before_first_write(void)
{
    ota_setup();
    build_image(2, "brama", 0);
    TEST_ASSERT_EQUAL(BRAMA_OTA_DOWNGRADE, first_chunk_result());
    TEST_ASSERT_EQUAL(0, P.writes);
    TEST_ASSERT_TRUE(P.aborts >= 1);
    build_image(4, "brama", 0); // wyższy numer jest dozwolony
    TEST_ASSERT_EQUAL(BRAMA_OTA_OK, first_chunk_result());
}

static void short_first_chunk_is_rejected(void)
{
    ota_setup();
    brama_ota_begin(&OTA, 7, 247, sizeof IMG, SHA, T0);
    uint32_t next;
    TEST_ASSERT_EQUAL(BRAMA_OTA_IMAGE_REJECTED, brama_ota_data(&OTA, 7, 0, IMG, 100, T0, &next));
}

static void size_over_slot_is_too_large(void)
{
    ota_setup();
    TEST_ASSERT_EQUAL(BRAMA_OTA_TOO_LARGE, brama_ota_begin(&OTA, 7, 247, BRAMA_OTA_MAX_IMAGE + 1, SHA, T0));
    TEST_ASSERT_EQUAL(BRAMA_OTA_TOO_LARGE, brama_ota_begin(&OTA, 7, 247, 0, SHA, T0));
    TEST_ASSERT_EQUAL(0, P.begins);
}

static void mtu_below_247_is_link_too_small(void)
{
    ota_setup();
    TEST_ASSERT_EQUAL(BRAMA_OTA_LINK_TOO_SMALL, brama_ota_begin(&OTA, 7, 246, sizeof IMG, SHA, T0));
    TEST_ASSERT_EQUAL(BRAMA_OTA_OK, brama_ota_begin(&OTA, 7, 517, sizeof IMG, SHA, T0));
}

static void wrong_offset_aborts_transfer(void)
{
    ota_setup();
    brama_ota_begin(&OTA, 7, 247, sizeof IMG, SHA, T0);
    uint32_t next;
    TEST_ASSERT_EQUAL(BRAMA_OTA_OK, brama_ota_data(&OTA, 7, 0, IMG, 470, T0, &next));
    TEST_ASSERT_EQUAL(BRAMA_OTA_BAD_OFFSET, brama_ota_data(&OTA, 7, 471, IMG + 471, 10, T0, &next));
    TEST_ASSERT_EQUAL(1, P.aborts);
    TEST_ASSERT_EQUAL(BRAMA_OTA_NOT_IN_PROGRESS, brama_ota_data(&OTA, 7, 470, IMG + 470, 10, T0, &next));
    TEST_ASSERT_EQUAL(0, next);
}

static void sha_mismatch_at_end_is_image_rejected(void)
{
    ota_setup();
    SHA[0] ^= 1;
    brama_ota_begin(&OTA, 7, 247, sizeof IMG, SHA, T0);
    send_all(7);
    TEST_ASSERT_EQUAL(BRAMA_OTA_IMAGE_REJECTED, brama_ota_end(&OTA, 7));
    TEST_ASSERT_EQUAL(0, P.set_boots);
    TEST_ASSERT_FALSE(OTA.active);
}

static void incomplete_image_at_end_is_rejected(void)
{
    ota_setup();
    brama_ota_begin(&OTA, 7, 247, sizeof IMG, SHA, T0);
    uint32_t next;
    brama_ota_data(&OTA, 7, 0, IMG, 470, T0, &next);
    TEST_ASSERT_EQUAL(BRAMA_OTA_IMAGE_REJECTED, brama_ota_end(&OTA, 7));
    TEST_ASSERT_EQUAL(0, P.set_boots);
}

static void signature_failure_at_end_is_image_rejected(void)
{
    ota_setup();
    P.fail_end = true; // esp_ota_end: nieprawidłowy podpis
    brama_ota_begin(&OTA, 7, 247, sizeof IMG, SHA, T0);
    send_all(7);
    TEST_ASSERT_EQUAL(BRAMA_OTA_IMAGE_REJECTED, brama_ota_end(&OTA, 7));
    TEST_ASSERT_EQUAL(0, P.set_boots);
    TEST_ASSERT_EQUAL(0, P.aborts); // po end transfer już zamknięty po stronie IDF
}

static void write_error_is_storage_failure_and_aborts(void)
{
    ota_setup();
    P.fail_write = true;
    TEST_ASSERT_EQUAL(BRAMA_OTA_STORAGE_FAILURE, first_chunk_result());
    TEST_ASSERT_EQUAL(1, P.aborts);
    TEST_ASSERT_FALSE(OTA.active);
}

static void second_session_begin_is_busy(void)
{
    ota_setup();
    brama_ota_begin(&OTA, 7, 247, sizeof IMG, SHA, T0);
    TEST_ASSERT_EQUAL(BRAMA_OTA_BUSY, brama_ota_begin(&OTA, 8, 247, sizeof IMG, SHA, T0));
    TEST_ASSERT_TRUE(brama_ota_owned_by(&OTA, 7));
}

static void data_from_other_session_is_rejected(void)
{
    ota_setup();
    brama_ota_begin(&OTA, 7, 247, sizeof IMG, SHA, T0);
    uint32_t next;
    TEST_ASSERT_EQUAL(BRAMA_OTA_NOT_IN_PROGRESS, brama_ota_data(&OTA, 8, 0, IMG, 470, T0, &next));
    TEST_ASSERT_TRUE(OTA.active); // cudza sesja nie przerywa transferu
    TEST_ASSERT_EQUAL(BRAMA_OTA_NOT_IN_PROGRESS, brama_ota_end(&OTA, 8));
}

static void idle_30s_aborts(void)
{
    ota_setup();
    brama_ota_begin(&OTA, 7, 247, sizeof IMG, SHA, T0);
    TEST_ASSERT_FALSE(brama_ota_tick(&OTA, T0 + BRAMA_OTA_IDLE_US));
    TEST_ASSERT_TRUE(brama_ota_tick(&OTA, T0 + BRAMA_OTA_IDLE_US + 1));
    TEST_ASSERT_EQUAL(1, P.aborts);
    TEST_ASSERT_FALSE(OTA.active);
}

static void abort_never_calls_set_boot(void)
{
    ota_setup();
    brama_ota_begin(&OTA, 7, 247, sizeof IMG, SHA, T0);
    send_all(7);
    brama_ota_abort(&OTA);
    TEST_ASSERT_EQUAL(0, P.set_boots);
    TEST_ASSERT_EQUAL(0, P.ends);
    TEST_ASSERT_EQUAL(BRAMA_OTA_NOT_IN_PROGRESS, brama_ota_end(&OTA, 7));
}

// --- Aktor: bramkowanie BEGIN/END regułą i tokenami -------------------------------------------------------------------

static int restarts;
static void on_restart(void *ctx, int link) { (void)ctx, (void)link; restarts++; }

static void actor_ota_setup(void)
{
    world_init(2);
    ota_setup();
    ACT.ota = &OTA;
    ACT.env.ota_restart = on_restart;
    restarts = 0;
    connect_ready(0, CA);
    connect_ready(1, CB);
}

static void ota_cmd(int link, uint8_t type, const uint8_t *p, size_t len, uint64_t seq)
{
    brama_actor_msg_t m = {.type = BRAMA_AMSG_COMMAND, .link = link, .link_token = 100 + link,
                           .session_token = 200 + link, .cmd_type = type, .payload_len = len, .seq = seq, .mtu = 247};
    if (len) memcpy(m.payload, p, len);
    run_msg(&m);
}

static uint8_t last_ota_result(int link)
{
    const sent_t *s = last_to(link, BRAMA_MSG_OTA_ACK);
    TEST_ASSERT_NOT_NULL(s);
    TEST_ASSERT_EQUAL(14, s->len);
    return s->payload[1];
}

static void begin_payload(uint8_t out[36])
{
    uint32_t size = sizeof IMG;
    memcpy(out, &size, 4);
    memcpy(out + 4, SHA, 32);
}

static void begin_with_armed_rule_is_rule_active(void)
{
    actor_ota_setup();
    open_cmd(0, true, 1);
    uint8_t b[36];
    begin_payload(b);
    ota_cmd(1, BRAMA_MSG_OTA_BEGIN, b, 36, 1);
    TEST_ASSERT_EQUAL(BRAMA_OTA_RULE_ACTIVE, last_ota_result(1));
    TEST_ASSERT_FALSE(OTA.active);
}

static void end_with_armed_rule_is_rejected_and_retryable(void)
{
    actor_ota_setup();
    uint8_t b[36];
    begin_payload(b);
    ota_cmd(1, BRAMA_MSG_OTA_BEGIN, b, 36, 1);
    TEST_ASSERT_EQUAL(BRAMA_OTA_OK, last_ota_result(1));
    TEST_ASSERT_EQUAL(BRAMA_OTA_OK, send_all(201));
    open_cmd(0, true, 1); // OPEN(true) innego telefonu w trakcie transferu
    ota_cmd(1, BRAMA_MSG_OTA_END, NULL, 0, 2);
    TEST_ASSERT_EQUAL(BRAMA_OTA_RULE_ACTIVE, last_ota_result(1));
    TEST_ASSERT_TRUE(OTA.active); // przyjęte dane zachowane
    TEST_ASSERT_EQUAL(0, P.set_boots);
    W.now_us += 2000000;
    command(0, BRAMA_MSG_CLOSE, NULL, 0, 2);
    ota_cmd(1, BRAMA_MSG_OTA_END, NULL, 0, 3);
    TEST_ASSERT_EQUAL(BRAMA_OTA_OK, last_ota_result(1));
    TEST_ASSERT_EQUAL(1, P.set_boots);
    TEST_ASSERT_EQUAL(1, restarts);
}

static void rejected_end_refreshes_idle_timer(void)
{
    // A77: rozbrojenie reguły może trwać dłużej niż 30 s od ostatniego DATA — odrzucony END odnawia licznik.
    actor_ota_setup();
    uint8_t b[36];
    begin_payload(b);
    ota_cmd(1, BRAMA_MSG_OTA_BEGIN, b, 36, 1);
    TEST_ASSERT_EQUAL(BRAMA_OTA_OK, send_all(201)); // ostatnie DATA dawno przed W.now_us
    open_cmd(0, true, 1);
    int64_t end_at = W.now_us;
    ota_cmd(1, BRAMA_MSG_OTA_END, NULL, 0, 2);
    TEST_ASSERT_EQUAL(BRAMA_OTA_RULE_ACTIVE, last_ota_result(1));
    tick_to(end_at + BRAMA_OTA_IDLE_US);
    TEST_ASSERT_TRUE(OTA.active);
    tick_to(end_at + BRAMA_OTA_IDLE_US + 1); // nadal 30 s bez postępu: porzucenie
    TEST_ASSERT_FALSE(OTA.active);
}

static void foreign_end_does_not_refresh_idle_timer(void)
{
    actor_ota_setup();
    uint8_t b[36];
    begin_payload(b);
    ota_cmd(1, BRAMA_MSG_OTA_BEGIN, b, 36, 1);
    TEST_ASSERT_EQUAL(BRAMA_OTA_OK, send_all(201));
    open_cmd(0, true, 1);
    int64_t end_at = W.now_us;
    ota_cmd(0, BRAMA_MSG_OTA_END, NULL, 0, 2); // END z cudzej sesji
    TEST_ASSERT_EQUAL(BRAMA_OTA_NOT_IN_PROGRESS, last_ota_result(0));
    tick_to(end_at + 1);
    TEST_ASSERT_FALSE(OTA.active);
}

static void auth_reset_aborts_ota(void)
{
    actor_ota_setup();
    uint8_t b[36];
    begin_payload(b);
    ota_cmd(1, BRAMA_MSG_OTA_BEGIN, b, 36, 1);
    brama_actor_msg_t r = {.type = BRAMA_AMSG_BUTTON_RESET};
    run_msg(&r);
    TEST_ASSERT_FALSE(OTA.active);
    TEST_ASSERT_EQUAL(1, P.aborts);
}

static void disconnect_of_owner_session_aborts_ota(void)
{
    actor_ota_setup();
    uint8_t b[36];
    begin_payload(b);
    ota_cmd(1, BRAMA_MSG_OTA_BEGIN, b, 36, 1);
    disconnect_link(0); // inne łącze: bez wpływu
    TEST_ASSERT_TRUE(OTA.active);
    disconnect_link(1);
    TEST_ASSERT_FALSE(OTA.active);
}

static void ota_abort_and_bad_payload(void)
{
    actor_ota_setup();
    uint8_t b[36];
    begin_payload(b);
    ota_cmd(1, BRAMA_MSG_OTA_BEGIN, b, 35, 1);
    TEST_ASSERT_EQUAL(BRAMA_OTA_INVALID_ARGUMENT, last_ota_result(1));
    ota_cmd(1, BRAMA_MSG_OTA_BEGIN, b, 36, 2);
    ota_cmd(1, BRAMA_MSG_OTA_ABORT, NULL, 0, 3);
    TEST_ASSERT_EQUAL(BRAMA_OTA_OK, last_ota_result(1));
    TEST_ASSERT_FALSE(OTA.active);
}

static void ota_writes_no_nvs(void)
{
    actor_ota_setup();
    uint8_t b[36];
    begin_payload(b);
    ota_cmd(1, BRAMA_MSG_OTA_BEGIN, b, 36, 1);
    send_all(201);
    ota_cmd(1, BRAMA_MSG_OTA_END, NULL, 0, 2);
    TEST_ASSERT_EQUAL(BRAMA_OTA_OK, last_ota_result(1));
    TEST_ASSERT_EQUAL(0, W.fnvs.writes);
}

static void ota_unsupported_without_port(void)
{
    world_init(1);
    connect_ready(0, CA);
    uint8_t b[36] = {0};
    command(0, BRAMA_MSG_OTA_BEGIN, b, 36, 1);
    TEST_ASSERT_EQUAL(BRAMA_ACK_UNSUPPORTED_TYPE, last_ack_result(0));
}

static void first_boot_table(void)
{
    TEST_ASSERT_EQUAL(BRAMA_FB_NORMAL, brama_ota_first_boot(false, false, true));
    TEST_ASSERT_EQUAL(BRAMA_FB_NORMAL, brama_ota_first_boot(false, true, false));
    TEST_ASSERT_EQUAL(BRAMA_FB_CONFIRM_THEN_MIGRATE, brama_ota_first_boot(true, false, true));
    TEST_ASSERT_EQUAL(BRAMA_FB_ROLLBACK, brama_ota_first_boot(true, false, false));
    TEST_ASSERT_EQUAL(BRAMA_FB_LEAVE_PENDING, brama_ota_first_boot(true, true, true));
}

static void commands_refused_after_ota_end_ok(void)
{
    // I3: po OTA_END OK do restartu żadna komenda nie uzbraja reguły (15.5: DISARMED przy restarcie).
    actor_ota_setup();
    uint8_t b[36];
    begin_payload(b);
    ota_cmd(1, BRAMA_MSG_OTA_BEGIN, b, 36, 1);
    send_all(201);
    ota_cmd(1, BRAMA_MSG_OTA_END, NULL, 0, 2);
    TEST_ASSERT_EQUAL(BRAMA_OTA_OK, last_ota_result(1));
    W.now_us += 2000000;
    open_cmd(0, true, 1);
    TEST_ASSERT_EQUAL(0, W.npulses);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, ACT.rule.phase);
}

void run_ota_tests(void)
{
    RUN_TEST(full_transfer_sets_boot_partition);
    RUN_TEST(header_rejects_foreign_project_chip_magic);
    RUN_TEST(downgrade_rejected_before_first_write);
    RUN_TEST(short_first_chunk_is_rejected);
    RUN_TEST(size_over_slot_is_too_large);
    RUN_TEST(mtu_below_247_is_link_too_small);
    RUN_TEST(wrong_offset_aborts_transfer);
    RUN_TEST(sha_mismatch_at_end_is_image_rejected);
    RUN_TEST(incomplete_image_at_end_is_rejected);
    RUN_TEST(signature_failure_at_end_is_image_rejected);
    RUN_TEST(write_error_is_storage_failure_and_aborts);
    RUN_TEST(second_session_begin_is_busy);
    RUN_TEST(data_from_other_session_is_rejected);
    RUN_TEST(idle_30s_aborts);
    RUN_TEST(abort_never_calls_set_boot);
    RUN_TEST(begin_with_armed_rule_is_rule_active);
    RUN_TEST(end_with_armed_rule_is_rejected_and_retryable);
    RUN_TEST(rejected_end_refreshes_idle_timer);
    RUN_TEST(foreign_end_does_not_refresh_idle_timer);
    RUN_TEST(auth_reset_aborts_ota);
    RUN_TEST(disconnect_of_owner_session_aborts_ota);
    RUN_TEST(ota_abort_and_bad_payload);
    RUN_TEST(ota_writes_no_nvs);
    RUN_TEST(ota_unsupported_without_port);
    RUN_TEST(first_boot_table);
    RUN_TEST(commands_refused_after_ota_end_ok);
}
