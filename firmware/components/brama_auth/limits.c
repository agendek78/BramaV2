#include <string.h>
#include "brama_auth.h"

void brama_auth_limits_init(brama_auth_limits_t *a)
{
    memset(a, 0, sizeof *a);
    // Restart (np. wymuszony przez OTA) nie odnawia prób PIN-u bez przycisku (phone_window_design §7).
    a->pin_failures = BRAMA_PIN_FAILURES_PER_WINDOW;
}

void brama_auth_window_open(brama_auth_limits_t *a, int64_t now_ms)
{
    a->window_open = true;
    a->window_until_ms = now_ms + BRAMA_WINDOW_MS;
    a->pin_failures = 0;
    a->pin_reset_ms = now_ms;
}

void brama_auth_window_open_remote(brama_auth_limits_t *a, int64_t now_ms)
{
    if (now_ms - a->pin_reset_ms >= BRAMA_REMOTE_PIN_RESET_MS) {
        brama_auth_window_open(a, now_ms);
        return;
    }
    a->window_open = true; // licznik PIN-u i chwila ostatniego zerowania bez zmian
    a->window_until_ms = now_ms + BRAMA_WINDOW_MS;
}

void brama_auth_window_close(brama_auth_limits_t *a)
{
    a->window_open = false;
}

bool brama_auth_window_is_open(const brama_auth_limits_t *a, int64_t now_ms)
{
    return a->window_open && now_ms < a->window_until_ms;
}

bool brama_auth_pin_attempt_allowed(const brama_auth_limits_t *a, int64_t now_ms)
{
    return brama_auth_window_is_open(a, now_ms) && a->pin_failures < BRAMA_PIN_FAILURES_PER_WINDOW;
}

uint8_t brama_auth_pin_attempts_left(const brama_auth_limits_t *a, int64_t now_ms)
{
    if (!brama_auth_window_is_open(a, now_ms) || a->pin_failures >= BRAMA_PIN_FAILURES_PER_WINDOW) return 0;
    return (uint8_t)(BRAMA_PIN_FAILURES_PER_WINDOW - a->pin_failures);
}

static brama_peer_failures_t *entry(brama_auth_limits_t *a, const uint8_t peer[7])
{
    brama_peer_failures_t *oldest = &a->peers[0];
    for (size_t i = 0; i < sizeof a->peers / sizeof a->peers[0]; i++) {
        brama_peer_failures_t *p = &a->peers[i];
        if (p->used && memcmp(p->peer, peer, 7) == 0) return p;
        if (!p->used) oldest = p;
        else if (oldest->used && p->last < oldest->last) oldest = p;
    }
    memset(oldest, 0, sizeof *oldest);
    oldest->used = true;
    memcpy(oldest->peer, peer, 7);
    for (int i = 0; i < BRAMA_MAX_FAILURES; i++) oldest->times[i] = INT64_MIN / 2;
    return oldest;
}

void brama_auth_note_failure(brama_auth_limits_t *a, const uint8_t peer[7], bool pin, int64_t now_ms)
{
    brama_peer_failures_t *p = entry(a, peer);
    p->times[p->next] = now_ms;
    p->next = (p->next + 1) % BRAMA_MAX_FAILURES;
    p->last = now_ms;
    // Globalny limit PIN-u w oknie, niezależny od adresu telefonu (11.4).
    if (pin && ++a->pin_failures >= BRAMA_PIN_FAILURES_PER_WINDOW) brama_auth_window_close(a);
}

bool brama_auth_rate_limited(const brama_auth_limits_t *a, const uint8_t peer[7], int64_t now_ms)
{
    for (size_t i = 0; i < sizeof a->peers / sizeof a->peers[0]; i++) {
        const brama_peer_failures_t *p = &a->peers[i];
        if (!p->used || memcmp(p->peer, peer, 7) != 0) continue;
        int recent = 0;
        for (int k = 0; k < BRAMA_MAX_FAILURES; k++) if (now_ms - p->times[k] < BRAMA_FAILURE_WINDOW_MS) recent++;
        return recent >= BRAMA_MAX_FAILURES;
    }
    return false;
}
