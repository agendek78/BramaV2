#include <stddef.h>
#include <string.h>
#include "brama_rule.h"

enum { RTC_DISARMED = 0, RTC_ARMED = 1, RTC_FAULT = 2 };

uint32_t brama_crc32(const void *data, size_t len)
{
    const uint8_t *p = data;
    uint32_t crc = 0xffffffffu;
    for (size_t i = 0; i < len; i++) {
        crc ^= p[i];
        for (int k = 0; k < 8; k++) crc = (crc >> 1) ^ (0xedb88320u & (0u - (crc & 1u)));
    }
    return ~crc;
}

static bool same_owner(const brama_rule_t *r, const uint8_t *id)
{
    return r->has_owner && id && memcmp(r->owner_id, id, 16) == 0;
}

/** Ustawia fazę, przyczynę i właściciela; revision rośnie przy każdej zmianie (12.2). */
static bool set(brama_rule_t *r, uint8_t phase, uint8_t reason, const uint8_t *owner)
{
    bool changed = r->phase != phase || r->reason != reason || r->has_owner != (owner != NULL)
                   || (owner && memcmp(r->owner_id, owner, 16) != 0);
    uint8_t id[16] = {0};
    if (owner) memcpy(id, owner, 16);
    r->phase = phase;
    r->reason = reason;
    r->has_owner = owner != NULL;
    memcpy(r->owner_id, id, 16);
    if (!owner) r->owner_link_up = false;
    if (changed) r->revision++;
    return changed;
}

static void schedule(brama_rule_t *r, brama_close_kind_t kind, int64_t deadline_us)
{
    r->close_kind = kind;
    r->deadline_us = deadline_us;
}

static bool disarmed(brama_rule_t *r)
{
    schedule(r, BRAMA_CLOSE_NONE, 0);
    r->reboot_warning = false;
    return set(r, BRAMA_PHASE_DISARMED, BRAMA_REASON_NONE, NULL);
}

bool brama_rule_open_allowed(const brama_rule_t *r, const uint8_t client_id[16])
{
    return !r->has_owner || same_owner(r, client_id);
}

bool brama_rule_apply_open(brama_rule_t *r, const uint8_t client_id[16], bool auto_close)
{
    if (!auto_close) return disarmed(r);
    uint8_t id[16];
    memcpy(id, client_id, 16);
    schedule(r, BRAMA_CLOSE_NONE, 0);
    r->reboot_warning = false;
    bool changed = set(r, BRAMA_PHASE_MONITORING, BRAMA_REASON_NONE, id);
    r->owner_link_up = true; // komenda przyszła właśnie uwierzytelnionym łączem
    return changed;
}

bool brama_rule_apply_close(brama_rule_t *r)
{
    return disarmed(r);
}

bool brama_rule_io_fault_after(brama_rule_t *r, const brama_rule_t *before, uint8_t request_type, bool auto_close)
{
    if (request_type == BRAMA_MSG_OPEN && auto_close) return set(r, BRAMA_PHASE_FAULT, BRAMA_REASON_IO_FAULT, r->owner_id);
    if (request_type == BRAMA_MSG_CLOSE && before->has_owner) {
        // Rozbrojenie bez podanego CLOSE nie może po cichu zostawić bramy bez reguły (13.3).
        uint8_t id[16];
        memcpy(id, before->owner_id, 16);
        bool changed = set(r, BRAMA_PHASE_FAULT, BRAMA_REASON_IO_FAULT, id);
        r->owner_link_up = before->owner_link_up;
        return changed;
    }
    return false; // OPEN(false) i CLOSE przy DISARMED pozostają DISARMED
}

bool brama_rule_on_owner_disconnected(brama_rule_t *r, const uint8_t client_id[16], int64_t now_us)
{
    if (!same_owner(r, client_id)) return false;
    r->owner_link_up = false;
    if (r->phase != BRAMA_PHASE_MONITORING) return false; // FAULT/BLOCKED/WAITING: tylko informacja o łączu (14.4)
    schedule(r, BRAMA_CLOSE_AUTO, now_us + BRAMA_WAIT_US);
    return set(r, BRAMA_PHASE_WAITING, BRAMA_REASON_LINK_LOST, r->owner_id);
}

bool brama_rule_on_authenticated(brama_rule_t *r, const uint8_t client_id[16], int64_t now_us)
{
    if (!same_owner(r, client_id)) return false;
    r->owner_link_up = true;
    bool waiting = r->phase == BRAMA_PHASE_WAITING || r->phase == BRAMA_PHASE_BOOT_WAIT;
    if (!waiting || r->close_kind != BRAMA_CLOSE_AUTO || now_us >= r->deadline_us) return false;
    schedule(r, BRAMA_CLOSE_NONE, 0);
    return set(r, BRAMA_PHASE_MONITORING, BRAMA_REASON_NONE, r->owner_id);
}

