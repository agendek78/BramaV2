# Projekt modułu `:ble`

Status: projekt do zatwierdzenia (po przeglądzie wykonawcy). Data: 27.10.2026.
Źródła wymagań: `doc/PLAN_I_SPECYFIKACJA_BRAMY.md` v1.3 (dalej „spec”), rozdz. 7, 8, 10, 11.3, 12, 13; kontrakt portu `GateLink` w `domain/src/main/kotlin/com/agendek/bramav2/domain/Ports.kt` (KDoc jest częścią wymagań); `doc/domain_design.md`.
Moduły zależne: `:protocol` (zakończony), `:domain` (zakończony).

## 1. Cel

`:ble` implementuje port `GateLink`: realizuje mechanikę połączenia BLE z ESP32 i protokół v2 po stronie telefonu, a do `:domain` raportuje wyłącznie zdarzenia `LinkMessage(connectionId, LinkEvent)`. Decyzje *kiedy* się łączyć, ponawiać i co pokazać użytkownikowi należą do `:domain` — `:ble` ich nie podejmuje.

Decyzja użytkownika: na razie brak płytki ESP32; `:ble` jest weryfikowany **symulacją w testach JVM**. Prawdziwe BLE sprawdzane dopiero z płytką.

## 2. Zakres

### Cele

1. Implementacja `GateLink` zgodna z KDoc `Ports.kt`, w tym:
   - nowy, niepowtarzalny w procesie `connectionId` dla każdego `connect()`/`pair()`;
   - po `disconnect()`/`abortPairing()`/`PairingDone` brak jakichkolwiek zdarzeń tego połączenia;
   - `connect()` kopiuje klucz (`:domain` zeruje swoją tablicę po powrocie); `pair()` kopiuje `K_setup`, jeśli używa go asynchronicznie.
2. Kolejność połączenia ze spec 8.1: skan z filtrem UUID usługi (do 10 s) lub bezpośrednie połączenie ze znanym adresem → `connectGatt` → odkrycie usług → odczyt INFO → szyfrowane, zbondowane łącze LE SC → włączenie wskazań TX (CCCD) → MTU 247 (kontynuacja przy 23) → handshake → `Ready`.
3. Jedna kolejka operacji GATT (spec 8.1): operacja za operacją, czekanie na callback, timeout operacji.
4. Transport i protokół: fragmentacja/składanie (`Fragmenter`, `Reassembler`), `TransferIdGenerator`, budżety `TimingBudget.forPhone` + `PhoneIntervalEstimator` (spec 10.4), handshake `SessionHandshake`/`PairingHandshake`, sesja `ClientSession` — wszystko z `:protocol`.
5. Retransmisje i limity (spec 10.4, 13.4): po pierwszym i drugim upływie `T_response` retransmisja **identycznych bajtów** z nowym `transfer_id`; po trzecim — unieważnienie sesji, `CommandUnknown` dla komendy w toku, odtworzenie sesji świeżym handshake **na zachowanym łączu** (`Rekeying` → `Ready`), bez zrywania BLE. Limit `T_attempt` dla handshake.
6. Rekey bez zrywania łącza, gdy `ClientSession.needsRekey` (spec 12.3).
7. Weryfikacja tożsamości sterownika: INFO (`protocol_version = 2`) i `device_id` zgodny z oczekiwanym; tożsamość potwierdza dopiero handshake (spec 8.1, 8.2).
8. Testy JVM z symulowanym sterownikiem i wirtualnym czasem.

### Poza zakresem

- Polityka połączenia, backoff 12.4, decyzje UI — `:domain`.
- Uprawnienia runtime, `CompanionDeviceManager` (powiązanie przy dodawaniu bramy), foreground service, DI — `:app`.
- Aplikacja-symulator z serwerem GATT na drugim telefonie (decyzja użytkownika: nie teraz).
- Firmware ESP32.
- Odtwarzanie sesji po restarcie procesu na zachowanym łączu (12.1): klient GATT Androida ginie z procesem, więc nowy proces zawsze zaczyna nowe połączenie.

### Anty-cele

- Retransmisja ze zmienionym `seq`, ciphertextem lub tagiem; ponowne wysłanie komendy w nowej sesji.
- Równoległe operacje GATT.
- Wysłanie OPEN/CLOSE/PING przed `Ready` lub poza zaszyfrowanym, zbondowanym łączem.
- Samodzielne ponowne łączenie po utracie łącza (to decyzja `:domain`); jedyny wyjątek to rekey/odtworzenie sesji na **zachowanym** łączu.
- Logowanie kluczy, nonce, QR, odszyfrowanych payloadów.

