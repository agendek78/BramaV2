# Projekt firmware sterownika bramy (ESP32-WROOM, ESP-IDF v6.0.1)

Data: 27.10.2026, OTA dopisane 28.10.2026. Specyfikacja: `doc/PLAN_I_SPECYFIKACJA_BRAMY.md` 1.5 — rozdz. 2.2 (wyjścia, interlock, impulsy), 8 (GATT, reklama), 9 (bezpieczeństwo, przycisk, reset), 10 (ramki, fragmentacja, budżety), 11 (rejestracja QR i PIN/EC-JPAKE), 12 (sesja, RuleSnapshot, HANDSHAKE_REJECT), 13 (komendy, ACK, duplikaty), 14 (reguła autozamykania, RTC), 15 (moduły, NVS, provisioning, OTA 15.5), 16 (wektory), testy A01–A81. Strona telefonu: `:protocol` (Kotlin) — implementacja odniesienia formatów i kryptografii.

## 1. Cel i zakres

**„Sucha” implementacja (decyzja właściciela):** kompletny firmware według specyfikacji, kompilujący się na ESP32 (target `esp32`), z logiką sprawdzoną testami na komputerze. Bez płytki: bez flashowania, bez pomiarów RAM na układzie, bez testów radia i napędu — te należą do Etapu 0/2/5.

**Cele:**
- wszystkie moduły z 15.1 i zachowania z rozdz. 9–14;
- zgodność bajt w bajt z aplikacją: ramki, HMAC/HKDF/AES-GCM, EC-JPAKE (wektory z `protocol/src/test/resources/ecjpake/` i scenariusze z `:protocol`);
- testy hostowe dla protokołu, kryptografii, automatu reguły, aktora, magazynu (NVS przez interfejs) i obsługi przycisku;
- tryb provisioningu i serwisowy przez UART (konsola), bez BLE;
- aktualizacja OTA przez BLE (15.5): podpisany obraz ECDSA P-256, numer bezpieczeństwa, rollback z autotestem, tabela partycji 4 MB.

**Nie-cele:** pomiary budżetu RAM i stosów (Etap 0/2), produkcyjne eFuse (Secure Boot, Flash Encryption, `secure_version` — tylko udokumentowana konfiguracja), test OTA na płytce (Etap 0/2), Wi-Fi.

**Anty-cele:**
- impuls bez pełnej kontroli w aktorze (generacje, tokeny, `control_enabled`, interlock, odstępy);
- jakikolwiek zapis NVS w normalnej pracy (A59);
- wypalenie eFuse lub włączenie Flash Encryption w konfiguracji deweloperskiej (nieodwracalne);
- nonce lub klucze z uptime/MAC/licznika;
- logowanie sekretów;
- przyjęcie obrazu OTA bez poprawnego podpisu albo ze starszym `secure_version`; zapis NVS w trakcie OTA; migracja NVS przed potwierdzeniem nowego obrazu.

## 2. Decyzje

**Właściciela:**
- ESP-IDF **v6.0.1** (zainstalowane lokalnie: `~/.espressif/v6.0.1/esp-idf`, narzędzia w `~/.espressif/tools`). Konsekwencja: mbedTLS 4 / TF-PSA-Crypto — EC-JPAKE przez **API PSA PAKE** (`PSA_ALG_JPAKE`), stare `mbedtls_ecjpake` jest tylko prywatnym sterownikiem.
- Najpierw projekt, potem „sucha” implementacja.
- OTA (spec 1.5): sparowany telefon bez fizycznego potwierdzenia, plik z pamięci telefonu, flash 4 MB, podpis ECDSA.

