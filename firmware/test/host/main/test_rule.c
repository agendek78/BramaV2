#include <string.h>
#include "unity.h"
#include "brama_rule.h"
#include "test_registry.h"

static const uint8_t A[16] = {0xA1}, B[16] = {0xB2};
#define S (1000LL * 1000)

static brama_rule_t fresh(void)
{
    brama_rule_t r;
    memset(&r, 0, sizeof r);
    r.phase = BRAMA_PHASE_DISARMED;
    r.revision = 1;
    r.auth_generation = 1;
    return r;
}

static void open_true_arms_monitoring(void)
{
    brama_rule_t r = fresh();
    TEST_ASSERT_TRUE(brama_rule_open_allowed(&r, A));
    TEST_ASSERT_TRUE(brama_rule_apply_open(&r, A, true));
    TEST_ASSERT_EQUAL(BRAMA_PHASE_MONITORING, r.phase);
    TEST_ASSERT_TRUE(r.has_owner);
    TEST_ASSERT_TRUE(r.owner_link_up);
    TEST_ASSERT_EQUAL(2, r.revision);
}

static void owner_open_true_again_keeps_monitoring_without_revision(void)
{
    brama_rule_t r = fresh();
    brama_rule_apply_open(&r, A, true);
    TEST_ASSERT_FALSE(brama_rule_apply_open(&r, A, true));
    TEST_ASSERT_EQUAL(2, r.revision);
}

static void open_false_and_close_disarm(void)
{
    brama_rule_t r = fresh();
    brama_rule_apply_open(&r, A, true);
    TEST_ASSERT_TRUE(brama_rule_apply_open(&r, A, false));
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, r.phase);
    TEST_ASSERT_FALSE(r.has_owner);
    brama_rule_apply_open(&r, A, true);
    TEST_ASSERT_TRUE(brama_rule_apply_close(&r)); // CLOSE dowolnego klienta
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, r.phase);
}

static void other_client_open_is_not_allowed_while_armed(void)
{
    brama_rule_t r = fresh();
    brama_rule_apply_open(&r, A, true);
    TEST_ASSERT_FALSE(brama_rule_open_allowed(&r, B));
    TEST_ASSERT_TRUE(brama_rule_open_allowed(&r, A));
    r.phase = BRAMA_PHASE_FAULT; // także FAULT/BLOCKED
    r.reason = BRAMA_REASON_IO_FAULT;
    TEST_ASSERT_FALSE(brama_rule_open_allowed(&r, B));
}

static void owner_disconnect_starts_waiting_30s(void)
{
    brama_rule_t r = fresh();
    brama_rule_apply_open(&r, A, true);
    TEST_ASSERT_FALSE(brama_rule_on_owner_disconnected(&r, B, 5 * S)); // nie właściciel
    TEST_ASSERT_TRUE(brama_rule_on_owner_disconnected(&r, A, 5 * S));
    TEST_ASSERT_EQUAL(BRAMA_PHASE_WAITING, r.phase);
    TEST_ASSERT_EQUAL(BRAMA_REASON_LINK_LOST, r.reason);
    TEST_ASSERT_EQUAL(35 * S, r.deadline_us);
    brama_snapshot_t s = brama_rule_snapshot_for(&r, B, 10 * S);
    TEST_ASSERT_EQUAL(BRAMA_OWNER_OTHER, s.owner);
    TEST_ASSERT_EQUAL(25000, s.remaining_ms);
    TEST_ASSERT_TRUE(brama_snapshot_valid(&s));
    // kolejne disconnect nie przesuwają terminu (14.3)
    TEST_ASSERT_FALSE(brama_rule_on_owner_disconnected(&r, A, 20 * S));
    TEST_ASSERT_EQUAL(35 * S, r.deadline_us);
}

