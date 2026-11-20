#include <stdlib.h>
#include <string.h>
#include "brama_crypto.h"
#include "brama_err.h"
#include "esp_app_desc.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "host/util/util.h"
#include "internal.h"
#include "nimble/nimble_port.h"
#include "os/os_mbuf.h"
#include "nimble/nimble_port_freertos.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

static const char *TAG = "brama_ble";

brama_link_ctx_t g_links[BRAMA_MAX_LINKS];
brama_ble_config_t g_cfg;

static uint8_t s_own_addr_type;
static uint16_t s_tx_handle;
static uint64_t s_tokens;
// Jedna statyczna wiadomość do aktora dla wszystkich ścieżek pod blokadą BLE (DRAM jest pełny; 720 B × 3 → 720 B).
static brama_actor_msg_t s_amsg;
static esp_timer_handle_t s_tick, s_restart;
static QueueHandle_t s_ota_queue;
static QueueHandle_t s_pake_queue;
static uint32_t s_pake_epoch;
static bool s_pake_busy; // zadanie PAKE ma w toku jedną rundę (pod blokadą BLE)

#ifndef FIRST_LINK_ITVL
#define FIRST_LINK_ITVL 0 // x1,25 ms; 0 = bez żądania zmiany interwału (80 zawiesiło A13, 14.11.2026)
#endif
#define CANDIDATE_GRACE_US (2LL * 1000 * 1000) // nieznany peer poza oknem: rozłączenie ≤ 2 s (8.3)

// UUID 8.2 (little-endian): a8f7000N-6a43-4ff6-9ad8-98f63412b001
#define BRAMA_UUID(n) BLE_UUID128_INIT(0x01, 0xb0, 0x12, 0x34, 0xf6, 0x98, 0xd8, 0x9a, 0xf6, 0x4f, 0x43, 0x6a, n, 0x00, 0xf7, 0xa8)
static const ble_uuid128_t UUID_SVC = BRAMA_UUID(0x01);
static const ble_uuid128_t UUID_INFO = BRAMA_UUID(0x02);
static const ble_uuid128_t UUID_RX = BRAMA_UUID(0x03);
static const ble_uuid128_t UUID_TX = BRAMA_UUID(0x04);
static const ble_uuid16_t UUID_DIS = BLE_UUID16_INIT(0x180A);
static const ble_uuid16_t UUID_FW_REV = BLE_UUID16_INIT(0x2A26);

void brama_ble_lock(void)
{
    if (xSemaphoreTakeRecursive(g_cfg.lock, pdMS_TO_TICKS(2000)) == pdTRUE) return;
    // Zabezpieczenie diagnostyczne: zawieszenie na blokadzie aktora/BLE zostaje w logu z nazwami zadań (czeka/trzyma).
    TaskHandle_t holder = xSemaphoreGetMutexHolder(g_cfg.lock);
    ESP_LOGE(TAG, "blokada BLE zajeta ponad 2 s: czeka '%s', trzyma '%s'", pcTaskGetName(NULL), holder ? pcTaskGetName(holder) : "?");
    xSemaphoreTakeRecursive(g_cfg.lock, portMAX_DELAY);
}
void brama_ble_unlock(void) { xSemaphoreGiveRecursive(g_cfg.lock); }

static int64_t now_us(void) { return esp_timer_get_time(); }

static brama_link_ctx_t *link_by_conn(uint16_t conn)
{
    for (int i = 0; i < BRAMA_MAX_LINKS; i++) if (g_links[i].used && g_links[i].conn == conn) return &g_links[i];
    return NULL;
}

static int link_index(const brama_link_ctx_t *l) { return (int)(l - g_links); }

static void peer7(const brama_link_ctx_t *l, uint8_t out[7])
{
    out[0] = l->peer_id.type;
    memcpy(out + 1, l->peer_id.val, 6);
}

static brama_budget_t link_budget(const brama_link_ctx_t *l)
{
    struct ble_gap_conn_desc d;
    uint32_t itvl_ms = 100;
    uint16_t latency = 0;
    if (ble_gap_conn_find(l->conn, &d) == 0) {
        itvl_ms = (uint32_t)d.conn_itvl * 5 / 4; // jednostki 1,25 ms
        latency = d.conn_latency;
    }
    return brama_budget(l->mtu, itvl_ms ? itvl_ms : 1, latency);
}

// --- Środowisko sesji (odczyt stanu aktora pod blokadą) -----------------------------------------------------------

static bool env_client_key(void *ctx, const uint8_t id[16], uint8_t key[32])
{
    const brama_link_ctx_t *l = ctx;
    // Łącze CANDIDATE: SESSION_BEGIN to naprawa bondu dowolnego ACTIVE (9.5) — dopuszczenie w env_session_admit.
    if (l->candidate) return brama_actor_client_key(g_cfg.actor, id, key);
    uint8_t p[7];
    peer7(l, p);
    // Sesja klienta wyłącznie na łączu jego zatwierdzonego bondu (9.5, 12.1).
    return brama_actor_client_key_for_peer(g_cfg.actor, id, p, key);
}

static bool env_window_open(void *ctx)
{
    (void)ctx;
    return brama_auth_window_is_open(&g_cfg.actor->limits, now_us() / 1000);
}

static bool env_pin_attempt_allowed(void *ctx)
{
    (void)ctx;
    return brama_auth_pin_attempt_allowed(&g_cfg.actor->limits, now_us() / 1000);
}

static uint8_t env_pair_admit(void *ctx, const uint8_t id[16], bool via_pin)
{
    (void)via_pin;
    brama_link_ctx_t *l = ctx;
    brama_actor_t *a = g_cfg.actor;
    if (!env_window_open(ctx)) return BRAMA_REJECT_WINDOW_CLOSED;
    if (brama_image_find(&a->image, id) >= 0) return BRAMA_REJECT_CLIENT_ALREADY_ACTIVE;
    // Ponowna rejestracja na bond dawnego ACTIVE zastępuje ten rekord, chyba że jego reguła jest uzbrojona (9.5).
    for (int i = 0; i < a->image.count; i++) {
        const brama_bond_t *b = &a->image.clients[i].bond;
        if (b->len && b->id_addr_type == l->peer_id.type && memcmp(b->id_addr, l->peer_id.val, 6) == 0) {
            bool owner = a->rule.has_owner && memcmp(a->rule.owner_id, a->image.clients[i].client_id, 16) == 0;
            return owner ? BRAMA_REJECT_REPLACEMENT_BLOCKED : 0;
        }
    }
    return a->image.count >= BRAMA_MAX_CLIENTS ? BRAMA_REJECT_NO_CAPACITY : 0;
}

/** Po poprawnym HMAC SESSION_BEGIN: na łączu CANDIDATE naprawa bondu (9.5), na zatwierdzonym zwykła sesja. */
static uint8_t env_session_admit(void *ctx, const uint8_t id[16], bool *repair)
{
    brama_link_ctx_t *l = ctx;
    *repair = l->candidate;
    if (!l->candidate) return 0;
    if (!env_window_open(ctx)) return BRAMA_REJECT_WINDOW_CLOSED;
    if (!l->has_staged) return BRAMA_REJECT_UNAVAILABLE; // brak nowego bondu do zatwierdzenia
    return brama_actor_repair_blocker(g_cfg.actor, link_index(l), id, &l->staged);
}

static uint8_t env_attempt_blocker(void *ctx)
{
    brama_link_ctx_t *l = ctx;
    uint8_t p[7];
    peer7(l, p);
    if (!g_cfg.actor->control_enabled) return BRAMA_REJECT_UNAVAILABLE;
    return brama_auth_rate_limited(&g_cfg.actor->limits, p, now_us() / 1000) ? BRAMA_REJECT_RATE_LIMITED : 0;
}

static void env_snapshot(void *ctx, const uint8_t id[16], brama_snapshot_t *out)
{
    (void)ctx;
    *out = brama_actor_snapshot_for(g_cfg.actor, id);
}

static void rng_fill(void *ctx, uint8_t *buf, size_t len)
{
    (void)ctx;
    psa_generate_random(buf, len);
}

// --- TX: fragmenty i wskazania (10.2) ------------------------------------------------------------------------------

