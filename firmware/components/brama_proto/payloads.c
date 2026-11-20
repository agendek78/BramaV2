#include <string.h>
#include "brama_err.h"
#include "brama_proto.h"

void brama_snapshot_encode(const brama_snapshot_t *s, uint8_t out[BRAMA_SNAPSHOT_LEN])
{
    brama_put_le(out, s->revision, 8);
    out[8] = s->phase;
    out[9] = s->owner;
    out[10] = s->reason;
    brama_put_le(out + 11, s->remaining_ms, 4);
}

bool brama_snapshot_valid(const brama_snapshot_t *s)
{
    bool armed = s->owner != BRAMA_OWNER_NONE;
    bool has_deadline = s->phase == BRAMA_PHASE_WAITING || s->phase == BRAMA_PHASE_BOOT_WAIT;
    if (s->owner > BRAMA_OWNER_OTHER) return false;
    if (!has_deadline && s->remaining_ms != 0) return false;
    switch (s->phase) {
    case BRAMA_PHASE_DISARMED: return !armed && (s->reason == BRAMA_REASON_NONE || s->reason == BRAMA_REASON_REBOOT);
    case BRAMA_PHASE_MONITORING: return armed && s->reason == BRAMA_REASON_NONE;
    case BRAMA_PHASE_WAITING: return armed && s->reason == BRAMA_REASON_LINK_LOST;
    case BRAMA_PHASE_BOOT_WAIT: return armed && s->reason == BRAMA_REASON_REBOOT;
    case BRAMA_PHASE_BLOCKED: return armed && s->reason == BRAMA_REASON_INTERLOCK;
    case BRAMA_PHASE_FAULT:
        return armed && (s->reason == BRAMA_REASON_IO_FAULT || s->reason == BRAMA_REASON_BUSY_TIMEOUT
                         || s->reason == BRAMA_REASON_STORAGE_FAILURE);
    default: return false;
    }
}

void brama_ack_encode(uint8_t request_type, uint8_t result, uint64_t request_seq, const brama_snapshot_t *s,
                      uint8_t out[BRAMA_ACK_LEN])
{
    out[0] = request_type;
    out[1] = result;
    brama_put_le(out + 2, request_seq, 8);
    brama_snapshot_encode(s, out + 10);
}

void brama_ota_ack_encode(uint8_t request_type, uint8_t result, uint64_t request_seq, uint32_t next_offset,
                          uint8_t out[BRAMA_OTA_ACK_LEN])
{
    out[0] = request_type;
    out[1] = result;
    brama_put_le(out + 2, request_seq, 8);
    brama_put_le(out + 10, next_offset, 4);
}

int brama_ota_begin_decode(const uint8_t *p, size_t len, uint32_t *image_size, const uint8_t **sha)
{
    if (len != BRAMA_OTA_BEGIN_LEN) return BRAMA_ERR_FORMAT;
    *image_size = (uint32_t)brama_get_le(p, 4);
    *sha = p + 4;
    return BRAMA_OK;
}

int brama_ota_data_decode(const uint8_t *p, size_t len, uint32_t *offset, const uint8_t **data, size_t *data_len)
{
    if (len < 5 || len > 4 + BRAMA_OTA_MAX_DATA) return BRAMA_ERR_FORMAT;
    *offset = (uint32_t)brama_get_le(p, 4);
    *data = p + 4;
    *data_len = len - 4;
    return BRAMA_OK;
}
