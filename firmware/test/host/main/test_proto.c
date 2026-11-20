#include <stdio.h>
#include <string.h>
#include "unity.h"
#include "brama_err.h"
#include "brama_proto.h"
#include "test_registry.h"
#include "test_util.h"
#include "test_vectors.h"

static void client_management_types_are_session_gcm_messages(void)
{
    const uint8_t c2s[] = {BRAMA_MSG_LIST_CLIENTS, BRAMA_MSG_REMOVE_CLIENT, BRAMA_MSG_SET_CLIENT_NAME};
    for (size_t i = 0; i < sizeof c2s; i++) {
        brama_msg_info_t m = brama_msg_info(c2s[i]);
        TEST_ASSERT_TRUE(m.known);
        TEST_ASSERT_EQUAL(BRAMA_DIR_C2S, m.dir);
        TEST_ASSERT_EQUAL(BRAMA_TAG_GCM, m.tag);
        TEST_ASSERT_EQUAL(BRAMA_PHASE_SESSION, m.phase);
    }
    brama_msg_info_t l = brama_msg_info(BRAMA_MSG_CLIENT_LIST);
    TEST_ASSERT_TRUE(l.known);
    TEST_ASSERT_EQUAL(BRAMA_DIR_S2C, l.dir);
    TEST_ASSERT_EQUAL(BRAMA_TAG_GCM, l.tag);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_SESSION, l.phase);
    TEST_ASSERT_EQUAL(9, BRAMA_ACK_CLIENT_REMOVED);
    TEST_ASSERT_EQUAL(13, BRAMA_ACK_CLIENT_OWNS_RULE);
}

static void headers_16_1(void)
{
    uint8_t h[BRAMA_FRAME_HEADER_LEN];
    brama_frame_header_t open = {BRAMA_MSG_OPEN, 0x0102030405060708ULL, 1, 1};
    brama_header_encode(&open, h);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(hex(0, "474202010807060504030201010000000000000001" "00", NULL), h, 22);
    brama_frame_header_t close = {BRAMA_MSG_CLOSE, 0x0102030405060708ULL, 1, 0};
    brama_header_encode(&close, h);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(hex(0, "47420202080706050403020101000000000000000000", NULL), h, 22);
}

static int decode_hex(const char *text)
{
    size_t len;
    const uint8_t *b = hex(1, text, &len);
    brama_frame_t f;
    return brama_frame_decode(b, len, &f);
}

/** Ramka z nagłówkiem typu/sid/seq i payload_len, payloadem zer i tagiem zer podanej długości. */
static int decode_built(uint8_t type, uint64_t sid, uint64_t seq, uint16_t plen, size_t body_len, size_t tag_len)
{
    static uint8_t buf[600];
    brama_frame_header_t h = {type, sid, seq, plen};
    brama_header_encode(&h, buf);
    memset(buf + 22, 0, body_len + tag_len);
    brama_frame_t f;
    return brama_frame_decode(buf, 22 + body_len + tag_len, &f);
}

static void frame_accepts_vector_frames(void)
{
    vec_load("vectors/commands.txt");
    const char *keys[] = {"c2s_1", "s2c_1", "c2s_2", "s2c_2", "c2s_3", "s2c_3"};
    for (int i = 0; i < 6; i++) {
        size_t len;
        const uint8_t *b = vec(keys[i], &len);
        brama_frame_t f;
        TEST_ASSERT_EQUAL_MESSAGE(BRAMA_OK, brama_frame_decode(b, len, &f), keys[i]);
        TEST_ASSERT_EQUAL(len, 22 + f.h.payload_len + f.tag_len);
    }
}

static void frame_rejects_bad_magic_version_and_short(void)
{
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, decode_hex("48420202080706050403020101000000000000000000" "00000000000000000000000000000000"));
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, decode_hex("47420102080706050403020101000000000000000000" "00000000000000000000000000000000"));
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, decode_hex("474202"));
}

