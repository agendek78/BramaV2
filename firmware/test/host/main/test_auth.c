#include <string.h>
#include "unity.h"
#include "brama_auth.h"
#include "brama_err.h"
#include "fake_nvs.h"
#include "test_registry.h"

static fake_nvs_t fnvs;
static brama_nvs_t nvs;

static void client(brama_client_rec_t *c, uint8_t tag)
{
    memset(c, 0, sizeof *c);
    memset(c->client_id, tag, 16);
    memset(c->k_client, tag ^ 0x55, 32);
    c->bond.id_addr_type = 1;
    memset(c->bond.id_addr, tag, 6);
    c->bond.len = 3;
    memset(c->bond.data, tag, 3);
}

/** Pusty obraz po provisioningu (auth_generation 1, image_generation 1) zapisany w slocie A. */
static void provisioned(brama_image_t *img)
{
    fake_nvs_init(&fnvs, &nvs);
    brama_image_init_empty(img);
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_image_commit(&nvs, img));
}

static void commit_alternates_slots_and_increments_generation(void)
{
    brama_image_t img, next, loaded;
    provisioned(&img);
    TEST_ASSERT_EQUAL(0, img.slot);
    TEST_ASSERT_EQUAL(1, img.image_generation);
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_image_next(&img, &next));
    brama_client_rec_t c;
    client(&c, 1);
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_image_put_client(&next, &c));
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_image_commit(&nvs, &next));
    TEST_ASSERT_EQUAL(1, next.slot);
    TEST_ASSERT_EQUAL(2, next.image_generation);
    TEST_ASSERT_EQUAL(1, next.auth_generation); // dodanie klienta nie zmienia auth_generation
    TEST_ASSERT_EQUAL(BRAMA_IMG_OK, brama_image_load(&nvs, &loaded));
    TEST_ASSERT_EQUAL(2, loaded.image_generation);
    TEST_ASSERT_EQUAL(1, loaded.count);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(c.k_client, loaded.clients[0].k_client, 32);
    TEST_ASSERT_EQUAL(0, brama_image_find(&loaded, c.client_id));
    // kolejny commit wraca do slotu A
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_image_next(&loaded, &next));
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_image_commit(&nvs, &next));
    TEST_ASSERT_EQUAL(0, next.slot);
}

static void torn_write_falls_back_to_previous_slot(void)
{
    brama_image_t img, next, loaded;
    provisioned(&img);
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_image_next(&img, &next));
    brama_client_rec_t c;
    client(&c, 2);
    brama_image_put_client(&next, &c);
    fnvs.torn_next_write = true;
    TEST_ASSERT_EQUAL(BRAMA_ERR_STORAGE, brama_image_commit(&nvs, &next));
    TEST_ASSERT_EQUAL(BRAMA_IMG_OK, brama_image_load(&nvs, &loaded));
    TEST_ASSERT_EQUAL(1, loaded.image_generation);
    TEST_ASSERT_EQUAL(0, loaded.count);
    TEST_ASSERT_EQUAL(0, loaded.slot);
}

static void failed_readback_is_storage_failure(void)
{
    brama_image_t img, next;
    provisioned(&img);
    brama_image_next(&img, &next);
    fnvs.corrupt_readback = true;
    TEST_ASSERT_EQUAL(BRAMA_ERR_STORAGE, brama_image_commit(&nvs, &next));
}

static void both_slots_corrupt_reports_error(void)
{
    brama_image_t img, next, loaded;
    provisioned(&img);
    brama_image_next(&img, &next);
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_image_commit(&nvs, &next));
    fake_nvs_flip(&fnvs, "img_a", 20);
    fake_nvs_flip(&fnvs, "img_b", 20);
    TEST_ASSERT_EQUAL(BRAMA_IMG_CORRUPT, brama_image_load(&nvs, &loaded));
    fake_nvs_init(&fnvs, &nvs);
    TEST_ASSERT_EQUAL(BRAMA_IMG_MISSING, brama_image_load(&nvs, &loaded));
}

static void one_corrupt_slot_uses_other(void)
{
    brama_image_t img, next, loaded;
    provisioned(&img);
    brama_image_next(&img, &next);
    brama_image_commit(&nvs, &next); // B, generacja 2
    fake_nvs_flip(&fnvs, "img_b", 40);
    TEST_ASSERT_EQUAL(BRAMA_IMG_OK, brama_image_load(&nvs, &loaded));
    TEST_ASSERT_EQUAL(1, loaded.image_generation);
}

