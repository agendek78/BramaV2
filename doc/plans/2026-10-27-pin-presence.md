# PIN (EC-JPAKE), powiadomienie „brama w zasięgu”, CLOSE bez potwierdzenia — plan implementacji

> **Dla wykonawców agentowych:** WYMAGANY SUB-SKILL: superpowers:subagent-driven-development (zalecany) albo superpowers:executing-plans, zadanie po zadaniu. Kroki mają składnię checkboxów (`- [ ]`).

**Cel:** Przygotować `:protocol`, `:ble` i `:domain` na specyfikację 1.4: rejestracja PIN-em przez EC-JPAKE zgodne z mbedTLS, logika powiadomienia „brama w zasięgu” w `:domain`, CLOSE bez potwierdzenia.

**Architektura:** `EcJpake` (obie role, format mbedTLS) na arytmetyce P-256 z BouncyCastle; `PinPakeHandshake` prowadzi rundy 0x26–0x29 i wyprowadza `K_pin`, którym dalej działa niezmieniony `PairingHandshake`. `BleGateLink.pair(PairingSecret)` dokłada fazę PAKE przed PAIR. `GateRepository` dostaje automat powiadomienia (HIDDEN/SHOWN/QUIET) z licznikiem 5 min i powodami w `ConnectionPolicy`.

**Stos:** Kotlin 2.2.10, JVM 11 (`:protocol`, `:domain`), Android library (`:ble`), `org.bouncycastle:bcprov-jdk18on:1.79` (w cache Gradle), coroutines 1.10.2, JUnit 4; do wektorów: `gcc` + mbedTLS 3.6.2 z `/home/andy/work/esp/v5.4/esp-idf/components/mbedtls/mbedtls`.

**Specyfikacja:** `doc/pin_presence_design.md` (zatwierdzony), `doc/PLAN_I_SPECYFIKACJA_BRAMY.md` 1.4 (3, 4.1, 4.3, 5.2, 8.2, 9.2, 11.3, 11.4, 12.4, A54, A67–A72), `doc/domain_design.md`, `doc/ble_design.md`. Wykonawca czyta wszystkie.

## Ograniczenia globalne

- Build wyłącznie `--offline`. Repozytorium bez git: zadanie kończy zielony test wskazany w zadaniu; pełny zestaw na końcu: `./gradlew :protocol:test :domain:test :ble:testDebugUnitTest :ble:assembleDebug :app:assembleDebug --offline -q`, bez ostrzeżeń kompilacji.
- BouncyCastle tylko lekkie API (`org.bouncycastle.math.ec.*`, `org.bouncycastle.crypto.ec.CustomNamedCurves`); bez rejestracji providera, bez `ECJPAKEParticipant`.
- EC-JPAKE: P-256, SHA-256, role ASCII `"client"`/`"server"`, hasło = 6 bajtów ASCII PIN-u, format i hash ZKP jak `ecjpake.c` mbedTLS 3.6.2; `S` = SHA-256(x(K)) (32 B).
- `K_pin = HKDF-Expand(HKDF-Extract(SHA-256(p26||p27||p28||p29), S), "BRAMA/2/PAKE/setup", 32)`.
- Typy: `0x26 PAKE_ROUND1 c2s`, `0x27 PAKE_ROUND1_REPLY s2c`, `0x28 PAKE_ROUND2 c2s`, `0x29 PAKE_ROUND2_REPLY s2c`; `seq = 0`, brak tagu, `session_id = 0` w 0x26, niezerowy z 0x27 w kolejnych.
- QR: `brama://provision?v=2&id=<32 hex>&k=<base64url 43 zn.>&p=<6 cyfr>`; `p` obowiązkowe.
- INFO: bit 5 `capabilities` = rejestracja PIN-em.
- Powiadomienie: licznik 300 000 ms; restart tylko przez `notificationOpen()`/`notificationClose()`.
- PIN, `S`, `K_pin`, skalary: tylko w pamięci, zerowane po użyciu; nie logować.
- Tekst `PIN_REGISTRATION_FAILED`: „Nie udało się zarejestrować — sprawdź PIN i czy okno rejestracji jest otwarte”.

## Punkty uwagi przy review

