# Projekt: PIN (EC-JPAKE), powiadomienie „brama w zasięgu” i CLOSE bez potwierdzenia w `:protocol`, `:ble`, `:domain`

> **Zmiana 19.11.2026:** licznik 5 min z tego dokumentu został zastąpiony odrzuceniem powiadomienia (`notificationDismissed()`: tap, przesunięcie, UI na pierwszym planie) i karencją nieobecności 30 s zamiast 180 s; obowiązuje spec 4.3 pkt 4–5, a poniższe odniesienia do licznika 5 min są historyczne.

Data: 27.10.2026. Specyfikacja: `doc/PLAN_I_SPECYFIKACJA_BRAMY.md` wersja 1.4 (rozdziały 4.1, 4.3, 5.2, 8.2, 9.2, 11.4, testy A54, A67–A72). Wcześniejsze projekty: `doc/domain_design.md`, `doc/ble_design.md`.

## 1. Cel

Przygotować gotowe moduły na zmiany specyfikacji 1.4, zanim powstanie `:app`:

- rejestracja PIN-em przez EC-JPAKE, zgodna bajt w bajt z `mbedtls_ecjpake` używanym przez firmware ESP32 (ESP-IDF);
- logika powiadomienia „brama w zasięgu” w `:domain` (pojawienie się, wyciszenie po 5 min, powrót po cyklu obecności) — testowalna na JVM, bez Androida;
- CLOSE bez potwierdzenia.

**Nie-cele:** `:app` (Compose, usługa, CompanionDeviceManager, Keystore — osobny projekt), firmware, `:car`, plik `protocol-v2-vectors.json`.

**Anty-cele:**

- PIN użyty bezpośrednio jako klucz HMAC albo zapisany na telefonie;
- implementacja EC-JPAKE „podobna” do mbedTLS, ale niezgodna co do bajtu — zgodność musi być udowodniona wektorami z prawdziwego mbedTLS;
- logika wyciszenia rozproszona w `:app` (nietestowalna) zamiast w `:domain`.

## 2. Decyzje właściciela (ze specyfikacji 1.4)

Bez potwierdzenia CLOSE wszędzie. Powiadomienie: połączenie od razu po pojawieniu się sterownika; przyciski działają na zablokowanym ekranie; po 5 min wyciszenie i rozłączenie także przy uzbrojonej regule własnej; powiadomienie wraca dopiero po zniknięciu i ponownym pojawieniu się. PIN przez PAKE; QR zawiera `K_setup` i PIN.

## 3. `:protocol`

### 3.1. Zależność

`org.bouncycastle:bcprov-jdk18on:1.79` (jest w cache Gradle — build offline) wyłącznie dla arytmetyki krzywej P-256 (`org.bouncycastle.math.ec`, `CustomNamedCurves.getByName("secp256r1")`). Nie używać `ECJPAKEParticipant` z BouncyCastle: ma inny format ZKP niż mbedTLS. Nie rejestrować providera JCA (na Androidzie lekkie API `org.bouncycastle` nie koliduje z systemowym `com.android.org.bouncycastle`). Pozostała kryptografia (SHA-256, HMAC, HKDF, AES-GCM) bez zmian — JCA.

### 3.2. `EcJpake` — EC-JPAKE w formacie mbedTLS

Wzorzec: `esp-idf/components/mbedtls/mbedtls/library/ecjpake.c` (ESP-IDF 5.4, lokalnie `/home/andy/work/esp/v5.4/`). Elementy, które muszą się zgadzać:

- identyfikatory ról ASCII `"client"` (telefon) i `"server"` (ESP32);
- hasło: bajty ASCII PIN-u jako liczba big-endian (`mbedtls_mpi_read_binary`), redukcja jak w mbedTLS;
- hash ZKP: SHA-256 z `len32(G)||G || len32(V)||V || len32(X)||X || len32(id)||id` (punkty nieskompresowane 65 B, długości 4 B big-endian), wynik mod n;
- kodowanie na łączu: punkt jako TLS `ECPoint` (1 B długości + 65 B), ZKP = `ECPoint V || 1 B długości || r` (r w minimalnej długości big-endian — długości payloadów są więc zmienne), runda 1 = `X1, ZKP(X1), X2, ZKP(X2)`, runda 2 serwera poprzedzona `ECParameters` (3 B: `03 00 17`), runda 2 klienta bez nich;
- generator rundy 2: suma odpowiednich punktów jak w mbedTLS; weryfikacja ZKP odbieranych punktów i odrzucenie punktu w nieskończoności;
- wspólny sekret `S` jak `mbedtls_ecjpake_derive_secret`: SHA-256 współrzędnej x punktu K (32 B).

API (strona klienta; strona serwera tylko w testach, do pełnej pętli i jako „atakujący”):

```kotlin
class EcJpakeClient(pin: ByteArray, random: SecureRandom = SecureRandom()) {
    fun round1(): ByteArray                              // payload 0x26
    fun readServerRound1(payload: ByteArray)             // payload 0x27; ProtocolException przy złym ZKP/formacie
    fun round2(): ByteArray                              // payload 0x28
    fun readServerRound2(payload: ByteArray): ByteArray  // payload 0x29 → S (32 B)
    fun destroy()                                        // zeruje PIN i skalary
}
```

`K_pin` (11.4): `HKDF-Expand(HKDF-Extract(SHA-256(PT), S), "BRAMA/2/PAKE/setup", 32)`, `PT` = payloady 26..29 w kolejności — funkcja w `HandshakeCrypto`.

**Zakres zgodności:** interoperacyjność — obie strony akceptują swoje rundy (format, ZKP) i wyliczają ten sam `S`. Nie wymaga się identycznych bajtów rund przy tym samym strumieniu RNG (mbedTLS zużywa losowość inaczej). `EcJpakeClient` ma wewnętrzny konstruktor z wstrzykiwanymi skalarami (x1, x2 i nonce ZKP) na potrzeby wektorów.

**Dowód zgodności (wymóg):**

1. Wektory z self-testu `ecjpake.c` (`ecjpake_test_x1..x4`, `cli_one`, `srv_one`, `srv_two`, `cli_two`, `pms`): Kotlin z ustalonymi x1..x4 parsuje rundy mbedTLS, weryfikuje ich ZKP i wylicza dokładnie `ecjpake_test_pms`.
2. Wektory pełnych przebiegów wygenerowane przez mały program C kompilowany `gcc` z biblioteką mbedTLS z ESP-IDF (deterministyczny RNG, PIN 6 cyfr): Kotlin-klient ↔ zapisane rundy mbedTLS-serwera daje ten sam `S`, a rundy Kotlina akceptuje mbedTLS (program C sprawdza je w drugą stronę). Program C i wygenerowany JSON trafiają do `protocol/src/test/resources/ecjpake/` z informacją o licencji Apache-2.0 mbedTLS.
3. Negatywne: zmieniony bit ZKP, punkt spoza krzywej, zły PIN → różne `S` (potwierdzenie klucza w PAIR_BEGIN zawiedzie).

### 3.3. Ramki i handshake

- `MessageType`: `PAKE_ROUND1 (0x26, c2s)`, `PAKE_ROUND1_REPLY (0x27, s2c)`, `PAKE_ROUND2 (0x28, c2s)`, `PAKE_ROUND2_REPLY (0x29, s2c)`; format: `seq = 0`, brak tagu (jak `HANDSHAKE_REJECT`), payload ≤ 490 B.
- `PinPakeHandshake` (nowy, obok `ClientHandshake`): maszyna stanów rund PAKE z `pendingRequest` (retransmisja identycznych bajtów, 10.4), sprawdzeniem `session_id` (0 w ROUND1, z ROUND1_REPLY dalej), duplikatów i `HANDSHAKE_REJECT` z tym samym `session_id`. Wynik: `K_pin` (kopia dla wołającego, własne kopie zerowane).
- `PairingHandshake` bez zmian w logice: dostaje 32-bajtowy klucz (`K_setup` albo `K_pin`) i `device_id`. Przy PIN-ie `device_id` pochodzi z INFO; poprawny PAIR_CHALLENGE (tag + `device_id` w payloadzie) go potwierdza.
- `ProvisioningQr`: pole `p` obowiązkowe (dokładnie 6 cyfr ASCII), `pin: ByteArray` w modelu, ukryty w `toString`, nowe błędy parsera (A54). Model wejścia rejestracji:

```kotlin
sealed interface PairingSecret {
    class Qr(val qr: ProvisioningQr) : PairingSecret      // K_setup
    class Pin(val pin: ByteArray) : PairingSecret         // 6 cyfr ASCII, walidowane przy tworzeniu
}
```

## 4. `:ble`

- `GateLink.pair(secret: PairingSecret, clientId)` zamiast `pair(qr, …)`.
- Ścieżka QR bez zmian.
- Ścieżka PIN:
  - kandydat = urządzenie z UUID usługi i INFO z bitem 5 `capabilities` (bez porównania `device_id`; poza oknem rejestracji sterownik i tak odrzuca nieznany telefon filtrem połączeń, 8.3);
  - potem bond (dozwolony w `pair()`), CCCD, MTU, `PinPakeHandshake`, a następnie `PairingHandshake(K_pin, device_id z INFO)`;
  - budżety `T_response`/retransmisje/`T_attempt` jak dla handshake (`T_attempt` od zakończenia wysłania PAKE_ROUND1).
- Odmowa lub porażka PAKE/PAIR kończy próbę jak dotąd (`HandshakeRejected`/`HandshakeFailed`), bez próbowania kolejnych kandydatów.
- `knownAddresses` zapisywane dopiero po poprawnym PAIR_CHALLENGE (wtedy `device_id` jest potwierdzony).
- `LinkEvent.GrantReceived(clientKey, deviceId)` — `:domain` poznaje `device_id` przy PIN-ie dopiero tutaj.
- `SimulatedGate` dostaje stronę serwera EC-JPAKE (z testowej klasy serwera `:protocol`), PIN, okno rejestracji i globalny licznik 5 nieudanych potwierdzeń. Testy JVM: poprawny PIN, zły PIN, PAKE odrzucone, zgubione rundy (retransmisja), INFO bez bitu 5, `device_id` z INFO różny od tego w PAIR_CHALLENGE.

## 5. `:domain`

### 5.1. Rejestracja

- `startRegistration(qrText)` bez zmian; nowe `startPinRegistration(pin: String)`. Format PIN-u pilnuje UI (pole tylko cyfry, przycisk aktywny przy 6 cyfrach — treść instrukcji, poza zamkniętą listą); `:domain` ignoruje niepoprawny PIN bez połączenia.
- `client_id` przy PIN-ie: istniejące UNCONFIRMED → ten sam `client_id` (jedna brama w aplikacji, recovery 11.3); brak poświadczenia → nowy. Rejestracja przy ACTIVE niedostępna jak dotąd (najpierw „Usuń”). Gdy `device_id` z `GrantReceived` różni się od `device_id` istniejącego UNCONFIRMED, nowy GRANT atomowo je zastępuje (`saveUnconfirmed`).
- Przy PIN-ie `device_id` do zapisu poświadczenia pochodzi z `GrantReceived`.
- Recovery 11.3 bez zmian (K1 i `device_id` są już znane po GRANT).
- Nowy komunikat AUTH_FAILED dla rejestracji PIN-em: `MessageId.PIN_REGISTRATION_FAILED` („Nie udało się zarejestrować — sprawdź PIN i czy okno rejestracji jest otwarte (po restarcie bramy PIN działa dopiero po wciśnięciu przycisku)”; dopisek od 17.11.2026). Format PIN-u nadal pilnuje UI, a od 17.11.2026 także dostępność prób: kreator czyta liczbę pozostałych prób z reklamy sterownika (`ble_design.md` §4.7) i przy 0 prób wyłącza pole PIN; `:domain` nie zna tego stanu.

### 5.2. CLOSE bez potwierdzenia

