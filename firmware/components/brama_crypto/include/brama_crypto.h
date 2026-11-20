#pragma once
// Prymitywy protokołu (11.2, 12.3) na PSA Crypto. Wynik int: BRAMA_OK albo kod z brama_err.h.
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "psa/crypto.h"

#ifdef __cplusplus
extern "C" {
#endif

#define BRAMA_GCM_TAG_LEN 16
#define BRAMA_GCM_NONCE_LEN 12

int brama_crypto_init(void);

void brama_sha256(const uint8_t *data, size_t len, uint8_t out[32]);

/** SHA-256 przyrostowo (obraz OTA, 15.5). */
typedef struct {
    psa_hash_operation_t op;
} brama_sha256_ctx_t;
int brama_sha256_start(brama_sha256_ctx_t *ctx);
int brama_sha256_update(brama_sha256_ctx_t *ctx, const uint8_t *data, size_t len);
int brama_sha256_finish(brama_sha256_ctx_t *ctx, uint8_t out[32]);
void brama_sha256_abort(brama_sha256_ctx_t *ctx);

void brama_hmac_sha256(const uint8_t *key, size_t key_len, const uint8_t *data, size_t data_len, uint8_t out[32]);

/** `MAC(K, D, X) = HMAC-SHA-256(K, ASCII(D) || 0x00 || X)` (11.2). */
void brama_mac(const uint8_t key[32], const char *domain, const uint8_t *x, size_t x_len, uint8_t out[32]);

void brama_hkdf_extract(const uint8_t *salt, size_t salt_len, const uint8_t *ikm, size_t ikm_len, uint8_t prk[32]);
int brama_hkdf_expand(const uint8_t prk[32], const uint8_t *info, size_t info_len, uint8_t *out, size_t len);

/** AES-256-GCM: `out` = ciphertext || tag[16] (pt_len + 16 B). */
int brama_gcm_seal(const uint8_t key[32], const uint8_t nonce[12], const uint8_t *aad, size_t aad_len,
                   const uint8_t *pt, size_t pt_len, uint8_t *out);
/** `in` = ciphertext || tag[16]; BRAMA_ERR_AUTH przy złym tagu. `out` ma in_len − 16 B. */
int brama_gcm_open(const uint8_t key[32], const uint8_t nonce[12], const uint8_t *aad, size_t aad_len,
                   const uint8_t *in, size_t in_len, uint8_t *out);

bool brama_ct_equal(const void *a, const void *b, size_t len);

/** Zerowanie sekretów, którego kompilator nie usunie. */
void brama_zeroize(void *buf, size_t len);

#ifdef __cplusplus
}
#endif
