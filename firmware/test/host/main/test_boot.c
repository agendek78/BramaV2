#include <string.h>
#include "unity.h"
#include "brama_boot.h"
#include "brama_err.h"
#include "test_registry.h"

static brama_boot_decision_t decide(bool button, int factory_rc, brama_image_status_t img, int cfg_rc)
{
    return brama_boot_decide(button, factory_rc, img, cfg_rc);
}

static void button_held_enters_service(void)
{
    TEST_ASSERT_EQUAL(BRAMA_BOOT_SERVICE, decide(true, BRAMA_OK, BRAMA_IMG_OK, BRAMA_OK).mode);
    TEST_ASSERT_EQUAL(BRAMA_BOOT_SERVICE, decide(true, BRAMA_ERR_FORMAT, BRAMA_IMG_CORRUPT, BRAMA_ERR_FORMAT).mode);
}

static void missing_factory_enters_provisioning(void)
{
    TEST_ASSERT_EQUAL(BRAMA_BOOT_PROVISIONING, decide(false, BRAMA_ERR_STORAGE, BRAMA_IMG_MISSING, BRAMA_ERR_STORAGE).mode);
}

static void corrupt_image_is_local_error(void)
{
    brama_boot_decision_t d = decide(false, BRAMA_OK, BRAMA_IMG_CORRUPT, BRAMA_OK);
    TEST_ASSERT_EQUAL(BRAMA_BOOT_LOCAL_ERROR, d.mode);
    TEST_ASSERT_TRUE(d.allow_error_close); // jedna próba CLOSE z kopii RTC (14.5 pkt 5)
    TEST_ASSERT_EQUAL(BRAMA_BOOT_LOCAL_ERROR, decide(false, BRAMA_OK, BRAMA_IMG_NEWER_SCHEMA, BRAMA_OK).mode);
    TEST_ASSERT_EQUAL(BRAMA_BOOT_LOCAL_ERROR, decide(false, BRAMA_OK, BRAMA_IMG_MISSING, BRAMA_OK).mode);
    TEST_ASSERT_EQUAL(BRAMA_BOOT_LOCAL_ERROR, decide(false, BRAMA_ERR_FORMAT, BRAMA_IMG_OK, BRAMA_OK).mode);
}

static void bad_localcfg_is_local_error_without_close(void)
{
    brama_boot_decision_t d = decide(false, BRAMA_OK, BRAMA_IMG_OK, BRAMA_ERR_FORMAT);
    TEST_ASSERT_EQUAL(BRAMA_BOOT_LOCAL_ERROR, d.mode);
    TEST_ASSERT_FALSE(d.allow_error_close); // nieznany bezpieczny czas impulsu
    TEST_ASSERT_EQUAL(BRAMA_BOOT_NORMAL, decide(false, BRAMA_OK, BRAMA_IMG_OK, BRAMA_OK).mode);
}

static void qr_text_format(void)
{
    uint8_t id[16], k[32];
    for (int i = 0; i < 16; i++) id[i] = (uint8_t)(0x10 + i);
    for (int i = 0; i < 32; i++) k[i] = (uint8_t)(0x20 + i);
    char out[160];
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_qr_text(id, k, (const uint8_t *)"482915", out, sizeof out));
    // Ten sam tekst przyjmuje ProvisioningQr.parse w :protocol (9.2).
    TEST_ASSERT_EQUAL_STRING("brama://provision?v=2&id=101112131415161718191a1b1c1d1e1f"
                             "&k=ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8&p=482915", out);
    TEST_ASSERT_EQUAL(BRAMA_ERR_ARG, brama_qr_text(id, k, (const uint8_t *)"482915", out, 50));
}

static uint32_t queue[8];
static int qpos;
static void rng(void *ctx, uint8_t *buf, size_t len)
{
    (void)ctx;
    TEST_ASSERT_EQUAL(4, len);
    memcpy(buf, &queue[qpos++], 4);
}

static void pin_is_uniform_with_rejection(void)
{
    brama_rng_t r = {rng, NULL};
    uint8_t pin[6];
    qpos = 0;
    queue[0] = 4294967295u; // ≥ 4 294 000 000 — odrzucone (brak obciążenia modulo, 9.2)
    queue[1] = 4294000000u; // też odrzucone
    queue[2] = 1000042u;
    brama_pin_generate(&r, pin);
    TEST_ASSERT_EQUAL(3, qpos);
    TEST_ASSERT_EQUAL_MEMORY("000042", pin, 6);
}

static void migration_runs_only_after_image_confirmed(void)
{
    TEST_ASSERT_TRUE(brama_boot_migration_allowed(BRAMA_FB_NORMAL));
    TEST_ASSERT_TRUE(brama_boot_migration_allowed(BRAMA_FB_CONFIRM_THEN_MIGRATE));
    TEST_ASSERT_FALSE(brama_boot_migration_allowed(BRAMA_FB_ROLLBACK));
    TEST_ASSERT_FALSE(brama_boot_migration_allowed(BRAMA_FB_LEAVE_PENDING));
}

void run_boot_tests(void)
{
    RUN_TEST(button_held_enters_service);
    RUN_TEST(missing_factory_enters_provisioning);
    RUN_TEST(corrupt_image_is_local_error);
    RUN_TEST(bad_localcfg_is_local_error_without_close);
    RUN_TEST(qr_text_format);
    RUN_TEST(pin_is_uniform_with_rejection);
    RUN_TEST(migration_runs_only_after_image_confirmed);
}
