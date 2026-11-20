#include <string.h>
#include "brama_ota.h"
#include "brama_proto.h"

#define IMAGE_MAGIC 0xE9
#define CHIP_ID_ESP32 0x0000
#define APP_DESC_OFFSET 32
#define APP_DESC_MAGIC 0xABCD5432u

void brama_ota_init(brama_ota_t *o, const brama_ota_port_t *port)
{
    memset(o, 0, sizeof *o);
    o->port = *port;
}

static void clear(brama_ota_t *o)
{
    if (o->active) brama_sha256_abort(&o->hash);
    o->active = false;
    o->session_token = 0;
    o->size = 0;
    o->next_offset = 0;
}

void brama_ota_abort(brama_ota_t *o)
{
    if (!o->active) return;
    o->port.abort(o->port.ctx);
    clear(o);
}

bool brama_ota_owned_by(const brama_ota_t *o, uint64_t session_token)
{
    return o->active && o->session_token == session_token;
}

brama_ota_result_t brama_ota_check_header(const uint8_t *first, size_t len, uint32_t running_secure_version)
{
    if (len < BRAMA_OTA_HEADER_LEN || first[0] != IMAGE_MAGIC) return BRAMA_OTA_IMAGE_REJECTED;
    if (brama_get_le(first + 12, 2) != CHIP_ID_ESP32) return BRAMA_OTA_IMAGE_REJECTED;
    const uint8_t *desc = first + APP_DESC_OFFSET;
    if (brama_get_le(desc, 4) != APP_DESC_MAGIC) return BRAMA_OTA_IMAGE_REJECTED;
    // project_name[32] w esp_app_desc_t (offset 48): dokładnie "brama" zakończone NUL.
    char project[sizeof BRAMA_OTA_PROJECT];
    memcpy(project, desc + 48, sizeof project);
    if (memcmp(project, BRAMA_OTA_PROJECT, sizeof project) != 0) return BRAMA_OTA_IMAGE_REJECTED;
    uint32_t secure_version = (uint32_t)brama_get_le(desc + 4, 4);
    return secure_version < running_secure_version ? BRAMA_OTA_DOWNGRADE : BRAMA_OTA_OK;
}

brama_ota_result_t brama_ota_begin(brama_ota_t *o, uint64_t session_token, uint16_t mtu, uint32_t size,
                                   const uint8_t sha[32], int64_t now_us)
{
    if (o->active && o->session_token != session_token) return BRAMA_OTA_BUSY;
    if (mtu < BRAMA_OTA_MIN_MTU) return BRAMA_OTA_LINK_TOO_SMALL;
    if (size == 0 || size > BRAMA_OTA_MAX_IMAGE) return BRAMA_OTA_TOO_LARGE;
    brama_ota_abort(o); // nowy BEGIN tej samej sesji zaczyna od offsetu 0 (bez wznawiania)
    if (o->port.begin(o->port.ctx, size) != ESP_OK) return BRAMA_OTA_STORAGE_FAILURE;
    if (brama_sha256_start(&o->hash) != 0) {
        o->port.abort(o->port.ctx);
        return BRAMA_OTA_STORAGE_FAILURE;
    }
    o->active = true;
    o->session_token = session_token;
    o->size = size;
    o->next_offset = 0;
    memcpy(o->sha, sha, 32);
    o->last_activity_us = now_us;
    return BRAMA_OTA_OK;
}

static brama_ota_result_t fail(brama_ota_t *o, brama_ota_result_t r)
{
    brama_ota_abort(o);
    return r;
}

brama_ota_result_t brama_ota_data(brama_ota_t *o, uint64_t session_token, uint32_t offset, const uint8_t *data,
                                  size_t len, int64_t now_us, uint32_t *next_offset)
{
    *next_offset = 0;
    // Transfer innej sesji nie jest przerywany cudzą ramką.
    if (!brama_ota_owned_by(o, session_token)) return BRAMA_OTA_NOT_IN_PROGRESS;
    if (len == 0 || len > BRAMA_OTA_MAX_DATA) return fail(o, BRAMA_OTA_INVALID_ARGUMENT);
    if (offset != o->next_offset) return fail(o, BRAMA_OTA_BAD_OFFSET);
    if ((uint64_t)offset + len > o->size) return fail(o, BRAMA_OTA_IMAGE_REJECTED);
    if (offset == 0) {
        // Nagłówek i opis obrazu sprawdzone przed zapisem pierwszego bajtu (obcy projekt, downgrade).
        brama_ota_result_t r = brama_ota_check_header(data, len, o->port.running_secure_version(o->port.ctx));
        if (r != BRAMA_OTA_OK) return fail(o, r);
    }
    if (o->port.write(o->port.ctx, data, len) != ESP_OK) return fail(o, BRAMA_OTA_STORAGE_FAILURE);
    brama_sha256_update(&o->hash, data, len);
    o->next_offset += (uint32_t)len;
    o->last_activity_us = now_us;
    *next_offset = o->next_offset;
    return BRAMA_OTA_OK;
}

brama_ota_result_t brama_ota_end(brama_ota_t *o, uint64_t session_token)
{
    if (!brama_ota_owned_by(o, session_token)) return BRAMA_OTA_NOT_IN_PROGRESS;
    if (o->next_offset != o->size) return fail(o, BRAMA_OTA_IMAGE_REJECTED);
    uint8_t sha[32];
    brama_sha256_finish(&o->hash, sha);
    bool sha_ok = memcmp(sha, o->sha, 32) == 0;
    if (!sha_ok) {
        o->port.abort(o->port.ctx);
        o->active = false; // hash już zamknięty przez finish
        clear(o);
        return BRAMA_OTA_IMAGE_REJECTED;
    }
    o->active = false;
    // esp_ota_end weryfikuje obraz i podpis; po błędzie uchwyt jest już zwolniony (bez abort).
    if (o->port.end(o->port.ctx) != ESP_OK) {
        clear(o);
        return BRAMA_OTA_IMAGE_REJECTED;
    }
    brama_ota_result_t r = o->port.set_boot(o->port.ctx) == ESP_OK ? BRAMA_OTA_OK : BRAMA_OTA_STORAGE_FAILURE;
    clear(o);
    return r;
}

void brama_ota_touch(brama_ota_t *o, uint64_t session_token, int64_t now_us)
{
    if (brama_ota_owned_by(o, session_token)) o->last_activity_us = now_us;
}

bool brama_ota_tick(brama_ota_t *o, int64_t now_us)
{
    if (!o->active || now_us - o->last_activity_us <= BRAMA_OTA_IDLE_US) return false;
    brama_ota_abort(o);
    return true;
}

brama_first_boot_t brama_ota_first_boot(bool pending_verify, bool service_mode, bool selftest_ok)
{
    if (!pending_verify) return BRAMA_FB_NORMAL;
    if (service_mode) return BRAMA_FB_LEAVE_PENDING; // tryb serwisowy nie potwierdza nowego obrazu
    return selftest_ok ? BRAMA_FB_CONFIRM_THEN_MIGRATE : BRAMA_FB_ROLLBACK;
}