`requestClose(source)` wysyła CLOSE od razu (jak `open()`); usunięte `Confirmation.CLOSE`, `confirmClose`, `MessageId.CONFIRM_CLOSE`. Blokada równoległych komend bez zmian.

### 5.3. Powiadomienie „brama w zasięgu”

Zawartość powiadomienia (decyzja właściciela 17.11.2026): tytuł z nazwą bramy, jedna linia ze stanem połączenia i przyciski OTWÓRZ/ZAMKNIJ; bez położenia, reguły, wyniku komendy, problemów ani ostrzeżenia o uzbrojeniu przed wyciszeniem (spec 4.3 pkt 2 i 4).

Wejścia od `:app` (zdarzenia CompanionDeviceManager i akcje powiadomienia):

```kotlin
fun presenceAppeared()
fun presenceDisappeared()
fun notificationOpen()    // OPEN z domyślnym autoCloseOnDisconnect + restart licznika 5 min
fun notificationClose()   // CLOSE + restart licznika 5 min
```

Stan w `GateUiState`:

```kotlin
enum class PresenceNotification { HIDDEN, SHOWN, QUIET }
val presence: PresenceNotification
val notificationActionsAvailable: Boolean   // SHOWN && READY && brak komendy w toku
```

Automat:

| Stan | Zdarzenie | Nowy stan, skutek |
|---|---|---|
| HIDDEN | `presenceAppeared` | SHOWN; powód połączenia `PRESENCE`; start licznika 5 min; zdjęte wstrzymanie użytkownika „Rozłącz” (`resumeByUser`, decyzja właściciela); blokada po odmowie terminalnej zostaje (powiadomienie bez przycisków z tekstem problemu) |
| SHOWN | `notificationOpen`/`notificationClose` | SHOWN; komenda; licznik od nowa |
| SHOWN | upływ 5 min | QUIET; zdjęty powód `PRESENCE`; **wstrzymana** własna uzbrojona reguła jako powód połączenia; `disconnect()`, jeśli nic innego (UI telefonu, Android Auto, komenda w toku) nie trzyma łącza |
| QUIET | nowa przyjęta komenda OPEN(true) z telefonu lub Android Auto | QUIET; wstrzymanie reguły zdjęte — świadome ponowne uzbrojenie trzyma łącze do wyjścia z zasięgu (decyzja właściciela) |
| SHOWN | `presenceDisappeared` | HIDDEN; powód `PRESENCE` zdjęty; wstrzymanie reguły zdjęte |
| QUIET | `presenceDisappeared` | QUIET z odliczaniem karencji `PRESENCE_ABSENCE_GRACE_MS` = 180 s; po upływie bez ponownego pojawienia się: HIDDEN, wstrzymanie reguły zdjęte; `presenceAppeared` w tym czasie anuluje odliczanie (CDM zgłasza Lost/Found co kilkanaście sekund przy zgaszonym ekranie, bez karencji powiadomienie wracało po każdym takim cyklu; zmierzone 17.11.2026 na A16) |
| QUIET | `presenceAppeared` | QUIET (system nie zgłosił zniknięcia — bez zmian) |

Uzasadnienie wstrzymania reguły w QUIET: bez niego `ConnectionPolicy` po rozłączeniu natychmiast połączyłaby się ponownie z powodu własnej uzbrojonej reguły, właściciel wróciłby przed upływem 30 s i brama nigdy by się nie zamknęła — wbrew decyzji właściciela. Podpięcie UI telefonu lub Android Auto łączy normalnie także w QUIET (spec 4.3 pkt 5).

Komenda w toku w chwili upływu 5 min: rozłączenie po jej rozstrzygnięciu (istniejące `shouldKeepLink`).

Licznik 5 min: timer aktora `GateRepository` na wstrzykiwanym zegarze/`delay` (testy na wirtualnym czasie). Stan QUIET (z wstrzymaniem reguły) jest utrwalany przez nowy port `PresenceStore` (implementuje `:app`): system może po odtworzeniu procesu ponownie zgłosić obecność, a powiadomienie nie może wtedy wrócić (spec 4.3 pkt 5). SHOWN nie jest utrwalany — po odtworzeniu procesu wynika z ponownego zdarzenia obecności.

