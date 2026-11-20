#include "brama_proto.h"

#define MAX_GATT_VALUE 512

static uint32_t max_u32(uint32_t a, uint32_t b) { return a > b ? a : b; }

uint32_t brama_budget_frame_ms(const brama_budget_t *b, uint32_t fragment_count)
{
    return 2000 + fragment_count * b->fragment_slot_ms;
}

brama_budget_t brama_budget(uint16_t att_mtu, uint32_t interval_ms, uint16_t slave_latency)
{
    if (att_mtu < 23) att_mtu = 23;
    if (interval_ms < 1) interval_ms = 1;
    brama_budget_t b;
    b.gatt_value_budget = (uint16_t)(att_mtu - 3 < MAX_GATT_VALUE ? att_mtu - 3 : MAX_GATT_VALUE);
    b.fragment_data_budget = (uint16_t)(b.gatt_value_budget - BRAMA_FRAGMENT_HEADER_LEN);
    b.effective_interval_ms = interval_ms * (slave_latency + 1u);
    b.fragment_slot_ms = max_u32(300, 4 * b.effective_interval_ms);
    b.progress_ms = max_u32(5000, 4 * b.effective_interval_ms);
    b.max_fragments = (BRAMA_FRAME_MAX + b.fragment_data_budget - 1) / b.fragment_data_budget;
    b.response_ms = 2 * brama_budget_frame_ms(&b, b.max_fragments) + 5000;
    b.attempt_ms = 4 * b.response_ms;
    return b;
}