## 3. Ograniczenia

- Moduł Android library (`com.android.library`), pakiet `com.agendek.bramav2.ble`, `minSdk = 33`, `compileSdk = 37` (jak `:app`). Zależności: `:protocol`, `:domain`, `kotlinx-coroutines-core` 1.10.2 (+ `-android` dla dispatcherów, jeśli potrzebne); testy: JUnit 4, `kotlinx-coroutines-test` 1.10.2. Build `--offline`.
- Kod rdzenia (pakiet `...ble.core`) **bez importów Androida** — testowany jako zwykłe testy jednostkowe JVM (`src/test`). Warstwa Androida (`...ble.android`) cienka, bez logiki.
- Obiekty `:protocol` nie są bezpieczne wątkowo: cały stan rdzenia w jednym aktorze (jedna korutyna przetwarzająca kanał zdarzeń); callbacki BLE (wątki bindera) tylko wrzucają zdarzenia do kanału.
- Czas: zegar monotoniczny i opóźnienia wstrzykiwane (wirtualny czas w testach).

## 4. Architektura

```text
 :domain ── GateLink ──► BleGateLink (rdzeń, aktor)
                            │  używa :protocol (handshake, sesja, fragmentacja, budżety)
                            ▼
                         BleRadio (port do warstwy radiowej)
                            ▲                    ▲
               AndroidBleRadio (BluetoothGatt)   SimulatedRadio + SimulatedGate (testy JVM)
```

### 4.1. Port `BleRadio` (pakiet `core`)

Minimalny interfejs nad BLE Androida; wszystkie operacje asynchroniczne, wyniki jako zdarzenia `RadioEvent` z uchwytem połączenia radiowego (`RadioHandle`), aby rdzeń odrzucał callbacki starych uchwytów:

- `scan(serviceUuid, timeoutMs)` → `ScanResult(address)` (wiele) / `ScanFinished`;
- `connect(address)` → `RadioHandle`; zdarzenia `Connected`, `Disconnected(status)`;
- na uchwycie: `discoverServices`, `readInfo`, `ensureBonded`, `enableIndications`, `requestMtu(247)`, `writeRx(bytes)` (Write Request), `disconnect`, `close`;
- zdarzenia: `ServicesDiscovered(ok)`, `InfoRead(bytes?)`, `Bonded(ok)`, `IndicationsEnabled(ok)`, `MtuChanged(mtu)`, `RxWritten(ok)`, `TxIndication(bytes)`;
- `removeBond(address)` (17.11.2026): usuwa bond systemowy z urządzeniem (patrz §4.6); w `AndroidBleRadio` przez ukryte `BluetoothDevice.removeBond()` (refleksja, sprawdzone na Androidzie 13 i 16), bez skutku, gdy bondu nie ma.

Dokładne sygnatury — budowniczy.

### 4.2. `BleGateLink` (implementacja `GateLink`)

Aktor z maszyną stanów jednego połączenia na raz (aplikacja ma jedną bramę, spec decyzja 8):

`IDLE → SCANNING → CONNECTING → DISCOVERING → READING_INFO → BONDING → SUBSCRIBING → MTU → HANDSHAKE → READY (↔ REKEYING)`

- `connect(deviceId, clientId, key)` / `pair(qr, clientId)` zwracają nowy `connectionId` (licznik w procesie) i startują maszynę; poprzednie połączenie (jeśli jest) jest zamykane bez emitowania zdarzeń.
- Mapowanie na `LinkEvent`: `Scanning` (skan), `Connecting` (connectGatt), `SecuringLink` (bonding/szyfrowanie), `Authenticating` (handshake), `Rekeying`, `Ready(snapshot)`, `RuleStatus`, `Pong`, `CommandResult`, `CommandUnknown`, `SessionLost`, `HandshakeRejected`, `HandshakeFailed`, `GrantReceived`, `PairingDone`, `Disconnected`.
- Nieudany etap przed handshake (brak urządzenia w 10 s, błąd GATT, INFO niezgodne, bonding nieudany) → `Disconnected` (domena traktuje to jak nieudaną próbę).
- Utrata łącza radiowego → `CommandUnknown` dla żądania w toku (jeśli było), potem `Disconnected`.
- Wybór urządzenia ze skanu: pierwsze z poprawnym INFO i oczekiwanym `device_id` (dla `pair` — `device_id` z QR); inne są rozłączane i skan trwa do limitu.
- Pamięć adresu: ostatni adres, pod którym znaleziono dany `device_id`, w pamięci procesu — kolejne `connect` próbują najpierw bezpośredniego połączenia, potem skanu.

