#pragma once
// Transport BLE na NimBLE (8, 9.5, 10, 12, 15.5): reklama i filtr połączeń, GATT INFO/RX/TX i Device Information,
// dziewięć kontekstów łączy, fragmentacja, sesje, własny magazyn bondów z obrazu autoryzacji (bez CCCD) i kolejka
// TX ze wskazaniami. Stan aktora i łączy chroni jeden rekursywny mutex (cfg.lock). Działanie wymaga płytki (Etap 0/2).
#include <stdbool.h>
#include <stdint.h>
#include "brama_actor.h"
#include "brama_auth.h"
#include "esp_err.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    brama_actor_t *actor;
    const brama_factory_t *factory;
    SemaphoreHandle_t lock;                        // rekursywny: aktor + BLE
    void (*post)(const brama_actor_msg_t *m);      // do kolejki zadania aktora
} brama_ble_config_t;

esp_err_t brama_ble_start(const brama_ble_config_t *cfg);
/** Po zmianie obrazu, okna rejestracji lub control_enabled: filtr połączeń i reklama (8.3). */
void brama_ble_refresh_advertising(void);
bool brama_ble_any_ready(void);

// Wywołania zwrotne aktora (brama_actor_env_t), wołane z zadania aktora pod cfg.lock.
void brama_ble_send(void *ctx, int link, uint64_t session_token, uint64_t request_seq, uint8_t type,
                    const uint8_t *payload, size_t len);
void brama_ble_pair_result(void *ctx, int link, uint64_t token, bool ok);
void brama_ble_repair_result(void *ctx, int link, uint64_t token, uint8_t code, const brama_bond_t *replaced);
/** Usunięcie klienta przez REMOVE_CLIENT: sprzątanie wpisu peera w stosie (retry po rozłączeniu, gdy łącze trwa). */
void brama_ble_client_removed(void *ctx, const brama_bond_t *bond);
void brama_ble_disconnect(void *ctx, int link);
void brama_ble_ota_restart(void *ctx, int link);

// Magazyn bondów (store.c): zwarty, niezależny od ABI NimBLE format w brama_bond_t.

int brama_ble_store_read(int obj_type, const void *key, void *value);
int brama_ble_store_write(int obj_type, const void *value);
int brama_ble_store_delete(int obj_type, const void *key);

#ifdef __cplusplus
}
#endif
