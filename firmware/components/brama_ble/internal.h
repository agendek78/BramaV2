#pragma once
#include "brama_ble.h"
#include "brama_session.h"
#include "host/ble_hs.h"

typedef struct {
    bool used;
    uint16_t conn;
    uint64_t link_token;
    uint64_t session_token;   // bieżąca sesja READY albo próba PAIR (tokeny aktora, 15.2.1)
    bool encrypted, approved, candidate, subscribed, ever_ready;
    uint8_t repeat_pairs;     // zdarzenia REPEAT_PAIRING na tym łączu (ochrona przed pętlą RETRY)
    bool repairing;           // ponowne parowanie w oknie: zatwierdzony bond tego peer ukryty przed stosem (9.5)
    ble_addr_t peer_id;
    uint16_t mtu;
    int64_t connected_us;
    brama_session_t session;
    brama_session_env_t env;
    brama_reasm_t rx;
    // TX: odpowiedzi przed zdarzeniami statusu; status pieczętowany dopiero przy wysyłce (scalanie, 13.3)
    uint8_t tx_frame[BRAMA_FRAME_MAX];
    size_t tx_len, tx_index, tx_count;
    uint8_t tx_tid;
    bool tx_busy;
    uint8_t resp[2][BRAMA_FRAME_MAX];
    size_t resp_len[2];
    int resp_n;
    bool status_pending;
    uint8_t status[BRAMA_SNAPSHOT_LEN];
    bool restart_after_tx;
    bool drop_after_tx;       // odmowa naprawy bondu: rozłączenie po wysłaniu HANDSHAKE_REJECT (9.5, 10.4)
    // bond w staging (CANDIDATE, 9.5) — utrwalany wyłącznie w commicie aktora
    brama_bond_t staged;
    bool has_staged;
    // PAKE liczone poza blokadą (zadanie pake_worker): sesja łącza jest w tym czasie zamrożona, a jej kopię przetwarza zadanie
    bool pake_inflight;
    uint32_t pake_epoch;
} brama_link_ctx_t;

/** Rozwiązuje RPA peera IRK-iem z bondów obrazu (gdy kontroler tego nie zrobił). Zwraca true i adres tożsamości, jeśli pasuje. */
bool brama_ble_resolve_identity(const ble_addr_t *ota, ble_addr_t *identity);

/** Czy peer (adres z łącza, także RPA rozwiązywany IRK-iem) ma bond w staging albo w obrazie. */
bool brama_ble_peer_bonded(const ble_addr_t *ota);

extern brama_link_ctx_t g_links[BRAMA_MAX_LINKS];
extern brama_ble_config_t g_cfg;
void brama_ble_lock(void);
void brama_ble_unlock(void);
