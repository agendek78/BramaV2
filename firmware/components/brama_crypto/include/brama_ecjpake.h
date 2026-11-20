#pragma once
// EC-JPAKE P-256/SHA-256 w roli serwera (11.4) na PSA PAKE, w formacie TLS jak mbedtls_ecjpake i `:protocol`:
// runda 1 = 2 × (ECPoint X, ECPoint V, r), runda 2 klienta = 1 × (…), runda 2 serwera = `03 00 17` || 1 × (…);
// ECPoint = len[1]=65 || 04 || x || y, r = len[1] || r (1–32 B, r < n). Kolejność wywołań jak w protokole:
// read_round1 → write_round1 → read_round2 → write_round2 → derive_secret. Błąd formatu lub ZKP: BRAMA_ERR_AUTH
// i kontekst bezużyteczny (wymaga nowego init). Kontekst wyłącznie w RAM; free zeruje.
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "psa/crypto.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct brama_jpake {
    psa_pake_operation_t op;
    psa_key_id_t password;
    int state;
} brama_jpake_t;

int brama_jpake_init(brama_jpake_t *j, const uint8_t *pin, size_t pin_len);
int brama_jpake_read_round1(brama_jpake_t *j, const uint8_t *p, size_t len);
int brama_jpake_write_round1(brama_jpake_t *j, uint8_t *out, size_t cap, size_t *olen);
int brama_jpake_read_round2(brama_jpake_t *j, const uint8_t *p, size_t len);
int brama_jpake_write_round2(brama_jpake_t *j, uint8_t *out, size_t cap, size_t *olen);
/** `S` = SHA-256(x(K)) — to samo co mbedtls_ecjpake_derive_secret. */
int brama_jpake_derive_secret(brama_jpake_t *j, uint8_t s[32]);
void brama_jpake_free(brama_jpake_t *j);

/** `K_pin = HKDF-Expand(HKDF-Extract(SHA-256(PT), S), "BRAMA/2/PAKE/setup", 32)` (11.4). */
void brama_pin_setup_key(const uint8_t s[32], const uint8_t *pt, size_t pt_len, uint8_t k_pin[32]);

#ifdef __cplusplus
}
#endif