**Moje (do sprawdzenia):**
1. Katalog `firmware/` w tym repozytorium (wspólne wektory i specyfikacja).
2. Stos BLE **NimBLE** (spec 15.1), 9 połączeń (`CONFIG_BT_NIMBLE_MAX_CONNECTIONS=9`, kontroler BLE na tyle samo).
3. **Logika w czystym C bez zależności od sprzętu**, za interfejsami (porty): zegar monotoniczny, losowość, GPIO, RTC, NVS, BLE. Implementacje ESP32 w osobnych komponentach.
4. **Testy hostowe na targecie `linux` ESP-IDF** (Unity, `idf.py --preview set-target linux`): ta sama kompilacja co na ESP32, ten sam mbedTLS/PSA. Jeżeli któryś komponent nie buduje się na `linux`, fallback: zwykły CMake na hoście dla rdzenia + mbedTLS z drzewa IDF (rozstrzygnąć w pierwszym zadaniu planu).
5. **Piny w menuconfig** (`Kconfig.projbuild`) z domyślnymi wartościami z 2.2: OPEN 21, CLOSE 22, PAIR_BUTTON 27, STATUS_LED 23, INTERLOCK 33. Prototyp (`sdkconfig.defaults`, 12.11.2026): OPEN 17, CLOSE 18, PAIR_BUTTON 0 (IO0, aktywny niskim), STATUS_LED 19 (aktywna niskim, `CONFIG_BRAMA_LED_ACTIVE_LOW`), INTERLOCK 5. Polaryzacja wyjść konfigurowalna (domyślnie aktywne wysokim).
6. **Konfiguracja deweloperska bez Secure Boot i Flash Encryption.** `sdkconfig.defaults.release` zawiera produkcyjne ustawienia (Flash Encryption release, Secure Boot v2, NVS Encryption, wyłączony JTAG) jako dokumentację do Etapu 5 — nie jest używany w buildach deweloperskich.
7. **Tryb serwisowy jako osobny tryb uruchomienia** (spec 15.2.1: „poza normalną pracą aktora”). Wejście: przycisk PAIR_BUTTON wciśnięty w chwili włączenia zasilania/resetu (fizyczny dostęp do wnętrza obudowy) albo brak danych fabrycznych (pierwsze uruchomienie = provisioning).
   - W trybie serwisowym nie startują BLE ani aktor, wyjścia są nieaktywne, działa konsola UART (`esp_console`): `provision` (tylko przed blokadą provisioningu), `reprovision`, `config pulse <ms>`, `config interlock on|off`, `lock-provisioning`, `info`, `reboot`.
   - `reprovision` w trybie serwisowym: nowe `device_id`/`K_setup`/PIN, pusty obraz z `auth_generation + 1`, kopia RTC → DISARMED (bez impulsu), wydruk QR (spec 15.4).
   - **`info` ujawnia sekrety (12.11.2026, decyzja właściciela):** oprócz stanu wypisuje `device_id`, QR i PIN; ochroną jest fizyczny dostęp do UART (spec 15.4).
   - **Zmiana 12.11.2026:** konsola z pełnym zestawem komend działa także w normalnej pracy (po starcie BLE i aktora), bo PAIR_BUTTON na IO0 uniemożliwia wejście przyciskiem przy starcie (IO0 = tryb download). Komendy piszące do NVS działają wtedy poza aktorem, bez blokady; po nich obowiązuje `reboot` (spec 15.4, 15.2.1). A59 dotyczy normalnej pracy bez użycia konsoli.
   - Release: ten sam mechanizm — zabezpieczeniem jest fizyczny dostęp (przycisk przy starcie + złącze serwisowe wewnątrz obudowy, spec 15.4); UART produkcyjny pozostaje włączony wyłącznie dla trybu serwisowego (zastrzeżenie do instrukcji produkcyjnej w Etapie 5).
