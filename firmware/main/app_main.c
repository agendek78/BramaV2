// Start sterownika (14.5, 15.2, 15.4, 15.5). Kolejność: wyjścia nieaktywne → NVS/RTC → decyzja trybu →
// NORMAL/LOCAL_ERROR: reguła z kopii RTC, aktor, BLE, autotest nowego obrazu; SERVICE/PROVISIONING: konsola UART.
#include <string.h>
#include "brama_actor.h"
#include "brama_ble.h"
#include "brama_boot.h"
#include "brama_button.h"
#include "brama_crypto.h"
#include "brama_err.h"
#include "brama_hw.h"
#include "brama_ota.h"
#include "brama_rule.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "service_console.h"

static const char *TAG = "brama";

static brama_actor_t s_actor;
static brama_ota_t s_ota;
static brama_factory_t s_factory;
static SemaphoreHandle_t s_lock;

/** Zajęcie blokady aktora/BLE z limitem 2 s; po przekroczeniu loguje, kto ją trzyma (zabezpieczenie diagnostyczne zakleszczeń). */
static int64_t now_us_ms(void) { return esp_timer_get_time() / 1000; }

static void take_lock(void)
{
    if (xSemaphoreTakeRecursive(s_lock, pdMS_TO_TICKS(2000)) == pdTRUE) return;
    TaskHandle_t holder = xSemaphoreGetMutexHolder(s_lock);
    ESP_LOGE(TAG, "blokada aktora zajeta ponad 2 s: czeka '%s', trzyma '%s'", pcTaskGetName(NULL),
             holder ? pcTaskGetName(holder) : "?");
    xSemaphoreTakeRecursive(s_lock, portMAX_DELAY);
}
static QueueHandle_t s_queue;
static brama_button_t s_button;
static int64_t s_reset_ok_until_us;

static volatile bool s_tick_pending;

static void post(const brama_actor_msg_t *m)
{
    // Bez blokowania: nadawcy trzymają wspólną blokadę, a aktor bierze ją po odebraniu wiadomości. TICK jest
    // scalany (s_tick_pending), więc kolejkę zapełniają tylko zdarzenia stanu.
    BaseType_t ok = m->type == BRAMA_AMSG_BUTTON_RESET ? xQueueSendToFront(s_queue, m, 0) : xQueueSend(s_queue, m, 0);
    if (ok != pdTRUE) ESP_LOGE(TAG, "Kolejka aktora pełna: utracono zdarzenie %d", m->type);
}

/** TICK aktora niezależnie od BLE (terminy reguły, BUSY, OTA), co 100 ms, najwyżej jeden w kolejce. */
static void actor_tick_cb(void *arg)
{
    (void)arg;
    if (s_tick_pending) return;
    s_tick_pending = true;
    brama_actor_msg_t m = {.type = BRAMA_AMSG_TICK};
    if (xQueueSend(s_queue, &m, 0) != pdTRUE) s_tick_pending = false;
}

static void actor_task(void *arg)
{
    (void)arg;
    static brama_actor_msg_t m;
    for (;;) {
        if (xQueueReceive(s_queue, &m, portMAX_DELAY) != pdTRUE) continue;
        if (m.type == BRAMA_AMSG_TICK) s_tick_pending = false;
        take_lock();
        uint64_t gen = s_actor.image.auth_generation;
        bool window = s_actor.limits.window_open;
        uint8_t pin_left = brama_auth_pin_attempts_left(&s_actor.limits, now_us_ms());
        int64_t pulse_before = s_actor.last_pulse_start_us;
        bool ota_before = s_ota.active;
        brama_actor_post(&s_actor, &m);
        while (brama_actor_step(&s_actor)) {}
        if (ota_before && !s_ota.active && m.type != BRAMA_AMSG_COMMAND) {
            // Transfer OTA porzucony bez komendy: rozłączenie telefonu, 30 s bez danych (TICK), reset.
            ESP_LOGW(TAG, "transfer OTA przerwany (zdarzenie aktora typ=%d)", (int)m.type);
        }
        if (m.type != BRAMA_AMSG_COMMAND && s_actor.last_pulse_start_us != pulse_before) {
            // Impuls bez komendy telefonu (np. samoczynne CLOSE reguły po rozłączeniu, 14.4): TICK, rozłączenie, reset.
            ESP_LOGI(TAG, "impuls z reguły sterownika (zdarzenie aktora typ=%d)", (int)m.type);
        }
        if (m.type == BRAMA_AMSG_COMMAND && (m.cmd_type == BRAMA_MSG_OPEN || m.cmd_type == BRAMA_MSG_CLOSE) &&
            m.link >= 0 && m.link < BRAMA_MAX_LINKS) {
            // Diagnostyka: kto wysłał komendę (indeks z `info` + 4 bajty client_id) i czy ruszył impuls (odmowy: BUSY, interlock…).
            const uint8_t *id = s_actor.links[m.link].client_id;
            ESP_LOGI(TAG, "komenda %s od klienta [%d] id=%02x%02x%02x%02x łącze=%d impuls=%s", m.cmd_type == BRAMA_MSG_OPEN ? "OPEN" : "CLOSE",
                     brama_image_find(&s_actor.image, id), id[0], id[1], id[2], id[3], m.link,
                     s_actor.last_pulse_start_us != pulse_before ? "tak" : "nie");
        }
        if (m.type == BRAMA_AMSG_BUTTON_RESET && s_actor.control_enabled && s_actor.image.auth_generation != gen) {
            s_reset_ok_until_us = esp_timer_get_time() + 3 * 1000 * 1000;
        }
        if (m.type == BRAMA_AMSG_COMMAND && m.cmd_type == BRAMA_MSG_OPEN_PAIRING_WINDOW && s_actor.limits.window_open) {
            ESP_LOGI(TAG, "Okno rejestracji otwarte przez telefon"); // bez client_id w logu (phone_window_design §4)
        }
        // Zmiana obrazu lub okna: filtr połączeń i reklama z opublikowanego stanu (8.3).
        if (s_actor.image.auth_generation != gen || m.type == BRAMA_AMSG_PAIR_COMMIT || m.type == BRAMA_AMSG_REPAIR_COMMIT || window != s_actor.limits.window_open ||
            pin_left != brama_auth_pin_attempts_left(&s_actor.limits, now_us_ms()) || ota_before != s_ota.active) {
            brama_ble_refresh_advertising();
        }
        xSemaphoreGiveRecursive(s_lock);
    }
}