1. Fałszywy sterownik z bitem 5 i podrobionym `device_id` w INFO (nie zna PIN-u) — PAIR_CHALLENGE odrzucony, nic nie zapisane, adres niezapamiętany → Zadanie 5, `fakeGateWithoutPinIsRejected`.
2. Zdarzenia obecności w dziwnej kolejności (zniknięcie bez pojawienia, podwójne pojawienie, akcja powiadomienia w HIDDEN/QUIET) — bez zmian stanu i bez komend → Zadanie 8, `presenceEventsOutOfOrderAreIgnored`.
3. Upływ 5 min z komendą w toku — rozłączenie dopiero po wyniku komendy, wynik nie ginie → Zadanie 8, `quietWaitsForPendingCommand`.
4. Odtworzenie procesu w QUIET (`PresenceStore.quiet = true`) i ponowne `presenceAppeared` — brak powiadomienia i brak połączenia z powodu reguły → Zadanie 8, `quietSurvivesProcessRestart`.
5. Uszkodzona runda PAKE (punkt spoza krzywej, punkt w nieskończoności, `r` dłuższe niż 32 B, obcięty payload) — `ProtocolException`, bez wyjątku innego typu → Zadanie 3, `malformedRoundsAreRejected`.

---

### Zadanie 1: QR z PIN-em i `PairingSecret` (`:protocol`)

**Pliki:** Zmień `protocol/src/main/kotlin/com/agendek/bramav2/protocol/ProvisioningQr.kt`; utwórz `protocol/src/main/kotlin/com/agendek/bramav2/protocol/PairingSecret.kt`; test `protocol/src/test/.../ProvisioningQrTest.kt`, `PairingSecretTest.kt`.

**Interfejsy — produkuje:** `class ProvisioningQr(val deviceId: ByteArray, val setupKey: ByteArray, val pin: ByteArray)` (pin = 6 B ASCII; `toString` ukrywa `setupKey` i `pin`); `QrError.INVALID_PIN`; `sealed interface PairingSecret { class Qr(val qr: ProvisioningQr); class Pin(pin: ByteArray) { val pin: ByteArray } }` — `Pin` kopiuje i waliduje (`require` 6 cyfr ASCII), `fun wipe()` na obu wariantach zeruje sekrety; `PairingSecret.Pin.parse(text: String): PairingSecret.Pin?` (null dla nie-6-cyfr).

- [ ] Testy (najpierw, RED): `pinFieldIsParsed` (`p=012345` → `pin` = ASCII „012345”), `missingPinIsMalformed`, `pinWithFiveOrSevenDigitsIsInvalidPin`, `pinWithNonDigitIsInvalidPin` (`12a456`, `１２３４５６` pełnej szerokości), `repeatedPinFieldIsMalformed`, `toStringHidesPin`; istniejące testy QR uzupełnić o `&p=…`. `pinParseRejectsNonSixDigits`, `pinCopiesInput`, `wipeZeroesPin`.
- [ ] Uruchom `./gradlew :protocol:test --offline -q` — oczekiwane FAIL (brak `pin`).
- [ ] Implementacja; `FIELDS = setOf("v","id","k","p")`, wzorzec `[0-9]{6}`.
- [ ] `./gradlew :protocol:test --offline -q` — PASS. (`:ble`/`:domain` mogą nie kompilować się do zadania 5 — dopuszczalne; nie uruchamiać ich teraz.)

Uwaga: zmiana konstruktora `ProvisioningQr` psuje wywołania w `:ble`/`:domain` (testy z `ProvisioningQr(id, key)`) — naprawiane w zadaniu 5.

### Zadanie 2: Typy PAKE w ramkach (`:protocol`)

**Pliki:** Zmień `MessageType.kt`, `Frame.kt`; test `FrameTest.kt`.

**Interfejsy — produkuje:** `MessageType.PAKE_ROUND1(0x26, C2S, NONE, PAIR)`, `PAKE_ROUND1_REPLY(0x27, S2C, NONE, PAIR)`, `PAKE_ROUND2(0x28, C2S, NONE, PAIR)`, `PAKE_ROUND2_REPLY(0x29, S2C, NONE, PAIR)`; `val MessageType.isPake: Boolean`.

- [ ] Testy (RED): `pakeFramesHaveNoTagAndSeqZero` (encode/decode 0x26 z 330 B payloadu), `pakeWithNonZeroSeqIsRejected`, `pakeRound1WithNonZeroSessionIdIsRejected`, `pakeWithEmptyPayloadIsRejected`, `handshakeRejectStillRequiresOneByte`.
- [ ] Implementacja: w `validate` gałąź `TagKind.NONE` rozdzielona — `HANDSHAKE_REJECT`: jak dotąd; PAKE: `seq == 0`, `bodyLen >= 1`; `PAKE_ROUND1` wymaga `session_id = 0`.
- [ ] `./gradlew :protocol:test --offline -q` — PASS.