static void owner_return_before_deadline_cancels_waiting(void)
{
    brama_rule_t r = fresh();
    brama_rule_apply_open(&r, A, true);
    brama_rule_on_owner_disconnected(&r, A, 0);
    TEST_ASSERT_FALSE(brama_rule_on_authenticated(&r, B, 10 * S)); // inny telefon nie anuluje
    TEST_ASSERT_EQUAL(BRAMA_PHASE_WAITING, r.phase);
    TEST_ASSERT_TRUE(brama_rule_on_authenticated(&r, A, 10 * S));
    TEST_ASSERT_EQUAL(BRAMA_PHASE_MONITORING, r.phase);
    TEST_ASSERT_EQUAL(0, r.deadline_us);
    TEST_ASSERT_EQUAL(BRAMA_CLOSE_NONE, brama_rule_due(&r, 40 * S));
}

static void deadline_attempts_close_then_disarmed(void)
{
    brama_rule_t r = fresh();
    brama_rule_apply_open(&r, A, true);
    brama_rule_on_owner_disconnected(&r, A, 0);
    TEST_ASSERT_EQUAL(BRAMA_CLOSE_NONE, brama_rule_due(&r, 30 * S - 1));
    TEST_ASSERT_EQUAL(BRAMA_CLOSE_AUTO, brama_rule_due(&r, 30 * S));
    // powrót po decyzji o CLOSE nie cofa jej (aktor rozstrzyga w jednej operacji)
    TEST_ASSERT_FALSE(brama_rule_on_authenticated(&r, A, 30 * S));
    TEST_ASSERT_TRUE(brama_rule_close_attempt_done(&r, BRAMA_CLOSE_STARTED));
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, r.phase);
    TEST_ASSERT_FALSE(r.has_owner);
}

static void interlock_at_deadline_blocks_and_clears(void)
{
    brama_rule_t r = fresh();
    brama_rule_apply_open(&r, A, true);
    brama_rule_on_owner_disconnected(&r, A, 0);
    brama_rule_close_attempt_done(&r, BRAMA_CLOSE_INTERLOCK);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_BLOCKED, r.phase);
    TEST_ASSERT_EQUAL(BRAMA_REASON_INTERLOCK, r.reason);
    TEST_ASSERT_TRUE(r.has_owner);
    // właściciel nieobecny: po zniknięciu blokady nowe WAITING 30 s
    TEST_ASSERT_TRUE(brama_rule_on_interlock_cleared(&r, 100 * S));
    TEST_ASSERT_EQUAL(BRAMA_PHASE_WAITING, r.phase);
    TEST_ASSERT_EQUAL(130 * S, r.deadline_us);
    // właściciel obecny: MONITORING bez CLOSE
    brama_rule_close_attempt_done(&r, BRAMA_CLOSE_INTERLOCK);
    brama_rule_on_authenticated(&r, A, 131 * S); // powrót w BLOCKED: faza zostaje
    TEST_ASSERT_EQUAL(BRAMA_PHASE_BLOCKED, r.phase);
    brama_rule_on_interlock_cleared(&r, 140 * S);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_MONITORING, r.phase);
}

static void busy_over_2s_is_fault(void)
{
    brama_rule_t r = fresh();
    brama_rule_apply_open(&r, A, true);
    brama_rule_on_owner_disconnected(&r, A, 0);
    brama_rule_close_attempt_done(&r, BRAMA_CLOSE_BUSY_TIMEOUT);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_FAULT, r.phase);
    TEST_ASSERT_EQUAL(BRAMA_REASON_BUSY_TIMEOUT, r.reason);
    TEST_ASSERT_TRUE(r.has_owner);
    TEST_ASSERT_EQUAL(BRAMA_CLOSE_NONE, brama_rule_due(&r, 100 * S)); // brak prób w pętli
    // disconnect w FAULT nie tworzy WAITING
    TEST_ASSERT_FALSE(brama_rule_on_owner_disconnected(&r, A, 101 * S));
    TEST_ASSERT_EQUAL(BRAMA_PHASE_FAULT, r.phase);
    // handshake nie kasuje FAULT
    brama_rule_on_authenticated(&r, A, 102 * S);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_FAULT, r.phase);
}

