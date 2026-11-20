# Projekt: lista telefonów na bramie i ich usuwanie z aplikacji

Status: **zatwierdzony 18.11.2026** (decyzje §9 przyjęte). Wariant nazw (a) wybrany przez właściciela 18.11.2026: nazwa telefonu zapisana w rekordzie klienta na sterowniku.
Założenie właściciela (18.11.2026): jest jeden egzemplarz płytki i 3 telefony, więc **bez zgodności wstecznej i bez migracji**. Wprowadzenie funkcji wymaga fabrycznego resetu sterownika (reset autoryzacji przyciskiem, wgranie nowego firmware) i ponownej rejestracji wszystkich telefonów razem z nową aplikacją. Aplikacja i firmware są wersjonowane razem.
Specyfikacja zmieniona 18.11.2026: `doc/PLAN_I_SPECYFIKACJA_BRAMY.md` nota w nagłówku, 3, 4.1, 9.1, 9.4, 13.1, 13.2, 15.2, 15.2.1, 15.4, 16.3, 18 (testy A90–A93), 19; `doc/phone_window_design.md` (zgubiony telefon), `doc/vectors_design.md`, `doc/app_design.md`, `doc/domain_design.md`, `doc/ble_design.md`.

## 1. Zasada

Telefon ACTIVE z sesją READY może:
1. pobrać listę telefonów zarejestrowanych na sterowniku (nazwa, znaczniki: „ten telefon”, „połączony teraz”, „zarządza bramą”),
2. usunąć wskazany inny telefon (jego rekord klienta i bond),
3. zmienić nazwę własnego telefonu.

Zmienia to dotychczasową decyzję (spec. 9.4, 3): „Usunięcie z telefonu nie odwołuje dostępu; zgubiony telefon odwołuje się resetem przyciskiem; selektywne zdalne zarządzanie klientami jest rozszerzeniem poza pierwszą wersją”. Po zmianie zgubiony lub sprzedany telefon można odwołać z innego zarejestrowanego telefonu, bez otwierania skrzynki. Reset przyciskiem i `remove-client` na konsoli zostają bez zmian.

## 2. Ocena bezpieczeństwa

- Wszystkie telefony są równoprawne (brak roli administratora), jak przy `OPEN_PAIRING_WINDOW`. Posiadacz ważnego telefonu już steruje bramą i może otwierać okno rejestracji, więc dodatkowa możliwość to usunięcie innego telefonu (uciążliwość, nie włamanie): usunięty telefon wraca przez ponowną rejestrację (QR lub PIN w oknie).
- Zysk: odwołanie zgubionego telefonu bez fizycznego dostępu do płytki. To główny powód zmiany.
- Komendy wymagają READY, `control_enabled` i bieżącego klienta w obrazie (`link_current`), jak komendy ruchu. Odpowiedzi idą AES-GCM. Lista nie zawiera `K_client` ani danych bondu; `client_id` nie jest sekretem (jest w logu `info`).
- Nazwy są widoczne tylko dla zarejestrowanych telefonów (GCM). Nie są sekretem, ale mogą identyfikować osobę (np. „Telefon Ani”). Aplikacja pokazuje użytkownikowi, jaką nazwę wyśle, i pozwala ją zmienić.
- Ochrona przed błędem: sterownik odmawia usunięcia **telefonu żądającego** i **właściciela uzbrojonej reguły** (jak 9.5: zastąpienie rekordu właściciela jest blokowane). Zostaje więc zawsze co najmniej jeden telefon, a usuwanie nie może zostawić bramy otwartej bez drogi zamknięcia.

## 3. Protokół

Nowe typy sesji (c2s i s2c, AES-GCM, faza SESSION; kolejka jednego żądania jak PING, 13.3):

| Typ | Nazwa | Kier. | Payload |
|---|---|---|---|
| `04` | `LIST_CLIENTS` | c2s | 0 B. Odpowiedź: `CLIENT_LIST`. |
| `05` | `REMOVE_CLIENT` | c2s | `client_id[16]`. Odpowiedź: `COMMAND_ACK`. |
| `06` | `SET_CLIENT_NAME` | c2s | `name_len[1]` (0–20) `|| name[name_len]`, UTF-8. Dotyczy telefonu żądającego. Odpowiedź: `COMMAND_ACK`. |
| `82` | `CLIENT_LIST` | s2c | `request_seq[8 LE] || count[1] ||` count × wpis (38 B). |

