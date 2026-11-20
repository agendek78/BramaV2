// Prywatne API mbedTLS tylko w teście: klient EC-JPAKE w formacie TLS (jak `:protocol`).
#define MBEDTLS_ALLOW_PRIVATE_ACCESS
#include <string.h>
#include "unity.h"
#include "brama_crypto.h"
#include "brama_ecjpake.h"
#include "brama_err.h"
#include "mbedtls/private/ecjpake.h"
#include "psa/crypto.h"
#include "test_registry.h"
#include "test_vectors.h"

static const uint8_t PIN[6] = {'4', '8', '2', '9', '1', '5'};

static int test_rng(void *ctx, unsigned char *out, size_t len)
{
    (void)ctx;
    return psa_generate_random(out, len) == PSA_SUCCESS ? 0 : -1;
}

/** Klient mbedTLS (format TLS jak Kotlin) ↔ serwer brama_jpake: obie strony wyliczają ten sam S. */
static void exchange_with_mbedtls_client(const uint8_t *client_pin, size_t client_pin_len, bool *agree, int *server_rc)
{
    mbedtls_ecjpake_context cli;
    mbedtls_ecjpake_init(&cli);
    TEST_ASSERT_EQUAL(0, mbedtls_ecjpake_setup(&cli, MBEDTLS_ECJPAKE_CLIENT, MBEDTLS_MD_SHA256,
                                               MBEDTLS_ECP_DP_SECP256R1, client_pin, client_pin_len));
    uint8_t c1[512], s1[512], c2[512], s2[512], sc[32], ss[32];
    size_t c1l, s1l, c2l, s2l, scl;
    TEST_ASSERT_EQUAL(0, mbedtls_ecjpake_write_round_one(&cli, c1, sizeof c1, &c1l, test_rng, NULL));

    brama_jpake_t srv;
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_jpake_init(&srv, PIN, sizeof PIN));
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_jpake_read_round1(&srv, c1, c1l));
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_jpake_write_round1(&srv, s1, sizeof s1, &s1l));
    TEST_ASSERT_EQUAL(0, mbedtls_ecjpake_read_round_one(&cli, s1, s1l));
    TEST_ASSERT_EQUAL(0, mbedtls_ecjpake_write_round_two(&cli, c2, sizeof c2, &c2l, test_rng, NULL));
    *server_rc = brama_jpake_read_round2(&srv, c2, c2l);
    if (*server_rc == BRAMA_OK) {
        TEST_ASSERT_EQUAL(BRAMA_OK, brama_jpake_write_round2(&srv, s2, sizeof s2, &s2l));
        TEST_ASSERT_EQUAL_HEX8_ARRAY("\x03\x00\x17", s2, 3);
        TEST_ASSERT_EQUAL(0, mbedtls_ecjpake_read_round_two(&cli, s2, s2l));
        TEST_ASSERT_EQUAL(0, mbedtls_ecjpake_derive_secret(&cli, sc, sizeof sc, &scl, test_rng, NULL));
        TEST_ASSERT_EQUAL(BRAMA_OK, brama_jpake_derive_secret(&srv, ss));
        *agree = memcmp(sc, ss, 32) == 0;
    }
    brama_jpake_free(&srv);
    mbedtls_ecjpake_free(&cli);
}

static void psa_server_and_mbedtls_client_agree(void)
{
    bool agree = false;
    int rc = -100;
    exchange_with_mbedtls_client(PIN, sizeof PIN, &agree, &rc);
    TEST_ASSERT_EQUAL(BRAMA_OK, rc);
    TEST_ASSERT_TRUE(agree);
}

static void wrong_pin_yields_different_secret(void)
{
    bool agree = true;
    int rc = -100;
    exchange_with_mbedtls_client((const uint8_t *)"111111", 6, &agree, &rc);
    // Zły PIN nie jest wykrywany w PAKE (ZKP są poprawne), tylko przez potwierdzenie klucza w PAIR_BEGIN (11.4).
    TEST_ASSERT_EQUAL(BRAMA_OK, rc);
    TEST_ASSERT_FALSE(agree);
}

static void interop_server_accepts_kotlin_client_round1(void)
{
    vec_load("ecjpake/interop.txt");
    size_t len;
    const uint8_t *cli_one = vec("cli_one", &len);
    brama_jpake_t srv;
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_jpake_init(&srv, PIN, sizeof PIN));
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_jpake_read_round1(&srv, cli_one, len));
    uint8_t out[512];
    size_t olen;
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_jpake_write_round1(&srv, out, sizeof out, &olen));
    brama_jpake_free(&srv);
}