static void send_fragment(brama_link_ctx_t *l)
{
    uint8_t frag[BRAMA_FRAME_MAX];
    size_t n = 0;
    brama_budget_t b = link_budget(l);
    if (brama_fragment_get(l->tx_frame, l->tx_len, l->tx_tid, b.fragment_data_budget, l->tx_index, frag, &n) != BRAMA_OK) {
        l->tx_len = 0;
        return;
    }
    struct os_mbuf *om = ble_hs_mbuf_from_flat(frag, (uint16_t)n);
    if (om && ble_gatts_indicate_custom(l->conn, s_tx_handle, om) == 0) l->tx_busy = true;
    else l->tx_len = 0; // niepowodzenie: telefon ponowi żądanie (13.4)
}

static void kick_tx(brama_link_ctx_t *l)
{
    if (!l->used || !l->subscribed || l->tx_busy) return;
    if (l->tx_len && l->tx_index < l->tx_count) {
        send_fragment(l);
        return;
    }
    l->tx_len = 0;
    if (l->drop_after_tx && !l->resp_n) {
        ble_gap_terminate(l->conn, BLE_ERR_REM_USER_CONN_TERM);
        return;
    }
    if (l->restart_after_tx) {
        esp_restart(); // OTA_ACK po END potwierdzony (15.5)
    }
    if (l->resp_n) {
        memcpy(l->tx_frame, l->resp[0], l->resp_len[0]);
        l->tx_len = l->resp_len[0];
        if (--l->resp_n) {
            memcpy(l->resp[0], l->resp[1], l->resp_len[1]);
            l->resp_len[0] = l->resp_len[1];
        }
    } else if (l->status_pending && brama_session_ready(&l->session)) {
        // Status pieczętowany dopiero teraz: niewysłane zdarzenia scalone do najnowszego snapshotu (13.3).
        if (brama_session_seal_event(&l->session, BRAMA_MSG_RULE_STATUS, l->status, BRAMA_SNAPSHOT_LEN, l->tx_frame,
                                     &l->tx_len) != BRAMA_OK) {
            l->tx_len = 0;
        }
        l->status_pending = false;
    }
    if (!l->tx_len) return;
    l->tx_tid = (uint8_t)(l->tx_tid % 255 + 1);
    l->tx_index = 0;
    l->tx_count = brama_fragment_count(l->tx_len, link_budget(l).fragment_data_budget);
    send_fragment(l);
}

static void enqueue_resp(brama_link_ctx_t *l, const uint8_t *frame, size_t len)
{
    if (l->resp_n == 2) return; // najwyżej jedna operacja w toku na sesję (13.3); nadmiar odrzucony
    memcpy(l->resp[l->resp_n], frame, len);
    l->resp_len[l->resp_n++] = len;
    kick_tx(l);
}

// --- Wywołania aktora ------------------------------------------------------------------------------------------------

void brama_ble_send(void *ctx, int link, uint64_t token, uint64_t req_seq, uint8_t type, const uint8_t *p, size_t len)
{
    (void)ctx;
    if (link < 0 || link >= BRAMA_MAX_LINKS) return;
    brama_link_ctx_t *l = &g_links[link];
    if (!l->used || l->session_token != token) return; // nieaktualna sesja: bez odpowiedzi pod starym kluczem
    if (req_seq == 0) {
        if (type == BRAMA_MSG_RULE_STATUS && len == BRAMA_SNAPSHOT_LEN) {
            memcpy(l->status, p, len);
            l->status_pending = true;
            kick_tx(l);
        }
        return;
    }
    if (type == BRAMA_MSG_OTA_ACK && len >= 2) {
        // Diagnostyka OTA (15.11.2026): odpowiedź na BEGIN/END/ABORT zawsze, na DATA tylko błąd (transfer to ~1400 ramek).
        uint8_t req = p[0], res = p[1];
        if (req != BRAMA_MSG_OTA_DATA || res != BRAMA_OTA_OK) {
            ESP_LOGI(TAG, "OTA ACK łącze=%d żądanie=0x%02x wynik=%u", link, req, res);
        }
    }
    uint8_t out[BRAMA_FRAME_MAX];
    size_t olen = 0;
    if (brama_session_respond(&l->session, req_seq, type, p, len, out, &olen) == BRAMA_OK) enqueue_resp(l, out, olen);
}

void brama_ble_pair_result(void *ctx, int link, uint64_t token, bool ok)
{
    (void)ctx;
    brama_link_ctx_t *l = &g_links[link];
    if (!l->used || l->session_token != token) return;
    uint8_t out[BRAMA_FRAME_MAX];
    size_t olen = 0;
    if (brama_session_pair_finish(&l->session, ok, out, &olen) == BRAMA_OK) enqueue_resp(l, out, olen);
    if (ok) {
        l->has_staged = false; // bond jest już w obrazie (APPROVED)
        brama_zeroize(&l->staged, sizeof l->staged);
        l->approved = true;
        l->candidate = false;
    }
    brama_ble_refresh_advertising();
}

static void post_actor(brama_actor_msg_t *m, const brama_link_ctx_t *l);

// Usunięcie tożsamości telefonu z resolving list kontrolera (client_management_design.md §4.3). Stary wpis powodował, że
// kontroler rozwiązywał RPA tego samego telefonu do adresu tożsamości, a host używał go w SMP (f6) zamiast adresu
// z powietrza: DHKey check failed (status 1035) przy ponownym parowaniu, aż do restartu sterownika (18.11.2026).
// ble_gap_unpair() nie nadaje się: zwraca BLE_HS_EBUSY przy aktywnej reklamie, a sterownik reklamuje się zawsze.
// Wpis usuwamy więc sami przy zatrzymanej reklamie (HCI nie pozwala na zmianę listy podczas reklamy); bondy zatwierdzone
// żyją w obrazie, więc magazyn stosu nie wymaga sprzątania. Aktywne łącze tego peera nie przeszkadza.
extern int ble_hs_pvcy_remove_entry(uint8_t addr_type, const uint8_t *addr);

static void resolving_list_remove(const brama_bond_t *bond)
{
    ble_gap_adv_stop();
    int rc = ble_hs_pvcy_remove_entry(bond->id_addr_type, bond->id_addr);
    brama_ble_refresh_advertising();
    ESP_LOGI(TAG, "resolving list: usunięto %02x:%02x:%02x:%02x:%02x:%02x typ=%u rc=%d", bond->id_addr[5], bond->id_addr[4],
             bond->id_addr[3], bond->id_addr[2], bond->id_addr[1], bond->id_addr[0], bond->id_addr_type, rc);
}

void brama_ble_client_removed(void *ctx, const brama_bond_t *bond)
{
    (void)ctx;
    resolving_list_remove(bond);
}

void brama_ble_repair_result(void *ctx, int link, uint64_t token, uint8_t code, const brama_bond_t *replaced)
{
    (void)ctx;
    if (replaced) {
        // Inny adres tożsamości: stary peer znika też z resolving list kontrolera — niezależnie od losu łącza naprawy
        // (sprawdzić w etapie 0). Magazyn zatwierdzonych bondów to obraz, więc delete stosu go nie dotyczy.
        resolving_list_remove(replaced);
    }
    brama_link_ctx_t *l = &g_links[link];
    // Łącze zniknęło albo próba się zmieniła (nowy BEGIN zeruje token): zatwierdzony bond zostaje w obrazie, a łącze
    // zachowuje staging — ponowiona próba zatwierdzi ten sam bond jeszcze raz.
    if (!l->used || l->session_token != token) return;
    uint8_t out[BRAMA_FRAME_MAX];
    size_t olen = 0;
    bool sent = brama_session_repair_finish(&l->session, &l->env, code, out, &olen) == BRAMA_OK;
    l->has_staged = false; // po commit bond jest w obrazie (APPROVED); po odmowie staging usunięty (9.5)
    brama_zeroize(&l->staged, sizeof l->staged);
    l->repairing = false;
    if (code) {
        l->drop_after_tx = true;
        if (sent) enqueue_resp(l, out, olen);
        else ble_gap_terminate(l->conn, BLE_ERR_REM_USER_CONN_TERM);
        brama_ble_refresh_advertising();
        return;
    }
    l->approved = true;
    l->candidate = false;
    if (sent) {
        enqueue_resp(l, out, olen);
        brama_actor_msg_t *const m = &s_amsg;
        memset(m, 0, sizeof *m);
        l->session_token = ++s_tokens;
        l->ever_ready = true;
        m->type = BRAMA_AMSG_SESSION_READY;
        m->session_token = l->session_token;
        memcpy(m->client_id, l->session.client_id, 16);
        post_actor(m, l);
    }
    brama_ble_refresh_advertising();
}

