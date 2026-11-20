#include <string.h>
#include "brama_auth.h"
#include "brama_crypto.h"
#include "brama_err.h"
#include "brama_proto.h"

#define FACTORY_LEN (4 + 16 + 32 + 6 + 1 + 32)
#define LOCALCFG_LEN (4 + 2 + 1 + 2 + 32)

/** Blob z magic i SHA-256 na końcu; BRAMA_ERR_STORAGE: brak, BRAMA_ERR_FORMAT: uszkodzony. */
static int load_blob(brama_nvs_t *nvs, const char *key, const char magic[4], uint8_t *buf, size_t len)
{
    size_t got = len;
    esp_err_t e = nvs->read(nvs->ctx, key, buf, &got);
    if (e == ESP_ERR_NOT_FOUND) return BRAMA_ERR_STORAGE;
    if (e != ESP_OK || got != len || memcmp(buf, magic, 4) != 0) return BRAMA_ERR_FORMAT;
    uint8_t sha[32];
    brama_sha256(buf, len - 32, sha);
    return brama_ct_equal(sha, buf + len - 32, 32) ? BRAMA_OK : BRAMA_ERR_FORMAT;
}

static int save_blob(brama_nvs_t *nvs, const char *key, uint8_t *buf, size_t len)
{
    brama_sha256(buf, len - 32, buf + len - 32);
    return nvs->write(nvs->ctx, key, buf, len) == ESP_OK ? BRAMA_OK : BRAMA_ERR_STORAGE;
}

static bool pin_valid(const uint8_t pin[6])
{
    for (int i = 0; i < 6; i++) if (pin[i] < '0' || pin[i] > '9') return false;
    return true;
}

int brama_factory_load(brama_nvs_t *nvs, brama_factory_t *out)
{
    uint8_t buf[FACTORY_LEN];
    int rc = load_blob(nvs, "factory", "BRAF", buf, sizeof buf);
    if (rc == BRAMA_OK) {
        memcpy(out->device_id, buf + 4, 16);
        memcpy(out->k_setup, buf + 20, 32);
        memcpy(out->pin, buf + 52, 6);
        out->provisioning_locked = buf[58] != 0;
        if (!pin_valid(out->pin)) rc = BRAMA_ERR_FORMAT;
    }
    brama_zeroize(buf, sizeof buf);
    return rc;
}

int brama_factory_save(brama_nvs_t *nvs, const brama_factory_t *f)
{
    if (!pin_valid(f->pin)) return BRAMA_ERR_ARG;
    uint8_t buf[FACTORY_LEN];
    memcpy(buf, "BRAF", 4);
    memcpy(buf + 4, f->device_id, 16);
    memcpy(buf + 20, f->k_setup, 32);
    memcpy(buf + 52, f->pin, 6);
    buf[58] = f->provisioning_locked;
    int rc = save_blob(nvs, "factory", buf, sizeof buf);
    brama_zeroize(buf, sizeof buf);
    return rc;
}

bool brama_localcfg_valid(const brama_localcfg_t *c)
{
    return c->pulse_ms >= 100 && c->pulse_ms <= 1000 && c->interlock_filter_ms >= 50 && c->interlock_filter_ms <= 1000;
}

int brama_localcfg_load(brama_nvs_t *nvs, brama_localcfg_t *out)
{
    uint8_t buf[LOCALCFG_LEN];
    int rc = load_blob(nvs, "localcfg", "BRAL", buf, sizeof buf);
    if (rc != BRAMA_OK) return rc;
    out->pulse_ms = (uint16_t)brama_get_le(buf + 4, 2);
    out->interlock_enabled = buf[6] != 0;
    out->interlock_filter_ms = (uint16_t)brama_get_le(buf + 7, 2);
    // Wartość spoza zakresu nie jest zastępowana domyślną: tryb błędu lokalnego (15.4).
    return brama_localcfg_valid(out) ? BRAMA_OK : BRAMA_ERR_FORMAT;
}

int brama_localcfg_save(brama_nvs_t *nvs, const brama_localcfg_t *c)
{
    if (!brama_localcfg_valid(c)) return BRAMA_ERR_ARG;
    uint8_t buf[LOCALCFG_LEN];
    memcpy(buf, "BRAL", 4);
    brama_put_le(buf + 4, c->pulse_ms, 2);
    buf[6] = c->interlock_enabled;
    brama_put_le(buf + 7, c->interlock_filter_ms, 2);
    return save_blob(nvs, "localcfg", buf, sizeof buf);
}
