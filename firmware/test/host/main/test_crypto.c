#include <string.h>
#include "unity.h"
#include "brama_crypto.h"
#include "brama_err.h"
#include "test_registry.h"
#include "test_util.h"

static void hmac_rfc4231_case1(void)
{
    uint8_t key[20], out[32];
    memset(key, 0x0b, sizeof key);
    brama_hmac_sha256(key, sizeof key, (const uint8_t *)"Hi There", 8, out);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(hex(0, "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", NULL), out, 32);
}

static void hmac_rfc4231_case2(void)
{
    uint8_t out[32];
    const char *data = "what do ya want for nothing?";
    brama_hmac_sha256((const uint8_t *)"Jefe", 4, (const uint8_t *)data, strlen(data), out);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(hex(0, "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", NULL), out, 32);
}

static void hkdf_rfc5869_a1(void)
{
    uint8_t ikm[22], prk[32], okm[42];
    memset(ikm, 0x0b, sizeof ikm);
    size_t sl, il;
    const uint8_t *salt = hex(0, "000102030405060708090a0b0c", &sl);
    const uint8_t *info = hex(1, "f0f1f2f3f4f5f6f7f8f9", &il);
    brama_hkdf_extract(salt, sl, ikm, sizeof ikm, prk);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(hex(2, "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", NULL), prk, 32);
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_hkdf_expand(prk, info, il, okm, sizeof okm));
    TEST_ASSERT_EQUAL_HEX8_ARRAY(hex(3, "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", NULL), okm, 42);
}

static void gcm_zero_vector_16_2(void)
{
    uint8_t key[32] = {0}, nonce[12] = {0}, pt[16] = {0}, sealed[32], back[16];
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_gcm_seal(key, nonce, NULL, 0, pt, sizeof pt, sealed));
    TEST_ASSERT_EQUAL_HEX8_ARRAY(hex(0, "cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919", NULL), sealed, 32);
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_gcm_open(key, nonce, NULL, 0, sealed, sizeof sealed, back));
    TEST_ASSERT_EQUAL_HEX8_ARRAY(pt, back, 16);
}

static void gcm_open_rejects_tampered_tag_and_aad(void)
{
    uint8_t key[32] = {1}, nonce[12] = {2}, aad[3] = {9, 9, 9}, pt[5] = {1, 2, 3, 4, 5}, sealed[21], back[5];
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_gcm_seal(key, nonce, aad, sizeof aad, pt, sizeof pt, sealed));
    sealed[20] ^= 1;
    TEST_ASSERT_EQUAL(BRAMA_ERR_AUTH, brama_gcm_open(key, nonce, aad, sizeof aad, sealed, sizeof sealed, back));
    sealed[20] ^= 1;
    aad[0] ^= 1;
    TEST_ASSERT_EQUAL(BRAMA_ERR_AUTH, brama_gcm_open(key, nonce, aad, sizeof aad, sealed, sizeof sealed, back));
    TEST_ASSERT_EQUAL(BRAMA_ERR_AUTH, brama_gcm_open(key, nonce, aad, sizeof aad, sealed, 15, back));
}

static void mac_domain_separator(void)
{
    uint8_t key[32], out[32];
    const uint8_t x[3] = {1, 2, 3};
    memset(key, 7, sizeof key);
    brama_mac(key, "BRAMA/2/X", x, sizeof x, out);
    // Ta sama wartość co Crypto.mac w :protocol (HMAC(K, ASCII(D) || 0x00 || X)).
    TEST_ASSERT_EQUAL_HEX8_ARRAY(hex(0, "07f0dc96c4bfce1dc83f4c475a65a6436c8dc350c6c64f5f3ad2dad4781baa6f", NULL), out, 32);
}

static void sha256_incremental_matches_one_shot(void)
{
    uint8_t a[32], b[32];
    brama_sha256((const uint8_t *)"abcdef", 6, a);
    brama_sha256_ctx_t ctx;
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_sha256_start(&ctx));
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_sha256_update(&ctx, (const uint8_t *)"abc", 3));
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_sha256_update(&ctx, (const uint8_t *)"def", 3));
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_sha256_finish(&ctx, b));
    TEST_ASSERT_EQUAL_HEX8_ARRAY(a, b, 32);
    TEST_ASSERT_EQUAL_HEX8_ARRAY(hex(0, "bef57ec7f53a6d40beb640a780a639c83bc29ac8a9816f1fc6c5c6dcd93c4721", NULL), a, 32);
}

static void ct_equal_compares_content(void)
{
    TEST_ASSERT_TRUE(brama_ct_equal("ab", "ab", 2));
    TEST_ASSERT_FALSE(brama_ct_equal("ab", "ac", 2));
}

void run_crypto_tests(void)
{
    TEST_ASSERT_EQUAL(BRAMA_OK, brama_crypto_init());
    RUN_TEST(hmac_rfc4231_case1);
    RUN_TEST(hmac_rfc4231_case2);
    RUN_TEST(hkdf_rfc5869_a1);
    RUN_TEST(gcm_zero_vector_16_2);
    RUN_TEST(gcm_open_rejects_tampered_tag_and_aad);
    RUN_TEST(mac_domain_separator);
    RUN_TEST(sha256_incremental_matches_one_shot);
    RUN_TEST(ct_equal_compares_content);
}
