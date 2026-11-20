#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "bootloader_random.h"
#include "brama_auth.h"
#include "brama_boot.h"
#include "brama_crypto.h"
#include "brama_err.h"
#include "brama_hw.h"
#include "brama_rule.h"
#include "esp_app_desc.h"
#include "esp_console.h"
#include "esp_random.h"
#include "esp_heap_caps.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "service_console.h"

static brama_nvs_t *s_nvs;

static void rng_fill(void *ctx, uint8_t *buf, size_t len)
{
    (void)ctx;
    esp_fill_random(buf, len); // przy włączonym bootloader_random — pełna entropia bez RF (15.3)
}

static void print_hex(const uint8_t *b, size_t n)
{
    for (size_t i = 0; i < n; i++) printf("%02x", b[i]);
}

static void print_qr(const brama_factory_t *f)
{
    char qr[160];
    if (brama_qr_text(f->device_id, f->k_setup, f->pin, qr, sizeof qr) != 0) return;
    // Wyłącznie przez złącze serwisowe do stanowiska drukującego etykietę; bez logowania (15.4).
    printf("QR %s\nPIN %.6s\n", qr, (const char *)f->pin);
    brama_zeroize(qr, sizeof qr);
}

/** Kopia reguły w RTC: DISARMED, aby restart nie odtworzył reguły klienta, którego już nie ma (15.4). */
static void disarm_rtc(uint64_t auth_generation)
{
    brama_rule_t r;
    memset(&r, 0, sizeof r);
    r.phase = BRAMA_PHASE_DISARMED;
    r.auth_generation = auth_generation;
    brama_rtc_copy_t c;
    brama_clock_t mono = brama_hw_clock(), rtc = brama_hw_rtc_clock();
    brama_rule_to_rtc(&r, mono.now_us(mono.ctx), rtc.now_us(rtc.ctx), &c);
    brama_rtc_t port = brama_hw_rtc();
    port.store(port.ctx, &c, sizeof c);
}

/** Nowe dane fabryczne, pusty obraz i (przy reprovisioningu) auth_generation + 1; reguła DISARMED bez impulsu. */
static int do_provision(bool re)
{
    brama_factory_t f, old;
    int rc = brama_factory_load(s_nvs, &old);
    if (!re && rc != BRAMA_ERR_STORAGE) {
        printf("Dane fabryczne istnieją — użyj reprovision\n");
        return 1;
    }
    brama_zeroize(&old, sizeof old); // ponowny provisioning zawsze dozwolony przy fizycznym dostępie (15.4)
    bootloader_random_enable();
    brama_rng_t rng = {rng_fill, NULL};
    brama_factory_generate(&rng, &f);
    bootloader_random_disable();
    brama_image_t cur, next;
    if (brama_image_load(s_nvs, &cur) == BRAMA_IMG_OK) {
        if (brama_image_reset(&cur, &next) != 0) return 1;
    } else {
        brama_image_init_empty(&next);
    }
    brama_localcfg_t cfg = {500, false, 50};
    brama_localcfg_t existing;
    if (brama_localcfg_load(s_nvs, &existing) == 0) cfg = existing;
    if (brama_image_commit(s_nvs, &next) != 0 || brama_factory_save(s_nvs, &f) != 0
        || brama_localcfg_save(s_nvs, &cfg) != 0) {
        printf("Błąd zapisu NVS\n");
        return 1;
    }
    disarm_rtc(next.auth_generation);
    print_qr(&f);
    brama_zeroize(&f, sizeof f);
    return 0;
}

static int cmd_provision(int argc, char **argv) { (void)argc, (void)argv; return do_provision(false); }
static int cmd_reprovision(int argc, char **argv) { (void)argc, (void)argv; return do_provision(true); }

static int cmd_config(int argc, char **argv)
{
    brama_localcfg_t c = {500, false, 50};
    brama_localcfg_load(s_nvs, &c);
    if (argc == 3 && strcmp(argv[1], "pulse") == 0) c.pulse_ms = (uint16_t)atoi(argv[2]);
    else if (argc == 3 && strcmp(argv[1], "interlock") == 0) c.interlock_enabled = strcmp(argv[2], "on") == 0;
    else {
        printf("config pulse <100-1000> | config interlock on|off\n");
        return 1;
    }
    int rc = brama_localcfg_save(s_nvs, &c); // walidacja przy zapisie (15.4)
    printf(rc == 0 ? "OK\n" : "Wartość poza zakresem\n");
    return rc != 0;
}

static int cmd_lock(int argc, char **argv)
{
    (void)argc, (void)argv;
    brama_factory_t f;
    if (brama_factory_load(s_nvs, &f) != 0) return 1;
    f.provisioning_locked = true;
    int rc = brama_factory_save(s_nvs, &f);
    brama_zeroize(&f, sizeof f);
    return rc != 0;
}

