#pragma once
// Przycisk PAIR_BUTTON (9.4): filtr drgań ≥ 30 ms, 3–10 s → okno rejestracji przy zwolnieniu, ≥ 10 s → reset
// w chwili osiągnięcia 10 s. STATUS_LED: wzorce z priorytetami tabeli 9.4; nigdy nie informują o położeniu bramy.
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define BRAMA_BUTTON_DEBOUNCE_US (30LL * 1000)
#define BRAMA_BUTTON_WINDOW_US (3LL * 1000 * 1000)
#define BRAMA_BUTTON_RESET_US (10LL * 1000 * 1000)

typedef enum { BRAMA_BUTTON_NONE, BRAMA_BUTTON_WINDOW, BRAMA_BUTTON_RESET } brama_button_event_t;
typedef enum { BRAMA_HOLD_NONE, BRAMA_HOLD_3S, BRAMA_HOLD_10S } brama_hold_stage_t;

typedef struct {
    bool raw, stable;
    int64_t raw_since_us;
    int64_t press_start_us;
    bool reset_fired;
    int64_t now_us;
} brama_button_t;

void brama_button_init(brama_button_t *b);
/** Surowy stan wejścia (true = naciśnięty) próbkowany co ≤ 10 ms. */
brama_button_event_t brama_button_update(brama_button_t *b, bool pressed, int64_t now_us);
brama_hold_stage_t brama_button_stage(const brama_button_t *b);

typedef enum {
    BRAMA_LED_RESET_10HZ,   // 1: przytrzymanie ≥ 10 s / reset w toku
    BRAMA_LED_RESET_OK,     // 2: 3 długie błyski po skutecznym resecie
    BRAMA_LED_RELEASE_5HZ,  // 3: przytrzymanie ≥ 3 s, przed zwolnieniem
    BRAMA_LED_WINDOW_1HZ,   // 4: okno rejestracji
    BRAMA_LED_ERROR,        // 5: nieudany reset, tryb błędu lokalnego, FAULT — 2 krótkie błyski co 2 s
    BRAMA_LED_SOLID,        // 6: co najmniej jedna sesja READY
    BRAMA_LED_IDLE_BLIP,    // 7: praca bez połączeń — krótki błysk co 3 s
} brama_led_pattern_t;

typedef struct {
    bool reset_in_progress;
    bool reset_success;     // przez 3 s po skutecznym resecie
    bool hold_3s;
    bool window_open;
    bool error;
    bool any_ready;
} brama_led_inputs_t;

brama_led_pattern_t brama_led_select(const brama_led_inputs_t *in);
/** Stan diody w chwili `t_ms` od początku wzorca. */
bool brama_led_level(brama_led_pattern_t p, int64_t t_ms);

#ifdef __cplusplus
}
#endif