void brama_ble_disconnect(void *ctx, int link)
{
    (void)ctx;
    if (g_links[link].used) ble_gap_terminate(g_links[link].conn, BLE_ERR_REM_USER_CONN_TERM);
}

static void restart_cb(void *arg)
{
    (void)arg;
    esp_restart();
}

void brama_ble_ota_restart(void *ctx, int link)
{
    (void)ctx;
    g_links[link].restart_after_tx = true;
    // Restart po potwierdzeniu wskazania OTA_ACK, najpóźniej po 2 s (15.5).
    esp_timer_start_once(s_restart, 2 * 1000 * 1000);
}

bool brama_ble_any_ready(void)
{
    for (int i = 0; i < BRAMA_MAX_LINKS; i++) if (g_links[i].used && brama_session_ready(&g_links[i].session)) return true;
    return false;
}

// --- RX: składanie i sesja -------------------------------------------------------------------------------------------

typedef struct {
    int link;
    uint64_t token, seq;
    uint8_t payload[BRAMA_FRAME_MAX];
    size_t len;
} ota_job_t;

static void post_actor(brama_actor_msg_t *m, const brama_link_ctx_t *l)
{
    m->link = link_index(l);
    m->link_token = l->link_token;
    m->auth_generation = g_cfg.actor->image.auth_generation;
    m->mtu = l->mtu;
    g_cfg.post(m);
}

/** Skutki kroku sesji: limity prób, odpowiedź, zdarzenia dla aktora. Pod blokadą BLE. */
static void finish_frame(brama_link_ctx_t *l, brama_step_t *step, bool committing)
{
    brama_actor_msg_t *const m = &s_amsg;
    // Próba naprawy porzucona w trakcie transakcji (nowy BEGIN, błędny CONFIRM): wynik aktora jest już nieaktualny.
    if (committing && l->session.state != BRAMA_SS_REPAIR_COMMITTING) l->session_token = 0;
    if (step->auth_failure) {
        uint8_t p[7];
        peer7(l, p);
        brama_auth_note_failure(&g_cfg.actor->limits, p, step->pin_failure, now_us() / 1000);
        brama_ble_refresh_advertising(); // piąta porażka PIN-u mogła zamknąć okno
    }
    if (step->reply_len) enqueue_resp(l, step->reply, step->reply_len);
    memset(m, 0, sizeof *m);
    switch (step->event) {
    case BRAMA_EV_SESSION_READY:
        l->session_token = ++s_tokens;
        l->ever_ready = true;
        m->type = BRAMA_AMSG_SESSION_READY;
        m->session_token = l->session_token;
        memcpy(m->client_id, step->client_id, 16);
        post_actor(m, l);
        break;
    case BRAMA_EV_COMMAND:
        if (step->type == BRAMA_MSG_OTA_DATA) {
            // Zapis flash w osobnym zadaniu o niższym priorytecie niż aktor i host NimBLE (15.5).
            static ota_job_t job;
            job.link = link_index(l);
            job.token = l->session_token;
            job.seq = step->seq;
            memcpy(job.payload, step->payload, step->payload_len);
            job.len = step->payload_len;
            xQueueSend(s_ota_queue, &job, 0);
            break;
        }
        m->type = BRAMA_AMSG_COMMAND;
        m->session_token = l->session_token;
        m->cmd_type = step->type;
        memcpy(m->payload, step->payload, step->payload_len);
        m->payload_len = step->payload_len;
        m->seq = step->seq;
        post_actor(m, l);
        break;
    case BRAMA_EV_PAIR_COMMIT:
        l->session_token = ++s_tokens;
        m->type = BRAMA_AMSG_PAIR_COMMIT;
        m->session_token = l->session_token;
        memcpy(m->client_id, step->client_id, 16);
        memcpy(m->k_client, step->k_client, 32);
        if (l->has_staged) m->bond = l->staged;
        brama_zeroize(step->k_client, sizeof step->k_client);
        post_actor(m, l);
        brama_zeroize(m->k_client, sizeof m->k_client);
        brama_zeroize(&m->bond, sizeof m->bond);
        break;
    case BRAMA_EV_REPAIR_COMMIT: {
        l->session_token = ++s_tokens;
        m->type = BRAMA_AMSG_REPAIR_COMMIT;
        m->session_token = l->session_token;
        memcpy(m->client_id, step->client_id, 16);
        if (l->has_staged) m->bond = l->staged;
        post_actor(m, l);
        brama_zeroize(&m->bond, sizeof m->bond);
        break;
    }
    case BRAMA_EV_INVALIDATED:
        // Unieważnienie albo rekey: aktor odrzuca zaległe komendy starej sesji (12.1), radio bez zmian.
        l->session_token = 0;
        m->type = BRAMA_AMSG_SESSION_INVALIDATED;
        post_actor(m, l);
        break;
    default: break;
    }
}


// --- PAKE poza blokadą ------------------------------------------------------------------------------------------------
// Rundy EC-JPAKE (mnożenia P-256 w programie) trwają ok. 2 s; pod wspólną blokadą zatrzymywały aktora (TICK, impuls, przycisk)
// i pozostałe łącza (16.11.2026). Zadanie pake_worker liczy rundę na KOPII sesji łącza bez blokady; wynik wraca pod blokadą
// tylko wtedy, gdy łącze nadal istnieje (epoka), inaczej kopia jest zwalniana. Sesja łącza jest przez ten czas zamrożona.
typedef struct {
    int link;
    uint32_t epoch;
    brama_session_t sess;
    brama_session_env_t env;
    bool (*orig_window_open)(void *ctx);
    uint8_t (*orig_attempt_blocker)(void *ctx);
    uint8_t frame[BRAMA_FRAME_MAX];
    size_t len;
    brama_step_t step;
} pake_job_t;
static pake_job_t *s_pake_job; // zajmowane z heapu na czas rundy (statyczny DRAM jest pełny); NULL = zadanie wolne

// Wywołania zwrotne środowiska czytają stan aktora, więc w zadaniu wołamy je pod blokadą (krótko).
static bool pake_window_open(void *ctx)
{
    brama_ble_lock();
    bool r = s_pake_job->orig_window_open(ctx);
    brama_ble_unlock();
    return r;
}
static uint8_t pake_attempt_blocker(void *ctx)
{
    brama_ble_lock();
    uint8_t r = s_pake_job->orig_attempt_blocker(ctx);
    brama_ble_unlock();
    return r;
}

static void pake_worker(void *arg)
{
    (void)arg;
    for (;;) {
        uint8_t token;
        if (xQueueReceive(s_pake_queue, &token, portMAX_DELAY) != pdTRUE) continue;
        brama_session_on_frame(&s_pake_job->sess, &s_pake_job->env, s_pake_job->frame, s_pake_job->len, &s_pake_job->step);
        brama_ble_lock();
        brama_link_ctx_t *l = &g_links[s_pake_job->link];
        if (l->used && l->pake_inflight && l->pake_epoch == s_pake_job->epoch) {
            memcpy(&l->session, &s_pake_job->sess, sizeof l->session); // kontekst jpake przechodzi do łącza (jedyny właściciel)
            brama_zeroize(&s_pake_job->sess, sizeof(s_pake_job->sess));
            l->pake_inflight = false;
            finish_frame(l, &s_pake_job->step, false);
        } else {
            ESP_LOGI(TAG, "PAKE: łącze %d zniknęło w trakcie rundy, wynik odrzucony", s_pake_job->link);
            brama_session_reset(&s_pake_job->sess); // łącze zniknęło w trakcie rundy: zwalnia jpake i zeruje sekrety
        }
        brama_zeroize(s_pake_job, sizeof *s_pake_job);
        free(s_pake_job);
        s_pake_job = NULL;
        s_pake_busy = false;
        brama_ble_unlock();
    }
}