### 4.3. Sesja i komendy

- `send(request)` tylko w READY; jedno żądanie w toku (asercja — pilnuje `:domain`).
- Wysłanie: `ClientSession.send` → `Fragmenter` (budżet z MTU) → kolejne `writeRx` przez kolejkę GATT; pomiar czasu Write Request → `onCharacteristicWrite` do `PhoneIntervalEstimator`.
- Odbiór: `TxIndication` → `Reassembler` → `ClientSession.onFrame` / handshake `onFrame` → zdarzenia.
- `T_response` liczony od zakończenia wysłania (spec 10.4), zamrożony per operacja; retransmisje jak w §2 pkt 5.
- `SessionEvent.Failed` → `CommandUnknown(unresolved)` (jeśli było) + odtworzenie sesji (`Rekeying`).
- **`send()` poza READY (moja decyzja):** `send()` tylko wrzuca żądanie do aktora (nie rzuca wyjątku z powodu stanu). Jeśli w chwili obsługi sesja nie jest READY (wyścig z utratą sesji lub rekeyem), żądanie nie jest wysyłane, a `:ble` emituje `CommandUnknown(request)` — wynik konserwatywny: `:domain` nie może wiedzieć, czy zapis zdążył wyjść, a spec 13.4 każe w razie wątpliwości pokazać wynik nieznany bez ponawiania. Wyjątek z `send()` tylko przy naruszeniu kontraktu (drugie żądanie w toku).

### 4.3a. Odtwarzanie sesji na zachowanym łączu (decyzja użytkownika)

Łącze, które w tej instancji połączenia osiągnęło READY, jest łączem właściciela: spec 10.4/12.4 (A45, A47) zabraniają zrywania go z powodu timeoutów protokołu, błędów handshake czy limitu prób. Dlatego **`:ble` sam odtwarza sesję na tym samym łączu**:

- Każde unieważnienie sesji (trzeci `T_response`, `SessionEvent.Failed`, `needsRekey`) → `Rekeying` i świeży `SessionHandshake` na tym samym łączu.
- Nieudany handshake na zachowanym łączu:
  - `HandshakeFailed` (błąd protokołu, `T_attempt`, trzeci `T_response`) → zdarzenie `HandshakeFailed`, kolejna próba po krótkim odstępie 2 s, podwajanym do 60 s;
  - `HANDSHAKE_REJECT` UNAVAILABLE/RATE_LIMITED → zdarzenie `HandshakeRejected(reason)`, kolejna próba po 30 s, podwajanym do 5 min (12.4);
  - `HANDSHAKE_REJECT` z pozostałymi kodami (AUTH_FAILED, …) → zdarzenie `HandshakeRejected(reason)`, **bez** dalszych prób; łącze pozostaje, decyzję podejmuje `:domain`.
  - Licznik odstępów zerowany po `Ready`.
- `SessionLost` nie jest emitowane na zachowanym łączu, dopóki istnieje radio; utrata radia → `CommandUnknown` (jeśli było żądanie) + `Disconnected`.

**Wymagana zmiana `:domain` (część planu `:ble`):** dla połączenia, które osiągnęło READY, `HandshakeFailed` i `HandshakeRejected` z kodem UNAVAILABLE/RATE_LIMITED **nie** zrywają łącza i nie uruchamiają własnych ponowień domeny — domena pokazuje komunikat (`handshakeProblem`), stan `REKEYING`, przyciski wyłączone, i czeka na `Ready` od `:ble`. Kody terminalne (AUTH_FAILED itd.) zachowują obecne zachowanie (zerwanie i blokada auto-ponowień): odmowa klucza oznacza zwykle reset autoryzacji, po którym sterownik i tak rozbroił regułę (9.6). Dla prób, które nie osiągnęły READY, zachowanie domeny bez zmian.

### 4.4. Rejestracja

`pair` → maszyna jak w §4.2 z `PairingHandshake` (`K_setup` z kopii QR) → `GrantReceived(key)` → czekanie na `commitPairing()` / `abortPairing()` → COMMIT → `PairingDone` (ostatnie zdarzenie tego połączenia; połączenie parowania kończone) albo `abortPairing` → `handshake.abort()` + rozłączenie, bez zdarzeń.

