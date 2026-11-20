# Projekt: naprawa bondu przy zachowanym `K_client` (spec 9.5)

Status: zatwierdzony 28.10.2026 (decyzje §6 przyjęte bez zmian). Data: 28.10.2026.
Specyfikacja: `doc/PLAN_I_SPECYFIKACJA_BRAMY.md` 9.5, 8.3, 10.4, 12.4, 15.2.1, A33, A42, A43, A57.

## 1. Stan wyjściowy

- **Firmware.** Ponowne parowanie w oknie (`BLE_GAP_EVENT_REPEAT_PAIRING`) kieruje nowe klucze do stagingu łącza i ukrywa stary bond przed stosem (`repairing`). `on_encrypted` oznacza jednak takie łącze jako `approved`, bo adres tożsamości zgadza się z obrazem, więc zwykły SESSION kończy się READY na kluczach **niezatwierdzonych**. Po rozłączeniu wraca stary bond i telefon znów nie może zaszyfrować łącza. Naprawa z innym adresem tożsamości (CANDIDATE + SESSION_BEGIN) kończy się AUTH_FAILED (`client_key_for_peer`). Brak transakcji naprawy w aktorze.
- **Telefon.** `connect()` bez bondu kończy próbę po INFO zdarzeniem `Disconnected`; domena ponawia krótkimi odstępami, a użytkownik nie dostaje żadnej wskazówki. Nie ma akcji naprawy.

Typowy scenariusz (A33): użytkownik użył „Zapomnij urządzenie” albo Android zgubił bond. Telefon ma nadal `client_id`/`K_client`, a jego adres tożsamości zwykle się nie zmienia, więc sterownik widzi ponowne parowanie znanego peer.

## 2. Przepływ

1. Telefon łączy się zwykle (`connect`) i stwierdza po INFO brak bondu → `LinkEvent.BondMissing`. Domena przestaje ponawiać i pokazuje tekst z instrukcją oraz akcję **„Napraw połączenie”**.
2. Użytkownik przytrzymuje przycisk sterownika 3 s i wybiera akcję → `GateLink.repairBond(deviceId, clientId, K_client)`: to samo co `connect`, ale z dozwolonym parowaniem systemowym (`createBond`).
3. Sterownik w oknie przyjmuje nowe SC bonding jako CANDIDATE (nowy peer) albo ponowne parowanie znanego peer — w obu przypadkach klucze w stagingu, łącze zajmuje jedyny kontekst CANDIDATE.
4. Zwykły handshake SESSION z `K_client`. Po poprawnym SESSION_CONFIRM sterownik **wstrzymuje** SESSION_READY i zleca aktorowi transakcję naprawy.
5. Aktor sprawdza warunki, zatwierdza w NVS nowy bond klienta (ten sam `K_client`, reguła bez zmian), publikuje obraz; stary bond znika razem z rekordem. Dopiero wtedy sterownik wysyła SESSION_READY ze snapshotem z tej chwili.
6. Telefon dostaje `Ready` → stan READY i powiadomienie „Połączenie ze sterownikiem naprawione”.

## 3. Firmware

### 3.1. Łącze (`brama_ble`)

- `on_encrypted`: łącze z kluczami w stagingu (`repairing` albo nowy peer) **nie jest** `approved`; zajmuje kontekst CANDIDATE (tylko w oknie i gdy wolny — jak dziś dla nowego peer). Usuwa to dzisiejszą ścieżkę READY na niezatwierdzonych kluczach.
- Na łączu CANDIDATE `client_key` zwraca klucz dowolnego ACTIVE (bez sprawdzania peer), a nowe wywołanie środowiska sesji `session_admit` (po poprawnym HMAC SESSION_BEGIN) zwraca:
  - `WINDOW_CLOSED` — okno zamknięte w chwili BEGIN (12.4: ujawniane po poprawnym HMAC `K_client`);
  - `REPLACEMENT_BLOCKED` — klient ma inne łącze z ukończonym SESSION (9.5 „brak aktywnego łącza dotychczasowego klienta”) albo adres tożsamości nowego bondu należy do **innego** ACTIVE (decyzja §6.3);
  - inaczej: przyjęte jako naprawa.
  Na łączu `approved` `session_admit` zawsze przyjmuje zwykłą sesję (bez zmian w zachowaniu).
