#include "brama_hw.h"
#include "esp_app_desc.h"
#include "esp_ota_ops.h"

static esp_ota_handle_t s_handle;
static const esp_partition_t *s_target;

static esp_err_t ota_begin(void *ctx, uint32_t size)
{
    (void)ctx;
    (void)size;
    s_target = esp_ota_get_next_update_partition(NULL);
    if (!s_target || size > s_target->size) return ESP_ERR_INVALID_SIZE;
    // Kasowanie sektorów na bieżąco — bez kilkusekundowego kasowania całego slotu przy BEGIN (15.5).
    return esp_ota_begin(s_target, OTA_WITH_SEQUENTIAL_WRITES, &s_handle);
}

static esp_err_t ota_write(void *ctx, const uint8_t *data, size_t len)
{
    (void)ctx;
    return esp_ota_write(s_handle, data, len);
}

static esp_err_t ota_end(void *ctx)
{
    (void)ctx;
    // Weryfikuje obraz i podpis ECDSA (CONFIG_SECURE_SIGNED_ON_UPDATE_NO_SECURE_BOOT); zwalnia uchwyt także przy błędzie.
    esp_err_t e = esp_ota_end(s_handle);
    s_handle = 0;
    return e;
}

static esp_err_t ota_set_boot(void *ctx)
{
    (void)ctx;
    return esp_ota_set_boot_partition(s_target);
}

static void ota_abort(void *ctx)
{
    (void)ctx;
    if (s_handle) esp_ota_abort(s_handle);
    s_handle = 0;
}

static uint32_t running_secure_version(void *ctx)
{
    (void)ctx;
    return esp_app_get_description()->secure_version;
}

brama_ota_port_t brama_hw_ota(void)
{
    return (brama_ota_port_t){ota_begin, ota_write, ota_end, ota_set_boot, ota_abort, running_secure_version, NULL};
}

bool brama_hw_ota_pending_verify(void)
{
    esp_ota_img_states_t state;
    return esp_ota_get_state_partition(esp_ota_get_running_partition(), &state) == ESP_OK
           && state == ESP_OTA_IMG_PENDING_VERIFY;
}

void brama_hw_ota_mark_valid(void)
{
    esp_ota_mark_app_valid_cancel_rollback();
}

void brama_hw_ota_rollback(void)
{
    esp_ota_mark_app_invalid_rollback_and_reboot();
}
