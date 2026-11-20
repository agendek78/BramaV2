# Projekt: aktualizacja firmware przez BLE (OTA) po stronie Androida

Status: projekt do zatwierdzenia. Data: 28.10.2026.
Specyfikacja: `doc/PLAN_I_SPECYFIKACJA_BRAMY.md` wersja 1.5 — rozdziały 3 (teksty aktualizacji), 4.1 (ustawienia), 4.2, 4.3 pkt 4 (wstrzymanie limitu 5 min), 8.2 (Device Information, bit 6 `capabilities`), 13.3 (wspólna kolejka), 15.5 (OTA), testy A76–A81. Strona sterownika: `firmware/components/brama_ota/ota.c`, `doc/firmware_design.md` decyzje 11–13.
Wcześniejsze projekty: `doc/domain_design.md`, `doc/ble_design.md`, `doc/app_design.md`, `doc/pin_presence_design.md`.

## 1. Cel

Telefon w sesji READY aktualizuje firmware sterownika plikiem wybranym przez użytkownika (15.5): wstępnie sprawdza plik, pokazuje „wersja X → Y” z ostrzeżeniem, przesyła obraz kawałkami po 470 B, każdy po `OTA_ACK` poprzedniego, a po restarcie sterownika łączy się ponownie i rozpoznaje sukces albo powrót do poprzedniej wersji.

**Nie-cele:** OTA w Android Auto (15.5: brak), wznawianie przerwanego transferu (15.5: brak), weryfikacja podpisu na telefonie (robi to sterownik), pobieranie obrazów z sieci, zmiany firmware.

**Anty-cele:**

- równoległe żądania w sesji (OTA_DATA obok komendy lub PING) — łamie 13.3;
- komenda użytkownika czekająca na koniec całego transferu — 15.5 każe jej wejść przed następnym kawałkiem;
- ponowienie transferu albo END w nowej sesji bez decyzji użytkownika;
- uznanie sukcesu bez odczytu wersji po restarcie;
- logika aktualizacji w `:app` (nietestowalna) zamiast w `:domain`.

## 2. Ustalenia z kodu i obrazu

- `:protocol` ma już typy `50`–`53`/`81`, `OtaPayloads` (BEGIN, DATA, `MAX_DATA = 470`, `MAX_IMAGE_SIZE = 0x1E0000`), `OtaAck`, `OtaResult` (nieznany kod → `IMAGE_REJECTED`). `ClientSession` nie wysyła OTA i traktuje `OTA_ACK` jako błąd protokołu.
- Firmware (`brama_ota`): BEGIN sprawdza transfer innej sesji (BUSY), MTU ≥ 247, rozmiar; pierwszy DATA (offset 0) musi zawierać pierwsze 288 B obrazu; transfer porzucany po 30 s bez DATA (od 28.10.2026 odrzucony END tej sesji odnawia ten licznik — decyzja właściciela po przeglądzie) i przy utracie sesji; nowy BEGIN tej samej sesji zaczyna od 0. Reguła DISARMED sprawdzana przez aktora przy BEGIN i END.
- Obraz z lokalnego builda (`firmware/build/brama.bin`, 655 348 B): magic `0xE9`, 7 segmentów, `chip_id = 0` (ESP32), dopisany SHA-256 (`hash_appended = 1`), `esp_app_desc_t` pod offsetem 32 (magic `0xABCD5432`, `secure_version` = 1, `version` = `0.1.0`, `project_name` = `brama`), a za obrazem **dokładnie 68 B** bloku podpisu (4 B wersji = 0, 64 B ECDSA). `image_size` w BEGIN i SHA-256 obejmują cały plik razem z blokiem podpisu.
- Szacunek czasu: obraz 655 KB to ok. 1400 kawałków. Przy MTU 247 kawałek to ramka 512 B = 3 zapisy GATT plus wskazanie `OTA_ACK`; przy interwale 30–50 ms ok. 150–250 ms na kawałek, czyli ok. 4–6 min. Największy obraz (1,9 MB) — do ok. 15 min. Wynika to z decyzji „kawałki po jednym, potwierdzane” (spec 1.5).