static void frame_rejects_length_mismatch_and_oversize(void)
{
    TEST_ASSERT_EQUAL(BRAMA_OK, decode_built(BRAMA_MSG_OPEN, 1, 1, 1, 1, 16));
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, decode_built(BRAMA_MSG_OPEN, 1, 1, 1, 2, 16));
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, decode_built(BRAMA_MSG_OPEN, 1, 1, 1, 1, 15));
    TEST_ASSERT_EQUAL(BRAMA_OK, decode_built(BRAMA_MSG_OTA_DATA, 1, 1, 474, 474, 16));
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, decode_built(BRAMA_MSG_OTA_DATA, 1, 1, 475, 475, 16));
}

static void frame_rejects_wrong_seq_for_tag_kind(void)
{
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, decode_built(BRAMA_MSG_OPEN, 1, 0, 1, 1, 16));           // GCM wymaga seq ≥ 1
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, decode_built(BRAMA_MSG_SESSION_CONFIRM, 1, 1, 0, 0, 32)); // HMAC wymaga seq 0
    TEST_ASSERT_EQUAL(BRAMA_OK, decode_built(BRAMA_MSG_SESSION_CONFIRM, 1, 0, 0, 0, 32));
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, decode_built(BRAMA_MSG_PAKE_ROUND2, 1, 1, 5, 5, 0));      // bez tagu: seq 0
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, decode_built(BRAMA_MSG_PAKE_ROUND2, 1, 0, 0, 0, 0));      // pusta runda
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, decode_built(BRAMA_MSG_HANDSHAKE_REJECT, 1, 0, 2, 2, 0));
    TEST_ASSERT_EQUAL(BRAMA_OK, decode_built(BRAMA_MSG_HANDSHAKE_REJECT, 1, 0, 1, 1, 0));
}

static void frame_begin_requires_zero_session_id(void)
{
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, decode_built(BRAMA_MSG_SESSION_BEGIN, 5, 0, 48, 48, 32));
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, decode_built(BRAMA_MSG_PAIR_BEGIN, 5, 0, 48, 48, 32));
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, decode_built(BRAMA_MSG_PAKE_ROUND1, 5, 0, 10, 10, 0));
    TEST_ASSERT_EQUAL(BRAMA_OK, decode_built(BRAMA_MSG_SESSION_BEGIN, 0, 0, 48, 48, 32));
}

static void unknown_type_is_treated_as_gcm(void)
{
    TEST_ASSERT_EQUAL(BRAMA_OK, decode_built(0x99, 1, 1, 0, 0, 16));
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, decode_built(0x99, 1, 1, 0, 0, 0));
    TEST_ASSERT_FALSE(brama_msg_info(0x99).known);
    TEST_ASSERT_TRUE(brama_msg_info(BRAMA_MSG_OTA_ACK).known);
    TEST_ASSERT_EQUAL(BRAMA_DIR_S2C, brama_msg_info(BRAMA_MSG_OTA_ACK).dir);
    TEST_ASSERT_EQUAL(BRAMA_PHASE_SESSION, brama_msg_info(BRAMA_MSG_OTA_BEGIN).phase);
}

static void budget_example_10_4(void)
{
    brama_budget_t b = brama_budget(23, 100, 0);
    TEST_ASSERT_EQUAL(17, b.fragment_data_budget);
    TEST_ASSERT_EQUAL(31, b.max_fragments);
    TEST_ASSERT_EQUAL(5000, b.progress_ms);
    TEST_ASSERT_EQUAL(14400, brama_budget_frame_ms(&b, 31));
    TEST_ASSERT_EQUAL(33800, b.response_ms);
    TEST_ASSERT_EQUAL(135200, b.attempt_ms);
    brama_budget_t big = brama_budget(517, 30, 0);
    TEST_ASSERT_EQUAL(509, big.fragment_data_budget);
    TEST_ASSERT_EQUAL(2, big.max_fragments);
}

