#pragma once
// Decyzje startu (14.5, 15.4, 15.5) i dane provisioningu (9.2) — czysta logika; app_main wykonuje kroki.
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "brama_auth.h"
#include "brama_ota.h"
#include "brama_ports.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum { BRAMA_BOOT_NORMAL, BRAMA_BOOT_LOCAL_ERROR, BRAMA_BOOT_SERVICE, BRAMA_BOOT_PROVISIONING } brama_boot_mode_t;

typedef struct {
    brama_boot_mode_t mode;
    bool allow_error_close; // LOCAL_ERROR z obrazu: jedna próba CLOSE z kopii RTC; z konfiguracji lokalnej: bez impulsu
} brama_boot_decision_t;

/**
 * Tryb startu: przycisk przy starcie → serwisowy (fizyczny dostęp, 15.4); brak danych fabrycznych → provisioning;
 * uszkodzone dane fabryczne, obraz autoryzacji lub konfiguracja lokalna → tryb błędu lokalnego (14.5 pkt 5).
 * `factory_rc`, `cfg_rc`: wynik brama_factory_load / brama_localcfg_load.
 */
brama_boot_decision_t brama_boot_decide(bool button_held, int factory_rc, brama_image_status_t img, int cfg_rc);

/** `brama://provision?v=2&id=<hex>&k=<base64url bez paddingu>&p=<6 cyfr>` (9.2). */
int brama_qr_text(const uint8_t device_id[16], const uint8_t k_setup[32], const uint8_t pin[6], char *out, size_t cap);

/** PIN 000000–999999 równomiernie, z odrzucaniem wartości ≥ 4 294 000 000 (bez obciążenia modulo, 9.2). */
void brama_pin_generate(const brama_rng_t *rng, uint8_t pin[6]);

/** Nowe device_id, K_setup i PIN z CSPRNG (15.4). */
void brama_factory_generate(const brama_rng_t *rng, brama_factory_t *out);

/** Migracja schematu NVS dopiero po potwierdzeniu nowego obrazu (15.4, 15.5). */
bool brama_boot_migration_allowed(brama_first_boot_t fb);

#ifdef __cplusplus
}
#endif