## 3. `:protocol`

### 3.1. Żądania OTA w sesji

`SessionRequest` dostaje warianty OTA:

```kotlin
class OtaBegin(val imageSize: Long, val sha256: ByteArray) : SessionRequest
class OtaData(val offset: Long, val data: ByteArray) : SessionRequest   // 1..470 B
data object OtaEnd : SessionRequest
data object OtaAbort : SessionRequest
```

`OtaBegin`/`OtaData` to zwykłe klasy (równość przez tożsamość): `:ble` zwraca w zdarzeniach ten sam obiekt, który dostał, a `:domain` porównuje go ze swoim oczekującym żądaniem.

`ClientSession`:

- `send()` szyfruje warianty OTA payloadami z `OtaPayloads` (wspólny `seq`, jedno oczekujące żądanie — bez zmian);
- `OTA_ACK` przyjmowany tylko przy oczekującym żądaniu OTA ze zgodnym `request_type` i `request_seq` → nowe `SessionEvent.OtaAcknowledged(request, ack)`; w przeciwnym razie błąd protokołu jak dla `COMMAND_ACK`. `COMMAND_ACK` na żądanie OTA i `OTA_ACK` na komendę lub PING — błąd protokołu;
- `OTA_ACK` nie niesie snapshotu, więc nie zmienia `latestSnapshot`;
- duplikaty, luka `seq` i cache — bez zmian (13.4).

Wektory `vectors/ota.txt` generowane dotąd z `FakeGate` mają zostać bajt w bajt takie same, gdy telefon buduje ramki przez `ClientSession` (pilnuje tego istniejący `vectorScenariosMatchCurrentImplementation`).

### 3.2. `FirmwareImage` — wstępne sprawdzenie pliku (15.5)

```kotlin
class FirmwareImage(val bytes: ByteArray, val version: String, val secureVersion: Long, val sha256: ByteArray)

sealed interface FirmwareImageResult {
    class Ok(val image: FirmwareImage) : FirmwareImageResult
    data class Invalid(val reason: FirmwareImageError) : FirmwareImageResult
}

enum class FirmwareImageError { TOO_LARGE, EMPTY, BAD_MAGIC, WRONG_CHIP, BAD_APP_DESC, WRONG_PROJECT, TRUNCATED, NO_SIGNATURE }

object FirmwareImages { fun parse(bytes: ByteArray): FirmwareImageResult }
```

Kontrole, w tej kolejności:

1. rozmiar 1..`0x1E0000`;
2. `bytes[0] = 0xE9`; `chip_id` (LE16 pod offsetem 12) = `0x0000`;
3. `esp_app_desc_t` pod offsetem 32: magic `0xABCD5432`, `project_name` (offset 48+32, 32 B) dokładnie `brama` z NUL — jak `brama_ota_check_header`;
4. przejście po segmentach (`segment_count` w bajcie 1; nagłówek segmentu 8 B: adres, LE32 długość), dopełnienie do 16 B z bajtem sumy kontrolnej, +32 B przy `hash_appended` (bajt 23) — wyznacza koniec obrazu; segment wychodzący poza plik → `TRUNCATED`;
5. za końcem obrazu dokładnie 68 B z pierwszymi 4 B = 0 → inaczej `NO_SIGNATURE`.

Wynik: `version` z `esp_app_desc_t.version` (offset 16, 32 B, do NUL, ASCII), `secure_version` (offset 4), SHA-256 całego pliku. Telefon nie porównuje `secure_version` (nie zna wartości działającego obrazu) — downgrade odrzuca sterownik (`OTA_DOWNGRADE`). Testy: obraz z builda w zasobach testowych (`protocol/src/test/resources/firmware/brama.bin`) i jego mutacje.

## 4. `:ble`

### 4.1. Device Information i bit 6