bool brama_app_pin_status(int *left, bool *window)
{
    if (!s_lock) return false; // tryb serwisowy/provisioningu: bez aktora
    take_lock();
    *left = brama_auth_pin_attempts_left(&s_actor.limits, now_us_ms());
    *window = brama_auth_window_is_open(&s_actor.limits, now_us_ms());
    xSemaphoreGiveRecursive(s_lock);
    return true;
}

static void on_interlock(bool active, void *ctx)
{
    (void)ctx;
    brama_actor_msg_t m = {.type = BRAMA_AMSG_INTERLOCK, .interlock_active = active};
    post(&m);
}

/** Przycisk co 10 ms i LED według priorytetów 9.4. */
static void ui_task(void *arg)
{
    (void)arg;
    brama_led_pattern_t last = BRAMA_LED_IDLE_BLIP;
    int64_t pattern_start = 0;
    for (;;) {
        int64_t t = esp_timer_get_time();
        brama_button_event_t ev = brama_button_update(&s_button, brama_hw_button_pressed(), t);
        if (ev != BRAMA_BUTTON_NONE) {
            brama_actor_msg_t m = {.type = ev == BRAMA_BUTTON_RESET ? BRAMA_AMSG_BUTTON_RESET : BRAMA_AMSG_BUTTON_WINDOW};
            post(&m);
        }
        take_lock();
        brama_led_inputs_t in = {
            .reset_in_progress = brama_button_stage(&s_button) == BRAMA_HOLD_10S,
            .reset_success = t < s_reset_ok_until_us,
            .hold_3s = brama_button_stage(&s_button) == BRAMA_HOLD_3S,
            .window_open = brama_auth_window_is_open(&s_actor.limits, t / 1000),
            .error = !s_actor.control_enabled || s_actor.rule.phase == BRAMA_PHASE_FAULT,
            .any_ready = brama_ble_any_ready(),
        };
        xSemaphoreGiveRecursive(s_lock);
        brama_led_pattern_t p = brama_led_select(&in);
        if (p != last) {
            last = p;
            pattern_start = t;
        }
        brama_hw_led_set(brama_led_level(p, (t - pattern_start) / 1000));
        vTaskDelay(pdMS_TO_TICKS(10));
    }
}

static bool owner_active(void *ctx, const uint8_t id[16])
{
    return brama_image_find((const brama_image_t *)ctx, id) >= 0;
}

static bool button_held_at_boot(void)
{
    // Przytrzymanie przy starcie przez ≥ 50 ms (filtr drgań) wybiera tryb serwisowy (15.4).
    for (int i = 0; i < 5; i++) {
        if (!brama_hw_button_pressed()) return false;
        vTaskDelay(pdMS_TO_TICKS(10));
    }
    return true;
}

