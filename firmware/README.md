# Firmware sterownika bramy (ESP32-WROOM, ESP-IDF v6.0.1)

Specyfikacja: `../doc/PLAN_I_SPECYFIKACJA_BRAMY.md` (1.5), projekt: `../doc/firmware_design.md`,
plan: `../doc/plans/2026-10-27-firmware.md`. Implementacja „sucha”: logika testowana na komputerze, sprzęt i radio
tylko kompilowane — działanie potwierdza Etap 0/2 na płytce.

## Narzędzia

- `tools/idf.sh` — `idf.py` z ESP-IDF v6.0.1 (`~/.espressif/v6.0.1/esp-idf`) bez interaktywnej aktywacji.
- Testy na komputerze wymagają pakietu systemowego `libbsd-dev` (target `linux` ESP-IDF).
- `tools/gen_dev_signing_key.sh` — deweloperski klucz podpisu ECDSA P-256 w `~/.config/brama/dev_signing_key.pem`
  (poza repozytorium) i dowiązanie `keys/dev_signing_key.pem`, którego szuka build. Bez klucza build kończy się błędem;
  obraz niepodpisany nie powstaje. Klucz produkcyjny opisuje instrukcja produkcyjna (Etap 5).

## Budowanie (ESP32)

```sh
tools/gen_dev_signing_key.sh    # jednorazowo
tools/idf.sh set-target esp32   # jednorazowo
tools/idf.sh build
```

Sprawdzenie podpisu obrazu:
`~/.espressif/tools/python/v6.0.1/venv/bin/python -m espsecure verify-signature --version 1 --keyfile keys/dev_signing_key.pem build/brama.bin`.

Konfiguracja deweloperska (`sdkconfig.defaults`): podpis obrazów bez sprzętowego Secure Boot, rollback,
anti-rollback **wyłącznie z emulacją eFuse** (`CONFIG_EFUSE_VIRTUAL=y`), tablica partycji `partitions.csv` (flash 4 MB,
pod `0xA000`). Nigdy nie włączać Flash Encryption, Secure Boot ani zapisu eFuse w tym pliku —
`sdkconfig.defaults.release` jest tylko dokumentacją ustawień produkcyjnych.

## Płytka docelowa (`../hw`): napięcie flash (eFuse)

Na PCB z `hw/` linia GPIO12 (strapping `MTDI`) ma pull-up R8 do 3,3 V, co przy resecie wybiera flash 1,8 V, a moduł
ESP32-WROOM-32D ma flash 3,3 V. **Na każdej takiej płytce wypalamy eFuse napięcia flash 3,3 V** (decyzja właściciela,
21.11.2026), przed pierwszym uruchomieniem firmware. Krok jest nieodwracalny i wykonywany ręcznie, nie z
`sdkconfig.defaults`:

```sh
espefuse --chip esp32 -p <PORT> set-flash-voltage 3.3V   # składnia zależna od wersji esptool (starsze: set_flash_voltage)
```

Płytka prototypowa (moduł deweloperski) tego nie wymaga. Polecenie nie było uruchamiane na tym PCB.

## Testy hostowe (Unity, target `linux`)

```sh
cd test/host
../../tools/idf.sh --preview set-target linux   # jednorazowo
../../tools/idf.sh build && ./build/brama_host_test.elf
```

Wektory: `../protocol/src/test/resources/ecjpake/` i `vectors/` (generacja w `:protocol`, opis w ich README).

## Komponenty

| Komponent | Zakres | Testy |
|---|---|---|
| `brama_ports` | Interfejsy sprzętu (zegar, RNG, NVS, RTC, wyjścia, OTA), kody błędów | — |
| `brama_crypto` | HMAC, HKDF, AES-GCM, SHA-256, EC-JPAKE (serwer, PSA), `K_pin` | `test_crypto.c`, `test_ecjpake.c` |
| `brama_proto` | Ramki, typy, fragmentacja, budżety 10.4, snapshot, payloady ACK i OTA | `test_proto.c` |
| `brama_session` | Handshake SESSION/PAIR/PAKE po stronie serwera, sesja GCM, cache duplikatów | `test_session.c` |
| `brama_auth` | Obraz A/B, dane fabryczne, konfiguracja lokalna, okno, limity | `test_auth.c` |
| `brama_rule` | Automat reguły 14.1–14.4, kopia RTC i start 14.5 | `test_rule.c` |
| `brama_actor` | `control_actor`: komendy, odstępy impulsów, auto-CLOSE, PAIR_COMMIT, reset, OTA BEGIN/END | `test_actor.c`, `test_ota.c` |
| `brama_ota` | Transfer obrazu, kontrola nagłówka, decyzja pierwszego startu | `test_ota.c` |
| `brama_button` | Przycisk 3 s/10 s, wzorce LED | `test_button.c` |
| `brama_boot` | Decyzja trybu startu, QR, PIN, dane fabryczne | `test_boot.c` |
| `brama_hw` | GPIO, timer impulsu w ISR/IRAM, interlock, RTC, NVS, RNG, port OTA | kompilacja |
| `brama_ble` | NimBLE: reklama/filtr, GATT, sesje, magazyn bondów, TX, worker OTA | kompilacja |
| `main` | Start, tryb serwisowy i konsola UART | kompilacja |