/** Wartość szesnastkowa (parzysta liczba cyfr) → bajty; -1 przy błędzie. */
static int parse_hex_prefix(const char *s, uint8_t *out, size_t max)
{
    size_t n = strlen(s);
    if (n == 0 || n % 2 != 0 || n / 2 > max) return -1;
    for (size_t i = 0; i < n; i += 2) {
        char pair[3] = {s[i], s[i + 1], 0};
        char *end;
        long v = strtol(pair, &end, 16);
        if (*end != 0 || !((s[i] >= '0' && s[i] <= '9') || (s[i] >= 'a' && s[i] <= 'f') || (s[i] >= 'A' && s[i] <= 'F'))
            || !((s[i + 1] >= '0' && s[i + 1] <= '9') || (s[i + 1] >= 'a' && s[i + 1] <= 'f') || (s[i + 1] >= 'A' && s[i + 1] <= 'F'))) {
            return -1;
        }
        out[i / 2] = (uint8_t)v;
    }
    return (int)(n / 2);
}

/**
 * Usuwa jednego klienta (numer z `info` albo początek client_id, min. 4 cyfry szesnastkowe). Jak reset (9.6), ale tylko ten klient:
 * auth_generation + 1 (unieważnia sesje i kopię reguły w RTC — mogła należeć do usuwanego), reguła w RTC DISARMED. Działa poza
 * aktorem, bez blokady; po nim obowiązuje `reboot` (jak po `config`).
 */
static int cmd_remove_client(int argc, char **argv)
{
    if (argc != 2) {
        printf("remove-client <numer z info | początek client_id (min. 4 cyfry hex)>\n");
        return 1;
    }
    brama_nvs_t nvs = brama_hw_nvs();
    brama_image_t cur, next;
    if (brama_image_load(&nvs, &cur) != BRAMA_IMG_OK) {
        printf("Brak poprawnego obrazu autoryzacji\n");
        return 1;
    }
    int idx = -1, hits = 0;
    size_t len = strlen(argv[1]);
    if (len <= 2 && len > 0 && strspn(argv[1], "0123456789") == len) {
        idx = atoi(argv[1]);
        hits = idx < cur.count ? 1 : 0;
    } else {
        uint8_t prefix[16];
        int n = len >= 4 ? parse_hex_prefix(argv[1], prefix, sizeof prefix) : -1;
        if (n < 0) {
            printf("Nieprawidłowy wybór: numer (0-%d) albo min. 4 cyfry hex client_id\n", BRAMA_MAX_CLIENTS - 1);
            brama_zeroize(&cur, sizeof cur);
            return 1;
        }
        for (int i = 0; i < cur.count; i++) {
            if (memcmp(cur.clients[i].client_id, prefix, (size_t)n) == 0) {
                idx = i;
                hits++;
            }
        }
    }
    if (hits != 1 || idx < 0 || idx >= cur.count) {
        printf(hits > 1 ? "Niejednoznaczny wybór (podaj dłuższy początek client_id)\n" : "Nie ma takiego klienta (zobacz info)\n");
        brama_zeroize(&cur, sizeof cur);
        return 1;
    }
    uint8_t removed[16];
    memcpy(removed, cur.clients[idx].client_id, sizeof removed);
    int rc = brama_image_next(&cur, &next);
    if (rc == 0) rc = brama_image_remove_client(&next, idx);
    if (rc == 0 && next.auth_generation == UINT64_MAX) rc = BRAMA_ERR_NO_SPACE;
    if (rc == 0) {
        next.auth_generation++;
        rc = brama_image_commit(&nvs, &next);
    }
    if (rc != 0) {
        printf("Błąd zapisu NVS (%d)\n", rc);
    } else {
        disarm_rtc(next.auth_generation);
        printf("Usunięto klienta ");
        print_hex(removed, sizeof removed);
        printf(" (pozostało %d). Wykonaj reboot.\n", next.count);
    }
    brama_zeroize(&cur, sizeof cur);
    brama_zeroize(&next, sizeof next);
    return rc != 0;
}

// Zasoby (16.11.2026): sterta i najmniejszy zapas stosu zadań; pomiar RAM przy wielu łączach.
static int cmd_mem(int argc, char **argv)
{
    (void)argc, (void)argv;
    printf("sterta wolna=%u min=%u największy blok=%u\n", (unsigned)esp_get_free_heap_size(), (unsigned)esp_get_minimum_free_heap_size(),
           (unsigned)heap_caps_get_largest_free_block(MALLOC_CAP_8BIT));
    static const char *const names[] = {"actor", "ui", "pake_worker", "ota_worker", "nimble_host", "console", "main", "ipc0", "btController"};
    for (size_t i = 0; i < sizeof names / sizeof names[0]; i++) {
        TaskHandle_t h = xTaskGetHandle(names[i]);
        if (h) printf("  stos %-12s wolny min=%u B\n", names[i], (unsigned)uxTaskGetStackHighWaterMark(h));
    }
    return 0;
}

