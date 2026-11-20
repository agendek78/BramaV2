# Projekt: okno rejestracji otwierane z zarejestrowanego telefonu

Status: zatwierdzony 28.10.2026 (decyzje §6 przyjęte bez zmian). Data: 28.10.2026.
Decyzja właściciela (28.10.2026): przycisk sterownika zostaje bez zmian (okno 3 s, reset 10 s); dodatkowo okno rejestracji może otworzyć zarejestrowany telefon, gdy płytka jest zamknięta w obudowie sterownika głównego. Zgubiony telefon odwołuje się jak dotąd resetem przyciskiem (otwarcie skrzynki); od 18.11.2026 także usunięciem z listy telefonów na innym zarejestrowanym telefonie (`doc/client_management_design.md`).
Specyfikacja: `doc/PLAN_I_SPECYFIKACJA_BRAMY.md` 1, 3, 8.2, 8.3, 9.1, 9.4, 9.5, 11.3, 11.4, 13.1–13.3, 19 (testy akceptacyjne).

## 1. Zasada

Telefon ACTIVE z sesją READY wysyła uwierzytelnioną komendę `OPEN_PAIRING_WINDOW`. Sterownik otwiera **to samo** okno co przycisk 3 s: 60 s, ta sama reklama bez filtra, jeden kontekst CANDIDATE, te same limity prób, ten sam wzorzec LED. Rejestracja nowego telefonu nadal wymaga QR albo PIN-u, a naprawa bondu — `K_client`.

Przykłady użycia:
- drugi telefon domownika: pierwszy telefon otwiera okno, drugi skanuje QR albo wpisuje PIN;
- naprawa bondu telefonu A: okno otwiera telefon B (telefon A bez bondu nie ma sesji, więc sam okna nie otworzy) — inaczej przycisk;
- pierwszy telefon po instalacji albo po resecie: tylko przycisk (brak ACTIVE).

## 2. Ocena bezpieczeństwa

- Okno jest dalej zdarzeniem rzadkim i autoryzowanym: otworzyć je może wyłącznie posiadacz ważnego `K_client` w zasięgu BLE (pełne uwierzytelnienie sesji, AES-GCM, liczniki, 13.4). Osoba bez zarejestrowanego telefonu nie otworzy okna — podatności z analizy „okno zawsze otwarte” (stały dostęp po wycieku QR/PIN, odgadywanie PIN-u w nieskończonym oknie, zajmowanie CANDIDATE, stała ekspozycja parowania) nie występują.
- **Nowe względem przycisku:** posiadacz telefonu ACTIVE (także skradzionego) może dodać kolejne telefony, jeśli zna QR albo PIN. Taki posiadacz i tak steruje bramą; reset przyciskiem usuwa wszystkie telefony, także dodane w ten sposób. Zapisać jawnie w 9.1 i w instrukcji.
- **PIN:** dziś każde otwarcie okna zeruje globalny licznik 5 nieudanych potwierdzeń PIN-u (11.4). Gdyby to robiło także okno z telefonu, posiadacz telefonu ACTIVE mógłby otwierać okna w pętli dla odgadywania PIN-u przez inne urządzenie. Ponieważ taki posiadacz już steruje bramą, zysk atakującego jest niewielki; mimo to proponuję ograniczenie (decyzja §6.2).
- Bez zmian: sesja nie zapisuje NVS (otwarcie okna tylko RAM), reset ma pierwszeństwo i zamyka okno, `control_enabled=false` blokuje komendę.

## 3. Protokół

- Nowy typ sesji `03` `OPEN_PAIRING_WINDOW`, c2s, AES-GCM, payload dokładnie 0 B (ramka 38 B jak CLOSE). Odpowiedź: `COMMAND_ACK` z `request_type = 03`.
- Nowy wynik ACK `08` `WINDOW_OPENED`: okno otwarte (albo uruchomione od nowa) na 60 s. Pozostałe wyniki dla tego typu: `INVALID_ARGUMENT` (payload ≠ 0 B), `UNSUPPORTED_TYPE` (firmware bez funkcji). Nie dotyczy go BUSY, interlock ani reguła — to nie jest komenda ruchu.
- Bit 7 `capabilities` = otwieranie okna z telefonu; INFO `capabilities = 0xFF`. Telefon pokazuje akcję tylko przy tym bicie.
- Komenda zajmuje wspólną kolejkę jednego żądania (13.3) jak PING; duplikat dostaje zachowany ACK (13.4) bez ponownego otwarcia okna.
- Wektory: przypadek w `commands.txt` (OPEN_PAIRING_WINDOW i ACK `WINDOW_OPENED`), `ack_window_opened` w `negative_client.txt` (z pętli po wynikach).

