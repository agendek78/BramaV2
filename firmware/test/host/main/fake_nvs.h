#pragma once
#include <stdbool.h>
#include "brama_ports.h"

/** NVS w pamięci: licznik zapisów (A59), wstrzykiwany błąd zapisu i urwany zapis (zanik zasilania). */
typedef struct {
    struct {
        char key[16];
        uint8_t value[2048];
        size_t len;
        bool used;
    } entries[16];
    int writes;
    bool fail_next_write;    // write zwraca błąd, bez zmiany danych
    bool torn_next_write;    // write zapisuje uszkodzone dane i zwraca błąd (zanik zasilania w trakcie)
    bool corrupt_readback;   // kolejne odczyty zwracają odwrócony ostatni bajt (błąd weryfikacji)
} fake_nvs_t;

void fake_nvs_init(fake_nvs_t *f, brama_nvs_t *port);
/** Odwraca bit w zapisanej wartości (uszkodzenie po fakcie). */
void fake_nvs_flip(fake_nvs_t *f, const char *key, size_t offset);