- `GattProfile`: `DEVICE_INFORMATION = 0x180A`, `FIRMWARE_REVISION = 0x2A26`; `GateInfo.supportsOta` (bit 6).
- `BleRadio.readFirmwareRevision(h)` → `RadioEvent.FirmwareRevisionRead(handle, value?)`; `GattOp.ReadFirmwareRevision` w kolejce (timeout 5 s jak odczyt INFO).
- Kolejność 8.1: po zweryfikowanym INFO, przed bondem — odczyt nie wymaga szyfrowania (8.2). Błąd lub brak charakterystyki nie kończy próby: wersja `null`.
- `AndroidBleRadio`: odczyt charakterystyki `0x2A26` z usługi `0x180A`, wartość jako UTF-8 do pierwszego NUL.

### 4.2. Zmiana portu `GateLink`

```kotlin
/** Dane łącza z bieżącego połączenia (8.2, 15.5). */
data class LinkInfo(val firmwareVersion: String?, val otaSupported: Boolean, val mtu: Int)

data class Ready(val snapshot: RuleSnapshot, val info: LinkInfo) : LinkEvent
data class OtaResult(val request: SessionRequest, val ack: OtaAck) : LinkEvent
```

`Ready` po rekeyu na zachowanym łączu niesie te same dane łącza. `SessionEvent.OtaAcknowledged` → `LinkEvent.OtaResult`. Pozostała mechanika bez zmian i wspólna z komendami: `T_response` od zakończenia wysłania, dwie retransmisje identycznych bajtów, po trzecim upływie `CommandUnknown(request)` i rekey (13.4); utrata radia → `CommandUnknown` + `Disconnected`.

### 4.3. Symulator

`SimulatedGate` dostaje stronę sterownika OTA zgodną z `brama_ota` (BEGIN/DATA/END/ABORT, `next_offset`, MTU ≥ 247, BUSY z innej sesji, RULE_ACTIVE, 30 s bez DATA, restart po END z nową wersją albo „rollback” do starej) oraz Device Information z konfigurowalną wersją i możliwością błędu odczytu.

## 5. `:domain`

### 5.1. Stan

```kotlin
sealed interface FirmwareUpdate {
    /** Plik sprawdzony; czeka na potwierdzenie „wersja X → Y” z ostrzeżeniem WARN_OTA_RESTART. */
    data class Confirm(val currentVersion: String?, val newVersion: String) : FirmwareUpdate
    data class Transferring(val percent: Int) : FirmwareUpdate
    /** END odrzucony przez OTA_RULE_ACTIVE / OTA_BUSY: dane zostają u sterownika, END można ponowić (15.5, A77). */
    data class EndRejected(val message: UiMessage) : FirmwareUpdate
    /** Po OTA_OK na END albo utracie sesji po wysłaniu END: oczekiwanie na READY i wersję, najdłużej 90 s. */
    data object Rebooting : FirmwareUpdate
    data class Finished(val message: UiMessage) : FirmwareUpdate   // sukces, powrót wersji, błąd — tekst z rozdz. 3
}
```

W `GateUiState`:

- `firmwareVersion: String?` — z ostatniego `Ready` (Device Information); `null` bez połączenia;
- `firmwareUpdate: FirmwareUpdate?`;
- `firmwareUpdateAvailable: Boolean` — READY, ACTIVE, `otaSupported` i brak aktualizacji w toku.

Procent liczony z przyjętego `next_offset`, publikowany tylko przy zmianie (StateFlow nie emituje identycznego stanu).

### 5.2. Intencje

```kotlin
fun selectFirmware(bytes: ByteArray)      // plik odczytany przez :app; parse → Confirm albo Finished(OTA_FILE_INVALID)
fun confirmFirmwareUpdate()               // Confirm → MTU < 247: Finished(OTA_LINK_TOO_SMALL) bez BEGIN; inaczej BEGIN
fun retryFirmwareEnd()                    // EndRejected → ponowny END
fun cancelFirmwareUpdate()                // Confirm → null; Transferring/EndRejected → OTA_ABORT i Finished(OTA_ABORTED)
fun dismissFirmwareUpdate()               // Finished → null
```