static void unknown_newer_schema_is_error_without_write(void)
{
    brama_image_t img, loaded;
    provisioned(&img);
    // schemat w bajtach 4–5 obrazu (po magic); zapis nowszej wersji z poprawną sumą robi test przez API
    img.schema = BRAMA_IMAGE_SCHEMA + 1;
    img.slot = 1; // commit trafi do A
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_image_commit(&nvs, &img));
    int writes = fnvs.writes;
    TEST_ASSERT_EQUAL(BRAMA_IMG_NEWER_SCHEMA, brama_image_load(&nvs, &loaded));
    TEST_ASSERT_EQUAL(writes, fnvs.writes);
}

static void set_name(brama_client_rec_t *c, const char *n, size_t len)
{
    c->name_len = (uint8_t)len;
    memset(c->name, 0, sizeof c->name);
    memcpy(c->name, n, len < sizeof c->name ? len : sizeof c->name);
}

static void client_name_roundtrips_through_commit_and_load(void)
{
    brama_image_t img, next, loaded;
    provisioned(&img);
    brama_image_next(&img, &next);
    brama_client_rec_t a, b;
    client(&a, 1);
    client(&b, 2);
    set_name(&a, "OnePlus A13", 11);
    set_name(&b, "Zażółć", 10); // 10 B UTF-8
    brama_image_put_client(&next, &a);
    brama_image_put_client(&next, &b);
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_image_commit(&nvs, &next));
    TEST_ASSERT_EQUAL(BRAMA_IMG_OK, brama_image_load(&nvs, &loaded));
    TEST_ASSERT_EQUAL(2, loaded.count);
    TEST_ASSERT_EQUAL(11, loaded.clients[0].name_len);
    TEST_ASSERT_EQUAL_MEMORY("OnePlus A13", loaded.clients[0].name, 11);
    TEST_ASSERT_EQUAL(10, loaded.clients[1].name_len);
    TEST_ASSERT_EQUAL_MEMORY("Zażółć", loaded.clients[1].name, 10);
    TEST_ASSERT_EQUAL(2, loaded.schema);
}

static void client_name_validation(void)
{
    TEST_ASSERT_TRUE(brama_client_name_valid((const uint8_t *)"", 0));
    TEST_ASSERT_TRUE(brama_client_name_valid((const uint8_t *)"12345678901234567890", 20));
    TEST_ASSERT_FALSE(brama_client_name_valid((const uint8_t *)"123456789012345678901", 21));
    TEST_ASSERT_TRUE(brama_client_name_valid((const uint8_t *)"Zażółć", 10));
    TEST_ASSERT_FALSE(brama_client_name_valid((const uint8_t *)"\xC5", 1));          // ucięty znak
    TEST_ASSERT_FALSE(brama_client_name_valid((const uint8_t *)"\xC0\xAF", 2));      // overlong
    TEST_ASSERT_FALSE(brama_client_name_valid((const uint8_t *)"\xED\xA0\x80", 3));  // surogat
    TEST_ASSERT_FALSE(brama_client_name_valid((const uint8_t *)"a\x01b", 3));        // znak sterujący
    TEST_ASSERT_FALSE(brama_client_name_valid((const uint8_t *)"a\x7f", 2));
    TEST_ASSERT_FALSE(brama_client_name_valid((const uint8_t *)"a\xC2\x85", 3));     // C1 (U+0085)
}

static void commit_rejects_invalid_name_without_write(void)
{
    brama_image_t img, next;
    provisioned(&img);
    brama_image_next(&img, &next);
    brama_client_rec_t c;
    client(&c, 1);
    set_name(&c, "a\x01", 2);
    brama_image_put_client(&next, &c);
    int writes = fnvs.writes;
    TEST_ASSERT_EQUAL(BRAMA_ERR_ARG, brama_image_commit(&nvs, &next));
    TEST_ASSERT_EQUAL(writes, fnvs.writes);
}

