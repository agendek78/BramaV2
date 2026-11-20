#pragma once
#include <stddef.h>
#include <stdint.h>

/** Dekoduje hex do bufora statycznego (do 4 KiB); zwraca wskaźnik ważny do następnego wywołania z tym samym slotem. */
const uint8_t *hex(int slot, const char *text, size_t *len);
