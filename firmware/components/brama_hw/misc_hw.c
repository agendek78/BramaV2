#include <string.h>
#include "brama_hw.h"
#include "driver/gpio.h"
#include "esp_attr.h"
#include "esp_random.h"
#include "esp_rtc_time.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "sdkconfig.h"

// --- LED i przycisk ----------------------------------------------------------------------------------------------

void brama_hw_led_init(void)
{
    gpio_config_t led = {.pin_bit_mask = 1ULL << CONFIG_BRAMA_GPIO_STATUS_LED, .mode = GPIO_MODE_OUTPUT};
    gpio_config(&led);
    brama_hw_led_set(false); // dioda zgaszona od inicjalizacji (poziom zależy od polaryzacji)
    gpio_config_t btn = {.pin_bit_mask = 1ULL << CONFIG_BRAMA_GPIO_PAIR_BUTTON, .mode = GPIO_MODE_INPUT,
                         .pull_up_en = GPIO_PULLUP_ENABLE};
    gpio_config(&btn);
}

void brama_hw_led_set(bool on)
{
#ifdef CONFIG_BRAMA_LED_ACTIVE_LOW
    on = !on;
#endif
    gpio_set_level(CONFIG_BRAMA_GPIO_STATUS_LED, on ? 1 : 0);
}

bool brama_hw_button_pressed(void)
{
    return gpio_get_level(CONFIG_BRAMA_GPIO_PAIR_BUTTON) == 0; // przycisk do masy
}

// --- Zegary, RTC, losowość ----------------------------------------------------------------------------------------

static int64_t mono_now(void *ctx)
{
    (void)ctx;
    return esp_timer_get_time();
}

static int64_t rtc_now(void *ctx)
{
    (void)ctx;
    return (int64_t)esp_rtc_get_time_us();
}

brama_clock_t brama_hw_clock(void) { return (brama_clock_t){mono_now, NULL}; }
brama_clock_t brama_hw_rtc_clock(void) { return (brama_clock_t){rtc_now, NULL}; }

/** Kopia reguły (14.5): przetrwa restart programowy, watchdog i panic; ważność rozstrzyga CRC. */
static RTC_NOINIT_ATTR uint8_t s_rtc_copy[64];

static bool rtc_load(void *ctx, void *buf, size_t len)
{
    (void)ctx;
    if (len > sizeof s_rtc_copy) return false;
    memcpy(buf, s_rtc_copy, len);
    return true;
}

static void rtc_store(void *ctx, const void *buf, size_t len)
{
    (void)ctx;
    if (len <= sizeof s_rtc_copy) memcpy(s_rtc_copy, buf, len);
}

brama_rtc_t brama_hw_rtc(void) { return (brama_rtc_t){rtc_load, rtc_store, NULL}; }

bool brama_hw_cold_start(void)
{
    return esp_reset_reason() == ESP_RST_POWERON;
}

static void rng_fill(void *ctx, uint8_t *buf, size_t len)
{
    (void)ctx;
    // Sprzętowy RNG; pełna entropia po starcie RF (BLE) albo bootloader_random_enable (15.3).
    esp_fill_random(buf, len);
}

brama_rng_t brama_hw_rng(void) { return (brama_rng_t){rng_fill, NULL}; }