- `BRAMA_EV_REPAIR_COMMIT` → nowy token próby, wiadomość `BRAMA_AMSG_REPAIR_COMMIT` (client_id, bond ze stagingu) do aktora.
- Wynik aktora `repair_result(link, token, code)`:
  - **Nieukończona rejestracja (17.11.2026):** gdy łącze ze stagingiem, którego peer nie pasuje do żadnego klienta obrazu, kończy się bez `PAIR_COMMIT`, `link_closed` usuwa peera ze stosu i z listy rozwiązywania kontrolera (`ble_gap_unpair` oraz `ble_hs_pvcy_remove_entry`; samo `ble_gap_unpair` zwraca ENOENT, bo staging jest już wyzerowany), inaczej kolejna rejestracja z tego samego adresu RPA była odrzucana.
  - OK: łącze `approved`, koniec stagingu i `repairing`, sesja wysyła SESSION_READY, potem zwykłe `SESSION_READY` do aktora (nowy token sesji). Gdy zmienił się adres tożsamości — `ble_gap_unpair(stary)` usuwa wpis z resolving list (sprawdzić w etapie 0). Accept list odświeża `brama_ble_refresh_advertising` z opublikowanego obrazu.
  - odmowa: HANDSHAKE_REJECT z kodem, staging usunięty, łącze rozłączone po wysłaniu odmowy (inaczej kolejny BEGIN na tym łączu działałby na kluczach, których nie ma w obrazie).
  - Wynik dla nieaktualnego tokenu (łącze zniknęło, nowy BEGIN): tylko aktualizacja flag łącza, jeśli ono nadal istnieje; bond zatwierdzony przez aktora pozostaje (telefon ma ten bond u siebie).

### 3.2. Sesja (`brama_session`)

- Nowy stan `BRAMA_SS_REPAIR_COMMITTING`: po poprawnym SESSION_CONFIRM naprawy wyprowadzone klucze, brak odpowiedzi, zdarzenie `BRAMA_EV_REPAIR_COMMIT`. Identyczny CONFIRM w tym stanie — bez odpowiedzi (jak COMMIT w toku, 11.3); wiadomości sesji ignorowane (brak READY).
- `brama_session_repair_finish(s, env, code, out)`: `0` → SESSION_READY (seq 1, snapshot z `snapshot_for` w tej chwili, zapamiętany dla duplikatu CONFIRM), stan READY; inaczej HANDSHAKE_REJECT z `code` i reset.
- Nieudane `session_admit` → odmowa z kodem i reset próby (na działającej sesji — ignorowane, jak dziś).

### 3.3. Aktor (`brama_actor`)

`on_repair_commit`: wymaga `control_enabled`, zgodnej `auth_generation`, istniejącego klienta i bondu w wiadomości; ponownie sprawdza oba warunki `REPLACEMENT_BLOCKED` na bieżącym stanie (15.2.1). Buduje obraz na bieżącym (`brama_image_next`), podmienia wyłącznie `bond` tego klienta, commit; publikacja po potwierdzonym commit. Reguła i `K_client` bez zmian (A42). Błąd commit → `STORAGE_FAILURE`, stary obraz i bond zostają (A25). Reset przed wiadomością zmienia `auth_generation` → odmowa (A43).

## 4. Telefon

### 4.1. `:ble`

- `GateLink.repairBond(deviceId, clientId, clientKey): Long` — `Conn(repair = true)`; po INFO `createBond`, gdy brak bondu (jak `pair()`), potem odczyt wersji, CCCD, MTU i zwykły `SessionHandshake`.
- `connect()` bez bondu → `LinkEvent.BondMissing` zamiast `Disconnected` (połączenie zakończone, jak dotąd).
- Nieudane `createBond` w `repairBond` → `LinkEvent.BondFailed` (sterownik odrzucił parowanie — zwykle zamknięte okno — albo Android trzyma nieaktualny bond).
- Odmowy i błędy handshake bez zmian (`HandshakeRejected`, `HandshakeFailed`).