### Zadanie 3: `EcJpake` i wektory mbedTLS (`:protocol`)

**Pliki:** `protocol/build.gradle.kts` (+`implementation("org.bouncycastle:bcprov-jdk18on:1.79")`, wpis `bouncycastle` w `gradle/libs.versions.toml`); utwórz `protocol/src/main/kotlin/.../EcJpake.kt`; test `EcJpakeTest.kt`; zasoby `protocol/src/test/resources/ecjpake/` (`mbedtls_selftest.json`, `interop.json`, `kotlin_client_rounds.json`, `harness.c`, `README.md` z opisem generacji i licencją Apache-2.0 mbedTLS).

**Interfejsy — produkuje:**

```kotlin
enum class JpakeRole(val id: String) { CLIENT("client"), SERVER("server") }
fun interface ScalarSource { fun next(): BigInteger }   // skalar w [1, n-1]
class EcJpake(role: JpakeRole, password: ByteArray, scalars: ScalarSource = SecureRandomScalars()) {
    fun writeRound1(): ByteArray
    fun readRound1(peer: ByteArray)                 // ProtocolException przy złym formacie/ZKP
    fun writeRound2(): ByteArray                    // SERVER poprzedza ECParameters 03 00 17
    fun readRound2(peer: ByteArray)                  // CLIENT oczekuje ECParameters
    fun deriveSecret(): ByteArray                   // 32 B
    fun destroy()
}
```

Ruling względem projektu: jedna klasa z rolą zamiast `EcJpakeClient` — strona serwera jest potrzebna `SimulatedGate` w `:ble` i jako „atakujący” w testach.

Kolejność pobierania skalarów (dla determinizmu wektorów): `writeRound1`: x1, v(ZKP X1), x2, v(ZKP X2); `writeRound2`: v(ZKP X2s). mbedTLS dodatkowo zaślepia mnożenie przez s — wynik bez zmian, w Kotlinie pominąć.

- [ ] Wektory 1 (self-test): z `ecjpake.c` (linie ~838–980) przepisz `ecjpake_test_password`, `x1..x4`, `cli_one`, `srv_one`, `srv_two`, `cli_two`, `pms` do `mbedtls_selftest.json`. Uwaga: hasło self-testu to `"threadjpaketest"` (nie 6 cyfr) — `EcJpake` przyjmuje dowolne bajty hasła, a 6 cyfr wymusza `PairingSecret.Pin`.
- [ ] Testy (RED): `clientWithTestScalarsDerivesMbedtlsPms` (CLIENT, x1/x2 z wektora, `readRound1(srv_one)`, `readRound2(srv_two)` → `deriveSecret() == pms`), `serverWithTestScalarsDerivesMbedtlsPms` (analogicznie z `cli_one`/`cli_two`), `clientAndServerAgree`, `wrongPasswordGivesDifferentSecret`, `flippedZkpBitIsRejected`, `malformedRoundsAreRejected` (punkt spoza krzywej, `00` = nieskończoność, `r` 33 B, obcięcie o 1 B, zła długość ECPoint, brak/zły `ECParameters`), `destroyZeroesPassword`.
- [ ] Implementacja według `ecjpake.c`: `ecjpake_hash` (len32 big-endian przed G, V, X i id), `zkp_write`/`zkp_read` (ECPoint TLS 1+65 B, `r` 1 B długości + minimalne big-endian), generator rundy 2 jak w `mbedtls_ecjpake_write_round_two`: G' = Xm1 + Xp1 + Xp2 (własny pierwszy punkt + oba punkty strony przeciwnej), przy odczycie G' = Xp1 + Xm1 + Xm2, `derive_secret`: K = (Xp − X2·x4·s)·x2 itd. dokładnie jak `mbedtls_ecjpake_derive_k`, wynik SHA-256 z x(K) jako 32 B big-endian.
- [ ] Wektory 2 (interop, dwukierunkowe): (a) test `writesDeterministicClientRounds` zapisuje rundy Kotlina-klienta (PIN `"482915"`, stały `ScalarSource` z ziarna) do `build/ecjpake/kotlin_client_rounds.json`; skopiuj do zasobów. (b) `harness.c` (w scratchpadzie zbuduj mbedTLS: `cp -r <esp-idf>/components/mbedtls/mbedtls $SCRATCH/mbedtls && make -C $SCRATCH/mbedtls lib`, potem `gcc harness.c -I.../include -L.../library -lmbedcrypto`): rola SERVER z PIN-em i deterministycznym DRBG, czyta rundy klienta z pliku, **weryfikuje je** (`read_round_one/two` muszą zwrócić 0), zapisuje swoje rundy i `pms` do `interop.json`. (c) Test `kotlinClientInteropsWithMbedtlsServer`: te same skalary → identyczne rundy klienta jak w pliku, `readRound1/2` z rund mbedTLS → `deriveSecret() == pms`.
- [ ] `./gradlew :protocol:test --offline -q` — PASS. Ledger: wpis z poleceniami generacji.

