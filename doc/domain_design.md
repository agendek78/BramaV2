# Projekt modułu `:domain`

Status: projekt do zatwierdzenia (po przeglądzie wykonawcy). Data: 27.10.2026.
Źródło wymagań: `doc/PLAN_I_SPECYFIKACJA_BRAMY.md` v1.3 (dalej „spec”), rozdziały 3, 4, 5.2, 6, 9.3–9.5, 11.3, 12.4.
Moduł zależny: `:protocol` (zakończony, `doc/implementation_status.md`).

## 1. Cel

`:domain` jest jedynym miejscem, w którym zapada decyzja, co użytkownik widzi i co może zrobić. Telefon (Compose) i Android Auto tylko renderują stan dostarczony przez `:domain`. Dzięki temu:

- oba interfejsy pokazują to samo i nie mogą przypadkiem sugerować położenia bramy (spec 3);
- wszystkie komendy, z obu interfejsów, przechodzą przez jedną kolejkę i jedno połączenie (spec decyzja 7, rozdz. 6);
- reguły bezpieczeństwa interfejsu (potwierdzenia, ostrzeżenia, brak ukrytych ponowień) są w jednym, testowalnym miejscu.

## 2. Zakres

### Cele

1. **Stan dla UI** jako `StateFlow` z identyfikatorami tekstów z zamkniętej listy spec 3 (decyzja użytkownika: tekst wybiera `:domain`, UI tylko tłumaczy identyfikator na zasób).
2. **Sterowanie**: OPEN(boolean) i CLOSE przez jeden aktor; blokada kolejnych poleceń w trakcie operacji; wyniki OUTPUT_TRIGGERED / REJECTED(AckResult) / OUTCOME_UNKNOWN; brak automatycznego ponowienia w nowej sesji (spec 4.1, 13.4).
3. **Polityka połączenia** (decyzja użytkownika: w `:domain`): kiedy łącze ma istnieć, kiedy wolno je zamknąć, ponawianie po odmowie handshake z backoffem (spec 4.2, 12.4).
4. **Rejestracja telefonu** (decyzja użytkownika: w pierwszej iteracji): QR → PAIR → zapis UNCONFIRMED → COMMIT → pierwsza sesja potwierdza ACTIVE, z recovery z spec 11.3.
5. **Reguła autozamykania w UI**: `RuleView` z flagą świeżości, odświeżanie przez PING po powrocie na ekran, powiadomienie DISARMED/REBOOT dla byłego właściciela (spec 3, 4.2, 12.2).
6. **Ostrzeżenia i potwierdzenia** zależne od stanu: CLOSE, „Rozłącz”, „Usuń z telefonu”, włączenie `autoCloseOnDisconnect`, instrukcja resetu (spec 3, 4.1, 9.4).
7. **Historia** ostatnich 200 prób sterowania, bez sekretów i surowych pakietów (spec 4.1).
8. **Priorytety wierszy dla ekranu samochodowego** (spec 5.2): `:domain` dostarcza uporządkowaną listę, `:car` tylko przycina.

### Poza zakresem

- **Naprawa bond (spec 9.5)** — zrealizowana osobno (28.10.2026): `doc/bond_repair_design.md` §4.2 (`BondMissing`, `repairBond()`, `bondRepairAvailable`).

- Mechanika BLE: skanowanie, GATT, MTU, fragmentacja, retransmisje i timeouty 10.4 — `:ble`.
- Keystore, DataStore, pliki — implementacje w `:app` za interfejsami z `:domain`.
- Foreground service, CompanionDeviceManager, uprawnienia Androida — `:app`; `:domain` jedynie przyjmuje informację o błędach systemowych (spec 3, „Błędy systemowe telefonu”).
- Teksty w językach — `strings.xml` w `:app`/`:car`.
- Skaner QR — `:app`; `:domain` przyjmuje tekst QR i parsuje go przez `:protocol`.

### Anty-cele (porażka nawet przy działającym kodzie)

- Jakiekolwiek pole, tekst lub wyprowadzenie sugerujące położenie bramy, także z historii.
- Automatyczne ponowienie komendy po OUTCOME_UNKNOWN lub ukryta zmiana boolean OPEN.
- Prezentowanie historycznego MONITORING jako aktualnego (`fresh = false` musi być widoczne).
- Dwa równoległe polecenia lub dwa połączenia z dwóch interfejsów.
- Tekst spoza zamkniętej listy spec 3 dla kategorii nią objętych.