static void fragments_match_vectors(void)
{
    vec_load("vectors/fragments.txt");
    struct { const char *name; uint16_t mtu; uint8_t tid; int n; } cases[] = {
        {"mtu23", 23, 1, 3}, {"mtu247", 247, 2, 3}, {"mtu517", 517, 255, 2}};
    for (int c = 0; c < 3; c++) {
        char key[32];
        snprintf(key, sizeof key, "%s_frame", cases[c].name);
        size_t flen;
        const uint8_t *frame = vec(key, &flen);
        brama_budget_t b = brama_budget(cases[c].mtu, 30, 0);
        TEST_ASSERT_EQUAL(cases[c].n, brama_fragment_count(flen, b.fragment_data_budget));
        brama_reasm_t r;
        brama_reasm_init(&r);
        for (int i = 0; i < cases[c].n; i++) {
            uint8_t out[512];
            size_t olen;
            TEST_ASSERT_EQUAL(BRAMA_OK, brama_fragment_get(frame, flen, cases[c].tid, b.fragment_data_budget, i, out, &olen));
            snprintf(key, sizeof key, "%s_frag_%d", cases[c].name, i);
            size_t elen;
            const uint8_t *expected = vec(key, &elen);
            TEST_ASSERT_EQUAL(elen, olen);
            TEST_ASSERT_EQUAL_HEX8_ARRAY(expected, out, olen);
            const uint8_t *done = NULL;
            size_t dlen = 0;
            int rc = brama_reasm_accept(&r, out, olen, 1000 + i, &b, &done, &dlen);
            TEST_ASSERT_EQUAL(i == cases[c].n - 1 ? BRAMA_REASM_COMPLETE : BRAMA_REASM_IN_PROGRESS, rc);
            if (rc == BRAMA_REASM_COMPLETE) {
                TEST_ASSERT_EQUAL(flen, dlen);
                TEST_ASSERT_EQUAL_HEX8_ARRAY(frame, done, flen);
            }
        }
    }
}

static int feed(brama_reasm_t *r, const brama_budget_t *b, const char *hexfrag, int64_t now)
{
    size_t len;
    const uint8_t *f = hex(2, hexfrag, &len);
    const uint8_t *done;
    size_t dlen;
    return brama_reasm_accept(r, f, len, now, b, &done, &dlen);
}

static void reassembly_rules_10_2(void)
{
    brama_budget_t b = brama_budget(23, 30, 0); // 17 B danych na fragment
    brama_reasm_t r;
    brama_reasm_init(&r);
    const char *full = "0100020102030405060708090a0b0c0d0e0f1011"; // tid 1, idx 0, count 2, 17 B
    TEST_ASSERT_EQUAL(BRAMA_REASM_IN_PROGRESS, feed(&r, &b, full, 0));
    // indeks 0 z tym samym transfer_id w trakcie — błąd kolejności, bufor usunięty
    TEST_ASSERT_EQUAL(BRAMA_REASM_DROPPED, feed(&r, &b, full, 1));
    TEST_ASSERT_FALSE(r.active);
    // indeks 0 z nowym transfer_id porzuca stary bufor i zaczyna nowy
    TEST_ASSERT_EQUAL(BRAMA_REASM_IN_PROGRESS, feed(&r, &b, full, 2));
    TEST_ASSERT_EQUAL(BRAMA_REASM_IN_PROGRESS, feed(&r, &b, "0200020102030405060708090a0b0c0d0e0f1011", 3));
    TEST_ASSERT_EQUAL(2, r.tid);
    // zły indeks
    TEST_ASSERT_EQUAL(BRAMA_REASM_DROPPED, feed(&r, &b, "020202aa", 4));
    // fragment niepełny przed ostatnim
    TEST_ASSERT_EQUAL(BRAMA_REASM_DROPPED, feed(&r, &b, "03000201", 5));
    // transfer_id 0 i fragment_count 0 / > 32
    TEST_ASSERT_EQUAL(BRAMA_REASM_DROPPED, feed(&r, &b, "000001aa", 6));
    TEST_ASSERT_EQUAL(BRAMA_REASM_DROPPED, feed(&r, &b, "010000aa", 7));
    TEST_ASSERT_EQUAL(BRAMA_REASM_DROPPED, feed(&r, &b, "010021aa", 8));
    // pusty fragment
    TEST_ASSERT_EQUAL(BRAMA_REASM_DROPPED, feed(&r, &b, "010001", 9));
}

