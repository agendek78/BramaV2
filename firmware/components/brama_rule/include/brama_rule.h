#pragma once
// Automat reguły autozamykania (14.1–14.4) i kopia w pamięci RTC (14.5). Czysta logika bez wyjść: kontrole
// interlocku, BUSY i IO oraz sam impuls należą do aktora (brama_actor), który woła te funkcje w jednej operacji.
// Czas: monotoniczny zegar w µs; licznik RTC tylko do przeniesienia terminu przez restart.
#include <stdbool.h>
#include <stdint.h>
#include "brama_proto.h"

#ifdef __cplusplus
extern "C" {
#endif

#define BRAMA_WAIT_US (30LL * 1000 * 1000)
#define BRAMA_BUSY_WAIT_US (2LL * 1000 * 1000)
#define BRAMA_RTC_MAGIC 0x52554C45u // "RULE"
#define BRAMA_RTC_VERSION 1

/** Rodzaj zaplanowanej próby CLOSE po upływie deadline_us. */
typedef enum {
    BRAMA_CLOSE_NONE = 0,
    BRAMA_CLOSE_AUTO,       // WAITING/BOOT_WAIT (14.1)
    BRAMA_CLOSE_RESET,      // jedna próba po nieudanym resecie, reguła zostaje FAULT/STORAGE_FAILURE (9.6)
    BRAMA_CLOSE_ERROR_MODE, // jedna próba w trybie błędu obrazu NVS, potem DISARMED (14.5 pkt 5)
} brama_close_kind_t;

typedef enum { BRAMA_CLOSE_STARTED, BRAMA_CLOSE_INTERLOCK, BRAMA_CLOSE_BUSY_TIMEOUT, BRAMA_CLOSE_IO_FAIL } brama_close_outcome_t;

typedef struct {
    uint8_t phase;              // brama_rule_phase_t
    uint8_t reason;             // brama_rule_reason_t
    bool has_owner;
    uint8_t owner_id[16];
    bool owner_link_up;         // właściciel ma teraz łącze uwierzytelnione w tej instancji
    int64_t deadline_us;        // termin zaplanowanej próby CLOSE (0 = brak)
    brama_close_kind_t close_kind;
    bool reboot_warning;        // DISARMED/REBOOT do pierwszej przyjętej komendy (12.2)
    uint64_t revision;          // od 1 przy boot
    uint64_t auth_generation;
    bool rtc_frozen;            // kopii RTC nie wolno nadpisywać (błąd konfiguracji lokalnej, 14.5 pkt 5)
} brama_rule_t;

/** Kopia w RTC (14.5); pola dokładnie jak w specyfikacji, CRC32 całości. */
typedef struct __attribute__((packed)) {
    uint32_t magic;
    uint16_t version;
    uint8_t state;              // 0 DISARMED, 1 ARMED, 2 FAULT
    uint8_t armed_phase;        // MONITORING / WAITING (także BOOT_WAIT) / BLOCKED
    uint8_t fault_reason;
    uint8_t reboot_warning;
    uint8_t owner_id[16];
    int64_t deadline_rtc_us;
    uint64_t auth_generation;
    uint32_t crc;
} brama_rtc_copy_t;

/** Wynik sprawdzenia uprawnień do OPEN (przed zmianą reguły). */
bool brama_rule_open_allowed(const brama_rule_t *r, const uint8_t client_id[16]);

/** Zmiany po przyjęciu komendy (RAM; aktor zapisuje kopię RTC). Zwracają true, gdy zmieniła się faza/właściciel/reason. */
bool brama_rule_apply_open(brama_rule_t *r, const uint8_t client_id[16], bool auto_close);
bool brama_rule_apply_close(brama_rule_t *r);
/** IO_FAULT po zmianie reguły (13.3); `before` = reguła sprzed komendy. */
bool brama_rule_io_fault_after(brama_rule_t *r, const brama_rule_t *before, uint8_t request_type, bool auto_close);

bool brama_rule_on_owner_disconnected(brama_rule_t *r, const uint8_t client_id[16], int64_t now_us);
/** Świeży SESSION_CONFIRM klienta (14.3). */
bool brama_rule_on_authenticated(brama_rule_t *r, const uint8_t client_id[16], int64_t now_us);
bool brama_rule_on_interlock_cleared(brama_rule_t *r, int64_t now_us);
/** Rodzaj próby CLOSE, której termin minął (BRAMA_CLOSE_NONE, gdy brak). */
brama_close_kind_t brama_rule_due(const brama_rule_t *r, int64_t now_us);
bool brama_rule_close_attempt_done(brama_rule_t *r, brama_close_outcome_t outcome);
/** Nieudany reset przy regule (9.6): FAULT/STORAGE_FAILURE i jedna próba CLOSE po 30 s, jeśli była uzbrojona. */
bool brama_rule_reset_failed(brama_rule_t *r, int64_t now_us);
/** Skuteczny reset, reprovisioning, zastąpienie rekordu właściciela: DISARMED bez impulsu. */
bool brama_rule_disarm(brama_rule_t *r, uint64_t auth_generation);

brama_snapshot_t brama_rule_snapshot_for(const brama_rule_t *r, const uint8_t *receiver_id, int64_t now_us);

void brama_rule_to_rtc(const brama_rule_t *r, int64_t now_us, int64_t rtc_now_us, brama_rtc_copy_t *out);

typedef struct {
    bool cold_start;            // ESP_RST_POWERON
    bool image_ok;              // poprawny obraz NVS
    bool allow_error_close;     // false przy błędzie konfiguracji lokalnej (brak bezpiecznego czasu impulsu)
    uint64_t auth_generation;
    bool (*owner_active)(void *ctx, const uint8_t client_id[16]);
    void *ctx;
    int64_t now_us, rtc_now_us;
} brama_rule_boot_env_t;

/** Reguła po starcie (14.5) z kopii RTC (`copy` może być NULL). */
void brama_rule_boot(brama_rule_t *r, const brama_rtc_copy_t *copy, const brama_rule_boot_env_t *env);

uint32_t brama_crc32(const void *data, size_t len);

#ifdef __cplusplus
}
#endif