8. **PIN** losowany równomiernie 000000–999999 z odrzucaniem (spec 9.2), przechowywany w NVS razem z `K_setup`.
9. **Wektory scenariuszy z `:protocol`** w zakresie tej pracy; pełna lista 16.3 później (z przeglądu projektu).
10. **Tryb serwisowy** jako osobny tryb uruchomienia (przycisk przy starcie); od 12.11.2026 konsola pełna także w normalnej pracy (patrz pkt 7).
11. **OTA jako czysta logika `brama_ota` za portem** `brama_ota_port_t` (begin/write/end/abort/set_boot, `secure_version` działającego obrazu, stan obrazu po starcie). Implementacja ESP32 (`esp_ota_*`) w `brama_hw/ota_hw.c`. Zapis flash w osobnym zadaniu `ota_worker` o niższym priorytecie niż aktor i host NimBLE; aktor dopuszcza tylko BEGIN/END (reguła DISARMED, tokeny) i porzuca transfer przy resecie i utracie sesji.
12. **Klucz podpisu deweloperski** poza repozytorium: `~/.config/brama/dev_signing_key.pem` (ECDSA P-256), tworzony skryptem `firmware/tools/gen_dev_signing_key.sh` (`espsecure.py generate_signing_key --version 1 --scheme ecdsa256`); ścieżka w `sdkconfig.defaults` (`CONFIG_SECURE_BOOT_SIGNING_KEY`). Klucz produkcyjny — instrukcja produkcyjna (Etap 5).
13. **Anti-rollback w buildzie deweloperskim wyłącznie z emulacją eFuse** (`CONFIG_BOOTLOADER_EFUSE_SECURE_VERSION_EMULATE=y`, które włącza `CONFIG_EFUSE_VIRTUAL`), partycja `emul_efuse`. Bez emulacji `esp_ota_mark_app_valid_cancel_rollback` zapisałby eFuse — nieodwracalne.

## 3. Architektura komponentów (`firmware/components/`)

| Komponent | Odpowiedzialność (spec) | Zależności | Testy hostowe |
|---|---|---|---|
| `brama_proto` | Ramki 22 B, typy i fazy, fragmentacja/składanie, budżety 10.4, `RuleSnapshot`, payloady ACK/PONG/HANDSHAKE_REJECT i OTA `50`–`53`/`81` (10, 12.2, 12.4, 13.1, 15.5) | — | tak, wektory z `:protocol` |
| `brama_crypto` | HMAC-SHA-256, HKDF, AES-256-GCM, `MAC(K,D,X)`, porównanie w stałym czasie, EC-JPAKE (serwer) na PSA z formatem z 11.4, `K_pin` | mbedTLS/PSA | tak, wektory 16.2 i `ecjpake/` |
| `brama_session` | Kontekst klienta: handshake SESSION/PAIR/PAKE (strona serwera), klucze kierunków, liczniki, cache ≥16 ACK, duplikaty i replay (11, 12, 13.4) | proto, crypto | tak |
| `brama_auth` | Obraz autoryzacji A/B (ACTIVE, bondy, generacje, integralność), staging CANDIDATE/PENDING w RAM, okno rejestracji, limity prób (globalny limit PIN-u), zasady 9.5/9.6 | port NVS | tak, z NVS w pamięci |
| `brama_rule` | Automat reguły (14.1–14.4), kopia RTC z CRC i migracją (14.5), RuleSnapshot, deadline'y | port RTC, zegar | tak |
| `brama_actor` | `control_actor` (15.2.1): jedna kolejka, tokeny/generacje, komendy, transakcje NVS, reset, auto-CLOSE, odstępy impulsów, interlock, `control_enabled` | wszystkie powyżej, port wyjść | tak, z fałszywymi portami |
| `brama_ota` | Automat transferu OTA (15.5): BEGIN/DATA/END/ABORT, `next_offset`, wstępna kontrola nagłówka obrazu (magic `0xE9`, układ, `project_name`, rozmiar, `secure_version`), SHA-256 przyrostowo, porzucenie po 30 s; decyzja pierwszego startu (autotest → potwierdzenie → migracja albo rollback) | proto, crypto, port OTA, zegar | tak, z fałszywym portem OTA |
| `brama_button` | Przycisk 3 s / 10 s z filtracją, wzorce LED z priorytetem (9.4) | zegar | tak |
| `brama_ble` | NimBLE: reklama i filtr połączeń (8.3), GATT INFO/RX/TX (8.2), Device Information z wersją firmware, `capabilities = 0x7F`, 9 kontekstów, security/bonding, własny `ble_store` z obrazu, CCCD bez utrwalania, kolejki do aktora | NimBLE | kompilacja na `esp32` |
| `brama_hw` | GPIO wyjść z dedykowanym timerem końca impulsu (`esp_timer`/GPTimer + ISR), interlock z filtrem 50 ms, LED, RTC (`RTC_NOINIT_ATTR`), NVS (`nvs_flash`), RNG (`esp_fill_random` po inicjalizacji), port OTA (`esp_ota_*`, `OTA_WITH_SEQUENTIAL_WRITES`) i zadanie `ota_worker` | ESP-IDF | kompilacja; logika czasu testowana przez port |
| `brama_console` | Provisioning, konfiguracja lokalna, `info`, licznik zapisów NVS (A59) | auth, hw | częściowo |
| `main` | Start (14.5): walidacja obrazu, RTC, odbudowa bondów, bezpieczne wyjścia, autotest i potwierdzenie obrazu OTA przed migracją (15.5), `control_enabled`, uruchomienie zadań | wszystkie | decyzja startu — tak |