void app_main(void)
{
    brama_hw_outputs_safe(); // przed czymkolwiek innym (2.2)
    brama_hw_led_init();
    if (brama_crypto_init() != 0 || brama_hw_nvs_init() != ESP_OK) ESP_LOGE(TAG, "NVS/krypto niedostępne");
    brama_nvs_t nvs = brama_hw_nvs();

    static brama_image_t image;
    brama_localcfg_t cfg = {500, false, 50};
    int factory_rc = brama_factory_load(&nvs, &s_factory);
    brama_image_status_t img = brama_image_load(&nvs, &image);
    int cfg_rc = brama_localcfg_load(&nvs, &cfg);
    brama_boot_decision_t d = brama_boot_decide(button_held_at_boot(), factory_rc, img, cfg_rc);
    bool pending = brama_hw_ota_pending_verify();

    if (d.mode == BRAMA_BOOT_SERVICE || d.mode == BRAMA_BOOT_PROVISIONING) {
        // Bez BLE i bez aktora; nowy obraz OTA nie jest tu potwierdzany (15.5) — kolejny start wróci do poprzedniego.
        brama_service_console_run(&nvs, d.mode == BRAMA_BOOT_PROVISIONING ? "Tryb provisioningu (aplikacja nie dziala)"
                                                                          : "Tryb serwisowy (aplikacja nie dziala)");
        return;
    }

    // Reguła z kopii RTC (14.5). W trybie błędu obrazu najwyżej jedna próba CLOSE; przy błędzie konfiguracji brak.
    brama_rtc_t rtc = brama_hw_rtc();
    brama_clock_t mono = brama_hw_clock(), rtcclk = brama_hw_rtc_clock();
    brama_rtc_copy_t copy;
    bool have_copy = rtc.load(rtc.ctx, &copy, sizeof copy);
    brama_rule_boot_env_t renv = {
        .cold_start = brama_hw_cold_start(), .image_ok = d.mode == BRAMA_BOOT_NORMAL,
        .allow_error_close = d.allow_error_close,
        .auth_generation = image.auth_generation, .owner_active = owner_active, .ctx = &image,
        .now_us = mono.now_us(mono.ctx), .rtc_now_us = rtcclk.now_us(rtcclk.ctx)};
    brama_rule_t rule;
    brama_rule_boot(&rule, have_copy ? &copy : NULL, &renv);
    if (!rule.rtc_frozen) {
        // Termin BOOT_WAIT trafia od razu do kopii: kolejne restarty go dziedziczą (A63).
        brama_rule_to_rtc(&rule, renv.now_us, renv.rtc_now_us, &copy);
        rtc.store(rtc.ctx, &copy, sizeof copy);
    }

    s_lock = xSemaphoreCreateRecursiveMutex();
    s_queue = xQueueCreate(2 * BRAMA_ACTOR_QUEUE, sizeof(brama_actor_msg_t));
    brama_ota_port_t ota_port = brama_hw_ota();
    brama_ota_init(&s_ota, &ota_port);
    brama_actor_env_t env = {
        .clock = mono, .rtc_clock = rtcclk, .nvs = nvs, .rtc = rtc, .outputs = brama_hw_outputs(),
        .send = brama_ble_send, .pair_result = brama_ble_pair_result, .repair_result = brama_ble_repair_result,
        .client_removed = brama_ble_client_removed, .disconnect = brama_ble_disconnect,
        .ota_restart = brama_ble_ota_restart};
    if (img != BRAMA_IMG_OK) brama_image_init_empty(&image); // tryb błędu: brak klientów i READY (14.5 pkt 5)
    brama_actor_init(&s_actor, &env, &image, &cfg, &rule, d.mode == BRAMA_BOOT_NORMAL);
    s_actor.ota = &s_ota;
    // Reset naprawia wyłącznie obraz autoryzacji; błąd danych fabrycznych lub konfiguracji lokalnej zostaje (14.5).
    s_actor.reset_restores_control = factory_rc == BRAMA_OK && cfg_rc == BRAMA_OK;
    brama_button_init(&s_button);
    brama_hw_interlock_init(cfg.interlock_enabled, cfg.interlock_filter_ms, on_interlock, NULL);

    xTaskCreate(actor_task, "actor", 8192, NULL, 5, NULL);
    static esp_timer_handle_t tick;
    esp_timer_create_args_t targs = {.callback = actor_tick_cb, .name = "actor_tick"};
    if (esp_timer_create(&targs, &tick) == ESP_OK) esp_timer_start_periodic(tick, 100 * 1000);
    xTaskCreate(ui_task, "ui", 4096, NULL, 3, NULL);
    brama_ble_config_t bcfg = {.actor = &s_actor, .factory = &s_factory, .lock = s_lock, .post = post};
    bool ble_ok = brama_ble_start(&bcfg) == ESP_OK;
    brama_service_console_run(&nvs, "Konsola serwisowa (aplikacja dziala)"); // pełny zestaw komend także w trybie normalnym (IO0 = download, brak przycisku serwisowego)

    // Autotest nowego obrazu OTA (15.5): obraz autoryzacji i konfiguracja czytelne bez zapisu, wyjścia
    // zainicjalizowane, NimBLE uruchomiony. Migracja schematu dopiero po potwierdzeniu (obecnie schemat 1).
    bool selftest = d.mode == BRAMA_BOOT_NORMAL && ble_ok;
    brama_first_boot_t fb = brama_ota_first_boot(pending, false, selftest);
    if (fb == BRAMA_FB_ROLLBACK) {
        ESP_LOGE(TAG, "Autotest nowego obrazu nieudany — powrót do poprzedniej wersji");
        brama_hw_ota_rollback();
    } else if (fb == BRAMA_FB_CONFIRM_THEN_MIGRATE) {
        brama_hw_ota_mark_valid();
    }
    (void)brama_boot_migration_allowed(fb);
    ESP_LOGI(TAG, "Start: tryb %d, obraz %d, klienci %d", d.mode, img, image.count);
}