bool brama_rule_on_interlock_cleared(brama_rule_t *r, int64_t now_us)
{
    if (r->phase != BRAMA_PHASE_BLOCKED) return false;
    if (r->owner_link_up) return set(r, BRAMA_PHASE_MONITORING, BRAMA_REASON_NONE, r->owner_id);
    // Nieobecny właściciel dostaje nowy okres powrotu zamiast natychmiastowego impulsu (14.4).
    schedule(r, BRAMA_CLOSE_AUTO, now_us + BRAMA_WAIT_US);
    return set(r, BRAMA_PHASE_WAITING, BRAMA_REASON_LINK_LOST, r->owner_id);
}

brama_close_kind_t brama_rule_due(const brama_rule_t *r, int64_t now_us)
{
    return r->close_kind != BRAMA_CLOSE_NONE && now_us >= r->deadline_us ? r->close_kind : BRAMA_CLOSE_NONE;
}

bool brama_rule_close_attempt_done(brama_rule_t *r, brama_close_outcome_t outcome)
{
    brama_close_kind_t kind = r->close_kind;
    schedule(r, BRAMA_CLOSE_NONE, 0);
    if (kind == BRAMA_CLOSE_RESET) return false;       // pozostaje FAULT/STORAGE_FAILURE, bez dalszych prób (9.6)
    if (kind == BRAMA_CLOSE_ERROR_MODE) return disarmed(r);
    switch (outcome) {
    case BRAMA_CLOSE_STARTED: return disarmed(r);
    case BRAMA_CLOSE_INTERLOCK: return set(r, BRAMA_PHASE_BLOCKED, BRAMA_REASON_INTERLOCK, r->owner_id);
    case BRAMA_CLOSE_BUSY_TIMEOUT: return set(r, BRAMA_PHASE_FAULT, BRAMA_REASON_BUSY_TIMEOUT, r->owner_id);
    case BRAMA_CLOSE_IO_FAIL: return set(r, BRAMA_PHASE_FAULT, BRAMA_REASON_IO_FAULT, r->owner_id);
    }
    return false;
}

bool brama_rule_reset_failed(brama_rule_t *r, int64_t now_us)
{
    if (!r->has_owner) return false;
    schedule(r, BRAMA_CLOSE_RESET, now_us + BRAMA_WAIT_US);
    return set(r, BRAMA_PHASE_FAULT, BRAMA_REASON_STORAGE_FAILURE, r->owner_id);
}

bool brama_rule_disarm(brama_rule_t *r, uint64_t auth_generation)
{
    r->auth_generation = auth_generation;
    return disarmed(r);
}

brama_snapshot_t brama_rule_snapshot_for(const brama_rule_t *r, const uint8_t *receiver_id, int64_t now_us)
{
    brama_snapshot_t s = {r->revision, r->phase, BRAMA_OWNER_NONE, r->reason, 0};
    if (r->has_owner) s.owner = same_owner(r, receiver_id) ? BRAMA_OWNER_SELF : BRAMA_OWNER_OTHER;
    if ((r->phase == BRAMA_PHASE_WAITING || r->phase == BRAMA_PHASE_BOOT_WAIT) && r->deadline_us > now_us) {
        int64_t ms = (r->deadline_us - now_us) / 1000;
        s.remaining_ms = ms > UINT32_MAX ? UINT32_MAX : (uint32_t)ms;
    }
    return s;
}

void brama_rule_to_rtc(const brama_rule_t *r, int64_t now_us, int64_t rtc_now_us, brama_rtc_copy_t *out)
{
    memset(out, 0, sizeof *out);
    out->magic = BRAMA_RTC_MAGIC;
    out->version = BRAMA_RTC_VERSION;
    if (r->phase == BRAMA_PHASE_DISARMED) {
        out->state = RTC_DISARMED;
        out->reboot_warning = r->reboot_warning;
    } else if (r->phase == BRAMA_PHASE_FAULT) {
        out->state = RTC_FAULT;
        out->fault_reason = r->reason;
    } else {
        out->state = RTC_ARMED;
        out->armed_phase = r->phase == BRAMA_PHASE_BOOT_WAIT ? BRAMA_PHASE_WAITING : r->phase;
    }
    if (r->has_owner) memcpy(out->owner_id, r->owner_id, 16);
    if (r->close_kind != BRAMA_CLOSE_NONE) {
        int64_t d = rtc_now_us + (r->deadline_us - now_us);
        out->deadline_rtc_us = d > 0 ? d : 1; // 0 oznacza brak terminu
    }
    out->auth_generation = r->auth_generation;
    out->crc = brama_crc32(out, offsetof(brama_rtc_copy_t, crc));
}