Obraz żyje w pamięci aktora od `selectFirmware` do końca transferu (Finished, Rebooting, anulowanie, „Usuń z telefonu”).

### 5.3. Przebieg i kolejka (13.3, 15.5)

Domena ma jedno oczekujące żądanie sesji: komendę (`inFlight`), PING albo żądanie OTA (`otaPending`).

- BEGIN → `OTA_OK` → DATA od offsetu 0 po 470 B; każdy kolejny DATA dopiero po `OTA_OK` poprzedniego z `next_offset` równym oczekiwanemu (inaczej OTA_ABORT i Finished(OTA_ABORTED)); po ostatnim — END.
- **Komenda w trakcie transferu:** przyciski OPEN/CLOSE pozostają aktywne. Gdy czeka żądanie OTA, komenda trafia do istniejącej kolejki (`queued`, jak za PING) i jest wysyłana zaraz po `OTA_ACK`; następny kawałek idzie dopiero po wyniku komendy. To samo dla PING (odświeżenie po powrocie do ekranu) — wysyłany między kawałkami, więc stan reguły nie utyka w „Odświeżanie…”.
- `CommandUnknown` dla żądania OTA, utrata sesji lub łącza (także rekey na zachowanym łączu): sterownik porzuca transfer (15.5 pkt 4) → Finished(OTA_ABORTED). Wyjątek: wysłany END — sterownik mógł już zweryfikować obraz i się zrestartować → Rebooting (15.5, „Telefon”).
- Wyniki `OTA_ACK`:

| Wynik | Na | Stan, tekst rozdz. 3 |
|---|---|---|
| OK | BEGIN, DATA | kolejny krok |
| OK | END | Rebooting („Sterownik uruchamia się ponownie…”) |
| RULE_ACTIVE | BEGIN | Finished(OTA_RULE_ACTIVE) |
| RULE_ACTIVE, BUSY | END | EndRejected(tekst wyniku), END do ponowienia |
| BUSY | BEGIN | Finished(OTA_BUSY) |
| LINK_TOO_SMALL | BEGIN | Finished(OTA_LINK_TOO_SMALL) |
| TOO_LARGE | BEGIN | Finished(OTA_FILE_INVALID) |
| IMAGE_REJECTED (i nieznany kod) | dowolne | Finished(OTA_IMAGE_REJECTED) |
| DOWNGRADE | DATA 0 | Finished(OTA_DOWNGRADE) |
| STORAGE_FAILURE | dowolne | Finished(OTA_STORAGE_FAILURE) |
| BAD_OFFSET, NOT_IN_PROGRESS, INVALID_ARGUMENT | dowolne | Finished(OTA_ABORTED) |

### 5.4. Po END: restart i wersja

- Rebooting startuje licznik 90 s (od `OTA_OK` na END albo od utraty sesji po wysłaniu END).
- Utrata łącza w Rebooting: zwykła ścieżka `onLinkLost` (natychmiastowe ponowne łączenie, potem krótkie odstępy).
- Pierwsze `Ready`: wersja równa Y → Finished(OTA_DONE, Y); inna → Finished(OTA_ROLLED_BACK, X). Firmware daje READY dopiero po potwierdzeniu obrazu (15.5: `control_enabled` po autoteście), więc READY nowej wersji to trwały sukces.
- Brak `Ready` w 90 s → Finished(OTA_NO_RECONNECT).
- Odmowa UNAVAILABLE w trakcie autotestu nowego obrazu uruchamia zwykły backoff 12.4 (30 s) — mieści się w 90 s.
- Wynik trafia też do `notification`, aby w tle pokazało go powiadomienie ostrzeżeń (`ServiceController`); `RULE_REBOOT_DISARMED` ma pierwszeństwo.

### 5.5. Łącze, „Rozłącz”, powiadomienie w zasięgu

