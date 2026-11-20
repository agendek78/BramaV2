#pragma once
// Autorytatywny obraz autoryzacji A/B (15.2), dane fabryczne i konfiguracja lokalna (15.4), okno rejestracji
// i limity nieudanych prób (9.4, 11.3, 11.4). Zapisy wyłącznie przez brama_nvs_t (licznik A59).
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "brama_ports.h"

#ifdef __cplusplus
extern "C" {
#endif

#define BRAMA_MAX_CLIENTS 8
#define BRAMA_IMAGE_SCHEMA 2 // 2: nazwa telefonu w rekordzie klienta (client_management_design.md), bez migracji ze schematu 1
#define BRAMA_CLIENT_NAME_MAX 20
#define BRAMA_BOND_DATA_MAX 96 // dwa rekordy klucza (nasz i peer) po 44 B w zwartym formacie brama_ble
#define BRAMA_WINDOW_MS 60000
#define BRAMA_FAILURE_WINDOW_MS 60000
#define BRAMA_MAX_FAILURES 5
#define BRAMA_PIN_FAILURES_PER_WINDOW 5
#define BRAMA_REMOTE_PIN_RESET_MS 3600000

/** Zatwierdzony bond (9.5): tożsamość peer i nieprzezroczyste dane magazynu stosu BLE. */
typedef struct {
    uint8_t id_addr_type;
    uint8_t id_addr[6];
    uint8_t len;
    uint8_t data[BRAMA_BOND_DATA_MAX];
} brama_bond_t;

typedef struct {
    uint8_t client_id[16];
    uint8_t k_client[32];
    brama_bond_t bond;
    uint8_t name_len;                       // 0–BRAMA_CLIENT_NAME_MAX
    uint8_t name[BRAMA_CLIENT_NAME_MAX];    // UTF-8 bez znaków sterujących, reszta zerowa
} brama_client_rec_t;

typedef struct {
    uint16_t schema;
    uint64_t image_generation;
    uint64_t auth_generation;
    uint8_t count;
    brama_client_rec_t clients[BRAMA_MAX_CLIENTS];
    int8_t slot; // slot, z którego obraz wczytano lub do którego zapisano: 0 = A, 1 = B, -1 = brak (nie serializowany)
} brama_image_t;

typedef enum { BRAMA_IMG_OK = 0, BRAMA_IMG_MISSING, BRAMA_IMG_CORRUPT, BRAMA_IMG_NEWER_SCHEMA } brama_image_status_t;

/** Nazwa telefonu: długość ≤ 20, poprawny UTF-8 (bez overlong i surogatów), bez znaków sterujących (C0, DEL, C1). */
bool brama_client_name_valid(const uint8_t *name, size_t len);
void brama_image_init_empty(brama_image_t *img);
/** Poprawny slot o wyższym image_generation; nigdy nie zapisuje. */
brama_image_status_t brama_image_load(brama_nvs_t *nvs, brama_image_t *out);
/** Zapis do slotu przeciwnego niż img->slot i weryfikacja odczytem; po sukcesie img->slot = nowy slot. */
int brama_image_commit(brama_nvs_t *nvs, brama_image_t *img);
/** Kopia z image_generation + 1 (BRAMA_ERR_NO_SPACE przy wyczerpaniu licznika — bez zawijania). */
int brama_image_next(const brama_image_t *cur, brama_image_t *next);
/** Reset autoryzacji (9.6): pusta lista, auth_generation + 1, image_generation + 1. */
int brama_image_reset(const brama_image_t *cur, brama_image_t *next);
int brama_image_find(const brama_image_t *img, const uint8_t client_id[16]);
/** Dodaje albo zastępuje rekord o tym samym client_id; BRAMA_ERR_NO_SPACE przy ośmiu innych. */
int brama_image_put_client(brama_image_t *img, const brama_client_rec_t *c);
int brama_image_remove_client(brama_image_t *img, int index);

/** Dane fabryczne (9.2, 15.4). */
typedef struct {
    uint8_t device_id[16];
    uint8_t k_setup[32];
    uint8_t pin[6];
    bool provisioning_locked;
} brama_factory_t;

/** BRAMA_ERR_STORAGE: brak danych; BRAMA_ERR_FORMAT: uszkodzone. */
int brama_factory_load(brama_nvs_t *nvs, brama_factory_t *out);
int brama_factory_save(brama_nvs_t *nvs, const brama_factory_t *f);

/** Konfiguracja lokalna (15.4): impuls 100–1000 ms, interlock, filtr ≥ 50 ms. */
typedef struct {
    uint16_t pulse_ms;
    bool interlock_enabled;
    uint16_t interlock_filter_ms;
} brama_localcfg_t;

bool brama_localcfg_valid(const brama_localcfg_t *c);
int brama_localcfg_load(brama_nvs_t *nvs, brama_localcfg_t *out);
int brama_localcfg_save(brama_nvs_t *nvs, const brama_localcfg_t *c);

/** Okno rejestracji i limity nieudanych prób (RAM). Peer = typ i adres tożsamości (7 B). */
typedef struct {
    bool used;
    uint8_t peer[7];
    int64_t times[BRAMA_MAX_FAILURES];
    uint8_t next;
    int64_t last;
} brama_peer_failures_t;

typedef struct {
    bool window_open;
    int64_t window_until_ms;
    uint8_t pin_failures;
    int64_t pin_reset_ms;      // ostatnie zerowanie licznika PIN-u (okno z telefonu: najwyżej raz na godzinę)
    brama_peer_failures_t peers[9];
} brama_auth_limits_t;

/** Po starcie licznik PIN-u jest wyczerpany: próby PIN-u daje przycisk albo okno z telefonu po godzinie pracy. */
void brama_auth_limits_init(brama_auth_limits_t *a);
/** Okno 60 s od teraz (od zwolnienia przycisku); ponowne otwarcie liczy od nowa i zeruje licznik PIN-u. */
void brama_auth_window_open(brama_auth_limits_t *a, int64_t now_ms);
/**
 * Okno otwarte komendą zarejestrowanego telefonu (phone_window_design): jak przycisk, ale licznik nieudanych
 * potwierdzeń PIN-u zeruje najwyżej raz na BRAMA_REMOTE_PIN_RESET_MS — bez dostępu do płytki ≤ 5 prób PIN-u na godzinę.
 */
void brama_auth_window_open_remote(brama_auth_limits_t *a, int64_t now_ms);
void brama_auth_window_close(brama_auth_limits_t *a);
/** Nowa próba PIN-u (PAKE_ROUND1): okno otwarte i globalny licznik nieudanych potwierdzeń niewyczerpany (11.4). */
bool brama_auth_pin_attempt_allowed(const brama_auth_limits_t *a, int64_t now_ms);
/** Ile jeszcze nieudanych potwierdzeń PIN-u zmieści okno (0..5); 0 także przy zamkniętym oknie. */
uint8_t brama_auth_pin_attempts_left(const brama_auth_limits_t *a, int64_t now_ms);
bool brama_auth_window_is_open(const brama_auth_limits_t *a, int64_t now_ms);
/** Nieudana próba; `pin` = nieudane potwierdzenie PIN-u (piąte w oknie zamyka okno, 11.4). */
void brama_auth_note_failure(brama_auth_limits_t *a, const uint8_t peer[7], bool pin, int64_t now_ms);
/** ≥ 5 nieudanych prób tego peer w ostatnich 60 s (11.3). */
bool brama_auth_rate_limited(const brama_auth_limits_t *a, const uint8_t peer[7], int64_t now_ms);

#ifdef __cplusplus
}
#endif