static void fault_recovers_on_own_open_true(void)
{
    brama_rule_t r = fresh();
    brama_rule_apply_open(&r, A, true);
    brama_rule_on_owner_disconnected(&r, A, 0);
    brama_rule_close_attempt_done(&r, BRAMA_CLOSE_IO_FAIL);
    TEST_ASSERT_EQUAL(BRAMA_REASON_IO_FAULT, r.reason);
    TEST_ASSERT_TRUE(brama_rule_apply_open(&r, A, true));
    TEST_ASSERT_EQUAL(BRAMA_PHASE_MONITORING, r.phase);
    TEST_ASSERT_EQUAL(BRAMA_REASON_NONE, r.reason);
}

static void io_fault_after_rule_change_13_3(void)
{
    brama_rule_t r = fresh(), before;
    before = r;
    brama_rule_apply_open(&r, A, true);
    brama_rule_io_fault_after(&r, &before, BRAMA_MSG_OPEN, true);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_FAULT, r.phase); // OPEN(true): FAULT/IO_FAULT z właścicielem
    TEST_ASSERT_TRUE(r.has_owner);
    // CLOSE przy uzbrojonej: przywrócony właściciel i FAULT
    brama_rule_apply_open(&r, A, true);
    before = r;
    brama_rule_apply_close(&r);
    brama_rule_io_fault_after(&r, &before, BRAMA_MSG_CLOSE, false);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_FAULT, r.phase);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(A, r.owner_id, 16);
    // CLOSE przy DISARMED: DISARMED
    r = fresh();
    before = r;
    brama_rule_apply_close(&r);
    brama_rule_io_fault_after(&r, &before, BRAMA_MSG_CLOSE, false);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, r.phase);
    // OPEN(false): DISARMED
    brama_rule_apply_open(&r, A, true);
    before = r;
    brama_rule_apply_open(&r, A, false);
    brama_rule_io_fault_after(&r, &before, BRAMA_MSG_OPEN, false);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, r.phase);
}

static void revision_increments_on_phase_owner_reason_change(void)
{
    brama_rule_t r = fresh();
    uint64_t rev = r.revision;
    brama_rule_apply_open(&r, A, true);
    TEST_ASSERT_EQUAL(rev + 1, r.revision);
    brama_rule_on_owner_disconnected(&r, A, 0);
    TEST_ASSERT_EQUAL(rev + 2, r.revision);
    brama_rule_on_authenticated(&r, A, S);
    TEST_ASSERT_EQUAL(rev + 3, r.revision);
    brama_rule_apply_close(&r);
    TEST_ASSERT_EQUAL(rev + 4, r.revision);
    TEST_ASSERT_FALSE(brama_rule_apply_close(&r)); // bez zmiany: bez revision
    TEST_ASSERT_EQUAL(rev + 4, r.revision);
}

static void failed_reset_schedules_one_close(void)
{
    brama_rule_t r = fresh();
    brama_rule_apply_open(&r, A, true);
    TEST_ASSERT_TRUE(brama_rule_reset_failed(&r, 10 * S));
    TEST_ASSERT_EQUAL(BRAMA_PHASE_FAULT, r.phase);
    TEST_ASSERT_EQUAL(BRAMA_REASON_STORAGE_FAILURE, r.reason);
    TEST_ASSERT_EQUAL(BRAMA_CLOSE_RESET, brama_rule_due(&r, 40 * S));
    brama_rule_close_attempt_done(&r, BRAMA_CLOSE_INTERLOCK);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_FAULT, r.phase); // bez dalszych prób
    TEST_ASSERT_EQUAL(BRAMA_CLOSE_NONE, brama_rule_due(&r, 100 * S));
    // nieudany reset przy DISARMED: bez próby CLOSE
    brama_rule_t d = fresh();
    brama_rule_reset_failed(&d, 0);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, d.phase);
    TEST_ASSERT_EQUAL(BRAMA_CLOSE_NONE, brama_rule_due(&d, 100 * S));
}

// --- Restart i kopia RTC (14.5) ----------------------------------------------------------------------------------

static bool owner_active_yes(void *ctx, const uint8_t id[16]) { (void)ctx, (void)id; return true; }
static bool owner_active_no(void *ctx, const uint8_t id[16]) { (void)ctx, (void)id; return false; }