- `ConnectionPolicy.setUpdating(true)` przy Transferring, EndRejected i Rebooting: powód połączenia także bez ekranu telefonu (`shouldConnectWithoutPhoneUi`) — transfer działa w usłudze po wyjściu z ekranu (15.5); Rebooting ograniczony 90 s.
- „Rozłącz” przy Transferring/EndRejected wymaga potwierdzenia (`Confirmation.DISCONNECT_ABORTS_UPDATE`, ostrzeżenie `WARN_DISCONNECT_ABORTS_UPDATE`); po potwierdzeniu zwykłe rozłączenie — sterownik porzuca transfer z utratą sesji — i Finished(OTA_ABORTED). Przy jednoczesnej własnej regule MONITORING nie dotyczy: transfer wymaga DISARMED.
- Licznik 5 min (4.3 pkt 4) jest wstrzymany (anulowany) przy Transferring/EndRejected i startuje od nowa, gdy transfer się kończy (Rebooting albo Finished), jeśli powiadomienie jest SHOWN. QUIET nie może więc rozłączyć trwającego transferu (A81).
- „Usuń z telefonu” w trakcie: zwykłe zerwanie łącza, aktualizacja znika bez komunikatu.
- Śmierć procesu: transfer i stan Rebooting giną (bez utrwalania, bez wznawiania — 15.5); sterownik porzuca transfer po utracie sesji.
- **Drugi telefon w trakcie transferu (17.11.2026):** sterownik nie reklamuje się łączalnie od przyjętego BEGIN do END/ABORT/porzucenia transferu (spec 8.3, 15.5), więc drugi telefon, który nie był połączony przed BEGIN, nie może się połączyć; jego aplikacja pokazuje stan „Łączenie…”/„Brak połączenia” bez wskazania przyczyny (decyzja właściciela: OTA jest sporadyczna i wykonywana przez głównego użytkownika przy bramie, komunikat pominięty). Telefon połączony przed BEGIN dostaje BUSY na własny BEGIN, może wysyłać OPEN/CLOSE i jego rozłączenie nie psuje transferu.

### 5.6. Teksty (spec 3)

Nowe pozycje `MessageId` (15) i argumenty `MessageArgs.Percent(value)`, `MessageArgs.Version(value)`:

`WARN_OTA_RESTART`, `WARN_DISCONNECT_ABORTS_UPDATE`, `OTA_PROGRESS` (N%), `OTA_REBOOTING`, `OTA_DONE` (Y), `OTA_ROLLED_BACK` (X), `OTA_NO_RECONNECT`, `OTA_FILE_INVALID`, `OTA_IMAGE_REJECTED`, `OTA_DOWNGRADE`, `OTA_RULE_ACTIVE`, `OTA_BUSY`, `OTA_LINK_TOO_SMALL`, `OTA_STORAGE_FAILURE`, `OTA_ABORTED` — brzmienie dokładnie z rozdziału 3.

`UiMessages.phone` dodaje wiersz aktualizacji (postęp, restart, wynik) po wyniku komendy; `UiMessages.car` i `carNotification` bez zmian (15.5: ekran samochodowy bez OTA).

## 6. `:app`

