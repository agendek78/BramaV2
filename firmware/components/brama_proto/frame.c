#include <string.h>
#include "brama_err.h"
#include "brama_proto.h"

void brama_put_le(uint8_t *out, uint64_t v, size_t n)
{
    for (size_t i = 0; i < n; i++) out[i] = (uint8_t)(v >> (8 * i));
}

uint64_t brama_get_le(const uint8_t *in, size_t n)
{
    uint64_t v = 0;
    for (size_t i = 0; i < n; i++) v |= (uint64_t)in[i] << (8 * i);
    return v;
}

brama_msg_info_t brama_msg_info(uint8_t type)
{
    const brama_dir_t C = BRAMA_DIR_C2S, S = BRAMA_DIR_S2C;
    switch (type) {
    case BRAMA_MSG_OPEN:
    case BRAMA_MSG_CLOSE:
    case BRAMA_MSG_OPEN_PAIRING_WINDOW:
    case BRAMA_MSG_LIST_CLIENTS:
    case BRAMA_MSG_REMOVE_CLIENT:
    case BRAMA_MSG_SET_CLIENT_NAME:
    case BRAMA_MSG_PING:
    case BRAMA_MSG_OTA_BEGIN:
    case BRAMA_MSG_OTA_DATA:
    case BRAMA_MSG_OTA_END:
    case BRAMA_MSG_OTA_ABORT: return (brama_msg_info_t){true, C, BRAMA_TAG_GCM, BRAMA_PHASE_SESSION};
    case BRAMA_MSG_PONG:
    case BRAMA_MSG_RULE_STATUS:
    case BRAMA_MSG_COMMAND_ACK:
    case BRAMA_MSG_CLIENT_LIST:
    case BRAMA_MSG_OTA_ACK: return (brama_msg_info_t){true, S, BRAMA_TAG_GCM, BRAMA_PHASE_SESSION};
    case BRAMA_MSG_HANDSHAKE_REJECT: return (brama_msg_info_t){true, S, BRAMA_TAG_NONE, BRAMA_PHASE_HANDSHAKE};
    case BRAMA_MSG_SESSION_BEGIN:
    case BRAMA_MSG_SESSION_CONFIRM: return (brama_msg_info_t){true, C, BRAMA_TAG_HMAC, BRAMA_PHASE_HANDSHAKE};
    case BRAMA_MSG_SESSION_CHALLENGE: return (brama_msg_info_t){true, S, BRAMA_TAG_HMAC, BRAMA_PHASE_HANDSHAKE};
    case BRAMA_MSG_SESSION_READY: return (brama_msg_info_t){true, S, BRAMA_TAG_GCM, BRAMA_PHASE_HANDSHAKE};
    case BRAMA_MSG_PAIR_BEGIN:
    case BRAMA_MSG_PAIR_CONFIRM: return (brama_msg_info_t){true, C, BRAMA_TAG_HMAC, BRAMA_PHASE_PAIR};
    case BRAMA_MSG_PAIR_CHALLENGE: return (brama_msg_info_t){true, S, BRAMA_TAG_HMAC, BRAMA_PHASE_PAIR};
    case BRAMA_MSG_PAIR_GRANT:
    case BRAMA_MSG_PAIR_DONE: return (brama_msg_info_t){true, S, BRAMA_TAG_GCM, BRAMA_PHASE_PAIR};
    case BRAMA_MSG_PAIR_COMMIT: return (brama_msg_info_t){true, C, BRAMA_TAG_GCM, BRAMA_PHASE_PAIR};
    case BRAMA_MSG_PAKE_ROUND1:
    case BRAMA_MSG_PAKE_ROUND2: return (brama_msg_info_t){true, C, BRAMA_TAG_NONE, BRAMA_PHASE_PAIR};
    case BRAMA_MSG_PAKE_ROUND1_REPLY:
    case BRAMA_MSG_PAKE_ROUND2_REPLY: return (brama_msg_info_t){true, S, BRAMA_TAG_NONE, BRAMA_PHASE_PAIR};
    default: return (brama_msg_info_t){false, C, BRAMA_TAG_GCM, BRAMA_PHASE_SESSION};
    }
}

void brama_header_encode(const brama_frame_header_t *h, uint8_t out[BRAMA_FRAME_HEADER_LEN])
{
    out[0] = 0x47; // 'G'
    out[1] = 0x42; // 'B'
    out[2] = 0x02;
    out[3] = h->type;
    brama_put_le(out + 4, h->session_id, 8);
    brama_put_le(out + 12, h->seq, 8);
    brama_put_le(out + 20, h->payload_len, 2);
}

int brama_frame_decode(const uint8_t *b, size_t len, brama_frame_t *f)
{
    if (len < BRAMA_FRAME_HEADER_LEN || len > BRAMA_FRAME_MAX) return BRAMA_ERR_FORMAT;
    if (b[0] != 0x47 || b[1] != 0x42 || b[2] != 0x02) return BRAMA_ERR_FORMAT;
    f->h.type = b[3];
    f->h.session_id = brama_get_le(b + 4, 8);
    f->h.seq = brama_get_le(b + 12, 8);
    f->h.payload_len = (uint16_t)brama_get_le(b + 20, 2);
    brama_msg_info_t info = brama_msg_info(f->h.type);
    f->tag_len = info.tag;
    if (len != BRAMA_FRAME_HEADER_LEN + (size_t)f->h.payload_len + f->tag_len) return BRAMA_ERR_FORMAT;
    switch (info.tag) {
    case BRAMA_TAG_HMAC:
        if (f->h.seq != 0) return BRAMA_ERR_FORMAT;
        break;
    case BRAMA_TAG_GCM:
        if (f->h.seq == 0) return BRAMA_ERR_FORMAT;
        break;
    case BRAMA_TAG_NONE:
        if (f->h.seq != 0) return BRAMA_ERR_FORMAT;
        if (f->h.type == BRAMA_MSG_HANDSHAKE_REJECT ? f->h.payload_len != 1 : f->h.payload_len == 0) return BRAMA_ERR_FORMAT;
        break;
    }
    bool zero_sid_type = f->h.type == BRAMA_MSG_SESSION_BEGIN || f->h.type == BRAMA_MSG_PAIR_BEGIN
                         || f->h.type == BRAMA_MSG_PAKE_ROUND1;
    if (zero_sid_type && f->h.session_id != 0) return BRAMA_ERR_FORMAT;
    f->header = b;
    f->body = b + BRAMA_FRAME_HEADER_LEN;
    f->tag = f->body + f->h.payload_len;
    return BRAMA_OK;
}