### 4.2. `:domain`

- `GateUiState.bondRepairAvailable: Boolean` — pokazuje akcję „Napraw połączenie”; ustawiane po `BondMissing`, `BondFailed` i odmowach w trakcie naprawy; czyszczone po READY i „Usuń z telefonu”.
- Intencja `repairBond()` (świadoma akcja użytkownika: zeruje backoff) → `link.repairBond(...)`.
- `BondMissing` → `teardownLink`, `handshakeProblem = BOND_MISSING`, bez automatycznych ponowień (jak odmowy nieponawiane, 12.4) do akcji użytkownika.
- W trakcie naprawy: `BondFailed` → `REPAIR_BOND_FAILED`; `HandshakeRejected` → tekst z 12.4 (STORAGE_FAILURE → `REPAIR_STORAGE_FAILURE`); `Ready` → `notification = REPAIR_DONE`, dalej zwykła sesja.
- Android Auto: tylko tekst (naprawa wymaga telefonu i przycisku sterownika).

### 4.3. `:app`

Ekran sterowania: przy `bondRepairAvailable` przycisk „Napraw połączenie” (zaokrąglona akcja tonalna nad przyciskami OTWÓRZ/ZAMKNIJ). **Od 17.11.2026 ekran główny nie wyświetla już tekstu problemu** (decyzja właściciela: bez tekstów statusów), więc instrukcja `BOND_MISSING` („przytrzymaj przycisk sterownika 3 s…”) nie jest widoczna; wskazówkę o oknie rejestracji zawiera tylko opis pozycji „Otwórz okno rejestracji” w Ustawieniach. Decyzja właściciela 17.11.2026: pod przyciskiem „Napraw połączenie” pokazać instrukcję (tekst problemu z domeny: `BOND_MISSING` albo `REPAIR_BOND_FAILED`, `MainUi.repairHint`); to jedyny tekst statusu na ekranie głównym, widoczny tylko przy `bondRepairAvailable`.

## 5. Testy

- Firmware (host, Unity): sesja — wstrzymane READY, duplikat CONFIRM, `repair_finish` OK/odmowa, `WINDOW_CLOSED`/`REPLACEMENT_BLOCKED` po poprawnym HMAC, AUTH_FAILED przy złym HMAC; aktor — podmiana bondu z zachowaniem `K_client` i reguły, blokada przez łącze klienta, konflikt adresu tożsamości, błąd NVS, nieaktualna generacja, licznik zapisów NVS. `brama_ble` tylko kompilacja (Ubuntu) + lista kontrolna etapu 0.
- `:ble` (rdzeń, symulator): `BondMissing`, naprawa z `createBond` → `Ready`, `BondFailed`, naprawa przy istniejącym bondzie.
- `:domain`: przepływ BondMissing → repair → Ready, odmowy w trakcie naprawy, brak ponowień, czyszczenie flagi.
- Etap 0 (płytka): A33 w obu wariantach (ten sam i nowy adres tożsamości), A57 (naprawa przy aktywnym starym łączu), czy Android po `security_initiate` sterownika sam nie pokazuje dialogu parowania przy zwykłym `connect`.

## 6. Do decyzji

1. **Nowe teksty w rozdziale 3** (spec ich nie ma):
   - `BOND_MISSING`: „Brak parowania Bluetooth ze sterownikiem — przytrzymaj przycisk sterownika 3 s i wybierz „Napraw połączenie””;
   - `REPAIR_BOND_FAILED`: „Parowanie Bluetooth nie powiodło się — sprawdź, czy okno rejestracji jest otwarte; jeśli to się powtarza, usuń sterownik w ustawieniach Bluetooth („Zapomnij urządzenie”)”;
   - `REPAIR_STORAGE_FAILURE`: „Błąd pamięci sterownika — naprawa połączenia nie została zakończona”;
   - `REPAIR_DONE`: „Połączenie ze sterownikiem naprawione”;
   - etykieta przycisku: „Napraw połączenie”.
