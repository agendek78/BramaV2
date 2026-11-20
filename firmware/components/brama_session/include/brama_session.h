#pragma once
// Strona serwera handshake i sesji jednego łącza BLE (11, 12, 13.4): PAKE, PAIR, SESSION, szyfrowanie GCM,
// liczniki, cache odpowiedzi i duplikaty. Nie zmienia stanu autoryzacji ani reguły — decyzje należą do aktora
// (15.2.1); stan potrzebny do odpowiedzi czyta przez brama_session_env_t. Kontekst wyłącznie w RAM.
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "brama_ecjpake.h"
#include "brama_ports.h"
#include "brama_proto.h"

#ifdef __cplusplus
extern "C" {
#endif

#define BRAMA_ID_LEN 16
#define BRAMA_NONCE_LEN 32
#define BRAMA_KEY_LEN 32
#define BRAMA_SESSION_CACHE 16
#define BRAMA_CACHED_RESPONSE_MAX 64

/** Odczyt stanu potrzebnego handshake; wywołania tylko do odczytu. */
typedef struct {
    const uint8_t *device_id;  // 16 B
    const uint8_t *k_setup;    // 32 B
    const uint8_t *pin;        // 6 B ASCII
    brama_rng_t rng;
    /** K_client zatwierdzonego klienta; false = nieznany klient. */
    bool (*client_key)(void *ctx, const uint8_t client_id[BRAMA_ID_LEN], uint8_t key_out[BRAMA_KEY_LEN]);
    /** Po poprawnym HMAC PAIR_BEGIN: 0 = przyjęty, inaczej kod brama_reject_reason_t (12.4). */
    uint8_t (*pair_admit)(void *ctx, const uint8_t client_id[BRAMA_ID_LEN], bool via_pin);
    /**
     * Po poprawnym HMAC SESSION_BEGIN: 0 = przyjęty, inaczej kod odmowy (WINDOW_CLOSED, REPLACEMENT_BLOCKED, 12.4).
     * `repair` = true: łącze naprawy bondu (9.5) — SESSION_READY dopiero po transakcji aktora.
     */
    uint8_t (*session_admit)(void *ctx, const uint8_t client_id[BRAMA_ID_LEN], bool *repair);
    /** Nowa próba PIN-u (PAKE_ROUND1) dozwolona: okno otwarte i limit nieudanych potwierdzeń PIN-u niewyczerpany. */
    bool (*window_open)(void *ctx);
    /** UNAVAILABLE (tryb błędu, reset) lub RATE_LIMITED przed obsługą nowej próby; 0 = brak przeszkód. */
    uint8_t (*attempt_blocker)(void *ctx);
    /** Snapshot dla odbiorcy SESSION_READY (owner_relation względem client_id). */
    void (*snapshot_for)(void *ctx, const uint8_t client_id[BRAMA_ID_LEN], brama_snapshot_t *out);
    void *ctx;
} brama_session_env_t;

typedef enum {
    BRAMA_EV_NONE = 0,
    BRAMA_EV_SESSION_READY,  // client_id: sesja READY (właściciel mógł wrócić, 14.3)
    BRAMA_EV_COMMAND,        // zweryfikowana wiadomość sesji: type, payload, seq → brama_session_respond
    BRAMA_EV_PAIR_COMMIT,    // client_id, k_client, via_pin → transakcja aktora → brama_session_pair_finish
    BRAMA_EV_INVALIDATED,    // sesja/próba unieważniona (błąd protokołu); nierozstrzygnięte operacje porzucone
    BRAMA_EV_REPAIR_COMMIT,  // client_id: SESSION_CONFIRM naprawy bondu → transakcja aktora → brama_session_repair_finish
} brama_session_event_t;

typedef struct {
    uint8_t reply[BRAMA_FRAME_MAX];  // ramka do wysłania (reply_len = 0: brak)
    size_t reply_len;
    brama_session_event_t event;
    uint8_t type;                    // EV_COMMAND
    const uint8_t *payload;          // EV_COMMAND: jawny payload (ważny do następnego wywołania na kontekście)
    size_t payload_len;
    uint64_t seq;                    // EV_COMMAND: request_seq
    uint8_t client_id[BRAMA_ID_LEN]; // EV_SESSION_READY, EV_PAIR_COMMIT, EV_REPAIR_COMMIT
    uint8_t k_client[BRAMA_KEY_LEN]; // EV_PAIR_COMMIT (wołający zeruje)
    bool via_pin;
    bool auth_failure;               // nieudana próba do limitów 11.3
    bool pin_failure;                // nieudane potwierdzenie PIN-u (globalny licznik okna, 11.4)
} brama_step_t;

typedef struct {
    uint8_t req_hash[32];
    uint64_t req_seq;
    uint8_t resp[BRAMA_CACHED_RESPONSE_MAX];
    uint8_t resp_len;
    bool used;
} brama_cache_entry_t;

typedef enum {
    BRAMA_SS_IDLE = 0, BRAMA_SS_PAKE_R1, BRAMA_SS_PAKE_DONE, BRAMA_SS_AWAIT_CONFIRM, BRAMA_SS_PAIR_GRANTED,
    BRAMA_SS_PAIR_COMMITTING, BRAMA_SS_PAIR_DONE, BRAMA_SS_REPAIR_COMMITTING, BRAMA_SS_READY
} brama_session_state_t;

typedef struct {
    brama_session_state_t state;
    bool pair;                       // bieżący handshake: PAIR (true) czy SESSION
    bool via_pin;
    bool repair;                     // bieżący SESSION to naprawa bondu (9.5)
    uint64_t session_id;
    uint8_t client_id[BRAMA_ID_LEN];
    uint8_t client_nonce[BRAMA_NONCE_LEN];
    uint8_t server_nonce[BRAMA_NONCE_LEN];
    uint8_t hs_key[BRAMA_KEY_LEN];   // K_setup / K_pin / K_client bieżącego handshake
    uint8_t k_pin[BRAMA_KEY_LEN];
    bool has_k_pin;
    uint8_t k_client[BRAMA_KEY_LEN]; // wydany w PAIR_GRANT
    uint8_t c2s_key[32], s2c_key[32], c2s_iv[4], s2c_iv[4];
    uint64_t last_c2s, next_s2c;
    // Idempotencja handshake (11.3, 13.4): skrót żądania → zachowana odpowiedź.
    uint8_t begin_hash[32], confirm_hash[32], commit_hash[32];
    uint8_t challenge[BRAMA_FRAME_HEADER_LEN + 96 + 32];
    uint8_t final_resp[BRAMA_FRAME_HEADER_LEN + 32 + 16]; // READY albo GRANT
    uint8_t done_resp[BRAMA_FRAME_HEADER_LEN + 16];
    size_t final_len;
    // PAKE
    brama_jpake_t jpake;
    bool jpake_active;
    uint8_t pake_r1_hash[32], pake_r2_hash[32];
    uint8_t pake_reply1[BRAMA_FRAME_HEADER_LEN + 340], pake_reply2[BRAMA_FRAME_HEADER_LEN + 180];
    size_t pake_reply1_len, pake_reply2_len;
    uint8_t pake_transcript[340 + 340 + 180 + 180];
    size_t pake_transcript_len;
    // Sesja READY
    brama_cache_entry_t cache[BRAMA_SESSION_CACHE];
    unsigned cache_next;
    uint8_t pending_hash[32];        // EV_COMMAND czekający na brama_session_respond
    uint8_t list_hash[32];           // ostatni LIST_CLIENTS z odpowiedzią spoza cache (> 64 B): duplikat wykonywany ponownie
    bool list_hash_set;
    uint8_t plain[BRAMA_FRAME_MAX];
} brama_session_t;

void brama_session_init(brama_session_t *s);
/** Kompletna ramka z RX łącza. */
void brama_session_on_frame(brama_session_t *s, const brama_session_env_t *env, const uint8_t *frame, size_t len,
                            brama_step_t *out);
/** Odpowiedź (ACK/PONG/OTA_ACK) na EV_COMMAND o request_seq; zapamiętywana dla duplikatów. */
int brama_session_respond(brama_session_t *s, uint64_t request_seq, uint8_t type, const uint8_t *payload, size_t len,
                          uint8_t *out, size_t *out_len);
/** Zdarzenie s2c bez żądania (RULE_STATUS); nie trafia do cache. */
int brama_session_seal_event(brama_session_t *s, uint8_t type, const uint8_t *payload, size_t len, uint8_t *out,
                             size_t *out_len);
/** Wynik transakcji PAIR_COMMIT: PAIR_DONE (ok) albo HANDSHAKE_REJECT/STORAGE_FAILURE. */
int brama_session_pair_finish(brama_session_t *s, bool ok, uint8_t *out, size_t *out_len);
/** Wynik transakcji naprawy bondu: 0 → SESSION_READY (snapshot z tej chwili), inaczej HANDSHAKE_REJECT z kodem. */
int brama_session_repair_finish(brama_session_t *s, const brama_session_env_t *env, uint8_t code, uint8_t *out,
                                size_t *out_len);
bool brama_session_ready(const brama_session_t *s);
/** Unieważnia sesję/próbę i zeruje sekrety. */
void brama_session_reset(brama_session_t *s);

#ifdef __cplusplus
}
#endif
