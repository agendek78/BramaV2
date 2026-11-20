#include <stdio.h>
#include <string.h>
#include "brama_boot.h"
#include "brama_err.h"

brama_boot_decision_t brama_boot_decide(bool button_held, int factory_rc, brama_image_status_t img, int cfg_rc)
{
    if (button_held) return (brama_boot_decision_t){BRAMA_BOOT_SERVICE, false};
    if (factory_rc == BRAMA_ERR_STORAGE) return (brama_boot_decision_t){BRAMA_BOOT_PROVISIONING, false};
    if (factory_rc != BRAMA_OK || img != BRAMA_IMG_OK) return (brama_boot_decision_t){BRAMA_BOOT_LOCAL_ERROR, true};
    if (cfg_rc != BRAMA_OK) return (brama_boot_decision_t){BRAMA_BOOT_LOCAL_ERROR, false};
    return (brama_boot_decision_t){BRAMA_BOOT_NORMAL, false};
}

int brama_qr_text(const uint8_t device_id[16], const uint8_t k_setup[32], const uint8_t pin[6], char *out, size_t cap)
{
    static const char HEX[] = "0123456789abcdef";
    static const char B64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    char id[33], k[44], p[7];
    for (int i = 0; i < 16; i++) {
        id[2 * i] = HEX[device_id[i] >> 4];
        id[2 * i + 1] = HEX[device_id[i] & 15];
    }
    id[32] = 0;
    size_t n = 0;
    for (int i = 0; i < 32; i += 3) {
        uint32_t v = (uint32_t)k_setup[i] << 16 | (i + 1 < 32 ? (uint32_t)k_setup[i + 1] << 8 : 0)
                     | (i + 2 < 32 ? k_setup[i + 2] : 0);
        int chars = i + 2 < 32 ? 4 : (i + 1 < 32 ? 3 : 2); // bez paddingu
        for (int c = 0; c < chars; c++) k[n++] = B64[(v >> (18 - 6 * c)) & 63];
    }
    k[n] = 0;
    memcpy(p, pin, 6);
    p[6] = 0;
    int len = snprintf(out, cap, "brama://provision?v=2&id=%s&k=%s&p=%s", id, k, p);
    return len > 0 && (size_t)len < cap ? BRAMA_OK : BRAMA_ERR_ARG;
}

void brama_pin_generate(const brama_rng_t *rng, uint8_t pin[6])
{
    uint32_t v;
    do {
        rng->fill(rng->ctx, (uint8_t *)&v, sizeof v);
    } while (v >= 4294000000u); // największa wielokrotność 10^6 nie większa niż 2^32
    v %= 1000000u;
    for (int i = 5; i >= 0; i--) {
        pin[i] = (uint8_t)('0' + v % 10);
        v /= 10;
    }
}

void brama_factory_generate(const brama_rng_t *rng, brama_factory_t *out)
{
    memset(out, 0, sizeof *out);
    rng->fill(rng->ctx, out->device_id, sizeof out->device_id);
    rng->fill(rng->ctx, out->k_setup, sizeof out->k_setup);
    brama_pin_generate(rng, out->pin);
}

bool brama_boot_migration_allowed(brama_first_boot_t fb)
{
    return fb == BRAMA_FB_NORMAL || fb == BRAMA_FB_CONFIRM_THEN_MIGRATE;
}