static void reassembly_expires_after_t_progress(void)
{
    brama_budget_t b = brama_budget(23, 30, 0);
    brama_reasm_t r;
    brama_reasm_init(&r);
    // 31 fragmentów: T_frame = 2000 + 31 × 300 = 11,3 s, więc pierwszy wygasa T_progress (5 s).
    TEST_ASSERT_EQUAL(BRAMA_REASM_IN_PROGRESS, feed(&r, &b, "01001f0102030405060708090a0b0c0d0e0f1011", 0));
    TEST_ASSERT_FALSE(brama_reasm_expire(&r, 5000));
    TEST_ASSERT_TRUE(brama_reasm_expire(&r, 5001));
    TEST_ASSERT_EQUAL(BRAMA_REASM_DROPPED, feed(&r, &b, "01011faa", 5002));
}

static void reassembly_expires_after_t_frame(void)
{
    brama_budget_t b = brama_budget(23, 30, 0);
    brama_reasm_t r;
    brama_reasm_init(&r);
    // 2 fragmenty: T_frame = 2000 + 2 × 300 = 2,6 s < T_progress.
    TEST_ASSERT_EQUAL(BRAMA_REASM_IN_PROGRESS, feed(&r, &b, "0100020102030405060708090a0b0c0d0e0f1011", 0));
    TEST_ASSERT_FALSE(brama_reasm_expire(&r, 2600));
    TEST_ASSERT_TRUE(brama_reasm_expire(&r, 2601));
}

static brama_snapshot_t snap(uint8_t phase, uint8_t owner, uint8_t reason, uint32_t remaining)
{
    return (brama_snapshot_t){1, phase, owner, reason, remaining};
}

static void snapshot_consistency_rules(void)
{
    TEST_ASSERT_TRUE(brama_snapshot_valid(&(brama_snapshot_t){1, BRAMA_PHASE_DISARMED, BRAMA_OWNER_NONE, BRAMA_REASON_NONE, 0}));
    TEST_ASSERT_TRUE(brama_snapshot_valid(&(brama_snapshot_t){1, BRAMA_PHASE_DISARMED, BRAMA_OWNER_NONE, BRAMA_REASON_REBOOT, 0}));
    brama_snapshot_t ok[] = {
        snap(BRAMA_PHASE_MONITORING, BRAMA_OWNER_SELF, BRAMA_REASON_NONE, 0),
        snap(BRAMA_PHASE_MONITORING, BRAMA_OWNER_OTHER, BRAMA_REASON_NONE, 0),
        snap(BRAMA_PHASE_WAITING, BRAMA_OWNER_OTHER, BRAMA_REASON_LINK_LOST, 30000),
        snap(BRAMA_PHASE_BOOT_WAIT, BRAMA_OWNER_SELF, BRAMA_REASON_REBOOT, 30000),
        snap(BRAMA_PHASE_BLOCKED, BRAMA_OWNER_SELF, BRAMA_REASON_INTERLOCK, 0),
        snap(BRAMA_PHASE_FAULT, BRAMA_OWNER_SELF, BRAMA_REASON_IO_FAULT, 0),
        snap(BRAMA_PHASE_FAULT, BRAMA_OWNER_SELF, BRAMA_REASON_BUSY_TIMEOUT, 0),
        snap(BRAMA_PHASE_FAULT, BRAMA_OWNER_SELF, BRAMA_REASON_STORAGE_FAILURE, 0),
    };
    for (size_t i = 0; i < sizeof ok / sizeof ok[0]; i++) TEST_ASSERT_TRUE(brama_snapshot_valid(&ok[i]));
    brama_snapshot_t bad[] = {
        snap(BRAMA_PHASE_DISARMED, BRAMA_OWNER_SELF, BRAMA_REASON_NONE, 0),
        snap(BRAMA_PHASE_DISARMED, BRAMA_OWNER_NONE, BRAMA_REASON_NONE, 5),
        snap(BRAMA_PHASE_MONITORING, BRAMA_OWNER_NONE, BRAMA_REASON_NONE, 0),
        snap(BRAMA_PHASE_MONITORING, BRAMA_OWNER_SELF, BRAMA_REASON_NONE, 7),
        snap(BRAMA_PHASE_WAITING, BRAMA_OWNER_SELF, BRAMA_REASON_NONE, 0),
        snap(BRAMA_PHASE_FAULT, BRAMA_OWNER_SELF, BRAMA_REASON_INTERLOCK, 0),
        snap(6, BRAMA_OWNER_SELF, BRAMA_REASON_NONE, 0),
    };
    for (size_t i = 0; i < sizeof bad / sizeof bad[0]; i++) TEST_ASSERT_FALSE(brama_snapshot_valid(&bad[i]));
}

