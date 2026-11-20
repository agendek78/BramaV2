#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "test_vectors.h"
#include "unity.h"

#define MAX_ENTRIES 96
#define MAX_VALUE 1024

static struct {
    char key[48];
    uint8_t value[MAX_VALUE];
    size_t len;
} entries[MAX_ENTRIES];
static int count;

void vec_load(const char *rel)
{
    char path[512];
    snprintf(path, sizeof path, "%s/%s", BRAMA_RES_DIR, rel);
    FILE *f = fopen(path, "r");
    TEST_ASSERT_NOT_NULL_MESSAGE(f, path);
    count = 0;
    static char line[2 * MAX_VALUE + 64];
    while (fgets(line, sizeof line, f)) {
        if (line[0] == '#' || line[0] == '\n') continue;
        char *eq = strchr(line, '=');
        if (!eq) continue;
        TEST_ASSERT_TRUE(count < MAX_ENTRIES);
        *eq = 0;
        TEST_ASSERT_TRUE(strlen(line) < sizeof entries[count].key);
        strcpy(entries[count].key, line);
        size_t n = 0;
        for (char *p = eq + 1; p[0] && p[1] && p[0] != '\n'; p += 2) {
            char b[3] = { p[0], p[1], 0 };
            TEST_ASSERT_TRUE(n < MAX_VALUE);
            entries[count].value[n++] = (uint8_t)strtoul(b, NULL, 16);
        }
        entries[count].len = n;
        count++;
    }
    fclose(f);
}

bool vec_has(const char *key)
{
    for (int i = 0; i < count; i++) if (strcmp(entries[i].key, key) == 0) return true;
    return false;
}

const uint8_t *vec(const char *key, size_t *len)
{
    for (int i = 0; i < count; i++) {
        if (strcmp(entries[i].key, key) == 0) {
            if (len) *len = entries[i].len;
            return entries[i].value;
        }
    }
    TEST_FAIL_MESSAGE(key);
    return NULL;
}