/** Ramki PAKE_ROUND1/2 oddajemy zadaniu; true = ramka obsłużona (przyjęta albo odrzucona z powodu zajętości). Pod blokadą BLE. */
static bool pake_try_offload(brama_link_ctx_t *l, const uint8_t *frame, size_t len)
{
    brama_frame_t f;
    if (brama_frame_decode(frame, len, &f) != BRAMA_OK) return false;
    if (f.h.type != BRAMA_MSG_PAKE_ROUND1 && f.h.type != BRAMA_MSG_PAKE_ROUND2) return false;
    pake_job_t *job = s_pake_busy || len > BRAMA_FRAME_MAX ? NULL : malloc(sizeof *job);
    if (!job) {
        ESP_LOGW(TAG, "PAKE: zadanie zajęte albo brak pamięci, ramka odrzucona (łącze %d)", link_index(l));
        return true; // klient ponowi; nie liczymy jako nieudanej próby
    }
    s_pake_job = job;
    s_pake_job->link = link_index(l);
    s_pake_job->epoch = ++s_pake_epoch;
    memcpy(&s_pake_job->sess, &l->session, sizeof(s_pake_job->sess));
    s_pake_job->env = l->env;
    s_pake_job->orig_window_open = l->env.window_open;
    s_pake_job->orig_attempt_blocker = l->env.attempt_blocker;
    s_pake_job->env.window_open = pake_window_open;
    s_pake_job->env.attempt_blocker = pake_attempt_blocker;
    memcpy(s_pake_job->frame, frame, len);
    s_pake_job->len = len;
    l->pake_inflight = true; // przed wysłaniem: zadanie może ruszyć, gdy tylko oddamy procesor (wynik i tak czeka na blokadę)
    l->pake_epoch = job->epoch;
    s_pake_busy = true;
    uint8_t token = 1;
    if (xQueueSend(s_pake_queue, &token, 0) != pdTRUE) {
        l->pake_inflight = false;
        s_pake_busy = false;
        brama_zeroize(job, sizeof *job); // kopia płytka (bez jpake w toku): nic do zwolnienia
        free(job);
        s_pake_job = NULL;
    }
    return true;
}

static void on_frame(brama_link_ctx_t *l, const uint8_t *frame, size_t len)
{
    static brama_step_t step;
    if (l->pake_inflight) return; // sesja zamrożona na czas rundy PAKE; klient czeka na odpowiedź i nie wysyła kolejnych ramek
    if (pake_try_offload(l, frame, len)) return;
    bool committing = l->session.state == BRAMA_SS_REPAIR_COMMITTING;
    brama_session_on_frame(&l->session, &l->env, frame, len, &step);
    finish_frame(l, &step, committing);
}

static bool ota_in_progress(void);

static void ota_worker(void *arg)
{
    (void)arg;
    static ota_job_t job;
    for (;;) {
        if (xQueueReceive(s_ota_queue, &job, portMAX_DELAY) != pdTRUE) continue;
        uint32_t offset, next = 0;
        const uint8_t *data;
        size_t len;
        uint8_t ack[BRAMA_OTA_ACK_LEN];
        brama_ble_lock();
        brama_ota_result_t r = BRAMA_OTA_INVALID_ARGUMENT;
        if (g_cfg.actor->ota && brama_ota_data_decode(job.payload, job.len, &offset, &data, &len) == BRAMA_OK) {
            r = brama_ota_data(g_cfg.actor->ota, job.token, offset, data, len, now_us(), &next);
        }
        brama_ota_ack_encode(BRAMA_MSG_OTA_DATA, r, job.seq, next, ack);
        brama_ble_send(NULL, job.link, job.token, job.seq, BRAMA_MSG_OTA_ACK, ack, sizeof ack);
        // Transfer zerwany tu (zły offset, odrzucony obraz, błąd zapisu) wyłącza `active` poza wiadomością aktora, więc actor_task
        // tego nie zobaczy: bez odświeżenia reklama (wyłączona na czas OTA) zostałaby wyłączona do rozłączenia telefonu.
        if (!ota_in_progress()) brama_ble_refresh_advertising();
        brama_ble_unlock();
    }
}

// --- GATT ------------------------------------------------------------------------------------------------------------

static void adopt_if_orphan(uint16_t conn);

static int access_cb(uint16_t conn, uint16_t attr, struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)arg;
    const ble_uuid_t *u = ctxt->chr->uuid;
    // Diagnostyka drugiego łącza (13.11.2026): czy odczyty/zapisy GATT w ogóle docierają, także gdy CONNECT nie został zgłoszony.
    brama_ble_lock();
    adopt_if_orphan(conn); // NimBLE nie zgłosił CONNECT (drugie łącze): pierwsza ramka nie może trafić na link=brak
    brama_ble_unlock();
    ESP_LOGI(TAG, "gatt access conn=%u attr=%u op=%u link=%s", conn, attr, ctxt->op, link_by_conn(conn) ? "tak" : "brak");
    if (ble_uuid_cmp(u, &UUID_INFO.u) == 0) {
        uint8_t info[20] = {0x02};
        memcpy(info + 1, g_cfg.factory->device_id, 16);
        info[17] = 0xFF; // OPEN, CLOSE, autozamykanie, krypto v2, RuleSnapshot, PIN, OTA, okno z telefonu (8.2)
        info[18] = BRAMA_MAX_CLIENTS;
        info[19] = BRAMA_FRAME_MAX / 16;
        return os_mbuf_append(ctxt->om, info, sizeof info) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    if (ble_uuid_cmp(u, &UUID_FW_REV.u) == 0) {
        const char *v = esp_app_get_description()->version;
        return os_mbuf_append(ctxt->om, v, strlen(v)) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    if (ble_uuid_cmp(u, &UUID_RX.u) == 0 && ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        static uint8_t frag[BRAMA_FRAME_MAX];
        uint16_t n = 0;
        if (OS_MBUF_PKTLEN(ctxt->om) > sizeof frag) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        if (ble_hs_mbuf_to_flat(ctxt->om, frag, sizeof frag, &n) != 0) return BLE_ATT_ERR_UNLIKELY;
        brama_ble_lock();
        brama_link_ctx_t *l = link_by_conn(conn);
        if (l && l->encrypted && (l->approved || l->candidate)) {
            brama_budget_t b = link_budget(l);
            const uint8_t *frame;
            size_t flen;
            if (brama_reasm_accept(&l->rx, frag, n, now_us() / 1000, &b, &frame, &flen) == BRAMA_REASM_COMPLETE) {
                on_frame(l, frame, flen);
            }
        }
        brama_ble_unlock();
        return 0;
    }
    return BLE_ATT_ERR_UNLIKELY;
}

static const struct ble_gatt_svc_def s_services[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &UUID_SVC.u,
        .characteristics = (struct ble_gatt_chr_def[]){
            {.uuid = &UUID_INFO.u, .access_cb = access_cb, .flags = BLE_GATT_CHR_F_READ},
            // Write Request z szyfrowaniem; bez Write Without Response i Prepare/Execute (8.2)
            {.uuid = &UUID_RX.u, .access_cb = access_cb, .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_ENC},
            {.uuid = &UUID_TX.u, .access_cb = access_cb, .flags = BLE_GATT_CHR_F_INDICATE, .val_handle = &s_tx_handle},
            {0},
        },
    },
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &UUID_DIS.u,
        .characteristics = (struct ble_gatt_chr_def[]){
            {.uuid = &UUID_FW_REV.u, .access_cb = access_cb, .flags = BLE_GATT_CHR_F_READ},
            {0},
        },
    },
    {0},
};

// --- Reklama i filtr połączeń (8.3) ----------------------------------------------------------------------------------

static int gap_event(struct ble_gap_event *event, void *arg);

/**
 * Reklama łączalna, nieskierowana, BEZ filtra kontrolera, dopóki jest wolny kontekst łącza. Filtr accept list z
 * tożsamościami zatwierdzonych peer (spec 8.3) nie działa na tym kontrolerze: ESP32 (BT 4.2) nie obsługuje LE Set Privacy
 * Mode (0x204E → Unknown HCI Command), a wpis IRK na liście rozwiązywania w trybie sieciowym odrzuca połączenia z adresu
 * tożsamości (potwierdzone testem: poza oknem telefon się nie łączył, bez filtra łączy się i dochodzi do READY). Nieznanego
 * peera odrzuca firmware: poza oknem rozłączenie po ≤ 2 s (tick_cb, on_encrypted), w oknie jeden kontekst CANDIDATE.
 * Lista rozwiązywania z IRK zostaje (ble_hs_misc_restore_irks w on_sync), aby kontroler rozpoznawał RPA telefonów.
 */