static int64_t from_rtc(int64_t deadline_rtc_us, const brama_rule_boot_env_t *env)
{
    int64_t d = env->now_us + (deadline_rtc_us - env->rtc_now_us);
    // Termin minął: próba zaraz po starcie. Żaden poprawny termin nie leży dalej niż 30 s — licznik RTC,
    // który cofnął się przy restarcie, nie może odsunąć zamknięcia (14.5).
    if (d < env->now_us) return env->now_us;
    return d > env->now_us + BRAMA_WAIT_US ? env->now_us + BRAMA_WAIT_US : d;
}

static bool copy_intact(const brama_rtc_copy_t *c)
{
    // Znane starsze wersje struktury migrowałyby się tutaj; na razie istnieje tylko wersja 1.
    return c && c->magic == BRAMA_RTC_MAGIC && c->version == BRAMA_RTC_VERSION
           && c->crc == brama_crc32(c, offsetof(brama_rtc_copy_t, crc));
}

static bool fault_reason_valid(uint8_t reason)
{
    return reason == BRAMA_REASON_IO_FAULT || reason == BRAMA_REASON_BUSY_TIMEOUT || reason == BRAMA_REASON_STORAGE_FAILURE;
}

void brama_rule_boot(brama_rule_t *r, const brama_rtc_copy_t *c, const brama_rule_boot_env_t *env)
{
    memset(r, 0, sizeof *r);
    r->phase = BRAMA_PHASE_DISARMED;
    r->revision = 1;
    r->auth_generation = env->auth_generation;
    bool intact = !env->cold_start && copy_intact(c);

    if (!env->image_ok) {
        // Tryb błędu obrazu NVS: najwyżej jedna próba CLOSE, tylko z nienaruszonej kopii (14.5 pkt 5).
        if (!env->allow_error_close) {
            r->rtc_frozen = true;
            return;
        }
        bool armed = intact && (c->state == RTC_ARMED || (c->state == RTC_FAULT && c->deadline_rtc_us != 0));
        if (!armed) return;
        int64_t deadline = c->deadline_rtc_us ? from_rtc(c->deadline_rtc_us, env) : env->now_us + BRAMA_WAIT_US;
        r->phase = BRAMA_PHASE_WAITING;
        r->reason = BRAMA_REASON_LINK_LOST;
        r->has_owner = true;
        memcpy(r->owner_id, c->owner_id, 16);
        schedule(r, BRAMA_CLOSE_ERROR_MODE, deadline);
        return;
    }

    bool owned = intact && (c->state == RTC_ARMED || c->state == RTC_FAULT);
    bool valid = intact && c->auth_generation == env->auth_generation
                 && (!owned || env->owner_active(env->ctx, c->owner_id))
                 && (c->state != RTC_FAULT || fault_reason_valid(c->fault_reason)) && c->state <= RTC_FAULT;
    if (!valid) {
        // Zimny start albo kopia nieważna: sterownik nie wie, czy reguła była uzbrojona — ostrzega zawsze.
        r->reason = BRAMA_REASON_REBOOT;
        r->reboot_warning = true;
        return;
    }
    switch (c->state) {
    case RTC_DISARMED:
        r->reboot_warning = c->reboot_warning != 0;
        r->reason = r->reboot_warning ? BRAMA_REASON_REBOOT : BRAMA_REASON_NONE;
        return;
    case RTC_FAULT:
        set(r, BRAMA_PHASE_FAULT, c->fault_reason, c->owner_id);
        if (c->deadline_rtc_us) schedule(r, BRAMA_CLOSE_RESET, from_rtc(c->deadline_rtc_us, env));
        break;
    default:
        if (c->armed_phase == BRAMA_PHASE_BLOCKED) {
            set(r, BRAMA_PHASE_BLOCKED, BRAMA_REASON_INTERLOCK, c->owner_id);
        } else {
            // MONITORING: nowe 30 s; WAITING/BOOT_WAIT: zachowany termin — pętla restartów go nie odsuwa (A63).
            bool keep = c->armed_phase == BRAMA_PHASE_WAITING && c->deadline_rtc_us;
            set(r, BRAMA_PHASE_BOOT_WAIT, BRAMA_REASON_REBOOT, c->owner_id);
            schedule(r, BRAMA_CLOSE_AUTO, keep ? from_rtc(c->deadline_rtc_us, env) : env->now_us + BRAMA_WAIT_US);
        }
        break;
    }
    r->revision = 1;
    r->owner_link_up = false;
}
