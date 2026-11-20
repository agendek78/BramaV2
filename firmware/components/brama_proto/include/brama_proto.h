#pragma once
// Format transportowy protokołu v2 (10, 12.2, 12.4, 13.1, 15.5): typy, nagłówek 22 B, fragmentacja GATT,
// budżety czasu i payloady. Bez kryptografii (szyfrowanie ramek: brama_session). Liczby little-endian.
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define BRAMA_FRAME_HEADER_LEN 22
#define BRAMA_FRAME_MAX 512
#define BRAMA_FRAGMENT_HEADER_LEN 3
#define BRAMA_MAX_FRAGMENTS 32
#define BRAMA_SNAPSHOT_LEN 15
#define BRAMA_ACK_LEN 25
#define BRAMA_OTA_ACK_LEN 14
#define BRAMA_OTA_BEGIN_LEN 36
#define BRAMA_OTA_MAX_DATA 470

typedef enum {
    BRAMA_MSG_OPEN = 0x01,
    BRAMA_MSG_CLOSE = 0x02,
    BRAMA_MSG_OPEN_PAIRING_WINDOW = 0x03, // okno rejestracji z zarejestrowanego telefonu (phone_window_design)
    BRAMA_MSG_LIST_CLIENTS = 0x04,        // lista telefonów na sterowniku (client_management_design.md)
    BRAMA_MSG_REMOVE_CLIENT = 0x05,       // usunięcie innego telefonu
    BRAMA_MSG_SET_CLIENT_NAME = 0x06,     // nazwa telefonu żądającego
    BRAMA_MSG_HANDSHAKE_REJECT = 0x0E,
    BRAMA_MSG_SESSION_BEGIN = 0x10,
    BRAMA_MSG_SESSION_CHALLENGE = 0x11,
    BRAMA_MSG_SESSION_CONFIRM = 0x12,
    BRAMA_MSG_SESSION_READY = 0x13,
    BRAMA_MSG_PAIR_BEGIN = 0x20,
    BRAMA_MSG_PAIR_CHALLENGE = 0x21,
    BRAMA_MSG_PAIR_CONFIRM = 0x22,
    BRAMA_MSG_PAIR_GRANT = 0x23,
    BRAMA_MSG_PAIR_COMMIT = 0x24,
    BRAMA_MSG_PAIR_DONE = 0x25,
    BRAMA_MSG_PAKE_ROUND1 = 0x26,
    BRAMA_MSG_PAKE_ROUND1_REPLY = 0x27,
    BRAMA_MSG_PAKE_ROUND2 = 0x28,
    BRAMA_MSG_PAKE_ROUND2_REPLY = 0x29,
    BRAMA_MSG_PING = 0x30,
    BRAMA_MSG_PONG = 0x31,
    BRAMA_MSG_RULE_STATUS = 0x40,
    BRAMA_MSG_OTA_BEGIN = 0x50,
    BRAMA_MSG_OTA_DATA = 0x51,
    BRAMA_MSG_OTA_END = 0x52,
    BRAMA_MSG_OTA_ABORT = 0x53,
    BRAMA_MSG_COMMAND_ACK = 0x80,
    BRAMA_MSG_CLIENT_LIST = 0x82,
    BRAMA_MSG_OTA_ACK = 0x81,
} brama_msg_type_t;

typedef enum { BRAMA_DIR_C2S, BRAMA_DIR_S2C } brama_dir_t;
typedef enum { BRAMA_TAG_NONE = 0, BRAMA_TAG_GCM = 16, BRAMA_TAG_HMAC = 32 } brama_tag_t;
typedef enum { BRAMA_PHASE_HANDSHAKE, BRAMA_PHASE_PAIR, BRAMA_PHASE_SESSION } brama_msg_phase_t;

typedef struct {
    bool known;
    brama_dir_t dir;
    brama_tag_t tag;
    brama_msg_phase_t phase;
} brama_msg_info_t;

/** Właściwości typu; nieznany typ: known = false, tag GCM (13.3). */
brama_msg_info_t brama_msg_info(uint8_t type);

typedef struct {
    uint8_t type;
    uint64_t session_id;
    uint64_t seq;
    uint16_t payload_len;
} brama_frame_header_t;

/** Zdekodowana ramka; wskaźniki do bufora wejściowego. */
typedef struct {
    brama_frame_header_t h;
    const uint8_t *header; // 22 B — AAD dla GCM
    const uint8_t *body;
    const uint8_t *tag;
    size_t tag_len;
} brama_frame_t;

void brama_header_encode(const brama_frame_header_t *h, uint8_t out[BRAMA_FRAME_HEADER_LEN]);
/** Reguły 10.1/10.3; BRAMA_ERR_FORMAT przy każdym naruszeniu. */
int brama_frame_decode(const uint8_t *bytes, size_t len, brama_frame_t *f);

// --- Budżety 10.4 -----------------------------------------------------------------------------------------------

typedef struct {
    uint16_t gatt_value_budget;
    uint16_t fragment_data_budget;
    uint32_t effective_interval_ms;
    uint32_t fragment_slot_ms;
    uint32_t progress_ms;
    uint32_t max_fragments;
    uint32_t response_ms;
    uint32_t attempt_ms;
} brama_budget_t;

brama_budget_t brama_budget(uint16_t att_mtu, uint32_t interval_ms, uint16_t slave_latency);
uint32_t brama_budget_frame_ms(const brama_budget_t *b, uint32_t fragment_count);

// --- Fragmentacja 10.2 ------------------------------------------------------------------------------------------

size_t brama_fragment_count(size_t frame_len, uint16_t data_budget);
int brama_fragment_get(const uint8_t *frame, size_t frame_len, uint8_t transfer_id, uint16_t data_budget, size_t index,
                       uint8_t *out, size_t *out_len);