static brama_rule_boot_env_t boot_env(bool cold, int64_t now, int64_t rtc_now)
{
    return (brama_rule_boot_env_t){cold, true, true, 1, owner_active_yes, NULL, now, rtc_now};
}

static void cold_start_is_disarmed_reboot_until_first_command(void)
{
    brama_rule_t r = fresh(), b;
    brama_rule_apply_open(&r, A, true);
    brama_rtc_copy_t c;
    brama_rule_to_rtc(&r, 0, 0, &c);
    brama_rule_boot_env_t env = boot_env(true, 0, 0);
    brama_rule_boot(&b, &c, &env);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, b.phase);
    TEST_ASSERT_EQUAL(BRAMA_REASON_REBOOT, b.reason);
    TEST_ASSERT_EQUAL(1, b.revision);
    brama_snapshot_t s = brama_rule_snapshot_for(&b, A, 0);
    TEST_ASSERT_TRUE(brama_snapshot_valid(&s));
    // ostrzeżenie przetrwa restart programowy przed jakąkolwiek komendą (A66)
    brama_rule_to_rtc(&b, 0, 0, &c);
    env = boot_env(false, 0, 0);
    brama_rule_boot(&b, &c, &env);
    TEST_ASSERT_EQUAL(BRAMA_REASON_REBOOT, b.reason);
    // CLOSE przy DISARMED też kasuje REBOOT
    TEST_ASSERT_TRUE(brama_rule_apply_close(&b));
    TEST_ASSERT_EQUAL(BRAMA_REASON_NONE, b.reason);
}

static void invalid_rtc_copy_never_restores_armed(void)
{
    brama_rule_t r = fresh(), b;
    brama_rule_apply_open(&r, A, true);
    brama_rtc_copy_t c, bad;
    brama_rule_to_rtc(&r, 0, 0, &c);
    brama_rule_boot_env_t env = boot_env(false, 0, 0);
    bad = c;
    bad.crc ^= 1;
    brama_rule_boot(&b, &bad, &env);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, b.phase);
    TEST_ASSERT_EQUAL(BRAMA_REASON_REBOOT, b.reason);
    bad = c;
    bad.version = BRAMA_RTC_VERSION + 1; // nowsza struktura po downgrade
    bad.crc = brama_crc32(&bad, sizeof bad - 4);
    brama_rule_boot(&b, &bad, &env);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, b.phase);
    env.auth_generation = 2; // reset w międzyczasie
    brama_rule_boot(&b, &c, &env);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, b.phase);
    env = boot_env(false, 0, 0);
    env.owner_active = owner_active_no;
    brama_rule_boot(&b, &c, &env);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, b.phase);
    brama_rule_boot(&b, NULL, &env);
    TEST_ASSERT_EQUAL(BRAMA_REASON_REBOOT, b.reason);
}

static void monitoring_copy_boots_boot_wait_30s(void)
{
    brama_rule_t r = fresh(), b;
    brama_rule_apply_open(&r, A, true);
    brama_rtc_copy_t c;
    brama_rule_to_rtc(&r, 0, 500 * S, &c);
    brama_rule_boot_env_t env = boot_env(false, 2 * S, 503 * S);
    brama_rule_boot(&b, &c, &env);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_BOOT_WAIT, b.phase);
    TEST_ASSERT_EQUAL(BRAMA_REASON_REBOOT, b.reason);
    TEST_ASSERT_EQUAL(32 * S, b.deadline_us);
    TEST_ASSERT_FALSE(b.owner_link_up);
    TEST_ASSERT_TRUE(brama_rule_on_authenticated(&b, A, 10 * S));
    TEST_ASSERT_EQUAL(BRAMA_PHASE_MONITORING, b.phase);
}

