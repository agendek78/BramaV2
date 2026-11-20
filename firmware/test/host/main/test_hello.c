#include "unity.h"
#include "brama_ports.h"
#include "test_registry.h"
#include "psa/crypto.h"

static void hello_ports_header_compiles(void)
{
    TEST_ASSERT_EQUAL(1, BRAMA_OUTPUT_CLOSE);
}

static void hello_psa_sha256_available(void)
{
    TEST_ASSERT_EQUAL(PSA_SUCCESS, psa_crypto_init());
    uint8_t out[32];
    size_t len = 0;
    TEST_ASSERT_EQUAL(PSA_SUCCESS, psa_hash_compute(PSA_ALG_SHA_256, (const uint8_t *)"abc", 3, out, sizeof out, &len));
    TEST_ASSERT_EQUAL_HEX8(0xba, out[0]);
    TEST_ASSERT_EQUAL_HEX8(0xad, out[31]);
}

void run_hello_tests(void)
{
    RUN_TEST(hello_ports_header_compiles);
    RUN_TEST(hello_psa_sha256_available);
}