static void pin_setup_key_matches_kotlin(void)
{
    vec_load("vectors/pair_pin.txt");
    uint8_t pt[1024];
    size_t ptl = 0;
    // PT = payload(26) || payload(27) || payload(28) || payload(29); payload zaczyna się po 22 B nagłówka.
    const char *keys[] = {"c2s_1", "s2c_1", "c2s_2", "s2c_2"};
    for (int i = 0; i < 4; i++) {
        size_t l;
        const uint8_t *f = vec(keys[i], &l);
        memcpy(pt + ptl, f + 22, l - 22);
        ptl += l - 22;
    }
    size_t sl, kl;
    const uint8_t *s = vec("pake_secret", &sl);
    const uint8_t *k_pin = vec("k_pin", &kl);
    uint8_t out[32];
    brama_pin_setup_key(s, pt, ptl, out);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(k_pin, out, 32);
}

static int read1_with(const uint8_t *msg, size_t len)
{
    brama_jpake_t srv;
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_jpake_init(&srv, PIN, sizeof PIN));
    int rc = brama_jpake_read_round1(&srv, msg, len);
    brama_jpake_free(&srv);
    return rc;
}

static void pake_rejects_malformed_rounds(void)
{
    vec_load("ecjpake/interop.txt");
    size_t len;
    const uint8_t *good = vec("cli_one", &len);
    uint8_t m[512];

    // obcięcie i nadmiar bajtów
    TEST_ASSERT_EQUAL(BRAMA_ERR_AUTH, read1_with(good, len - 1));
    memcpy(m, good, len);
    m[len] = 0;
    TEST_ASSERT_EQUAL(BRAMA_ERR_AUTH, read1_with(m, len + 1));
    // zły znacznik punktu (0x05 zamiast 0x04)
    memcpy(m, good, len);
    m[1] = 0x05;
    TEST_ASSERT_EQUAL(BRAMA_ERR_AUTH, read1_with(m, len));
    // punkt spoza krzywej (zmieniona współrzędna y)
    memcpy(m, good, len);
    m[65] ^= 1;
    TEST_ASSERT_EQUAL(BRAMA_ERR_AUTH, read1_with(m, len));
    // punkt w nieskończoności: ECPoint o długości 1 (0x00)
    uint8_t inf[] = {0x01, 0x00};
    TEST_ASSERT_EQUAL(BRAMA_ERR_AUTH, read1_with(inf, sizeof inf));
    // r ≥ n: pierwszy dowód z r = 0xff..ff (32 B)
    memcpy(m, good, len);
    size_t r_len_at = 66 + 66;
    TEST_ASSERT_EQUAL(32, m[r_len_at]);
    memset(m + r_len_at + 1, 0xff, 32);
    TEST_ASSERT_EQUAL(BRAMA_ERR_AUTH, read1_with(m, len));
    // r = 0 (długość 0)
    TEST_ASSERT_EQUAL(BRAMA_ERR_AUTH, read1_with((const uint8_t *)"", 0));
    // zły dowód (zmieniony bajt r)
    memcpy(m, good, len);
    m[r_len_at + 1] ^= 1;
    TEST_ASSERT_EQUAL(BRAMA_ERR_AUTH, read1_with(m, len));
}

static void steps_out_of_order_are_state_errors(void)
{
    brama_jpake_t srv;
    uint8_t out[512];
    size_t olen;
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_jpake_init(&srv, PIN, sizeof PIN));
    TEST_ASSERT_EQUAL(BRAMA_ERR_STATE, brama_jpake_write_round1(&srv, out, sizeof out, &olen));
    TEST_ASSERT_EQUAL(BRAMA_ERR_STATE, brama_jpake_write_round2(&srv, out, sizeof out, &olen));
    uint8_t s[32];
    TEST_ASSERT_EQUAL(BRAMA_ERR_STATE, brama_jpake_derive_secret(&srv, s));
    brama_jpake_free(&srv);
    brama_jpake_free(&srv); // podwójne zwolnienie jest bezpieczne
}

static void write_round1_fits_tight_buffer(void)
{
    // Runda 1 serwera ma ≤ 330 B (2 × (66 + 66 + 1 + r ≤ 32)); kontrola miejsca nie może rezerwować 65 B na r.
    vec_load("ecjpake/interop.txt");
    size_t len;
    const uint8_t *cli_one = vec("cli_one", &len);
    brama_jpake_t srv;
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_jpake_init(&srv, PIN, sizeof PIN));
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_jpake_read_round1(&srv, cli_one, len));
    uint8_t out[330];
    size_t olen;
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_jpake_write_round1(&srv, out, sizeof out, &olen));
    TEST_ASSERT_TRUE(olen <= 330);
    brama_jpake_free(&srv);
}

void run_ecjpake_tests(void)
{
    RUN_TEST(write_round1_fits_tight_buffer);
    RUN_TEST(psa_server_and_mbedtls_client_agree);
    RUN_TEST(wrong_pin_yields_different_secret);
    RUN_TEST(interop_server_accepts_kotlin_client_round1);
    RUN_TEST(pin_setup_key_matches_kotlin);
    RUN_TEST(pake_rejects_malformed_rounds);
    RUN_TEST(steps_out_of_order_are_state_errors);
}