static void bootLoopKeepsOriginalDeadline(void)
{
    // A63: restart co 5 s przy ARMED i nieobecnym właścicielu — termin nie przesuwa się.
    brama_rule_t r = fresh(), b;
    brama_rule_apply_open(&r, A, true);
    brama_rule_on_owner_disconnected(&r, A, 0); // termin 30 s w czasie monotonicznym 0
    brama_rtc_copy_t c;
    brama_rule_to_rtc(&r, 0, 1000 * S, &c);     // RTC: termin 1030 s
    int64_t rtc = 1000 * S;
    for (int i = 0; i < 5; i++) {
        rtc += 5 * S;                            // kolejny restart po 5 s, zegar monotoniczny od 1 s
        brama_rule_boot_env_t env = boot_env(false, 1 * S, rtc);
        brama_rule_boot(&b, &c, &env);
        TEST_ASSERT_EQUAL(BRAMA_PHASE_BOOT_WAIT, b.phase);
        TEST_ASSERT_EQUAL(1 * S + (1030 * S - rtc), b.deadline_us);
        brama_rule_to_rtc(&b, 1 * S, rtc, &c);
    }
    // restart po terminie: próba CLOSE zaraz po starcie
    brama_rule_boot_env_t env = boot_env(false, 1 * S, 1040 * S);
    brama_rule_boot(&b, &c, &env);
    TEST_ASSERT_EQUAL(BRAMA_CLOSE_AUTO, brama_rule_due(&b, 1 * S));
    brama_rule_close_attempt_done(&b, BRAMA_CLOSE_STARTED);
    brama_rule_to_rtc(&b, 1 * S, 1040 * S, &c);
    env = boot_env(false, 1 * S, 1045 * S);
    brama_rule_boot(&b, &c, &env);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, b.phase);       // brak wielokrotnych impulsów
    TEST_ASSERT_EQUAL(BRAMA_CLOSE_NONE, brama_rule_due(&b, 100 * S));
}

static void fault_and_blocked_survive_soft_restart(void)
{
    brama_rule_t r = fresh(), b;
    brama_rule_apply_open(&r, A, true);
    brama_rule_on_owner_disconnected(&r, A, 0);
    brama_rule_close_attempt_done(&r, BRAMA_CLOSE_BUSY_TIMEOUT);
    brama_rtc_copy_t c;
    brama_rule_to_rtc(&r, 0, 0, &c);
    brama_rule_boot_env_t env = boot_env(false, 0, 0);
    brama_rule_boot(&b, &c, &env);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_FAULT, b.phase);
    TEST_ASSERT_EQUAL(BRAMA_REASON_BUSY_TIMEOUT, b.reason);
    r = fresh();
    brama_rule_apply_open(&r, A, true);
    brama_rule_on_owner_disconnected(&r, A, 0);
    brama_rule_close_attempt_done(&r, BRAMA_CLOSE_INTERLOCK);
    brama_rule_to_rtc(&r, 0, 0, &c);
    brama_rule_boot(&b, &c, &env);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_BLOCKED, b.phase);
}

static void failed_reset_close_deadline_survives_restart(void)
{
    // A65: zaplanowane po nieudanym resecie CLOSE wykonuje się w pierwotnym terminie.
    brama_rule_t r = fresh(), b;
    brama_rule_apply_open(&r, A, true);
    brama_rule_reset_failed(&r, 0); // termin 30 s
    brama_rtc_copy_t c;
    brama_rule_to_rtc(&r, 10 * S, 100 * S, &c); // RTC: termin 120 s
    brama_rule_boot_env_t env = boot_env(false, 1 * S, 105 * S);
    brama_rule_boot(&b, &c, &env);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_FAULT, b.phase);
    TEST_ASSERT_EQUAL(BRAMA_REASON_STORAGE_FAILURE, b.reason);
    TEST_ASSERT_EQUAL(16 * S, b.deadline_us);
    TEST_ASSERT_EQUAL(BRAMA_CLOSE_RESET, brama_rule_due(&b, 16 * S));
}

