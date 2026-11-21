# Status implementacji

Specyfikacja: `doc/PLAN_I_SPECYFIKACJA_BRAMY.md` (wersja 1.5, protokół v2; wersje 1.3 i 1.4 w `doc/archiwum/`).
Ostatnia aktualizacja: 28.10.2026. Ostatni pełny build (28.10.2026, przed OTA na Androidzie): wszystkie testy zielone — `:protocol` 174 (+2 pominięte generatory wektorów), firmware 144 testy hostowe i build `esp32` bez ostrzeżeń, `:domain` 246, `:ble` 97, `:car` 30, `:app` 39; `:app:assembleDebug` bez ostrzeżeń kompilacji, lint bez błędów.

Po dodaniu OTA na Androidzie (28.10.2026): pełny build na Ubuntu przeszedł — testy hostowe firmware, build `esp32`, `:protocol:test :domain:test :ble:testDebugUnitTest :car:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` (wynik zgłoszony przez użytkownika). Po wektorach negatywnych: firmware 154 testy hostowe zielone (144 + 2 OTA + 8 wektorów). Po naprawie bondu (28.10.2026, na Macu): `:protocol` 206 (+2 pominięte), `:domain` 293, rdzeń `:ble` 115, mapery `:app` 26; firmware: aktor/OTA/reguła/autoryzacja 91 testów i sesja/wektory negatywne (bez PAKE) przez uprząż ze stubami (`/tmp/fw-actor-test.sh`, `/tmp/fw-session-test.sh`) . Pełny build na Ubuntu po naprawie bondu przeszedł (wynik zgłoszony przez użytkownika): testy hostowe firmware, build `esp32` (z `brama_ble`), `:protocol:test :domain:test :ble:testDebugUnitTest :car:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.

## Podsumowanie

| Moduł / etap | Status |
|---|---|
| Etap 0 — weryfikacja wykonalności | Nie rozpoczęty |
| Etap 1 — `:protocol` (strona Android) | Zakończony: 193 testy JVM (+2 pomijane generatory wektorów), 8 rund code review; spec 1.4: QR z PIN-em, PAKE 0x26–0x29, `EcJpake` zgodne z mbedTLS; spec 1.5: OTA w `ClientSession`, `FirmwareImages` |
| OTA na Androidzie (spec 15.5) | Zaimplementowana wg `doc/plans/2026-10-28-ota.md` we wszystkich modułach i w firmware (odrzucony END odnawia limit 30 s); pełny build zielony; transfer do sprawdzenia z płytką (A76–A81) |
| Naprawa bondu przy zachowanym `K_client` (spec 9.5) | Zaimplementowana wg `doc/plans/2026-10-28-bond-repair.md` (projekt `doc/bond_repair_design.md`) w firmware, `:ble`, `:domain`, `:app`; po niezależnym przeglądzie; pełny build i testy na Ubuntu zielone; czeka na test z płytką (A33, A57) |
| Ekran Android Auto w stylu telefonu (decyzja 19.11.2026) | Zaimplementowany: grafika bramy, linia stanu połączenia z kolorową kropką, OTWÓRZ (zielony) i ZAMKNIJ; teksty statusów usunięte (`carPane`, `GateScreen`, `CarPaneTest`); `:car:testDebugUnitTest :app:assembleDebug` i lint zielone. **Do sprawdzenia na DHU lub w aucie:** czy host akceptuje obraz w `Pane` i akcję `FLAG_PRIMARY` z kolorem tła oraz wygląd kropki (span `ForegroundCarColorSpan`). Powiadomienie w samochodzie też tylko z nazwą bramy i linią stanu (`UiMessages.carNotification(state)`, testy `:domain` i `CarNotificationPolicyTest` zielone). |
| Nowe reguły powiadomienia „w zasięgu” (decyzja 19.11.2026) | Zaimplementowane: bez licznika 5 min; `notificationDismissed()` (tap, `deleteIntent`, UI na pierwszym planie, brama pojawiająca się przy widocznym UI) → QUIET z rozłączeniem; karencja nieobecności 30 s; Android Auto bez zmian (znika razem z SHOWN). Spec 4.3 pkt 4–5, A71, A81 i dokumenty projektowe zaktualizowane. `:domain:test :app:testDebugUnitTest :car:testDebugUnitTest :app:assembleDebug :app:lintDebug` zielone (19.11.2026). Po review (19.11.2026): test odrzucenia w trakcie OTA, `DismissWhenUiVisibleSink` z testami w `:app`. **Do sprawdzenia na Androidzie 13 i nowszym (A71):** czy `deleteIntent` dochodzi dla odrzuconego powiadomienia usługi `connectedDevice` i czy usługa działa dalej. |
| `minSdk` 33 (decyzja 13.11.2026) | Obniżone z 34 w `:app`, `:ble`, `:car`; jedyne API 34 w kodzie (`AssociationInfo.associatedDevice`) zastąpione `getRemoteLeDevice` (API 33); testy, `assembleDebug` i lint zielone; czeka na test CDM (obecność BLE, start usługi z tła, A70–A72) na telefonie z Androidem 13 |
| Okno rejestracji z telefonu (decyzja 28.10.2026) | Zaimplementowane wg `doc/plans/2026-10-28-phone-window.md` (projekt `doc/phone_window_design.md`): typ `03`, wynik ACK `08`, bit 7, firmware, wszystkie moduły telefonu; po niezależnym przeglądzie; pełny build i testy na Ubuntu zielone (28.10.2026); czeka na test z płytką (A82) |
| Etap 1 — wektory zgodności (spec 16.3) | Scenariusze poprawne i przypadki negatywne w `protocol/src/test/resources/vectors/` (pliki `klucz=hex` zamiast `protocol-v2-vectors.json`, projekt `doc/vectors_design.md`); telefon: `NegativeVectorsTest` (13 testów, zielone); firmware: `test_negative.c` (8 testów, zielone) |
| `:domain` | Zakończony: 246 testów JVM; spec 1.4: CLOSE bez potwierdzenia, rejestracja PIN-em, powiadomienie „brama w zasięgu”; po przeglądach `:app`: `backgroundLinkNeeded` i wygaśnięcie nieaktualnej własnej reguły po utracie łącza (spec 4.2, A73) |
| `:ble` | Zaimplementowany wg `doc/plans/2026-10-27-ble.md`: 97 testów JVM z symulowanym sterownikiem; spec 1.4: rejestracja PIN-em; `AndroidBleRadio` bez testów (wymaga płytki) |
| `:app` (UI, usługa) | Zaimplementowany wg `doc/plans/2026-10-27-app.md`, po trzech rundach przeglądu (zmiany w `doc/app_design.md` §13): 39 testów JVM (magazyny, mapery, bramka obecności; testy tekstów przeniesione do `:domain`); części zależne od urządzenia skompilowane, do sprawdzenia z płytką (A27, A58, A70–A73) |
| `:car` (Android Auto) | Zaimplementowany wg `doc/plans/2026-10-27-car.md`: 30 testów JVM (model ekranu, polityka powiadomienia w samochodzie); usługa, ekran i powiadomienie skompilowane, do sprawdzenia na DHU lub w aucie |
| Firmware ESP32 | „Sucha” implementacja wg `doc/plans/2026-10-27-firmware.md` (katalog `firmware/`): 144 testy hostowe Unity (target `linux`), build `esp32` z podpisanym obrazem; BLE, GPIO, RTC, NVS i OTA na układzie tylko skompilowane — do sprawdzenia z płytką |

## Konfiguracja projektu

- `minSdk = 33` (decyzja użytkownika z 13.11.2026, zapisana w specyfikacji, rozdział 7; wcześniej 34, pierwotnie proponowano 26). Jedyne API 34 w kodzie (`AssociationInfo.associatedDevice`) zastąpione `BluetoothAdapter.getRemoteLeDevice(adres, RANDOM)` (API 33). **Do sprawdzenia na telefonie z Androidem 13:** obecność BLE przez CDM (`onDeviceAppeared`) i start usługi `connectedDevice` z tła (A70–A72), dialog parowania.
- `compileSdk = 37` (podniesione z 36.1, bo `core-ktx:1.19.0` wymaga 37), `targetSdk = 36`.
- `:protocol` — czysty Kotlin/JVM (`org.jetbrains.kotlin.jvm`), JVM target 11, toolchain 21, testy JUnit 4.
- Katalog nie jest repozytorium git.

### Budowanie i testy

- Pełny zestaw: `./gradlew :protocol:test :domain:test :ble:testDebugUnitTest :car:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`.
- Pierwszy build `:app` i `:car` wymaga internetu (Navigation Compose, DataStore, skaner kodów Google, Car App Library). Kolejne buildy mogą iść z `--offline`.
- Wektory interop EC-JPAKE (`protocol/src/test/resources/ecjpake/`) są w repozytorium. Ich ponowna generacja wymaga `gcc` i mbedTLS z ESP-IDF — instrukcja w `README.md` w tym katalogu.
- Lint: `./gradlew :app:lintDebug :car:lintDebug`.

## Moduł `:protocol`

Pakiet `com.agendek.bramav2.protocol`, bez zależności od Androida.

| Plik | Zakres specyfikacji |
|---|---|
| `Crypto.kt` | HMAC-SHA-256, HKDF (RFC 5869), AES-256-GCM, `MAC(K, D, X)`, porównanie w stałym czasie; wektory 16.2 |
| `MessageType.kt` | Typy wiadomości z kierunkiem, rodzajem tagu i fazą (`HANDSHAKE`/`PAIR`/`SESSION`) |
| `Frame.kt` | Nagłówek 22 B i ramka (10.3) z walidacją |
| `Fragmentation.kt` | Fragmentacja i składanie (10.2), `TransferIdGenerator` |
| `TimingBudget.kt` | Budżety 10.4, `PhoneIntervalEstimator` |
| `RuleSnapshot.kt` | 15-bajtowy snapshot z walidacją spójności (12.2) |
| `Payloads.kt` | `COMMAND_ACK`, `AckResult`, `HandshakeRejectReason`, payloady komend |
| `ProvisioningQr.kt` | Parser QR (9.2) |
| `HandshakeCrypto.kt` | Transkrypt `T`, `KeySchedule`, ramki HMAC handshake, `GcmFrames`, `ChallengeBody` |
| `ClientHandshake.kt` | `SessionHandshake`, `PairingHandshake` (strona telefonu) |
| `ClientSession.kt` | Sesja READY: seq, duplikaty, dopasowanie ACK, kolejność snapshotów |
| `Hex.kt`, `Bytes.kt`, `NonceSource.kt`, `ProtocolException.kt` | Pomocnicze |

Testy: `FakeGate` w źródłach testowych symuluje stronę ESP32 (handshake, READY/ACK/PONG, cache odpowiedzi). Kluczowe kontrole sprawdzone mutacyjnie.

### Decyzje i odstępstwa

- Nieuwierzytelnione lub błędne ramki unieważniają sesję/próbę handshake — **zgodnie ze specyfikacją** 10.4/13.4 (decyzja użytkownika po review, mimo możliwości DoS przez wstrzyknięcie).
- Poprawnie podpisany CHALLENGE z obcym `client_nonce` (spóźniony, z wcześniejszej próby) jest ignorowany, nie kończy próby (12.1).
- Kopia CHALLENGE po READY jest rozpoznawana przez `ClientSession` jako duplikat.
- `PhoneIntervalEstimator`: `max(100, mediana)` zgodnie z 10.4; skrajne próbki przycinane do 2 000 000 ms (maksymalny efektywny interwał BLE), co gwarantuje brak przepełnienia `Int`.
- Wynik ACK `STORAGE_FAILURE` jest przekazywany bez zmian; mapowanie na „Błąd zgodności aplikacji i sterownika” należy do `:domain`/UI.
- Klasy stanowe nie są bezpieczne wątkowo — kontrakt: jeden wątek/aktor (kolejka GATT w `:ble`).
- Po zakończeniu próby handshake (Failed/Rejected/Paired/Established) obiekt zeruje swoją kopię `K_setup`/`K_client` i klucze próby; po READY klucze sesji przechodzą do `ClientSession` (9.2: minimalny czas życia sekretów).
- `ClientSession.invalidate()` (także po błędzie protokołu) zeruje klucze sesji (`SessionKeys.wipe()`).
- `ClientHandshake.abort()` — porzucenie próby przez `:ble` (trzeci T_response, T_attempt, rozłączenie, nieudany zapis `K_client` po GRANT) zeruje sekrety.
- `PairingHandshake.commit()` zwraca `null`, jeśli próba zakończyła się w trakcie zapisu `K_client` (dalej recovery z 11.3); wywołanie przed GRANT to błąd programisty (wyjątek).
- `TransferIdGenerator` startuje od losowego id, aby proces odtworzony na zachowanym łączu nie powtórzył id niedokończonego transferu.
- Parser QR sprawdza wersję przed zestawem pól: QR nowszej wersji daje `UNSUPPORTED_VERSION`, nie `MALFORMED`.
- `aesGcmOpen` traktuje każdy `BadPaddingException` (nie tylko `AEADBadTagException`) jako zły tag.
- PRK z HKDF i bloki pośrednie są zerowane po wyprowadzeniu kluczy (najlepszy możliwy wysiłek — JCA trzyma własne kopie).
- Konstruktor handshake wymaga klucza długoterminowego dokładnie 32 B (uszkodzony `K_client` to błąd lokalny, nie AUTH_FAILED).
- `revision` snapshotu porównywane jako LE64 bez znaku, zgodnie z typem pola na łączu.
- Snapshot z READY dostępny wyłącznie przez `session.latestSnapshot` (jedno źródło prawdy).
- Funkcje strony sterownika (`HandshakeFrames.challenge`, `verifyBegin`, `verifyConfirm`, `CommandAck.encode`) pozostają w kodzie produkcyjnym: potrzebne do generowania wektorów zgodności (16.3) i jako implementacja odniesienia dla firmware.
- `Reassembler` odrzuca od razu `fragment_count` większy niż możliwy przy wynegocjowanym MTU.
- `hexToBytes` przyjmuje wyłącznie ASCII `0-9a-fA-F`.
- Typy `AutoClosePhase`, `OwnerRelation`, `RuleReason`, `AckResult`, `HandshakeRejectReason`, `RuleSnapshot` mają nazwy z rozdziału 3, aby `:domain` mógł ich używać bez duplikowania.

- Wykrywanie duplikatów pozostaje osobne w handshake (jedna ramka na etap) i w sesji (cache po `seq`, 13.4) — różna semantyka.

### Świadomie odłożone

- Timery retransmisji, liczenie `T_response`/`T_attempt` w czasie rzeczywistym — w `:ble`.
- Przypadki PIN wymagające rund serwera EC-JPAKE jako wspólne bajty (firmware losuje je z PSA) — `doc/vectors_design.md` §3.5.
- Optymalizacje wydajności zgłoszone w review (bufor składania, cache `Mac`/`Cipher`, podwójna walidacja w `Frame.decode`) — pomijalny koszt przy ramkach ≤ 512 B; odrzucone świadomie w kolejnych rundach review.

## Moduł `:domain`

Pakiet `com.agendek.bramav2.domain`, czysty Kotlin/JVM (`:protocol`, coroutines 1.10.2). Projekt: `doc/domain_design.md`, plan: `doc/plans/2026-10-27-domain.md`.

| Plik | Zakres |
|---|---|
| `Model.kt` | Typy stanu UI (spec 3), `GateUiState` |
| `MessageId.kt` | 44 komunikaty listy zamkniętej + 2 etykiety `LABEL_*` |
| `Messages.kt`, `UiMessages.kt` | Mapowanie stanu na komunikaty; kolejność telefon/samochód (5.2) |
| `ConnectionPolicy.kt` | Powody połączenia, „Rozłącz”/„Połącz”, backoff 30 s → 5 min (12.4) |
| `Ports.kt` | `GateLink` (dla `:ble`), magazyny i `ConnectionKeeper` (dla `:app`) |
| `GateRepository.kt` | Aktor: połączenie, świeżość reguły, OPEN/CLOSE, potwierdzenia per źródło (rozłączenie, usunięcie, włączenie autozamykania), historia, rejestracja z recovery, powiadomienie „brama w zasięgu” |

### Decyzje i odstępstwa (`:domain`)

- Decyzje z przeglądu projektu: `doc/domain_design.md` §8.
- **Od wersji 1.4 CLOSE nie ma potwierdzenia** (`requestClose` wysyła od razu); wpisy niżej dotyczące potwierdzeń obejmują wyłącznie „Rozłącz”, „Usuń z telefonu” i włączenie autozamykania.
- `RuleView.refreshing` odróżnia „Odświeżanie…” od „nieaktualny”; sekundy zaokrąglane w górę; historia nie jest czyszczona przy „Usuń z telefonu”.
- `GateUiState.handshakeProblem` — tekst ostatniej odmowy handshake (plan go nie przewidywał), czyszczony po READY.
- `HandshakeFailed` → ponowienie po 2 s bez zużywania backoffu 12.4 (backoff tylko dla jawnych UNAVAILABLE/RATE_LIMITED; przy powtarzających się błędach sterownik sam odpowie RATE_LIMITED); w trakcie PAIR → `REGISTRATION_INCOMPLETE`. UNAVAILABLE/RATE_LIMITED w trakcie PAIR też uruchamiają backoff przed powrotem do zwykłej sesji. Nieudany zapis `K_client` → `REGISTRATION_INCOMPLETE`.
- Komenda czekająca za PING, który przepadł, jest wycofywana (nie była wysłana); wysłana komenda przy utracie sesji → OUTCOME_UNKNOWN.
- Utrata łącza po READY (zerwana sesja) → natychmiastowe ponowne łączenie (właściciel ma 30 s, 14.2). Nieudana próba przed READY (np. sterownik poza zasięgiem), nieudane `connect()` i `HandshakeFailed` → krótki odstęp 2 s, podwajany do 60 s, zerowany po READY i akcji użytkownika — bez backoffu 12.4, który przekreśliłby okno 30 s, i bez ciągłego skanowania w tle.
- BLOCKED/SELF utrzymuje łącze (spec 4.2, 14.4), ale „Rozłącz” w BLOCKED nadal bez ostrzeżenia (spec 4.1).
- Komenda w toku utrzymuje łącze do rozstrzygnięcia (np. koniec projekcji w trakcie OPEN), chyba że użytkownik świadomie rozłączył.
- Zdarzenia sesji po rozłączeniu zainicjowanym przez domenę są ignorowane; błąd portu nie zatrzymuje aktora; zablokowane poświadczenie odczytywane ponownie przy akcji UI.
- **Identyfikator połączenia w porcie (decyzja użytkownika):** `GateLink.connect()`/`pair()` zwracają `connectionId`, `GateLink.events` emituje `LinkMessage(connectionId, event)`; domena odrzuca zdarzenia innych połączeń niż bieżące, także już zakolejkowane. `:ble` musi nadawać nowy identyfikator każdemu połączeniu. Kontrakt `GateLink.disconnect()`: po powrocie brak zdarzeń starego połączenia. `GateLink.pair()`: `:ble` kopiuje `K_setup`, bo `:domain` go zeruje.
- `systemProblem` wyliczany: blokada poświadczenia > zgłoszenie `:app` > brak startu w tle (ten ostatni znika po udanym OPEN(true)).
- Rejestracja odrzucana przy zablokowanym poświadczeniu (nie wiadomo, czy istnieje ACTIVE). „Usuń z telefonu” najpierw usuwa poświadczenie; przy błędzie nic innego się nie zmienia.
- PING nie jest wysyłany obok komendy w toku (13.3) — „odświeżanie” rozstrzyga snapshot z ACK. Żądanie (komenda, PING) jest „w toku” dopiero po udanym `GateLink.send`; odrzucony zapis nie blokuje przycisków.
- Recovery po zerwaniu parowania tylko dla UNCONFIRMED tego samego `device_id`; nieudany odczyt klucza po PairingDone kończy rejestrację komunikatem `REGISTRATION_INCOMPLETE`.
- „Usuń z telefonu” z komendą w toku zapisuje OUTCOME_UNKNOWN w historii. Ostrzeżenie „może podać sygnał zamknięcia” także w BOOT_WAIT/SELF; nadal nie w BLOCKED/FAULT (spec 4.1). Potwierdzenie „Rozłącz” tylko przy MONITORING/SELF (spec 3, 4.2).
- Stan publikowany raz na obsłużoną wiadomość (brak stanów pośrednich, np. READY z nieaktualną regułą). Start aktora chroniony jak pętla wiadomości. Błędy portów łapane bez połykania `CancellationException`.
- Wyjątek z `GateLink.pair()`/`connect()` kończy próbę rejestracji komunikatem `REGISTRATION_INCOMPLETE`; każda porażka próby PAIR woła `abortPairing()`.
- `dismissNotification()` — UI zamyka powiadomienie, dzięki czemu kolejne identyczne powiadomienie daje nową emisję stanu.
- Ostrzeżenie przy usuwaniu w BLOCKED pozostaje wyłączone mimo napięcia z 14.4 — rozstrzyga spec 4.1; zmiana wymaga zmiany specyfikacji.
- Nieudany zapis ACTIVE po READY: komunikat `REGISTRATION_INCOMPLETE`, ponowienie przy „Odśwież”/„Połącz” na tej samej sesji. Wyjątek z `commitPairing()` kończy próbę (recovery 11.3 sesją z K1). PairingDone po „Rozłącz” nie łączy do świadomego „Połącz”.
- Wyjątki `:ble` przy sprzątaniu („Usuń”, anulowanie rejestracji) nie przerywają sprzątania. Nieudany zapis ostatniego stanu reguły jest ponawiany przy następnym snapshocie.
- Ostrzeżenie „Rozłącz” tylko przy (także nieaktualnym) MONITORING/SELF — spec 3 i 4.2; tekst stwierdza uruchomienie autozamykania jako pewnik, więc nie jest pokazywany przy nieznanym stanie.
- READY potwierdza poświadczenie przed oceną łącza (rejestracja kończy się także bez otwartego ekranu). Zniknięcie problemu systemowego ponownie ocenia łącze. Odmowa/błąd handshake wołają `GateLink.disconnect()`.
- Ponowne podpięcie UI zeruje licznik backoffu (następny odstęp 30 s), nie skraca bieżącego odliczania.
- `connectSuspended` wyliczane z `ConnectionPolicy.suspended`; spóźniony GRANT poza próbą jest zerowany.
- Odmowa rozpoczęcia rejestracji widoczna w stanie: ACTIVE → `REJECT_CLIENT_ALREADY_ACTIVE`, zablokowane poświadczenie → `CREDENTIAL_LOCKED`. Wyjątek z `ConnectionKeeper` = brak startu w tle.
- REBOOT_DISARMED ma pierwszeństwo przed REGISTRATION_DONE w jednym `notification` (bezpieczeństwo przed informacją). `ConnectionState.ERROR` i `CommandOutcome.IDLE` zostają mimo braku użycia — model z rozdziału 3 specyfikacji.
- Krótki odstęp po nieudanej próbie nie zastępuje trwającego backoffu. Kontrakt `GateLink.abortPairing()` jak `disconnect()`: brak spóźnionych zdarzeń przerwanej próby.
- Odpięcie źródła UI kasuje jego potwierdzenie (decyzja z zamkniętego ekranu nie wraca). Utrata sesji kasuje potwierdzenie „Rozłącz”; wyłączenie autozamykania kasuje oczekujące potwierdzenie jego włączenia.
- Nieudane `GateLink.connect()` → ponowienie po 2 s (łącze właściciela nie znika po cichu). Krótki odstęp może skrócić zniknięcie problemu systemowego; backoffu 12.4 nie.
- Synchronicznie odrzucony zapis GATT (komenda lub PING) = utrata łącza: rozłączenie i ponowne połączenie po 2 s; polecenie nie jest „w toku” ani „nieznane”.
- Kontrakt `LinkEvent.PairingDone`: ostatnie zdarzenie próby PAIR. `start()` idempotentne.
- Utracone połączenie domena kończy jawnie (`disconnect()`), a wszystkie zdarzenia przy nieaktywnym łączu odrzuca — rozróżnienie starych i nowych połączeń należy do `:ble` (kontrakt). Brak prób łączenia przy BLUETOOTH_OFF / braku uprawnień.
- Oczekujące potwierdzenie rejestracji (UNCONFIRMED, „potwierdzanie”) jest powodem połączenia także bez otwartego ekranu; PairingDone przechodzi przez zwykłą ocenę łącza. Chwilowy błąd połączenia po PairingDone nie kończy rejestracji (ponowienie), zablokowane poświadczenie → `CREDENTIAL_LOCKED`.
- `HandshakeFailed` nie kończy potwierdzania rejestracji (ponowienie, jak przy utracie łącza). Wynik komendy dla innego żądania niż wysłane (błąd `:ble`) → OUTCOME_UNKNOWN i nowa sesja. Łącze bez uprawnień BT lub dostępu do poświadczenia jest zrywane także z komendą w toku. Wyłączenie autozamykania zdejmuje „brak startu w tle”.
- `LastKnownRule` zapisywany przy zmianie fazy/właściciela/przyczyny; `revision`, `remaining_ms`, `receivedAtMillis` nie służą do oceny wieku (KDoc).
- Strumienie portów (`GateLink.events`, ustawienia) przeżywają awarię: utrata łącza i ponowna subskrypcja po 2 s; normalne zakończenie `GateLink.events` traktowane jak awaria.
- Każda faktyczna zmiana problemu systemowego ocenia łącze; powtórzone zgłoszenie tego samego stanu nie skraca krótkiego odstępu. PairingDone bez zapisanego GRANT → `REGISTRATION_INCOMPLETE`.
- Kontrakt `uiAttached`/`uiDetached`: idempotentne dla źródła (zbiór, nie licznik); `:app` woła je z jednego właściciela cyklu życia na źródło — do uwzględnienia przy implementacji `:app` i `:car`.
- **Pętla READY (decyzja użytkownika):** po utracie sesji, która osiągnęła READY, natychmiastowe łączenie (okno właściciela 30 s, 14.2); po więcej niż 3 kolejnych cyklach READY→utrata bez wymiany wiadomości ani akcji użytkownika — rosnący krótki odstęp (2 s → 60 s). Licznik zerowany przez ACK/PONG/RULE_STATUS i akcję użytkownika.
- Wszystkie powody utrzymania łącza w `ConnectionPolicy` (także komenda w toku i oczekujące potwierdzenie rejestracji). Odmowa ponawialna (12.4) nie kończy potwierdzania rejestracji. PairingDone wymaga GRANT zapisanego w bieżącej próbie.
- Licznik generacji timera backoffu (wyścig możliwy tylko na wielowątkowym dispatcherze — bez testu odtwarzającego).
- **Łącze w tle i wygaśnięcie własnej reguły (po przeglądach `:app`, spec 4.2, A73):**
  - `GateUiState.backgroundLinkNeeded` mówi `:app`, że łącze jest potrzebne bez ekranu telefonu: Android Auto, powiadomienie w zasięgu albo własna uzbrojona reguła. Usługa `connectedDevice` nie znika przy chwilowym DISCONNECTED w trakcie ponawiania.
  - Po utracie łącza przy własnej uzbrojonej regule telefon próbuje wrócić przez `max(30 s, remaining_ms) + 30 s`; nieudane próby nie przedłużają tego czasu. Potem reguła przestaje być powodem połączenia (`ownerArmedExpired`), usługa znika, a ekran pokazuje regułę jako nieaktualną.
  - Świeży snapshot po powrocie (ekran, Android Auto, pojawienie się sterownika) przywraca regułę jako powód połączenia.

### Odłożone drobne uwagi z przeglądu

Brak tekstu dla `HandshakeFailed`; potwierdzenie rozłączenia dla samochodu bez ostrzeżenia; `api()` zamiast `implementation()` dla `:protocol`/coroutines.

## Moduł `:ble`

Android library `com.agendek.bramav2.ble`; rdzeń (`core`) bez Androida, testowany na JVM z `SimulatedRadio` + `SimulatedGate` (testy). Projekt: `doc/ble_design.md`, plan: `doc/plans/2026-10-27-ble.md`.

| Plik | Zakres |
|---|---|
| `core/BleRadio.kt` | Port radia (`RadioHandle`, `RadioEvent`) |
| `core/GattProfile.kt` | UUID (spec 8.2), `GateInfo` (INFO 20 B) |
| `core/GattQueue.kt` | Jedna operacja GATT naraz, timeouty (discover 10 s, read/CCCD/MTU/write 5 s, bond 30 s) |
| `core/FrameChannel.kt` | Fragmentacja/składanie, estymator interwału, budżety `T_response`/`T_attempt` |
| `core/BleGateLink.kt` | Aktor `GateLink`: skan/połączenie, INFO, bond, CCCD, MTU, handshake, sesja, retransmisje, rekey, odtwarzanie na zachowanym łączu, rejestracja |
| `android/AndroidBleRadio.kt` | `BluetoothGatt` (bez testów automatycznych) |

### Decyzje (`:ble`)

- Zatwierdzone w `doc/ble_design.md` §6 (m.in. `send()` poza READY → `CommandUnknown`, GRANT bez DONE → `Disconnected`, bond tylko w `pair()`, odstępy odtwarzania 2 s→60 s i 30 s→5 min).
- Zmiana `:domain`: na łączu po READY `HandshakeFailed` i odmowy UNAVAILABLE/RATE_LIMITED nie zrywają BLE (stan REKEYING, czekanie na `Ready`).
- Plugin `com.android.library` stosowany po id (marker niedostępny offline). Timeout zestawienia łącza radiowego 10 s; nieudany zapamiętany adres → skan. Każda próba odtworzenia na zachowanym łączu ogłaszana `Rekeying`.
- Nieudany/przeterminowany zapis GATT: przed READY → `Disconnected`; na łączu po READY → transmisja przepada (`CommandUnknown` dla komendy) i sesja jest odtwarzana na tym samym łączu, bez zrywania BLE (spec 10.4).
- Po weryfikacji INFO każda porażka kończy próbę (bez pętli skanów przy nieaktualnym bondzie); zapamiętany adres → najwyżej jeden powrót do skanu. Kandydaci skanu bez duplikatów i bez ponownych prób odrzuconych adresów; zestawienie łącza z kandydatem do 5 s; okno skanu czeka na zakończenie weryfikacji. Timeout GATT na każdą operację. Wyjątek w aktorze kończy próbę `Disconnected` zamiast wyciszyć łącze.
- `AndroidBleRadio` łączy przez `BluetoothDevice` z wyniku skanu (typ adresu static random) — do weryfikacji z płytką.
- Druga runda poprawek (przegląd po poprawkach):
  - `GattQueue`: po timeoucie operacja przechodzi w stan „przeterminowana”. Następna operacja startuje dopiero po jej spóźnionym callbacku albo po drugim okresie oczekiwania. Spóźniony callback nie domyka więc kolejnej operacji tego samego typu. `clear()` także czeka na callback trwającej operacji.
  - Timer GATT jest kluczowany licznikiem generacji kolejki. Każdy fragment zapisu ma własny timeout 5 s, więc wolna ramka wielofragmentowa nie zawiesza próby.
  - Estymator interwału liczy czas zapisu od faktycznego startu operacji, bez czasu czekania w kolejce.
  - Ponowienie na zachowanym łączu czyści kolejkę i fragmenty przerwanego żądania.
  - Nieudane discover kończy próbę bez czytania INFO.
  - `T_response` uzbrajany po zapisie tylko, gdy żądanie czeka; nowe żądanie kasuje stary timer. Test mutacyjny „bez sprawdzenia oczekującego żądania” przeżywa jako równoważny: `Sent` przychodzi dopiero po zapisie wszystkich zakolejkowanych ramek, a timer bez żądania nic nie robi. Warunek zostaje jako zabezpieczenie.
  - `AndroidBleRadio`: uchwyt jest rejestrowany przed `connectGatt`, więc wczesne callbacki nie przepadają. `disconnect()` w trakcie `connectGatt` zamyka GATT. Zmiana tylko skompilowana.
  - Drugi `send()` w trakcie komendy → `CommandUnknown`, a nie wyjątek. `send` jest asynchroniczne, więc wyjątek zabiłby próbę. Domena i tak nie wysyła drugiej komendy.
- Trzecia runda poprawek:
  - Operacja wystartowana przez spóźniony callback porzuconej operacji dostaje własny timer GATT. Wcześniej stary timer karencji przerywał ją przedwcześnie i wywoływał zbędny rekey.
  - Zegar `GattQueue` jest parametrem obowiązkowym.
  - `writeLost` nie resetuje transportu podwójnie.
  - Usunięte nieużywane `FrameChannel.expire()`: `Reassembler` wygasza niedokończoną ramkę przy następnym fragmencie, a brak odpowiedzi i tak obsługuje `T_response`.
  - `AndroidBleRadio` (tylko kompilacja):
    - GATT zamknięty także wtedy, gdy `close()` z innego wątku trafi w trakcie `connectGatt`;
    - `onScanFailed` nie kasuje callbacku nowszego skanu.
- Czwarta runda poprawek:
  - Powrót do skanu po porażce zapamiętanego adresu nie odrzuca tego adresu. Adres sterownika jest statyczny (spec 8.3), a porażka mogła być chwilowa.
  - Ponowne `Connected` dla bieżącego uchwytu (zgłaszane przez niektóre stosy, np. po bondzie) jest ignorowane poza fazą łączenia.
  - Timer GATT jest uzgadniany w jednym miejscu, po każdym wejściu aktora, zamiast ręcznie w każdej ścieżce zmieniającej kolejkę.
  - `GattCompletion.startedAt` jest obowiązkowe.
  - `AndroidBleRadio.startScan` zatrzymuje poprzedni skan.
- Piąta runda poprawek:
  - Powrót do skanu wyklucza zapamiętany adres, jeśli odpowiedziało pod nim obce urządzenie (niezgodne INFO). Porażka chwilowa nadal go nie wyklucza.
  - `AndroidBleRadio` nie czyści mapy urządzeń ze skanu, bo trzeba zachować typ adresu zapamiętanego sterownika. Usuwa to regresję z czwartej rundy.
  - Spóźnione wyniki zatrzymanego skanu są ignorowane.
  - Po porażce kandydata w trakcie skanu stan wraca na SCANNING.
  - Timerem GATT zarządza wyłącznie `syncGattTimer`.
  - Karencja po timeoucie (najwyżej 5 s) nie zagraża `T_attempt`: budżet wynosi co najmniej 36 s, a `T_response` jest uzbrajany po zapisie. Przypina to test `unansweredMtuGraceDoesNotEatHandshakeBudget`.
- Szósta runda poprawek:
  - Adres jest odrzucany do końca próby i zapominany jako zapamiętany tylko wtedy, gdy odpowiedziało pod nim obce urządzenie (niezgodne INFO).
  - Chwilowa porażka (status 133, timeout, brak odczytu INFO) pozwala ponowić adres przy jego kolejnym wyniku skanu, na zmianę z innymi kandydatami i w granicach okna 10 s.
  - Powrót do skanu po porażce kandydata emituje `Scanning`.
- Siódma runda poprawek:
  - Adres po chwilowej porażce dostaje najwyżej 2 podejścia w próbie, drugie nie wcześniej niż 1 s po porażce (`MAX_ATTEMPTS_PER_ADDRESS`, `RETRY_PAUSE_MS`). Bez tego status 133 dawał pętlę: test pokazał 40 wywołań `connectGatt` w jednym oknie skanu.
  - Za obce uznawane jest tylko poprawnie odczytane INFO z innym `device_id`. Nieznany format (np. nowa wersja firmware) to porażka chwilowa.
  - Obce INFO z zapamiętanego adresu zapomina go na każdej ścieżce, także w ponownym skanie.
  - Zapamiętany adres zawsze dostaje 10 s na zestawienie łącza.
  - `candidateFailed` po weryfikacji INFO od razu kończy próbę.
  - Decyzja: symulator domyślnie zgłasza urządzenie w skanie raz. Testy ponowień używają powtórzeń (`scanRepeats`), tak jak prawdziwy Android przy `SCAN_MODE_LOW_LATENCY`.
- Ósma runda poprawek:
  - Obcość kandydata ustalana po `device_id` z INFO niezależnie od wersji protokołu (`GateInfo.deviceIdOf`). Sąsiad z nowszym firmware nie zjada 2 podejść. Założenie: początek INFO (wersja + `device_id`) ma stały układ między wersjami.
  - Porażki adresów w jednej mapie (`AddressFailures`), a odrzucenie z niej wyliczane.
  - Klucz `deviceKey` liczony raz na połączenie.
- Odrzucone w czwartym przeglądzie:
  - Nieudany callback zapisu po odpowiedzi sterownika przed READY nadal kończy próbę. Tak przewiduje projekt (porażka przed READY → `Disconnected`, `:domain` ponawia); zachowanie stosów jest tylko spekulacją.
- Odrzucone w piątym przeglądzie:
  - Brak zdarzenia przy ograniczeniu startów skanu przez Androida. Próbę kończy okno skanu 10 s, a `:domain` ponawia.
  - `T_attempt` biegnie między GRANT a `commitPairing()`. To decyzja z §4.4; `:domain` zatwierdza automatycznie po zapisie klucza.
- Odrzucone w szóstym przeglądzie:
  - Wyłączony Bluetooth w trakcie skanu daje `Disconnected` dopiero po oknie skanu. `:domain` i tak nie łączy przy BLUETOOTH_OFF.
  - Mapa urządzeń ze skanu nie jest ograniczana. Filtr po UUID bramy trzyma ją małą.
- Odrzucone w siódmym przeglądzie:
  - Podwójne `Connecting` przy przejściu do kolejnego kandydata. To kosmetyka: `:domain` mapuje je na ten sam stan.
  - Wspólna funkcja powrotu do skanu i jeden enum zamiast flag skanu. Refaktoryzacja bez zmiany zachowania.
- Odrzucone w ósmym przeglądzie:
  - Wynik skanu w przerwie 1 s jest wyrzucany, nie odkładany: Android powtarza wyniki.
  - Do ~20 s na zapamiętanym adresie przy bramie poza zasięgiem.
  - Kandydat zestawia łącze, ale nie odpowiada na wykrywanie usług: sąsiednia brama ma ten sam firmware.

### Odłożone drobne uwagi (`:ble`)

`T_response` nie zamrażany per operacja; timeout bondu 30 s może być krótki; brak testu „disconnect w trakcie operacji GATT”. `AndroidBleRadio.close()` jest końcem życia obiektu; `:app` go nie woła, bo radio żyje tyle co proces aplikacji (rozstrzygnięte przy podłączaniu `:app`).
- `connectGatt(Context, …)` przestarzałe w API 37, ale jedyne dostępne dla minSdk 34.
- Do weryfikacji na sprzęcie (Etap 0): czy zatrzymywać skan na czas łączenia z kandydatem. Na części telefonów skan w trakcie `connectGatt` sprzyja statusowi 133, ale każde wznowienie skanu liczy się do limitu 5 startów na 30 s.

## Specyfikacja 1.4 (27.10.2026)

Decyzje właściciela projektu, opisane w specyfikacji („Zmiany w wersji 1.4”, 4.3, 5.4, 11.4):
- **Bez potwierdzenia CLOSE:** na telefonie, w powiadomieniu i w Android Auto.
- **Powiadomienie „brama w zasięgu” (4.3):**
  - wykrywanie przez CompanionDeviceManager;
  - połączenie od razu po pojawieniu się sterownika;
  - przyciski OTWÓRZ/ZAMKNIJ tylko w READY, działające na zablokowanym ekranie;
  - po 5 min w zasięgu (licząc od pojawienia się lub ostatniego dotknięcia) wyciszenie i rozłączenie, także przy uzbrojonej regule własnej;
  - powrót powiadomienia dopiero po zniknięciu i ponownym pojawieniu się sterownika;
  - wyłączane w ustawieniach.
- **Powiadomienie w samochodzie (5.4):** przy podjeździe w czasie Android Auto przycisk OTWÓRZ (ustawienie autozamykania z telefonu); znika po potwierdzonym otwarciu, po odjechaniu, po końcu projekcji i po odrzuceniu.
- **Rejestracja PIN-em (11.4):** 6 cyfr, EC-JPAKE P-256 zgodne z mbedTLS, typy `26`–`29`, `K_pin` zamiast `K_setup` na tym samym łączu, `K_pin` jednorazowy. Po 5 nieudanych potwierdzeniach PIN-u okno zamyka się od razu. QR zawiera `k` i obowiązkowe `p`; INFO `capabilities = 0x3F` (bit 5).

Moje decyzje w zmianie specyfikacji (zaakceptowane razem ze specyfikacją 1.4):
- przyciski ukryte przed READY i w trakcie komendy;
- licznik 5 min startuje od nowa po każdym dotknięciu;
- funkcja domyślnie włączona;
- globalny limit 5 nieudanych PIN-ów na okno, odporny na zmianę adresu telefonu;
- PIN w QR i czytelnie na etykiecie;
- nowy tekst „Nie udało się zarejestrować — sprawdź PIN i czy okno rejestracji jest otwarte”;
- testy A67–A72 (PIN, powiadomienie), A73 (łącze w tle i wygaśnięcie reguły, dopisane przy przeglądach `:app`), A74–A75 (powiadomienie w samochodzie).

Wpływ na gotowe moduły:
- **`:protocol`:**
  - parser QR wymaga `p`;
  - nowe typy PAKE i EC-JPAKE (potrzebna arytmetyka P-256, np. BouncyCastle, zgodna z formatem mbedTLS);
  - `K_pin` w PRK.
- **`:ble`:**
  - faza PAKE przed PAIR_BEGIN;
  - rejestracja bez znanego `device_id` (kandydaci z bitem 5, `device_id` dopiero z PAIR_CHALLENGE).
- **`:domain`:**
  - wejście rejestracji „PIN” obok QR;
  - nowy powód połączenia „powiadomienie w zasięgu” z wyciszeniem po 5 min;
  - CLOSE bez potwierdzenia.

## Specyfikacja 1.5 (28.10.2026) — aktualizacja firmware (OTA)

Decyzje właściciela projektu (spec 15.5, „Zmiany w wersji 1.5”):
- sparowany telefon (READY) aktualizuje firmware przez BLE, bez fizycznego potwierdzenia na sterowniku;
- plik wybierany przez użytkownika z pamięci telefonu (bez serwera i Wi-Fi);
- flash 4 MB, podpis obrazu ECDSA P-256.

Moje decyzje (zaakceptowane przed zapisem do specyfikacji):
- typy `50` OTA_BEGIN, `51` OTA_DATA (do 470 B danych), `52` OTA_END, `53` OTA_ABORT, `81` OTA_ACK z `next_offset`; kawałki po jednym, potwierdzane, wspólna kolejka z komendami;
- MTU ≥ 247; jedna aktualizacja naraz; bez wznawiania; porzucenie po utracie sesji, resecie autoryzacji lub 30 s bez danych;
- reguła autozamykania musi być DISARMED przy BEGIN i END (rollback do starszego firmware mógłby unieważnić kopię RTC);
- weryfikacja podpisu bez Secure Boot i bez eFuse w buildzie deweloperskim; `secure_version` z emulacją eFuse; downgrade odrzucany przed zapisem;
- rollback ESP-IDF z autotestem do 30 s; migracja NVS dopiero po potwierdzeniu obrazu;
- zapis flash w osobnym zadaniu, timer impulsu w IRAM; zero zapisów NVS;
- tabela partycji bez `factory`: dwa sloty po `0x1E0000`;
- obowiązkowa usługa Device Information z wersją, bit 6 `capabilities` (`0x7F`);
- 5-minutowy limit powiadomienia wstrzymany w trakcie OTA; brak OTA w Android Auto;
- teksty rozdziału 3 i testy A76–A81.

Wpływ:
- **`:protocol`:** nowe typy i OTA_ACK, `capabilities = 0x7F`, parser nagłówka obrazu ESP (`esp_app_desc_t`).
- **`:ble`:** odczyt Device Information, sterowanie transferem OTA w kolejce sesji.
- **`:domain` / `:app`:** ekran aktualizacji w ustawieniach, wybór pliku (SAF), postęp, wstrzymanie limitu 5 min, wykrycie powrotu wersji.
- **Firmware:** uzupełnione `doc/firmware_design.md` (decyzje 11–13: komponent `brama_ota` za portem, klucz deweloperski w `~/.config/brama/`, anti-rollback tylko z emulacją eFuse) i plan (nowe Zadanie 8A, OTA w zadaniach 1, 4, 10–13, punkt uwagi 6).

## OTA na Androidzie (plan `doc/plans/2026-10-28-ota.md`, projekt `doc/ota_design.md`)

Transfer prowadzi `:domain` kawałek po kawałku (470 B) przez `GateLink.send`, w tej samej kolejce co komendy i PING. `:ble` daje mechanikę (retransmisje, `T_response`) i odczyt Device Information.

| Moduł | Zmiany | Weryfikacja |
|---|---|---|
| `:protocol` | `SessionRequest.OtaBegin/OtaData/OtaEnd/OtaAbort`, `OTA_ACK` w `ClientSession` (`SessionEvent.OtaAcknowledged`); `FirmwareImages.parse` — magic, układ, `esp_app_desc_t`, `project_name`, segmenty, blok podpisu 68 B, wersja, `secure_version`, SHA-256 | 19 nowych testów; obraz z builda w `protocol/src/test/resources/firmware/brama.bin` |
| `:ble` | odczyt `0x2A26` po INFO (przed bondem, tylko dla sesji), `GateInfo.supportsOta` (bit 6), `LinkEvent.Ready(snapshot, LinkInfo)`, `LinkEvent.OtaResult`; symulator OTA zgodny z `brama_ota` i restart z nową wersją | 13 nowych testów (JVM, rdzeń) |
| `:domain` | `FirmwareUpdate` (Confirm, Transferring, EndRejected, Rebooting, Finished), intencje `selectFirmware`/`confirmFirmwareUpdate`/`retryFirmwareEnd`/`cancelFirmwareUpdate`/`dismissFirmwareUpdate`; komenda i PING między kawałkami; utrata sesji przed END → przerwana, po END → Rebooting; 90 s na READY i porównanie wersji; powód łącza `updating` (także bez ekranu); licznik 5 min wstrzymany; „Rozłącz” z potwierdzeniem; 15 tekstów rozdziału 3 | 36 nowych testów; 7 kluczowych reguł sprawdzonych mutacyjnie |
| `:app` | ustawienia (wersja, „Aktualizuj firmware”), wybór pliku przez SAF z limitem rozmiaru, ekran `FirmwareUpdateScreen`, wiersz postępu w powiadomieniu usługi | mapery: 7 nowych testów; Compose — skompilowane, do sprawdzenia na telefonie |

Decyzje (zatwierdzone z projektem, `ota_design.md` §8):
- tekst ostrzeżenia „Rozłącz” w trakcie transferu dopisany do rozdziału 3 (decyzja właściciela);
- cały obraz w pamięci;
- telefon sprawdza tylko MTU, regułę sprawdza sterownik;
- „Rozłącz” przerywa transfer samym rozłączeniem, „Anuluj” wysyła OTA_ABORT;
- X = Y pokazywane jako sukces;
- brak wersji po restarcie — aktualizacja znika bez komunikatu;
- wynik także w `notification`;
- błąd odczytu pliku = „Wybrany plik nie jest firmware tego sterownika”.

Decyzje wykonawcze:
- `LinkEvent.Ready.info` ma wartość domyślną `LinkInfo.UNKNOWN` (istniejące testy `:domain` bez zmian);
- po odrzuceniu END (`RULE_ACTIVE`/`BUSY`) utrata sesji to przerwanie, nie Rebooting;
- anulowanie po wysłaniu END jest ignorowane;
- wynik OTA dla innego żądania niż wysłane (błąd `:ble`) → przerwana i nowa sesja.

Przegląd końcowy (świeży recenzent): bez błędów krytycznych; zgodność bajtów z `brama_ota`, parser obrazu i kolejka potwierdzone. Poprawione (TDD):
- OTA_ABORT po anulowaniu czeka, aż sesja nie ma innego żądania (komenda, PING) — wcześniej łamał 13.3 i dawał `CommandUnknown`;
- nowa aktualizacja startuje dopiero po rozstrzygnięciu OTA_ABORT poprzedniej (spóźniony OTA_ACK nie trafia do nowego transferu);
- po OTA_OK na END komenda z kolejki jest wycofywana, a przyciski nieaktywne — sterownik nie wykonuje już komend;
- „Rozłącz” po wysłanym END bez ostrzeżenia „firmware pozostanie bez zmian”;
- wersja z obrazu bez białych znaków (jak z Device Information).

Decyzja właściciela po przeglądzie: odrzucony END (`OTA_RULE_ACTIVE`) odnawia w firmware 30-sekundowy limit bezczynności (`brama_ota_touch`, spec 15.5 pkt 4), więc „Ponów” działa także po dłuższym rozbrajaniu reguły (A77). Zmiana firmware z 2 testami hostowymi (`rejected_end_refreshes_idle_timer`, `foreign_end_does_not_refresh_idle_timer`) — testy hostowe i build `esp32` zielone na Ubuntu.

Odłożone: symulator OTA w `:ble` uproszczony względem `brama_ota` (BUSY, nagłówek, DOWNGRADE, `next_offset` po END) — telefon tych pól nie sprawdza.

**Do sprawdzenia z płytką:** A76–A81 po stronie telefonu, czas transferu (szacunek 4–6 min dla 655 KB), odmowa UNAVAILABLE w trakcie autotestu nowego obrazu a limit 90 s.

## PIN, powiadomienie i CLOSE bez potwierdzenia (plan `doc/plans/2026-10-27-pin-presence.md`)

Projekt: `doc/pin_presence_design.md`. Wykonane zadania 1–9 (natywnie, TDD, testy mutacyjne kluczowych reguł):
- **`:protocol`:**
  - QR z obowiązkowym `p` i `PairingSecret` (QR | PIN);
  - typy PAKE 0x26–0x29 (bez tagu, `seq = 0`);
  - `EcJpake` (P-256, format mbedTLS, BouncyCastle 1.79 tylko do arytmetyki krzywej);
  - `PinPakeHandshake` i `K_pin`.
- **Zgodność EC-JPAKE z mbedTLS 3.6.2 z ESP-IDF 5.4:**
  - wektory self-testu dają ten sam `pms` w obu rolach;
  - program `harness.c` (mbedTLS jako serwer) przyjął rundy Kotlina i wyliczył ten sam sekret;
  - zasoby i instrukcja generacji w `protocol/src/test/resources/ecjpake/`.
- **`:ble`:**
  - `pair(PairingSecret)`;
  - ścieżka PIN (kandydaci z bitem 5, PAKE przed PAIR, adres zapamiętany po PAIR_CHALLENGE);
  - `GrantReceived` z `device_id`.
- **`:domain`:**
  - CLOSE bez potwierdzenia;
  - `startPinRegistration`, komunikat `PIN_REGISTRATION_FAILED`;
  - automat powiadomienia HIDDEN/SHOWN/QUIET z licznikiem 5 min, wstrzymaniem reguły i portem `PresenceStore`.

Decyzje wykonawcze (rulingi):
- wektory jako pliki `klucz=hex` zamiast JSON;
- `pinSetupKey` w `HandshakeFrames`;
- zły PIN → `HandshakeRejected(AUTH_FAILED)` (sterownik odmawia), a fałszywy sterownik → `HandshakeFailed`;
- `PresenceStore` przechowuje `PresenceRecord(quiet, ruleSuppressed)`;
- urządzenie bez bitu 5 przy PIN-ie odrzucane do końca próby;
- lista zamknięta komunikatów: −„Zamknąć bramę?”, +komunikat rejestracji PIN-em (44 pozycje).

Przegląd końcowy (świeży recenzent): bez błędów krytycznych; zgodność EC-JPAKE z mbedTLS potwierdzona. Poprawione (TDD):
- „Usuń z telefonu” resetuje automat powiadomienia (wyciszenie, wstrzymanie reguły, licznik, zapis);
- pojawienie się sterownika pomija krótki odstęp ponowienia (backoff 12.4 zostaje).

Odłożone drobne uwagi:
- EC-JPAKE przyjmuje punkt w kodowaniu hybrydowym (mbedTLS odrzuca; bez skutku dla bezpieczeństwa);
- `T_attempt` telefonu liczony od nowa po PAKE;
- pierwszy kandydat z bitem 5 przejmuje próbę PIN-em;
- PIN w pamięci do końca połączenia parowania;
- stan SHOWN nieutrwalany;
- błąd `PresenceStore` nie wstrzymuje reguły;
- PIN jako `String` w API `:domain`.

## Moduł `:app` (plan `doc/plans/2026-10-27-app.md`, projekt `doc/app_design.md`)

| Obszar | Pliki | Weryfikacja |
|---|---|---|
| Teksty | listy zamkniętej (spec 3) — `Texts` w `:domain` (wspólne z `:car`); etykiety i instrukcje poza listą — `ui/Labels.kt` | JVM (w `:domain`): kompletność, dokładne brzmienie, brak stwierdzeń o położeniu |
| Magazyny | `store/` — `FileCredentialStore` (Keystore AES-GCM, `noBackupFilesDir`, zapis atomowy), `FileHistoryStore` (JSON-lines), `DataStoreSettings` (ustawienia, pamięć reguły, `presence`) | JVM: formaty, Locked, uszkodzone pliki, przycinanie 200 |
| Mapery | `ui/MainModel.kt`, `service/NotificationModel.kt` (`gateNotification`, `serviceNeeded`) | JVM |
| Bramka obecności | `companion/PresenceGate.kt` | JVM (ustawienie, zgoda na powiadomienia) |
| Złożenie, ekrany | `BramaApp.kt` (`AppGraph`), `MainActivity.kt`, `ui/*Screen.kt`, `ui/BramaNav.kt` | kompilacja |
| Usługa, powiadomienia | `service/GateConnectionService.kt`, `ServiceController.kt`, `NotificationActionReceiver.kt`, `Channels.kt` | kompilacja |
| CDM | `companion/CompanionAssociation.kt`, `GateCompanionService.kt` | kompilacja |
| Problemy systemowe | `system/SystemStatus.kt` | kompilacja |

Zmiany w innych modułach:
- **`:domain`:** `SettingsStore` (nazwa, przełącznik powiadomienia), `HistoryStore.entries`, intencje `setGateName` i `setPresenceNotificationEnabled`, przepływ `history`.
- **`:ble`:** `BleGateLink.knownAddress` i `rememberAddress`, `AndroidBleRadio.registerDevice`.

Nowe zależności: Navigation Compose 2.10.2, lifecycle-*-compose 2.11.0, DataStore 1.3.0, play-services-code-scanner 16.1.0, material-icons-core 1.7.8. Lint bez błędów; ostrzeżenia dotyczą szablonu i nowszych wersji bibliotek.

Decyzje wykonawcze (rulingi):
- `FakeHistoryStore.entries` → `appended`;
- parametr `history` → `historyStore`;
- test położenia dopuszcza spec-owe ostrzeżenie „Brama otwarta przed resetem pozostanie otwarta”;
- `PresenceStore` jako pole `DataStoreSettings.presence`;
- `GateNotification` bez pola kanału;
- jeden `GateViewModel` (AndroidViewModel);
- zadania 6 i 7 sprawdzone jednym buildem;
- `AppGraph` obserwuje przełącznik powiadomienia;
- `CompanionAssociation` czyta `device_id` z magazynu poświadczenia.

**Lista kontrolna testów ręcznych z płytką (Etap 0):**
1. Pierwsze uruchomienie: prośby o uprawnienia Bluetooth i powiadomienia; odmowa → „Brak uprawnień Bluetooth…”; wyłączony Bluetooth → komunikat (A27).
2. Dodanie bramy QR (skaner Google) i PIN-em; systemowy dialog parowania; po „Telefon zarejestrowany…” powiązanie urządzenia (dialog CDM z jednym urządzeniem).
3. OTWÓRZ/ZAMKNIJ z ekranu; wyniki z listy; historia.
4. Powiadomienie w zasięgu (A70–A72):
   - przyciski na zablokowanym ekranie;
   - wyciszenie po 5 min, także z autozamykaniem (brama zamyka się po ok. 30 s);
   - powrót po zniknięciu i ponownym pojawieniu się sterownika;
   - wyłączenie w ustawieniach;
   - cofnięcie zgody na powiadomienia.
5. OPEN(true) z ekranu, zamknięcie aplikacji: usługa trzyma łącze, odejście poza zasięg → zamknięcie przez sterownik.
6. Restart telefonu: przed pierwszym odblokowaniem „Poświadczenie niedostępne…”; po odblokowaniu łączenie bez skanu (adres z powiązania).
7. Backup (A58): `adb backup`/transfer nie zawiera danych aplikacji; brak sekretów w logcat.
8. Uruchomienie usługi z tła przy obecności (wyjątki CDM, API 34–36).

Przegląd końcowy `:app` (świeży recenzent). Poprawione:
- **Krytyczne — wyścig start/stop usługi:** awaria „startForegroundService() did not then call startForeground()” przy zwykłej komendzie z ekranu. Usługa zatrzymuje się teraz sama po `startForeground`, kontroler tylko ją uruchamia (po sprawdzeniu uprawnień Bluetooth), a komenda w toku wymaga usługi tylko poza ekranem.
- **Podpięcie UI według procesu** (`ProcessLifecycleOwner`): obrót ekranu i skaner QR nie zrywają łącza.
- **Powiadomienie pokazuje problem** systemowy i odmowę sterownika (spec 4.3 pkt 2).
- **Uszkodzony klucz lub szyfrogram** → brak poświadczenia (plik zostaje), bez fałszywego „niedostępne do odblokowania”. Brak „Dodaj bramę” przy zablokowanym poświadczeniu.
- **Zgoda na powiadomienia** obejmuje też wyłączone powiadomienia aplikacji i zablokowany kanał; sprawdzana przy każdej aktualizacji powiadomienia i po nadaniu zgody.
- **Deduplikacja** aktualizacji powiadomienia.
- **Ostrzeżenie** (np. o zaniku zasilania) zdejmowane dopiero po faktycznym wysłaniu powiadomienia.
- **Wyjątek przy powiązaniu urządzenia** daje komunikat zamiast awarii.
- **Skaner QR** pobierany z instalacją aplikacji.

Odłożone drobne uwagi:
- odczyt ustawienia powiadomienia przed załadowaniem DataStore przy zimnym starcie z CDM;
- zapis atomowy bez `fsync`;
- przypadki brzegowe historii (urwana linia, odczyt na wątku głównym);
- DataStore bez obsługi uszkodzonego pliku;
- PIN w `rememberSaveable`;
- etykiety postępu i błędu QR spoza listy zamkniętej;
- powtarzana prośba o uprawnienia.

Do sprawdzenia na urządzeniu (recenzent nie rozstrzygnął):
- zachowanie przestarzałych callbacków obecności i `startObservingDevicePresence(String)` na API 36;
- ponowne zgłaszanie obecności po zabiciu procesu;
- filtr dokładnego adresu przy adresie prywatnym sterownika.

## Moduł `:car` (plan `doc/plans/2026-10-27-car.md`, projekt `doc/car_design.md`)

Specyfikacja: nowy punkt 5.4 „Powiadomienie w samochodzie”, testy A74–A75; poprawione „CLOSE z potwierdzeniem” w Etapie 4.

**Stan obecny (po sześciu rundach przeglądu; historia rund niżej):**
- **Ekran (`GateScreen`, `carPane`):**
  - `PaneTemplate`: tytuł z nazwą bramy, wiersze z `GateUiState.carMessages` (kolejność 5.2) przez `Texts.car`;
  - przycinanie do limitu hosta: przy limicie 1 połączenie, położenie i reguła w jednym wierszu; przy 2 — połączenie z położeniem, potem reguła;
  - bez konfiguracji tylko „Skonfiguruj aplikację na telefonie po zatrzymaniu”, zawsze w osobnym wierszu i bez przycisków;
  - problem dotyczący tylko telefonu (aparat) pomijany;
  - OTWÓRZ → `open()`, ZAMKNIJ → `requestClose(CAR)` bez potwierdzenia;
  - `uiAttached/uiDetached(CAR)` według cyklu życia ekranu.
- **Powiadomienie (`CarNotificationPolicy`, `CarPresenceNotifier`):**
  - widoczne w stanie SHOWN (4.3) tylko w czasie projekcji;
  - treść z `UiMessages.carNotification`: połączenie, położenie, problem, odmowa, wynik z bieżącego pobytu;
  - OTWÓRZ tylko przy `notificationActionsAvailable` i bez „Skonfiguruj…”, działa jak OTWÓRZ z 4.3;
  - alarm (heads-up) przy pierwszym pokazaniu i przy pierwszym pojawieniu się przycisku w pobycie;
  - znika po potwierdzonym OPEN z dowolnego źródła w pobycie, po wyjściu z SHOWN, po końcu projekcji i po odrzuceniu (w aucie lub na telefonie) do końca pobytu;
  - numer pobytu losowy na proces i w URI intentu „odrzucono”;
  - pozostałość po poprzednim procesie i nieudane usunięcie są usuwane przy kolejnych zmianach stanu;
  - kanał telefonu bez heads-up, ważność w aucie z `CarAppExtender`.

| Obszar | Pliki | Weryfikacja |
|---|---|---|
| Model ekranu (5.2) | `CarPane.kt` — kolejność wierszy, przycinanie do limitu hosta, łączenie (1)+(2) przy limicie 1, brak akcji przy CONFIGURE_ON_PHONE | JVM |
| Powiadomienie w samochodzie (5.4) | `CarNotificationPolicy.kt` — cykl pobytu przy bramie, zniknięcie po potwierdzonym OPEN (dowolne źródło), odrzucenie przez kierowcę, projekcja | JVM (12 testów, mutacje) |
| Usługa i ekran | `GateCarAppService.kt` (HostValidator: debug — dowolny host, release — lista biblioteki), `GateScreen.kt` (`PaneTemplate`, OTWÓRZ/ZAMKNIJ, `uiAttached/Detached(CAR)`) | kompilacja |
| Wysyłka powiadomienia | `CarPresenceNotifier.kt` (`CarAppExtender`, `CarNotificationManager`, `CarConnection`), `CarNotificationActionReceiver` | kompilacja |
| Teksty | `Texts` przeniesione do `:domain`, `Texts.car` („Położenie nieznane”) | JVM |

Decyzje wykonawcze (rulingi):
- `PaneTemplate.setTitle/setHeaderAction` zamiast `Header` (Car API 7; `minCarApiLevel` = 1);
- `Action.setEnabled` tylko przy Car API ≥ 5 — na starszych hostach przyciski są zawsze aktywne, a domena odrzuca komendę bez sesji READY;
- jawna zależność `core-ktx` w `:car`;
- `CarDependencies` udostępnia też `carNotifier` (odbiornik akcji „odrzucono”).

**Lista kontrolna DHU / auta (Etap 0):**
1. Aplikacja widoczna w Android Auto (kategoria IOT); `minCarApiLevel` potwierdzony na hoście; release odrzuca nieznany host.
2. Ekran:
   - wiersze w kolejności 5.2 i przycinanie przy limicie hosta;
   - OTWÓRZ i ZAMKNIJ (bez potwierdzenia) z wynikiem;
   - bez konfiguracji „Skonfiguruj aplikację na telefonie po zatrzymaniu”;
   - wyjście z ekranu nie rozłącza, gdy działa reguła.
3. Powiadomienie (A74–A75):
   - pojawia się przy podjeździe w czasie projekcji;
   - OTWÓRZ z ustawieniem z telefonu;
   - znika po potwierdzeniu, po otwarciu z telefonu, po odjechaniu, po końcu projekcji i po odrzuceniu;
   - zostaje przy BUSY/wyniku nieznanym;
   - chwilowa utrata łącza — bez przycisku.
4. Koniec projekcji nie zmienia łącza ze sterownikiem (5.2).

Przegląd końcowy `:car` (świeży recenzent): bez błędów krytycznych. Poprawione (TDD):
- przy limicie 2 wierszy reguła nie znika (połączenie i położenie w jednym wierszu, reguła w drugim);
- pojawienie się przycisku OTWÓRZ alarmuje kierowcę, a nie tylko cicho aktualizuje powiadomienie.

Przegląd po poprawkach — poprawione:
- usunięcie kopii powiadomienia na telefonie też je kończy w tym pobycie (nie wraca przy zmianie stanu);
- spóźnione „odrzucono” z poprzedniego pobytu jest ignorowane (numer pobytu);
- przy limicie 2 wierszy bez konfiguracji teksty nie są sklejane;
- powiadomienie nie jest wysyłane ponownie przy samej zmianie znacznika alarmu;
- ekran czyta `carMessages` z repozytorium (jedno źródło).

Odrzucone:
- utrata alarmu przy nieudanym wysłaniu (bez zgody na powiadomienia nie ma SHOWN);
- osobna treść powiadomienia (celowo krótsza, 5.4);
- etykieta i kanał poza `:app` (`:car` nie zależy od `:app`);
- zbędne pola modelu ekranu.

Trzeci przegląd — poprawione:
- „Skonfiguruj aplikację…” zawsze w osobnym wierszu, także przy limicie 1;
- osobny `PendingIntent` „odrzucono” na każdy pobyt;
- powiadomienie w aucie pokazuje problem systemowy i odmowę sterownika (jak 4.3);
- kanał telefonu bez heads-up (ważność w aucie z `CarAppExtender`) — do potwierdzenia na DHU;
- nieudane wysłanie jest ponawiane;
- porównanie treści przez `copy(alert = false)`;
- licznik odrzuceń tylko przy faktycznym zamknięciu pobytu.

Odrzucone: wykrywanie wyniku z poprzedniego pobytu po tożsamości obiektu — repozytorium nie kopiuje `LastCommand`.

Czwarty przegląd — poprawione:
- treść powiadomienia w aucie z `UiMessages.carNotification` (reguły 5.2). Przy problemie konfiguracji tylko „Skonfiguruj aplikację na telefonie po zatrzymaniu”; tekst o aparacie, przeznaczony wyłącznie dla telefonu, pomijany; połączenie przed problemem. Usuwa regresję z trzeciej rundy;
- usunięcie pozostałego powiadomienia przy starcie procesu;
- zabezpieczone usuwanie powiadomienia;
- `DEFAULT_GATE_NAME` jedna stała w `:domain`.

Odrzucone: ponawianie przy statycznym stanie i odebranej zgodzie, kolizja `PendingIntent` po 1000 pobytach, kosmetyka.

Piąty przegląd — poprawione:
- numery pobytów startują od losowej wartości w każdym procesie, więc „odrzucono” ze starego procesu nie ukrywa nowego pobytu;
- nieudane usunięcie powiadomienia (także pozostałości po poprzednim procesie) jest ponawiane przy kolejnych zmianach stanu;
- wspólna reguła „Skonfiguruj…” dla ekranu i powiadomienia w samochodzie;
- jeden KDoc;
- menedżer powiadomień tworzony raz.

Odrzucone:
- wynik OPEN wysłanego tuż przed podjazdem chowa powiadomienie — brama jest otwarta, przycisk zbędny;
- teksty „włącz Bluetooth” i „otwórz aplikację na telefonie” w aucie — lista zamknięta (spec 3), tak samo na ekranie 5.2.

Szósty przegląd — poprawione:
- numer pobytu w URI intentu „odrzucono” — osobny `PendingIntent` na pobyt bez kolizji;
- brak przycisku OTWÓRZ w powiadomieniu przy „Skonfiguruj…” (jak na ekranie 5.2);
- ekran samochodowy pomija problem dotyczący tylko telefonu (uprawnienie do aparatu).

Odłożone z szóstego przeglądu:
- pominięcie potwierdzenia OPEN przez scalenie stanów;
- alarm oznaczany przed udanym wysłaniem;
- podobne listy komunikatów ekranu i powiadomienia.

Odłożone drobne uwagi, część do sprawdzenia na DHU:
- bardzo szybkie zniknięcie i pojawienie się bramy może przenieść „zamknięte” do nowego pobytu;
- usunięcie powiadomienia przez host (np. po dotknięciu akcji) też je ukrywa — do sprawdzenia na DHU;
- długi połączony wiersz przy limicie 1;
- brak otwierania ekranu z treści powiadomienia;
- heads-up także na telefonie;
- zbieranie stanu przez cały cykl życia ekranu;
- `UiSource.CAR` przy nagłej śmierci hosta.

## Firmware ESP32 (plan `doc/plans/2026-10-27-firmware.md`, projekt `doc/firmware_design.md`)

Stan obecny: kompletny firmware według specyfikacji 1.5 w `firmware/` (opis komponentów, budowania i trybu serwisowego: `firmware/README.md`). Logika protokołu, kryptografii, sesji, autoryzacji, reguły, aktora, OTA, przycisku i decyzji startu ma 144 testy hostowe; warstwa sprzętowa (`brama_hw`), NimBLE (`brama_ble`) i `main` są tylko kompilowane.

- **Narzędzia:** ESP-IDF v6.0.1 przez `firmware/tools/idf.sh`; testy na komputerze wymagają `libbsd-dev`.
- **Wektory:** w `:protocol` doszły typy OTA (`OtaPayloads`, `OtaAck`, `OtaResult`; 7 testów) i generator scenariuszy `VectorScenarios` → `protocol/src/test/resources/vectors/` (SESSION, PAIR QR, PIN, komendy, OTA, fragmenty, odmowy). Firmware odtwarza je bajt w bajt jako serwer.
- **EC-JPAKE na PSA:** serwer zgodny formatem z mbedTLS; test z klientem `mbedtls_ecjpake` (ten sam `S`), odczyt rundy 1 klienta Kotlina, `K_pin` z wektora.
- **Podpis i OTA:** obraz podpisany ECDSA P-256 kluczem deweloperskim z `~/.config/brama/` (dowiązanie `firmware/keys/`); weryfikacja przy aktualizacji bez Secure Boot; anti-rollback tylko z emulacją eFuse (`CONFIG_EFUSE_VIRTUAL=y`). Tablica partycji pod `0xA000` (bootloader ~0x7E00 B), spec 15.5 zaktualizowana.
- **Decyzje wykonawcze** (ledger planu): bond w zwartym formacie 96 B; jeden rekursywny mutex aktora i BLE, więc zapis flash OTA może opóźnić komendę o kilkadziesiąt ms (koniec impulsu w ISR/IRAM — bez wpływu); limit 5/60 s liczony per peer; RULE_STATUS bez duplikatu do wnioskodawcy komendy.
- **Końcowy przegląd** (recenzent niezależny): poprawione 2 krytyczne i 9 istotnych uwag — m.in. bond nowego telefonu nie był zapisywany (klucze zapisywane przez NimBLE przed zdarzeniem szyfrowania), pętla hosta przy ponownym parowaniu, unieważnienie sesji/rekey nieprzekazywane aktorowi, komendy po OTA_END, termin z RTC bez górnej granicy, reset bez przerwania impulsu, sesja niezwiązana z bondem łącza, TICK zależny od BLE. Poprawki BLE/FreeRTOS sprawdzone tylko buildem. Pełna lista decyzji i odłożonych drobnych uwag: `doc/plans/2026-10-27-firmware.ledger.md`.
- **Naprawa bondu (9.5)** — zaimplementowana 28.10.2026, sekcja niżej.
- **Do sprawdzenia z płytką:** lista w `firmware/README.md` (RAM/stosy przy 9 łączach, accept/resolving list z RPA, impulsy podczas zapisu flash — A80, RTC przy restartach, OTA end-to-end i rollback, kodowanie eFuse).

### Pierwsze uruchomienie na płytce (12.11.2026)

Pierwszy test na sprzęcie (ESP32, telefon z Androidem, ColorOS). Stan końcowy potwierdzony przez użytkownika: rejestracja z QR, sesja READY, OPEN/CLOSE, ponowne połączenie także po zamknięciu okna parowania. Zmiany zbudowane (firmware `idf.sh -B`, testy hostowe 169/169, `:ble` kompiluje się i przechodzi testy).

**Konsola, konfiguracja, sprzęt**
- **Stos `console_repl`:** `do_provision` trzyma na stosie dwa `brama_image_t` i `brama_factory_t`; `start_repl()` ustawia `task_stack_size = 8192` (`service_console.c`). Zapas stosu nie był mierzony.
- **Konsola zawsze pełna:** PAIR_BUTTON jest na IO0 (przy resecie = tryb download), więc wejście przyciskiem było nieosiągalne. `brama_service_console_run(nvs, banner)` działa także po starcie BLE i aktora; usunięto `brama_service_console_readonly`. Komendy piszące do NVS działają poza aktorem, bez blokady; po nich obowiązuje `reboot`. Banery: „Konsola serwisowa (aplikacja dziala)”, „Tryb provisioningu / serwisowy (aplikacja nie dziala)”. Po `provision` w trybie provisioningu BLE rusza dopiero po `reboot`. Spec 15.4, 15.2.1, A59, decyzja 12 zaktualizowane.
- **`info` wypisuje sekrety** (decyzja właściciela): `device_id`, QR (z `K_setup`) i PIN. UART w wersji produkcyjnej musi być wyłączony lub chroniony (15.3).
- **Piny prototypu** (`sdkconfig.defaults`, spec 2.2): OPEN 17, CLOSE 18, PAIR_BUTTON 0 (IO0, aktywny niskim), STATUS_LED 19 (aktywna niskim, `CONFIG_BRAMA_LED_ACTIVE_LOW`, dioda gaszona od inicjalizacji), INTERLOCK 5. IO0 i IO5 to piny rozruchowe ESP32. Okno parowania otwiera zwolnienie przycisku po 3–10 s.
- **DRAM statyczny jest prawie pełny** (zapas < 1 kB): statyczny bufor 1,8 kB przepełnił link; rozważyć odchudzenie `g_links` (`brama_link_ctx_t` ma bufory TX/odpowiedzi na 9 łączy).

**Parowanie i sesja — znalezione przyczyny (w kolejności)**
1. `tick_cb` rozłączał po 2 s łącze niezaszyfrowane także przy otwartym oknie (telefon czekał na systemowe pytanie o parowanie). Teraz 2 s dotyczy łącza zaszyfrowanego albo przy zamkniętym oknie; niezaszyfrowane w oknie ma `T_attempt`.
2. Aplikacja: `ACTION_BOND_STATE_CHANGED` nie docierał do odbiornika `RECEIVER_NOT_EXPORTED` → `RECEIVER_EXPORTED` + `pollBond` (odpytywanie `bondState` co 500 ms; BONDED z odpytywania uznawane dopiero po 6 s bez broadcastu, bo `bondState` bywa BONDED wcześniej niż broadcast).
3. **`CONFIG_BT_NIMBLE_SM_SC_ONLY=1`** (w `sdkconfig.defaults` jako „tylko LE SC”) wymaga uwierzytelnionego klucza (MITM) dla każdej charakterystyki z `WRITE_ENC`; Just Works daje `authenticated=0`, więc host odpowiadał ATT 0x05 na zapis RX przed `access_cb`. Ustawiono `=0` i jawne `ble_hs_cfg.sm_sc_only = 0`; odrzucenie legacy pairing zapewniają `SM_LEGACY=n` i `SM_SC=y` (spec 9.1).
4. `brama_gcm_seal` z `pt == NULL` dla pustego ładunku (PAIR_DONE): sprzętowy sterownik AES-GCM ESP-IDF odrzuca NULL (`No input supplied`). Podawany jest niezerowy wskaźnik (analogicznie `out` w `brama_gcm_open`).
5. **Start hosta NimBLE podmienia magazyn kluczy** (`CONFIG_BT_NIMBLE_STATIC_TO_DYNAMIC=y`: `ble_hs_startup` → `ble_store_config_init()`), więc nasze callbacki z `brama_ble_start` ginęły, staging bondu było puste, a `PAIR_COMMIT` zapisywał klienta bez bondu. `install_store()` wywoływane także w `on_sync`. `on_pair_commit` odrzuca rejestrację bez bondu (test `pair_commit_without_bond_is_rejected`).
6. `REPEAT_PAIRING`: `RETRY` bez warunku zapętlał NimBLE; teraz licznik `repeat_pairs` (max 3, potem `IGNORE`) i odrzucenie staging łącza.
7. **Połączenia poza oknem:** filtr accept list nie działa na tym kontrolerze (ESP32 nie obsługuje `LE Set Privacy Mode`, 0x204E → Unknown HCI Command; IRK na liście rozwiązywania w trybie sieciowym odrzuca połączenia z adresu tożsamości). Decyzja użytkownika: reklama zawsze bez filtra (`BLE_HCI_ADV_FILT_NONE`), nieznanego peera rozłącza firmware (≤ 2 s poza oknem; w oknie jeden CANDIDATE). Spec 8.3 zaktualizowana; obcy peer może chwilowo zająć slot.
8. **RPA telefonu nie jest rozwiązywany przez kontroler** (telefon łączy się z adresu prywatnego, `id` = `ota`; przyczyna nieustalona, mimo `ble_hs_misc_restore_irks()` w `on_sync`). `store.c` rozwiązuje RPA sam (`ble_hs_pvcy_resolve_with_irk` z IRK z bondów, próba obu kolejności bajtów) w `find_bond` i `brama_ble_resolve_identity` (używane w `on_encrypted` i `REPEAT_PAIRING`), więc LTK jest znajdowany, a `l->peer_id` to adres tożsamości.

**Otwarte ryzyka**
- Jednorazowe **zawieszenie** hosta NimBLE w trakcie zapisu kluczy przy ponownej rejestracji (po `write obj=2` brak dalszych logów BLE, konsola działa, LED przestaje migać — `ui_task` zajmuje tę samą blokadę `s_lock`, więc blokada była trzymana na stałe). **Nie odtworzono** po kolejnym wgraniu (limit 2 s w `brama_ble_lock` i `take_lock` zmienia czasy), więc przyczyna nie jest ustalona; podejrzenie: zakleszczenie `s_lock` z wewnętrzną blokadą hosta NimBLE. Zostawiono zabezpieczenie: `brama_ble_lock` (`ble.c`) i `take_lock` (`app_main.c`) czekają 2 s i logują `blokada … zajeta ponad 2 s: czeka '…', trzyma '…'`, po czym czekają dalej.
- `ble_hs_pvcy_resolve_with_irk`, `ble_hs_misc_restore_irks` i `ble_gap_conn_foreach_handle` (adopcja łączy bez CONNECT, 13.11.2026) to funkcje wewnętrzne NimBLE (mogą zmienić się w innej wersji ESP-IDF); kolejność bajtów IRK sprawdzana w obu wariantach.
- Systemowe pytanie o parowanie pojawia się dwa razy (podejrzenie: żądanie bezpieczeństwa SMP z `ble_gap_security_initiate` w `on_connect` plus `createBond()` aplikacji); nie zbadano.
- Do sprawdzenia: A80, pomiary RAM/stosów przy 9 łączach; OTA: ścieżki negatywne sprawdzone 15.11.2026 (poniżej), poza anti-rollbackiem z `secure_version` 2 (świadomie pominięty) i zrywaniem łącza OTA przy dołączeniu drugiego telefonu (otwarte).
- Sprawdzone (14.11.2026): **OTA end-to-end** (Oppo CPH2791, API 36, obraz testowy `0.1.1` zbudowany w scratchpadzie z `CONFIG_APP_PROJECT_VER="0.1.1"` na działającym `0.1.0`, plik z `/sdcard/Download`): aplikacja pokazała zmianę wersji i przesłała obraz (655 348 B) w ok. 3,5 min (powiadomienie 15:02–15:05, bez zapisanego czasu początku transferu); UART: dwukrotna weryfikacja podpisu (`secure_boot_v1: Verification result 0`), restart `SW_CPU_RESET`, bootloader z anti-rollback (secure version 1) załadował partycję `0x200000` (ota_1), `App version: 0.1.1`, telefon wrócił do READY z wersją 0.1.1 po ~5 s od startu płytki (znany bond). Ostrzeżenie „Checksum mismatch between flashed and built applications” w monitorze to skutek budowy obrazu w innym katalogu niż `firmware/build`, nie błąd urządzenia. Trwałość: po odłączeniu i ponownym włączeniu zasilania aplikacja po ponownym połączeniu pokazuje wersję 0.1.1 (obraz potwierdzony, bez rollbacku); wersja odczytana w aplikacji, bez logu UART tego startu.
- Sprawdzone (14.11.2026): impulsy OPEN/CLOSE na GPIO 17/18 „wyglądają prawidłowo” wg użytkownika (bez zapisanych wartości pomiaru: czas impulsu i odstęp nie zostały tu odnotowane).
- **Android 13 — parowanie (13.11.2026), rozwiązane:** na telefonie z Androidem 13 (MTK/ColorOS, Bluedroid) parowanie kończyło się dwoma niezależnymi błędami.
  - **Aplikacja:** firmware wysyłał Security Request ~40 ms po połączeniu, więc stos telefonu był już w `BOND_BONDING`, gdy `AndroidBleRadio.createBond()` wołało `device.createBond()` (zwraca wtedy `false`). Aplikacja uznawała to za porażkę, zamykała GATT i pokazywała błąd przed pytaniami o parowanie. Poprawka: porażką jest tylko `false` przy `BOND_NONE`; przy `BOND_BONDING` aplikacja czeka (`pollBond`).
  - **Firmware/stos telefonu:** parowanie startujące na wczesnym Security Request kończyło się `enc_change status=1035` (0x40B, DHKEY_CHECK_FAILED po stronie NimBLE). Telefon liczył DHKey Check z adresem własnym `69:4d:87:xx:xx:xx` typu 1 (RPA), a na łączu ESP widział go jako `d0:97:fe:xx:xx:xx` typu 0 (publiczny). Rozwiązanie: Security Request tylko dla znanego klienta z bondem (`brama_ble_peer_bonded`, `ble.c` `on_connect`); nieznany peer w oknie sam inicjuje parowanie przez `createBond()` po odkryciu usług. Potwierdzone na płytce: parowanie nieznanego telefonu przechodzi (QR, PIN), pytanie o parowanie pojawia się raz. Mechanizm po stronie Bluedroida to hipoteza poparta wynikiem testu (poprawka działa), nie ustalona przyczyna; osłabia ją to, że znany klient (Security Request wysyłany od razu) paruje się poprawnie. **Ponowne parowanie tego samego telefonu po „Zapomnij urządzenie” (znany klient na płytce) daje dwa pytania o parowanie** (Security Request + `createBond()` z aplikacji) — oczekiwane, sprawdzone 13.11.2026; firmware loguje decyzję (`peer ze znanym bondem…` / `peer bez bondu…`). Potwierdzone przez użytkownika (13.11.2026): po usunięciu rekordu klienta z płytki (`remove-client`) to samo parowanie daje jedno pytanie; tym samym `remove-client` działa na płytce.
  - Dodany log diagnostyczny w `ble.c` (`log_conn_addrs`: adresy `our_ota`/`our_id`/`peer_ota`/`peer_id` przy połączeniu i przy `enc_fail`, linia `SM: …` przy starcie) zostaje. Eksperyment `sm_mitm = 1` cofnięty do `0` po teście (nie był potrzebny); wersja z `sm_mitm = 0` zbudowana i testy hostowe 169/169 zielone, ale **do ponownego sprawdzenia na płytce**.
  - Do sprawdzenia: dalsze punkty A71–A72 na A13 (wyciszenie w 4. minucie, ponowne pojawienie się — ograniczenie ColorOS 13, patrz niżej); brak telefonu z API 34 (testowany telefon „A14” to Android 16, API 36).
  - Sprawdzone (14.11.2026, A13): rejestracja PIN-em przechodzi (z jednym pytaniem o parowanie dla nowego klienta; dwa pytania występują tylko u znanego klienta z bondem).
  - Sprawdzone (14.11.2026, A13/API 33, Oppo CPH2251): „brama w zasięgu” (A70, część A71). Warunek: powiązanie CDM utworzone przyciskiem „Powiąż urządzenie” w ustawieniach (po świeżej instalacji powiązania nie ma — pierwsze dwa testy na A13 i A14 nie przeszły tylko z tego powodu; `dumpsys companiondevice` bez wpisu `com.agendek.bramav2`). Po restarcie płytki powiadomienie z OTWÓRZ/ZAMKNIJ pojawiło się bez otwierania aplikacji i zniknęło po ok. 4 min 52 s od pojawienia się (zgodnie z 5 min). Nie sprawdzone: czy telefon rozłączył się przy wyciszeniu, ostrzeżenie przy regule własnej, ekran zablokowany, A72.
  - Telefon „A14” z logu okazał się Androidem 16 (CPH2791, API 36). „Powiąż urządzenie” kończyło się błędem od razu: `CDM_CompanionDeviceManagerService: IllegalStateException: Must declare uses-feature android.software.companion_device_setup in manifest` (Android 13 tego nie wymaga). Poprawka: `<uses-feature android:name="android.software.companion_device_setup" android:required="false" />` w `app/src/main/AndroidManifest.xml`; build i lint zielone, sprawdzone 14.11.2026 na API 36: po poprawce powiązanie przechodzi (okno systemowe, `Adding new association id=3`, `Start observing request`), `dumpsys companiondevice` pokazuje bramę w „Nearby BLE Devices”. Nie sprawdzone: samo powiadomienie (A70) na API 36.
  - Ograniczenie ColorOS 13 / A13 (14.11.2026): po wyciszeniu (QUIET) powiadomienie nie wraca po 30 s bez zasilania płytki — CDM przez 15 min (13:25–13:40) nadal raportował bramę w „Nearby BLE Devices”, więc zdarzenie zniknięcia i ponownego pojawienia się nie przychodzi. To zgodne ze spec 4.3 pkt 5 (wymaga pary zniknięcie/pojawienie się), ale zależy od timeoutu CDM danego telefonu.
  - Sprawdzone (14.11.2026, Oppo CPH2791 / Android 16, API 36): „brama w zasięgu” po powiązaniu: po restarcie płytki powiadomienie pojawia się w ~10 s; po odłączeniu zasilania CDM zgłasza zniknięcie w ~10 s (`likely BLE out of range`, powiadomienie znika), a po ponownym włączeniu płytki powiadomienie wraca w kilkanaście sekund (14:13:56). Odwrotnie niż na A13/ColorOS 13, gdzie zniknięcie nie jest zgłaszane. Ekran blokady (A70): po obudzeniu ekranu (bez odblokowania) powiadomienie jest widoczne, a OTWÓRZ/ZAMKNIJ działają; przy zgaszonym ekranie jest tylko dźwięk, bez wybudzenia (kanał `gate_connection` ma ważność domyślną — zachowanie systemu, bez zmian w kodzie). Wyciszenie (A71): powiadomienie zniknęło o 14:21:35, ok. 5 min po ostatnim dotknięciu (14:16:31); ~3 s wcześniej CDM zgłosił rozłączenie BT telefonu z bramą (`onBluetoothCompanionDeviceDisconnected`/`isAppeared=false`), a brama pozostała w „Nearby BLE Devices” — telefon rozłączył się i nie wrócił do łącza. Wyłączenie funkcji w ustawieniach (A72): plik ustawień `presence_enabled=false` (sprawdzone w `settings.preferences_pb`); odłączenie zasilania płytki na ~30 s i ponowne włączenie — CDM zgłosił `isAppeared=false` (14:30:47) i `isAppeared=true` (14:31:29), a przez 4 min nie pojawiło się powiadomienie, liczba usług aplikacji wróciła do 1 (tylko usługa CDM; z powiadomieniem było 2). Sam `reboot` płytki bez dłuższej przerwy nie wywołuje zdarzeń CDM, więc nie jest wystarczającym testem. Reguła własna MONITORING: patrz wpis poniżej (sprawdzone po poprawce).
  - **Błąd znaleziony przy teście reguły własnej MONITORING (14.11.2026, API 36):** po 5 min wyciszenie rozłączało telefon, ale powiadomienie wracało po ułamku sekundy i połączenie było odnawiane. Przyczyna (logcat): rozłączenie BT telefonu → CDM zgłasza `isAppeared=false` ~1 s po naszym `disconnect` (14:42:24.096) → `onPresenceDisappeared` zdejmuje wyciszenie i wstrzymanie reguły → własna uzbrojona reguła łączy ponownie po 17 ms → połączenie BT daje `isAppeared=true` → powiadomienie wraca. Poprawka w `GateRepository`: znacznik `quietEcho` (5 s, `PRESENCE_QUIET_ECHO_MS`) po wyciszeniu — `presenceDisappeared` w tym oknie w stanie QUIET jest ignorowane (prawdziwe odejście CDM zgłasza osobno po ~10 s). Testy: nowy `disappearedRightAfterQuietIsEchoOfOwnDisconnect`, dwa istniejące (`disappearedResetsToHidden`, `quietIsPersistedAndClearedOnDisappear`) czekają 6 s po wyciszeniu; wszystkie testy JVM, build i lint zielone. Do sprawdzenia na telefonie (API 36): reguła własna, wyciszenie bez powrotu powiadomienia i samoczynne CLOSE po ~30 s (nowa linia logu `impuls z reguły sterownika` w UART).
  - **Powtarzające się powiadomienia przy stałej obecności (14.11.2026, A16):** UART pokazał połączenia A16 co 104–1409 s, każde dokładnie ~300 s. Logcat (CDM): `onBleCompanionDeviceLost`/`Found` co kilkanaście sekund — skan BLE CDM gubi reklamy płytki przy zgaszonym ekranie, a `Lost` → `isAppeared=false` zdejmowało wyciszenie, następne `Found` dawało powiadomienie. Decyzja użytkownika: po wygaśnięciu i ukryciu nowe powiadomienie dopiero po zniknięciu i ponownym pojawieniu się bramy. Poprawka w `GateRepository`: `quietEcho` (5 s) zastąpiony karencją `PRESENCE_ABSENCE_GRACE_MS` = 180 s — w stanie QUIET `presenceDisappeared` uruchamia karencję, `presenceAppeared` ją anuluje, dopiero jej upływ (`QuietAbsenceOver`) zeruje do HIDDEN i zdejmuje wstrzymanie reguły. Testy: `briefDisappearAfterQuietKeepsQuiet`, `absenceLongerThanGraceResetsAndNextAppearShows`, dwa istniejące poprawione; JVM i build zielone. Do sprawdzenia na A16: po wyciszeniu brak nowych powiadomień przy stałej obecności; po odejściu >3 min i powrocie powiadomienie wraca.
  - **OTA, ścieżki negatywne (15.11.2026, płytka 0.1.1, telefon API 36):** (1) plik obcięty do 400 000 B — aplikacja odrzuca od razu (`OTA_FILE_INVALID`), w UART cisza (BEGIN nie wysłany). (2) pełny rozmiar z jednym zmienionym bajtem (offset 300000) — aplikacja przyjmuje, transfer idzie do końca (~400 s od startu do END), płytka odrzuca: `esp_image: Checksum failed. Calculated 0x37 read 0xa9`, `esp_ota_ops: New image failed verification`; sesja BLE trwa, brak restartu, po READY wersja nadal 0.1.1, aplikacja pokazuje błąd odrzucenia firmware. Zadziałała tu suma kontrolna obrazu; odrzucenie na podpisie (poprawna suma, zły podpis) nie było osobno wywołane. Do sprawdzenia: downgrade (0.1.0 na 0.1.1), anulowanie, rozłączenie w trakcie, obraz z inną nazwą projektu, drugi telefon w trakcie, rollback po nieudanym autoteście, zanik zasilania w trakcie.
  - **OTA, downgrade 0.1.1 → 0.1.0 (15.11.2026):** transfer i END przeszły, aplikacja pokazała sukces, płytka wystartowała z 0.1.0 (`app_init: App version: 0.1.0, Secure version: 1`, partycja 0x20000, `boot: Secure version (from eFuse) = 1`). Zgodne z projektem (`firmware_design.md`): odrzucany jest tylko starszy `secure_version`, a oba obrazy mają 1; zwykłe obniżenie wersji tekstowej jest dozwolone. Ochrona przed downgrade'em wymaga podniesienia `secure_version` w nowszym obrazie (osobny test: obraz z wyższym `secure_version`, potem obraz ze starszym). Płytka ma teraz firmware 0.1.0.
  - **OTA, anulowanie (15.11.2026):** anulowanie w połowie transferu → aplikacja pokazuje komunikat o przerwaniu, ponowny transfer rusza (sesja po przerwanej nie blokuje płytki), drugie anulowanie też; w UART nie ma śladu zdarzeń OTA, bo firmware ich nie logował. **Dodane logi OTA** (`ble.c` `brama_ble_send`: `OTA ACK łącze=… żądanie=0x… wynik=…` dla BEGIN/END/ABORT zawsze, dla DATA tylko przy błędzie; `app_main.c` `actor_task`: `transfer OTA przerwany (zdarzenie aktora typ=…)` przy porzuceniu bez komendy — rozłączenie, 30 s bez danych, reset). Build `esp32` zielony (obraz do wgrania z `fwbuild`), do sprawdzenia na płytce.
  - **OTA, rozłączenie w trakcie (15.11.2026, firmware z logami OTA):** BEGIN → `OTA ACK żądanie=0x50 wynik=0`; wyłączenie Bluetooth w telefonie w trakcie transferu → `DISCONNECT reason=0x213` i po 40 ms `transfer OTA przerwany (zdarzenie aktora typ=2)` (DISCONNECT), bez czekania na limit 30 s. Po włączeniu Bluetooth telefon połączył się sam (CONNECT po ~53 s od rozłączenia), nowy BEGIN dostał wynik 0 (sesja czysta). Płytka nadal 0.1.0.
  - **OTA, drugi telefon dołącza w trakcie transferu (15.11.2026, A16 transfer, A13 łączy się):** BEGIN z A16 (conn=0) przyjęty, w trakcie ruchu DATA łączy się A13 (conn=1); ~140 ms po `LINK_ESTAB` kontroler zrywa łącze A16: `ACL free: connection not found; conn_handle=0x0000`, `DISCONNECT conn=0 reason=0x23d` (0x3D, MIC failure), `transfer OTA przerwany (typ=2)`. A13 normalnie przechodzi do sesji (ENC_CHANGE, SUBSCRIBE, MTU, indicate), aplikacja A16 pokazała samoczynne przerwanie transferu. Wygląda na kolizję drugiego łącza (oba uchwyty `0x0000` w logach NimBLE — hipoteza, niepotwierdzona) z intensywnym ruchem szyfrowanym; wariant znanego ograniczenia dwóch łączy na kontrolerze BT 4.2. Logika BUSY dla drugiego klienta nie została przez to sprawdzona; do powtórzenia z A13 połączonym przed startem transferu. Do decyzji: zapis jako ograniczenie albo dalsza analiza (np. żądanie `Update Data Length`/wstrzymanie ruchu DATA w chwili CONNECT).
  - **OTA, drugi telefon już połączony (15.11.2026):** A16 BEGIN → `OTA ACK łącze=0 żądanie=0x50 wynik=0`; A13 (conn=1, połączony przed startem) BEGIN w trakcie transferu A16 → `OTA ACK łącze=1 żądanie=0x50 wynik=1` (BUSY), aplikacja A13 pokazuje „aktualizacja z innego telefonu”, transfer nie startuje; transfer A16 trwa bez przerwy (brak `transfer OTA przerwany`). Zgodne ze specyfikacją. Wniosek łącznie z poprzednim wpisem: BUSY działa, ale samo dołączenie drugiego telefonu w trakcie ruchu OTA zrywa łącze OTA (MIC failure).
  - **OTA, zanik zasilania w połowie transferu (15.11.2026):** zasilanie odcięte w trakcie DATA (transfer A16). Start: `rst:0x1 (POWERON_RESET)`, `boot: Loaded app from partition at offset 0x20000`, `App version: 0.1.0`, `Secure version: 1`, brak błędów `esp_image`/`esp_ota_ops` i rollbacku; telefon połączył się po ~1,4 s od startu aplikacji. Obraz był zapisywany do nieaktywnej partycji, więc przerwany zapis nie wpływa na rozruch. Nowy transfer po takim restarcie: BEGIN → `OTA ACK … wynik=0` (55,5 s po starcie), transfer rusza na partycji z niepełnym obrazem.
  - **OTA, obraz z inną nazwą projektu (15.11.2026):** obraz zbudowany z kopii `firmware/` ze zmienionym `project(obcy)` (ten sam klucz i konfiguracja; podpis i suma poprawne, w `esp_app_desc_t` `obcy`). Aplikacja odrzuca go lokalnie (`FirmwareImage.parse` → `WRONG_PROJECT` → „Wybrany plik nie jest firmware tego sterownika”), BEGIN nie jest wysyłany, w UART brak nowych wpisów OTA. Sprawdzenie nazwy po stronie płytki (`brama_ota_check_header`, wynik 5 przy pierwszym DATA) jest pokryte tylko testami hostowymi; na płytce wymagałoby tymczasowo wyłączonej walidacji w aplikacji.
  - **OTA, zły podpis przy poprawnej sumie (15.11.2026):** `brama-0.1.1.bin` z jednym zmienionym bitem w podpisie (offset 655339, ostatnie 64 B), reszta identyczna. Aplikacja przyjęła plik, transfer do końca, END → UART: `esp_image: Verifying image signature...`, `E esp_image: Secure boot signature verification failed`, `Calculating simple hash to check for corruption...`, `W esp_image: image valid, signature bad`, `E esp_ota_ops: New image failed verification`, `OTA ACK łącze=0 żądanie=0x52 wynik=5` (IMAGE_REJECTED). Obraz leżał w `ota_1` (0x200000); brak restartu, płytka dalej 0.1.0. Podpis jest więc rzeczywiście sprawdzany i odróżniany od sumy kontrolnej (`image valid, signature bad`).
  - **OTA, rollback po nieudanym autoteście (15.11.2026):** obraz 0.1.1 zbudowany z kopii `firmware/` (scratchpad `fwrb`) z wymuszonym `selftest = false` w `app_main.c` (poza tym identyczny, podpis poprawny). Transfer i END → `OTA ACK żądanie=0x52 wynik=0`; restart `rst:0xc (SW_CPU_RESET)`, `Loaded app from partition at offset 0x200000`, `App version: 0.1.1`; po ~2 s `E brama: Autotest nowego obrazu nieudany — powrót do poprzedniej wersji` → `brama_hw_ota_rollback` → drugi restart `SW_CPU_RESET`, `Loaded app from partition at offset 0x20000`, `App version: 0.1.0`, `Start: tryb 0, obraz 0, klienci 3`. Rollback działa, klienci i bondy zachowane, płytka wróciła do 0.1.0 bez interwencji.
  - Po poprawce (14.11.2026, API 36): OPEN z autozamykaniem o 14:47:40, powiadomienie zniknęło o 14:53:11 (5 min), echo `isAppeared=false` od CDM o 14:53:12 zostało zignorowane, brak `connect()` i brak powrotu powiadomienia przez kolejne 90 s. UART: `DISCONNECT` o 974,14 s, `impuls z reguły sterownika (zdarzenie aktora typ=7 = TICK)` o 1004,25 s, czyli samoczynne CLOSE po 30,1 s od rozłączenia — A71 (reguła własna MONITORING) sprawdzone na API 36.
  - Sprawdzone (14.11.2026, A13): naprawa bondu (9.5) działa, gdy okno rejestracji na płytce jest otwarte (i rekord klienta istnieje na płytce). Bez otwartego okna nie jest oferowana/nie przechodzi — zachowanie zamierzone.
  - Sprawdzone (14.11.2026, `fwbuild`, A13 + A14): reconnect po `reboot` płytki z dwoma klientami. Oba telefony wróciły same do READY w ~5,3 s i ~6,5 s od startu (bez otwierania aplikacji); komendy OPEN/CLOSE z obu działają. Pierwszy: Security Request znanemu bondowi, peer RPA rozpoznany po IRK; drugi: bez CONNECT z NimBLE (adopcja), łącze zaszyfrowane przez telefon. Nie sprawdzone: odwrócona kolejność (A14 pierwszy) po restarcie.
  - Log komend (14.11.2026, `main/app_main.c`, `actor_task`): po każdym OPEN/CLOSE linia `komenda OPEN|CLOSE od klienta [n] id=xxxxxxxx łącze=L impuls=tak|nie` — `[n]` to indeks z `info` (-1 = brak w obrazie), `id` to 4 pierwsze bajty `client_id` (prefiks akceptowany przez `remove-client`), `impuls=nie` oznacza odmowę (BUSY, interlock, reguła) albo nieaktualne łącze. Sprawdzone na sprzęcie (14.11.2026, trzy klienty w obrazie: A13, A14 i drugi A13 Oppo): CLOSE od klienta [2] na łączu 1 i OPEN od klienta [0] na łączu 0, obie z `impuls=tak`.
  - **A82, okno z telefonu (15.11.2026, A16 otwiera, A13 rejestrowany):** (1) otwarcie okna z A16: `Okno rejestracji otwarte przez telefon`, reklama odświeżona w 20 ms (`filter_policy=0`), dioda miga, bez impulsu i bez zapisu NVS (`zapisy NVS 0`). (2) Rejestracja A13 przez QR z okna po `remove-client 2` + reboot: nowy klient (ten sam adres bondu, nowy `client_id`), CLOSE z impulsem. (3) Rejestracja PIN-em po kolejnym `remove-client` + reboot nie powiodła się (licznik PIN-u po restarcie wyczerpany wg `phone_window_design` §7 — nie potwierdzone osobno), a po niej **wszystkie kolejne próby rejestracji bez restartu płytki** (PIN i QR, także po przycisku i po restarcie telefonu) kończyły się `enc_change status=1035` (SM: DHKey Check Failed wysłany przez płytkę), po stronie telefonu `smp_proc_pairing_cmpl failed, smp_reason UNKNOWN[145]`. (4) **Po restarcie płytki QR przeszedł od razu** (klient [2] `4feae14a…`, bond, CLOSE z impulsem). Hipoteza, niepotwierdzona: nieudana rejestracja (parowanie przeszło, odmowa na etapie PIN-u) zostawia w stanie płytki (staging/bond NimBLE) wpis, który psuje kolejne parowania tego telefonu do restartu. Do sprawdzenia: celowo błędny PIN, potem QR bez restartu; jeśli się potwierdzi — firmware ma czyścić staging i bond NimBLE przy rozłączeniu bez zatwierdzenia rejestracji. Do zrobienia: licznik PIN-u po przycisku, 5 nieudanych PIN-ów, naprawa bondu z okna innego telefonu.
  - **A82, błąd „nieudana rejestracja psuje kolejne parowania” (15.11.2026) — odtworzony i naprawiony:** celowo błędny PIN, potem QR bez restartu płytki dawał deterministycznie `enc_change status=1035` (parowanie błędnego PIN-u przechodzi, telefon rozłącza 0x213, brak klienta; kolejne parowanie tego samego telefonu kończy się DHKey Check Failed do restartu płytki). Przyczyna: stos zostawia peera w resolving liście kontrolera po niezatwierdzonej rejestracji (staging jest zerowany z łączem, więc nie on). Pierwsza poprawka (`ble_gap_unpair` w `link_closed`) nie zadziałała: staging był już wyzerowany, więc `rc=15` (ENOENT) i resolving lista zostawała. Poprawka: `link_closed` (`ble.c`) dla peera ze stagingiem, którego tożsamość nie należy do klienta z obrazu, woła dodatkowo `ble_hs_pvcy_remove_entry`; log `niezatwierdzona rejestracja: usunięcie peera … unpair=15 resolving=0`. **Potwierdzone na płytce:** błędny PIN → QR bez restartu przechodzi (klient [2], OPEN i CLOSE z impulsem). Mechanizm (resolving lista) wynika z działania poprawki, nie z bezpośredniego odczytu listy. Do zrobienia nadal: licznik PIN-u po przycisku, 5 nieudanych PIN-ów + okno z telefonu, naprawa bondu z okna innego telefonu.
  - **A82, rejestracja PIN-em po przycisku (15.11.2026):** po `remove-client 2` + reboot, okno z przycisku, rejestracja A13 PIN-em przeszła (klient [2], nowy `client_id`, bond `b4:20:5b:xx:xx:xx`); wcześniejsze próby przed otwarciem okna kończyły się `enc_change status=7` i rozłączeniem 0x216 (okno zamknięte, oczekiwane). To jeszcze nie dowodzi resetu licznika PIN-u przez przycisk (po restarcie licznik jest świeży); wariant z jednym błędnym PIN-em przed przyciskiem do zrobienia. **Nowa obserwacja:** podczas wymiany PIN-u (PAKE) log `blokada aktora zajeta ponad 2 s: czeka 'ui'/'actor', trzyma 'nimble_host'` — obliczenia EC-JPAKE (krzywa P-256) wykonywane przy zajętej wspólnej blokadzie w wątku NimBLE blokują aktora i UI na ok. 2 s. Funkcjonalnie bez skutku (rejestracja przeszła), ale to opóźnia inne łącza i przycisk; przyczyna to hipoteza z czasu i miejsca w logu, nieprofilowana. Do rozważenia: liczenie PAKE poza blokadą.
  - **A82, błędny PIN → przycisk → poprawny PIN (15.11.2026):** po `remove-client 2` + reboot: błędny PIN (`niezatwierdzona rejestracja: … unpair=15 resolving=0`), przycisk, poprawny PIN — rejestracja przeszła, klient [2] z nowym `client_id`. Ponownie widać `blokada aktora zajeta ponad 2 s` przy każdej wymianie PIN-u (także przy błędnym). Ten test potwierdza tylko, że jedna porażka nie blokuje kolejnej próby po przycisku; reset licznika sprawdzi dopiero wariant z 5 błędnymi PIN-ami.
  - **A82, 5 błędnych PIN-ów i okno z telefonu (15.11.2026):** pięć nieudanych PIN-ów w jednym oknie (kolejne okna otwierane z A16, bez przycisku, bez restartu płytki; przed każdą próbą bond zapomniany na A13) — wszystkie pięć doszło do wymiany PAKE, po każdej `niezatwierdzona rejestracja … resolving=0`. Szósta próba, po ponownym otwarciu okna z A16, dostała parowanie, ale rozłączyła się bez wymiany PIN-u (brak obliczeń PAKE w logu): **limit 5 zadziałał i okno z telefonu go nie zdjęło**. Potem rejestracja **QR w zablokowanym stanie PIN-u przeszła** (klient [2], CLOSE z impulsem). Uwaga do wcześniejszej próby: po jednej nieudanej rejestracji kolejne połączenia bez zapomnienia bondu na telefonie kończą się `enc_change status=7` (telefon trzyma bond, płytka nie) — to zachowanie testu, nie błąd płytki. Do sprawdzenia: przycisk przywraca PIN po wyczerpaniu licznika.
  - **A82, przycisk a licznik PIN-u — wniosek z kodu i wcześniejszych testów (15.11.2026):** `brama_auth_init` (`limits.c:8`) ustawia po starcie `pin_failures = BRAMA_PIN_FAILURES_PER_WINDOW`, czyli licznik jest po restarcie wyczerpany, a zeruje go dopiero przycisk (`brama_auth_window_open`). To wyjaśnia pierwszą nieudaną rejestrację PIN-em w oknie z telefonu po restarcie (wcześniej opisaną jako „niepotwierdzoną”) i potwierdza reset: rejestracja PIN-em po przycisku, wykonana po rebootcie, przeszła. Próba „usuń klienta bez rebootu + przycisk + PIN” jest nieważna: `remove-client` zmienia NVS, a obraz w RAM nadal zna bond, więc płytka widzi „znany bond” (Security Request) i nie rejestruje od nowa — dlatego konsola żąda `reboot`.
  - **A82, naprawa bondu z okna otwartego przez inny telefon — NIE DZIAŁA (15.11.2026, odtworzone dwukrotnie, także po rebootcie płytki):** A13 zarejestrowany (klient [2]); na A16 usunięty bond systemowy; okno otwarte z A13 (A13 nadal połączony, `conn=0`); A16 skanuje QR. Płytka: A16 łączy się przez RPA (`47:4e:71:xx:xx:xx` typ 1), `łącze conn=1 bez zdarzenia CONNECT, ale aktywne: adopcja`, `peer ze znanym bondem: Security Request` (rozpoznany po IRK z obrazu), potem po ok. 8 s `CONNECT status=1 conn=1` (connect_fail), LINK_ESTAB, **brak ENC_CHANGE i brak DISCONNECT dla conn=1**. Telefon (logcat A16): połączenie OK, `PAIRING_REQ` wysłany po zatwierdzeniu pytania, Pairing Failed po ~90 ms (`SMP_PAIR_FAIL_UNKNOWN`, `btif_dm_ble_auth_cmpl_evt: failed with reason 86`), telefon sam rozłącza. Pierwsza rejestracja A13 (bez wcześniejszego bondu na płytce) przy drugim łączu A16 działała, więc nowa jest kombinacja: **klient z bondem w obrazie (płytka zna jego IRK) + drugie łącze równocześnie**. Przyczyna niewiadoma (log NimBLE na poziomie SM wyłączony); hipotezy: (a) zakłócona obsługa drugiego łącza przy rozwiązywaniu RPA po IRK z obrazu, (b) znany problem drugiego łącza centralnego ESP32. Następny krok: ten sam test z oknem z przycisku i bez drugiego połączonego telefonu (rozróżnia (a)/(b)).
  - **A82, naprawa bondu z okna innego telefonu — PRZYCZYNA ZNALEZIONA I POTWIERDZONA (15.11.2026):** nie IRK/RPA ani drugie łącze (test bez drugiego łącza, z oknem z przycisku, też padał), tylko **limit bondów NimBLE `CONFIG_BT_NIMBLE_MAX_BONDS=3`** (domyślny) przy 3 klientach w obrazie. Przy `PAIRING_REQ` NimBLE sprawdza `ble_sm_chk_store_overflow`: licznik z naszego magazynu (3 klientów) ≥ limit → `BLE_STORE_EVENT_FULL`, a `store_status_cb = NULL` → odmowa `Pairing Failed 0x08 (Unspecified)` w ~30 ms bez żadnego zdarzenia GAP (log HCI: `PAIRING_REQ 01 04 00 0d 10 0f 0f`, odpowiedź `05 08`). Dowód: diagnostyczny callback `store_status` przy limicie 3 zgłaszał `event=2 count=3 procs=0 max=3`; po podniesieniu limitu do 9 brak zdarzenia FULL, `REPEAT_PAIRING`, `ENC_CHANGE` OK i klient A16 naprawiony (ten sam adres bondu `54:1f:cd:xx:xx:xx`, nowy `client_id`, nadal 3 klientów, bez duplikatu). **Skutek błędu produkcyjnego:** płytka z 3 klientami (a limit obrazu to 8) nie rejestruje kolejnych telefonów ani nie naprawia bondu. Poprawka: `CONFIG_BT_NIMBLE_MAX_BONDS` ≥ `BRAMA_MAX_CLIENTS + 1` (9) w `sdkconfig.defaults` — **wprowadzone 15.11.2026** (`CONFIG_BT_NIMBLE_MAX_BONDS=9`); czysty build z drzewa użytkownika (scratchpad, `-B`) potwierdza wartość 9 i brak śladów diagnostycznych. Istniejący `firmware/sdkconfig` trzeba przegenerować (usunąć plik albo `fullclean`), bo przechowuje starą wartość 3. Uwaga z przebiegu: pierwsze dwie próby z limitem 9 poszły na złym obrazie (`fwdbg-build`, limit 3) przez pomylone katalogi budowania; wniosek opiera się dopiero na weryfikacji flash (`verify_flash`) i obrazie `fwbond-build`. Wcześniejsza analiza poniżej (RPA/IRK, drugie łącze) jest nieaktualna.
  - **A82, końcowa weryfikacja na czystym obrazie (15.11.2026):** czysty build z `sdkconfig.defaults` (`MAX_BONDS=9`) wgrany; start OK, 3 klientów, brak śladów diagnostycznych. A16 po naprawie bondu steruje bramą: `komenda CLOSE od klienta [2] id=ec32a52c … impuls=tak`, potem `OPEN … impuls=nie` (decyzja o impulsie zależy od stanu bramy w modelu; nie weryfikowano osobno). Rejestracji czwartego klienta nie sprawdzono (użytkownik ma 3 telefony); limit obrazu (8) i limit NimBLE (9) są zgodne z założeniem. **A82 zamknięte:** okno z telefonu, QR, PIN po przycisku, limit 5 PIN-ów, QR w zablokowanym stanie PIN-u, naprawa bondu z okna innego telefonu.
  - **PAKE poza blokadą (16.11.2026) — zaimplementowane, pierwszy test na płytce OK:** rundy EC-JPAKE (`brama_session_on_frame` dla `PAKE_ROUND1/2`) trzymały wspólną blokadę aktora/BLE ok. 2–2,7 s (zatrzymanie `TICK`/impulsu, przycisku, UI i pozostałych łączy; log `blokada aktora zajeta ponad 2 s … trzyma 'nimble_host'`). Teraz `ble.c` (`pake_try_offload`, `pake_worker`, `finish_frame`) kopiuje sesję łącza do zadania z heapu, liczy rundę bez blokady (zadanie `pake_worker`, prio 2, stos 10 KB) i oddaje wynik pod blokadą tylko jeśli łącze nadal istnieje (`link_token`/epoka); sesja łącza jest w tym czasie zamrożona (`pake_inflight`, kolejne ramki odrzucane), `link_closed` nie zwalnia `jpake` podwójnie, a gdy zadanie zajęte albo brak pamięci ramka jest odrzucana (klient ponawia). Callbacki `window_open`/`attempt_blocker` w zadaniu biorą blokadę krótko. API `session` bez zmian (testy hostowe nieuruchamiane, brak zmian w modułach hostowych). **Ograniczenie pamięci:** statyczny DRAM zapełniony w 98,9% (zostaje ok. 1,4 KB) — zwolniono 1440 B przez wspólną statyczną wiadomość `s_amsg` zamiast trzech (wszystkie ścieżki pod blokadą BLE); kopia sesji jest z heapu. **Wynik:** rejestracja PIN-em A13 (klient [2], nowy `client_id`) przeszła bez ani jednego błędu `blokada…`, bez `PAKE: zadanie zajęte`, bez paniki. Do sprawdzenia: błędny PIN (ścieżka `auth_failure` z zadania), 5 błędnych PIN-ów, rozłączenie telefonu w trakcie rundy. **Błędny PIN → przycisk → poprawny PIN (16.11.2026):** błędny PIN przeszedł ścieżką zadania (`niezatwierdzona rejestracja … resolving=0`), po przycisku poprawny PIN zarejestrował A13 (klient [2], nowy `client_id`); brak `blokada…`, `PAKE: zadanie zajęte`, paniki. **Rozłączenie w trakcie rundy:** nie sprawdzone na sprzęcie (okno ok. 1–2 s, dwie próby użytkownika nie trafiły w rundę: połączenia kończyły się `0x205` poza oknem; dodano log `PAKE: łącze N zniknęło w trakcie rundy, wynik odrzucony`, który pokaże trafienie); zweryfikowane przeglądem kodu: `link_closed` zeruje `jpake_active` oryginału, zadanie przy niezgodnej epoce zwalnia `jpake` kopii dokładnie raz, ponownie użyty slot łącza ma `pake_inflight=false` i wynik jest odrzucany, zajęte zadanie odrzuca ramkę drugiego łącza.
  - **Aplikacja: usuwanie bondu systemowego i powrót na ekran główny (16.11.2026):** (1) `BleRadio.removeBond(address)` (`AndroidBleRadio` przez refleksję na ukrytym `BluetoothDevice.removeBond()`, bez efektu gdy brak bondu). (2) `BleGateLink.close`: rejestracja (`pairing`) zakończona przed wysłaniem PAIR_COMMIT (zły PIN, odmowa sterownika, timeout próby, anulowanie) usuwa bond; po wysłaniu COMMIT bond zostaje (sterownik mógł zatwierdzić klienta, recovery 11.3), po PAIR_DONE oczywiście też. (3) „Usuń z telefonu” (`CompanionAssociation.disassociateAll`): usuwa bondy dla adresów z powiązań CDM i `BleGateLink.knownAddresses()`. (4) `BramaNav`: przejście poświadczenia ACTIVE → NONE zdejmuje ze stosu ustawienia/aktualizację/historię i zostawia ekran główny z „Dodaj bramę” (UNCONFIRMED → NONE, czyli porażka rejestracji, zostaje w kreatorze). Testy: 6 nowych w `BleGateLinkPairingTest`/`BleGateLinkPinPairingTest` (bond po sukcesie zostaje, po przerwaniu/odmowie/timeout/złym PIN-ie znika, po zgubionym DONE po COMMIT zostaje); `:ble`, `:domain`, `:app` zielone. **Na urządzeniach:** A13 (Android 13) — zły PIN i „Usuń z telefonu” usuwają bond (potwierdzone przez użytkownika, `dumpsys`); A16 (Android 16) — „Usuń z telefonu” usuwa bond (logcat: `removeBond() … called by com.agendek.bramav2`, lista bondów pusta). Nawigację po usunięciu potwierdzono ręcznie. Ryzyko: ukryte API może zostać zablokowane w przyszłych wersjach Androida (wtedy bond zostaje, użytkownik zapomina urządzenie w ustawieniach Bluetooth).
- **Konsola UART (13.11.2026):** `info` wypisuje listę klientów (`[n] client_id …  bond <adres tożsamości> typ=…`, bez `K_client` i danych bondu); nowa komenda `remove-client <numer z info | początek client_id (min. 4 cyfry hex)>` usuwa jednego klienta: commit obrazu z `auth_generation + 1` (unieważnia sesje i kopię reguły), reguła w RTC DISARMED (wspólny `disarm_rtc` z `reprovision`), potem obowiązuje `reboot` (konsola działa poza aktorem). Tylko zbudowane (`esp32`, bez ostrzeżeń); `main` nie ma testów hostowych — do sprawdzenia na płytce.
- **Dwa łącza naraz (13.11.2026), w toku:** A13 podłączony → A14 jako drugi nie łączy się (A14 pierwszy, A13 drugi działa). UART: `connect status=1` (`BLE_HS_EAGAIN`: NimBLE dla slave zgłasza CONNECT dopiero po `Read Remote Version` + `LE Read Remote Features`), bez `adresy[connect]`; telefon A14 widzi żywe łącze ~5 s i zrywa je timeoutem GATT; `gatt access conn=1` nie pojawia się nigdy, więc ruch ATT drugiego łącza nie dociera do GATT. Parametry obu łączy identyczne (itvl 39), więc to nie równe interwały. Dodane logi: `parametry`, `connect_fail`, `gatt access`, `zasoby[…]` (wolne mbufy i sterta). Eksperymenty (bez zmiany `sdkconfig.defaults`): (1) log zasobów w zwykłym obrazie, (2) wersja testowa z `CONFIG_BT_NIMBLE_MAX_CONNECTIONS=3`/`CONFIG_BTDM_CTRL_BLE_MAX_CONN=3`. Wyniki: log zasobów — mbufy 36/36 wolne, sterta stabilna (odpada); 3 łącza zamiast 9 — bez zmiany (odpada); nRF Connect na A14 jako drugi centralny — łącze 1 działa na poziomie SMP/ATT (`ENC_CHANGE`, zapis RX z `conn=1`), ale NimBLE nigdy nie zgłasza dla niego CONNECT (`link=brak`). **Obejście (do sprawdzenia na płytce):** `adopt_orphan_links` w `tick_cb` — łącze hosta (rola slave) bez kontekstu przez 1,5 s jest adoptowane (`on_connect`, ewentualnie `on_encrypted`), a `CONNECT status != 0` (NimBLE kończy tak łącze zamiast DISCONNECT) zamyka kontekst (`link_closed`). Przyczyna braku CONNECT (utknięty łańcuch `Read Remote Version`/`Read Remote Features` dla drugiego łącza) nieustalona.
  - **Ślad HCI hosta (obraz `fwdbg`, NimBLE DEBUG, 13.11.2026):** łącze 1 (A13): `LE Enhanced Connection Complete` → `Read Remote Version Info` → po ~100 ms zdarzenie `Read Remote Version Complete` → `LE Read Remote Features` → `Features Complete` → CONNECT. Łącze 2 (A14, przy A13 połączonym): po `Enhanced Connection Complete` host wysyła `Read Remote Version Info`, ale **kontroler nie zgłasza żadnego zdarzenia dla tego łącza** (ani Version Complete, ani danych ACL) do końca połączenia; `Disconnection Complete` przychodzi 2,66 s po naszym `Disconnect`. Awaria jest więc poniżej hosta NimBLE (kontroler/link-layer ESP32, libbtdm z ESP-IDF 6.0.1). Powtarzalność: A14→A13 działa 100%, A13→A14 nie działa 100%; nRF Connect na A14 jako drugi centralny działał. Próba zbudowania firmware na ESP-IDF 5.4 (porównanie kontrolera) nie przechodzi: PSA PAKE (`psa_pake_setup`/`psa_pake_get_shared_key`) i `esp_rtc_time.h` są specyficzne dla IDF 6.
  - Wcześniejszy wynik z modem sleep (nieaktualny jako wyjaśnienie — modem sleep nie jest przyczyną; wyłączenie dwukrotnie zwiększa pobór prądu): z `CONFIG_BTDM_CTRL_MODEM_SLEEP=y` (tryb ORIG) drugie łącze było prawie nieobsługiwane przez kontroler (brak odpowiedzi na Security Request, zerwanie łącza trwało ~2,7 s). Z `CONFIG_BTDM_CTRL_MODEM_SLEEP=n` A14 jako drugi centralny szyfruje, czyta INFO, subskrybuje i pisze do RX w ~0,6 s od pierwszego zdarzenia (obraz testowy `fwnosleep`). CONNECT nadal nie jest zgłaszany dla drugiego łącza — adopcja pozostaje potrzebna; jest uruchamiana od razu przy pierwszej oznace życia (`adopt_if_orphan`: zapis/odczyt GATT albo zdarzenie GAP z uchwytem) i dodatkowo po 1,5 s w `tick_cb`; na łączu już zaszyfrowanym nie wysyła Security Requestu. Decyzja o trwałym `MODEM_SLEEP=n` w `sdkconfig.defaults` — do zatwierdzenia.
  - Wynik z 14.11.2026: po wyłączeniu na A13 „powiadomienia gdy brama jest w zasięgu” A14 łączy się jako drugi bez problemu (przy włączonym — nie). Logcat A13 (`~/a13_presence_on.log`) pokazuje tylko zwykłe łączenie (skan LOW_LATENCY ~2,5 s, `connect()` bez auto, MTU 247, potem cisza radiowa aplikacji); nie obejmuje próby A14. Ustawienie wyłącza w aplikacji jedynie reakcję na zdarzenia CDM (`onPresenceAppeared`: `policy.setPresence(true)`, `evaluateConnection`), obserwacja CDM w systemie trwa. Mechanizm nieznany; do sprawdzenia logcat A13 obejmujący próbę A14 oraz macierz ON/OFF × kolejność.
  - Powtórka 14.11.2026 (ustawienie ON, A14 od razu po A13, bez „Zapomnij”): A14 połączył się od razu; `~/a13_full.log` nie zawiera aktywności BT (logcat zebrany po połączeniu). Awaria nie jest już odtwarzalna, więc przełącznik nie jest potwierdzoną przyczyną; możliwy wpływ stanu telefonu/płytki (np. wcześniejsza sesja CDM, restart). Adopcja łączy bez CONNECT i logi diagnostyczne zostają do czasu potwierdzenia, że problem nie wraca.
  - Powtórka z wieloma próbami (14.11.2026, `fwbuild`): A14 jako drugi łączył się dopiero za 3. (wcześniej) i za 7. razem (po zmianie grace). Nieudane próby miały zawsze ten sam przebieg: LINK_ESTAB bez CONNECT, adopcja z ticka po 1,5 s, Security Request bez reakcji, po ~4,8 s od adopcji `enc_change status=7` (to timeout nadzoru 5 s po stronie kontrolera; `terminate` kończy się `UNK_CONN_ID`). Udane próby: A14 sam szyfruje łącze po ~0,75 s od LINK_ESTAB. Wniosek: nieudane łącze jest martwe na poziomie radiowym od początku (kontroler nie dostaje pakietów od A14), a nie ucinane przez nasze 2 s — próba wydłużenia grace (`ORPHAN_SLOW_LINK_US`) nic nie dała i została wycofana. Hipoteza: nakładanie się terminów zdarzeń drugiego łącza (oba itvl=39) z łączem A13 w kontrolerze ESP32; sukces zależy od losowego przesunięcia. Do sprawdzenia: zmiana interwału pierwszego łącza przez żądanie aktualizacji parametrów.
  - Eksperyment (14.11.2026, do sprawdzenia): `on_encrypted` pierwszego (jedynego) łącza wysyła `ble_gap_update_params` z interwałem `FIRST_LINK_ITVL` = 80 (100 ms, `ble.c`, 0 wyłącza); log `żądanie interwału … rc=`. Oczekiwane: A14 jako drugi łączy się za pierwszym razem. Eksperyment, nie decyzja projektowa.
  - Wynik eksperymentu: z `FIRST_LINK_ITVL`=80 A13 przestał się łączyć — po `ENC_CHANGE` firmware wysłał `connection parameter update` (rc=0), a log kończy się na `DATA_LEN_CHG`, bez dalszych zdarzeń (A13 nie kończy konfiguracji). Domyślnie wyłączone (`FIRST_LINK_ITVL` 0); hipoteza o nakładaniu terminów nie została zweryfikowana.
  - **Decyzja (14.11.2026): znane ograniczenie, bez dalszych zmian.** Drugi telefon łączy się jako drugi centralny dopiero po kilku próbach (każda nieudana trwa ~7 s: martwe łącze do timeoutu nadzoru 5 s); pierwszy telefon i kolejność A14→A13 działają od razu. Przyczyna po stronie kontrolera ESP32 (BT 4.2), nieustalona. Zostaje obejście adopcji łączy bez CONNECT oraz logi diagnostyczne. Opcje odrzucone: krótszy timeout próby w aplikacji, zmiana parametrów kontrolera w `sdkconfig.defaults`.
- Diagnostyka tymczasowa (ślad ACL, `hcilog`, logi `gatt access`/`subscribe`/`mtu`/`ramka`/`client_key`/`brama_store`, logi `AndroidBleRadio`) została usunięta; zostają logi `connect`/`disconnect` z kodem powodu oraz ostrzeżenia (`enc:`, `repeat_pairing`, `adv_start`, blokada).

## Naprawa bondu przy zachowanym `K_client` (plan `doc/plans/2026-10-28-bond-repair.md`, projekt `doc/bond_repair_design.md`)

Stan przed zmianą: w oknie ponowne parowanie znanego telefonu dawało READY na kluczach tylko w stagingu (bez zapisu — po rozłączeniu wracał stary bond); nowy adres tożsamości dostawał AUTH_FAILED; telefon bez bondu ponawiał połączenie bez wskazówki.

| Część | Zmiana | Testy |
|---|---|---|
| Firmware — sesja | `session_admit` (WINDOW_CLOSED / REPLACEMENT_BLOCKED po poprawnym HMAC), stan `REPAIR_COMMITTING`, `BRAMA_EV_REPAIR_COMMIT`, `brama_session_repair_finish` (READY ze snapshotem z chwili commitu) | 4 w `test_session.c` |
| Firmware — aktor | `BRAMA_AMSG_REPAIR_COMMIT`: podmiana wyłącznie bondu klienta (K_client i reguła bez zmian), `brama_actor_repair_blocker` (inne łącze klienta, adres tożsamości innego ACTIVE), STORAGE_FAILURE przy błędzie NVS | 6 w `test_actor.c` |
| Firmware — `brama_ble` | łącze ze stagingiem zawsze CANDIDATE; klucz dowolnego ACTIVE tylko na CANDIDATE; READY po commit; odmowa rozłącza po wysłaniu; `ble_gap_unpair` starego adresu | kompilacja `esp32` zielona; działanie na płytce |
| `:ble` | `repairBond()`, `BondMissing` (connect bez bondu), `BondFailed` | 5 nowych + 1 zmieniony |
| `:domain` | `repairBond()`, `bondRepairAvailable`, 4 teksty (63 statusowe), brak automatycznych ponowień po braku bondu i nieudanej naprawie | 10 |
| `:app` | przycisk „Napraw połączenie” na ekranie głównym | 1 |

- Decyzje właściciela (§6 projektu): nowe teksty w rozdziale 3, naprawa świadomą akcją, REPLACEMENT_BLOCKED przy cudzym adresie tożsamości, rozłączenie po odmowie.
- Przegląd: 1 istotna (zatwierdzanie łącza ze świeżymi kluczami przy znanym adresie) i 5 drobnych uwag, poprawione (`doc/bond_repair_design.md` §7). Jedno świadome odstępstwo: commit naprawy nie zależy od trwania łącza.
- Do sprawdzenia z płytką: lista w `firmware/README.md`.

## Okno rejestracji z telefonu (plan `doc/plans/2026-10-28-phone-window.md`, projekt `doc/phone_window_design.md`)

Płytka może być zamknięta w obudowie sterownika głównego. Przycisk zostaje bez zmian, a dodatkowo zarejestrowany telefon z sesją READY otwiera to samo okno 60 s. Zgubiony telefon: reset przyciskiem po otwarciu skrzynki.

| Część | Zmiana | Testy (Mac) |
|---|---|---|
| `:protocol` | `MessageType.OPEN_PAIRING_WINDOW` (0x03), `AckResult.WINDOW_OPENED` (8), `SessionRequest.OpenPairingWindow`; wektory: wymiana w `commands.txt`, `ack_window_opened` | 209 (+3) |
| Firmware | aktor: komenda bez impulsu i zapisu NVS; `brama_auth_window_open_remote` (licznik PIN-u raz na godzinę), `brama_auth_pin_attempt_allowed` przed PAKE, licznik wyczerpany po starcie; INFO `0xFF`; log | aktor/auth/OTA 95 (+4), sesja/wektory (bez PAKE) |
| `:ble` | `GateInfo.supportsPhoneWindow`, `LinkInfo.pairingWindowSupported` | 118 (+3) |
| `:domain` | `openPairingWindow()`, `pairingWindowAvailable`/`pairingWindowSupported`, wspólna kolejka żądań (13.3), tekst `PAIRING_WINDOW_OPENED` (64 statusowe), zmienione WINDOW_CLOSED i BOND_MISSING | 302 (+9) |
| `:app` | „Otwórz okno rejestracji” w Ustawieniach (tylko przy bicie 7) | mapery 26 |

- Pełny build na Ubuntu przeszedł (wynik zgłoszony przez użytkownika): testy hostowe firmware, build `esp32`, testy i build wszystkich modułów Androida z lintem.
- Przegląd: 1 istotna (dodatkowa próba PIN-u przy każdym otwarciu okna z telefonu) i 5 drobnych uwag, poprawione (`doc/phone_window_design.md` §7).
- Specyfikacja: decyzja 5, notka 1.5, rozdz. 3, 4.1, 8.2, 9.1, 9.4, 11.4, 13.1, 13.2, A82.

## Etap 0 — zbiorcza lista do sprawdzenia na sprzęcie

Szczegóły w listach kontrolnych przy modułach; tutaj wszystkie otwarte sprawy w jednym miejscu.

- **Telefon i płytka (`:app`, `:ble`):**
  - lista kontrolna `:app` (8 punktów; A27, A58, A70–A73);
  - naprawa bondu (A33, A57): oba warianty adresu tożsamości, `T_attempt` a dialog parowania, brak dialogu przy zwykłym `connect` bez bondu;
  - czy zatrzymywać skan na czas łączenia z kandydatem (status 133 a limit 5 startów skanu na 30 s);
  - typ adresu sterownika (static random) przy łączeniu z wyniku skanu i z powiązania CDM;
  - przestarzałe callbacki obecności i `startObservingDevicePresence(String)` na API 36;
  - ponowne zgłaszanie obecności po zabiciu procesu;
  - filtr dokładnego adresu przy adresie prywatnym sterownika;
  - uruchomienie usługi z tła przy obecności (wyjątki CDM, API 34–36).
- **Android Auto (`:car`), DHU lub samochód:**
  - lista kontrolna `:car` (4 punkty; A74–A75);
  - `minCarApiLevel` dla kategorii IOT;
  - czy host pokazuje powiadomienia `CarAppExtender` aplikacji IOT i czy alarmuje przy kanale telefonu o niskiej ważności;
  - czy host sam usuwa powiadomienie (np. po dotknięciu akcji) — wtedy ukrywa je do końca pobytu;
  - klasyfikacja odświeżeń `PaneTemplate` przy zmianie liczby wierszy;
  - start usługi połączenia z tła po OPEN(true) z ekranu samochodu.
- **Napęd i instalacja (spec 17, Etap 0 pkt 1–2):** dokumentacja napędu, osobne wejścia OPEN/CLOSE, płytka i izolacja wyjść.

## Następne kroki

1. **Etap 0** — weryfikacja wykonalności według listy powyżej (wymaga płytki ESP32, telefonu, DHU lub samochodu).
2. **Firmware ESP32 na płytce** (Etap 2) — „sucha” implementacja gotowa; uruchomienie i pomiary według listy w `firmware/README.md` (w tym naprawa bondu, 9.5).
3. **OTA po stronie Androida** — zaimplementowana i zbudowana (sekcja wyżej); dalej test z płytką (A76–A81).
4. **Wektory zgodności** (Etap 1, spec 16.3) — przypadki negatywne dopisane i zielone po obu stronach. Wyjątek: zły PIN, jednorazowość `K_pin` i piąta próba PIN-u tylko w testach obu stron, bez wspólnych bajtów (rundy serwera EC-JPAKE z PSA).
5. **Etap 5** — integracja z napędem na instalacji (A62 i pozostałe testy akceptacyjne z napędem).
6. **Wydanie** — podpis release, `HostValidator` w release, przegląd jakości Google Play dla aplikacji samochodowych (spec 5.1).

### Poprawka: „Powiąż” nie może usuwać bondu (16.11.2026)

- **Błąd (regresja z usuwania bondu):** `CompanionAssociation.associate()` wywoływało `disassociateAll()`, które po zmianie z 16.11 usuwa też bond systemowy. Skutek na A16: po „Powiąż” znikał bond i trzeba było otwierać okno oraz naprawiać połączenie.
- **Poprawka:** `disassociateAll(removeBonds: Boolean = true)`; `associate()` wywołuje je z `removeBonds = false` (wymiana powiązania CDM, bond bieżącej bramy zostaje). Usuwanie bondu zostaje przy „Usuń z telefonu” i przy `restore()` bez poświadczenia.
- **Test karencji na A16:** pierwsze podejście nieważne: brak powiązania CDM (pominięty krok „Powiąż”), więc brak callbacków obecności.

### Test karencji obecności 180 s na A16 (16.11.2026)

- **Warunek:** A16 (CPH2791) z powiązaniem CDM (`Association mId=5`, `mNotifyOnDeviceNearby=true`) i bondem; logcat `a16_grace.log`. Pierwsze podejście (16:44–17:16) było nieważne: po ponownej rejestracji pominięto krok „Powiąż”, więc CDM nie zgłaszał obecności (wynik 16 min w zasięgu bez powtórek tam nie ma wartości).
- **Zwykły cykl:** powiadomienie po rejestracji o 16:44:04, wyciszenie po 5 min (`PRESENCE_QUIET_MS`) o 16:49:03; stan QUIET jest utrwalany i przeżywa reinstalację aplikacji.
- **Nieobecność ponad karencję:** zniknięcie 17:24:52 (CDM Event=1), powrót 17:31:02 (Event=0): jeden post powiadomienia, start usługi foreground, sesja po ok. 1 s.
- **Brak powtórek w zasięgu:** po wyciszeniu o 17:36:19 do 17:49:18 (13 min) brak postów, startów usługi i zdarzeń Event=0, mimo Lost/Found z CDM.
- **Wynik:** karencja działa zgodnie z założeniem (jedno powiadomienie na cykl, powrót po nieobecności > 180 s).
- **Znaleziona regresja:** „Powiąż” usuwało bond bieżącej bramy (naprawione wyżej). Osobno: nie zmierzono krótkiej nieobecności (< 180 s); logika (`GateRepository.kt:1453`) pokryta testami domeny.

### RAM i stosy przy wielu łączach (16.11.2026)

- **Narzędzie:** nowa komenda konsoli `mem` (sterta wolna/min/największy blok, minimalny zapas stosu zadań `actor`, `ui`, `pake_worker`, `ota_worker`, `nimble_host`, `btController`).
- **Pomiar, 0 łączy (świeży start):** sterta 40 544 B (min 36 616, blok 34 816); stosy: actor 7 652, ui 2 800, pake_worker 9 728, ota_worker 5 584, nimble_host 2 412, btController 2 100.
- **Pomiar, 3 łącza i rejestracja QR z udziałem pozostałych dwóch:** sterta 39 436–39 536 B (min 28 468, dołek z rundy PAKE ok. 8 KB); actor 3 892; pake_worker 7 360 (zużycie ok. 2,9 KB z 10 KB); **nimble_host 924 B z 4096**. Rejestracja (REPEAT_PAIRING, SC) nie zeszła poniżej tego minimum.
- **Wniosek, sterta:** koszt jednego łącza ok. 370 B (3 łącza = ok. 1,1 KB), więc 9 łączy ok. 3,3 KB; sloty łączy i bufory NimBLE są alokowane przy starcie i wchodzą w baseline. PAKE jest szeregowane (`s_pake_busy`), więc szczyt ok. 8 KB występuje jednorazowo, nie razy 9. Zapas po 9 łączach z PAKE szacunkowo ponad 25 KB.
- **Statyczny DRAM:** `g_links` = 69 192 B (ok. 7,7 KB/łącze), DRAM 98,9% (1 373 B wolne). Nowe statyki nie mieszczą się; trzeba je brać ze sterty.
- **Zmiana:** `CONFIG_BT_NIMBLE_HOST_TASK_STACK_SIZE=5120` w `sdkconfig.defaults` (+1 KB ze sterty, DRAM statyczny bez zmian: 123 207 B). Zapas hosta rośnie z 924 do ok. 1 950 B przy tym samym obciążeniu.
- **Nie zmierzono:** realnych 9 łączy naraz (3 telefony), rejestracji PIN-em ze wszystkimi aktywnymi, OTA przy kilku łączach. Do `sdkconfig` użytkownika zmiana trafi po jego usunięciu/regeneracji.
- **Weryfikacja stosu 5120 B na sprzęcie (16.11.2026):** obraz wgrany; po restarcie nimble_host 3 428 B zapasu (0 łączy), przy 3 łączach 1 940 B (wcześniej 924 B), sterta wolna 38 820 B. Wynik zgodny z oczekiwaniem (+1 KB). Runda PAKE w tym pomiarze nie była wykonywana (pake_worker 9 736 B zapasu).

### Dostępność rejestracji PIN-em: `info` i blokada w aplikacji (16.11.2026)

- **Powód:** po restarcie płytki licznik prób PIN-u jest wyczerpany do przycisku (okno z telefonu zeruje go najwyżej raz na godzinę pracy, phone_window_design §6.2); aplikacja pokazywała wtedy ogólny błąd „sprawdź PIN i czy okno jest otwarte”.
- **Firmware:** `brama_auth_pin_attempts_left()` (0 przy zamkniętym oknie albo wyczerpanym liczniku); komenda `info` wypisuje `rejestracja PIN-em: dostępna|niedostępna, próby PIN N/5, okno otwarte|zamknięte` (`brama_app_pin_status`, pod blokadą aktora); reklama BLE przy otwartym oknie dodaje pole producenta `0xFFFF` + 1 bajt liczby prób (poza oknem pola brak; reklama odświeżana przy zmianie okna i licznika). Test hostowy `pin_attempts_left_counts_down_and_is_zero_without_window`, 170/170. Statyczny DRAM bez zmian (123 207 B).
- **Prywatność:** pole w reklamie ujawnia obserwatorowi tylko, że okno rejestracji jest otwarte, i ile prób PIN-u zostało; nie zawiera device_id ani danych klientów. Okno i tak jest widoczne po tym, że sterownik przyjmuje parowanie bez filtra.
- **Aplikacja:** `GateAdvertisement.pinAvailability` (`:ble`, testy), `AndroidGateWatcher` (własny skan podczas ekranu „Dodaj bramę”, poza rejestracją w toku; po 5 s bez reklamy wraca `Unknown`, które niczego nie blokuje); `AddGateScreen` przy 0 prób wyłącza pole PIN i „Zarejestruj” z komunikatem (przycisk na bramie albo QR), przy 1–5 pokazuje „Pozostało prób PIN: N z 5”. Tekst błędu PIN dostał wskazówkę o przycisku po restarcie.
- **Test na sprzęcie:** `info` po restarcie pokazuje `niedostępna, 0/5, okno zamknięte`; komunikat blokady w aplikacji A13 przy oknie otwartym z A16 potwierdzony przez użytkownika, rejestracja A13 przeszła (QR). Nie sprawdzono jeszcze: odblokowania po przycisku (`Pozostało prób 5 z 5`) i rejestracji PIN-em po przycisku.
- **Potwierdzenie na sprzęcie (16.11.2026):** po restarcie `info` pokazuje `niedostępna 0/5`; okno z telefonu nie odblokowuje PIN-u, a aplikacja A13 blokuje pole PIN z komunikatem (QR działa). Po wciśnięciu przycisku `info`: `dostępna 5/5, okno otwarte`, komunikat znika, rejestracja A13 PIN-em przechodzi (nowy `client_id` klienta [2], zapisy NVS 2). Po wygaśnięciu okna znów `niedostępna 0/5, okno zamknięte`.
- **Nie sprawdzono:** odliczania w aplikacji po błędnych PIN-ach (4/5, 3/5…) i zamknięcia okna po 5 błędach na reklamie.

### A80: czas impulsu podczas kasowania i zapisu flash (17.11.2026)

- **Metoda (bez analizatora):** tymczasowy harness poza produktem (kopia `firmware/` w scratchpadzie, komenda `a80 <ms> <n>`): oba piny wyjść (GPIO 17 OPEN, 18 CLOSE) w trybie INPUT_OUTPUT, przerwanie zboczy w IRAM zapisuje `esp_timer_get_time()`, szerokość impulsu = różnica zboczy na pinie (do pinów podłączone diody LED, nie napęd). Impulsy z `brama_hw_outputs().start_pulse` (ta sama ścieżka co OPEN/CLOSE i auto-CLOSE aktora), na przemian OPEN i CLOSE, w różnych fazach względem pętli kasowanie 4 KB + zapis 4 KB na nieaktywnym slocie `ota_1` (zadanie na rdzeniu 1, najdłuższa operacja flash 60 ms).
- **Wyniki** (szerokość min / max, 2 rundy z flash; bez flash zawsze +0,02 ms):

| Impuls | Bez flash | Z flash, runda 1 | Z flash, runda 2 |
|---|---|---|---|
| 100 ms | 100,021 | 99,810 … 100,026 | 99,720 … 100,314 |
| 200 ms | 200,020 … 200,022 | 199,766 … 200,120 | 199,800 … 200,336 |
| 1000 ms | 1000,020 … 1000,022 | 999,774 … 1000,056 | 999,785 … 1000,024 |

  Wszystkie impulsy zmierzone (20/20, 25/25, 10/10); największa odchyłka +0,34 ms, najmniejsza −0,28 ms przy operacjach flash do 60 ms.
- **Wniosek:** koniec impulsu (timer `ESP_TIMER_ISR`, `CONFIG_ESP_TIMER_IN_IRAM`, `CONFIG_GPIO_CTRL_FUNC_IN_IRAM`) nie jest opóźniany przez kasowanie i zapis flash; A80 spełniony dla czasu trwania. Początek impulsu startuje z kontekstu zadania, więc może się opóźnić o czas trwającej operacji flash (do ok. 60 ms), szerokości to nie zmienia.
- **Nie sprawdzono:** równoległego ruchu BLE (3 telefony podczas OTA) i pomiaru zewnętrznym analizatorem; skutek uboczny testu: slot `ota_1` skasowany (do następnej aktualizacji OTA brak rollbacku).

### RTC przy restartach (17.11.2026)

- **Metoda:** tymczasowy harness poza produktem (kopia `firmware/` w scratchpadzie, komenda `rtctest <tryb>`): przed resetem zapisuje w `RTC_NOINIT` znacznik z wzorcem i czasem `esp_rtc_get_time_us()` oraz CRC32 prawdziwej kopii reguły (`s_rtc_copy`); po starcie (pierwsza linia `app_main`) sprawdza znacznik, wzorzec, zgodność CRC i różnicę licznika RTC.
- **Wyniki:**

| Reset | Powód | Znacznik i wzorzec | Kopia reguły (CRC) | Licznik RTC |
|---|---|---|---|---|
| `esp_restart` | SW | OK | zgodna | +0,83 s |
| `abort()` | PANIC | OK | zgodna | +0,85 s |
| zapis pod NULL (`StoreProhibited`) | PANIC | OK | zgodna | +0,90 s |
| interrupt WDT (300 ms) | INT_WDT | OK | zgodna | +1,25 s |
| przełączenie slotu OTA (`esp_ota_set_boot_partition` + restart przez bootloader) | SW | OK | zgodna | +1,72 s |
| przycisk EN | POWERON (`rst:0x1`) | BRAK (pamięć utracona) | inna | — |

  Pamięć RTC i licznik RTC przeżywają restart programowy, panic, interrupt WDT i zmianę slotu OTA; reset przyciskiem EN (i odłączenie zasilania) jest zimnym startem, więc `brama_hw_cold_start()` = true i reguła startuje jako DISARMED/REBOOT (14.5). Licznik rośnie ciągle (różnica = czas restartu), więc termin auto-CLOSE w czasie RTC jest zachowany przez restart.
- **Task watchdog nie resetuje płytki:** `CONFIG_ESP_TASK_WDT_PANIC` nie jest ustawiony, więc zawieszenie zadania (pętla 8 s w zadaniu o priorytecie 24 na rdzeniu 0) kończy się tylko logiem `task_wdt: Task watchdog got triggered` i płytka wraca do pracy bez resetu. Zawieszenie zadania aktora (nie tylko brak oddania procesora przez 5 s) nie spowoduje więc odzyskania przez restart; do decyzji, czy włączyć `ESP_TASK_WDT_PANIC` (reset po 5 s zawieszenia, kopia reguły przeżyje).
- **Nie sprawdzono:** brownoutu (nie da się wywołać bez zasilacza regulowanego) i restartu po prawdziwej aktualizacji OTA przez telefon (sprawdzono zmianę slotu przez bootloader).
- **Decyzja i zmiana (17.11.2026): task watchdog resetuje płytkę.** `CONFIG_ESP_TASK_WDT_PANIC=y` w `sdkconfig.defaults` (timeout bez zmian: 5 s, sprawdzane IDLE0 i IDLE1). Zawieszone zadanie kończy się panic i restartem; kopia reguły w RTC przeżywa taki reset (tabela wyżej). Build: DRAM statyczny bez zmian (123 207 B).
- **Weryfikacja braku fałszywych resetów:** na sprzęcie rejestracja PIN-em A13 (runda PAKE ok. 2–2,7 s w `pake_worker` o priorytecie wyższym niż IDLE) przeszła bez `task_wdt`, panic i restartu; nowy `client_id` klienta [2], zapis NVS 1, `info` po rejestracji `dostępna 5/5, okno otwarte`, sterta min 27 524 B, zapas stosu pake_worker 7 504 B.
- **Nie sprawdzono pod nowym watchdogiem:** weryfikacji podpisu na końcu OTA (`esp_ota_end`: SHA-256 i ECDSA w `ota_worker`) na pełnym obrazie przez telefon; jeśli trwa dłużej niż 5 s bez oddania procesora rdzeniowi 0, da reset zamiast zakończenia OTA. Do sprawdzenia przy najbliższym OTA przez aplikację.
- **OTA end-to-end pod task watchdogiem z resetem (17.11.2026):** płytka `0.1.0` (`ESP_TASK_WDT_PANIC=y`, timeout 5 s) → obraz testowy `0.1.1` (`CONFIG_APP_PROJECT_VER="0.1.1"`, scratchpad `fwota`), telefon A13 (CPH2251, API 33), plik z `/sdcard/Download`. UART (czas od startu płytki): BEGIN `OTA ACK 0x50 wynik=0` w 439,8 s, END po 859,0 s, czyli transfer ok. 419 s (ok. 7 min, wolniej niż ok. 3,5 min na A16 z 14.11). Weryfikacja podpisu na końcu: dwa wpisy `secure_boot_v1: Verification result 0` w 859,011 s i 859,951 s, `OTA ACK 0x52 wynik=0` w 859,961 s, czyli `esp_ota_end` trwa ok. 1 s, daleko poniżej 5 s. Brak `task_wdt`, panic i resetu w trakcie; po restarcie `SW_CPU_RESET` bootloader załadował `ota_1` (0x200000), `App version: 0.1.1`, `Secure version: 1`, klienci 3 bez zmian, A13 wrócił do łącza ok. 3,3 s po starcie aplikacji. Ryzyko z watchdogiem (weryfikacja podpisu > 5 s) nie wystąpiło.
- **Efekt uboczny:** płytka działa teraz na `0.1.1` ze slotu `ota_1` (obraz testowy, poza numerem wersji identyczny z 0.1.0).

### Losowość (RNG) na sprzęcie (17.11.2026)

- **Użycie w kodzie:** `esp_fill_random` (`brama_hw_rng`) dla nonce sesji, `K_client` i parametrów PAKE; `bootloader_random_enable/disable` wokół generowania sekretów fabrycznych (`device_id`, `K_setup`, PIN) w konsoli (`service_console.c`); PIN losowany z odrzucaniem (test hostowy `pin_is_uniform_with_rejection`). Klucze NimBLE (SM) i mbedTLS korzystają z własnych źródeł ESP-IDF (RNG kontrolera/sprzętowy).
- **Metoda:** tymczasowy harness poza produktem (kopia `firmware/`, komenda `rngtest <tryb> <MB>`): 4 MiB z `esp_fill_random` w każdym stanie, statystyki liczone na urządzeniu: monobit (z), runs (z), chi-kwadrat histogramu bajtów (255 st. swobody; oczekiwane ok. 255, zakres ok. 200–310), korelacja seryjna bajtów (r), identyczne kolejne słowa 32 b.
- **Wyniki:**

| Stan | monobit z | runs z | chi² | r | identyczne słowa |
|---|---|---|---|---|---|
| RF włączony (BLE działa) | −1,66 | +1,29 | 245,5 | +0,00053 | 0 z 1 048 576 |
| RF włączony + `bootloader_random_enable` | −1,61 | +0,47 | 243,6 | −0,00005 | 0 z 1 048 576 |
| RF wyłączony (BLE zatrzymane), bez `bootloader_random` | −1,76 | +1,11 | 287,3 | −0,00007 | 0 z 1 048 576 |
| RF wyłączony + `bootloader_random_enable` (jak provisioning w trybie serwisowym) | −0,60 | +1,10 | 238,4 | −0,00113 | 0 z 1 048 576 |

  (Wcześniejsza próba 1 MiB z RF: z=+0,42 / −0,53, chi² 247,8, r=+0,00213.) Wszystkie wartości mieszczą się w zakresach dla losowego strumienia (|z| < 4, r w granicach kilku σ ≈ 0,0005 dla 4 MiB); przepustowość ok. 100 KB/s (ograniczona przez `esp_fill_random`, nie zależy od RF).
- **Wniosek i ograniczenia:** testy statystyczne nie wykryły błędu w żadnym z czterech stanów, włącznie z wywołaniem `bootloader_random_enable/disable` przy działającym BLE (bez zawieszenia i resetu). Nie dowodzą jednak entropii: stan bez RF i bez `bootloader_random` też przechodzi testy, więc o prawdziwej losowości przesądza dokumentowane zachowanie ESP-IDF (RF albo `bootloader_random`), a nie ten pomiar. Zmiana dotycząca produktu: brak; provisioning zostaje z `bootloader_random_enable`.
- **Przy okazji:** pierwsza wersja harnessu (pętla 4 MiB bez oddawania procesora) wywołała task watchdog z resetem (`task_wdt: Aborting`, `SW_CPU_RESET`): dodatkowe potwierdzenie działania `ESP_TASK_WDT_PANIC=y`. Wywołania `esp_fill_random` w produkcie dotyczą małych buforów (≤ kilkaset bajtów), więc nie zbliżają się do progu 5 s.

### Kodowanie eFuse: pominięte (17.11.2026)

Decyzja właściciela: sprawdzanie kodowania eFuse (schemat kodowania, produkcyjna konfiguracja bezpieczeństwa układu) jest pominięte na etapie testów deweloperskich. Wraca przed wydaniem produkcyjnym razem z pozostałymi punktami 15.3 (klucz podpisu, Flash Encryption, Secure Boot).

### Zerwanie OTA przez drugi telefon: diagnoza i obejście (17.11.2026)

- **Objaw (z 15.11):** drugi telefon łączy się w trakcie transferu OTA → łącze OTA pada (wtedy MIC failure `0x3d`). Dziś odtworzone w innej postaci, trzy razy na firmware 0.1.0 (harness z komendą `bt`, `esp_backtrace_print_all_tasks`):
  1. po dołączeniu A16: `blokada aktora/BLE zajęta ponad 2 s, trzyma 'ota_worker'` dla `actor`, `ui`, `nimble_host`, UART milczy, płytka nie resetuje się (task WDT widzi tylko IDLE). **Przyczyna niewyjaśniona**, wystąpiło raz, nie udało się go powtórzyć, więc nie mam `bt` z zawieszonego stanu.
  2. po cyklach dołączenia/rozłączenia A16 w trakcie transferu (A13 robi OTA, 209 s): `reset hosta NimBLE: 12` (`BLE_HS_ECONTROLLER`) 0,94 s po rozłączeniu drugiego łącza; wszystkie łącza padają (`disconnect reason=0xc`), `transfer OTA przerwany`.
  3. automatycznie (A16 robi OTA, A13 start/zatrzymanie aplikacji co 12 s przez adb, skrypt `cycle.sh`): ten sam reset 0,75 s po rozłączeniu łącza dołączonego w trakcie OTA (532,9 s).
- **Co wiadomo o resecie:** `ble_hs_hci_evt_num_completed_pkts` (`ble_hs_hci_evt.c:652`) wywołuje `ble_hs_sched_reset(BLE_HS_ECONTROLLER)`, gdy kontroler zgłasza więcej ukończonych pakietów dla łącza niż host ma w toku (`bhc_outstanding_pkts`; host liczy poprawnie, inkrementacja przed `ble_hs_tx_data`); drugie miejsce to `ble_hs_flow.c:168` (więcej niż `ACL_FROM_LL_COUNT`=24 niepotwierdzonych pakietów od kontrolera). Które z nich zadziałało, nie rozstrzygnięto (blob kontrolera ESP32 nie jest do podejrzenia, brak logu licznika). Samo **rozłączenie** drugiego łącza, które istniało przed BEGIN (A13 połączony przed startem OTA, rozłączony po 23 s transferu), **nie** psuje OTA (transfer szedł dalej).
- **Obejście (firmware):** w trakcie transferu OTA (`brama_ota_t.active`) sterownik nie reklamuje się (`brama_ble_refresh_advertising`), więc żadne nowe łącze nie powstaje; łącze przyjęte w wyścigu tuż przed BEGIN jest zrywane od razu (`OTA w toku: odrzucenie nowego łącza`). Reklama wraca po END, ABORT lub porzuceniu transferu (odświeżana w `actor_task` przy zmianie `s_ota.active`). Efekt dla użytkownika: przez czas OTA (3,5–7 min) drugi telefon nie może się połączyć.
- **Weryfikacja:** A16 robi OTA, A13 przez 15 cykli (3 min) próbuje się połączyć: brak resetu hosta, brak `blokada`, brak `przerwany`; transfer trwał ok. 244 s do ręcznego `OTA_ABORT` (ACK `0x53` wynik 0 w 273,05 s), po nim od razu `stop advertising` + `advertise` (reklama wróciła). Aplikacja A13 podczas OTA nie może się połączyć (zamierzone), więc nie widzi przyczyny.
- **Nie rozwiązane / do rozważenia:** (a) zawieszenie na blokadzie `ota_worker` (pkt 1); (b) komunikat w aplikacji drugiego telefonu („trwa aktualizacja bramy z innego telefonu”): wymagałby reklamy niełączalnej z flagą OTA; (c) `ota_worker` trzyma wspólną blokadę podczas zapisu flash, co wydłuża postoje hosta NimBLE.
- **OPEN/CLOSE podczas OTA (17.11.2026, potwierdzenie użytkownika):** komendy OPEN i CLOSE wysyłane w trakcie trwającego transferu OTA działają (impuls wychodzi). Szczegóły pomiaru (który telefon, firmware, czasy impulsów) nie zostały zapisane.
- **Decyzja właściciela (17.11.2026): komunikat o OTA w aplikacji drugiego telefonu pomijamy.** Powód: aktualizacja OTA jest wykonywana sporadycznie, przez głównego użytkownika przy bramie, więc brak połączenia drugiego telefonu na 3,5–7 min nie jest problemem. Reklama niełączalna z flagą OTA nie jest implementowana.

### Resolving lista z IRK i `failed to configure restored IRK` (17.11.2026)

- **Skąd komunikat:** `ble_hs_misc_restore_one_irk` (NimBLE) przy synchronizacji hosta dodaje IRK zatwierdzonych bondów do resolving listy kontrolera (`ble_hs_pvcy_add_entry`: `LE Add Device To Resolving List` + `LE Set Privacy Mode`); u nas po jednym wpisie na bond (3 przy 3 klientach).
- **Kiedy występuje (logi z całego dnia):** 0 razy przy zwykłym starcie i restartach (`reboot`, panic, WDT, OTA); 6 razy (2 × 3 bondy) wyłącznie po **resecie hosta NimBLE** (`reset hosta NimBLE: 12`, ten sam mechanizm co zerwanie OTA przy drugim telefonie).
- **Pomiar kodu (harness `hsreset` + log `ble_hs_pvcy_add_entry` w `on_sync`):** zwykły start: `rc=0` dla trzech bondów; po wymuszonym resecie hosta: `rc=3` dla wszystkich trzech (w `ble_hs_hci_cmd_tx` to status HCI zwrócony przez kontroler, `rc = ack.bha_status`; dokładnego znaczenia dla tego kontrolera nie ustalono: możliwe, że wpisy po resecie hosta już są na liście kontrolera i dodanie duplikatu jest odrzucane, tego nie sprawdzono).
- **Skutek funkcjonalny: brak.** W żadnym z logów (także po zwykłym starcie z `rc=0`) kontroler nie rozwiązuje RPA telefonów: `peer_id` równa się `peer_ota` (adres RPA `typ=1`, np. `49:58:d7:xx:xx:xx`). Rozpoznanie bondu załatwia własne rozwiązywanie IRK w firmware (`brama_ble_resolve_identity`): telefon z RPA po resecie hosta łączy się, jest rozpoznany (`peer ze znanym bondem`), szyfruje i dochodzi do sesji. Lista rozwiązywania kontrolera jest więc w praktyce nieużywana; błąd po resecie hosta nie zmienia zachowania względem zwykłego startu.
- **Nie sprawdzono:** czy wpisy po resecie hosta już istnieją w kontrolerze (próba usunięcia i ponownego dodania); czy przy większej liczbie bondów (8) ograniczenie listy kontrolera daje inne zachowanie; accept lista z filtrem nadal nie działa na tym kontrolerze (brak `LE Set Privacy Mode` w BT 4.2, wcześniejszy wpis).

### Przegląd kodu zmian z 16–17.11.2026 (`/code-review`, poziom medium) i poprawki

- **Poprawione:**
  1. `ota_worker`: transfer zerwany w workerze (zły offset, odrzucony obraz, błąd zapisu) wyłączał `active` poza wiadomością aktora, więc reklama (wyłączona na czas OTA) nie wracała do rozłączenia telefonu; teraz po `brama_ota_data` przy `!active` jest `brama_ble_refresh_advertising()`.
  2. `CompanionAssociation.associate()`: stare powiązania CDM są usuwane dopiero w `onAssociationCreated` (anulowanie okna systemowego albo błąd nie zostawia użytkownika bez powiązania); `disassociateAll()` wróciło do jednej wersji (zawsze usuwa też bondy; używane przy usunięciu bramy i przy `restore()` bez poświadczenia).
  3. `AndroidGateWatcher`: dostępność PIN liczona per urządzenie (wygasa po 5 s), wynik z `GateAdvertisement.aggregate` (blokada tylko, gdy wszystkie bramki z polem mają 0 prób; sąsiednia bramka nie blokuje rejestracji na własnej), `onScanFailed` → `Unknown`, dostęp do stanu pod `synchronized` (3 nowe testy `GateAdvertisementTest`).
  4. `on_encrypted`: `d` zerowane przed `ble_gap_conn_find` (log przy błędzie nie czyta niezainicjowanych bajtów).
- **Odrzucone po sprawdzeniu:** „PAKE worker czyta stan aktora bez blokady”: rundy `PAKE_ROUND1/2` wołają tylko `attempt_blocker` i `window_open` (opakowane blokadą); `pair_admit`, `client_key`, `session_admit` działają przy BEGIN, poza workerem.
- **Zostaje do decyzji (nie zmieniane):** konsola zapisuje NVS poza aktorem (`remove-client` wymaga reboot; `reprovision` drukuje QR niezgodny z żywą bramką i nie każe zrobić reboot); `bootloader_random_enable` przy działającym BLE w `reprovision` (test RNG nie wykazał problemu); po rundzie PAKE worker nadpisuje sesję bez sprawdzenia, czy łącze nadal jest zaszyfrowane i zatwierdzone (rzadkie); okno z telefonu przedłużane bez limitu łącznego czasu (zgodne z `phone_window_design`); drugi skan watchera obok skanu radia (limit startów skanu Androida).
- **Weryfikacja:** testy `:ble`, `:domain`, `:app` przechodzą, firmware buduje się (DRAM statyczny bez zmian, 123 207 B), testy hostowe 170/170. Poprawki nie były jeszcze sprawdzane na sprzęcie ani na telefonach (aplikacja niezainstalowana, firmware niewgrany).

### Drugi przegląd kodu (po poprawkach) i dalsze poprawki (17.11.2026)

- **Poprawione:** (1) `set_adv_data`: jeden odczyt czasu dla sprawdzenia okna i liczby prób (okno nie może wygasnąć pomiędzy, co dałoby `0` = twarda blokada zamiast braku pola); (2) `on_sync` nie woła już `set_adv_data()` osobno (robi to `brama_ble_refresh_advertising()` pod blokadą BLE), a błąd `ble_gap_adv_rsp_set_fields` jest logowany; (3) `GateAdvertisement.pinAvailability` czyta pierwszy bajt pola i ignoruje dalsze (przyszłe rozszerzenia pola nie wyłączą blokady w starej aplikacji; test `extraBytesAfterCounterAreIgnored`); (4) `AndroidGateWatcher` publikuje stan tylko przy zmianie stanu bramki (reklamy co 100–200 ms nie przeliczają agregatu), wygasanie robi tykanie co 2,5 s.
- **Odrzucone po sprawdzeniu:** „okno wygasa bez odświeżenia reklamy”: `on_tick` zamyka okno, a następne tykanie jest planowane na `window_until_ms` (`actor.c:104`), więc `actor_task` widzi zmianę `window_open` i odświeża reklamę; „filtr CDM zakłada adres publiczny, dopasowanie niepotwierdzone”: powiązanie CDM dla bramki powstało na sprzęcie (A16, `Association mId=5`, 17.11), więc dopasowanie działa.
- **Przyjęte bez zmian (stary kod albo mały zysk):** `setDeviceAddress` poza `runCatching` i `associate()` zwracające tylko `isSuccess` (stary kod, adres z `BluetoothDevice.address` jest kanoniczny); brak ponownego skanu watchera, gdy Bluetooth był wyłączony przy otwarciu kreatora (do rejestracji i tak potrzebny); brak testów jednostkowych samego skanu Androida (logika agregacji jest testowana w `:ble`).
- **Weryfikacja:** testy `:ble`, `:domain`, `:app` przechodzą, firmware buduje się. Na sprzęcie niesprawdzone.
- **Sprawdzenie na sprzęcie po poprawkach (17.11.2026):** firmware z poprawkami (ota_1) i aplikacja zainstalowane na A13 i A16. Rejestracja A13 z kreatora „Dodaj bramę”: po restarcie `niedostępna 0/5`, po przycisku okno otwarte; po dwóch nieudanych PIN-ach `info` pokazał `dostępna, próby PIN 3/5, okno otwarte`, a poprawny PIN dał nowego klienta [2] (`client_id ddcd2797…`, zapis NVS 1); w logu brak `task_wdt`, panic i resetu hosta. Nie sprawdzono wzrokowo odliczania w aplikacji (5→4→3) ani zamknięcia okna po 5 błędach.
- **Potwierdzenie użytkownika (17.11.2026):** licznik w aplikacji spada 5 → 4 → 3 po błędnych PIN-ach, wszystko działa poprawnie.

### Uproszczenie ekranu głównego i powiadomienia (17.11.2026, decyzja właściciela)

- **Ekran główny:** pod przyciskami OTWÓRZ/ZAMKNIJ nie ma już tekstów statusów (`MainUi.details` usunięte, `MainScreen` bez listy szczegółów). Zostają: stan połączenia i przycisk Połącz/Rozłącz u góry, przyciski, „Napraw połączenie”, „Dodaj bramę” i przycisk uprawnień.
- **Powiadomienie usługi:** tytuł (nazwa bramy), jedna linia ze stanem połączenia (`Messages.connection`) i przyciski OTWÓRZ/ZAMKNIJ (jak dotąd tylko przy obecności i READY). Usunięte linie: pozycja, reguła, wynik komendy, postęp i wynik firmware, problemy systemowe i handshake oraz ostrzeżenie o uzbrojeniu przy rozłączeniu (spec 4.3 pkt 4).
- **Skutki do wiedzy:** błędy i wyniki nie są już widoczne w tych miejscach (m.in. instrukcja przy braku bondu, wynik BUSY/interlock/„wynik nieznany”, komunikat BLUETOOTH_OFF, postęp OTA po wyjściu z ekranu). Historia i ekran aktualizacji firmware pokazują je nadal. Spec 4.2/4.3 i `app_design` §3.1 wymagają aktualizacji zgodnie z tą decyzją (nie zrobiono).
- **Weryfikacja:** testy `:app` przechodzą (zaktualizowane `MainModelTest` i `NotificationModelTest`), aplikacja zainstalowana na A13 i A16; wygląd nie był jeszcze oceniany na telefonie.

### Nowy wygląd ekranu głównego i motyw (17.11.2026, propozycja do oceny)

- **Układ:** zawartość wyśrodkowana w pionie i poziomie (kolumna o wysokości co najmniej ekranu, więc środkowanie działa też przy przewijaniu): grafika bramy, „pigułka” ze stanem połączenia i kolorową kropką, Połącz/Rozłącz, duże zaokrąglone OTWÓRZ i ZAMKNIJ (max 360 dp szerokości), przezroczysty pasek tytułu na gradientowym tle.
- **Grafika:** `GateIllustration` rysowana w Compose (bez zasobów): dwuskrzydłowa brama wjazdowa z murkami, słupkami z daszkami i lampkami w kolorze stanu, ukośnymi zastrzałami; przy łączeniu tło pulsuje. Pierwsza wersja (słupki z kulami i szczebelki) wyglądała jak łóżeczko dziecięce i została przerysowana po obejrzeniu zrzutów ekranu.
- **Kolory:** motyw marki w `Color.kt`/`Theme.kt` (turkus `#0E7C86`, granat, zieleń `#1F9D55` dla OTWÓRZ, granat/niebieski dla ZAMKNIJ, bursztyn dla łączenia, szary dla braku połączenia), jasny i ciemny; kolory dynamiczne systemu wyłączone, więc motyw dotyczy wszystkich ekranów aplikacji.
- **Poprawka z oglądu zrzutów:** tekst w „pigułce” był czarny na ciemnym tle (kolor treści z `Surface` dla koloru spoza schematu), teraz `onSurface`; ZAMKNIJ w trybie ciemnym wyglądał na wyszarzony, teraz niebieski `#3E7CA3` z białym tekstem.
- **Weryfikacja:** zrzuty z A13 w trybie ciemnym i jasnym oraz z A16; testy `:app` przechodzą. Nie zmieniono ikony aplikacji ani układu pozostałych ekranów (poza kolorami motywu).
- **Ikona aplikacji (17.11.2026):** adaptacyjna ikona w stylu aplikacji zastępuje domyślną ikonę szablonu Android: tło z gradientu granatowego (`ic_launcher_background`), pierwszy plan z bramą wjazdową (murki, słupki z daszkami i zielonymi lampkami, dwa skrzydła z ukośnymi zastrzałami w turkusie, `ic_launcher_foreground`), warstwa monochromatyczna dla ikon motywowanych (`ic_launcher_monochrome`), ta sama dla `ic_launcher_round`. Powiadomienie dostało własną jednokolorową ikonę `ic_stat_gate` (wcześniej używało pierwszego planu ikony, który jako ikona małego rozmiaru dawałby bryłę). Grafika wektorowa, bez plików rastrowych; stare pliki `mipmap-*dpi/*.webp` zostają (minSdk 33 używa wersji adaptacyjnej). Obejrzane na ekranie głównym A13 (pierwsza wersja miała zbyt cienkie linie, pogrubiona po oglądzie); ikona małego powiadomienia nie była oglądana.

### Pozostałe ekrany w nowym stylu (17.11.2026)

- **Wspólne elementy** (`Common.kt`): tło gradientowe (`brandBackground`), przezroczysty pasek z wyśrodkowanym tytułem (`BackBar`), kolumna ograniczona do 560 dp (`ScreenColumn`), karty sekcji z tytułem w kolorze marki (`SectionCard`), akcje `PrimaryAction`/`SecondaryAction`/`DangerAction` (zaokrąglone 20 dp), nagłówek z grafiką bramy (`IllustrationHeader`); `GateIllustration` ma parametr rozmiaru.
- **Dodaj bramę:** grafika (w trakcie rejestracji pulsuje, po sukcesie zielona), karta z instrukcją i komunikatem błędu w kolorze błędu, główny przycisk „Skanuj QR”, osobna karta „Albo wpisz PIN” z licznikiem prób i polem PIN, przycisk „Zarejestruj”; „Powiąż” jako akcja tonalna.
- **Ustawienia:** karty „Działanie”, „Brama”, „Firmware”, „Rejestracja telefonów”, „Reset autoryzacji sterownika”; „Usuń z telefonu” jako kontur w kolorze błędu.
- **Historia:** karty z kolorową kropką (zielona OTWÓRZ, niebieska ZAMKNIJ), pusty stan z grafiką bramy. **Aktualizacja firmware:** grafika (pulsuje w trakcie), karta z wersją i postępem (gruby pasek), akcje w nowym stylu.
- **Motyw:** dopisane kolory kontenerów i konturów (tonalne przyciski były fioletowe, bo schemat ich nie definiował).
- **Weryfikacja:** zrzuty z A13 (ustawienia, historia, tryb ciemny); testy `:app` przechodzą; aplikacja zainstalowana na A13 i A16. Nie oglądano ekranów „Dodaj bramę” i aktualizacji firmware ani trybu jasnego dla ustawień i historii.
- **Poprawki po rejestracji na sprzęcie (17.11.2026):** grafika i napis „Rejestruję…” są wyśrodkowane w pionie (`ScreenColumn` z `verticalArrangement`, wypełnia ekran tylko w trakcie rejestracji); tekst poza kartami był czarny na ciemnym tle, bo `Scaffold` z przezroczystym tłem ustawia kolor treści na czarny: wszystkie ekrany mają teraz `contentColor = onBackground` (dotyczyło też pustej historii, podpowiedzi w ustawieniach i napisu w kreatorze).
- **Biały ekran na A13 po rejestracji (17.11.2026):** aplikacja nie padła (brak wyjątku w logcacie, proces żywy), ale `ComposeView` był pusty, a Wstecz kończył się `moveTaskToBack`, czyli stos nawigacji był pusty: drugi `popBackStack()` zdjął ekran główny (stary kod: `onDone`/`onBack` wołały `nav.popBackStack()` bez sprawdzenia; podwójne dotknięcie „Gotowe” albo „Gotowe” + Wstecz). Przyczyna wywnioskowana z logu i hierarchii UI, nie odtworzona. Poprawka: w `BramaNav` wspólne `back`, które zdejmuje ekran tylko, gdy istnieje poprzedni (`previousBackStackEntry != null`); użyte w „Dodaj bramę”, ustawieniach, aktualizacji firmware i historii. Restart aplikacji naprawia stan (stan jest w domenie).
- **Potwierdzenie użytkownika (17.11.2026):** rejestracja A13 przeszła, ekran rejestracji (grafika wyśrodkowana, napis czytelny) działa dobrze.

### Aktualizacja `app_design`, spec i `firmware_design` (17.11.2026)

- `app_design.md`: §2 (ekran główny bez tekstów statusów, powiadomienie z jedną linią i przyciskami), §3 (komunikaty nie są wyświetlane; dostępność PIN w kreatorze; usuwanie bondu przy rejestracji przerwanej przed COMMIT i „Usuń z telefonu”; powrót na ekran główny), §4 (powiadomienie, mała ikona), §13 (powiązanie CDM zastępowane po utworzeniu nowego), §14 (bez wiersza OTA w powiadomieniu), nowy §15 „Wygląd” (motyw, grafika, ikona, wspólne elementy, nawigacja).
- `PLAN_I_SPECYFIKACJA_BRAMY.md`: 4.1 (ekran sterowania, kreator z dostępnością PIN i usuwaniem bondu), 4.3 (powiadomienie bez dodatkowych tekstów, bez ostrzeżenia o uzbrojeniu), 8.3 (wiersz „OTA w toku”, pole producenta z liczbą prób PIN), 11.4 (dostępność prób PIN), 15.4 (`info`, `mem`), 15.5 (brak reklamy w trakcie transferu).
- `firmware_design.md`: nowy §7 „Zmiany po testach na sprzęcie” (MAX_BONDS, stos hosta, PAKE poza blokadą, task watchdog, OTA, reklama, znane niewyjaśnione).
- **Nie zaktualizowano:** testów akceptacyjnych w rozdziale 18 (m.in. A71 wspomina ostrzeżenie o uzbrojeniu w powiadomieniu), `ota_design.md` i `phone_window_design.md`.
- **Testy akceptacyjne (rozdział 18, 17.11.2026):** zaktualizowane A60 (teksty na ekranach i w Historii), A70 (powiadomienie: jedna linia i przyciski), A71 (bez ostrzeżenia w powiadomieniu), A76 (postęp na ekranie aktualizacji, weryfikacja podpisu < 5 s), A77 (drugi telefon przed BEGIN i w trakcie transferu), A80 (pomiar i wynik); nowe A83 (dostępność PIN), A84 (bond systemowy telefonu, limit bondów), A85 (OTA a drugi telefon), A86 (restarty i RTC), A87 (zasoby), A88 (wygląd i treść ekranów), A89 (losowość). Nadal niewykonane na sprzęcie: A87 z dziewięcioma telefonami i A88 w pełni (tryb jasny, ekran firmware).
- **`ota_design.md` i `phone_window_design.md` (17.11.2026):** `ota_design.md`: §5.5 (drugi telefon w trakcie transferu), §6 (powiadomienie bez wiersza aktualizacji, wygląd ekranu aktualizacji), §8 (decyzja 9), nowy §10 z wynikami na sprzęcie (OTA pod task watchdogiem, A80, zerwanie przez drugi telefon i obejście, nierozstrzygnięte zawieszenie). `phone_window_design.md`: §4 (pole w reklamie i `info`), §5 (kreator z dostępnością PIN), §6 pkt 2 (licznik liczony od startu płytki), nowy §8 z wynikami na sprzęcie (trzy przyczyny błędów naprawy bondu, PAKE poza blokadą, PIN po restarcie, uwaga o przedłużaniu okna).
- **`domain_design.md` i `ble_design.md` (17.11.2026):** `domain_design.md`: §5 (ekran główny i powiadomienie nie wyświetlają `messages`), §6.2a (karencja 180 s i wyniki pomiaru), §6.4 pkt 8 (tekst błędu PIN), §6.5 (bond i powrót na ekran główny). `ble_design.md`: §4.1 (`removeBond`), nowe §4.6 (bond systemowy po nieudanej rejestracji, ryzyko ukrytego API, testy) i §4.7 (`GateAdvertisement`, `PinAvailability`, `AndroidGateWatcher`).
- **`bond_repair_design.md` i `pin_presence_design.md` (17.11.2026):** `bond_repair_design.md`: §3.1 (czyszczenie listy rozwiązywania po nieukończonej rejestracji), §4.3 (ekran główny bez tekstu problemu: instrukcja `BOND_MISSING` niewidoczna, luka do decyzji), nowy §8 z wynikami na sprzęcie (limit bondów 3→9, resolving lista, bond systemowy). `pin_presence_design.md`: §5.1 (tekst błędu i dostępność prób), §5.3 (karencja 180 s w automacie, zawartość powiadomienia), nowy §8 (EC-JPAKE na ESP-IDF 6.0.1, limit prób PIN, karencja zmierzona).
- **Instrukcja pod przyciskiem „Napraw połączenie” (17.11.2026):** `MainUi.repairHint` (tekst problemu domeny `BOND_MISSING` / `REPAIR_BOND_FAILED`, tylko przy `bondRepairAvailable` i poświadczeniu różnym od NONE) wyświetlany wyśrodkowany pod przyciskiem naprawy; jedyny tekst statusu na ekranie głównym. Test w `MainModelTest`; `bond_repair_design.md` §4.3 zaktualizowany. Wygląd nie był oglądany na telefonie (wymaga braku bondu w telefonie przy działającym poświadczeniu).
- **Ekran naprawy bondu obejrzany na A13 (17.11.2026):** po „Zapomnij” bond w ustawieniach Bluetooth ekran główny pokazuje stan „Brak połączenia”, Połącz, „Napraw połączenie” i pod nim instrukcję (`BOND_MISSING`); przyciski OTWÓRZ/ZAMKNIJ przygaszone. W pierwszej wersji ZAMKNIJ wychodził poza ekran, więc przy naprawie (`MainUi.repairBond`) grafika (120 dp zamiast 176) i przyciski (92 dp zamiast 112) są mniejsze: wszystko mieści się bez przewijania. Sama naprawa (przycisk sterownika 3 s i „Napraw połączenie”) nie była w tym teście wykonana.
- **Naprawa bondu na A13 po zmianach z 17.11.2026 (sprawdzona w logu):** po „Zapomnij” bond (ustawienia Bluetooth) telefon łączył się bez bondu: sterownik wysłał Security Request (`peer ze znanym bondem`, adres RPA `typ=1`), szyfrowanie nie powiodło się (`enc_change status=7`), płytka rozłączyła łącze (`reason=0x216`), aplikacja pokazała „Napraw połączenie” z instrukcją. Po przycisku sterownika (3 s) i „Napraw połączenie”: `REPEAT_PAIRING`, `IDENTITY_RESOLVED`, `ENC_CHANGE` bez błędu, subskrypcja, MTU, wymiana ramek sesji; zapisy NVS wzrosły do 4 (naprawa zatwierdza nowy bond klienta [2], `client_id` ten sam co po ostatniej rejestracji). W logu 0 wpisów `reset hosta`, `task_wdt`, `Guru`, `blokada`.
- **Projekt zarządzania telefonami na bramie (18.11.2026, do zatwierdzenia):** `doc/client_management_design.md`. Lista telefonów w aplikacji (nazwa, „ten telefon”, „połączony”, „zarządza bramą”), usuwanie innego telefonu i zmiana własnej nazwy. Nowe typy `04` `LIST_CLIENTS`, `05` `REMOVE_CLIENT`, `06` `SET_CLIENT_NAME`, `82` `CLIENT_LIST`, wyniki ACK `09`–`0D`; nazwa (≤ 20 B) w rekordzie klienta (schemat obrazu 2 bez migracji, wariant (a) wybrany przez właściciela). Usuwanie bez zmiany `auth_generation` (reguła i inne sesje działają dalej), odmowa usunięcia siebie i właściciela uzbrojonej reguły. Decyzja właściciela: bez zgodności wstecznej i migracji (1 płytka, 3 telefony), wdrożenie przez reset i ponowną rejestrację; INFO bez zmian. Zmienia dotychczasową decyzję „zgubiony telefon tylko resetem przyciskiem”. Kod i specyfikacja nie zmienione; czekają na zatwierdzenie decyzji z §9.
- **Specyfikacja i dokumenty zarządzania telefonami (18.11.2026):** projekt `doc/client_management_design.md` zatwierdzony (decyzje §9 przyjęte). Spec `PLAN_I_SPECYFIKACJA_BRAMY.md`: nota decyzji, decyzja 12, teksty (usuwanie telefonu, wyniki), 4.1 (Ustawienia), 9.1, 9.4 (lista i odwołanie, nazwa), 13.1 (typy `04`–`06`, `82`), 13.2 (wyniki `09`–`0D`, `04` używany), 15.2/15.2.1 (zapisy NVS), 15.4 (schemat 2 bez migracji), 16.3, testy A90–A93, 19. Zaktualizowane: phone_window_design (§9), vectors_design (§3.3a), app_design (§3, §16), domain_design (§11), ble_design (§9), firmware_design (§8). Kod bez zmian; następny krok: firmware (schemat 2, typy, aktor).
- **Review dokumentacji zarządzania telefonami (18.11.2026):** poprawione: (1) odwołanie do `brama_image_find` w spec 9.4 zastąpione opisem (15.2.1 mówi o aktualnym poświadczeniu); (2) „właściciel reguły” obejmuje WAITING/BOOT_WAIT/BLOCKED/FAULT z właścicielem (`has_owner`), a odmowa dla zgubionego telefonu jest przejściowa do autozamknięcia (ok. 30 s), teksty doprecyzowane; (3) wyniki `09`–`0D` poza `REMOVE_CLIENT`/`SET_CLIENT_NAME` to błąd zgodności; (4) A59 z wyjątkiem nowych komend; (5) procedura wdrożenia: obraz schematu 1 daje tryb błędu, reset przyciskiem 10 s plus `reboot` (`reset_restores_control = false`), bez `erase-flash` (kasuje dane fabryczne) — do potwierdzenia na sprzęcie; (6) liczba fragmentów listy (21 przy budżecie 17 B), lista dokumentów i drobne redakcje w projekcie.
- **Firmware zarządzania telefonami, część 1 (18.11.2026; testy hosta 187/187, build `esp32` bez błędów):** `brama_auth` schemat obrazu 2 (nazwa ≤ 20 B w rekordzie klienta, `brama_client_name_valid`: UTF-8 bez overlong, surogatów i znaków sterujących C0/DEL/C1; `commit` odrzuca niepoprawną nazwę; obraz schematu 1 to „uszkodzony”, bez migracji); bufory `load`/`commit` przeniesione na stertę (schemat 2 przepełnił statyczny DRAM o 160 B). `brama_proto`: typy `04`–`06`, `82`, wyniki ACK `09`–`0D`, `brama_msg_info`. `brama_actor`: `LIST_CLIENTS`, `SET_CLIENT_NAME`, `REMOVE_CLIENT` (kolejność odmów jak w projekcie; `auth_generation`, reguła i sesje innych telefonów bez zmian; komendy usuniętego klienta zakolejkowane przed commit są odrzucane; callback `client_removed`). `brama_ble`: `brama_ble_client_removed` (`ble_gap_unpair`, przy aktywnym łączu ponowienie po DISCONNECT przez `xTimerPendFunctionCall`, żeby nie odwracać kolejności blokad). `info` wypisuje nazwy. Nowe testy: 12 aktora, 4 obrazu, 1 protokołu. **Brak:** wektory wspólne z Kotlinem i obsługa duplikatu `LIST_CLIENTS` w sesji (odpowiedź > 64 B nie trafia do cache; szczegóły w projekcie §4.2), test na sprzęcie (A90–A93), pomiar `mem` (bufor listy na stosie aktora).
- **Review firmware zarządzania telefonami (18.11.2026):** znaleziony i naprawiony błąd: `on_pair_commit` budował `brama_client_rec_t c` bez inicjalizacji, więc po dodaniu pól nazwy `commit` mógł odrzucić rejestrację z powodu śmieci ze stosu (`BRAMA_ERR_ARG`); test z zabrudzonym stosem (`pair_commit_gives_empty_name_regardless_of_stack_contents`) padał, po `= {0}` przechodzi (188/188, build `esp32` czysty, DRAM wolny ok. 7 KB). Sprawdzone bez uwag: naprawa bondu zachowuje nazwę (zmienia tylko `bond`), kolejność odczytu bondu przed publikacją obrazu, granice walidatora UTF-8 i buforów nazwy, kolejność blokad w ścieżce `unpair`. Zostaje do sprawdzenia na sprzęcie: bufor listy (313 B) na stosie aktora 8 KB (`mem`), `ble_gap_unpair` dla połączonego peera (EBUSY i ponowienie), brak testu automatycznego dla naprawy z nazwą.
- **Aplikacja zarządzania telefonami (18.11.2026; testy `:protocol`, `:domain`, `:ble`, `:app` zielone, `assembleDebug` czysty):** `:protocol`: `MessageType` `04`–`06`/`82`, `AckResult` `09`–`0D`, `ClientName` (walidacja i `truncate` jak w firmware), `ClientEntry`/`ClientList` (kodek 38 B/wpis, ścisły UTF-8, zerowe wypełnienie), `SessionRequest.ListClients/RemoveClient/SetClientName`, `SessionEvent.ClientListReceived`; `FakeGate`; wektory `commands.txt` i `negative_client.txt` wygenerowane (firmware odtwarza listę bajt w bajt: `client_list_matches_kotlin_vectors`). `:domain`: `GateRepository.refreshClients/removeClient/renameSelf`, `ClientsUi`, `clientsAvailable`, `auxInFlight` (zastąpił `windowInFlight`), 7 nowych `MessageId`, nazwa wysyłana po rejestracji (port `phoneName`). `:ble`: `LinkEvent.ClientList` z `BleGateLink`, `SimulatedGate` z listą/usuwaniem/nazwą. `:app`: `ClientsScreen` (trasa `clients`, karta w Ustawieniach, dialogi usuń/zmień nazwę), `ClientsModel` (`clientRows`, `defaultPhoneName`), etykiety. Firmware: obsługa duplikatu `LIST_CLIENTS` w sesji (`list_hash`, testy hosta 191/191). **Brak:** test na sprzęcie (A90–A93): wgranie firmware, reset przyciskiem 10 s + `reboot`, ponowna rejestracja 3 telefonów; wygląd ekranu „Telefony z dostępem” nieoglądany.
- **Review aplikacji zarządzania telefonami (18.11.2026):** poprawione: (1) „Usuń” i przycisk potwierdzenia w dialogu nie były blokowane przy zajętej sesji (`clientsAvailable`), więc kliknięcie w czasie ładowania listy znikało bez śladu — `DangerAction` dostało `enabled`; (2) okno zmiany nazwy zgadywało nazwę po przedrostku „Telefon ” (zła wartość dla nazwy „Telefon Ani”) — `ClientRow.name` niesie prawdziwą nazwę (test); (3) nazwa po rejestracji nie wychodziła, gdy sesja została odtworzona przed jej wysłaniem — `trySendOwnName` także w `onReady`, flaga czyszczona przy „Usuń z telefonu”; (4) test fragmentacji listy używał MTU 247, a nie 23 — ustawiono `mtuNeverAnswers` (lista 351 B w 21 fragmentach przechodzi). Znane, niepoprawiane: nazwy mogą zawierać znaki formatujące (np. U+202E, przekierowanie RTL), których walidator nie odrzuca (C0/DEL/C1 tak); ryzyko niskie, bo nazwy widzą tylko zarejestrowane telefony — decyzja, czy zaostrzyć walidację po obu stronach (firmware i `ClientName`).
- **Zarządzanie telefonami na sprzęcie (18.11.2026; płytka + A13 i A16, bez A80):** firmware wgrany pod `0x20000` z resetem `otadata`; stary obraz (schemat 1) dał tryb błędu (`obraz 2`), po resecie przyciskiem 10 s i `reboot` normalny start (`obraz 0`, `auth_generation 2`). Rejestracja A13 (QR) i A16 (PIN), nazwy „OPPO CPH2251”/„OPPO CPH2791” dotarły do sterownika; usunięcie A16 z A13 (ekran „Telefony z dostępem”) działa bez restartu płytki. **Błąd znaleziony i naprawiony:** po usunięciu klienta (i po resecie przyciskiem) ponowne parowanie tego samego telefonu kończyło się `enc_change status=1035` (DHKey check failed) do restartu sterownika. Przyczyna: `ble_gap_unpair` zwraca `BLE_HS_EBUSY` przy aktywnej reklamie (zawsze), więc wpis tożsamości zostawał w resolving list kontrolera; kontroler rozwiązywał RPA do adresu tożsamości (`peer_ota` typ 0 zamiast typu 1), host używał go w SMP i f6 się nie zgadzało. Poprawka: `brama_ble_client_removed` usuwa wpis przez `ble_hs_pvcy_remove_entry` przy zatrzymanej reklamie; `on_reset` woła ten sam callback dla każdego bondu (test `reset_notifies_removal_of_every_bond`; hosta 192/192). Weryfikacja: log `resolving list: usunięto … rc=0`, ponowna rejestracja usuniętego A16 bez restartu przeszła, 0 wpisów `enc_change`, `Guru`, `task_wdt`, `reset hosta`. Zasoby po testach: `actor` wolny min 3,5 KB, `nimble_host` 1,9 KB, sterta min 33 KB. Obserwacja: „Napraw połączenie” przy zachowanym starym bondzie telefonu nie działa (aplikacja nie usuwa bondu systemowego przed naprawą) i dotyczy też telefonu usuniętego ze sterownika (sterownik nie zna już `client_id`): właściwa droga to „Usuń z telefonu” i rejestracja od nowa; decyzja, czy naprawa ma usuwać stary bond sama, nie zapadła. Do zrobienia: A93 (odmowy: właściciel reguły, OTA, wymuszone usunięcie siebie), trzeci telefon A80 i test z 3 telefonami, ocena wyglądu ekranu.

### Wersja release: R8, rozmiar APK i test na telefonie (20.11.2026)

- **Rozmiar:** release miał 36 MB (`isMinifyEnabled = false`: cały Compose, Guava i Bouncy Castle w czterech nieskompresowanych plikach dex, 34 MB). Po włączeniu R8 i `isShrinkResources` APK ma ok. 4,1 MB (jeden `classes.dex` ok. 3,3 MB, `resources.arsc` 0,23 MB). `app/build.gradle.kts`: `isMinifyEnabled = true`, `isShrinkResources = true`; `packaging.resources.excludes` dla `org/bouncycastle/pqc/**` i `org/bouncycastle/x509/**` (dane PQC ok. 1,2 MB; BC jest używany tylko przez `EcJpake`: `CustomNamedCurves` i `ECPoint`). Mapowanie nazw: `app/build/outputs/mapping/release/mapping.txt` (trzymać razem z wydanym APK).
- **Błąd po R8 znaleziony na telefonie i naprawiony:** `ComponentDiscovery` ML Kit (skaner QR, `play-services-code-scanner`) logował `NoSuchMethodException: ...CommonComponentRegistrar.<init>` i `VisionCommonRegistrar.<init>` (R8 usunął konstruktory wołane refleksją). Dodane reguły keep w `app/proguard-rules.pro` (`ComponentRegistrar`, `com.google.mlkit.**` `<init>()`, `mlkit_**`); po przebudowie log czysty. Reguły dobrane do objawu z logu, nie sprawdzone na samym skanowaniu QR.
- **Podpis:** APK podpisany kluczem debug (`~/.android/debug.keystore`, `zipalign` + `apksigner`; `app-release-debugsigned.apk`). Nie nadaje się do Play ani publicznej dystrybucji; własnego keystore'a i `signingConfig` nie ma. Ten sam klucz co debug, więc instalacja nad buildem debug działa.
- **Test na Oppo CPH2251:** instalacja i start bez crasha; ekran główny poprawny, „Połączono ze sterownikiem” (sesja BLE i J-PAKE na klasach BC po R8 działa); w logu procesu brak wyjątków (`NoSuchMethod`, `FATAL`). **Niesprawdzone:** ustawienia, kalendarz. **Potwierdzone przez użytkownika (20.–21.11.2026):** OTWÓRZ/ZAMKNIJ i Android Auto działają na buildzie release (R8); OTA na buildzie release z A16 (CPH2791, Android 16) przeszło do końca, sterownik po aktualizacji ma wersję 1.0.0 (log UART i czas transferu nie zapisane); parowanie przez QR na A16 z buildem release działa poprawnie, więc reguły keep dla ML Kit wystarczają dla skanera.
- **Repozytorium:** `.gitignore` uzupełniony (`build/`, `.kotlin/`, `*.apk`/`*.aab`, klucze `*.jks`/`*.keystore`/`*.pem`/`*.p12`, `keystore.properties`, `log.txt`, `*.log`, `*.hprof`, cały `/.idea/`). Katalog `fwfinal/` (snapshot buildu firmware z 16.11.2026) usunięty przez właściciela; kopii w repozytorium nie było.
- **Firmware 1.0.0 do testu OTA (20.11.2026):** `CONFIG_APP_PROJECT_VER="1.0.0"` w `firmware/sdkconfig.defaults` i `firmware/sdkconfig` (było `0.1.0`). Obraz zbudowany w scratchpadzie (`idf.sh -B <scratchpad>/fwbuild build`, katalog `firmware/build` nietknięty): `brama.bin` 655 348 B, nagłówek: magic `0xABCD5432`, `project_name` `brama`, `version` `1.0.0`, `secure_version` 1; `espsecure verify-signature` — podpis poprawny (klucz deweloperski). Wgrany na Oppo CPH2251 do `/sdcard/Download/brama-1.0.0.bin` (SHA-256 `50b0aa00…9744f2` zgodny z plikiem na komputerze). Płytka **nie** była zmieniana ani flashowana; przebieg OTA (wersja przed → 1.0.0, restart, powrót do READY) do sprawdzenia przez użytkownika. Testy symulatora nadal używają `0.1.0` jako wartości testowej (to nie wersja firmware).
- **OTA 0.1.0 → 1.0.0 na buildzie release z A16 (21.11.2026):** potwierdzone przez użytkownika: aktualizacja przeszła do końca, aplikacja pokazuje wersję firmware 1.0.0. Telefon miał zainstalowany build release (R8, podpis debug, nad wcześniejszym debug), plik `brama-1.0.0.bin` z Downloads. Płytka jest teraz na 1.0.0 (partycja i log UART nie sprawdzane). Wniosek: skaner QR, sesja BLE, J-PAKE, OTWÓRZ/ZAMKNIJ, Android Auto i OTA działają po minifikacji R8; zostają ustawienia i kalendarz.

### Projekt PCB płytki sterownika (`hw/`, analiza 21.11.2026)

Dodany katalog `hw/` (KiCad 10, `esp32_brama`, 2 warstwy Cu): schemat, PCB, `bom/ibom.html`. Analiza z plików (połączenia z `.kicad_pcb`, BOM ze schematu) oraz `kicad-cli` (ERC, DRC z parytetem schematu); płytki fizycznej nie oglądano ani nie mierzono.

- **Zgodność z firmware (sprawdzona po nazwach sieci):** IO17 → R2 220 Ω → LED U2 CNY17 (OPEN), IO18 → R3 → U3 (CLOSE), IO19 → R4 → D1 do +3,3 V (LED aktywna niskim, `CONFIG_BRAMA_LED_ACTIVE_LOW`), IO0 → SW1 (PAIR_BUTTON), IO5 (INTERLOCK) wyprowadzone tylko na J6 pin 14 (bez izolacji i zabezpieczenia na płytce). Wyjścia OPEN/CLOSE: transoptory CNY17-1, prąd LED ok. 9,5 mA, styki bezpotencjałowe na J2/J3 (polaryzacja tranzystora: pin 5 kolektor, pin 4 emiter).
- **Zasilanie:** VIN (J4) → TVS D3, C3 4,7 µF/50 V, C9 10 µF/35 V → przetwornica LMR54410 (U4, 4–36 V, VREF 0,8 V), dzielnik R5 68k / R6 22k daje ok. 3,27 V, L1 10 µH, C4 100 nF (bootstrap). U5 AMS1117-3.3 i C11 oznaczone DNP (wariant zapasowy na tej samej sieci +3,3 V; obsadzać tylko jeden regulator).
- **Decyzja właściciela (21.11.2026): eFuse flash voltage wypalamy zawsze.** `CARD_D2` (GPIO12, pin strapping) ma pull-up R8 10k do 3,3 V, co przy resecie wybiera napięcie flash 1,8 V, a ESP32-WROOM-32D ma flash 3,3 V (schemat ma o tym notatkę). Na każdej płytce z tego PCB wypalane jest więc eFuse VDD_SDIO = 3,3 V (nieodwracalne), przed pierwszym uruchomieniem firmware; R8 zostaje obsadzony. Krok ręczny przy produkcji/uruchomieniu płytki (opis w `firmware/README.md`), nie konfiguracja w `sdkconfig.defaults` (zasada „nie wypalać eFuse w konfiguracji deweloperskiej” bez zmian). Płytka prototypowa (moduł deweloperski) tego nie wymaga. Wniosek z pinoutu i not producenta; samego wypalenia na tym PCB nie wykonywano.
- **Moduł:** symbol `ESP32-WROOM-32D`, a footprint `ESP32-WROOM-32U` (DRC: `lib_footprint_mismatch`). Trzeba potwierdzić, który moduł jest obsadzany (antena PCB czy złącze u.FL) i czy strefa antenowa zgadza się z footprintem.
- **ERC:** 0 naruszeń. **DRC:** 10 naruszeń, 0 nierozłączonych padów: J5 (microSD, footprint `footprinty:SD_MICRO`) — 2 pady PTH bez sieci z zerowym pierścieniem (`annular_width`, `padstack`) i PTH w courtyard; 2 termiczne GND z jednym szprychem (`starved_thermal`); 2 nakładki opisów na J2/J3; do tego 45 różnic pól symbol/footprint (opisy, atrybut DNP dla C9, C11, U5) — kosmetyka. Przed zamówieniem PCB warto sprawdzić J5 u producenta.
- **Odtwarzalność (poprawione 21.11.2026):** biblioteki projektu są w repozytorium: `hw/lib/footprinty.pretty` (SD_MICRO, brama_logo), `hw/lib/komponenty.kicad_sym` (LMR54410); `fp-lib-table` i `sym-lib-table` używają `${KIPRJMOD}`. Nieużywana biblioteka `NRFIoT` usunięta z tablicy. ERC/DRC po zmianie bez różnic (0 / 10 naruszeń). `bom/ibom.html` z 21.10, a PCB zmieniano 4.11, więc ibom może być nieaktualny. Brak pull-downów na IO17/IO18 (stan wyjść w czasie resetu/startu do sprawdzenia na sprzęcie).
- **Repozytorium:** `.gitignore`: `hw/*.kicad_prl` (lokalny stan edytora), kopie zapasowe i autosave KiCada. `esp32_brama.kicad_prl` nie jest w commicie.
- **Przygotowanie do publicznego repozytorium (21.11.2026):** adresy BLE w logach z testów zamaskowane (ostatnie trzy oktety `xx`); pozostałe ciągi hex w dokumentach to wektory testowe kryptografii, nie klucze; `firmware/.vscode/settings.json` wyjęty z repozytorium (`.vscode/` w `firmware/.gitignore`); ścieżki do katalogu domowego usunięte z dokumentów i projektu KiCad. Imię, nazwisko i e-mail w historii commitów zostają (decyzja właściciela). Licencja: MIT (`LICENSE`, właściciel praw: Andrzej Gendek, 2026; wybór właściciela).
- **README w korzeniu repozytorium (21.11.2026):** opis projektu, struktura katalogów, komendy budowania aplikacji i firmware, uwaga o eFuse, odnośniki do dokumentacji i licencji (MIT).