## Tryb serwisowy

Konsola UART ma pełny zestaw komend zawsze: `provision` (do `lock-provisioning`), `reprovision`, `config pulse <100–1000>`,
`config interlock on|off`, `lock-provisioning`, `info` (także lista klientów), `remove-client <numer z info | początek client_id>`
(usuwa jednego klienta, `auth_generation + 1`, reguła w RTC DISARMED; potem `reboot`), `reboot`. Baner na starcie mówi, czy aplikacja działa:
„Konsola serwisowa (aplikacja dziala)” w normalnej pracy; „Tryb provisioningu / serwisowy (aplikacja nie dziala)” — bez
BLE i aktora (brak danych fabrycznych albo PAIR_BUTTON przy starcie; po `provision` w tym trybie potrzebny `reboot`).
QR i PIN trafiają wyłącznie na złącze serwisowe: wypisuje je `provision`/`reprovision` oraz `info` (razem z `device_id`) — kto ma dostęp do UART, ma sekrety. Komendy piszące do NVS działają także przy działającej aplikacji
(bez blokady względem aktora); po provisioningu użyj `lock-provisioning` (nie wyłącza `reprovision`). PAIR_BUTTON:
`CONFIG_BRAMA_GPIO_PAIR_BUTTON` (domyślnie IO0 w `sdkconfig.defaults`, aktywny stanem niskim); okno parowania otwiera
zwolnienie po 3–10 s. Tryb serwisowy nie potwierdza nowego obrazu OTA.

## Testy akceptacyjne pokryte testami hostowymi (część logiczna)

A06/A13 (duplikaty, luki seq) — `test_session.c`; A20–A25, A29 (reguła, FAULT, BLOCKED) — `test_rule.c`,
`test_actor.c`; A41–A46 (reset, tokeny, transakcje) — `test_actor.c`, `test_auth.c`; A47 (kody odmów) —
`test_session.c`; A51 (interlock), A52 (odstęp impulsów) — `test_actor.c`; A53 (przycisk, LED) — `test_button.c`;
A54 (format QR) — `test_boot.c`; A56 (uszkodzony obraz, schemat) — `test_auth.c`, `test_boot.c`; A59 (zero zapisów
NVS) — `test_actor.c`, `test_ota.c`; A61, A63–A66 (restart, RTC) — `test_rule.c`; A67–A68 (PIN) — `test_session.c`,
`test_auth.c`; A76–A79 (OTA, część logiczna) — `test_ota.c`; A33, A42, A43, A57 (naprawa bondu, część logiczna)
— `test_session.c` (`repair_*`, `session_admit_*`), `test_actor.c` (`repair_*`).

## Do sprawdzenia na płytce (Etap 0/2)

- Pomiary RAM, stosów i kolejek przy 9 łączach; NimBLE 9 połączeń; accept list i resolving list z RPA (8.3).
- Impulsy na analizatorze, w tym podczas zapisu flash OTA (A80); interlock z filtrem.
- Licznik i pamięć RTC przy watchdogu, panic, `esp_restart`, restarcie po OTA i brownoucie (14.5).
- OTA end-to-end: podpis, rollback po nieudanym autoteście, zanik zasilania w trakcie (A76–A79).
- Rewizja układu i schemat kodowania eFuse (anti-rollback wymaga `NONE`); decyzje Secure Boot/Flash Encryption w Etapie 5.
- Losowość w trybie serwisowym (`bootloader_random_enable`) i przy pracy z RF.
- Naprawa bondu (9.5, A33, A57): „Zapomnij urządzenie” na telefonie → poza oknem `REPEAT_PAIRING` odrzucone, w oknie
  CANDIDATE + SESSION → commit → READY i kolejne połączenie bez okna; wariant z nowym adresem tożsamości
  (`ble_gap_unpair` starego wpisu z resolving list); odmowa przy aktywnym łączu starego klienta; czy `T_attempt` od
  połączenia wystarcza na dialog parowania Androida; czy Android przy zwykłym `connect` bez bondu sam nie pokazuje
  dialogu po `ble_gap_security_initiate` (od 13.11.2026 Security Request tylko dla znanego klienta z bondem).
- Okno z telefonu (A82): `OPEN_PAIRING_WINDOW` → reklama bez filtra i LED 1 Hz jak po przycisku; rejestracja drugiego
  telefonu QR i PIN-em; PIN po 5 porażkach zablokowany do przycisku albo godziny; naprawa bondu z okna otwartego
  przez inny telefon.