## 4. Kluczowe rozstrzygnięcia techniczne

- **EC-JPAKE na PSA:** rundy `PSA_PAKE_STEP_KEY_SHARE`, `ZK_PUBLIC`, `ZK_PROOF` składane w format TLS z 11.4 (ECPoint 1 B długości + 65 B, ZKP `V || len || r`, `ECParameters 03 00 17` w rundzie 2 serwera). Role i identyfikatory `"server"`/`"client"`. Sekret `S` = SHA-256(x(K)) jak `mbedtls_ecjpake_derive_secret` — to, co zwraca `psa_pake_get_shared_key` dla J-PAKE w tej wersji, potwierdzić testem z wektorami; w razie różnicy dołożyć SHA-256 po stronie firmware. Odrzucenie punktów spoza krzywej, w nieskończoności i `r ≥ n` (zgodnie z `:protocol`).
- **Wspólne wektory (w zakresie tej pracy, moja decyzja):** w `:protocol` dochodzi generator (test uruchamiany flagą, jak generator EC-JPAKE) zapisujący scenariusze `klucz=hex` do `protocol/src/test/resources/vectors/`: pełne handshake SESSION, PAIR (QR) i PIN (PAKE + PAIR) ze stałymi nonce i skalarami, komendy OPEN/CLOSE/PING z ACK/PONG, fragmenty przy MTU 23/247 i HANDSHAKE_REJECT. Firmware odtwarza te bajty w testach hostowych jako strona serwera. Pełna lista 16.3 (`protocol-v2-vectors.json`: błędy pojedynczego bitu, refleksja, rekey, każdy kod odmowy, MTU 517) pozostaje na Etap 1 po implementacji obu stron — te scenariusze są jej zalążkiem.
- **Sekret J-PAKE na PSA:** `psa_pake_get_shared_key` w IDF v6.0.1 zwraca punkt K (przez `mbedtls_ecjpake_write_shared_key`), nie SHA-256(x). Firmware liczy `S` = SHA-256(x(K)) samodzielnie (albo `PSA_ALG_TLS12_ECJPAKE_TO_PMS`). Testy hostowe z ustalonymi skalarami korzystają z prywatnego `mbedtls_ecjpake` (z RNG podanym przez test), aby odtworzyć wektory `interop.txt`.
- **Impuls:** start i koniec wyłącznie przez `brama_hw`; koniec impulsu w callbacku timera (nie w zadaniu aktora); wykluczenie jednoczesnej aktywacji obu wyjść w `brama_hw`; wyjścia nieaktywne przed startem aktora.
- **RTC:** struktura w `RTC_NOINIT_ATTR` z magic, wersją, CRC, `auth_generation`, właścicielem, fazą i `deadline_rtc_us` (14.5); zegar RTC przez `esp_rtc_get_time_us`.
- **NVS:** sloty `img_a`/`img_b` (blob z nagłówkiem: wersja schematu, image_generation, auth_generation, CRC/SHA-256) + `factory` (`device_id`, `K_setup`, PIN, flaga blokady provisioningu) + `localcfg`. Licznik zapisów w porcie NVS (A59).
- **PHY:** `CONFIG_ESP_PHY_CALIBRATION_AND_DATA_STORAGE=n` (15.2).
- **Partycje:** `firmware/partitions.csv` według tabeli 15.5 (bez `factory`; `ota_0`/`ota_1` po `0x1E0000`, `emul_efuse` w buildzie deweloperskim), `CONFIG_PARTITION_TABLE_CUSTOM=y`, `CONFIG_ESPTOOLPY_FLASHSIZE_4MB=y`.
- **Podpis i rollback (dev):** `CONFIG_SECURE_SIGNED_APPS_NO_SECURE_BOOT=y`, `CONFIG_SECURE_SIGNED_APPS_ECDSA_SCHEME=y`, `CONFIG_SECURE_SIGNED_ON_UPDATE_NO_SECURE_BOOT=y`, `CONFIG_SECURE_BOOT_BUILD_SIGNED_BINARIES=y`, `CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE=y`, `CONFIG_BOOTLOADER_APP_ANTI_ROLLBACK=y` + emulacja eFuse, `CONFIG_BOOTLOADER_APP_SECURE_VERSION` = numer bezpieczeństwa wydania. Dokładne zależności Kconfig potwierdzić `idf.py menuconfig`/buildem w zadaniu planu.
- **OTA a impulsy:** zapis flash wyłącza cache; callback końca impulsu (`esp_timer` z `ESP_TIMER_ISR`, `IRAM_ATTR`) i sterowanie GPIO w IRAM (`CONFIG_ESP_TIMER_SUPPORTS_ISR_DISPATCH_METHOD=y`, `CONFIG_GPIO_CTRL_FUNC_IN_IRAM=y`). Kasowanie sektorów na bieżąco, bez kasowania całego slotu przy BEGIN.
- **Wersja:** `esp_app_desc_t.version` (z `PROJECT_VER`) w Device Information (`0x180A`/`0x2A26`); `project_name = "brama"`.
- **Losowość:** `esp_fill_random` dopiero po włączeniu RF/bootloader entropy zgodnie z IDF; w provisioningu przed generowaniem sekretów sprawdzić warunki z dokumentacji IDF (15.3).