static void image_of_old_schema_length_is_corrupt(void)
{
    brama_image_t img, loaded;
    provisioned(&img);
    uint8_t old[4 + 2 + 8 + 8 + 1 + 8 * (16 + 32 + 1 + 6 + 1 + BRAMA_BOND_DATA_MAX) + 32] = {'B', 'R', 'A', 'I', 1, 0};
    TEST_ASSERT_EQUAL(0, nvs.write(nvs.ctx, "img_a", old, sizeof old) == ESP_OK ? 0 : 1);
    TEST_ASSERT_EQUAL(0, nvs.write(nvs.ctx, "img_b", old, sizeof old) == ESP_OK ? 0 : 1);
    TEST_ASSERT_EQUAL(BRAMA_IMG_CORRUPT, brama_image_load(&nvs, &loaded));
}

static void reset_empties_active_and_increments_auth_generation(void)
{
    brama_image_t img, next, reset;
    provisioned(&img);
    brama_image_next(&img, &next);
    brama_client_rec_t c;
    client(&c, 3);
    brama_image_put_client(&next, &c);
    brama_image_commit(&nvs, &next);
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_image_reset(&next, &reset));
    TEST_ASSERT_EQUAL(0, reset.count);
    TEST_ASSERT_EQUAL(2, reset.auth_generation);
    TEST_ASSERT_EQUAL(3, reset.image_generation);
}

static void generation_exhaustion_is_error(void)
{
    brama_image_t img, next;
    brama_image_init_empty(&img);
    img.image_generation = UINT64_MAX;
    TEST_ASSERT_EQUAL(BRAMA_ERR_NO_SPACE, brama_image_next(&img, &next));
    img.image_generation = 5;
    img.auth_generation = UINT64_MAX;
    TEST_ASSERT_EQUAL(BRAMA_ERR_NO_SPACE, brama_image_reset(&img, &next));
}

static void capacity_is_eight_and_replace_keeps_slot(void)
{
    brama_image_t img;
    brama_image_init_empty(&img);
    brama_client_rec_t c;
    for (int i = 0; i < 8; i++) {
        client(&c, (uint8_t)(i + 1));
        TEST_ASSERT_EQUAL(BRAMA_OK, brama_image_put_client(&img, &c));
    }
    client(&c, 9);
    TEST_ASSERT_EQUAL(BRAMA_ERR_NO_SPACE, brama_image_put_client(&img, &c));
    client(&c, 4);
    c.k_client[0] ^= 1;
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_image_put_client(&img, &c)); // ten sam client_id: zastąpienie
    TEST_ASSERT_EQUAL(8, img.count);
    TEST_ASSERT_EQUAL(3, brama_image_find(&img, c.client_id));
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_image_remove_client(&img, 3));
    TEST_ASSERT_EQUAL(7, img.count);
    TEST_ASSERT_EQUAL(-1, brama_image_find(&img, c.client_id));
}

static void factory_round_trip_and_corruption(void)
{
    fake_nvs_init(&fnvs, &nvs);
    brama_factory_t f = {0}, back;
    memset(f.device_id, 1, 16);
    memset(f.k_setup, 2, 32);
    memcpy(f.pin, "123456", 6);
    TEST_ASSERT_EQUAL(BRAMA_ERR_STORAGE, brama_factory_load(&nvs, &back)); // brak danych
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_factory_save(&nvs, &f));
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_factory_load(&nvs, &back));
    TEST_ASSERT_EQUAL_HEX8_ARRAY(f.k_setup, back.k_setup, 32);
    TEST_ASSERT_FALSE(back.provisioning_locked);
    fake_nvs_flip(&fnvs, "factory", 10);
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, brama_factory_load(&nvs, &back));
}

static void localcfg_out_of_range_is_error(void)
{
    fake_nvs_init(&fnvs, &nvs);
    brama_localcfg_t c = {500, true, 50}, back;
    TEST_ASSERT_TRUE(brama_localcfg_valid(&c));
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_localcfg_save(&nvs, &c));
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_localcfg_load(&nvs, &back));
    TEST_ASSERT_EQUAL(500, back.pulse_ms);
    c.pulse_ms = 99;
    TEST_ASSERT_FALSE(brama_localcfg_valid(&c));
    TEST_ASSERT_EQUAL(BRAMA_ERR_ARG, brama_localcfg_save(&nvs, &c));
    c.pulse_ms = 1001;
    TEST_ASSERT_FALSE(brama_localcfg_valid(&c));
    c.pulse_ms = 1000;
    c.interlock_filter_ms = 49;
    TEST_ASSERT_FALSE(brama_localcfg_valid(&c));
    fake_nvs_flip(&fnvs, "localcfg", 1);
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, brama_localcfg_load(&nvs, &back));
}

