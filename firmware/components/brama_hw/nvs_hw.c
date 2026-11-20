#include "brama_hw.h"
#include "nvs.h"
#include "nvs_flash.h"

static nvs_handle_t s_nvs;
static uint32_t s_writes;

esp_err_t brama_hw_nvs_init(void)
{
    esp_err_t e = nvs_flash_init();
    if (e != ESP_OK) return e; // bez kasowania partycji: uszkodzenie prowadzi do trybu błędu lokalnego (15.2)
    return nvs_open("brama", NVS_READWRITE, &s_nvs);
}

static esp_err_t nvs_read(void *ctx, const char *key, void *buf, size_t *len)
{
    (void)ctx;
    esp_err_t e = nvs_get_blob(s_nvs, key, buf, len);
    return e == ESP_ERR_NVS_NOT_FOUND ? ESP_ERR_NOT_FOUND : e;
}

static esp_err_t nvs_write(void *ctx, const char *key, const void *buf, size_t len)
{
    (void)ctx;
    s_writes++;
    esp_err_t e = nvs_set_blob(s_nvs, key, buf, len);
    return e == ESP_OK ? nvs_commit(s_nvs) : e;
}

static esp_err_t nvs_erase(void *ctx, const char *key)
{
    (void)ctx;
    s_writes++;
    esp_err_t e = nvs_erase_key(s_nvs, key);
    return e == ESP_OK ? nvs_commit(s_nvs) : e;
}

brama_nvs_t brama_hw_nvs(void) { return (brama_nvs_t){nvs_read, nvs_write, nvs_erase, NULL}; }

uint32_t brama_hw_nvs_writes(void) { return s_writes; }
