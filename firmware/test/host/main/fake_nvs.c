#include <string.h>
#include "fake_nvs.h"

static int find(fake_nvs_t *f, const char *key)
{
    for (int i = 0; i < 16; i++) if (f->entries[i].used && strcmp(f->entries[i].key, key) == 0) return i;
    return -1;
}

static esp_err_t nvs_read(void *ctx, const char *key, void *buf, size_t *len)
{
    fake_nvs_t *f = ctx;
    int i = find(f, key);
    if (i < 0) return ESP_ERR_NOT_FOUND;
    if (*len < f->entries[i].len) return ESP_ERR_INVALID_SIZE;
    memcpy(buf, f->entries[i].value, f->entries[i].len);
    *len = f->entries[i].len;
    if (f->corrupt_readback && *len) ((uint8_t *)buf)[*len - 1] ^= 1;
    return ESP_OK;
}

static esp_err_t nvs_write(void *ctx, const char *key, const void *buf, size_t len)
{
    fake_nvs_t *f = ctx;
    f->writes++;
    if (f->fail_next_write) {
        f->fail_next_write = false;
        return ESP_FAIL;
    }
    int i = find(f, key);
    if (i < 0) {
        for (i = 0; i < 16 && f->entries[i].used; i++) {}
        if (i == 16 || len > sizeof f->entries[0].value) return ESP_ERR_NO_MEM;
        strncpy(f->entries[i].key, key, sizeof f->entries[i].key - 1);
        f->entries[i].used = true;
    }
    memcpy(f->entries[i].value, buf, len);
    f->entries[i].len = len;
    if (f->torn_next_write) {
        f->torn_next_write = false;
        f->entries[i].value[len / 2] ^= 0xff;
        return ESP_FAIL;
    }
    return ESP_OK;
}

static esp_err_t nvs_erase(void *ctx, const char *key)
{
    fake_nvs_t *f = ctx;
    f->writes++;
    int i = find(f, key);
    if (i >= 0) f->entries[i].used = false;
    return ESP_OK;
}

void fake_nvs_init(fake_nvs_t *f, brama_nvs_t *port)
{
    memset(f, 0, sizeof *f);
    *port = (brama_nvs_t){nvs_read, nvs_write, nvs_erase, f};
}

void fake_nvs_flip(fake_nvs_t *f, const char *key, size_t offset)
{
    int i = find(f, key);
    if (i >= 0) f->entries[i].value[offset] ^= 1;
}