Wpis listy (stała długość, kolejność z obrazu = kolejność rejestracji):
`client_id[16] || flags[1] || name_len[1] || name[20]` (nazwa dopełniona zerami). `flags`: bit 0 = to jest telefon żądający, bit 1 = ma teraz sesję READY, bit 2 = jest właścicielem uzbrojonej reguły. Pozostałe bity 0, odbiorca je ignoruje. `count` ≤ `max_clients` (8), więc payload ≤ 9 + 8×38 = 313 B, ramka ≤ 351 B (limit 512 B, 10.1); fragmentacja jak OTA, 21 fragmentów przy MTU 23 (budżet 17 B, limit 32).

Nowe wyniki `COMMAND_ACK` (13.2):

| Kod | Nazwa | Znaczenie |
|---|---|---|
| `09` | `CLIENT_REMOVED` | Klient usunięty, zmiana zatwierdzona w NVS. |
| `0A` | `NAME_SET` | Nazwa telefonu żądającego zapisana. |
| `0B` | `CLIENT_NOT_FOUND` | Brak klienta o tym `client_id` (np. usunięty w międzyczasie). Bez zmian. |
| `0C` | `CANNOT_REMOVE_SELF` | `client_id` = telefon żądający. Bez zmian. |
| `0D` | `CLIENT_OWNS_RULE` | Wskazany telefon jest właścicielem uzbrojonej reguły; najpierw zamknąć bramę albo poczekać na autozamknięcie (WAITING ok. 30 s od utraty łącza). Bez zmian. |

Wykorzystany zostaje też zarezerwowany dotąd `04 STORAGE_FAILURE`: te komendy jako pierwsze zapisują NVS z sesji (13.2 trzeba przepisać). Pozostałe wyniki: `INVALID_ARGUMENT` (zła długość, niepoprawny UTF-8, znaki sterujące, nazwa > 20 B), `BUSY` (trwa OTA albo reset), `UNSUPPORTED_TYPE` tylko dla nieznanych typów.

**Wykrywanie obsługi:** niepotrzebne. Aplikacja i firmware są wydawane razem (założenie wyżej), więc INFO i bity `capabilities` bez zmian, a `UNSUPPORTED_TYPE` dla nowych typów traktowane jest jak każdy inny błąd zgodności.

## 4. Firmware

### 4.1. Dane: schemat obrazu 2

- Rekord klienta rośnie o `name_len[1] || name[20]` (21 B); `BRAMA_IMAGE_SCHEMA = 2`, `IMAGE_LEN` odpowiednio większy. Walidacja nazwy przy zapisie i odczycie: długość ≤ 20, UTF-8, brak znaków sterujących.
- **Bez migracji.** Obraz schematu 1 (inna długość blobu) jest nierozpoznawany jak uszkodzony; sterownik wymaga resetu autoryzacji (przycisk 10 s) i ponownej rejestracji. Dotychczasowy test migracji schematu (15.4) pozostaje jako mechanizm ogólny, ale ta zmiana go nie używa. Zmiana przy okazji upraszcza kod (jeden format).
- **Procedura wdrożenia (do potwierdzenia na sprzęcie):** po wgraniu nowego firmware obraz schematu 1 jest nieczytelny, więc `app_main` przechodzi w tryb błędu (pusty obraz, bez READY, 14.5 pkt 5). Reset przyciskiem 10 s zapisuje nowy, pusty obraz schematu 2, ale w trybie błędu `reset_restores_control = false`, więc sterowanie wraca dopiero po `reboot`. Kolejność: wgranie, przycisk 10 s, `reboot`, rejestracja telefonów. Nie używać `erase-flash`: usuwa dane fabryczne (`K_setup`, PIN, `device_id`) i wymusza nowy provisioning oraz etykietę.

### 4.2. Aktor

