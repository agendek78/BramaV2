#include <string.h>
#include "brama_crypto.h"
#include "brama_err.h"
#include "mbedtls/platform_util.h"

int brama_crypto_init(void)
{
    return psa_crypto_init() == PSA_SUCCESS ? BRAMA_OK : BRAMA_ERR_CRYPTO;
}

void brama_zeroize(void *buf, size_t len)
{
    mbedtls_platform_zeroize(buf, len);
}

bool brama_ct_equal(const void *a, const void *b, size_t len)
{
    const uint8_t *x = a, *y = b;
    uint8_t diff = 0;
    for (size_t i = 0; i < len; i++) diff |= x[i] ^ y[i];
    return diff == 0;
}

void brama_sha256(const uint8_t *data, size_t len, uint8_t out[32])
{
    size_t olen = 0;
    if (psa_hash_compute(PSA_ALG_SHA_256, data, len, out, 32, &olen) != PSA_SUCCESS) memset(out, 0, 32);
}

int brama_sha256_start(brama_sha256_ctx_t *ctx)
{
    ctx->op = psa_hash_operation_init();
    return psa_hash_setup(&ctx->op, PSA_ALG_SHA_256) == PSA_SUCCESS ? BRAMA_OK : BRAMA_ERR_CRYPTO;
}

int brama_sha256_update(brama_sha256_ctx_t *ctx, const uint8_t *data, size_t len)
{
    return psa_hash_update(&ctx->op, data, len) == PSA_SUCCESS ? BRAMA_OK : BRAMA_ERR_CRYPTO;
}

int brama_sha256_finish(brama_sha256_ctx_t *ctx, uint8_t out[32])
{
    size_t olen = 0;
    return psa_hash_finish(&ctx->op, out, 32, &olen) == PSA_SUCCESS ? BRAMA_OK : BRAMA_ERR_CRYPTO;
}

void brama_sha256_abort(brama_sha256_ctx_t *ctx)
{
    psa_hash_abort(&ctx->op);
}

/** Klucz ulotny PSA; zwraca 0 przy błędzie. */
static psa_key_id_t import_key(const uint8_t *key, size_t len, psa_key_type_t type, psa_algorithm_t alg, psa_key_usage_t usage)
{
    psa_key_attributes_t attr = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&attr, type);
    psa_set_key_bits(&attr, PSA_BYTES_TO_BITS(len));
    psa_set_key_algorithm(&attr, alg);
    psa_set_key_usage_flags(&attr, usage);
    psa_key_id_t id = 0;
    if (psa_import_key(&attr, key, len, &id) != PSA_SUCCESS) return 0;
    return id;
}

/** HMAC-SHA-256 z dwóch części danych (domena MAC bez sklejania w buforze). */
static void hmac2(const uint8_t *key, size_t key_len, const uint8_t *a, size_t a_len, const uint8_t *b, size_t b_len,
                  uint8_t out[32])
{
    const psa_algorithm_t alg = PSA_ALG_HMAC(PSA_ALG_SHA_256);
    // HMAC dopuszcza klucz pusty (RFC 2104); PSA nie importuje klucza 0 B, więc zastępujemy go równoważnym blokiem zer.
    static const uint8_t zero_block[1] = {0};
    if (key_len == 0) {
        key = zero_block;
        key_len = 1;
    }
    psa_key_id_t id = import_key(key, key_len, PSA_KEY_TYPE_HMAC, alg, PSA_KEY_USAGE_SIGN_MESSAGE);
    psa_mac_operation_t op = PSA_MAC_OPERATION_INIT;
    size_t olen = 0;
    bool ok = id != 0 && psa_mac_sign_setup(&op, id, alg) == PSA_SUCCESS
              && psa_mac_update(&op, a, a_len) == PSA_SUCCESS
              && (b_len == 0 || psa_mac_update(&op, b, b_len) == PSA_SUCCESS)
              && psa_mac_sign_finish(&op, out, 32, &olen) == PSA_SUCCESS;
    if (!ok) {
        psa_mac_abort(&op);
        memset(out, 0, 32);
    }
    if (id) psa_destroy_key(id);
}

void brama_hmac_sha256(const uint8_t *key, size_t key_len, const uint8_t *data, size_t data_len, uint8_t out[32])
{
    hmac2(key, key_len, data, data_len, NULL, 0, out);
}

