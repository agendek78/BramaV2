#include "unity.h"
#include "brama_button.h"
#include "test_registry.h"

#define MS 1000LL

/** Stan przycisku od `from` do `to` w krokach 10 ms; zwraca pierwsze zdarzenie różne od NONE. */
static brama_button_event_t hold(brama_button_t *b, bool pressed, int64_t from_ms, int64_t to_ms)
{
    brama_button_event_t ev = BRAMA_BUTTON_NONE;
    for (int64_t t = from_ms; t <= to_ms; t += 10) {
        brama_button_event_t e = brama_button_update(b, pressed, t * MS);
        if (e != BRAMA_BUTTON_NONE && ev == BRAMA_BUTTON_NONE) ev = e;
    }
    return ev;
}

static void short_press_does_nothing(void)
{
    brama_button_t b;
    brama_button_init(&b);
    TEST_ASSERT_EQUAL(BRAMA_BUTTON_NONE, hold(&b, true, 0, 2900));
    TEST_ASSERT_EQUAL(BRAMA_BUTTON_NONE, hold(&b, false, 2910, 3500));
}

static void release_between_3_and_10s_opens_window(void)
{
    brama_button_t b;
    brama_button_init(&b);
    TEST_ASSERT_EQUAL(BRAMA_BUTTON_NONE, hold(&b, true, 0, 3100));
    TEST_ASSERT_EQUAL(BRAMA_HOLD_3S, brama_button_stage(&b));
    TEST_ASSERT_EQUAL(BRAMA_BUTTON_WINDOW, hold(&b, false, 3110, 3300));
    TEST_ASSERT_EQUAL(BRAMA_HOLD_NONE, brama_button_stage(&b));
}

static void reset_fires_at_10s_without_release(void)
{
    brama_button_t b;
    brama_button_init(&b);
    TEST_ASSERT_EQUAL(BRAMA_BUTTON_NONE, hold(&b, true, 0, 9990));
    // filtr 30 ms: przytrzymanie liczy się od ustabilizowanego naciśnięcia
    TEST_ASSERT_EQUAL(BRAMA_BUTTON_RESET, hold(&b, true, 10000, 10100));
    TEST_ASSERT_EQUAL(BRAMA_HOLD_10S, brama_button_stage(&b));
    TEST_ASSERT_EQUAL(BRAMA_BUTTON_NONE, hold(&b, true, 10110, 15000)); // jeden reset
    TEST_ASSERT_EQUAL(BRAMA_BUTTON_NONE, hold(&b, false, 15010, 15500)); // zwolnienie nie otwiera okna
}

static void bounce_under_30ms_ignored(void)
{
    brama_button_t b;
    brama_button_init(&b);
    // drgania: 20 ms naciśnięcia, przerwa, znowu 20 ms — brak ustabilizowanego naciśnięcia
    for (int i = 0; i < 400; i++) {
        bool pressed = (i / 2) % 2 == 0;
        TEST_ASSERT_EQUAL(BRAMA_BUTTON_NONE, brama_button_update(&b, pressed, i * 10 * MS));
    }
    TEST_ASSERT_EQUAL(BRAMA_HOLD_NONE, brama_button_stage(&b));
    // krótka przerwa < 30 ms w trakcie przytrzymania nie przerywa go
    brama_button_init(&b);
    hold(&b, true, 0, 2000);
    hold(&b, false, 2010, 2020);
    TEST_ASSERT_EQUAL(BRAMA_BUTTON_WINDOW, hold(&b, true, 2030, 3500) == BRAMA_BUTTON_NONE
                                               ? hold(&b, false, 3510, 3700) : BRAMA_BUTTON_NONE);
}

static void led_priority_table(void)
{
    brama_led_inputs_t in = {0};
    TEST_ASSERT_EQUAL(BRAMA_LED_IDLE_BLIP, brama_led_select(&in));
    in.any_ready = true;
    TEST_ASSERT_EQUAL(BRAMA_LED_SOLID, brama_led_select(&in));
    in.error = true;
    TEST_ASSERT_EQUAL(BRAMA_LED_ERROR, brama_led_select(&in));
    in.window_open = true;
    TEST_ASSERT_EQUAL(BRAMA_LED_WINDOW_1HZ, brama_led_select(&in));
    in.hold_3s = true;
    TEST_ASSERT_EQUAL(BRAMA_LED_RELEASE_5HZ, brama_led_select(&in));
    in.reset_success = true;
    TEST_ASSERT_EQUAL(BRAMA_LED_RESET_OK, brama_led_select(&in));
    in.reset_in_progress = true;
    TEST_ASSERT_EQUAL(BRAMA_LED_RESET_10HZ, brama_led_select(&in));
}

static void led_patterns_have_expected_timing(void)
{
    TEST_ASSERT_TRUE(brama_led_level(BRAMA_LED_SOLID, 1234));
    TEST_ASSERT_TRUE(brama_led_level(BRAMA_LED_RESET_10HZ, 0));
    TEST_ASSERT_FALSE(brama_led_level(BRAMA_LED_RESET_10HZ, 50));
    TEST_ASSERT_TRUE(brama_led_level(BRAMA_LED_WINDOW_1HZ, 100));
    TEST_ASSERT_FALSE(brama_led_level(BRAMA_LED_WINDOW_1HZ, 600));
    TEST_ASSERT_TRUE(brama_led_level(BRAMA_LED_IDLE_BLIP, 3000));
    TEST_ASSERT_FALSE(brama_led_level(BRAMA_LED_IDLE_BLIP, 3500));
    // błąd: 2 krótkie błyski co 2 s
    TEST_ASSERT_TRUE(brama_led_level(BRAMA_LED_ERROR, 50));
    TEST_ASSERT_FALSE(brama_led_level(BRAMA_LED_ERROR, 150));
    TEST_ASSERT_TRUE(brama_led_level(BRAMA_LED_ERROR, 250));
    TEST_ASSERT_FALSE(brama_led_level(BRAMA_LED_ERROR, 1000));
}

void run_button_tests(void)
{
    RUN_TEST(short_press_does_nothing);
    RUN_TEST(release_between_3_and_10s_opens_window);
    RUN_TEST(reset_fires_at_10s_without_release);
    RUN_TEST(bounce_under_30ms_ignored);
    RUN_TEST(led_priority_table);
    RUN_TEST(led_patterns_have_expected_timing);
}
