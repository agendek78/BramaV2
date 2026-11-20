#pragma once
// control_actor (15.2.1): jedyny autor obrazu NVS, stanu sterowania w RAM i kopii reguły w RTC. Jedna kolejka;
// każda wiadomość niesie tokeny, które aktor sprawdza przed wykonaniem. Komendy, auto-CLOSE i FAULT zmieniają
// tylko RAM i RTC (decyzja 12); NVS zapisują wyłącznie PAIR_COMMIT i reset.
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "brama_auth.h"
#include "brama_ota.h"
#include "brama_ports.h"
#include "brama_proto.h"
#include "brama_rule.h"

#ifdef __cplusplus
extern "C" {
#endif

#define BRAMA_MAX_LINKS 9
#define BRAMA_ACTOR_QUEUE 16
#define BRAMA_PULSE_GAP_MIN_MS 1000
#define BRAMA_PULSE_REST_MS 200

typedef enum {
    BRAMA_AMSG_COMMAND,        // zweryfikowana wiadomość sesji (OPEN/CLOSE/PING/nieznana; OTA w brama_ota)
    BRAMA_AMSG_SESSION_READY,  // świeży SESSION_CONFIRM klienta na łączu
    BRAMA_AMSG_DISCONNECT,     // rzeczywista utrata łącza (link_token)
    BRAMA_AMSG_PAIR_COMMIT,    // PAIR_COMMIT: client_id, k_client, bond
    BRAMA_AMSG_BUTTON_WINDOW,  // przycisk 3–10 s (9.4)
    BRAMA_AMSG_BUTTON_RESET,   // przycisk ≥ 10 s: reset autoryzacji (9.6) — pierwszeństwo w kolejce
    BRAMA_AMSG_INTERLOCK,      // zmiana przefiltrowanego stanu interlocku
    BRAMA_AMSG_TICK,           // upływ czasu (terminy reguły, oczekiwanie BUSY)
    BRAMA_AMSG_SESSION_INVALIDATED, // sesja łącza unieważniona lub zastąpiona (rekey) — bez utraty radia
    BRAMA_AMSG_REPAIR_COMMIT,  // naprawa bondu (9.5): client_id po SESSION_CONFIRM, nowy bond ze stagingu
} brama_actor_msg_type_t;

typedef struct {
    brama_actor_msg_type_t type;
    int link;                  // 0..8
    uint64_t link_token;       // fizyczna instancja łącza
    uint64_t session_token;    // sesja READY (COMMAND) albo próba (PAIR_COMMIT, REPAIR_COMMIT)
    uint64_t auth_generation;
    uint8_t client_id[16];
    uint8_t cmd_type;
    uint8_t payload[BRAMA_FRAME_MAX];
    size_t payload_len;
    uint64_t seq;
    uint8_t k_client[32];
    brama_bond_t bond;
    bool interlock_active;
    uint16_t mtu;              // wynegocjowany ATT MTU łącza (OTA_BEGIN, 15.5)
} brama_actor_msg_t;

typedef struct {
    brama_clock_t clock;       // monotoniczny
    brama_clock_t rtc_clock;   // licznik RTC (przeniesienie terminu przez restart)
    brama_nvs_t nvs;
    brama_rtc_t rtc;
    brama_outputs_t outputs;
    /** Odpowiedź (request_seq ≥ 1) albo zdarzenie (request_seq = 0, np. RULE_STATUS) na sesji łącza. */
    void (*send)(void *ctx, int link, uint64_t session_token, uint64_t request_seq, uint8_t type, const uint8_t *payload,
                 size_t len);
    void (*pair_result)(void *ctx, int link, uint64_t attempt_token, bool ok);
    /**
     * Wynik naprawy bondu: 0 = zatwierdzona, inaczej kod HANDSHAKE_REJECT (12.4). `replaced`: zastąpiony bond o innym
     * adresie tożsamości (do usunięcia ze stosu BLE niezależnie od losu łącza), inaczej NULL.
     */
    void (*repair_result)(void *ctx, int link, uint64_t attempt_token, uint8_t code, const brama_bond_t *replaced);
    /** Usunięcie klienta (REMOVE_CLIENT): bond do usunięcia z magazynu i hosta stosu BLE (tylko gdy bond.len > 0). */
    void (*client_removed)(void *ctx, const brama_bond_t *bond);
    /** Wymuszone zakończenie łącza (reset, zastąpienie poświadczenia); disconnect nie tworzy WAITING. */
    void (*disconnect)(void *ctx, int link);
    /** OTA_END zakończony OK: restart po potwierdzeniu wskazania OTA_ACK albo po 2 s (15.5). */
    void (*ota_restart)(void *ctx, int link);
    void *ctx;
} brama_actor_env_t;

typedef struct {
    bool used;
    bool ready;
    bool authenticated;        // łącze co najmniej raz ukończyło SESSION_CONFIRM (14.1); unieważnienie sesji go nie zmienia
    uint64_t link_token;
    uint64_t session_token;
    uint8_t client_id[16];
} brama_link_t;

typedef struct {
    brama_actor_env_t env;
    brama_image_t image;
    brama_localcfg_t cfg;
    brama_auth_limits_t limits;
    brama_rule_t rule;
    brama_ota_t *ota;          // NULL: OTA niedostępne (UNSUPPORTED_TYPE)
    bool control_enabled;
    bool reset_restores_control; // false: tryb błędu z konfiguracji/danych fabrycznych — reset go nie naprawia
    bool interlock_active;
    brama_link_t links[BRAMA_MAX_LINKS];
    // impulsy (2.2): odstęp początków max(1000, pulse + 200) ms
    bool pulsed;
    int64_t last_pulse_start_us;
    int64_t close_wait_since_us; // oczekiwanie BUSY auto-CLOSE (≤ 2 s); 0 = brak
    // kolejka
    brama_actor_msg_t queue[BRAMA_ACTOR_QUEUE];
    unsigned head, count;
} brama_actor_t;

void brama_actor_init(brama_actor_t *a, const brama_actor_env_t *env, const brama_image_t *image,
                      const brama_localcfg_t *cfg, const brama_rule_t *rule, bool control_enabled);
/** false przy pełnej kolejce (wiadomość odrzucona bez skutków). BUTTON_RESET trafia na początek kolejki. */
bool brama_actor_post(brama_actor_t *a, const brama_actor_msg_t *m);
/** Przetwarza jedną wiadomość; false, gdy kolejka pusta. */
bool brama_actor_step(brama_actor_t *a);
/** Najbliższe zdarzenie czasowe (µs zegara monotonicznego) albo INT64_MAX. */
int64_t brama_actor_next_deadline(const brama_actor_t *a);
/** BUSY: trwa impuls albo nie minął odstęp od początku poprzedniego (2.2). */
bool brama_actor_busy(const brama_actor_t *a, int64_t now_us);
/** Klucz zatwierdzonego klienta (dla brama_session_env_t). */
bool brama_actor_client_key(const brama_actor_t *a, const uint8_t client_id[16], uint8_t key_out[32]);
/** Klucz klienta tylko na łączu jego zatwierdzonego bondu (peer = typ i adres tożsamości, 7 B). */
bool brama_actor_client_key_for_peer(const brama_actor_t *a, const uint8_t client_id[16], const uint8_t peer[7],
                                     uint8_t key_out[32]);
/**
 * Naprawa bondu klienta na łączu `link` (9.5): REPLACEMENT_BLOCKED, gdy klient ma inne łącze z ukończonym SESSION
 * albo adres tożsamości `bond` (len > 0) należy do innego klienta; inaczej 0.
 */
uint8_t brama_actor_repair_blocker(const brama_actor_t *a, int link, const uint8_t client_id[16], const brama_bond_t *bond);
brama_snapshot_t brama_actor_snapshot_for(const brama_actor_t *a, const uint8_t client_id[16]);

#ifdef __cplusplus
}
#endif