- **Ustawienia:** sekcja „Firmware”: etykieta wersji (`firmwareVersion` albo „—”) i przycisk „Aktualizuj firmware” aktywny przy `firmwareUpdateAvailable`; przy trwającej aktualizacji przycisk prowadzi do ekranu aktualizacji.
- **Wybór pliku:** `ActivityResultContracts.OpenDocument` (`*/*`), bez uprawnień do pamięci. Odczyt na `Dispatchers.IO` przez `ContentResolver` z limitem `0x1E0000 + 1` B (większy plik → od razu za duży, bez czytania całości), potem `repository.selectFirmware(bytes)`. Błąd odczytu → ten sam tekst „Wybrany plik nie jest firmware tego sterownika”.
- **Ekran aktualizacji** (nowa trasa w `BramaNav`): Confirm — „wersja X → Y” (etykieta) i ostrzeżenie, przyciski Aktualizuj/Anuluj; Transferring — pasek postępu i tekst N%, Anuluj; EndRejected — tekst, Ponów/Anuluj; Rebooting — tekst; Finished — tekst i Zamknij. Wyjście z ekranu nie przerywa transferu. Wygląd (17.11.2026): grafika bramy nad kartą z wersją i grubym paskiem postępu (grafika pulsuje w trakcie transferu i restartu), akcje jako zaokrąglone przyciski (`PrimaryAction`/`SecondaryAction`).
- **Powiadomienie usługi:** od 17.11.2026 bez wiersza aktualizacji (decyzja właściciela: w powiadomieniu tylko stan połączenia i przyciski); postęp, restart i wynik tylko na ekranie aktualizacji. Usługa nadal wynika z `backgroundLinkNeeded` (bez zmian w `serviceNeeded`), więc transfer trwa po wyjściu z ekranu.
- Etykiety spoza listy zamkniętej w `Labels.kt`: „Firmware”, „Wersja firmware”, „Aktualizuj firmware”, „wersja X → Y”, „Aktualizuj”, „Ponów”, „Anuluj aktualizację”, „Zamknij”.

## 7. Testy

- `:protocol`: warianty OTA w `ClientSession` (szyfrowanie, `OTA_ACK` z dobrą i złą referencją, krzyżowe ACK, duplikat), `FirmwareImages.parse` na obrazie z builda i mutacjach (każdy błąd), niezmienione wektory.
- `:ble` (symulator): odczyt Device Information i błąd odczytu, `LinkInfo` w `Ready`, pełny transfer przy MTU 247 i 517, utrata `OTA_ACK` (retransmisja i duplikat z cache), trzeci timeout → `CommandUnknown`, MTU 23.
- `:domain` (fałszywy `GateLink`, wirtualny czas): każda gałąź tabeli 5.3; komenda i PING między kawałkami; utrata sesji przed i po END; Rebooting → sukces, powrót wersji, 90 s bez READY; „Rozłącz” z potwierdzeniem; licznik 5 min wstrzymany i wznowiony; `updating` jako powód łącza bez ekranu; teksty zamkniętej listy.
- `:app`: mapery (ekran ustawień, wiersz powiadomienia) na JVM; wybór pliku i ekran — kompilacja.
- Ręcznie z płytką: A76–A81 w części telefonu, pomiar czasu transferu.

## 8. Do decyzji

**Decyzja właściciela (28.10.2026, dopisana do rozdziału 3 specyfikacji):**

1. Ostrzeżenie przy „Rozłącz” w trakcie transferu: „Rozłączenie przerwie aktualizację — firmware pozostanie bez zmian” (`WARN_DISCONNECT_ABORTS_UPDATE`).

**Moje decyzje (do sprawdzenia):**

2. Transfer prowadzi `:domain` (kawałek po kawałku przez `GateLink.send`), a nie `:ble` — jedno oczekujące żądanie i kolejka komend już są w domenie; `:ble` bez nowej logiki transferu.
3. Cały obraz w pamięci (≤ 1,9 MB) od wyboru do końca transferu, bez portu do strumieniowego odczytu.
4. Wersja z Device Information odczytywana przy każdym połączeniu przed bondem; błąd odczytu nie przerywa połączenia. Po restarcie bez odczytanej wersji (`null`) aktualizacja znika bez komunikatu (nie da się rozstrzygnąć sukcesu).
5. Ponowna instalacja tej samej wersji (X = Y) jest pokazywana jako sukces — nie da się jej odróżnić od powrotu.
6. Telefon nie sprawdza reguły przed BEGIN — o DISARMED rozstrzyga sterownik (`OTA_RULE_ACTIVE`); sprawdzane jest tylko MTU (wymóg 15.5).
7. „Rozłącz” przerywa transfer samym rozłączeniem (sterownik porzuca transfer z utratą sesji), bez wysyłania OTA_ABORT. „Anuluj” na ekranie wysyła OTA_ABORT.
8. „Rozłącz” w Rebooting kończy oczekiwanie bez komunikatu (inaczej po 90 s pojawiłby się fałszywy „Brak połączenia…”).
9. Wynik aktualizacji także w `notification` (jednorazowy komunikat/snackbar; w powiadomieniu usługi od 17.11.2026 go nie ma, zob. §6).
10. Błąd odczytu pliku → „Wybrany plik nie jest firmware tego sterownika”.

