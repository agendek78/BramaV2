#include <string.h>
#include "brama_crypto.h"
#include "host/ble_store.h"
#include "internal.h"

#define SEC_LEN 44 // key_size, ediv[2], rand[8], ltk[16], irk[16], flagi

static void sec_encode(const struct ble_store_value_sec *v, uint8_t *out)
{
    out[0] = v->key_size;
    memcpy(out + 1, &v->ediv, 2);
    memcpy(out + 3, &v->rand_num, 8);
    memcpy(out + 11, v->ltk, 16);
    memcpy(out + 27, v->irk, 16);
    out[43] = (uint8_t)(v->ltk_present | v->irk_present << 1 | v->authenticated << 2 | v->sc << 3);
}

static void sec_decode(const uint8_t *in, const ble_addr_t *peer, struct ble_store_value_sec *v)
{
    memset(v, 0, sizeof *v);
    v->peer_addr = *peer;
    v->key_size = in[0];
    memcpy(&v->ediv, in + 1, 2);
    memcpy(&v->rand_num, in + 3, 8);
    memcpy(v->ltk, in + 11, 16);
    memcpy(v->irk, in + 27, 16);
    v->ltk_present = in[43] & 1;
    v->irk_present = (in[43] >> 1) & 1;
    v->authenticated = (in[43] >> 2) & 1;
    v->sc = (in[43] >> 3) & 1;
}

static void bond_addr(const brama_bond_t *b, ble_addr_t *a)
{
    a->type = b->id_addr_type;
    memcpy(a->val, b->id_addr, 6);
}

/** Ponowne parowanie w oknie: stos nie widzi starego APPROVED tego peer, inaczej pętla RETRY w NimBLE byłaby
 *  nieskończona. Stary bond pozostaje w obrazie i wraca po rozłączeniu bez commitu (9.5). */
static bool hidden_by_repair(const ble_addr_t *a)
{
    for (int i = 0; i < BRAMA_MAX_LINKS; i++) {
        if (g_links[i].used && g_links[i].repairing && ble_addr_cmp(&g_links[i].peer_id, a) == 0) return true;
    }
    return false;
}

// Funkcja wewnętrzna NimBLE (ble_hs_pvcy_priv.h): czy adres prywatny (RPA) został wygenerowany z danego IRK.
extern bool ble_hs_pvcy_resolve_with_irk(const uint8_t rpa[6], const uint8_t irk[16]);

/** Adres prywatny rozwiązywalny (RPA): typ losowy, dwa najstarsze bity 01. */
static bool is_rpa(const ble_addr_t *a)
{
    return a->type == BLE_ADDR_RANDOM && (a->val[5] & 0xC0) == 0x40;
}

/** Czy `rpa` pochodzi z IRK zapisanego w bondzie (część PEER_SEC). Kolejność bajtów IRK w magazynie bywa różnie
 *  interpretowana w NimBLE, więc próbujemy obu; ryzyko fałszywego dopasowania to ok. 2^-24 na bond. */
static bool bond_irk_matches(const brama_bond_t *b, const ble_addr_t *rpa)
{
    struct ble_store_value_sec v;
    ble_addr_t any = {0};
    sec_decode(b->data + SEC_LEN, &any, &v);
    bool ok = false;
    if (v.irk_present) {
        uint8_t swapped[16];
        for (int i = 0; i < 16; i++) swapped[i] = v.irk[15 - i];
        ok = ble_hs_pvcy_resolve_with_irk(rpa->val, v.irk) || ble_hs_pvcy_resolve_with_irk(rpa->val, swapped);
        brama_zeroize(swapped, sizeof swapped);
    }
    brama_zeroize(&v, sizeof v);
    return ok;
}

/** Wyszukanie bondu: staging łączy (CANDIDATE/naprawa) ma pierwszeństwo przed zatwierdzonymi z obrazu. */
static const brama_bond_t *find_bond(const ble_addr_t *peer, int idx)
{
    int n = 0;
    for (int i = 0; i < BRAMA_MAX_LINKS; i++) {
        const brama_link_ctx_t *l = &g_links[i];
        if (!l->used || !l->has_staged) continue;
        ble_addr_t a;
        bond_addr(&l->staged, &a);
        if (ble_addr_cmp(peer, BLE_ADDR_ANY) == 0 ? n++ == idx : ble_addr_cmp(peer, &a) == 0) return &l->staged;
    }
    const brama_image_t *img = &g_cfg.actor->image;
    for (int i = 0; i < img->count; i++) {
        const brama_bond_t *b = &img->clients[i].bond;
        if (!b->len) continue;
        ble_addr_t a;
        bond_addr(b, &a);
        if (hidden_by_repair(&a)) continue;
        if (ble_addr_cmp(peer, BLE_ADDR_ANY) == 0 ? n++ == idx : ble_addr_cmp(peer, &a) == 0) return b;
    }
    // Kontroler nie rozwiązał RPA telefonu (id = ota): rozwiązujemy go sami IRK-iem z bondów obrazu.
    if (ble_addr_cmp(peer, BLE_ADDR_ANY) != 0 && is_rpa(peer)) {
        for (int i = 0; i < img->count; i++) {
            const brama_bond_t *b = &img->clients[i].bond;
            if (!b->len) continue;
            ble_addr_t a;
            bond_addr(b, &a);
            if (hidden_by_repair(&a)) continue;
            if (bond_irk_matches(b, peer)) return b;
        }
    }
    return NULL;
}

