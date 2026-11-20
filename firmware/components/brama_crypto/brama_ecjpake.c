#include <string.h>
#include "brama_crypto.h"
#include "brama_ecjpake.h"
#include "brama_err.h"

#define POINT_LEN 65
#define SCALAR_LEN 32

enum { S_FREE = 0, S_INIT, S_R1_READ, S_R1_WRITTEN, S_R2_READ, S_R2_WRITTEN, S_FAILED };

/** Rząd grupy P-256 (n), big-endian. */
static const uint8_t P256_N[SCALAR_LEN] = {
    0xff, 0xff, 0xff, 0xff, 0x00, 0x00, 0x00, 0x00, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
    0xbc, 0xe6, 0xfa, 0xad, 0xa7, 0x17, 0x9e, 0x84, 0xf3, 0xb9, 0xca, 0xc2, 0xfc, 0x63, 0x25, 0x51,
};

static const uint8_t EC_PARAMETERS[3] = {0x03, 0x00, 0x17}; // named_curve, secp256r1

int brama_jpake_init(brama_jpake_t *j, const uint8_t *pin, size_t pin_len)
{
    memset(j, 0, sizeof *j);
    j->op = psa_pake_operation_init();
    psa_key_attributes_t attr = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&attr, PSA_KEY_TYPE_PASSWORD);
    psa_set_key_usage_flags(&attr, PSA_KEY_USAGE_DERIVE);
    psa_set_key_algorithm(&attr, PSA_ALG_JPAKE(PSA_ALG_SHA_256));
    if (psa_import_key(&attr, pin, pin_len, &j->password) != PSA_SUCCESS) return BRAMA_ERR_CRYPTO;

    psa_pake_cipher_suite_t cs = psa_pake_cipher_suite_init();
    psa_pake_cs_set_algorithm(&cs, PSA_ALG_JPAKE(PSA_ALG_SHA_256));
    psa_pake_cs_set_primitive(&cs, PSA_PAKE_PRIMITIVE(PSA_PAKE_PRIMITIVE_TYPE_ECC, PSA_ECC_FAMILY_SECP_R1, 256));
    // Identyfikatory ról jak w mbedtls_ecjpake: serwer "server", klient "client".
    if (psa_pake_setup(&j->op, j->password, &cs) != PSA_SUCCESS
        || psa_pake_set_user(&j->op, (const uint8_t *)"server", 6) != PSA_SUCCESS
        || psa_pake_set_peer(&j->op, (const uint8_t *)"client", 6) != PSA_SUCCESS) {
        brama_jpake_free(j);
        return BRAMA_ERR_CRYPTO;
    }
    j->state = S_INIT;
    return BRAMA_OK;
}

void brama_jpake_free(brama_jpake_t *j)
{
    if (j->state != S_FREE) psa_pake_abort(&j->op);
    if (j->password) psa_destroy_key(j->password);
    brama_zeroize(j, sizeof *j);
}

static int fail(brama_jpake_t *j)
{
    psa_pake_abort(&j->op);
    j->state = S_FAILED;
    return BRAMA_ERR_AUTH;
}

/** Czytelnik formatu TLS z kontrolą granic. */
typedef struct {
    const uint8_t *p;
    size_t len, pos;
} reader_t;

static bool read_point(reader_t *r, const uint8_t **point)
{
    if (r->pos + 1 + POINT_LEN > r->len || r->p[r->pos] != POINT_LEN || r->p[r->pos + 1] != 0x04) return false;
    *point = r->p + r->pos + 1;
    r->pos += 1 + POINT_LEN;
    return true;
}

static bool scalar_below_n(const uint8_t *s, size_t len)
{
    uint8_t padded[SCALAR_LEN] = {0};
    memcpy(padded + SCALAR_LEN - len, s, len);
    return memcmp(padded, P256_N, SCALAR_LEN) < 0;
}

static bool read_scalar(reader_t *r, const uint8_t **s, size_t *s_len)
{
    if (r->pos + 1 > r->len) return false;
    size_t n = r->p[r->pos];
    if (n < 1 || n > SCALAR_LEN || r->pos + 1 + n > r->len) return false;
    *s = r->p + r->pos + 1;
    *s_len = n;
    r->pos += 1 + n;
    return scalar_below_n(*s, n);
}

/** Wczytuje `count` trójek (X, V, r) do PSA. Format sprawdzany w całości przed pierwszym wejściem PSA. */
static int input_triples(brama_jpake_t *j, const uint8_t *p, size_t len, int count)
{
    const uint8_t *x[2], *v[2], *rr[2];
    size_t rl[2];
    reader_t r = {p, len, 0};
    for (int i = 0; i < count; i++) {
        if (!read_point(&r, &x[i]) || !read_point(&r, &v[i]) || !read_scalar(&r, &rr[i], &rl[i])) return fail(j);
    }
    if (r.pos != len) return fail(j);
    for (int i = 0; i < count; i++) {
        if (psa_pake_input(&j->op, PSA_PAKE_STEP_KEY_SHARE, x[i], POINT_LEN) != PSA_SUCCESS
            || psa_pake_input(&j->op, PSA_PAKE_STEP_ZK_PUBLIC, v[i], POINT_LEN) != PSA_SUCCESS
            || psa_pake_input(&j->op, PSA_PAKE_STEP_ZK_PROOF, rr[i], rl[i]) != PSA_SUCCESS) {
            return fail(j);
        }
    }
    return BRAMA_OK;
}