### Zadanie 4: `PinPakeHandshake` i `K_pin` (`:protocol`)

**Pliki:** utwórz `protocol/src/main/kotlin/.../PinPakeHandshake.kt`; zmień `HandshakeCrypto.kt` (`fun pinSetupKey(s: ByteArray, transcript: ByteArray): ByteArray`); test `PinPakeHandshakeTest.kt`; rozszerz `FakeGate.kt` o serwer PAKE.

**Interfejsy — produkuje:**

```kotlin
sealed interface PakeStep {
    class Send(val frame: ByteArray) : PakeStep
    class KeyReady(val pinKey: ByteArray) : PakeStep          // K_pin, 32 B; wołający zeruje
    data class Rejected(val reason: HandshakeRejectReason) : PakeStep
    data class Failed(val reason: String) : PakeStep
    data object Duplicate : PakeStep
    data class Ignored(val reason: String) : PakeStep
}
class PinPakeHandshake(pin: PairingSecret.Pin, scalars: ScalarSource = SecureRandomScalars()) {
    val pendingRequest: ByteArray?     // ramka 0x26, potem 0x28; null po KeyReady/porażce
    fun onFrame(bytes: ByteArray): PakeStep
    fun abort()
}
```

- [ ] Testy (RED): `fullExchangeWithFakeGateYieldsSameKey` (klucz telefonu == klucz FakeGate), `keyFeedsPairingHandshakeToGrant` (`PairingHandshake(K_pin, deviceId, …)` z FakeGate dochodzi do `GrantReceived`), `wrongPinFailsAtPairChallenge` (FakeGate z innym PIN-em → `PairingHandshake` zwraca `Failed`), `reply1AssignsSessionIdUsedLater` (0x28 niesie `session_id` z 0x27), `reply2WithOtherSessionIdIsIgnored`, `duplicateReply1IsDuplicate`, `rejectWithMatchingSessionIdEndsAttempt`, `rejectWithOtherSessionIdIsIgnored`, `pendingRequestIsIdenticalOnRetransmission`, `kPinMatchesSpecFormula` (ręczne HKDF z `Crypto`), `abortWipesState`.
- [ ] Implementacja: `pinSetupKey` = wzór z Ograniczeń; PT = payloady w kolejności 26, 27, 28, 29.
- [ ] `./gradlew :protocol:test --offline -q` — PASS.

### Zadanie 5: Ścieżka PIN w `:ble` i zmiana portu

**Pliki:** `domain/.../Ports.kt` (`pair(secret: PairingSecret, clientId: ByteArray): Long`, `class GrantReceived(val clientKey: ByteArray, val deviceId: ByteArray)`); `domain/.../GateRepository.kt` (minimalnie: `link.pair(PairingSecret.Qr(qr), …)`, użycie `event.deviceId` nie jest jeszcze wymagane); `ble/.../BleGateLink.kt`; `ble/.../GattProfile.kt` (`GateInfo.supportsPin` = bit 5); `ble/src/test/.../sim/SimulatedGate.kt` (PIN, okno rejestracji, serwer `EcJpake`, globalny licznik 5 nieudanych potwierdzeń zamykający okno), `SimulatedRadio.kt` (INFO z bitem 5 domyślnie); testy `BleGateLinkPinPairingTest.kt`; naprawa testów używających `ProvisioningQr(id,key)` i `GrantReceived(key)` w obu modułach.

**Interfejsy — konsumuje:** zadania 1–4. **Produkuje:** `GateLink.pair(PairingSecret, clientId)`, `LinkEvent.GrantReceived(clientKey, deviceId)`.