- **Próba kończy się po GRANT bez DONE (moja decyzja):** wyczerpane retransmisje COMMIT, upływ `T_attempt` (także między `GrantReceived` a `commitPairing()`) → `handshake.abort()`, zakończenie połączenia parowania i zdarzenie `Disconnected`. `:domain` przechodzi wtedy do recovery 11.3 krok 1 (zwykła sesja z zapisanym K1; A13). `HandshakeFailed` tylko dla porażek przed GRANT.
- **Bonding (moja decyzja):** nowy bond tworzy wyłącznie `pair()` (okno rejestracji, CANDIDATE, spec 9.5). `connect()` nigdy nie inicjuje parowania systemowego — sterownik poza oknem i tak je odrzuci, a dialog byłby mylący. Brak bondu lub nieudane szyfrowanie przy `connect()` → `Disconnected` (nieudana próba). Brak bondu przy `connect()` → `BondMissing`; naprawa bondu przez `repairBond()` (parowanie dozwolone, nieudane → `BondFailed`) — `doc/bond_repair_design.md` §4.1.

### 4.5. Rejestracja PIN-em (spec 11.4, `doc/pin_presence_design.md` §4)

- `pair(PairingSecret.Pin, clientId)`: zawsze skan (bez zapamiętanego adresu); kandydat = INFO z bitem 5 `capabilities` (`GateInfo.supportsPin`), urządzenie bez bitu 5 odrzucane do końca próby; `device_id` z INFO jest podpowiedzią.
- Po bondzie, CCCD i MTU: `PinPakeHandshake` (rundy 0x26–0x29, `T_response`, 2 retransmisje, `T_attempt` od PAKE_ROUND1) → `K_pin` → `PairingHandshake(K_pin, device_id z INFO)` z nowym `T_attempt`.
- Adres trafia do `knownAddresses` dopiero po poprawnym PAIR_CHALLENGE (pierwszy CONFIRM). Odmowa lub porażka PAKE/PAIR kończy próbę jak dotąd.
- `GrantReceived(clientKey, deviceId)` w obu ścieżkach. Symulator: PIN, okno rejestracji, limit 5 nieudanych potwierdzeń, fałszywy sterownik (`forgeChallenge`).

### 4.6. Bond systemowy po nieudanej rejestracji i usunięciu bramy (17.11.2026)

- **Reguła:** w `BleGateLink.close()` połączenie parowania, które nie dostało `PAIR_DONE` i nie wysłało `PAIR_COMMIT` (`pairing && !pairingDone && !commitSent`), usuwa bond systemowy (`radio.removeBond(address)`). Dotyczy złego PIN-u, odmowy sterownika przed GRANT, timeoutu próby, anulowania i utraty łącza przed COMMIT. Po wysłaniu `COMMIT` bond zostaje (sterownik mógł już zatwierdzić klienta; recovery 11.3 używa zwykłej sesji).
- **Powód:** zostawiony bond psuł następną rejestrację tej samej bramy (sterownik zna go po IRK i wysyła Security Request, a nowa rejestracja nie ma już jego klucza).
- `BleGateLink.knownAddresses()` zwraca adresy znane w procesie; `:app` używa ich razem z adresami powiązań CDM do usunięcia bondów przy „Usuń z telefonu”.
- Ryzyko: `removeBond()` jest ukrytym API (refleksja); przyszła wersja Androida może je zablokować, wtedy bond zostaje i użytkownik musi go zapomnieć w ustawieniach Bluetooth.
- Testy: `successfulPairingKeepsSystemBond`, `abortBeforeCommitRemovesSystemBond`, `attemptExpiringBeforeCommitRemovesSystemBond`, `rejectBeforeGrantRemovesSystemBond`, `lostDoneAfterCommitKeepsSystemBond` (`BleGateLinkPairingTest`), `wrongPinRemovesSystemBond` (`BleGateLinkPinPairingTest`); `RecordingRadio` i `SimulatedRadio` implementują `removeBond`.

### 4.7. Reklama sterownika i dostępność PIN (17.11.2026, spec 8.3, 11.4)

- `GateAdvertisement` (pakiet `core`, czysta funkcja): dane producenta `0xFFFF` z reklamy, 1. bajt = liczba pozostałych prób PIN-u (0–5), dalsze bajty ignorowane; wynik `PinAvailability` = `Unknown` (brak pola lub błędne dane: niczego nie blokuje), `Available(n)`, `Unavailable` (0 prób). `GateAdvertisement.aggregate` łączy stany kilku bramek w zasięgu: bramki bez pola nie wpływają na wynik, blokada tylko, gdy wszystkie bramki z polem mają 0 prób, `Available` z największą liczbą prób.
- `AndroidGateWatcher` (pakiet `android`, poza `BleGateLink` i portem `GateLink`): własny skan z filtrem UUID usługi, stan per adres urządzenia wygasający po 5 s, publikacja tylko przy zmianie stanu, tykanie co 2,5 s do wygaszania, `onScanFailed` → `Unknown`. Zbierany przez kreator „Dodaj bramę” tylko poza rejestracją w toku, żeby nie rywalizować ze skanem `BleGateLink`.
- Testy: `GateAdvertisementTest` (parser, dane zniekształcone, bajty dodatkowe, agregacja). Logika skanu Androida nie ma testu jednostkowego.