typedef enum { BRAMA_REASM_IN_PROGRESS, BRAMA_REASM_COMPLETE, BRAMA_REASM_DROPPED } brama_reasm_result_t;

/** Składanie jednej ramki na łączu w jednym kierunku; budżet zamrażany na początku transferu. */
typedef struct {
    bool active;
    uint8_t tid, count, next;
    brama_budget_t budget;
    int64_t start_ms, last_ms;
    size_t len;
    uint8_t buf[BRAMA_FRAME_MAX];
} brama_reasm_t;

void brama_reasm_init(brama_reasm_t *r);
/** Przy COMPLETE `*frame`/`*frame_len` wskazują bufor składacza, ważny do następnego accept. */
brama_reasm_result_t brama_reasm_accept(brama_reasm_t *r, const uint8_t *frag, size_t len, int64_t now_ms,
                                        const brama_budget_t *current, const uint8_t **frame, size_t *frame_len);
/** Usuwa niedokończony bufor po T_progress lub T_frame; true, gdy usunięto. */
bool brama_reasm_expire(brama_reasm_t *r, int64_t now_ms);

// --- RuleSnapshot 12.2 i payloady --------------------------------------------------------------------------------

typedef enum {
    BRAMA_PHASE_DISARMED = 0, BRAMA_PHASE_MONITORING, BRAMA_PHASE_WAITING, BRAMA_PHASE_BOOT_WAIT, BRAMA_PHASE_BLOCKED,
    BRAMA_PHASE_FAULT
} brama_rule_phase_t;
typedef enum { BRAMA_OWNER_NONE = 0, BRAMA_OWNER_SELF, BRAMA_OWNER_OTHER } brama_owner_relation_t;
typedef enum {
    BRAMA_REASON_NONE = 0, BRAMA_REASON_LINK_LOST, BRAMA_REASON_REBOOT, BRAMA_REASON_INTERLOCK, BRAMA_REASON_IO_FAULT,
    BRAMA_REASON_STORAGE_FAILURE, BRAMA_REASON_BUSY_TIMEOUT
} brama_rule_reason_t;

typedef struct {
    uint64_t revision;
    uint8_t phase, owner, reason;
    uint32_t remaining_ms;
} brama_snapshot_t;

void brama_snapshot_encode(const brama_snapshot_t *s, uint8_t out[BRAMA_SNAPSHOT_LEN]);
bool brama_snapshot_valid(const brama_snapshot_t *s);

typedef enum {
    BRAMA_ACK_OUTPUT_TRIGGERED = 0, BRAMA_ACK_BUSY, BRAMA_ACK_LEASE_OWNED_BY_OTHER, BRAMA_ACK_INTERLOCK_BLOCKED,
    BRAMA_ACK_STORAGE_FAILURE, BRAMA_ACK_IO_FAULT, BRAMA_ACK_INVALID_ARGUMENT, BRAMA_ACK_UNSUPPORTED_TYPE,
    BRAMA_ACK_WINDOW_OPENED, BRAMA_ACK_CLIENT_REMOVED, BRAMA_ACK_NAME_SET, BRAMA_ACK_CLIENT_NOT_FOUND,
    BRAMA_ACK_CANNOT_REMOVE_SELF, BRAMA_ACK_CLIENT_OWNS_RULE
} brama_ack_result_t;

typedef enum {
    BRAMA_REJECT_AUTH_FAILED = 1, BRAMA_REJECT_UNAVAILABLE, BRAMA_REJECT_RATE_LIMITED, BRAMA_REJECT_NO_CAPACITY,
    BRAMA_REJECT_CLIENT_ALREADY_ACTIVE, BRAMA_REJECT_REPLACEMENT_BLOCKED, BRAMA_REJECT_STORAGE_FAILURE,
    BRAMA_REJECT_WINDOW_CLOSED
} brama_reject_reason_t;

typedef enum {
    BRAMA_OTA_OK = 0x00, BRAMA_OTA_BUSY, BRAMA_OTA_RULE_ACTIVE, BRAMA_OTA_LINK_TOO_SMALL, BRAMA_OTA_TOO_LARGE,
    BRAMA_OTA_IMAGE_REJECTED, BRAMA_OTA_DOWNGRADE, BRAMA_OTA_BAD_OFFSET, BRAMA_OTA_NOT_IN_PROGRESS,
    BRAMA_OTA_STORAGE_FAILURE, BRAMA_OTA_INVALID_ARGUMENT
} brama_ota_result_t;

/** `request_type[1] || result[1] || request_seq[8] || RuleSnapshot[15]` (13.1). */
void brama_ack_encode(uint8_t request_type, uint8_t result, uint64_t request_seq, const brama_snapshot_t *s,
                      uint8_t out[BRAMA_ACK_LEN]);
/** `request_type[1] || result[1] || request_seq[8] || next_offset[4]` (15.5). */
void brama_ota_ack_encode(uint8_t request_type, uint8_t result, uint64_t request_seq, uint32_t next_offset,
                          uint8_t out[BRAMA_OTA_ACK_LEN]);
/** OTA_BEGIN: `image_size[4] || sha256[32]`; `*sha` wskazuje bufor wejściowy. */
int brama_ota_begin_decode(const uint8_t *p, size_t len, uint32_t *image_size, const uint8_t **sha);
/** OTA_DATA: `offset[4] || data[1..470]`. */
int brama_ota_data_decode(const uint8_t *p, size_t len, uint32_t *offset, const uint8_t **data, size_t *data_len);

void brama_put_le(uint8_t *out, uint64_t v, size_t n);
uint64_t brama_get_le(const uint8_t *in, size_t n);

#ifdef __cplusplus
}
#endif