- [ ] Testy (RED): `pinPairingReachesGrantAndDone` (zdarzenia jak przy QR, `GrantReceived.deviceId == gate.deviceId`), `pinPairingSkipsCandidateWithoutPinCapability` (INFO bez bitu 5 → kolejny kandydat), `wrongPinEndsAttemptWithFailure` (`HandshakeFailed`, brak `GrantReceived`), `lostPakeReplyIsRetransmitted` (`dropIndications = 1` w fazie PAKE → retransmisja identycznych bajtów, próba dochodzi do `GrantReceived`), `pakeRejectEndsAttempt` (`HandshakeRejected(AUTH_FAILED)`), `fakeGateWithoutPinIsRejected` (urządzenie z bitem 5 i `device_id` bramy w INFO, serwer z innym PIN-em → `HandshakeFailed`, `knownAddresses` bez wpisu: następne `connect` skanuje), `rememberedAddressOnlyAfterChallenge`, `fifthWrongPinClosesWindow` (symulator: po 5 nieudanych kolejny PAKE → AUTH_FAILED), `qrPairingStillWorks` (istniejące testy parowania przechodzą), `grantReceivedCarriesDeviceIdForQr`.
- [ ] Implementacja: `Input.Pair(id, secret, clientId)` (kopia sekretu, oryginał zeruje `:domain`); `Conn` w trybie PIN: `deviceId` nieznany do INFO — `onInfo` w trybie PIN wymaga bitu 5 zamiast zgodności `device_id`, ustawia `c.deviceId` z INFO (podpowiedź), **nie** zapisuje `knownAddresses`; po MTU `PinPakeHandshake` (budżety jak handshake: `T_response`, 2 retransmisje, `T_attempt` od zakończenia wysłania 0x26), `KeyReady` → `PairingHandshake(K_pin, c.deviceId, …)` (zerowanie `K_pin` po skopiowaniu), `knownAddresses` zapisywane przy `HandshakeStep.Send` po CHALLENGE (pierwszy CONFIRM) dla trybu PIN. `GrantReceived(key, deviceId)` w obu trybach.
- [ ] `./gradlew :protocol:test :domain:test :ble:testDebugUnitTest --offline -q` — PASS.

### Zadanie 6: CLOSE bez potwierdzenia (`:domain`)

**Pliki:** `GateRepository.kt`, `Model.kt` (`Confirmation` bez `CLOSE`), `MessageId.kt` (bez `CONFIRM_CLOSE`), `Messages.kt`/`UiMessages.kt` jeśli odwołują się; testy `RepositoryCommandTest.kt` i testy rund przeglądu używające `confirmClose`.

**Interfejsy — produkuje:** `fun requestClose(source: UiSource)` wysyła CLOSE od razu, gdy `canCommand()`; usunięte `confirmClose`, `Msg.ConfirmClose`.

- [ ] Testy (RED): `requestCloseSendsCloseImmediately` (jedno `requestClose(PHONE)` → `FakeGateLink.sent == [Close]`, brak wpisu w `confirmations`), `requestCloseDuringPendingCommandIsIgnored`, `requestCloseFromCarSendsImmediately`. Testy używające `confirmClose` przepisać na samo `requestClose`.
- [ ] Implementacja; `MessageIdTest` (lista zamknięta) uaktualniony.
- [ ] `./gradlew :domain:test --offline -q` — PASS.

### Zadanie 7: Rejestracja PIN-em (`:domain`)

**Pliki:** `GateRepository.kt` (`pairingQr` → `pairing: PairingAttempt(secret: PairingSecret, clientId: ByteArray, deviceId: ByteArray?)`), `MessageId.kt` + `UiMessages.kt` (`PIN_REGISTRATION_FAILED`), test `RepositoryPinRegistrationTest.kt`.

**Interfejsy — produkuje:** `fun startPinRegistration(pin: String)`.

- [ ] Testy (RED): `pinRegistrationCallsPairWithPin` (`FakeGateLink.lastPairSecret is PairingSecret.Pin`), `invalidPinIsIgnoredWithoutPair` (`"12345"`, `"12a456"`), `grantSavesDeviceIdFromEvent` (`saveUnconfirmed(deviceId = event.deviceId, …)`), `pinRegistrationReusesUnconfirmedClientId` (istniejące UNCONFIRMED — ten sam `client_id`, niezależnie od `device_id`), `pinRegistrationWithoutCredentialUsesNewClientId`, `pinAuthFailedShowsPinMessage` (`HandshakeRejected(AUTH_FAILED)` i `HandshakeFailed` przy PIN → `RegistrationState.Failed(PIN_REGISTRATION_FAILED)`), `qrAuthFailedKeepsOldMessage`, `pinIsWipedAfterAttempt`, `pinRegistrationNotAvailableWithActive`, `recoveryAfterLostDoneWorksForPin` (Disconnected po GRANT → `ConfirmingSession` z `device_id` z GRANT).
- [ ] Implementacja: miejsca używające `qr.deviceId` korzystają z `pairing.deviceId` ustawianego przy `GrantReceived` (dla QR — z QR od początku).
- [ ] `./gradlew :domain:test --offline -q` — PASS.