## 3. Ograniczenia

- Czysty Kotlin/JVM, jak `:protocol` (JVM target 11, toolchain 21). Zależności: `:protocol`, `kotlinx-coroutines-core` 1.10.2; testy: JUnit 4, `kotlinx-coroutines-test` 1.10.2 (obie wersje dostępne offline).
- Czas i opóźnienia wstrzykiwane (zegar monotoniczny + `CoroutineScope`/dispatcher), aby testy backoffu i świeżości nie czekały naprawdę.
- Obiekty `:protocol` nie są bezpieczne wątkowo — `:domain` nie dotyka ich bezpośrednio; robi to `:ble` w swoim aktorze. `:domain` widzi tylko zdarzenia i typy wartości (`RuleSnapshot`, `AckResult`, `HandshakeRejectReason`, `ProvisioningQr`).
- Jedna brama w aplikacji (spec decyzja 8).

## 4. Architektura

```text
 :app (Compose) ─┐                         ┌─ CredentialStore   (impl. :app, Keystore)
 :car (Car App) ─┼─► GateRepository ───────┼─ SettingsStore     (impl. :app, DataStore)
                 │    (aktor, StateFlow)   ├─ HistoryStore      (impl. :app, plik)
                 │                         └─ GateLink          (impl. :ble)
```

### 4.1. `GateRepository` — jedyny punkt wejścia

- Wewnętrznie jeden aktor (kanał zdarzeń przetwarzany sekwencyjnie). Do aktora trafiają: intencje UI, zdarzenia `GateLink`, upływy timerów (backoff, ponowne próby rejestracji).
- Publikuje `StateFlow<GateUiState>`.
- Intencje (funkcje `suspend` lub nieblokujące `submit`):
  - `uiAttached(source: UiSource)` / `uiDetached(source)` — `UiSource.PHONE`, `UiSource.CAR`;
  - `open()` — używa bieżącej preferencji `autoCloseOnDisconnect` (spec 4.2: zmiana działa od następnego OPEN);
  - `requestClose(source)` → potwierdzenie dla tego źródła; `confirmClose(source)` / `cancelClose(source)`;
  - `requestDisconnect(source)` → ewentualne ostrzeżenie; `confirmDisconnect(source)`;
  - `connect()` — świadome „Połącz”; zdejmuje wstrzymanie po „Rozłącz” (§6.2);
  - `setAutoCloseDefault(enabled)` → przy włączaniu potwierdzenie z ostrzeżeniem (tylko telefon);
  - `refresh()` — PING na żądanie;
  - `startRegistration(qrText)`, `cancelRegistration()`;
  - `requestRemoveFromPhone()` → ostrzeżenia (tylko telefon); `confirmRemoveFromPhone()`;
  - `reportSystemProblem(problem: SystemProblem?)` — zgłaszane przez `:app` (Bluetooth OFF, brak uprawnień, brak startu usługi w tle, poświadczenie niedostępne przed odblokowaniem).

### 4.2. Port `GateLink` (implementuje `:ble`)

`:domain` zleca, `:ble` wykonuje mechanikę i raportuje zdarzenia przez `Flow<LinkEvent>`.

Polecenia:
- `connect(credential: ActiveCredential)` — sesja zwykła (SESSION) z `K_client`; `:ble` sam odtwarza sesję świeżym handshake na zachowanym łączu (spec 12.1), jeśli trzeba;
- `pair(qr: ProvisioningQr, clientId: ByteArray)` — próba PAIR; zgłasza `GrantReceived(kClient)` i czeka na `commitPairing()` albo `abortPairing()`;
- `send(request: SessionRequest)` — OPEN/CLOSE/PING, najwyżej jedno oczekujące (pilnuje `:domain`, `:ble` asertuje);
- `disconnect()`.

Zdarzenia (`LinkEvent`):
- `Connecting`, `SecuringLink`, `Authenticating`, `Rekeying` — dla `ConnectionState`;
- `Ready(snapshot)`, `RuleStatus(snapshot, applied)`, `Pong(snapshot, applied)`;
- `CommandResult(request, ack, snapshotApplied)` — z `SessionEvent.CommandAcknowledged`;
- `CommandUnknown(request)` — sesja unieważniona z nierozstrzygniętą komendą (spec 13.4);
- `SessionLost` — utrata sesji bez nierozstrzygniętej komendy;
- `HandshakeRejected(reason)`, `HandshakeFailed`;
- `GrantReceived(kClient)`, `PairingDone`;
- `Disconnected`.