## 5. Testowanie

- `SimulatedRadio` + `SimulatedGate` (testy JVM): sterownik zgodny ze spec 11–13 — handshake PAIR/SESSION, cache odpowiedzi (13.4), `HANDSHAKE_REJECT`, ACK ze snapshotem, RULE_STATUS; sterowalne usterki: utrata wskazania/ACK, opóźnienia callbacków, błędy GATT, zerwanie łącza, obce urządzenie w skanie, MTU 23/247/517.
- Wirtualny czas (`kotlinx-coroutines-test`) dla `T_response`, `T_attempt`, skanu 10 s.
- Scenariusze obowiązkowe: A05/A06 (duplikat i utrata ACK z dwiema retransmisjami), A07/A45 (trzeci timeout → `CommandUnknown`, rekey bez zerwania BLE), A10 (fałszywe urządzenie), A13 (utracony DONE), A24/A37/A49 (MTU, fragmentacja, budżety), A36 (RULE_STATUS przed ACK), A39 (rekey), A47/A48 (odmowy), kontrakt `connectionId` (brak zdarzeń po `disconnect`/`abortPairing`/`PairingDone`, zdarzenia starego uchwytu odrzucane).
- `AndroidBleRadio` bez testów automatycznych (emulator nie wspiera BLE); weryfikacja z płytką.

## 6. Decyzje podjęte przy przeglądzie

Użytkownik:
- Na razie wyłącznie symulacja w testach JVM (bez aplikacji-symulatora na drugim telefonie).
- Odtwarzanie sesji na zachowanym łączu robi `:ble`; `:domain` dostaje małą zmianę, aby nie zrywać łącza właściciela przy błędach handshake na takim łączu (§4.3a).

Moje (do sprawdzenia):
- `send()` poza READY → `CommandUnknown` (konserwatywnie), bez wyjątku (§4.3).
- PAIR zakończone po GRANT bez DONE → `Disconnected` (recovery 11.3 krok 1) (§4.4).
- `connect()` nie tworzy bondu; brak bondu → `Disconnected` (§4.4).
- Odstępy ponowień na zachowanym łączu: błędy 2 s → 60 s, odmowy UNAVAILABLE/RATE_LIMITED 30 s → 5 min, kody terminalne bez ponowień (§4.3a).

## 7. Pozostawione budowniczemu

- Dokładne sygnatury `BleRadio`/`RadioEvent`, podział plików.
- Timeouty pojedynczych operacji GATT (discover, read, bond, CCCD, MTU, write) — rozsądne wartości, zapisane jako stałe.
- Sposób wymuszenia bondingu na Androidzie (`createBond` vs odczyt chronionej charakterystyki).
- Dispatcher aktora w produkcji.

## 8. Aktualizacja firmware (spec 1.5)

Opis w `doc/ota_design.md` §4: odczyt Device Information (`0x2A26`) po INFO, przed bondem, tylko dla sesji; `GateInfo.supportsOta`; `LinkEvent.Ready(snapshot, LinkInfo)` i `LinkEvent.OtaResult`. Transferem steruje `:domain`; `:ble` traktuje żądania OTA jak każde żądanie sesji (retransmisje, `T_response`, `CommandUnknown`).

## 9. Telefony z dostępem (18.11.2026, `doc/client_management_design.md` §5.1–5.2)

`:protocol`: `MessageType` (`LIST_CLIENTS`, `REMOVE_CLIENT`, `SET_CLIENT_NAME`, `CLIENT_LIST`), `AckResult` `09`–`0D`, `SessionRequest`, kodek wpisu listy (38 B) i zdarzenie `ClientListReceived`. `BleGateLink` przekazuje nowe żądania jak PING (jedno żądanie w toku, `T_response` jak dotąd); lista (≤ 351 B) idzie zwykłą fragmentacją. `SimulatedGate` obsługuje listę, usuwanie (z odmowami) i nazwy. INFO i `capabilities` bez zmian.
