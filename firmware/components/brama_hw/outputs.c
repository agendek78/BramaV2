#include "brama_hw.h"
#include "driver/gpio.h"
#include "esp_attr.h"
#include "esp_timer.h"
#include "sdkconfig.h"

#define LEVEL_ON (CONFIG_BRAMA_OUTPUT_ACTIVE_HIGH ? 1 : 0)
#define LEVEL_OFF (!LEVEL_ON)

static esp_timer_handle_t s_pulse_timer;
static volatile int s_active_gpio = -1;

static void IRAM_ATTR pulse_end(void *arg)
{
    (void)arg;
    // Callback w kontekście ISR (ESP_TIMER_ISR) z GPIO w IRAM: działa także podczas zapisu flash (15.5, A80).
    if (s_active_gpio >= 0) gpio_set_level(s_active_gpio, LEVEL_OFF);
    s_active_gpio = -1;
}

void brama_hw_outputs_safe(void)
{
    const int pins[2] = {CONFIG_BRAMA_GPIO_OPEN, CONFIG_BRAMA_GPIO_CLOSE};
    for (int i = 0; i < 2; i++) {
        gpio_set_level(pins[i], LEVEL_OFF); // poziom przed kierunkiem: bez krótkiego impulsu przy konfiguracji
        gpio_config_t c = {.pin_bit_mask = 1ULL << pins[i], .mode = GPIO_MODE_OUTPUT};
        gpio_config(&c);
        gpio_set_level(pins[i], LEVEL_OFF);
    }
}

static bool start_pulse(void *ctx, brama_output_t out, uint32_t ms)
{
    (void)ctx;
    if (!s_pulse_timer) {
        esp_timer_create_args_t args = {.callback = pulse_end, .dispatch_method = ESP_TIMER_ISR, .name = "pulse"};
        if (esp_timer_create(&args, &s_pulse_timer) != ESP_OK) return false;
    }
    if (s_active_gpio >= 0 || ms < 100 || ms > 1000) return false; // wykluczenie obu wyjść, zakres 2.2
    int gpio = out == BRAMA_OUTPUT_OPEN ? CONFIG_BRAMA_GPIO_OPEN : CONFIG_BRAMA_GPIO_CLOSE;
    s_active_gpio = gpio;
    if (gpio_set_level(gpio, LEVEL_ON) != ESP_OK) {
        s_active_gpio = -1;
        return false;
    }
    if (esp_timer_start_once(s_pulse_timer, (uint64_t)ms * 1000) != ESP_OK) {
        gpio_set_level(gpio, LEVEL_OFF);
        s_active_gpio = -1;
        return false;
    }
    return true;
}

static void stop_pulse(void *ctx)
{
    (void)ctx;
    // Reset autoryzacji wyłącza trwający impuls bez rozpoczęcia innego (9.6).
    if (s_pulse_timer) esp_timer_stop(s_pulse_timer);
    pulse_end(NULL);
}

static bool interlock_active_port(void *ctx);

brama_outputs_t brama_hw_outputs(void)
{
    return (brama_outputs_t){start_pulse, interlock_active_port, stop_pulse, NULL};
}

// --- Interlock (2.2): rozwarcie, przerwany przewód lub brak zasilania obwodu = blokada ---------------------------

static esp_timer_handle_t s_interlock_timer;
static bool s_interlock_enabled, s_interlock_state, s_interlock_raw;
static int64_t s_interlock_raw_since;
static uint16_t s_interlock_filter_ms;
static void (*s_interlock_cb)(bool, void *);
static void *s_interlock_ctx;

static bool interlock_active_port(void *ctx)
{
    (void)ctx;
    return s_interlock_enabled && s_interlock_state;
}

static void interlock_sample(void *arg)
{
    (void)arg;
    bool raw = gpio_get_level(CONFIG_BRAMA_GPIO_INTERLOCK) != 0; // pull-up: styk NC zwarty do masy = 0 = brak blokady
    int64_t now = esp_timer_get_time();
    if (raw != s_interlock_raw) {
        s_interlock_raw = raw;
        s_interlock_raw_since = now;
    }
    if (raw != s_interlock_state && now - s_interlock_raw_since >= (int64_t)s_interlock_filter_ms * 1000) {
        s_interlock_state = raw;
        if (s_interlock_cb) s_interlock_cb(raw, s_interlock_ctx);
    }
}

void brama_hw_interlock_init(bool enabled, uint16_t filter_ms, void (*on_change)(bool active, void *ctx), void *ctx)
{
    s_interlock_enabled = enabled;
    if (!enabled) return;
    s_interlock_filter_ms = filter_ms;
    s_interlock_cb = on_change;
    s_interlock_ctx = ctx;
    gpio_config_t c = {.pin_bit_mask = 1ULL << CONFIG_BRAMA_GPIO_INTERLOCK, .mode = GPIO_MODE_INPUT,
                       .pull_up_en = GPIO_PULLUP_ENABLE};
    gpio_config(&c);
    // Do pierwszego przefiltrowanego odczytu przyjmujemy blokadę (fail-safe).
    s_interlock_state = true;
    s_interlock_raw = true;
    s_interlock_raw_since = esp_timer_get_time();
    esp_timer_create_args_t args = {.callback = interlock_sample, .name = "interlock"};
    if (esp_timer_create(&args, &s_interlock_timer) == ESP_OK) esp_timer_start_periodic(s_interlock_timer, 10 * 1000);
}