### 4.3. Porty magazynów (implementuje `:app`)

- `CredentialStore`: `load(): StoredCredential?`, `saveUnconfirmed(deviceId, clientId, kClient)`, `markActive()`, `delete()`. **Moja decyzja:** `K_setup` nie jest utrwalany wcale — żyje tylko w pamięci na czas próby PAIR (spec 9.2 wymaga usunięcia po rejestracji; trwały zapis nie jest wymagany). Po śmierci procesu w trakcie rejestracji recovery prosi o ponowne zeskanowanie QR (spec 11.3 recovery 2). `StoredCredential` ma `state: CredentialState` (`NONE`/`UNCONFIRMED`/`ACTIVE`, spec 3), `deviceId`, `clientId`. Odczyt może zgłosić `Unavailable` (przed pierwszym odblokowaniem, spec 9.3).
- `SettingsStore`: `autoCloseDefault: Flow<Boolean>` (domyślnie `false`, spec 4.1), `gateName`.
- `HistoryStore`: `append(entry)`, `entries(): Flow<List<HistoryEntry>>`; przycinanie do 200 wykonuje `:domain` (lub kontrakt portu — decyzja budowniczego).
- `RuleCacheStore` (**moja decyzja**, po przeglądzie): `save(LastKnownRule)` / `load()`. `LastKnownRule` = ostatni świeży `RuleSnapshot` + czas jego otrzymania. Potrzebny, aby po śmierci procesu: (a) powód 3 utrzymania łącza (własna uzbrojona reguła) przetrwał, (b) usługa pokazała ostatni potwierdzony stan (spec 4.2), (c) powiadomienie DISARMED/REBOOT (A61) zadziałało dla byłego właściciela. Zapis przy każdym nowym świeżym snapshocie zmieniającym fazę/relację/przyczynę.

## 5. Model stanu dla UI

```kotlin
data class GateUiState(
    val gateName: String?,
    val credential: CredentialState,
    val connection: ConnectionState,          // spec 3
    val position: GatePosition = GatePosition.UNKNOWN,
    val rule: RuleView,                       // spec 3: snapshot + fresh
    val lastCommand: LastCommand?,            // spec 3
    val nextOpenAutoClose: Boolean,           // preferencja, nie reguła w sterowniku
    val controlsEnabled: Boolean,             // READY i brak operacji w toku
    val confirmations: Map<UiSource, Confirmation>, // osobno dla telefonu i samochodu
    val registration: RegistrationState?,
    val messages: List<UiMessage>,            // uporządkowane wg ważności (5.2)
    val notification: UiMessage?,             // np. DISARMED/REBOOT dla byłego właściciela
)

data class UiMessage(val id: MessageId, val args: MessageArgs = MessageArgs.None)
```

- `MessageId` — enum z dokładnie jedną pozycją na każdy tekst listy spec 3 (położenie, połączenie, błędy systemowe, wyniki komend, reguła, ostrzeżenia, rejestracja, odmowy handshake). Dodanie pozycji = zmiana specyfikacji.
- `MessageArgs`: `Seconds(n)` dla WAITING/BOOT_WAIT (z `remaining_ms`, zaokrąglenie do pełnych sekund — szczegół dla budowniczego), `FaultCause(RuleReason)` dla FAULT.
- Wariant samochodowy: ten sam `MessageId`; skracanie robi `:car` w zasobach (spec 3, 5.2).
- **Potwierdzenia per źródło (moja decyzja):** potwierdzenie rozpoczęte na telefonie nie pojawia się w samochodzie i odwrotnie; `confirmClose(CAR)` nie potwierdza prośby z telefonu. Samochód ma tylko potwierdzenie CLOSE (spec 5.2). „Usuń z telefonu” i włączenie `autoCloseOnDisconnect` istnieją wyłącznie dla telefonu.
- `messages` ma kolejność ważności 5.2: (1) połączenie + „Położenie nieznane”, (2) reguła z przyczyną FAULT/BLOCKED lub nieaktualność, (3) wynik ostatniej komendy, (4) ustawienie następnego otwarcia, (5) nazwa bramy jest w `gateName`, nie w `messages`. `:car` przycina od końca; pozycje (1) i (2) zawsze obecne.
- **Użycie po stronie `:app` (17.11.2026):** ekran główny telefonu i powiadomienie usługi nie wyświetlają już `messages` (decyzja właściciela: ekran pokazuje tylko stan połączenia z `Messages.connection`, powiadomienie jedną linię połączenia i przyciski); wyniki komend są w Historii. `messages` i `carNotification` zasilają dalej `:car`. Domena nie zmieniła się: `UiMessages.phone` nadal buduje listę (m.in. wiersz aktualizacji firmware dla ekranu aktualizacji).