static void set_adv_data(void);

/** Transfer OTA w toku (BEGIN przyjęty, END/ABORT jeszcze nie). Pod blokadą aktora/BLE. */
static bool ota_in_progress(void)
{
    return g_cfg.actor && g_cfg.actor->ota && g_cfg.actor->ota->active;
}

void brama_ble_refresh_advertising(void)
{
    brama_ble_lock();
    ble_gap_adv_stop();
    set_adv_data(); // dane reklamy zależą od okna i licznika PIN-u
    int free_slots = 0;
    for (int i = 0; i < BRAMA_MAX_LINKS; i++) {
        if (!g_links[i].used) free_slots++;
    }
    // Podczas transferu OTA brak reklamy (17.11.2026): nowe łącze zawiązane w trakcie strumienia danych OTA, a potem zerwane,
    // powodowało reset hosta NimBLE (BLE_HS_ECONTROLLER) i przerwanie OTA. Reklama wraca po END/ABORT/porzuceniu transferu.
    if (free_slots > 0 && !ota_in_progress()) {
        struct ble_gap_adv_params p = {0};
        p.conn_mode = BLE_GAP_CONN_MODE_UND;
        p.disc_mode = BLE_GAP_DISC_MODE_GEN;
        p.itvl_min = 160; // 100 ms
        p.itvl_max = 320; // 200 ms
        p.filter_policy = BLE_HCI_ADV_FILT_NONE;
        int rc = ble_gap_adv_start(s_own_addr_type, NULL, BLE_HS_FOREVER, &p, gap_event, NULL);
        if (rc != 0) ESP_LOGW(TAG, "adv_start %d", rc);
    }
    brama_ble_unlock();
}

static void set_adv_data(void)
{
    struct ble_hs_adv_fields f = {0};
    f.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    f.uuids128 = &UUID_SVC;
    f.num_uuids128 = 1;
    f.uuids128_is_complete = 1;
    // Okno otwarte: producent 0xFFFF (zarezerwowany do testów) + liczba pozostałych prób PIN-u (0..5), żeby aplikacja nowego
    // telefonu wiedziała przed wpisaniem PIN-u, że rejestracja PIN-em jest niedostępna (QR działa zawsze). Poza oknem pola brak.
    uint8_t mfg[3] = {0xFF, 0xFF, 0};
    const int64_t now_ms = now_us() / 1000; // jeden odczyt czasu: okno nie może wygasnąć między sprawdzeniem a liczbą prób
    if (g_cfg.actor && brama_auth_window_is_open(&g_cfg.actor->limits, now_ms)) {
        mfg[2] = brama_auth_pin_attempts_left(&g_cfg.actor->limits, now_ms);
        f.mfg_data = mfg;
        f.mfg_data_len = sizeof mfg;
    }
    int arc = ble_gap_adv_set_fields(&f);
    if (arc != 0) ESP_LOGW(TAG, "adv_set_fields %d", arc);
    struct ble_hs_adv_fields rsp = {0};
    const char *name = ble_svc_gap_device_name(); // nazwa bez device_id i danych właściciela
    rsp.name = (const uint8_t *)name;
    rsp.name_len = (uint8_t)strlen(name);
    rsp.name_is_complete = 1;
    int rrc = ble_gap_adv_rsp_set_fields(&rsp);
    if (rrc != 0) ESP_LOGW(TAG, "adv_rsp_set_fields %d", rrc);
}

// --- Zdarzenia GAP ---------------------------------------------------------------------------------------------------

// Diagnostyka parowania: adresy, z których NimBLE liczy DHKey Check (f5/f6), w zapisie MSB-first jak w logach Androida.
static void log_addr(const char *what, const ble_addr_t *a)
{
    ESP_LOGI(TAG, "  %s %02x:%02x:%02x:%02x:%02x:%02x typ=%u", what, a->val[5], a->val[4], a->val[3], a->val[2], a->val[1], a->val[0], a->type);
}

// Diagnostyka zasobów hosta (13.11.2026): wolne bloki mbuf i sterta przy zdarzeniach połączenia.
static void log_resources(const char *when)
{
    ESP_LOGI(TAG, "zasoby[%s] mbuf wolne=%d/%d sterta wolna=%u min=%u", when, os_msys_num_free(), os_msys_count(),
             (unsigned)esp_get_free_heap_size(), (unsigned)esp_get_minimum_free_heap_size());
}

static void log_conn_addrs(const char *when, uint16_t conn)
{
    struct ble_gap_conn_desc d;
    if (ble_gap_conn_find(conn, &d) != 0) {
        ESP_LOGI(TAG, "adresy[%s] conn=%u: brak opisu", when, conn);
        return;
    }
    ESP_LOGI(TAG, "adresy[%s] conn=%u rola=%u enc=%d auth=%d bonded=%d keysz=%u", when, conn, d.role, d.sec_state.encrypted,
             d.sec_state.authenticated, d.sec_state.bonded, d.sec_state.key_size);
    ESP_LOGI(TAG, "  parametry itvl=%u (%u.%02u ms) latency=%u timeout=%u (x10 ms)", d.conn_itvl, d.conn_itvl * 5 / 4,
             (d.conn_itvl * 5 % 4) * 25, d.conn_latency, d.supervision_timeout);
    log_addr("our_ota ", &d.our_ota_addr);
    log_addr("our_id  ", &d.our_id_addr);
    log_addr("peer_ota", &d.peer_ota_addr);
    log_addr("peer_id ", &d.peer_id_addr);
}

static void on_connect(uint16_t conn)
{
    if (link_by_conn(conn)) return; // CONNECT mógł już zostać obsłużony albo łącze zaadoptowane (tick_cb)
    for (int i = 0; i < BRAMA_MAX_LINKS; i++) {
        brama_link_ctx_t *l = &g_links[i];
        if (l->used) continue;
        memset(l, 0, sizeof *l);
        l->used = true;
        l->conn = conn;
        l->link_token = ++s_tokens;
        l->mtu = 23;
        l->connected_us = now_us();
        brama_session_init(&l->session);
        brama_reasm_init(&l->rx);
        l->env = (brama_session_env_t){
            .device_id = g_cfg.factory->device_id, .k_setup = g_cfg.factory->k_setup, .pin = g_cfg.factory->pin,
            .rng = {rng_fill, NULL}, .client_key = env_client_key, .pair_admit = env_pair_admit, .session_admit = env_session_admit,
            .window_open = env_pin_attempt_allowed, .attempt_blocker = env_attempt_blocker, .snapshot_for = env_snapshot,
            .ctx = l};
        // Security Request wysyłamy tylko znanemu klientowi (bond w obrazie albo staging). Nieznany peer w oknie rejestracji sam
        // inicjuje parowanie (createBond po odkryciu usług): Android 13 (Bluedroid/MTK), który odebrał Security Request ~40 ms
        // po połączeniu, liczył DHKey Check z innym adresem własnym niż na łączu i parowanie kończyło się DHKEY_CHECK_FAILED
        // (0x40B po stronie NimBLE); log 13.11.2026.
        struct ble_gap_conn_desc d;
        if (ble_gap_conn_find(conn, &d) == 0 && d.sec_state.encrypted) {
            ESP_LOGI(TAG, "łącze już zaszyfrowane: bez Security Request"); // adopcja po szyfrowaniu zainicjowanym przez telefon
        } else if (ble_gap_conn_find(conn, &d) == 0 && brama_ble_peer_bonded(&d.peer_ota_addr)) {
            ESP_LOGI(TAG, "peer ze znanym bondem: Security Request");
            ble_gap_security_initiate(conn);
        } else {
            ESP_LOGI(TAG, "peer bez bondu: bez Security Request, parowanie inicjuje telefon");
        }
        return;
    }
    ble_gap_terminate(conn, BLE_ERR_CONN_LIMIT);
}