/** Zapisuje `count` trójek z PSA, każdą część poprzedzoną bajtem długości. */
static int output_triples(brama_jpake_t *j, uint8_t *out, size_t cap, size_t *pos, int count)
{
    static const psa_pake_step_t steps[3] = {PSA_PAKE_STEP_KEY_SHARE, PSA_PAKE_STEP_ZK_PUBLIC, PSA_PAKE_STEP_ZK_PROOF};
    for (int i = 0; i < count; i++) {
        for (int s = 0; s < 3; s++) {
            if (*pos + 1 + (steps[s] == PSA_PAKE_STEP_ZK_PROOF ? SCALAR_LEN : POINT_LEN) > cap) return BRAMA_ERR_ARG;
            size_t n = 0;
            if (psa_pake_output(&j->op, steps[s], out + *pos + 1, cap - *pos - 1, &n) != PSA_SUCCESS || n > 255) {
                psa_pake_abort(&j->op);
                j->state = S_FAILED;
                return BRAMA_ERR_CRYPTO;
            }
            out[*pos] = (uint8_t)n;
            *pos += 1 + n;
        }
    }
    return BRAMA_OK;
}

int brama_jpake_read_round1(brama_jpake_t *j, const uint8_t *p, size_t len)
{
    if (j->state != S_INIT) return BRAMA_ERR_STATE;
    int rc = input_triples(j, p, len, 2);
    if (rc == BRAMA_OK) j->state = S_R1_READ;
    return rc;
}

int brama_jpake_write_round1(brama_jpake_t *j, uint8_t *out, size_t cap, size_t *olen)
{
    if (j->state != S_R1_READ) return BRAMA_ERR_STATE;
    size_t pos = 0;
    int rc = output_triples(j, out, cap, &pos, 2);
    if (rc != BRAMA_OK) return rc;
    *olen = pos;
    j->state = S_R1_WRITTEN;
    return BRAMA_OK;
}

int brama_jpake_read_round2(brama_jpake_t *j, const uint8_t *p, size_t len)
{
    if (j->state != S_R1_WRITTEN) return BRAMA_ERR_STATE;
    int rc = input_triples(j, p, len, 1);
    if (rc == BRAMA_OK) j->state = S_R2_READ;
    return rc;
}

int brama_jpake_write_round2(brama_jpake_t *j, uint8_t *out, size_t cap, size_t *olen)
{
    if (j->state != S_R2_READ) return BRAMA_ERR_STATE;
    if (cap < sizeof EC_PARAMETERS) return BRAMA_ERR_ARG;
    memcpy(out, EC_PARAMETERS, sizeof EC_PARAMETERS);
    size_t pos = sizeof EC_PARAMETERS;
    int rc = output_triples(j, out, cap, &pos, 1);
    if (rc != BRAMA_OK) return rc;
    *olen = pos;
    j->state = S_R2_WRITTEN;
    return BRAMA_OK;
}

int brama_jpake_derive_secret(brama_jpake_t *j, uint8_t s[32])
{
    if (j->state != S_R2_WRITTEN) return BRAMA_ERR_STATE;
    psa_key_attributes_t attr = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&attr, PSA_KEY_TYPE_DERIVE);
    psa_set_key_usage_flags(&attr, PSA_KEY_USAGE_EXPORT);
    psa_key_id_t k = 0;
    // PSA zwraca punkt K (format niekompresowany); S = SHA-256(x(K)) jak mbedtls_ecjpake_derive_secret.
    psa_status_t st = psa_pake_get_shared_key(&j->op, &attr, &k);
    j->state = S_FAILED; // operacja PSA nieaktywna po pobraniu klucza
    if (st != PSA_SUCCESS) return BRAMA_ERR_CRYPTO;
    uint8_t point[POINT_LEN];
    size_t n = 0;
    st = psa_export_key(k, point, sizeof point, &n);
    psa_destroy_key(k);
    if (st != PSA_SUCCESS || n != POINT_LEN || point[0] != 0x04) {
        brama_zeroize(point, sizeof point);
        return BRAMA_ERR_CRYPTO;
    }
    brama_sha256(point + 1, SCALAR_LEN, s);
    brama_zeroize(point, sizeof point);
    return BRAMA_OK;
}

void brama_pin_setup_key(const uint8_t s[32], const uint8_t *pt, size_t pt_len, uint8_t k_pin[32])
{
    uint8_t salt[32], prk[32];
    static const char INFO[] = "BRAMA/2/PAKE/setup";
    brama_sha256(pt, pt_len, salt);
    brama_hkdf_extract(salt, sizeof salt, s, 32, prk);
    brama_hkdf_expand(prk, (const uint8_t *)INFO, sizeof INFO - 1, k_pin, 32);
    brama_zeroize(prk, sizeof prk);
}