void brama_mac(const uint8_t key[32], const char *domain, const uint8_t *x, size_t x_len, uint8_t out[32])
{
    // ASCII(D) || 0x00 — terminator NUL łańcucha jest separatorem domeny.
    hmac2(key, 32, (const uint8_t *)domain, strlen(domain) + 1, x, x_len, out);
}

void brama_hkdf_extract(const uint8_t *salt, size_t salt_len, const uint8_t *ikm, size_t ikm_len, uint8_t prk[32])
{
    brama_hmac_sha256(salt, salt_len, ikm, ikm_len, prk);
}

int brama_hkdf_expand(const uint8_t prk[32], const uint8_t *info, size_t info_len, uint8_t *out, size_t len)
{
    if (len == 0 || len > 255 * 32 || info_len > 256) return BRAMA_ERR_ARG;
    uint8_t t[32];
    uint8_t input[32 + 256 + 1];
    size_t t_len = 0, offset = 0;
    for (uint8_t counter = 1; offset < len; counter++) {
        memcpy(input, t, t_len);
        memcpy(input + t_len, info, info_len);
        input[t_len + info_len] = counter;
        brama_hmac_sha256(prk, 32, input, t_len + info_len + 1, t);
        t_len = 32;
        size_t n = len - offset < 32 ? len - offset : 32;
        memcpy(out + offset, t, n);
        offset += n;
    }
    brama_zeroize(t, sizeof t);
    brama_zeroize(input, sizeof input);
    return BRAMA_OK;
}

static psa_key_id_t gcm_key(const uint8_t key[32], psa_key_usage_t usage)
{
    return import_key(key, 32, PSA_KEY_TYPE_AES, PSA_ALG_GCM, usage);
}

int brama_gcm_seal(const uint8_t key[32], const uint8_t nonce[12], const uint8_t *aad, size_t aad_len,
                   const uint8_t *pt, size_t pt_len, uint8_t *out)
{
    psa_key_id_t id = gcm_key(key, PSA_KEY_USAGE_ENCRYPT);
    if (!id) return BRAMA_ERR_CRYPTO;
    size_t olen = 0;
    // Pusty ładunek (np. PAIR_DONE): sprzętowy sterownik AES-GCM ESP-IDF odrzuca wskaźnik NULL ("No input supplied") nawet
    // przy długości 0, a wiadomość nie jest wtedy wysyłana. Podajemy niezerowy wskaźnik.
    static const uint8_t k_empty[1] = {0};
    if (!pt) pt = k_empty;
    psa_status_t st = psa_aead_encrypt(id, PSA_ALG_GCM, nonce, BRAMA_GCM_NONCE_LEN, aad, aad_len, pt, pt_len,
                                       out, pt_len + BRAMA_GCM_TAG_LEN, &olen);
    psa_destroy_key(id);
    return st == PSA_SUCCESS && olen == pt_len + BRAMA_GCM_TAG_LEN ? BRAMA_OK : BRAMA_ERR_CRYPTO;
}

int brama_gcm_open(const uint8_t key[32], const uint8_t nonce[12], const uint8_t *aad, size_t aad_len,
                   const uint8_t *in, size_t in_len, uint8_t *out)
{
    if (in_len < BRAMA_GCM_TAG_LEN) return BRAMA_ERR_AUTH;
    psa_key_id_t id = gcm_key(key, PSA_KEY_USAGE_DECRYPT);
    if (!id) return BRAMA_ERR_CRYPTO;
    size_t olen = 0;
    uint8_t sink[1]; // jak wyżej: sterownik odrzuca NULL także jako wyjście przy pustym ładunku
    if (!out && in_len == BRAMA_GCM_TAG_LEN) out = sink; // tylko pusty ładunek; przy niepustym NULL to błąd wywołującego
    psa_status_t st = psa_aead_decrypt(id, PSA_ALG_GCM, nonce, BRAMA_GCM_NONCE_LEN, aad, aad_len, in, in_len,
                                       out, in_len - BRAMA_GCM_TAG_LEN, &olen);
    psa_destroy_key(id);
    if (st == PSA_ERROR_INVALID_SIGNATURE) return BRAMA_ERR_AUTH;
    return st == PSA_SUCCESS ? BRAMA_OK : BRAMA_ERR_CRYPTO;
}
