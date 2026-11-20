#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/** Wczytuje plik `klucz=hex` z protocol/src/test/resources/<rel> (np. "vectors/session.txt"). */
void vec_load(const char *rel);
/** Wartość klucza z ostatnio wczytanego pliku; test kończy się błędem, gdy klucza brak. */
const uint8_t *vec(const char *key, size_t *len);
bool vec_has(const char *key);
