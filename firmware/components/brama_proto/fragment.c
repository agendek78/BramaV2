#include <string.h>
#include "brama_err.h"
#include "brama_proto.h"

size_t brama_fragment_count(size_t frame_len, uint16_t data_budget)
{
    return data_budget == 0 ? 0 : (frame_len + data_budget - 1) / data_budget;
}

int brama_fragment_get(const uint8_t *frame, size_t frame_len, uint8_t transfer_id, uint16_t data_budget, size_t index,
                       uint8_t *out, size_t *out_len)
{
    size_t count = brama_fragment_count(frame_len, data_budget);
    if (transfer_id == 0 || frame_len == 0 || frame_len > BRAMA_FRAME_MAX || count > BRAMA_MAX_FRAGMENTS || index >= count) {
        return BRAMA_ERR_ARG;
    }
    size_t from = index * data_budget;
    size_t n = frame_len - from < data_budget ? frame_len - from : data_budget;
    out[0] = transfer_id;
    out[1] = (uint8_t)index;
    out[2] = (uint8_t)count;
    memcpy(out + BRAMA_FRAGMENT_HEADER_LEN, frame + from, n);
    *out_len = BRAMA_FRAGMENT_HEADER_LEN + n;
    return BRAMA_OK;
}

void brama_reasm_init(brama_reasm_t *r)
{
    memset(r, 0, sizeof *r);
}

static brama_reasm_result_t drop(brama_reasm_t *r)
{
    brama_reasm_init(r);
    return BRAMA_REASM_DROPPED;
}

bool brama_reasm_expire(brama_reasm_t *r, int64_t now_ms)
{
    if (!r->active) return false;
    bool expired = now_ms - r->last_ms > r->budget.progress_ms
                   || now_ms - r->start_ms > brama_budget_frame_ms(&r->budget, r->count);
    if (expired) brama_reasm_init(r);
    return expired;
}

brama_reasm_result_t brama_reasm_accept(brama_reasm_t *r, const uint8_t *frag, size_t len, int64_t now_ms,
                                        const brama_budget_t *current, const uint8_t **frame, size_t *frame_len)
{
    brama_reasm_expire(r, now_ms);
    if (len <= BRAMA_FRAGMENT_HEADER_LEN) return drop(r);
    uint8_t id = frag[0], index = frag[1], total = frag[2];
    if (id == 0 || total < 1 || total > BRAMA_MAX_FRAGMENTS || index >= total) return drop(r);

    if (index == 0) {
        // Ten sam transfer_id na indeksie 0 w trakcie składania to błąd kolejności; inny — retransmisja (10.2).
        if (r->active && id == r->tid) return drop(r);
        brama_reasm_init(r);
        r->active = true;
        r->tid = id;
        r->count = total;
        r->budget = *current;
        r->start_ms = now_ms;
        if (total > r->budget.max_fragments) return drop(r);
    } else if (!r->active || id != r->tid || total != r->count || index != r->next) {
        return drop(r);
    }

    size_t data_len = len - BRAMA_FRAGMENT_HEADER_LEN;
    bool last = index == r->count - 1;
    if (data_len > r->budget.fragment_data_budget || (!last && data_len != r->budget.fragment_data_budget)) return drop(r);
    if (r->len + data_len > BRAMA_FRAME_MAX) return drop(r);
    memcpy(r->buf + r->len, frag + BRAMA_FRAGMENT_HEADER_LEN, data_len);
    r->len += data_len;
    r->next = index + 1;
    r->last_ms = now_ms;
    if (!last) return BRAMA_REASM_IN_PROGRESS;
    *frame = r->buf;
    *frame_len = r->len;
    r->active = false; // bufor ważny do następnego accept
    return BRAMA_REASM_COMPLETE;
}