static void ack_matches_vector(void)
{
    vec_load("vectors/commands.txt");
    size_t len;
    const uint8_t *expected = vec("s2c_3_plain", &len); // ACK OPEN(false) przy DISARMED, revision 1
    uint8_t out[BRAMA_ACK_LEN];
    brama_snapshot_t s = {1, BRAMA_PHASE_DISARMED, BRAMA_OWNER_NONE, BRAMA_REASON_NONE, 0};
    brama_ack_encode(BRAMA_MSG_OPEN, BRAMA_ACK_OUTPUT_TRIGGERED, 1, &s, out);
    TEST_ASSERT_EQUAL(25, len);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(expected, out, 25);
}

static void ota_payloads_match_vectors(void)
{
    vec_load("vectors/ota.txt");
    size_t len, shal;
    const uint8_t *begin = vec("c2s_3_plain", &len);
    uint32_t size;
    const uint8_t *sha;
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_ota_begin_decode(begin, len, &size, &sha));
    TEST_ASSERT_EQUAL(471, size);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(vec("image_sha256", &shal), sha, 32);
    const uint8_t *data = vec("c2s_4_plain", &len);
    uint32_t offset;
    const uint8_t *chunk;
    size_t clen;
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_ota_data_decode(data, len, &offset, &chunk, &clen));
    TEST_ASSERT_EQUAL(0, offset);
    TEST_ASSERT_EQUAL(470, clen);
    data = vec("c2s_5_plain", &len);
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_ota_data_decode(data, len, &offset, &chunk, &clen));
    TEST_ASSERT_EQUAL(470, offset);
    TEST_ASSERT_EQUAL(1, clen);
    uint8_t ack[BRAMA_OTA_ACK_LEN];
    brama_ota_ack_encode(BRAMA_MSG_OTA_DATA, BRAMA_OTA_OK, 2, 470, ack);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(vec("s2c_4_plain", &len), ack, 14);
    brama_ota_ack_encode(BRAMA_MSG_OTA_DATA, BRAMA_OTA_BAD_OFFSET, 9, 940, ack);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(vec("ota_ack_bad_offset", &len), ack, 14);
}

static void ota_payload_lengths(void)
{
    uint8_t buf[480] = {0};
    uint32_t size, offset;
    const uint8_t *sha, *chunk;
    size_t clen;
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, brama_ota_begin_decode(buf, 35, &size, &sha));
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, brama_ota_begin_decode(buf, 37, &size, &sha));
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, brama_ota_data_decode(buf, 4, &offset, &chunk, &clen));
    TEST_ASSERT_EQUAL(BRAMA_ERR_FORMAT, brama_ota_data_decode(buf, 475, &offset, &chunk, &clen));
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_ota_data_decode(buf, 474, &offset, &chunk, &clen));
}

void run_proto_tests(void)
{
    RUN_TEST(client_management_types_are_session_gcm_messages);
    RUN_TEST(headers_16_1);
    RUN_TEST(frame_accepts_vector_frames);
    RUN_TEST(frame_rejects_bad_magic_version_and_short);
    RUN_TEST(frame_rejects_length_mismatch_and_oversize);
    RUN_TEST(frame_rejects_wrong_seq_for_tag_kind);
    RUN_TEST(frame_begin_requires_zero_session_id);
    RUN_TEST(unknown_type_is_treated_as_gcm);
    RUN_TEST(budget_example_10_4);
    RUN_TEST(fragments_match_vectors);
    RUN_TEST(reassembly_rules_10_2);
    RUN_TEST(reassembly_expires_after_t_progress);
    RUN_TEST(reassembly_expires_after_t_frame);
    RUN_TEST(snapshot_consistency_rules);
    RUN_TEST(ack_matches_vector);
    RUN_TEST(ota_payloads_match_vectors);
    RUN_TEST(ota_payload_lengths);
}