## 9. Kolejność prac (plan)

1. `:protocol`: `FirmwareImages.parse`, warianty OTA w `SessionRequest`/`ClientSession`.
2. `:ble`: Device Information, `LinkInfo`, `LinkEvent.OtaResult`, symulator OTA.
3. `:domain`: stan, intencje, kolejka, Rebooting, polityka, licznik 5 min, teksty.
4. `:app`: ustawienia, wybór pliku, ekran aktualizacji, powiadomienie.

Każdy krok TDD; na końcu przegląd całości i aktualizacja `doc/implementation_status.md`, `doc/domain_design.md`, `doc/ble_design.md`, `doc/app_design.md`.

## 10. Wyniki na sprzęcie i zmiany (17.11.2026)

- **OTA end-to-end pod task watchdogiem z resetem** (`ESP_TASK_WDT_PANIC=y`, 5 s): `0.1.0` → obraz testowy `0.1.1` z A13 (CPH2251, API 33): transfer ok. 419 s (ok. 7 min; na A16 z 14.11 ok. 3,5 min, przyczyna różnicy niezbadana, najpewniej parametry połączenia lub MTU starszego telefonu); weryfikacja podpisu na końcu (`esp_ota_end`) ok. 1 s, bez `task_wdt`; restart `SW_CPU_RESET`, bootloader załadował `ota_1`, `App version: 0.1.1`, klienci bez zmian, telefon wrócił do łącza po ok. 3 s.
- **A80:** czas impulsu OPEN/CLOSE podczas ciągłego kasowania i zapisu slotu OTA (zadanie na rdzeniu 1, operacje flash do 60 ms): odchyłka do +0,34 ms (100, 200 i 1000 ms); koniec impulsu nie jest opóźniany (timer w ISR, GPIO w IRAM); początek może się opóźnić o czas trwającej operacji flash.
- **Zerwanie przez drugi telefon:** odtworzone trzy razy; nowe łącze zawiązane w trakcie strumienia OTA i potem zerwane powoduje reset hosta NimBLE (`BLE_HS_ECONTROLLER`: kontroler zgłasza więcej ukończonych pakietów, niż host ma w toku) i przerwanie transferu. Obejście: brak reklamy łączalnej w trakcie transferu (`ota_in_progress()` w `brama_ble_refresh_advertising`, zerwanie łącza przyjętego w wyścigu tuż przed BEGIN). Błąd transferu w `ota_worker` (zły offset, odrzucony obraz, błąd zapisu) także wznawia reklamę (poprawka po przeglądzie kodu). Weryfikacja: 15 cykli dołączania drugiego telefonu przez 3 min bez resetu hosta; po `OTA_ABORT` reklama wraca od razu. Rozłączenie drugiego telefonu połączonego przed BEGIN transferu nie psuje (sprawdzone).
- **OPEN/CLOSE w trakcie OTA:** komendy z drugiego, już połączonego telefonu działają (potwierdzenie użytkownika), zgodnie z A76.
- **Nierozstrzygnięte:** jednorazowe zawieszenie na blokadzie `ota_worker` (aktor, `ui` i host NimBLE czekają na blokadę, której `ota_worker` nie oddaje; task watchdog tego nie widzi) po dołączeniu drugiego telefonu w trakcie OTA; nie powtórzyło się, nie ma śladu stosu z zawieszonego stanu. `ota_worker` trzyma wspólną blokadę podczas zapisu flash, co wydłuża postoje hosta.
- Spec 4.3: powiadomienie usługi bez wiersza aktualizacji; `UiMessages.phone` nadal buduje wiersz aktualizacji dla ekranu, ale ekran główny go nie wyświetla.