static void on_encrypted(brama_link_ctx_t *l)
{
    struct ble_gap_conn_desc d;
    memset(&d, 0, sizeof d); // przy błędzie ble_gap_conn_find log niżej nie może czytać niezainicjowanych bajtów
    if (ble_gap_conn_find(l->conn, &d) != 0 || !d.sec_state.encrypted || d.sec_state.key_size != 16) {
        ESP_LOGW(TAG, "enc: odrzucone (encrypted=%d key_size=%d)", d.sec_state.encrypted, d.sec_state.key_size);
        ble_gap_terminate(l->conn, BLE_ERR_AUTH_FAIL);
        return;
    }
    l->encrypted = true;
    // EKSPERYMENT (14.11.2026): pierwsze łącze dostaje inny interwał niż 39 (48,75 ms), którym telefony zaczynają drugie łącze;
    // hipoteza: kontroler ESP32 nie synchronizuje drugiego łącza o tym samym interwale (nakładanie terminów zdarzeń).
    int used = 0;
    for (int i = 0; i < BRAMA_MAX_LINKS; i++) used += g_links[i].used;
    if (used == 1 && FIRST_LINK_ITVL > 0) {
        struct ble_gap_upd_params up = {.itvl_min = FIRST_LINK_ITVL, .itvl_max = FIRST_LINK_ITVL,
                                        .latency = 0, .supervision_timeout = 500};
        int rc = ble_gap_update_params(l->conn, &up);
        ESP_LOGI(TAG, "żądanie interwału %d (x1,25 ms) dla pierwszego łącza: rc=%d", FIRST_LINK_ITVL, rc);
    }
    if (!brama_ble_resolve_identity(&d.peer_id_addr, &l->peer_id)) l->peer_id = d.peer_id_addr; // RPA → tożsamość z IRK bondu
    // Ocena od nowa przy każdym szyfrowaniu (także ponownym parowaniu na tym samym łączu). Zatwierdzone jest wyłącznie
    // łącze zaszyfrowane bondem z obrazu: świeża dystrybucja kluczy (staging) nigdy, nawet przy znanym adresie
    // tożsamości — inaczej zwykła sesja działałaby na niezatwierdzonych kluczach albo obcy peer podszyłby się pod adres.
    l->approved = false;
    l->candidate = false;
    const brama_image_t *img = &g_cfg.actor->image;
    for (int i = 0; i < img->count && !l->repairing && !l->has_staged; i++) {
        const brama_bond_t *b = &img->clients[i].bond;
        if (b->len && b->id_addr_type == l->peer_id.type && memcmp(b->id_addr, l->peer_id.val, 6) == 0) l->approved = true;
    }
    if (l->approved) return;
    // Nieznany peer i ponowne parowanie znanego (klucze w stagingu, naprawa bondu albo ponowna rejestracja) zajmują
    // wyłącznie jeden kontekst CANDIDATE i tylko w oknie (9.5, 14.3); zatwierdzony bond nie daje tu sesji.
    bool candidate_taken = false;
    for (int i = 0; i < BRAMA_MAX_LINKS; i++) if (&g_links[i] != l && g_links[i].used && g_links[i].candidate) candidate_taken = true;
    if (!candidate_taken && env_window_open(l)) l->candidate = true;
    else ble_gap_terminate(l->conn, BLE_ERR_REM_USER_CONN_TERM);
}

/** Koniec łącza: aktor dostaje DISCONNECT (14.2), kontekst jest zwalniany. Wspólne dla DISCONNECT i dla CONNECT status != 0. */
static void link_closed(uint16_t conn)
{
    brama_link_ctx_t *l = link_by_conn(conn);
    if (!l) return;
    brama_actor_msg_t *const m = &s_amsg;
    memset(m, 0, sizeof *m);
    m->type = BRAMA_AMSG_DISCONNECT;
    post_actor(m, l); // rzeczywista utrata łącza (14.2); aktor sprawdza link_token
    // Trwa runda PAKE na kopii sesji: kontekst jpake należy do zadania pake_worker (zwolni go po nieudanym dopasowaniu epoki).
    if (l->pake_inflight) l->session.jpake_active = false;
    brama_session_reset(&l->session);
    // Niezatwierdzona rejestracja (parowanie przeszło, ale COMMIT nie nastąpił — np. zły PIN): stos zostawia peera w resolving
    // liście kontrolera ze starym IRK, a kolejne parowanie tego telefonu kończyło się DHKey Check Failed do restartu płytki
    // (15.11.2026). Usuwamy go, o ile tożsamość nie należy do klienta z obrazu (ten zostaje w resolving liście).
    bool unpair = false;
    ble_addr_t stale = {0};
    if (l->has_staged) {
        stale.type = l->staged.id_addr_type;
        memcpy(stale.val, l->staged.id_addr, 6);
        unpair = true;
        const brama_image_t *img = &g_cfg.actor->image;
        for (int i = 0; i < img->count; i++) {
            const brama_bond_t *b = &img->clients[i].bond;
            if (b->len && b->id_addr_type == stale.type && memcmp(b->id_addr, stale.val, 6) == 0) unpair = false;
        }
    }
    memset(l, 0, sizeof *l);
    if (unpair) {
        int rc = ble_gap_unpair(&stale); // staging już wyzerowany, więc zwykle ENOENT i resolving lista zostaje
        int rc2 = ble_hs_pvcy_remove_entry(stale.type, stale.val);
        ESP_LOGI(TAG, "niezatwierdzona rejestracja: usunięcie peera %02x:%02x:%02x:%02x:%02x:%02x typ=%u unpair=%d resolving=%d", stale.val[5], stale.val[4],
                 stale.val[3], stale.val[2], stale.val[1], stale.val[0], stale.type, rc, rc2);
    }
}

/** Uchwyt łącza ze zdarzenia GAP (do logu diagnostycznego); 0xFFFF, gdy zdarzenie go nie niesie. */
static uint16_t gap_event_conn(const struct ble_gap_event *e)
{
    switch (e->type) {
    case BLE_GAP_EVENT_CONNECT: return e->connect.conn_handle;
    case BLE_GAP_EVENT_DISCONNECT: return e->disconnect.conn.conn_handle;
    case BLE_GAP_EVENT_ENC_CHANGE: return e->enc_change.conn_handle;
    case BLE_GAP_EVENT_NOTIFY_TX: return e->notify_tx.conn_handle;
    case BLE_GAP_EVENT_SUBSCRIBE: return e->subscribe.conn_handle;
    case BLE_GAP_EVENT_MTU: return e->mtu.conn_handle;
    case BLE_GAP_EVENT_REPEAT_PAIRING: return e->repeat_pairing.conn_handle;
    case BLE_GAP_EVENT_LINK_ESTAB: return e->link_estab.conn_handle;
    case BLE_GAP_EVENT_PARING_COMPLETE: return e->pairing_complete.conn_handle;
    case BLE_GAP_EVENT_DATA_LEN_CHG: return e->data_len_chg.conn_handle;
    default: return 0xFFFF;
    }
}

static const char *gap_event_name(uint8_t t)
{
    switch (t) {
    case BLE_GAP_EVENT_CONNECT: return "CONNECT";
    case BLE_GAP_EVENT_DISCONNECT: return "DISCONNECT";
    case BLE_GAP_EVENT_CONN_UPDATE: return "CONN_UPDATE";
    case BLE_GAP_EVENT_CONN_UPDATE_REQ: return "CONN_UPDATE_REQ";
    case BLE_GAP_EVENT_L2CAP_UPDATE_REQ: return "L2CAP_UPDATE_REQ";
    case BLE_GAP_EVENT_TERM_FAILURE: return "TERM_FAILURE";
    case BLE_GAP_EVENT_ADV_COMPLETE: return "ADV_COMPLETE";
    case BLE_GAP_EVENT_ENC_CHANGE: return "ENC_CHANGE";
    case BLE_GAP_EVENT_NOTIFY_TX: return "NOTIFY_TX";
    case BLE_GAP_EVENT_SUBSCRIBE: return "SUBSCRIBE";
    case BLE_GAP_EVENT_MTU: return "MTU";
    case BLE_GAP_EVENT_IDENTITY_RESOLVED: return "IDENTITY_RESOLVED";
    case BLE_GAP_EVENT_REPEAT_PAIRING: return "REPEAT_PAIRING";
    case BLE_GAP_EVENT_PHY_UPDATE_COMPLETE: return "PHY_UPDATE";
    case BLE_GAP_EVENT_DATA_LEN_CHG: return "DATA_LEN_CHG";
    case BLE_GAP_EVENT_LINK_ESTAB: return "LINK_ESTAB";
    default: return "?";
    }
}