- `LIST_CLIENTS`: odczyt obrazu i `links[]` w aktorze (tym samym zadaniem, bez blokad), bez zapisu NVS.
- `SET_CLIENT_NAME`: `brama_image_next`, zmiana nazwy rekordu żądającego, `brama_image_commit`. **`auth_generation` bez zmian** (nie wpływa na reguły ani sesje).
- `REMOVE_CLIENT`: kolejność sprawdzeń: `INVALID_ARGUMENT` (długość), `BUSY` (OTA/reset), `CANNOT_REMOVE_SELF`, `CLIENT_NOT_FOUND`, `CLIENT_OWNS_RULE` (`rule.has_owner` i `owner_id` = wskazany; dotyczy też WAITING, BOOT_WAIT, BLOCKED i FAULT z zachowanym właścicielem), potem transakcja: `brama_image_next` → `brama_image_remove_client` → `brama_image_commit`. Po commit: usunięcie bondu z magazynu stosu BLE, rozłączenie łącza usuwanego klienta (jeśli jest), czyszczenie jego wpisów w kolejce aktora, ACK `CLIENT_REMOVED`. Przy błędzie commit: ACK `STORAGE_FAILURE`, obraz bez zmian.
- **`auth_generation` nie rośnie przy usunięciu** (inaczej niż w konsolowym `remove-client`). Aktualność wszystkich komend sprawdza już `link_current` m.in. przez `brama_image_find(client_id) >= 0`, więc nic z usuniętego telefonu nie wykona się po commit, a pozostałe sesje, reguła i kopia RTC (`auth_generation`, 14.5) zostają. Brama otwarta z autozamykaniem przez inny telefon nadal się zamknie. **Do weryfikacji w implementacji:** testy hosta na operacje usuwanego klienta zakolejkowane przed commit (muszą zostać odrzucone) i na przeżycie reguły innego klienta.
- Kolejność: ACK idzie przez łącze żądającego, rozłączenie łącza usuwanego następuje po zakolejkowaniu ACK. Własne łącze żądającego nie jest ruszane.

- **Duplikat `LIST_CLIENTS` (znalezione przy implementacji 18.11.2026):** odpowiedź `CLIENT_LIST` (do 351 B) nie mieści się w pamięci podręcznej odpowiedzi sesji (`BRAMA_CACHED_RESPONSE_MAX` = 64 B), więc retransmisja tego samego żądania po `T_response` (13.4) unieważniałaby sesję (seq ≠ last+1). Żądanie jest bezstanowym odczytem, więc sesja zapamiętuje skrót ostatniego `LIST_CLIENTS` i wykonuje identyczny duplikat ponownie (nowa odpowiedź z nowym seq; telefon dopasowuje ją po `request_seq` w payloadzie). Zrobione 18.11.2026 (`list_hash` w sesji, test na wektorze `c2s_9`).
- **Bufory obrazu NVS na stercie:** schemat 2 powiększył obraz o 168 B, co przepełniło statyczny DRAM (o 160 B); bufory `brama_image_load`/`brama_image_commit` są teraz alokowane na stercie (przejściowo ok. 4 KB w `load` i 2,9 KB w `commit`), co zwolniło ok. 6 KB DRAM.

### 4.3. BLE

- `brama_ble`: po usunięciu klienta (i po resecie przyciskiem, dla każdego bondu starego obrazu) aktor woła `env.client_removed(ctx, bond)`; `brama_ble_client_removed` usuwa wpis tożsamości z resolving list kontrolera (`ble_hs_pvcy_remove_entry` przy zatrzymanej reklamie, potem odświeżenie reklamy). Magazyn zatwierdzonych bondów to obraz, więc stos nie wymaga dalszego sprzątania.
- **Znalezione na sprzęcie (18.11.2026):** `ble_gap_unpair` nie nadaje się do tego: zwraca `BLE_HS_EBUSY` przy aktywnej reklamie (`ble_gap_adv_active()`), a sterownik reklamuje się zawsze, więc wpis usuniętego telefonu zostawał w kontrolerze. Kontroler rozwiązywał wtedy RPA tego samego telefonu do adresu tożsamości, host używał go w SMP zamiast adresu z powietrza, a f6 się nie zgadzało: `DHKey check failed` (`enc_change status=1035`) przy ponownym parowaniu aż do restartu. Ten sam skutek miał reset przyciskiem bez restartu. Poprawka potwierdzona w logu: `resolving list: usunięto … rc=0`, ponowna rejestracja usuniętego telefonu przechodzi bez restartu.
- **Plan zapasowy** (restart po ACK) nie jest potrzebny: usuwanie na żywo działa po poprawce powyżej.
- Telefon usunięty z sterownika, łączący się dalej: bond nieznany po stronie sterownika, więc łącze trafia na filtr 8.3 (poza oknem nieznane telefony są odrzucane) albo na `HANDSHAKE_REJECT`/`AUTH_FAILED`: ścieżka identyczna jak po resecie przyciskiem (bez zmian w firmware).

### 4.4. Konsola i dokumentacja kodu

- `info` wypisuje także nazwy klientów. `remove-client` zostaje (z `auth_generation + 1` i `reboot`, 15.4).
- 15.2/15.2.1: lista dozwolonych zapisów NVS rozszerzona o `REMOVE_CLIENT` i `SET_CLIENT_NAME` (nadal tylko aktor).

