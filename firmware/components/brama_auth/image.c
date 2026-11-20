#include <stdlib.h>
#include <string.h>
#include "brama_auth.h"
#include "brama_crypto.h"
#include "brama_err.h"
#include "brama_proto.h"

#define REC_LEN (16 + 32 + 1 + 6 + 1 + BRAMA_BOND_DATA_MAX + 1 + BRAMA_CLIENT_NAME_MAX)
#define HDR_LEN (4 + 2 + 8 + 8 + 1)
#define IMAGE_LEN (HDR_LEN + BRAMA_MAX_CLIENTS * REC_LEN + 32)

static const char *SLOT_KEYS[2] = {"img_a", "img_b"};
static const uint8_t IMAGE_MAGIC[4] = {'B', 'R', 'A', 'I'};

bool brama_client_name_valid(const uint8_t *n, size_t len)
{
    if (len > BRAMA_CLIENT_NAME_MAX) return false;
    for (size_t i = 0; i < len;) {
        uint8_t b = n[i];
        size_t extra;
        uint32_t cp, min;
        if (b < 0x80) { extra = 0; cp = b; min = 0; }
        else if ((b & 0xE0) == 0xC0) { extra = 1; cp = b & 0x1F; min = 0x80; }
        else if ((b & 0xF0) == 0xE0) { extra = 2; cp = b & 0x0F; min = 0x800; }
        else if ((b & 0xF8) == 0xF0) { extra = 3; cp = b & 0x07; min = 0x10000; }
        else return false;
        if (i + extra >= len) return false; // ucięty znak
        for (size_t k = 1; k <= extra; k++) {
            if ((n[i + k] & 0xC0) != 0x80) return false;
            cp = (cp << 6) | (n[i + k] & 0x3F);
        }
        if (cp < min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return false;
        if (cp < 0x20 || (cp >= 0x7F && cp <= 0x9F)) return false;
        i += extra + 1;
    }
    return true;
}

void brama_image_init_empty(brama_image_t *img)
{
    memset(img, 0, sizeof *img);
    img->schema = BRAMA_IMAGE_SCHEMA;
    img->image_generation = 1;
    img->auth_generation = 1;
    img->slot = -1;
}

static void encode(const brama_image_t *img, uint8_t out[IMAGE_LEN])
{
    memset(out, 0, IMAGE_LEN);
    memcpy(out, IMAGE_MAGIC, 4);
    brama_put_le(out + 4, img->schema, 2);
    brama_put_le(out + 6, img->image_generation, 8);
    brama_put_le(out + 14, img->auth_generation, 8);
    out[22] = img->count;
    for (int i = 0; i < img->count; i++) {
        uint8_t *r = out + HDR_LEN + i * REC_LEN;
        const brama_client_rec_t *c = &img->clients[i];
        memcpy(r, c->client_id, 16);
        memcpy(r + 16, c->k_client, 32);
        r[48] = c->bond.id_addr_type;
        memcpy(r + 49, c->bond.id_addr, 6);
        r[55] = c->bond.len;
        memcpy(r + 56, c->bond.data, c->bond.len);
        r[56 + BRAMA_BOND_DATA_MAX] = c->name_len;
        memcpy(r + 57 + BRAMA_BOND_DATA_MAX, c->name, BRAMA_CLIENT_NAME_MAX);
    }
    brama_sha256(out, IMAGE_LEN - 32, out + IMAGE_LEN - 32);
}

/** Wczytuje slot; 1 = poprawny, 0 = brak/uszkodzony. Schemat nowszy zwracany w img->schema. */
// Bufory na stercie, nie statyczne: statyczny DRAM jest zapełniony w ok. 99% (client_management_design.md §4).
static int read_slot(brama_nvs_t *nvs, int slot, uint8_t *buf, brama_image_t *img, bool *present)
{
    size_t len = IMAGE_LEN;
    *present = false;
    esp_err_t e = nvs->read(nvs->ctx, SLOT_KEYS[slot], buf, &len);
    if (e == ESP_ERR_NOT_FOUND) return 0;
    *present = true;
    if (e != ESP_OK || len != IMAGE_LEN || memcmp(buf, IMAGE_MAGIC, 4) != 0) return 0;
    uint8_t sha[32];
    brama_sha256(buf, IMAGE_LEN - 32, sha);
    if (!brama_ct_equal(sha, buf + IMAGE_LEN - 32, 32)) return 0;
    memset(img, 0, sizeof *img);
    img->schema = (uint16_t)brama_get_le(buf + 4, 2);
    img->image_generation = brama_get_le(buf + 6, 8);
    img->auth_generation = brama_get_le(buf + 14, 8);
    img->count = buf[22];
    img->slot = (int8_t)slot;
    if (img->schema > BRAMA_IMAGE_SCHEMA) return 1; // treści nowszego schematu nie interpretujemy
    if (img->count > BRAMA_MAX_CLIENTS || img->image_generation == 0 || img->auth_generation == 0) return 0;
    for (int i = 0; i < img->count; i++) {
        const uint8_t *r = buf + HDR_LEN + i * REC_LEN;
        brama_client_rec_t *c = &img->clients[i];
        memcpy(c->client_id, r, 16);
        memcpy(c->k_client, r + 16, 32);
        c->bond.id_addr_type = r[48];
        memcpy(c->bond.id_addr, r + 49, 6);
        c->bond.len = r[55];
        if (c->bond.len > BRAMA_BOND_DATA_MAX) return 0;
        memcpy(c->bond.data, r + 56, c->bond.len);
        c->name_len = r[56 + BRAMA_BOND_DATA_MAX];
        memcpy(c->name, r + 57 + BRAMA_BOND_DATA_MAX, BRAMA_CLIENT_NAME_MAX);
        if (!brama_client_name_valid(c->name, c->name_len)) return 0;
        for (size_t k = c->name_len; k < BRAMA_CLIENT_NAME_MAX; k++) if (c->name[k] != 0) return 0;
    }
    return 1;
}

brama_image_status_t brama_image_load(brama_nvs_t *nvs, brama_image_t *out)
{
    brama_image_t *slots = malloc(2 * sizeof *slots);
    uint8_t *buf = malloc(IMAGE_LEN);
    if (!slots || !buf) { // brak sterty przy starcie: nie da się ocenić obrazu, tryb błędu (14.5 pkt 5)
        free(slots);
        free(buf);
        return BRAMA_IMG_CORRUPT;
    }
    bool present[2];
    int ok[2];
    for (int s = 0; s < 2; s++) ok[s] = read_slot(nvs, s, buf, &slots[s], &present[s]);
    brama_image_status_t st = BRAMA_IMG_OK;
    if (!present[0] && !present[1]) st = BRAMA_IMG_MISSING;
    else if (!ok[0] && !ok[1]) st = BRAMA_IMG_CORRUPT;
    // Nowszy, nieznany schemat (downgrade): błąd bez modyfikacji danych (15.4).
    else if ((ok[0] && slots[0].schema > BRAMA_IMAGE_SCHEMA) || (ok[1] && slots[1].schema > BRAMA_IMAGE_SCHEMA)) st = BRAMA_IMG_NEWER_SCHEMA;
    else {
        int best = !ok[0] ? 1 : !ok[1] ? 0 : (slots[1].image_generation > slots[0].image_generation ? 1 : 0);
        *out = slots[best];
    }
    brama_zeroize(slots, 2 * sizeof *slots);
    brama_zeroize(buf, IMAGE_LEN);
    free(slots);
    free(buf);
    return st;
}

int brama_image_commit(brama_nvs_t *nvs, brama_image_t *img)
{
    int target = img->slot == 0 ? 1 : 0;
    for (int i = 0; i < img->count; i++)
        if (!brama_client_name_valid(img->clients[i].name, img->clients[i].name_len)) return BRAMA_ERR_ARG;
    uint8_t *buf = malloc(2 * IMAGE_LEN), *back = buf ? buf + IMAGE_LEN : NULL;
    if (!buf) return BRAMA_ERR_STORAGE;
    encode(img, buf);
    int rc = BRAMA_ERR_STORAGE;
    if (nvs->write(nvs->ctx, SLOT_KEYS[target], buf, IMAGE_LEN) == ESP_OK) {
        size_t len = IMAGE_LEN;
        // Commit potwierdzony dopiero po weryfikacji odczytem nowego slotu (15.2).
        if (nvs->read(nvs->ctx, SLOT_KEYS[target], back, &len) == ESP_OK && len == IMAGE_LEN
            && memcmp(buf, back, IMAGE_LEN) == 0) {
            img->slot = (int8_t)target;
            rc = BRAMA_OK;
        }
    }
    brama_zeroize(buf, 2 * IMAGE_LEN);
    free(buf);
    return rc;
}

int brama_image_next(const brama_image_t *cur, brama_image_t *next)
{
    if (cur->image_generation == UINT64_MAX) return BRAMA_ERR_NO_SPACE;
    *next = *cur;
    next->image_generation++;
    return BRAMA_OK;
}

int brama_image_reset(const brama_image_t *cur, brama_image_t *next)
{
    if (cur->image_generation == UINT64_MAX || cur->auth_generation == UINT64_MAX) return BRAMA_ERR_NO_SPACE;
    brama_image_init_empty(next);
    next->image_generation = cur->image_generation + 1;
    next->auth_generation = cur->auth_generation + 1;
    next->slot = cur->slot;
    return BRAMA_OK;
}

int brama_image_find(const brama_image_t *img, const uint8_t client_id[16])
{
    for (int i = 0; i < img->count; i++) if (memcmp(img->clients[i].client_id, client_id, 16) == 0) return i;
    return -1;
}

int brama_image_put_client(brama_image_t *img, const brama_client_rec_t *c)
{
    int i = brama_image_find(img, c->client_id);
    if (i < 0) {
        if (img->count >= BRAMA_MAX_CLIENTS) return BRAMA_ERR_NO_SPACE;
        i = img->count++;
    }
    img->clients[i] = *c;
    return BRAMA_OK;
}

int brama_image_remove_client(brama_image_t *img, int index)
{
    if (index < 0 || index >= img->count) return BRAMA_ERR_ARG;
    for (int i = index; i < img->count - 1; i++) img->clients[i] = img->clients[i + 1];
    img->count--;
    brama_zeroize(&img->clients[img->count], sizeof img->clients[0]);
    return BRAMA_OK;
}