## 4. Firmware

- Aktor: `on_command` dla typu `03` — sprawdzenia jak dla komend (tokeny, generacja, `control_enabled`), payload 0 B, `brama_auth_window_open` (bez zerowania licznika PIN-u według §6.2), ACK `WINDOW_OPENED` ze snapshotem. Reklama odświeżana jak przy przycisku.
- INFO bit 7. Log diagnostyczny: okno otwarte przez telefon (bez `client_id` w logu produkcyjnym, jak dotąd).
- **Dostępność PIN w reklamie (17.11.2026):** przy otwartym oknie reklama niesie pole producenta `0xFFFF` z liczbą pozostałych prób PIN-u (0–5, spec 8.3); `info` wypisuje „rejestracja PIN-em: dostępna/niedostępna, próby PIN N/5, okno otwarte/zamknięte”. Reklama jest odświeżana także przy zmianie licznika (`brama_auth_pin_attempts_left`).
- Testy hostowe: otwarcie i ponowne otwarcie, INVALID_ARGUMENT, nieaktualna sesja/generacja bez skutku, `control_enabled=false`, brak zapisu NVS, licznik PIN-u wg §6.2, wektor.

## 5. Telefon

- `:protocol`: `MessageType.OPEN_PAIRING_WINDOW`, `AckResult.WINDOW_OPENED`, `SessionRequest.OpenPairingWindow`; `GateInfo.supportsPhoneWindow` (bit 7).
- `:ble`: bit 7 do `LinkInfo` (`pairingWindowSupported`).
- `:domain`: intencja `openPairingWindow()` (READY, ACTIVE, bit 7, brak komendy/OTA w toku); wynik w komunikacie, bez wpisu w historii komend ruchu; `GateUiState.pairingWindowAvailable`.
- `:app`: w Ustawieniach pozycja „Otwórz okno rejestracji” z opisem: drugi telefon albo naprawa połączenia innego telefonu. Android Auto: bez tej akcji.
- **Kreator nowego telefonu (17.11.2026):** nasłuchuje reklamy (`AndroidGateWatcher`) i przed wpisaniem PIN-u pokazuje „Pozostało prób PIN: N z 5” albo, przy 0 prób (restart sterownika, okno z telefonu go nie odnawia przez godzinę), wyłącza pole PIN z komunikatem „przycisk na bramie albo QR”. Powód: po restarcie okno z telefonu było otwarte, a PIN odrzucany bez wyjaśnienia (ogólny błąd „sprawdź PIN i czy okno jest otwarte”).

## 6. Do decyzji

1. **Wynik ACK:** nowy kod `08 WINDOW_OPENED` (proponuję) zamiast nadużywania `OUTPUT_TRIGGERED` („wyjście aktywowane”).
2. **Licznik PIN-u:** okno z telefonu nie zeruje globalnego licznika nieudanych potwierdzeń PIN-u, chyba że od ostatniego zerowania minęła 1 h; przycisk zeruje go zawsze (jak dziś). Skutek: bez dostępu do płytki najwyżej 5 prób PIN-u na godzinę; użytkownik, który pomylił PIN 5 razy, czeka do godziny albo używa QR. Godzina liczy się od startu płytki (zerowanie ostatnie = start), więc po restarcie okno z telefonu przez pierwszą godzinę nie odnawia prób (zmierzone 17.11.2026); aplikacja nowego telefonu informuje o tym zamiast ogólnego błędu.
3. **Teksty (rozdział 3):**
   - wynik `WINDOW_OPENED`: „Okno rejestracji otwarte na 60 s — dodaj telefon albo napraw połączenie na drugim telefonie”;
   - etykieta akcji: „Otwórz okno rejestracji”;
   - zmiana dwóch istniejących tekstów, aby nie wskazywały wyłącznie przycisku: WINDOW_CLOSED „Okno rejestracji jest zamknięte — przytrzymaj przycisk sterownika 3 s albo otwórz je z zarejestrowanego telefonu” oraz BOND_MISSING „Brak parowania Bluetooth ze sterownikiem — otwórz okno rejestracji (przycisk sterownika 3 s albo inny zarejestrowany telefon) i wybierz „Napraw połączenie””.