### 5.4. `ConnectionPolicy`

Nowe: powód `presence`, flaga `ownerArmedSuppressed` (QUIET). `shouldConnect = !suspended && !terminalBlocked && (attached.isNotEmpty() || presence || (ownerArmed && !ownerArmedSuppressed) || confirming)`.

## 6. Kolejność prac (plan)

1. `:protocol`: QR z `p`, `PairingSecret`, typy 26–29, `EcJpake` + wektory mbedTLS (program C), `PinPakeHandshake`, `K_pin`.
2. `:ble`: `pair(PairingSecret)`, ścieżka PIN, symulator z serwerem EC-JPAKE.
3. `:domain`: CLOSE bez potwierdzenia, rejestracja PIN-em, `GrantReceived.deviceId`, powiadomienie i `ConnectionPolicy`.

Każdy krok TDD; na końcu przegląd całości, aktualizacja `doc/implementation_status.md`, `doc/domain_design.md` i `doc/ble_design.md`.

## 7. Moje decyzje (do sprawdzenia)

1. EC-JPAKE implementowany samodzielnie na arytmetyce BouncyCastle, a nie przez `ECJPAKEParticipant` (inny format niż mbedTLS).
2. Dowód zgodności przez program C z mbedTLS z lokalnego ESP-IDF 5.4 i wektory z jego self-testu.
3. Ścieżka PIN bierze pierwszego kandydata z bitem 5 i nie próbuje kolejnych po odmowie.
4. Licznik 5 min restartują tylko przyciski powiadomienia, nie ekran aplikacji.
5. W QUIET własna uzbrojona reguła nie jest powodem ponownego połączenia aż do zniknięcia sterownika z zasięgu albo nowego OPEN(true) (to drugie — decyzja właściciela).
6. QUIET utrwalany (`PresenceStore`), SHOWN nie.
7. Zgodność EC-JPAKE = interoperacyjność i ten sam `S`, nie identyczne bajty przy tym samym RNG.
8. `client_id` przy PIN-ie według istniejącego UNCONFIRMED.
9. Format PIN-u pilnuje UI; bez nowego komunikatu z zamkniętej listy.

Decyzje właściciela z przeglądu projektu: nowe OPEN(true) w QUIET zdejmuje wstrzymanie reguły; pojawienie się sterownika znosi „Rozłącz”.

## 8. Wyniki na sprzęcie (16–17.11.2026)

- **EC-JPAKE na płytce:** rejestracje PIN-em z A13 i A16 przechodzą z firmware na mbedTLS z ESP-IDF 6.0.1 (projekt zakładał 5.4): zgodność z mbedTLS potwierdzona na sprzęcie. Runda ok. 2–2,7 s liczona w osobnym zadaniu `pake_worker` (nie blokuje aktora ani blokady BLE); zapas stosu zadania 7,3 KB z 10 KB, minimum sterty podczas rundy ok. 8 KB niższe niż bez PAKE.
- **Limit prób PIN:** licznik globalny 5 na okno, po starcie sterownika wyczerpany do przycisku; okno z telefonu zeruje go najwyżej raz na godzinę pracy (godzina liczona od startu), więc po restarcie PIN jest odrzucany mimo otwartego okna (potwierdzone, wcześniej ogólny błąd). Teraz liczba prób jest w reklamie (spec 8.3) i w `info`, a kreator odlicza 5→4→3 i blokuje PIN przy 0.
- **Karencja powiadomienia (§5.3):** zmierzone na A16 z działającym powiązaniem CDM: 16 min w zasięgu bez powtórnych powiadomień, po nieobecności 6 min 10 s jedno nowe powiadomienie, wyciszenie po 5 min. Pierwsze podejście nieważne: po ponownej rejestracji pominięto „Powiąż”, więc CDM nie zgłaszał obecności; po drodze naprawiona regresja „Powiąż” usuwające bond bieżącej bramy.
- **Zmiana:** powiadomienie bez dodatkowych tekstów (§5.3), tekst błędu PIN z dopiskiem (§5.1).