2. **Uruchomienie naprawy świadomą akcją**, nie automatycznie po `BondMissing` — parowanie systemowe i otwarte okno wymagają obecności użytkownika przy sterowniku.
3. **Adres tożsamości nowego bondu należy do innego ACTIVE** → `REPLACEMENT_BLOCKED` (spec nie opisuje; alternatywą byłoby zastąpienie tamtego rekordu, czego 9.5 dla naprawy nie przewiduje).
4. **Odmowa naprawy rozłącza łącze** po wysłaniu HANDSHAKE_REJECT (10.4 dla CANDIDATE), zamiast czekać do T_attempt.

## 7. Wyniki przeglądu (28.10.2026)

Niezależny przegląd: 1 istotna i 5 drobnych uwag, wszystkie poprawione.

- **Istotna:** `on_encrypted` zatwierdzał łącze ze świeżymi kluczami, gdy adres tożsamości był znany, a NimBLE nie zgłosił `REPEAT_PAIRING` (np. telefon z nowym IRK), albo przy ponownym parowaniu na łączu już zaszyfrowanym. Teraz ocena od nowa przy każdym szyfrowaniu; `approved` wyłącznie bez stagingu i bez `repairing` — inaczej CANDIDATE. Obcy peer podający cudzy adres tożsamości nie dostaje więc zatwierdzonego łącza.
- Porzucenie próby w trakcie transakcji (nowy BEGIN, błędny CONFIRM) zeruje token próby; łącze zachowuje staging, a ponowiona próba zatwierdza ten sam bond.
- `ble_gap_unpair` starego adresu tożsamości na podstawie wyniku aktora (`repair_result(..., replaced)`), także gdy łącza naprawy już nie ma.
- **Odstępstwo od 9.5 („ponownie sprawdzić token próby”):** aktor zatwierdza naprawę niezależnie od tego, czy łącze jeszcze trwa — dowodem jest uwierzytelniony SESSION_CONFIRM i aktualna generacja; telefon ma już nowy bond, a zachowanie starego zostawiłoby go bez szyfrowania. Token sprawdza warstwa BLE przy wysłaniu READY.
- Zerowanie kopii bondu (LTK/IRK) w wiadomości aktora, kolejce i stagingu łącza.
- Komentarz do odmów PAIR_BEGIN na działającej sesji (kod szczegółowy po poprawnym HMAC; ogólne blokady milczące).

## 8. Wyniki na sprzęcie (16–17.11.2026)

Naprawa bondu przy oknie otwartym z innego telefonu i rejestracje z kilku telefonów (A13, A16, trzeci telefon) odtworzyły dwa błędy, oba naprawione:

- **Limit bondów NimBLE:** `CONFIG_BT_NIMBLE_MAX_BONDS` 3 przy `BRAMA_MAX_CLIENTS` 8: `ble_sm_chk_store_overflow` liczy klientów obrazu (nasz magazyn) i przy trzech bondach plus staging kończył parowanie `Pairing Failed 0x08 (Unspecified)` bez zdarzenia GAP. Poprzednie hipotezy (RPA/IRK, drugie łącze) były błędne. Teraz 9 (≥ 8 + staging); błąd ujawniał się dopiero przy trzech klientach, wcześniejsze testy miały dwóch.
- **Wpis w resolving liście po nieukończonej rejestracji** (zob. §3.1): usuwany w `link_closed`.
- **Bond systemowy telefonu:** nieudana rejestracja albo „Usuń z telefonu” zostawiały bond systemowy, który psuł następne parowanie; aplikacja usuwa go (`ble_design.md` §4.6, tylko przed `PAIR_COMMIT`). Naprawa nie jest tą ścieżką: bond naprawiany przez `repairBond()` jest tworzony od nowa przez telefon w oknie.
- Sprawdzone na sprzęcie (obie sytuacje z oknem z telefonu): rejestracja QR i PIN-em trzeciego telefonu, naprawa bondu telefonu A z okna otwartego przez B po poprawkach przeszły; A33 w wariancie ze zmienionym adresem tożsamości nadal do sprawdzenia osobno.