static const uint8_t PEER_A[7] = {0, 1, 2, 3, 4, 5, 6}, PEER_B[7] = {1, 9, 9, 9, 9, 9, 9};

static void window_lasts_60s_and_restarts(void)
{
    brama_auth_limits_t a;
    brama_auth_limits_init(&a);
    TEST_ASSERT_FALSE(brama_auth_window_is_open(&a, 0));
    brama_auth_window_open(&a, 1000);
    TEST_ASSERT_TRUE(brama_auth_window_is_open(&a, 60999));
    TEST_ASSERT_FALSE(brama_auth_window_is_open(&a, 61000));
    brama_auth_window_open(&a, 50000); // ponowne otwarcie: 60 s od nowa
    TEST_ASSERT_TRUE(brama_auth_window_is_open(&a, 109999));
    brama_auth_window_close(&a);
    TEST_ASSERT_FALSE(brama_auth_window_is_open(&a, 50001));
}

static void fifth_pin_failure_closes_window(void)
{
    brama_auth_limits_t a;
    brama_auth_limits_init(&a);
    brama_auth_window_open(&a, 0);
    // z różnych adresów — zmiana adresu nie omija limitu (11.4)
    for (int i = 0; i < 4; i++) brama_auth_note_failure(&a, i % 2 ? PEER_A : PEER_B, true, 1000 + i);
    TEST_ASSERT_TRUE(brama_auth_window_is_open(&a, 2000));
    brama_auth_note_failure(&a, PEER_B, true, 2000);
    TEST_ASSERT_FALSE(brama_auth_window_is_open(&a, 2001));
    // nowe okno zeruje licznik PIN-u
    brama_auth_window_open(&a, 3000);
    brama_auth_note_failure(&a, PEER_A, true, 3001);
    TEST_ASSERT_TRUE(brama_auth_window_is_open(&a, 3002));
}

static void phone_window_resets_pin_counter_at_most_hourly(void)
{
    // Okno z telefonu (phone_window_design §6.2): bez dostępu do płytki najwyżej 5 prób PIN-u na godzinę.
    brama_auth_limits_t a;
    brama_auth_limits_init(&a);
    brama_auth_window_open(&a, 0); // przycisk zeruje zawsze
    for (int i = 0; i < 5; i++) brama_auth_note_failure(&a, PEER_A, true, 100 + i);
    TEST_ASSERT_FALSE(brama_auth_window_is_open(&a, 200));
    brama_auth_window_open_remote(&a, 1000); // < 1 h od zerowania: okno otwarte, licznik zostaje
    TEST_ASSERT_TRUE(brama_auth_window_is_open(&a, 1001));
    TEST_ASSERT_EQUAL(5, a.pin_failures);
    // Wyczerpany licznik blokuje PAKE przed rozpoczęciem — bez dodatkowej próby na każde otwarcie okna (przegląd).
    TEST_ASSERT_FALSE(brama_auth_pin_attempt_allowed(&a, 1002));
    for (int i = 0; i < 10; i++) brama_auth_window_open_remote(&a, 2000 + i);
    TEST_ASSERT_FALSE(brama_auth_pin_attempt_allowed(&a, 3000));
    TEST_ASSERT_TRUE(brama_auth_window_is_open(&a, 3000)); // okno dalej działa dla QR i naprawy bondu
    brama_auth_window_open_remote(&a, 3600000); // godzina od zerowania: zerowanie
    TEST_ASSERT_EQUAL(0, a.pin_failures);
    TEST_ASSERT_TRUE(brama_auth_pin_attempt_allowed(&a, 3600000));
    brama_auth_note_failure(&a, PEER_A, true, 3600001);
    TEST_ASSERT_TRUE(brama_auth_window_is_open(&a, 3600002));
    brama_auth_window_open_remote(&a, 3600100); // ponownie < 1 h: licznik zostaje
    TEST_ASSERT_EQUAL(1, a.pin_failures);
    brama_auth_window_open(&a, 3600200); // przycisk zeruje zawsze
    TEST_ASSERT_EQUAL(0, a.pin_failures);
}

