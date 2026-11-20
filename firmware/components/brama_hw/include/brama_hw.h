#pragma once
// Implementacje portów na ESP32 (spec 2.2, 14.5, 15.2, 15.5). Weryfikacja działania wymaga płytki (Etap 0/2).
#include <stdbool.h>
#include <stdint.h>
#include "brama_ports.h"

#ifdef __cplusplus
extern "C" {
#endif

/** Wyjścia w stanie nieaktywnym — pierwsza czynność app_main, przed czymkolwiek innym (2.2). */
void brama_hw_outputs_safe(void);
/** Port wyjść: impuls kończy callback timera w ISR (IRAM), wykluczenie jednoczesnego impulsu obu wyjść. */
brama_outputs_t brama_hw_outputs(void);

/** Interlock fail-safe z filtrem (rozwarcie = blokada); `on_change` z zadania timera po zmianie stanu. */
void brama_hw_interlock_init(bool enabled, uint16_t filter_ms, void (*on_change)(bool active, void *ctx), void *ctx);

void brama_hw_led_init(void);
void brama_hw_led_set(bool on);
bool brama_hw_button_pressed(void);

brama_clock_t brama_hw_clock(void);
brama_clock_t brama_hw_rtc_clock(void);
brama_rtc_t brama_hw_rtc(void);
/** Zimny start: włączenie zasilania (ESP_RST_POWERON); brownout i restarty programowe nie (14.5). */
bool brama_hw_cold_start(void);

brama_rng_t brama_hw_rng(void);

/** NVS w przestrzeni "brama"; licznik zapisów (A59). */
esp_err_t brama_hw_nvs_init(void);
brama_nvs_t brama_hw_nvs(void);
uint32_t brama_hw_nvs_writes(void);

brama_ota_port_t brama_hw_ota(void);
/** Obraz w stanie ESP_OTA_IMG_PENDING_VERIFY (pierwszy start po aktualizacji). */
bool brama_hw_ota_pending_verify(void);
void brama_hw_ota_mark_valid(void);
void brama_hw_ota_rollback(void);

#ifdef __cplusplus
}
#endif