### 5.1. Mapowania (testowane tabelarycznie)

- `ConnectionState` → komunikat połączenia; „Połączono ze sterownikiem” wyłącznie przy READY (spec 3).
- `RuleView` → komunikat reguły wg tabeli spec 3, w tym `owner_relation`, `fresh = false` („Stan reguły nieaktualny — brak połączenia” / „Odświeżanie stanu reguły…”), DISARMED/REBOOT.
- `AckResult` → komunikat wyniku; `STORAGE_FAILURE`, `INVALID_ARGUMENT`, `UNSUPPORTED_TYPE` → „Błąd zgodności aplikacji i sterownika” (spec 3, 13.2); `OUTPUT_TRIGGERED` rozróżnia OPEN/CLOSE.
- `HandshakeRejectReason` → komunikat rejestracji/handshake (spec 3, 12.4).
- Stan reguły po komendzie rozstrzyga wyłącznie snapshot, nie kod wyniku (spec 13.3).

## 6. Zachowania

### 6.1. Sterowanie (spec 4.1, 13)

- `controlsEnabled` tylko w READY i bez **komendy użytkownika** w toku. W trakcie OPEN/CLOSE kolejne intencje sterowania są odrzucane (nie kolejkowane).
- **PING (moja decyzja):** diagnostyczny PING nie wyłącza przycisków. Jeśli użytkownik wyśle OPEN/CLOSE w trakcie PING, aktor czeka na rozstrzygnięcie PING (najwyżej jedno oczekujące żądanie, spec 13.3) i dopiero wtedy wysyła komendę. PING zakończony `CommandUnknown` oznacza tylko `fresh = false`; nie tworzy `LastCommand` ani wpisu historii.
- OPEN: jedno dotknięcie. Jeśli preferencja `autoCloseOnDisconnect = true`, `:domain` przed OPEN upewnia się, że `:app` uruchomiło usługę połączenia (port `ConnectionKeeper` albo zdarzenie — szczegół dla budowniczego); gdy start niemożliwy: komunikat „Nie można uruchomić połączenia w tle…”, polecenie nie zostaje wysłane, boolean nie jest zmieniany (spec 4.2, 7).
- CLOSE (spec 1.4): `requestClose(source)` wysyła od razu, bez potwierdzenia (usunięte `Confirmation.CLOSE`, `confirmClose`, `CONFIRM_CLOSE`). CLOSE dostępny także przy FAULT lub `fresh = false`, o ile sesja READY (spec 4.2).
- `CommandResult` → `LastCommand(outcome = OUTPUT_TRIGGERED | REJECTED, ackResult)`; `CommandUnknown` → `OUTCOME_UNKNOWN`. Bez automatycznego ponowienia; użytkownik może świadomie wysłać nowe polecenie.
- Każda próba trafia do historii: czas, komenda, boolean dla OPEN, wynik.

### 6.2. Polityka połączenia (spec 4.2, 12.4)

Powody utrzymania łącza:
1. UI telefonu podpięte (`uiAttached(PHONE)`);
2. UI samochodu podpięte (`uiAttached(CAR)`);
3. ostatni znany snapshot (nawet nieaktualny) ma `owner_relation = SELF` w fazie MONITORING, WAITING lub BOOT_WAIT.