## 5. Testowanie

- **Host (`firmware/test/host/`, target `linux`):** Unity; fałszywe porty (zegar sterowany ręcznie, RNG deterministyczny, NVS w pamięci z licznikiem zapisów i wstrzykiwanymi błędami, RTC w pamięci, wyjścia rejestrujące impulsy z czasem).
- **Zakres testów:** odpowiadające logice testy akceptacyjne z rozdz. 18 (np. A06, A10–A13, A20–A25, A29, A41–A46, A51–A53, A59, A61, A63–A68, A76–A79 w części logicznej) — w planie wypisane per zadanie. A80 (czas impulsu podczas zapisu flash) i rzeczywisty rollback wymagają płytki.
- **Zgodność z aplikacją:** EC-JPAKE (wektory mbedTLS 3.6.2 i interop), pełne handshake SESSION/PAIR/PIN z bajtami z `:protocol`.
- **Target `esp32`:** `idf.py build` bez ostrzeżeń jako bramka dla `brama_ble`, `brama_hw`, `main`.

## 6. Pozostawione budowniczemu

Podział na pliki wewnątrz komponentów, rozmiary kolejek i stosów (z zapasem, do pomiaru w Etapie 2), dokładne wywołania NimBLE (filtr połączeń, resolving list, security), format logów diagnostycznych (bez sekretów).

