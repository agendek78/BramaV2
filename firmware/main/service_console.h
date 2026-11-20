#pragma once
#include <stdbool.h>
#include "brama_ports.h"

/** Konsola UART (15.4): pełny zestaw komend; w trybie serwisowym/provisioningu bez BLE i aktora, w normalnym obok nich. banner: tekst wypisywany na starcie (tryb). Uruchamia REPL i wraca. */
void brama_service_console_run(brama_nvs_t *nvs, const char *banner);

/** Stan okna i prób PIN-u z aktora (pod jego blokadą). false: aktor nie działa (tryb serwisowy/provisioningu). */
bool brama_app_pin_status(int *left, bool *window);