- Jest powód i poświadczenie ACTIVE/UNCONFIRMED → `GateLink.connect`.
- Brak powodów i reguła nie jest własna uzbrojona → `GateLink.disconnect()` bez ostrzeżenia (nie powstaje WAITING).
- `requestDisconnect(source)` przy MONITORING/SELF → `Confirmation.DISCONNECT_ARMS_AUTOCLOSE`; bez uzbrojonej własnej reguły rozłączenie następuje bez ostrzeżenia.
- **Po potwierdzonym „Rozłącz” (decyzja użytkownika):** wstrzymane są **wszystkie** powody (1–3, telefon i samochód). Brak automatycznego łączenia, także przy otwartym ekranie i po ponownym podpięciu UI, aż do świadomego `connect()` („Połącz”). OPEN/CLOSE są w tym stanie niedostępne (brak READY), więc jedyną drogą powrotu jest „Połącz”. Gwarantuje to, że potwierdzona decyzja o autozamknięciu nie zostanie cofnięta automatycznym reconnectem. Wstrzymanie nie jest utrwalane: po śmierci procesu obowiązuje §6.2 „Start procesu”.
- **Start procesu (moja decyzja):** przy istniejącym poświadczeniu ACTIVE/UNCONFIRMED `:domain` wczytuje `RuleCacheStore`; jeśli ostatni znany stan to własna uzbrojona reguła, powód 3 jest aktywny od razu i `:domain` łączy się (właściciel musi zdążyć przed upływem WAITING, spec 14.2). W innym przypadku łączy się dopiero przy powodzie 1–2.
- **Wygaśnięcie powodu 3 (spec 4.2, A73; decyzja właściciela 27.10.2026):** po utracie łącza, także przy starcie procesu z regułą z `RuleCacheStore`, powód 3 trwa `max(30 s, remainingMs) + 30 s`. Później sterownik na pewno już wykonał regułę: WAITING 30 s, potem CLOSE (14.2). Telefon poza zasięgiem nie utrzymuje więc usługi i nie skanuje bez końca. Kolejne nieudane próby nie przedłużają odliczania. Powód 3 przywraca świeży snapshot z sesji READY, do której doprowadza ekran, samochód albo obecność CDM. Pole `GateUiState.backgroundLinkNeeded` steruje usługą w `:app`. Obejmuje powody poza ekranem telefonu: samochód, powiadomienie „w zasięgu” i powód 3. Nie obejmuje potwierdzania rejestracji, które nie ma limitu prób, więc porzucone w tle trzymałoby usługę bez końca. Wyłączony Bluetooth go nie zeruje (powrót przed auto-CLOSE), brak uprawnień Bluetooth i zablokowane poświadczenie — tak.
- Odmowa UNAVAILABLE/RATE_LIMITED → ponowienie nie wcześniej niż po 30 s, podwajane do 5 min; licznik zerowany po READY lub świadomej akcji użytkownika (`refresh`, `open`, ponowne podpięcie UI). Ponawianie tylko gdy istnieje powód 1–3.
- Odmowy AUTH_FAILED, NO_CAPACITY, CLIENT_ALREADY_ACTIVE, REPLACEMENT_BLOCKED, WINDOW_CLOSED, STORAGE_FAILURE → brak automatycznego ponowienia.
- Utrata sesji bez odmowy (zerwanie radia, `SessionLost`) → ponowne łączenie, gdy jest powód (bez backoffu 12.4; tempo decyduje `:ble` — szczegół dla budowniczego, czy dodać minimalny odstęp).
- `uiAttached` → snapshot oznaczony jako odświeżany (`fresh = false`, komunikat „Odświeżanie stanu reguły…”) i PING po READY (spec 4.2).
- Zakończenie projekcji Android Auto to tylko `uiDetached(CAR)` — nie jest utratą łącza (spec 5.2).

### 6.2a. Powiadomienie „brama w zasięgu” (spec 4.3, `doc/pin_presence_design.md` §5.3)