## 7. Zmiany po testach na sprzęcie (16–17.11.2026)

- **Limit bondów NimBLE:** `CONFIG_BT_NIMBLE_MAX_BONDS=9` (≥ `BRAMA_MAX_CLIENTS` 8 plus jeden bond w staging). Przy domyślnym 3 czwarty bond (trzeci klient plus staging) kończył parowanie `Pairing Failed 0x08` bez zdarzenia GAP (`ble_sm_chk_store_overflow`).
- **Stos hosta NimBLE:** `CONFIG_BT_NIMBLE_HOST_TASK_STACK_SIZE=5120` (zapas przy 3 łączach i rejestracji: 924 B z 4096 B przed zmianą, 1 940 B po). Statyczny DRAM jest zapełniony w ok. 99% (`g_links` 69 KB, wolne ok. 1,3 KB): nowe bufory tylko ze sterty. Sterta przy 3 łączach ok. 39 KB wolnych (minimum 27,5 KB podczas rundy PAKE); koszt łącza ok. 370 B.
- **PAKE poza blokadą:** rundy EC-JPAKE w osobnym zadaniu `pake_worker` (kopia sesji, wynik stosowany pod blokadą tylko gdy łącze nadal istnieje); wcześniej blokowały aktora i blokadę BLE na 2–2,7 s.
- **Task watchdog:** `CONFIG_ESP_TASK_WDT_PANIC=y` (timeout 5 s, IDLE0/IDLE1): zawieszone zadanie kończy się resetem; kopia reguły w RTC przeżywa restart programowy, panic i watchdog (zmierzone), a reset przyciskiem EN jest zimnym startem. Brak zawieszeń w normalnej pracy (runda PAKE, OTA, weryfikacja podpisu ok. 1 s).
- **OTA:** brak reklamy łączalnej w trakcie transferu (spec 15.5); impulsy OPEN/CLOSE nie są opóźniane przez kasowanie i zapis flash (A80: odchyłka do +0,34 ms przy operacjach flash do 60 ms; timer w ISR i GPIO w IRAM).
- **Reklama:** pole producenta `0xFFFF` z liczbą prób PIN przy otwartym oknie (spec 8.3); komenda `info` wypisuje liczbę prób, `mem` stos i stertę.
- **Znane, niewyjaśnione:** jednorazowe zawieszenie `ota_worker` na wspólnej blokadzie po dołączeniu drugiego telefonu w trakcie OTA (aktor, `ui` i host NimBLE czekają na blokadę, task watchdog tego nie widzi); `ota_worker` trzyma blokadę podczas zapisu flash. Komunikat `failed to configure restored IRK` po resecie hosta jest bez skutku (RPA rozpoznaje własny kod po IRK, lista kontrolera i tak nie rozwiązuje adresów).

## 8. Zarządzanie telefonami (18.11.2026, `doc/client_management_design.md` §4)

Schemat obrazu NVS 2 (rekord klienta z nazwą 20 B, bez migracji: reset autoryzacji po wgraniu); aktor obsługuje `LIST_CLIENTS`, `SET_CLIENT_NAME` i `REMOVE_CLIENT` (kolejność odmów: długość, BUSY, siebie, brak klienta, właściciel reguły; commit w jednej transakcji, `auth_generation` bez zmian, rozłączenie usuwanego łącza, usunięcie bondu z magazynu i hosta przez callback środowiska, plan zapasowy: `esp_restart` po ACK). `info` wypisuje nazwy. `remove-client` na konsoli zostaje (z `auth_generation + 1` i `reboot`). Do sprawdzenia na sprzęcie: usuwanie bondu na żywo bez resetu hosta (A91).
