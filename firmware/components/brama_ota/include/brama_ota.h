#pragma once
// Transfer obrazu firmware przez BLE (15.5): automat BEGIN/DATA/END/ABORT, wstępna kontrola nagłówka obrazu,
// SHA-256 przyrostowo, porzucenie po 30 s bez danych; decyzja pierwszego startu nowego obrazu. Reguła DISARMED
// i tokeny sesji sprawdza aktor (BEGIN/END/ABORT); DATA woła zadanie zapisu flash. Wywołania z różnych zadań
// wymagają wspólnego mutexu po stronie wołającego.
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "brama_crypto.h"
#include "brama_ports.h"
#include "brama_proto.h"

#ifdef __cplusplus
extern "C" {
#endif

#define BRAMA_OTA_MAX_IMAGE 0x1E0000u
#define BRAMA_OTA_MIN_MTU 247
#define BRAMA_OTA_IDLE_US (30LL * 1000 * 1000)
#define BRAMA_OTA_HEADER_LEN 288 // esp_image_header_t (24) + segment (8) + esp_app_desc_t (256)
#define BRAMA_OTA_PROJECT "brama"

typedef struct {
    brama_ota_port_t port;
    bool active;
    uint64_t session_token;
    uint32_t size;
    uint32_t next_offset;
    uint8_t sha[32];
    brama_sha256_ctx_t hash;
    int64_t last_activity_us;
} brama_ota_t;

void brama_ota_init(brama_ota_t *o, const brama_ota_port_t *port);
brama_ota_result_t brama_ota_begin(brama_ota_t *o, uint64_t session_token, uint16_t mtu, uint32_t size,
                                   const uint8_t sha[32], int64_t now_us);
brama_ota_result_t brama_ota_data(brama_ota_t *o, uint64_t session_token, uint32_t offset, const uint8_t *data,
                                  size_t len, int64_t now_us, uint32_t *next_offset);
/** OK: obraz zweryfikowany i ustawiony do startu — wołający wysyła OTA_ACK i restartuje. */
brama_ota_result_t brama_ota_end(brama_ota_t *o, uint64_t session_token);
/** Porzuca transfer (ABORT, reset, utrata sesji); nie zmienia aktywnego slotu. */
void brama_ota_abort(brama_ota_t *o);
/**
 * Odrzucony END (OTA_RULE_ACTIVE) tej sesji odnawia licznik bezczynności: rozbrojenie reguły może trwać dłużej
 * niż 30 s od ostatniego DATA, a END ma dać się ponowić (15.5, A77). Cudza sesja nie odnawia licznika.
 */
void brama_ota_touch(brama_ota_t *o, uint64_t session_token, int64_t now_us);
/** Porzuca transfer bez danych przez 30 s (licząc od DATA albo odrzuconego END); true, gdy porzucono. */
bool brama_ota_tick(brama_ota_t *o, int64_t now_us);
bool brama_ota_owned_by(const brama_ota_t *o, uint64_t session_token);
/** Nagłówek obrazu ESP32 i esp_app_desc_t (≥ 288 B): magic, układ, projekt, numer bezpieczeństwa. */
brama_ota_result_t brama_ota_check_header(const uint8_t *first, size_t len, uint32_t running_secure_version);

typedef enum { BRAMA_FB_NORMAL, BRAMA_FB_CONFIRM_THEN_MIGRATE, BRAMA_FB_ROLLBACK, BRAMA_FB_LEAVE_PENDING } brama_first_boot_t;
/** Pierwszy start obrazu w stanie PENDING_VERIFY: autotest → potwierdzenie przed migracją albo powrót. */
brama_first_boot_t brama_ota_first_boot(bool pending_verify, bool service_mode, bool selftest_ok);

#ifdef __cplusplus
}
#endif