static int gap_event(struct ble_gap_event *event, void *arg)
{
    (void)arg;
    // Diagnostyka dwóch łączy naraz (13.11.2026): każde zdarzenie GAP dostarczone aplikacji (poza zwykłym CONNECT/DISCONNECT
    // logowanym niżej), żeby zobaczyć, co NimBLE zgłasza dla drugiego łącza.
    ESP_LOGI(TAG, "gap event %s (%u) conn=%d", gap_event_name(event->type), event->type, (int16_t)gap_event_conn(event));
    brama_ble_lock();
    brama_link_ctx_t *l;
    int rc = 0;
    {
        uint16_t c = gap_event_conn(event);
        if (c != 0xFFFF && event->type != BLE_GAP_EVENT_CONNECT && event->type != BLE_GAP_EVENT_DISCONNECT
            && event->type != BLE_GAP_EVENT_LINK_ESTAB) {
            adopt_if_orphan(c); // pierwsze zdarzenie łącza bez CONNECT (ENC_CHANGE, SUBSCRIBE, MTU…) uruchamia adopcję od razu
        }
    }
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        ESP_LOGI(TAG, "connect status=%d conn=%d", event->connect.status, event->connect.conn_handle);
        log_resources(event->connect.status == 0 ? "connect" : "connect_fail");
        if (event->connect.status == 0) {
            log_conn_addrs("connect", event->connect.conn_handle);
            if (ota_in_progress()) {
                // Wyścig: połączenie przyjęte tuż przed zatrzymaniem reklamy przy BEGIN. Zerwanie od razu, zanim ruszy ruch na nowym łączu.
                ESP_LOGW(TAG, "OTA w toku: odrzucenie nowego łącza conn=%d", event->connect.conn_handle);
                ble_gap_terminate(event->connect.conn_handle, BLE_ERR_REM_USER_CONN_TERM);
            } else {
                on_connect(event->connect.conn_handle);
            }
        } else {
            // status=1 (BLE_HS_EAGAIN): link-layer połączenie zerwane, zanim host zgłosił zwykłe CONNECT (nie ma wtedy DISCONNECT);
            // opis może jeszcze istnieć — pokazuje, kto i z jakimi parametrami próbował się połączyć (diagnostyka 13.11.2026).
            log_conn_addrs("connect_fail", event->connect.conn_handle);
            // Łącze zaadoptowane przez tick_cb (bez CONNECT) NimBLE kończy tym zdarzeniem zamiast DISCONNECT.
            link_closed(event->connect.conn_handle);
        }
        brama_ble_refresh_advertising();
        break;
    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "disconnect reason=0x%x conn=%d", event->disconnect.reason, event->disconnect.conn.conn_handle);
        link_closed(event->disconnect.conn.conn_handle);
        brama_ble_refresh_advertising();
        break;
    case BLE_GAP_EVENT_ENC_CHANGE:
        l = link_by_conn(event->enc_change.conn_handle);
        if (event->enc_change.status != 0) {
            ESP_LOGW(TAG, "enc_change status=%d", event->enc_change.status);
            log_conn_addrs("enc_fail", event->enc_change.conn_handle);
        }
        if (l && event->enc_change.status == 0) on_encrypted(l);
        else if (l) ble_gap_terminate(l->conn, BLE_ERR_AUTH_FAIL);
        break;
    case BLE_GAP_EVENT_CONN_UPDATE:
        // Diagnostyka wielu łączy naraz (13.11.2026): jak telefon ustawia interwał po odkryciu usług.
        log_conn_addrs("conn_update", event->conn_update.conn_handle);
        break;
    case BLE_GAP_EVENT_REPEAT_PAIRING:
        // Poza oknem bez rebondingu; w oknie nowe klucze trafiają do staging, stare APPROVED zostają (9.5).
        // RETRY tylko po ukryciu starego bondu przed stosem (hidden_by_repair), inaczej NimBLE zapętla się.
        l = link_by_conn(event->repeat_pairing.conn_handle);
        if (l && env_window_open(NULL) && l->repeat_pairs < 3) {
            struct ble_gap_conn_desc d;
            if (ble_gap_conn_find(l->conn, &d) == 0 && !brama_ble_resolve_identity(&d.peer_id_addr, &l->peer_id)) l->peer_id = d.peer_id_addr;
            // Nieukończona wcześniejsza próba na tym łączu zostawiła klucze w staging; stos widzi je jako bond peer
            // (staging ma pierwszeństwo w store), więc bez ich usunięcia RETRY zapętlałby się w NimBLE.
            if (l->has_staged) {
                l->has_staged = false;
                brama_zeroize(&l->staged, sizeof l->staged);
            }
            l->repairing = true;
            l->repeat_pairs++;
            rc = BLE_GAP_REPEAT_PAIRING_RETRY;
        } else {
            ESP_LOGW(TAG, "repeat_pairing: IGNORE (okno=%d, powtórzenia=%u)", env_window_open(NULL), l ? l->repeat_pairs : 0);
            rc = BLE_GAP_REPEAT_PAIRING_IGNORE;
        }
        break;
    case BLE_GAP_EVENT_SUBSCRIBE:
        l = link_by_conn(event->subscribe.conn_handle);
        if (l && event->subscribe.attr_handle == s_tx_handle) {
            l->subscribed = event->subscribe.cur_indicate; // CCCD wyłącznie w RAM (15.2)
            kick_tx(l);
        }
        break;
    case BLE_GAP_EVENT_MTU:
        l = link_by_conn(event->mtu.conn_handle);
        if (l) l->mtu = event->mtu.value;
        break;
    case BLE_GAP_EVENT_NOTIFY_TX:
        l = link_by_conn(event->notify_tx.conn_handle);
        if (l && event->notify_tx.indication && event->notify_tx.status != 0) {
            l->tx_busy = false;
            if (event->notify_tx.status == BLE_HS_EDONE) l->tx_index++;
            else l->tx_len = 0; // wskazanie niepotwierdzone: ramka porzucona, telefon ponowi żądanie
            kick_tx(l);
        }
        break;
    case BLE_GAP_EVENT_ADV_COMPLETE:
        brama_ble_refresh_advertising();
        break;
    default: break;
    }
    brama_ble_unlock();
    return rc;
}

// --- Zegar łączy i aktora --------------------------------------------------------------------------------------------

/**
 * Adopcja natychmiast przy pierwszej oznace życia łącza bez kontekstu (zapis/odczyt GATT, zdarzenie GAP): bez niej pierwsze ramki
 * telefonu (np. zapis RX przed adopcją z ticka) trafiałyby na `link=brak` i przepadały. Pod blokadą.
 */
static void adopt_if_orphan(uint16_t conn)
{
    struct ble_gap_conn_desc d;
    if (link_by_conn(conn) || ble_gap_conn_find(conn, &d) != 0 || d.role != BLE_GAP_ROLE_SLAVE) return;
    ESP_LOGW(TAG, "łącze conn=%u bez zdarzenia CONNECT, ale aktywne: adopcja", conn);
    on_connect(conn);
    brama_link_ctx_t *l = link_by_conn(conn);
    if (l && d.sec_state.encrypted) on_encrypted(l);
}

#define ORPHAN_HANDLES 16
#define ORPHAN_ADOPT_US (1500LL * 1000) // zwykłe CONNECT przychodzi po ~80 ms; dłuższy brak = utknięty łańcuch NimBLE
static int64_t s_orphan_since_us[ORPHAN_HANDLES];

/**
 * Obejście (13.11.2026): NimBLE (ESP-IDF 6.0.1) zgłasza CONNECT dla roli slave dopiero po `Read Remote Version` i `LE Read Remote
 * Features`. Z drugim telefonem (A14) przy działającym pierwszym (A13) ten łańcuch nie kończy się, a łącze żyje i obsługuje
 * SMP/ATT — firmware nie wiedział o nim (brak on_connect, Security Request, limitów). Łącze hosta bez kontekstu przez
 * ORPHAN_ADOPT_US adoptujemy tak, jakby CONNECT przyszedł. Wołane pod blokadą z tick_cb.
 */
// Wewnętrzne API NimBLE (jest w bibliotece, brak deklaracji w publicznym nagłówku — jak `ble_hs_pvcy_resolve_with_irk` w store.c).
// Użyte, bo `ble_gap_conn_find` dla nieistniejącego uchwytu loguje INFO („connection not found”) przy każdym wywołaniu.
extern void ble_gap_conn_foreach_handle(ble_gap_conn_foreach_handle_fn *cb, void *arg);