### Zadanie 8: Powiadomienie „brama w zasięgu” (`:domain`)

**Pliki:** `Ports.kt` (`interface PresenceStore { suspend fun loadQuiet(): Boolean; suspend fun saveQuiet(quiet: Boolean) }` — parametr konstruktora `GateRepository`), `Model.kt` (`enum class PresenceNotification { HIDDEN, SHOWN, QUIET }`, pola `GateUiState.presence`, `notificationActionsAvailable`), `ConnectionPolicy.kt` (`setPresence(Boolean)`, `setOwnerArmedSuppressed(Boolean)`), `GateRepository.kt`, `Fakes.kt` (`FakePresenceStore`); testy `PresenceNotificationTest.kt`, `ConnectionPolicyTest.kt`.

**Interfejsy — produkuje:** `presenceAppeared()`, `presenceDisappeared()`, `notificationOpen()`, `notificationClose()`; `GateUiState.presence`, `GateUiState.notificationActionsAvailable` (= SHOWN && connection READY && brak komendy w toku).

- [ ] Testy polityki (RED): `presenceIsConnectReason`, `suppressedOwnerArmedIsNotConnectReason`, `attachedUiConnectsWhileSuppressed`.
- [ ] Testy automatu (RED), wirtualny czas: `appearedShowsAndConnects`, `appearedLiftsUserSuspension` (po `confirmDisconnect` → `connect` wywołane), `appearedKeepsTerminalBlock`, `actionsOnlyWhenReadyAndIdle`, `notificationOpenUsesDefaultAutoClose` (OPEN(true) przy domyślnym true), `notificationCloseSendsClose`, `actionRestartsFiveMinutes` (akcja w 240 s → QUIET dopiero w 540 s), `quietAfterFiveMinutesDisconnects` (300 000 ms → QUIET, `disconnect()`), `quietDisconnectsDespiteOwnArmedRule` (snapshot MONITORING/SELF → nadal `disconnect()` i brak ponownego `connect` przez 60 s), `quietWaitsForPendingCommand`, `quietKeepsLinkWhilePhoneUiAttached`, `quietAppearedAgainStaysQuiet`, `disappearedResetsToHidden` (i zdejmuje wstrzymanie: reguła znów łączy), `openTrueInQuietLiftsSuppression` (UI telefonu w QUIET → `open()` z domyślnym true → po `uiDetached` brak `disconnect`), `presenceEventsOutOfOrderAreIgnored`, `quietSurvivesProcessRestart` (`FakePresenceStore.quiet = true` przy starcie → `presenceAppeared` daje QUIET, brak `connect` z powodu reguły), `quietIsPersistedAndClearedOnDisappear`.
- [ ] Implementacja: licznik jako `Job` aktora z `delay(300_000)`; `QUIET` → `policy.setPresence(false)`, `setOwnerArmedSuppressed(true)`, `presenceStore.saveQuiet(true)`, ocena łącza (istniejące `evaluateConnection`). OPEN(true) przyjęte (`CommandResult` OUTPUT_TRIGGERED z OPEN(true)) w QUIET → `setOwnerArmedSuppressed(false)`.
- [ ] `./gradlew :domain:test --offline -q` — PASS.

### Zadanie 9: Dokumentacja i pełny zestaw

**Pliki:** `doc/domain_design.md` (rejestracja PIN, CLOSE bez potwierdzenia, §powiadomienie, `PresenceStore`), `doc/ble_design.md` (ścieżka PIN, `pair(PairingSecret)`, `GrantReceived.deviceId`), `doc/implementation_status.md`.

- [ ] Pełny zestaw z Ograniczeń + `./gradlew :ble:compileDebugKotlin --rerun-tasks --offline` bez `w:` — PASS.
- [ ] Aktualizacja dokumentów (po polsku), liczby testów, rulingi z ledgera.