static int cmd_info(int argc, char **argv)
{
    (void)argc, (void)argv;
    brama_nvs_t nvs = brama_hw_nvs();
    brama_image_t img;
    brama_image_status_t st = brama_image_load(&nvs, &img);
    printf("firmware %s, obraz %d, klienci %d, auth_generation %llu, zapisy NVS %lu\n",
           esp_app_get_description()->version, st, st == BRAMA_IMG_OK ? img.count : 0,
           st == BRAMA_IMG_OK ? (unsigned long long)img.auth_generation : 0ULL, (unsigned long)brama_hw_nvs_writes());
    if (st == BRAMA_IMG_OK) {
        // Bez K_client i danych bondu (klucze stosu BLE): tylko identyfikatory do wskazania klienta w `remove-client`.
        for (int i = 0; i < img.count; i++) {
            const brama_client_rec_t *c = &img.clients[i];
            printf("  [%d] client_id ", i);
            print_hex(c->client_id, sizeof c->client_id);
            if (c->bond.len) {
                printf("  bond %02x:%02x:%02x:%02x:%02x:%02x typ=%u\n", c->bond.id_addr[5], c->bond.id_addr[4], c->bond.id_addr[3],
                       c->bond.id_addr[2], c->bond.id_addr[1], c->bond.id_addr[0], c->bond.id_addr_type);
            } else {
                printf("  bond brak\n");
            }
            if (c->name_len) printf("      nazwa \"%.*s\"\n", (int)c->name_len, (const char *)c->name);
        }
    }
    brama_zeroize(&img, sizeof img);
    int pin_left;
    bool window;
    if (brama_app_pin_status(&pin_left, &window)) {
        // Licznik jest globalny: po starcie wyczerpany do przycisku (albo okna z telefonu po godzinie pracy), zob. phone_window_design §6.2.
        printf("rejestracja PIN-em: %s, próby PIN %d/%d, okno %s\n", pin_left > 0 ? "dostępna" : "niedostępna", pin_left,
               BRAMA_PIN_FAILURES_PER_WINDOW, window ? "otwarte" : "zamknięte");
    }
    // Decyzja właściciela 12.11.2026: dostęp do UART = dostęp do QR i PIN-u (spec 15.4); stąd `info` ujawnia sekrety.
    brama_factory_t f;
    if (brama_factory_load(s_nvs, &f) == 0) {
        printf("device_id %02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x\n", f.device_id[0], f.device_id[1],
               f.device_id[2], f.device_id[3], f.device_id[4], f.device_id[5], f.device_id[6], f.device_id[7], f.device_id[8],
               f.device_id[9], f.device_id[10], f.device_id[11], f.device_id[12], f.device_id[13], f.device_id[14],
               f.device_id[15]);
        print_qr(&f);
    } else {
        printf("Brak danych fabrycznych\n");
    }
    brama_zeroize(&f, sizeof f);
    return 0;
}

static int cmd_reboot(int argc, char **argv)
{
    (void)argc, (void)argv;
    esp_restart();
    return 0;
}

static void start_repl(void)
{
    esp_console_repl_t *repl = NULL;
    esp_console_repl_config_t rc = ESP_CONSOLE_REPL_CONFIG_DEFAULT();
    rc.prompt = "brama>";
    rc.task_stack_size = 8192; // do_provision trzyma na stosie dwa brama_image_t (8 klientów z bondami) + brama_factory_t; domyślne 4096 nie wystarcza
    esp_console_dev_uart_config_t uc = ESP_CONSOLE_DEV_UART_CONFIG_DEFAULT();
    if (esp_console_new_repl_uart(&uc, &rc, &repl) == ESP_OK) esp_console_start_repl(repl);
}

static void reg(const char *name, const char *help, esp_console_cmd_func_t fn)
{
    esp_console_cmd_t c = {.command = name, .help = help, .func = fn};
    esp_console_cmd_register(&c);
}

void brama_service_console_run(brama_nvs_t *nvs, const char *banner)
{
    static brama_nvs_t nvs_copy;
    nvs_copy = *nvs;
    s_nvs = &nvs_copy;
    brama_factory_t f;
    bool locked = brama_factory_load(s_nvs, &f) == 0 && f.provisioning_locked;
    brama_zeroize(&f, sizeof f);
    if (!locked) reg("provision", "Provisioning fabryczny: device_id, K_setup, PIN, QR", cmd_provision);
    reg("reprovision", "Ponowny provisioning: nowe sekrety, usunięcie wszystkich telefonów", cmd_reprovision);
    reg("config", "Konfiguracja lokalna: pulse <ms> | interlock on|off", cmd_config);
    reg("lock-provisioning", "Blokada trybu provisioningu", cmd_lock);
    reg("mem", "Sterta i zapas stosów zadań", cmd_mem);
    reg("info", "Stan sterownika, lista klientów, device_id, QR i PIN", cmd_info);
    reg("remove-client", "Usuń klienta: remove-client <numer z info | początek client_id>", cmd_remove_client);
    reg("reboot", "Restart", cmd_reboot);
    printf("%s\n", banner);
    start_repl();
}
