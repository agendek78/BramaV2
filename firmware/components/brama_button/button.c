#include <string.h>
#include "brama_button.h"

void brama_button_init(brama_button_t *b)
{
    memset(b, 0, sizeof *b);
}

brama_button_event_t brama_button_update(brama_button_t *b, bool pressed, int64_t now_us)
{
    b->now_us = now_us;
    if (pressed != b->raw) {
        b->raw = pressed;
        b->raw_since_us = now_us;
    }
    brama_button_event_t ev = BRAMA_BUTTON_NONE;
    if (b->raw != b->stable && now_us - b->raw_since_us >= BRAMA_BUTTON_DEBOUNCE_US) {
        b->stable = b->raw;
        if (b->stable) {
            b->press_start_us = b->raw_since_us;
            b->reset_fired = false;
        } else {
            int64_t held = b->raw_since_us - b->press_start_us;
            if (!b->reset_fired && held >= BRAMA_BUTTON_WINDOW_US && held < BRAMA_BUTTON_RESET_US) ev = BRAMA_BUTTON_WINDOW;
        }
    }
    // Reset startuje w chwili osiągnięcia 10 s, bez czekania na zwolnienie (9.4).
    if (b->stable && !b->reset_fired && now_us - b->press_start_us >= BRAMA_BUTTON_RESET_US) {
        b->reset_fired = true;
        ev = BRAMA_BUTTON_RESET;
    }
    return ev;
}

brama_hold_stage_t brama_button_stage(const brama_button_t *b)
{
    if (!b->stable) return BRAMA_HOLD_NONE;
    int64_t held = b->now_us - b->press_start_us;
    if (held >= BRAMA_BUTTON_RESET_US) return BRAMA_HOLD_10S;
    return held >= BRAMA_BUTTON_WINDOW_US ? BRAMA_HOLD_3S : BRAMA_HOLD_NONE;
}

brama_led_pattern_t brama_led_select(const brama_led_inputs_t *in)
{
    if (in->reset_in_progress) return BRAMA_LED_RESET_10HZ;
    if (in->reset_success) return BRAMA_LED_RESET_OK;
    if (in->hold_3s) return BRAMA_LED_RELEASE_5HZ;
    if (in->window_open) return BRAMA_LED_WINDOW_1HZ;
    if (in->error) return BRAMA_LED_ERROR;
    if (in->any_ready) return BRAMA_LED_SOLID;
    return BRAMA_LED_IDLE_BLIP;
}

bool brama_led_level(brama_led_pattern_t p, int64_t t_ms)
{
    if (t_ms < 0) t_ms = 0;
    switch (p) {
    case BRAMA_LED_RESET_10HZ: return t_ms % 100 < 50;
    case BRAMA_LED_RESET_OK: return t_ms < 3000 && t_ms % 1000 < 600;
    case BRAMA_LED_RELEASE_5HZ: return t_ms % 200 < 100;
    case BRAMA_LED_WINDOW_1HZ: return t_ms % 1000 < 500;
    case BRAMA_LED_ERROR: {
        int64_t x = t_ms % 2000;
        return x < 100 || (x >= 200 && x < 300);
    }
    case BRAMA_LED_SOLID: return true;
    case BRAMA_LED_IDLE_BLIP: return t_ms % 3000 < 100;
    }
    return false;
}