4. **Kto może otworzyć okno:** każdy ACTIVE (proponuję; w pierwszej wersji wszystkie telefony są równoprawne), bez osobnego uprawnienia „administratora”.

## 7. Wyniki przeglądu (28.10.2026)

Niezależny przegląd: 1 istotna i 5 drobnych uwag, poprawione.

- **Istotna:** okno z telefonu przy wyczerpanym liczniku PIN-u dopuszczało jedną próbę PAKE na każde otwarcie (licznik sprawdzany dopiero po porażce) — posiadacz telefonu ACTIVE otwierający okno w pętli dawał drugiemu urządzeniu nieograniczone próby. Teraz PAKE_ROUND1 wymaga `brama_auth_pin_attempt_allowed` (okno otwarte i licznik < 5); okno działa dalej dla QR i naprawy bondu.
- Restart (np. wymuszony aktualizacją OTA) nie odnawia prób PIN-u: po starcie licznik jest wyczerpany do przycisku albo okna z telefonu po godzinie pracy. Pierwszy telefon i tak wymaga przycisku; QR działa bez ograniczeń.
- Domena: nieznany wynik okna oznacza regułę jako nieaktualną; wynik innego żądania przy oknie w toku odtwarza sesję; pozycja w Ustawieniach widoczna tylko przy bicie 7 (`pairingWindowSupported`).
- Firmware: wpis diagnostyczny „Okno rejestracji otwarte przez telefon” (bez `client_id`).

## 8. Wyniki na sprzęcie (16–17.11.2026)

- **Naprawa bondu z okna otwartego przez inny telefon i nieudana rejestracja:** trzy przyczyny znalezione na płytce. (1) Limit bondów NimBLE `CONFIG_BT_NIMBLE_MAX_BONDS` 3 przy `BRAMA_MAX_CLIENTS` 8: czwarty bond (trzeci klient plus staging) kończył parowanie `Pairing Failed 0x08` bez zdarzenia GAP; teraz 9. (2) Wpis nieukończonej rejestracji zostawał na liście rozwiązywania kontrolera: `link_closed` usuwa go (`ble_gap_unpair` i `ble_hs_pvcy_remove_entry`). (3) Bond systemowy nowego telefonu zostawał po nieudanej rejestracji i psuł następną (sterownik zna go po IRK): aplikacja usuwa go przy rejestracji przerwanej przed `PAIR_COMMIT` i przy „Usuń z telefonu” (`BluetoothDevice.removeBond()` przez refleksję, sprawdzone na Androidzie 13 i 16).
- **Rundy PAKE poza blokadą:** EC-JPAKE (ok. 2–2,7 s) liczone w zadaniu `pake_worker` na kopii sesji; aktor i blokada BLE nie są blokowane, wynik stosowany tylko, gdy łącze nadal istnieje.
- **Rejestracja PIN-em po restarcie:** dopóki nie wciśnięto przycisku, PIN jest odrzucany mimo otwartego okna z telefonu (licznik wyczerpany po starcie); potwierdzone: po przycisku `5/5` i rejestracja PIN-em przechodzi, odliczanie w aplikacji 5→4→3.
- **Uwaga z przeglądu kodu (nie zmieniona):** `brama_auth_window_open_remote` przedłuża okno o 60 s przy każdym wywołaniu bez górnego limitu łącznego czasu; zgodne z §1–2 (posiadacz telefonu ACTIVE i tak steruje bramą), do decyzji, czy dodać limit.
- Testy akceptacyjne: A82 (bez zmian) oraz nowe A83 (dostępność PIN) i A84 (bond systemowy telefonu), spec rozdz. 18.

## 9. Uzupełnienie (18.11.2026)

Zgubiony telefon można odwołać zdalnie: `LIST_CLIENTS`/`REMOVE_CLIENT` z innego telefonu (`doc/client_management_design.md`, spec 9.4). Okno rejestracji z telefonu, licznik PIN-u i teksty z §6 bez zmian. Naprawa bondu innego telefonu (okno z telefonu B) pozostaje drogą dla telefonu, który nie został usunięty ze sterownika; telefon usunięty rejestruje się od nowa.