- Wejścia od `:app`: `presenceAppeared()`, `presenceDisappeared()` (CompanionDeviceManager), `notificationOpen()`, `notificationClose()` (przyciski). Stan: `GateUiState.presence` (HIDDEN/SHOWN/QUIET) i `notificationActionsAvailable` (SHOWN, READY, brak komendy w toku).
- HIDDEN → `presenceAppeared` → SHOWN: powód połączenia `presence` w `ConnectionPolicy`, zdjęte „Rozłącz” (`liftUserSuspension`; blokada po odmowie terminalnej zostaje).
- Akcja powiadomienia (tylko w SHOWN i gdy komenda dozwolona): OPEN z domyślnym boolean albo CLOSE; licznik od nowa.
- `notificationDismissed()` (odrzucenie powiadomienia: tap, przesunięcie, ekran na pierwszym planie) w SHOWN → QUIET; karencja nieobecności po wyciszeniu 30 s (19.11.2026): zdjęty powód `presence`, własna uzbrojona reguła wstrzymana jako powód połączenia (`setOwnerArmedSuppressed`), rozłączenie, jeśli nic innego nie trzyma łącza (UI, komenda w toku). Decyzja 1.4: sterownik zamknie bramę po ok. 30 s.
- QUIET: `presenceAppeared` ignorowane; przyjęte OPEN(true) z aplikacji/Android Auto zdejmuje wstrzymanie reguły (decyzja właściciela); `presenceDisappeared` → HIDDEN i zdjęte wstrzymanie.
- Port `PresenceStore` (`PresenceRecord(quiet, ruleSuppressed)`) — oba stany przetrwają odtworzenie procesu (także reinstalację z zachowanymi danymi); SHOWN nie jest utrwalany.
- **Karencja zniknięcia po wyciszeniu (zmierzona 17.11.2026 na A16):** w QUIET `presenceDisappeared` zdejmuje wyciszenie dopiero po `PRESENCE_ABSENCE_GRACE_MS` = 180 s bez ponownego pojawienia się; krótsza nieobecność (np. Lost/Found z CDM co kilkanaście sekund przy zgaszonym ekranie) niczego nie zmienia, a `presenceAppeared` w tym czasie anuluje odliczanie. Wyciszenie po `PRESENCE_QUIET_MS` = 300 s. Wynik pomiaru: 16 min w zasięgu bez powtórek, po nieobecności ponad 3 min (6 min 10 s) jedno nowe powiadomienie.

### 6.3. Świeżość reguły (spec 3, 12.2)

- `fresh = true` tylko po snapshocie z bieżącej sesji READY (READY, ACK, PONG, RULE_STATUS).
- Utrata sesji → `fresh = false`, snapshot zachowany jako „ostatni znany”.
- **Wyświetlanie przy `fresh = false` (moja decyzja):** pokazywany jest wyłącznie komunikat nieaktualności („Stan reguły nieaktualny — brak połączenia” albo „Odświeżanie stanu reguły…”), bez ostatniej fazy — lista zamknięta nie ma tekstów „nieaktualny FAULT/BLOCKED/WAITING”, a pozycja (2) z 5.2 dopuszcza „informację o nieaktualności”. Ostatni znany stan nadal steruje polityką połączenia i ostrzeżeniami (§6.2, §6.5).
- Po nowym READY baza revision się resetuje (spec 12.2) — porównania revision w obrębie sesji robi `:protocol`.
- Powiadomienie DISARMED/REBOOT: jeśli przed utratą sesji ostatni świeży snapshot miał własną uzbrojoną regułę, a pierwszy świeży snapshot nowej sesji to DISARMED/REBOOT → `notification` (spec 3).

### 6.4. Rejestracja (spec 11.3, 9.5)

Stany `RegistrationState`: `WaitingForWindow` (instrukcja: przytrzymaj przycisk 3 s), `Pairing`, `SavingCredential`, `Committing`, `ConfirmingSession`, `Done`, `Failed(messageId)`.

