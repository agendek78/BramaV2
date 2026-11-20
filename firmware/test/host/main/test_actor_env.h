#pragma once
// Wspólny świat testów aktora (test_actor.c, test_ota.c).
#include <stdbool.h>
#include "brama_actor.h"
#include "fake_nvs.h"

typedef struct {
    int link;
    uint64_t token, req_seq;
    uint8_t type;
    uint8_t payload[320];
    size_t len;
} sent_t;

typedef struct {
    int64_t now_us;
    fake_nvs_t fnvs;
    brama_nvs_t nvs;
    brama_rtc_copy_t rtc;
    bool rtc_written;
    int rtc_writes;
    bool io_fail, interlock;
    int stops;
    struct { brama_output_t out; int64_t at_us; uint32_t ms; } pulses[2100];
    int npulses;
    sent_t sent[8192];
    int nsent;
    int pair_link, pair_results;
    bool pair_ok;
    int repair_link, repair_results;
    uint64_t repair_token;
    uint8_t repair_code;
    bool repair_replaced;      // callback dostał stary bond o innym adresie tożsamości
    uint8_t repair_replaced_addr;
    bool disconnected[BRAMA_MAX_LINKS];
    int bonds_removed;         // callback client_removed (zarządzanie telefonami)
    uint8_t bond_removed_addr;
} fake_world_t;

extern fake_world_t W;
extern brama_actor_t ACT;
extern const uint8_t CA[16], CB[16], CC[16];

void world_init(int clients);
void run_msg(brama_actor_msg_t *m);
void connect_ready(int link, const uint8_t *client);
void command(int link, uint8_t type, const uint8_t *payload, size_t len, uint64_t seq);
void open_cmd(int link, bool auto_close, uint64_t seq);
void tick_to(int64_t us);
void disconnect_link(int link);
const sent_t *last_to(int link, uint8_t type);
uint8_t last_ack_result(int link);