bool brama_ble_peer_bonded(const ble_addr_t *ota)
{
    return find_bond(ota, 0) != NULL;
}

bool brama_ble_resolve_identity(const ble_addr_t *ota, ble_addr_t *identity)
{
    *identity = *ota;
    if (!is_rpa(ota)) return false;
    const brama_image_t *img = &g_cfg.actor->image;
    for (int i = 0; i < img->count; i++) {
        const brama_bond_t *b = &img->clients[i].bond;
        if (!b->len) continue;
        if (bond_irk_matches(b, ota)) {
            bond_addr(b, identity);
            return true;
        }
    }
    return false;
}

int brama_ble_store_read(int obj_type, const void *key, void *value)
{
    if (obj_type != BLE_STORE_OBJ_TYPE_OUR_SEC && obj_type != BLE_STORE_OBJ_TYPE_PEER_SEC) return BLE_HS_ENOENT; // CCCD nie jest utrwalany
    const struct ble_store_key_sec *k = key;
    brama_ble_lock();
    const brama_bond_t *b = find_bond(&k->peer_addr, k->idx);
    if (b) {
        ble_addr_t a;
        bond_addr(b, &a);
        sec_decode(b->data + (obj_type == BLE_STORE_OBJ_TYPE_OUR_SEC ? 0 : SEC_LEN), &a, &((union ble_store_value *)value)->sec);
    }
    brama_ble_unlock();
    return b ? 0 : BLE_HS_ENOENT;
}

int brama_ble_store_write(int obj_type, const void *value)
{
    if (obj_type != BLE_STORE_OBJ_TYPE_OUR_SEC && obj_type != BLE_STORE_OBJ_TYPE_PEER_SEC) return 0; // CCCD: bez zapisu
    const struct ble_store_value_sec *v = &((const union ble_store_value *)value)->sec;
    brama_ble_lock();
    // Bond trafia wyłącznie do staging łącza; NVS zapisuje dopiero commit aktora (9.5, A59).
    // NimBLE zapisuje klucze przed zdarzeniem ENC_CHANGE: łącze rozpoznajemy po połączeniu, nie po on_encrypted.
    for (int i = 0; i < BRAMA_MAX_LINKS; i++) {
        brama_link_ctx_t *l = &g_links[i];
        struct ble_gap_conn_desc d;
        if (!l->used || ble_gap_conn_find(l->conn, &d) != 0 || ble_addr_cmp(&d.peer_id_addr, &v->peer_addr) != 0) continue;
        l->peer_id = d.peer_id_addr;
        if (!l->has_staged) {
            memset(&l->staged, 0, sizeof l->staged);
            l->staged.id_addr_type = v->peer_addr.type;
            memcpy(l->staged.id_addr, v->peer_addr.val, 6);
            l->staged.len = 2 * SEC_LEN;
            l->has_staged = true;
        }
        sec_encode(v, l->staged.data + (obj_type == BLE_STORE_OBJ_TYPE_OUR_SEC ? 0 : SEC_LEN));
        break;
    }
    brama_ble_unlock();
    return 0;
}

int brama_ble_store_delete(int obj_type, const void *key)
{
    (void)key;
    if (obj_type != BLE_STORE_OBJ_TYPE_OUR_SEC && obj_type != BLE_STORE_OBJ_TYPE_PEER_SEC) return 0;
    // Zatwierdzone bondy usuwa wyłącznie reset/commit aktora; stos może skasować najwyżej staging.
    const struct ble_store_key_sec *k = key;
    brama_ble_lock();
    for (int i = 0; i < BRAMA_MAX_LINKS; i++) {
        brama_link_ctx_t *l = &g_links[i];
        ble_addr_t a;
        if (!l->has_staged) continue;
        a.type = l->staged.id_addr_type;
        memcpy(a.val, l->staged.id_addr, 6);
        if (ble_addr_cmp(&k->peer_addr, &a) == 0) {
            l->has_staged = false;
            brama_zeroize(&l->staged, sizeof l->staged);
        }
    }
    brama_ble_unlock();
    return 0;
}

_Static_assert(2 * SEC_LEN <= BRAMA_BOND_DATA_MAX, "bond nie mieści się w brama_bond_t");