static int collect_conn_handle(uint16_t handle, void *arg)
{
    if (handle < 32) *(uint32_t *)arg |= 1u << handle; // pod blokadą hosta: tylko zapis maski, bez wywołań NimBLE
    return 0; // 0 = kontynuuj iterację
}

static void adopt_orphan_links(int64_t t)
{
    uint32_t existing = 0;
    ble_gap_conn_foreach_handle(collect_conn_handle, &existing);
    for (uint16_t h = 0; h < ORPHAN_HANDLES; h++) {
        struct ble_gap_conn_desc d;
        if (!(existing & (1u << h)) || link_by_conn(h) || ble_gap_conn_find(h, &d) != 0 || d.role != BLE_GAP_ROLE_SLAVE) {
            s_orphan_since_us[h] = 0;
            continue;
        }
        if (s_orphan_since_us[h] == 0) {
            s_orphan_since_us[h] = t;
            continue;
        }
        if (t - s_orphan_since_us[h] < ORPHAN_ADOPT_US) continue;
        s_orphan_since_us[h] = 0;
        ESP_LOGW(TAG, "łącze conn=%u bez zdarzenia CONNECT od >%lld ms: adopcja", h, (long long)(ORPHAN_ADOPT_US / 1000));
        on_connect(h);
        brama_link_ctx_t *l = link_by_conn(h);
        if (l && d.sec_state.encrypted) on_encrypted(l); // zaszyfrowane przed adopcją: ENC_CHANGE już minął
    }
}

static void tick_cb(void *arg)
{
    (void)arg;
    // Zadanie esp_timer nie może czekać na blokadę (PAKE, esp_ota_end): przy zajętej pomijamy ten cykl.
    if (xSemaphoreTakeRecursive(g_cfg.lock, 0) != pdTRUE) return;
    int64_t t = now_us();
    adopt_orphan_links(t);
    for (int i = 0; i < BRAMA_MAX_LINKS; i++) {
        brama_link_ctx_t *l = &g_links[i];
        if (!l->used) continue;
        brama_reasm_expire(&l->rx, t / 1000);
        int64_t age = t - l->connected_us;
        // Łącze bez uwierzytelnienia: nieznany peer ≤ 2 s poza oknem, zatwierdzony do T_attempt od połączenia (10.4).
        // Przy otwartym oknie łącze jeszcze niezaszyfrowane to trwający bonding nowego telefonu (systemowe pytanie o parowanie
        // czeka na użytkownika): nie obcinać go po 2 s, obowiązuje T_attempt poniżej. Po zaszyfrowaniu on_encrypted
        // sam rozstrzyga (candidate albo rozłączenie).
        bool bonding_in_window = !l->encrypted && brama_auth_window_is_open(&g_cfg.actor->limits, t / 1000);
        if (!l->ever_ready && !l->approved && !l->candidate && age > CANDIDATE_GRACE_US && !bonding_in_window) {
            ble_gap_terminate(l->conn, BLE_ERR_REM_USER_CONN_TERM);
        } else if (!l->ever_ready && age > (int64_t)link_budget(l).attempt_ms * 1000) {
            ble_gap_terminate(l->conn, BLE_ERR_REM_USER_CONN_TERM);
        }
    }
    brama_ble_unlock();
}

static int store_read(int t, const union ble_store_key *k, union ble_store_value *v);
static int store_write(int t, const union ble_store_value *v);
static int store_delete(int t, const union ble_store_key *k);

// Wewnętrzna funkcja NimBLE (ble_hs_priv.h), brak publicznego nagłówka.
extern int ble_hs_misc_restore_irks(void);

static void install_store(void)
{
    ble_hs_cfg.store_read_cb = store_read;
    ble_hs_cfg.store_write_cb = store_write;
    ble_hs_cfg.store_delete_cb = store_delete;
    ble_hs_cfg.store_status_cb = NULL;
}

static void on_sync(void)
{
    // Przy CONFIG_BT_NIMBLE_STATIC_TO_DYNAMIC=y start hosta (ble_hs_startup → ble_hs_pvcy_set_default_irk) wywołuje
    // ble_store_config_init(), które PODMIENIA store_*_cb na domyślny magazyn NimBLE. Wcześniejsze przypisanie w
    // brama_ble_start ginęło, klucze nie trafiały do staging (bond pusty przy PAIR_COMMIT). Instalujemy magazyn po starcie
    // hosta, przy każdej synchronizacji (także po resecie hosta).
    install_store();
    ble_hs_misc_restore_irks(); // wpisy IRK zatwierdzonych peer do listy rozwiązywania kontrolera (spec 8.3), już z naszego magazynu
    ble_hs_util_ensure_addr(0);
    ble_hs_id_infer_auto(0, &s_own_addr_type); // stały adres między restartami (8.3)
    brama_ble_refresh_advertising(); // ustawia też dane reklamy (set_adv_data) pod blokadą BLE
}

static void on_reset(int reason)
{
    ESP_LOGW(TAG, "reset hosta NimBLE: %d", reason);
}

static void host_task(void *arg)
{
    (void)arg;
    nimble_port_run();
    nimble_port_freertos_deinit();
}

static int store_read(int t, const union ble_store_key *k, union ble_store_value *v) { return brama_ble_store_read(t, k, v); }
static int store_write(int t, const union ble_store_value *v) { return brama_ble_store_write(t, v); }
static int store_delete(int t, const union ble_store_key *k) { return brama_ble_store_delete(t, k); }

esp_err_t brama_ble_start(const brama_ble_config_t *cfg)
{
    g_cfg = *cfg;
    memset(g_links, 0, sizeof g_links);
    esp_err_t e = nimble_port_init();
    if (e != ESP_OK) return e;
#if CONFIG_BT_NIMBLE_LOG_LEVEL_DEBUG
    esp_log_level_set("NimBLE", ESP_LOG_DEBUG); // obraz diagnostyczny: ślad HCI hosta (polecenia, zdarzenia, ACL) — 13.11.2026
#endif
    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.reset_cb = on_reset;
    install_store(); // dodatkowo w on_sync (start hosta nadpisuje magazyn)
    // LE Secure Connections, bonding, bez MITM (Just Works; uwierzytelnienie w warstwie aplikacyjnej, 9.1)
    ble_hs_cfg.sm_io_cap = BLE_SM_IO_CAP_NO_IO;
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_mitm = 0;
    ble_hs_cfg.sm_sc = 1;
    // Bez trybu „tylko SC”: ten tryb wymaga poziomu 4 (uwierzytelniony klucz) dla KAŻDEJ charakterystyki z WRITE_ENC, a Just Works
    // daje klucz nieuwierzytelniony, więc NimBLE odpowiadał ATT 0x05 na zapis RX przed access_cb (potwierdzone śladem ACL).
    ble_hs_cfg.sm_sc_only = 0;
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ESP_LOGI(TAG, "SM: io_cap=%u bonding=%u mitm=%u sc=%u sc_only=%u", ble_hs_cfg.sm_io_cap, ble_hs_cfg.sm_bonding, ble_hs_cfg.sm_mitm,
             ble_hs_cfg.sm_sc, ble_hs_cfg.sm_sc_only);
    ble_svc_gap_init();
    ble_svc_gatt_init();
    if (ble_gatts_count_cfg(s_services) != 0 || ble_gatts_add_svcs(s_services) != 0) return ESP_FAIL;
    s_pake_queue = xQueueCreate(1, sizeof(uint8_t));
    if (!s_pake_queue || xTaskCreate(pake_worker, "pake_worker", 10240, NULL, 2, NULL) != pdPASS) return ESP_ERR_NO_MEM;
    s_ota_queue = xQueueCreate(2, sizeof(ota_job_t));
    if (!s_ota_queue || xTaskCreate(ota_worker, "ota_worker", 6144, NULL, 2, NULL) != pdPASS) return ESP_ERR_NO_MEM;
    esp_timer_create_args_t tick = {.callback = tick_cb, .name = "ble_tick"};
    esp_timer_create_args_t restart = {.callback = restart_cb, .name = "ota_restart"};
    if (esp_timer_create(&tick, &s_tick) != ESP_OK || esp_timer_create(&restart, &s_restart) != ESP_OK) return ESP_FAIL;
    esp_timer_start_periodic(s_tick, 100 * 1000);
    nimble_port_freertos_init(host_task);
    return ESP_OK;
}