static void error_mode_does_one_close_from_rtc(void)
{
    // 14.5 pkt 5: uszkodzony obraz NVS przy ARMED — jedna próba CLOSE, potem DISARMED.
    brama_rule_t r = fresh(), b;
    brama_rule_apply_open(&r, A, true);
    brama_rtc_copy_t c;
    brama_rule_to_rtc(&r, 0, 50 * S, &c); // MONITORING, bez terminu
    brama_rule_boot_env_t env = boot_env(false, 1 * S, 60 * S);
    env.image_ok = false;
    brama_rule_boot(&b, &c, &env);
    TEST_ASSERT_EQUAL(BRAMA_CLOSE_ERROR_MODE, brama_rule_due(&b, 31 * S));
    // nowy termin trafia do kopii, więc kolejny restart go dziedziczy
    brama_rule_to_rtc(&b, 1 * S, 60 * S, &c);
    env.now_us = 1 * S;
    env.rtc_now_us = 70 * S;
    brama_rule_boot(&b, &c, &env);
    TEST_ASSERT_EQUAL(21 * S, b.deadline_us);
    brama_rule_close_attempt_done(&b, BRAMA_CLOSE_INTERLOCK); // zablokowana próba też jest tą jedyną
    TEST_ASSERT_EQUAL(BRAMA_PHASE_DISARMED, b.phase);
    brama_rule_to_rtc(&b, 30 * S, 99 * S, &c);
    brama_rule_boot(&b, &c, &env);
    TEST_ASSERT_EQUAL(BRAMA_CLOSE_NONE, brama_rule_due(&b, 1000 * S));
    // błąd konfiguracji lokalnej: brak impulsu, kopia bez zmian
    brama_rule_apply_open(&r, A, true);
    brama_rule_to_rtc(&r, 0, 0, &c);
    env.allow_error_close = false;
    brama_rule_boot(&b, &c, &env);
    TEST_ASSERT_EQUAL(BRAMA_CLOSE_NONE, brama_rule_due(&b, 1000 * S));
    // zimny start w trybie błędu: brak próby
    env = boot_env(true, 0, 0);
    env.image_ok = false;
    brama_rule_boot(&b, &c, &env);
    TEST_ASSERT_EQUAL(BRAMA_CLOSE_NONE, brama_rule_due(&b, 1000 * S));
}

static void rtc_deadline_clamped_to_30s(void)
{
    // I6: licznik RTC wyzerowany przy restarcie bez utraty pamięci — termin nigdy dalej niż 30 s od startu.
    brama_rule_t r = fresh(), b;
    brama_rule_apply_open(&r, A, true);
    brama_rule_on_owner_disconnected(&r, A, 0);
    brama_rtc_copy_t c;
    brama_rule_to_rtc(&r, 0, 100000 * S, &c);
    brama_rule_boot_env_t env = boot_env(false, 1 * S, 5 * S); // licznik RTC „cofnął się”
    brama_rule_boot(&b, &c, &env);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_BOOT_WAIT, b.phase);
    TEST_ASSERT_EQUAL(31 * S, b.deadline_us);
}

void run_rule_tests(void)
{
    RUN_TEST(open_true_arms_monitoring);
    RUN_TEST(owner_open_true_again_keeps_monitoring_without_revision);
    RUN_TEST(open_false_and_close_disarm);
    RUN_TEST(other_client_open_is_not_allowed_while_armed);
    RUN_TEST(owner_disconnect_starts_waiting_30s);
    RUN_TEST(owner_return_before_deadline_cancels_waiting);
    RUN_TEST(deadline_attempts_close_then_disarmed);
    RUN_TEST(interlock_at_deadline_blocks_and_clears);
    RUN_TEST(busy_over_2s_is_fault);
    RUN_TEST(fault_recovers_on_own_open_true);
    RUN_TEST(io_fault_after_rule_change_13_3);
    RUN_TEST(revision_increments_on_phase_owner_reason_change);
    RUN_TEST(failed_reset_schedules_one_close);
    RUN_TEST(cold_start_is_disarmed_reboot_until_first_command);
    RUN_TEST(invalid_rtc_copy_never_restores_armed);
    RUN_TEST(monitoring_copy_boots_boot_wait_30s);
    RUN_TEST(bootLoopKeepsOriginalDeadline);
    RUN_TEST(fault_and_blocked_survive_soft_restart);
    RUN_TEST(failed_reset_close_deadline_survives_restart);
    RUN_TEST(error_mode_does_one_close_from_rtc);
    RUN_TEST(rtc_deadline_clamped_to_30s);
}