static void pin_attempts_left_counts_down_and_is_zero_without_window(void)
{
    brama_auth_limits_t a;
    brama_auth_limits_init(&a);
    TEST_ASSERT_EQUAL(0, brama_auth_pin_attempts_left(&a, 0)); // po starcie: okno zamknięte i licznik wyczerpany
    brama_auth_window_open_remote(&a, 1000);                    // < 1 h od startu: licznik zostaje wyczerpany
    TEST_ASSERT_EQUAL(0, brama_auth_pin_attempts_left(&a, 1001));
    brama_auth_window_open(&a, 2000); // przycisk
    TEST_ASSERT_EQUAL(5, brama_auth_pin_attempts_left(&a, 2001));
    brama_auth_note_failure(&a, PEER_A, true, 2002);
    brama_auth_note_failure(&a, PEER_A, false, 2003); // błąd nie-PIN-owy nie zmniejsza
    TEST_ASSERT_EQUAL(4, brama_auth_pin_attempts_left(&a, 2004));
    TEST_ASSERT_EQUAL(0, brama_auth_pin_attempts_left(&a, 2000 + BRAMA_WINDOW_MS + 1)); // okno wygasło
}

static void pin_attempts_after_boot_need_button_or_an_hour(void)
{
    // Restart (np. po OTA) nie może dawać nowych 5 prób PIN-u przez okno z telefonu (przegląd).
    brama_auth_limits_t a;
    brama_auth_limits_init(&a);
    brama_auth_window_open_remote(&a, 1000);
    TEST_ASSERT_FALSE(brama_auth_pin_attempt_allowed(&a, 1001));
    brama_auth_window_open_remote(&a, 3600000);
    TEST_ASSERT_TRUE(brama_auth_pin_attempt_allowed(&a, 3600001));
    brama_auth_limits_init(&a);
    brama_auth_window_open(&a, 1000); // przycisk: od razu
    TEST_ASSERT_TRUE(brama_auth_pin_attempt_allowed(&a, 1001));
    TEST_ASSERT_FALSE(brama_auth_pin_attempt_allowed(&a, 61000)); // poza oknem
}

static void five_failures_per_60s_rate_limited(void)
{
    brama_auth_limits_t a;
    brama_auth_limits_init(&a);
    for (int i = 0; i < 4; i++) brama_auth_note_failure(&a, PEER_A, false, i * 1000);
    TEST_ASSERT_FALSE(brama_auth_rate_limited(&a, PEER_A, 4000));
    brama_auth_note_failure(&a, PEER_A, false, 4000);
    TEST_ASSERT_TRUE(brama_auth_rate_limited(&a, PEER_A, 4001));
    TEST_ASSERT_FALSE(brama_auth_rate_limited(&a, PEER_B, 4001)); // obcy peer nie blokuje innych
    TEST_ASSERT_FALSE(brama_auth_rate_limited(&a, PEER_A, 60001)); // pierwsza porażka wypadła z okna 60 s
}

void run_auth_tests(void)
{
    RUN_TEST(commit_alternates_slots_and_increments_generation);
    RUN_TEST(torn_write_falls_back_to_previous_slot);
    RUN_TEST(failed_readback_is_storage_failure);
    RUN_TEST(both_slots_corrupt_reports_error);
    RUN_TEST(one_corrupt_slot_uses_other);
    RUN_TEST(unknown_newer_schema_is_error_without_write);
    RUN_TEST(reset_empties_active_and_increments_auth_generation);
    RUN_TEST(generation_exhaustion_is_error);
    RUN_TEST(capacity_is_eight_and_replace_keeps_slot);
    RUN_TEST(factory_round_trip_and_corruption);
    RUN_TEST(localcfg_out_of_range_is_error);
    RUN_TEST(window_lasts_60s_and_restarts);
    RUN_TEST(client_name_roundtrips_through_commit_and_load);
    RUN_TEST(client_name_validation);
    RUN_TEST(commit_rejects_invalid_name_without_write);
    RUN_TEST(image_of_old_schema_length_is_corrupt);
    RUN_TEST(fifth_pin_failure_closes_window);
    RUN_TEST(phone_window_resets_pin_counter_at_most_hourly);
    RUN_TEST(pin_attempts_left_counts_down_and_is_zero_without_window);
    RUN_TEST(pin_attempts_after_boot_need_button_or_an_hour);
    RUN_TEST(five_failures_per_60s_rate_limited);
}