### 4.5. Testy hosta (`firmware/test/host`)

- Obraz: round-trip schematu 2 (nazwy), obraz schematu 1 odrzucony jak uszkodzony, nieprawidłowa nazwa → obraz uszkodzony, nowszy schemat → `NEWER_SCHEMA`.
- Aktor, `LIST_CLIENTS`: flagi (żądający, połączony, właściciel), zero wpisów niemożliwe (żądający jest zawsze), 8 wpisów, brak zapisu NVS.
- Aktor, `REMOVE_CLIENT`: usunięcie połączonego i niepołączonego, `CANNOT_REMOVE_SELF`, `CLIENT_NOT_FOUND`, `CLIENT_OWNS_RULE` (po CLOSE przechodzi), niepoprawna długość, błąd commit (obraz bez zmian, `STORAGE_FAILURE`), operacje usuniętego klienta w kolejce odrzucone, reguła innego klienta i RTC nietknięte, `auth_generation` bez zmian, BUSY podczas OTA.
- Aktor, `SET_CLIENT_NAME`: poprawna nazwa, pusta (czyści), 20 B, 21 B, zły UTF-8, znak sterujący, błąd commit.
- Wektory `commands.txt` i `negative_client.txt` (nowe typy i wyniki), wspólne z Kotlinem.

## 5. Telefon

### 5.1. `:protocol`

`MessageType` (`LIST_CLIENTS`, `REMOVE_CLIENT`, `SET_CLIENT_NAME`, `CLIENT_LIST`), `AckResult` (nowe kody), `SessionRequest` (trzy nowe), kodek wpisu listy `ClientEntry(clientId, name, isSelf, isConnected, ownsRule)`; zdarzenie sesji `ClientListReceived`. `FakeGate` i testy wektorów.

### 5.2. `:ble`

Przekazanie nowych żądań/zdarzeń przez `BleGateLink`; `SimulatedGate` obsługuje listę, usuwanie i nazwy (w tym odmowy).

### 5.3. `:domain`

- `GateRepository`: `refreshClients()`, `removeClient(clientId)`, `renameSelf(name)`; stan `GateUiState.clients: ClientsUi` (`Idle` = lista nieaktualna, `Loading`, `Ready(list)`, `Error(message)`) i `clientsAvailable` (READY, ACTIVE, brak innego żądania); żądanie zarządzania zajmuje wspólną kolejkę (`auxInFlight`, wcześniej `windowInFlight` tylko dla okna rejestracji). `LinkEvent.ClientList` niesie odpowiedź na `ListClients`; wyniki usunięcia i nazwy to `CommandResult`. Po zmianie lista jest odświeżana od razu albo oznaczana `Idle`, a ekran odświeża ją, gdy sesja jest wolna.
- **Nazwa własna (zmiana przy implementacji):** po potwierdzeniu nowej rejestracji (pierwszy READY, `confirmCredential`) domena wysyła `SET_CLIENT_NAME` z nazwą z portu `phoneName` (`:app`: `defaultPhoneName(Build.MANUFACTURER, Build.MODEL)`, przycięta przez `ClientName.truncate` do 20 B na granicy znaku), jednorazowo i bez komunikatu; gdy żądanie nie może wyjść od razu (np. PING w toku), idzie po pierwszej odpowiedzi. Nowy rekord ma pustą nazwę z konstrukcji, więc listowanie przed wysyłką jest zbędne. Błąd nazwy nie przerywa sesji; użytkownik zmienia nazwę ręcznie.
- Po `CLIENT_REMOVED` aplikacja odświeża listę. Teksty (`MessageId` + `Texts.kt`): usunięto telefon, telefon już usunięty, nie można usunąć siebie, telefon zarządza otwartą bramą (zamknij bramę albo poczekaj na autozamknięcie), błąd zapisu.

### 5.4. `:app` (UI)

- Ustawienia → karta „Telefony z dostępem” (aktywna w READY) → ekran **Telefony z dostępem**: nagłówek z ilustracją jak w pozostałych ekranach, karty wierszy: nazwa (lub „Telefon N” dla bez nazwy), znaczniki „Ten telefon”, „Połączony”, „Zarządza bramą”; przy innych telefonach przycisk „Usuń”; przy własnym „Zmień nazwę” (okno z polem, limit 20 znaków).
- Dialog usuwania: „Usunąć „{nazwa}”? Ten telefon straci dostęp do bramy. Aby wrócił, trzeba go zarejestrować od nowa (okno rejestracji).”; przyciski Usuń (czerwony)/Anuluj. Przy właścicielu reguły przycisk wyłączony z objaśnieniem.
- Stan ładowania i błędu jak w innych ekranach. Teksty zapisane wg stylu bez dodatkowych komunikatów statusu na ekranie głównym.
- Android Auto i powiadomienie: bez zmian.

