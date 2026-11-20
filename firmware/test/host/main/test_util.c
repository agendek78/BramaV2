#include <stdlib.h>
#include <string.h>
#include "test_util.h"
#include "unity.h"

#define SLOTS 8
static uint8_t buffers[SLOTS][4096];

const uint8_t *hex(int slot, const char *text, size_t *len)
{
    TEST_ASSERT_TRUE(slot >= 0 && slot < SLOTS);
    size_t n = strlen(text) / 2;
    TEST_ASSERT_TRUE(n <= sizeof buffers[0]);
    for (size_t i = 0; i < n; i++) {
        char b[3] = { text[2 * i], text[2 * i + 1], 0 };
        buffers[slot][i] = (uint8_t)strtoul(b, NULL, 16);
    }
    if (len) *len = n;
    return buffers[slot];
}