0. **Warunek (decyzja użytkownika):** rejestracja dostępna tylko bez poświadczenia ACTIVE. Przy ACTIVE (np. po resecie autoryzacji sterownik odrzuca klucz) użytkownik najpierw wykonuje „Usuń z telefonu” (§6.5), potem dodaje bramę od nowa. Przy UNCONFIRMED rejestracja jest dozwolona (recovery 11.3). Przed PAIR `:domain` rozłącza istniejącą sesję (jeśli jest); PAIR na zachowanym łączu (wyjątek 9.5) nie jest używany.
1. `startRegistration(qrText)` → `ProvisioningQr.parse` (QR z obowiązkowym `p`, spec 1.4); albo `startPinRegistration(pin)` (spec 11.4) — niepoprawny format ignorowany (pilnuje go UI); błąd → komunikat (MALFORMED/wersja/id/klucz — szczegół mapowania dla budowniczego, w ramach instrukcji, nie listy zamkniętej). Brak zapisu sekretu przy błędzie.
2. Nowy `client_id` (16 losowych bajtów) — chyba że istnieje UNCONFIRMED z tym samym `device_id` (QR) albo dowolne UNCONFIRMED (PIN: `device_id` nieznany, jedna brama w aplikacji): wtedy zachowany `client_id` (spec 11.3 recovery, krok 2).
3. `GateLink.pair(PairingSecret.Qr | PairingSecret.Pin, clientId)`; sekret zerowany przy każdym końcu próby.
4. `GrantReceived(kClient, deviceId)` → `CredentialStore.saveUnconfirmed(deviceId z GRANT, ...)` (atomowo zastępuje wcześniejsze UNCONFIRMED); sukces → `commitPairing()`; błąd zapisu → `abortPairing()`, `Failed`.
5. `PairingDone` → `K_setup` usunięty z pamięci, `GateLink.connect` sesją zwykłą.
6. Pierwsze `Ready` po rejestracji → `markActive()`, komunikat „Telefon zarejestrowany. Połączono ze sterownikiem”.
7. Utrata COMMIT/DONE (brak `PairingDone`, zerwanie): poświadczenie zostaje UNCONFIRMED; `:domain` próbuje zwykłej sesji z K1 (spec 11.3 recovery 1). READY → ACTIVE. Odmowa AUTH_FAILED → komunikat „Rejestracja niezakończona — połącz się ponownie ze sterownikiem” i oczekiwanie na nowe okno i QR (recovery 2–3).
8. `HandshakeRejected` w trakcie PAIR → komunikat z listy (przy PIN-ie AUTH_FAILED i `HandshakeFailed` → `PIN_REGISTRATION_FAILED`; tekst od 17.11.2026 dodaje wskazówkę, że po restarcie bramy PIN działa dopiero po wciśnięciu przycisku); poświadczenia, K1/K2 i bond nie są zmieniane (spec 12.4).
9. Sterowanie nie jest dostępne w UNCONFIRMED (spec 9.3, 11.3 krok 6).
10. **ACTIVE odrzucane przez sterownik (AUTH_FAILED na sesji zwykłej):** poświadczenie nie jest usuwane (spec 12.4); komunikat „Nie udało się uwierzytelnić sterownika”, brak automatycznego ponawiania; ponowna próba tylko po świadomej akcji użytkownika (`connect()`, `refresh()`), nie po samym ponownym podpięciu UI — ogranicza ryzyko RATE_LIMITED (5 prób/60 s). Ścieżka naprawy: „Usuń z telefonu” i ponowna rejestracja; utracony bond przy ważnym `K_client` — „Napraw połączenie” (`doc/bond_repair_design.md`).

### 6.5. Usunięcie z telefonu i reset (spec 4.1, 9.4)

Zdalne usuwanie innego telefonu: §11.

- `requestRemoveFromPhone()` → potwierdzenie z ostrzeżeniem „Usunięcie z telefonu nie odwołuje dostępu…” zawsze, plus „Usunięcie zerwie połączenie i sterownik może podać sygnał zamknięcia za ok. 30 s”, gdy ostatni świeży snapshot to MONITORING/WAITING z SELF albo stan reguły jest nieaktualny; nie przy FAULT/BLOCKED.
- `confirmRemoveFromPhone()` → `disconnect`, `CredentialStore.delete()`, historia zostaje (decyzja budowniczego, czy czyścić — patrz §8).
- **Bond systemowy i powrót na ekran główny (17.11.2026):** domena nie zna bondów Androida. Po przejściu poświadczenia ACTIVE → NONE `:app` (`BramaApp`) wywołuje `CompanionAssociation.disassociateAll()`, które usuwa powiązania CDM i bondy (`BleRadio.removeBond`) znanych adresów, a `BramaNav` wraca na ekran główny z „Dodaj bramę” (tylko z ACTIVE: porażka rejestracji zostaje w kreatorze z komunikatem). Rejestracja przerwana przed `PAIR_COMMIT` usuwa bond w `:ble` (`ble_design.md` §4.6).
- Instrukcja resetu autoryzacji: stała pozycja `MessageId` z ostrzeżeniem „Reset nie podaje sygnału…”.

## 7. Testowanie

