# BramaV2

[English](README.md) | **Polski**

Sterowanie bramą przez Bluetooth LE: aplikacja Android (z obsługą Android Auto) i firmware sterownika na ESP32.
Telefon jest centralą BLE, ESP32 urządzeniem peryferyjnym, które podaje impulsy na dwa osobne wyjścia
(OPEN i CLOSE) napędu bramy.

> **Status: prototyp.** Projekt jest testowany na jednej płytce prototypowej i trzech telefonach. Steruje urządzeniem
> fizycznym (ruchoma brama), więc przed użyciem w prawdziwej instalacji sprawdź go samodzielnie. Do podpisywania
> obrazów firmware używany jest klucz deweloperski; produkcyjny Secure Boot, szyfrowanie flash i klucze produkcyjne
> nie są częścią repozytorium.

## Co to robi

- Dwie komendy: `OPEN` (z opcjonalnym automatycznym zamknięciem po utracie połączenia) i `CLOSE`.
- Położenie bramy jest zawsze nieznane: potwierdzenie oznacza tylko, że impuls został podany na wyjście.
- Parowanie przez QR albo 6-cyfrowy PIN sterownika (EC-JPAKE), sesja szyfrowana i uwierzytelniana AES-256-GCM.
- Do 8 autoryzowanych telefonów jednocześnie; lista telefonów, usuwanie i zmiana nazwy z poziomu aplikacji.
- Automatyczne zamknięcie wykonuje sterownik, bez udziału telefonu.
- Aktualizacja firmware przez BLE (OTA): podpisany obraz ECDSA P-256, ochrona przed starszym `secure_version`,
  powrót do poprzedniej wersji po nieudanym starcie.
- Android Auto wyświetla interfejs aplikacji działającej na telefonie.

## Struktura repozytorium

| Katalog | Zawartość |
|---|---|
| `app/` | Aplikacja Android (Kotlin, Jetpack Compose), ekrany i usługi |
| `protocol/` | Protokół na łączu: ramki, kryptografia, EC-JPAKE, wektory testowe |
| `domain/` | Logika aplikacji, stan i repozytorium bramy (bez zależności od Androida) |
| `ble/` | Warstwa BLE (Android) i symulowany sterownik do testów |
| `car/` | Android Auto (Car App Library) |
| `firmware/` | Firmware ESP32 (ESP-IDF v6.0.1, NimBLE), testy hostowe |
| `hw/` | Projekt PCB w KiCad 10 (schemat, płytka, interaktywny BOM) |
| `doc/` | Specyfikacja, projekty modułów, plany i stan implementacji |

Dokumentacja jest po polsku. Dobry początek:

- [`doc/PLAN_I_SPECYFIKACJA_BRAMY.md`](doc/PLAN_I_SPECYFIKACJA_BRAMY.md) — specyfikacja i protokół,
- [`doc/implementation_status.md`](doc/implementation_status.md) — co jest zrobione i sprawdzone (także na sprzęcie),
- [`firmware/README.md`](firmware/README.md) — budowanie, testy i konfiguracja firmware.

## Aplikacja Android

Wymagania: Android Studio z JDK, Android SDK 37 (`compileSdk`), urządzenie z Androidem 13 (API 33) lub nowszym
i z Bluetooth LE. Skaner QR korzysta z usług Google Play.

```sh
./gradlew :app:assembleDebug        # build debug
./gradlew :app:assembleRelease      # build release (R8, bez podpisu: apk trzeba podpisać)
./gradlew test                      # testy jednostkowe modułów
```

Build release ma włączone R8 i skracanie zasobów (ok. 4 MB). Reguły keep dla ML Kit są w
`app/proguard-rules.pro`. W repozytorium nie ma konfiguracji podpisu wydania.

## Firmware

Wymagania: ESP-IDF v6.0.1 (`~/.espressif/v6.0.1/esp-idf`), klucz deweloperski do podpisu obrazu.

```sh
cd firmware
tools/gen_dev_signing_key.sh        # jednorazowo: klucz w ~/.config/brama/ (poza repozytorium)
tools/idf.sh set-target esp32       # jednorazowo
tools/idf.sh build                  # powstaje podpisany build/brama.bin
```

Testy hostowe logiki (target `linux`, wymagają pakietu `libbsd-dev`):

```sh
cd firmware/test/host
../../tools/idf.sh --preview set-target linux
../../tools/idf.sh build && ./build/brama_host_test.elf
```

Szczegóły (piny prototypu, tryb serwisowy, konsola UART, OTA) opisuje [`firmware/README.md`](firmware/README.md).

**Uwaga o eFuse.** Na płytce z `hw/` linia GPIO12 ma pull-up, który przy resecie wybiera napięcie flash 1,8 V, a moduł
ESP32-WROOM-32D ma flash 3,3 V. Na każdej takiej płytce wypalane jest więc eFuse napięcia flash 3,3 V (krok
nieodwracalny, wykonywany ręcznie przed pierwszym uruchomieniem; opis w `firmware/README.md`).

## Sprzęt

Projekt płytki jest w `hw/` (KiCad 10, otwórz `hw/esp32_brama.kicad_pro`): ESP32-WROOM-32D, zasilanie z przetwornicy
LMR54410, dwa wyjścia OPEN/CLOSE przez transoptory CNY17, wejście blokady (interlock) i diagnostyka UART. Biblioteki
projektu są w `hw/lib/`. Notatki z analizy płytki są w `doc/implementation_status.md`.

## Licencja

[MIT](LICENSE). Projekt jest tworzony i testowany przez jedną osobę; wkład i uwagi są mile widziane.
