/*
 * Generator wektorów interop EC-JPAKE (spec 11.4): mbedTLS w roli SERVER (jak firmware ESP32) z deterministycznym
 * RNG. Weryfikuje rundy klienta wygenerowane przez Kotlin (`read_round_one/two` muszą zwrócić 0) i wypisuje swoje
 * rundy oraz pms. Budowa: patrz README.md.
 *
 *   harness <pin> <cli_one_hex>                -> srv_one=, srv_two=
 *   harness <pin> <cli_one_hex> <cli_two_hex>  -> srv_one=, srv_two=, pms=
 */
#include "mbedtls/ecjpake.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int rng(void *ctx, unsigned char *out, size_t len)
{
    static unsigned int state = 0x1234;
    (void) ctx;
    for (size_t i = 0; i < len; i++) {
        state = state * 1664525u + 1013904223u;
        out[i] = (unsigned char) (state >> 24);
    }
    return 0;
}

static size_t unhex(const char *s, unsigned char *out)
{
    size_t n = strlen(s) / 2;
    for (size_t i = 0; i < n; i++) sscanf(s + 2 * i, "%2hhx", &out[i]);
    return n;
}

static void print(const char *name, const unsigned char *b, size_t n)
{
    printf("%s=", name);
    for (size_t i = 0; i < n; i++) printf("%02x", b[i]);
    printf("\n");
}

int main(int argc, char **argv)
{
    if (argc < 3) return 2;
    mbedtls_ecjpake_context srv;
    unsigned char in[1024], out[1024];
    size_t len, olen;
    mbedtls_ecjpake_init(&srv);
    if (mbedtls_ecjpake_setup(&srv, MBEDTLS_ECJPAKE_SERVER, MBEDTLS_MD_SHA256, MBEDTLS_ECP_DP_SECP256R1,
                              (const unsigned char *) argv[1], strlen(argv[1])) != 0) return 3;
    if (mbedtls_ecjpake_write_round_one(&srv, out, sizeof(out), &olen, rng, NULL) != 0) return 4;
    print("srv_one", out, olen);
    len = unhex(argv[2], in);
    if (mbedtls_ecjpake_read_round_one(&srv, in, len) != 0) { fprintf(stderr, "cli_one rejected\n"); return 5; }
    if (mbedtls_ecjpake_write_round_two(&srv, out, sizeof(out), &olen, rng, NULL) != 0) return 6;
    print("srv_two", out, olen);
    if (argc > 3) {
        len = unhex(argv[3], in);
        if (mbedtls_ecjpake_read_round_two(&srv, in, len) != 0) { fprintf(stderr, "cli_two rejected\n"); return 7; }
        if (mbedtls_ecjpake_derive_secret(&srv, out, sizeof(out), &olen, rng, NULL) != 0) return 8;
        print("pms", out, olen);
    }
    mbedtls_ecjpake_free(&srv);
    return 0;
}