- Testy JVM z `kotlinx-coroutines-test` (wirtualny czas) i fałszywymi implementacjami portów (`FakeGateLink`, magazyny w pamięci).
- Tabelaryczne testy mapowań: każdy `AckResult`, każdy `HandshakeRejectReason`, każda faza/relacja/przyczyna reguły, `fresh = false`, błędy systemowe → dokładnie jeden `MessageId` (pokrywa A60 po stronie aplikacji).
- Scenariusze: A07 (brak ponowienia po utracie sesji), A13/A32 (recovery rejestracji), A17 (odłączenie Android Auto), A27 (błędy systemowe bez ukrytej zmiany boolean), A47 (backoff ≥ 30 s), A61 (powiadomienie DISARMED/REBOOT).
- Test-anty-cel: żaden `MessageId` nie jest nazwany ani mapowany na położenie otwarte/zamknięte.

## 8. Decyzje podjęte przy przeglądzie

Użytkownik:
- „Rozłącz” wstrzymuje wszystkie powody połączenia do świadomego „Połącz” (§6.2).
- Rejestracja tylko bez ACTIVE; inaczej najpierw „Usuń z telefonu” (§6.4).
- Naprawa bond — `doc/bond_repair_design.md` (§2).

Moje (do sprawdzenia):
- `RuleCacheStore` z ostatnim świeżym snapshotem; po starcie procesu łączenie, gdy ostatni stan to własna uzbrojona reguła (§4.3, §6.2).
- `K_setup` nieutrwalany; po śmierci procesu w trakcie rejestracji — ponowny skan QR (§4.3).
- Potwierdzenia osobno dla telefonu i samochodu (§5).
- PING nie blokuje przycisków; komenda czeka na rozstrzygnięcie PING; nieudany PING nie trafia do historii (§6.1).
- Przy `fresh = false` tylko komunikat nieaktualności, bez ostatniej fazy (§6.3).
- AUTH_FAILED na ACTIVE: bez auto-ponawiania, także po ponownym podpięciu UI (§6.4 pkt 10).

## 9. Pozostawione budowniczemu

- Dokładne sygnatury i podział plików; nazwy pozycji `MessageId`.
- Czy przycinanie historii do 200 jest w `:domain`, czy w kontrakcie `HistoryStore`.
- Mechanizm potwierdzenia startu usługi połączenia przed OPEN(true) (port vs zdarzenie).
- Zaokrąglanie `remaining_ms` do sekund.
- Czy „Usuń z telefonu” czyści historię.
- Minimalny odstęp ponownego łączenia po `SessionLost` (poza backoffem 12.4).
- Mapowanie błędów parsera QR na instrukcje UI (nie są na liście zamkniętej).

## 10. Aktualizacja firmware (spec 1.5)

Opis w `doc/ota_design.md` §5: stan `FirmwareUpdate`, transfer w kolejce aktora obok komendy i PING, powód łącza `updating` w `ConnectionPolicy`, wstrzymanie licznika 5 min powiadomienia, Rebooting z limitem 90 s.

## 11. Telefony z dostępem (18.11.2026, spec 9.4, 13.1, `doc/client_management_design.md` §5.3)

- `GateRepository`: `refreshClients()`, `removeClient(clientId)`, `renameSelf(name)`; dozwolone tylko w READY i bez trwającej komendy ani OTA (jedno żądanie w toku, 13.3).
- Stan `GateUiState.clients: ClientsUi` (`Loading`, `Ready(list)`, `Error(messageId)`); wpis: `clientId`, `name`, `isSelf`, `isConnected`, `ownsRule`.
- Po `CLIENT_REMOVED` i `NAME_SET` odświeżenie listy; `CLIENT_NOT_FOUND` też odświeża (telefon usunięty w międzyczasie).
- Nazwa własna: po pierwszej sesji READY po rejestracji, gdy lista ma pustą nazwę własną, wysyłane jest `SET_CLIENT_NAME` z magazynu ustawień; błąd nazwy nie przerywa sesji.
- Nowe `MessageId` i teksty (spec 3): usunięto, już usunięty, nie można usunąć siebie, zarządza otwartą bramą, błąd zapisu, nazwa zapisana.
- Testy: odmowy z każdym wynikiem, odświeżenie po usunięciu, brak wysyłki poza READY i w trakcie OTA, nazwa domyślna przycięta do 20 B po granicy znaku.
