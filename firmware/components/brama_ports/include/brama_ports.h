#pragma once
// Porty (interfejsy) między czystą logiką a sprzętem ESP32. Implementacje: brama_hw (ESP32) i fałszywe porty testów.

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum { BRAMA_OUTPUT_OPEN = 0, BRAMA_OUTPUT_CLOSE = 1 } brama_output_t;

/** Zegar monotoniczny w mikrosekundach. */
typedef struct {
    int64_t (*now_us)(void *ctx);
    void *ctx;
} brama_clock_t;

/** CSPRNG (spec 15.3) — nigdy uptime/MAC/licznik. */
typedef struct {
    void (*fill)(void *ctx, uint8_t *buf, size_t len);
    void *ctx;
} brama_rng_t;

/** NVS: klucz → blob. Każdy write/erase liczy się do licznika zapisów (A59). */
typedef struct {
    esp_err_t (*read)(void *ctx, const char *key, void *buf, size_t *len);
    esp_err_t (*write)(void *ctx, const char *key, const void *buf, size_t len);
    esp_err_t (*erase)(void *ctx, const char *key);
    void *ctx;
} brama_nvs_t;

/** Kopia reguły w pamięci RTC (14.5). load zwraca false, gdy nie ma danych do odczytu. */
typedef struct {
    bool (*load)(void *ctx, void *buf, size_t len);
    void (*store)(void *ctx, const void *buf, size_t len);
    void *ctx;
} brama_rtc_t;

/** Wyjścia napędu i interlock (2.2). start_pulse zwraca false przy błędzie IO; koniec impulsu należy do implementacji.
 *  stop_pulse natychmiast kończy trwający impuls (reset autoryzacji, 9.6). */
typedef struct {
    bool (*start_pulse)(void *ctx, brama_output_t out, uint32_t ms);
    bool (*interlock_active)(void *ctx);
    void (*stop_pulse)(void *ctx);
    void *ctx;
} brama_outputs_t;

/** Zapis obrazu OTA do nieaktywnego slotu (15.5). end weryfikuje obraz i podpis (esp_ota_end); po błędzie end
 *  transfer jest zakończony bez abort. running_secure_version: numer bezpieczeństwa działającego obrazu. */
typedef struct {
    esp_err_t (*begin)(void *ctx, uint32_t image_size);
    esp_err_t (*write)(void *ctx, const uint8_t *data, size_t len);
    esp_err_t (*end)(void *ctx);
    esp_err_t (*set_boot)(void *ctx);
    void (*abort)(void *ctx);
    uint32_t (*running_secure_version)(void *ctx);
    void *ctx;
} brama_ota_port_t;

#ifdef __cplusplus
}
#endif