## 6. Wdrożenie

Jeden egzemplarz sterownika i 3 telefony: wgrać nowy firmware, zresetować autoryzację przyciskiem, zainstalować nową aplikację, zarejestrować telefony od nowa (QR/PIN). Nie ma ścieżki zgodności ze starą wersją, migracji ani trybu „firmware bez funkcji”.

## 7. Testy akceptacyjne (do dopisania w rozdziale 18)

| ID | Scenariusz | Oczekiwanie |
|---|---|---|
| A90 | Lista z 3 telefonami, jeden rozłączony; kolejność rejestracji; nazwa domyślna nadana po pierwszej sesji; zmiana nazwy własnej | Wszystkie 3 widoczne, znaczniki „Ten telefon”/„Połączony” zgodne ze stanem, nazwy wypełnione, zmiana nazwy widoczna na pozostałych telefonach po odświeżeniu. |
| A91 | Usuń telefon: połączony, rozłączony, zaraz po usunięciu jego próba połączenia, usuwany podczas ruchu bramy | Usunięty znika z listy i z `info`; jego łącze zostaje rozłączone; jego komendy nie wykonują się; inne telefony i reguła auto-CLOSE (brama otwarta przez inny telefon) działają bez przerwy; brak resetu hosta, `task_wdt`, `Guru`. |
| A92 | Usunięty telefon łączy się ponownie; rejestracja od nowa QR i PIN-em | Pokazuje stan „brama nie zna tego telefonu” (jak po resecie); po oknie rejestracji i QR dostaje dostęp jako nowy klient. |
| A93 | Odmowy: usuń siebie (UI i wymuszone), usuń właściciela uzbrojonej reguły, usuń w trakcie OTA, usuń nieistniejącego; nazwa 21 B, zły UTF-8; zanik zasilania w trakcie commit | Wynik jak w §3, bez zmian w obrazie NVS; po zaniku zasilania w trakcie commit obraz poprawny (stary lub nowy, nigdy uszkodzony), żaden inny klient nie znika. |

## 8. Plan implementacji

1. Specyfikacja i dokumenty (po zatwierdzeniu projektu).
2. Firmware: schemat 2 (bez migracji) → nowe typy `brama_proto` → aktor (`LIST`, `NAME`, `REMOVE`) → BLE (bond) → konsola → testy hosta i wektory.
3. Android: protokół → `SimulatedGate`/`FakeGate` → domena → UI.
4. Testy na sprzęcie A13/A16/A80 po wgraniu firmware i ponownej rejestracji (A90–A93). Zasoby: sprawdzić `mem` (lista 351 B w sterowniku na stosie aktora, lepiej na stercie, DRAM statyczny 98,9%).
5. `doc/implementation_status.md` po każdym kroku.

Szacunek: ok. 1,5 dnia (firmware z testami ok. pół dnia–1 dzień, Android ok. 1 dzień, sprzęt ok. pół dnia); bez migracji i zgodności wersji jest to mniej niż w pierwszej wersji projektu.

## 9. Do decyzji

1. **Miejsce nazwy.** Przyjęto pole w rekordzie (schemat 2). Skoro i tak jest reset, osobny klucz NVS nie ma już przewagi.
2. **`auth_generation` przy usuwaniu.** Proponuję bez zmiany (§4.2), żeby usuwanie nie rozbrajało reguły i nie rozłączało pozostałych telefonów. Wariant konsolowy (zmiana generacji, restart) jest prostszy, ale odłącza wszystkich i rozbraja regułę (brama otwarta zostaje otwarta).
3. **Bond na żywo czy restart** (§4.3): na żywo, restart jako plan zapasowy po teście na sprzęcie.
4. **Usuwanie właściciela uzbrojonej reguły:** odmowa (jak 9.5); zgubiony telefon, który właśnie otworzył bramę, przestaje być właścicielem po autozamknięciu (ok. 30 s), więc odmowa jest przejściowa. Alternatywa: pozwolić i rozbroić regułę (ryzyko bramy otwartej bez autozamykania).
5. **Zmiana nazwy własnej w UI:** w pierwszej wersji tak (koszt niski, wspólny kod z nazwą domyślną).
