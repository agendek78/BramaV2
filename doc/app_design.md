# Projekt modułu `:app`

Data: 27.10.2026. Specyfikacja: `doc/PLAN_I_SPECYFIKACJA_BRAMY.md` 1.4 (rozdz. 3, 4, 7, 9.2–9.4). Gotowe moduły: `:protocol`, `:domain` (`doc/domain_design.md`, `doc/pin_presence_design.md`), `:ble` (`doc/ble_design.md`).

## 1. Cel

Aplikacja gotowa do użycia w chwili, gdy pojawi się płytka ESP32. Spina `GateRepository` (`:domain`) z `BleGateLink` + `AndroidBleRadio` (`:ble`) i daje:

- ekran główny: stan połączenia i duże przyciski OTWÓRZ/ZAMKNIJ;
- dodawanie bramy: QR albo 6-cyfrowy PIN;
- ustawienia, historię i instrukcję resetu;
- powiadomienie „brama w zasięgu” (spec 4.3) jako powiadomienie usługi `connectedDevice`.

**Nie-cele:** tryb demonstracyjny lub symulowany sterownik w aplikacji (decyzja właściciela), Android Auto (`:car` osobno), wiele bram (naprawa bondu dodana później: przycisk „Napraw połączenie”, `doc/bond_repair_design.md` §4.3), konto i chmura.

**Anty-cele:**
- tekst sugerujący położenie bramy („Brama otwarta/zamknięta”, zielone ikony, animacje ruchu — spec 3);
- komunikat spoza zamkniętej listy rozdziału 3;
- logika domeny w UI — UI tylko renderuje `GateUiState` i woła intencje `GateRepository`;
- sekret (`K_client`, `K_setup`, PIN) w logach, w backupie albo zapisany jawnie.

## 2. Decyzje właściciela

- **Ekran główny:**
  - na górze nazwa bramy (pasek z ikonami ustawień i historii), pod nim grafika bramy i stan połączenia w „pigułce” z kolorową kropką, z małym przyciskiem Rozłącz/Połącz;
  - całość wyśrodkowana w pionie i poziomie; pośrodku duże OTWÓRZ i ZAMKNIJ (jednym dotknięciem, bez potwierdzenia);
  - **pod przyciskami nie ma tekstów statusów** (decyzja 17.11.2026: bez „Położenie bramy: nieznane”, stanu reguły, wyniku ostatniej komendy i ustawienia następnego otwarcia); wyniki komend są w Historii;
  - bez skonfigurowanej bramy zamiast przycisków „Dodaj bramę”.
- **Powiadomienie:** spec 4.3. W powiadomieniu wyłącznie stan połączenia i przyciski OTWÓRZ/ZAMKNIJ (decyzja 17.11.2026, bez dodatkowych tekstów, także bez ostrzeżenia o uzbrojeniu przed wyciszeniem i bez postępu OTA). Powiadomienie tylko przy aplikacji w tle; tap (`contentIntent`) i wyjście UI na pierwszy plan wołają `notificationDismissed()`, przesunięcie — `deleteIntent` → `NotificationActionReceiver.ACTION_DISMISSED`; pojawienie się bramy przy widocznym UI nie pokazuje powiadomienia. Odrzucenie rozłącza także przy uzbrojonej regule. Przyciski działają na zablokowanym ekranie.
- **Skaner QR:** Google Play services code scanner (bez uprawnienia CAMERA, wymaga usług Google). Alternatywą jest wpisanie PIN-u.
- **Bez trybu demo.**

## 3. Ekrany (Compose, Material 3, motyw systemowy jasny/ciemny)

Nawigacja: Navigation Compose, jedna aktywność. Każdy ekran ma `ViewModel`, który mapuje `GateUiState` na model ekranu i woła intencje repozytorium. Podpięcie ekranu: `uiAttached(PHONE)` w `ON_START`, `uiDetached(PHONE)` w `ON_STOP` (proces na pierwszym planie = UI telefonu).

1. **Główny** (opis wyżej).
   - Przyciski aktywne według `controlsEnabled`.
   - „Rozłącz” przy MONITORING/SELF prowadzi przez potwierdzenie z ostrzeżeniem (`requestDisconnect`/`confirmDisconnect`, `WARN_DISCONNECT_ARMS`).
   - `GateUiState.messages` nie są wyświetlane na ekranie głównym (decyzja 17.11.2026); ekran pokazuje tylko stan połączenia (`MainUi.connection` i `tone`).
   - Jednorazowe `notification` (np. REGISTRATION_DONE, REBOOT_DISARMED dla właściciela) jako snackbar plus powiadomienie systemowe, gdy aplikacja jest w tle.
2. **Dodaj bramę.** Kreator:
   1. wyjaśnienie wymagań (Bluetooth, przytrzymanie przycisku sterownika 3 s, LED miga);
   2. wybór: „Zeskanuj QR” albo „Wpisz PIN” (pole tylko cyfry, przycisk aktywny przy 6 cyfrach). Ekran nasłuchuje reklamy sterownika (`AndroidGateWatcher`, osobny skan poza rejestracją w toku): przy otwartym oknie pokazuje „Pozostało prób PIN: N z 5”, a gdy licznik jest wyczerpany (np. po restarcie sterownika, do przycisku), wyłącza pole PIN i przycisk z komunikatem (przycisk na bramie albo QR). Stan liczony per bramka, blokada tylko, gdy wszystkie bramki z polem mają 0 prób; brak reklamy z polem nie blokuje;
   3. postęp według `RegistrationState` (Pairing → SavingCredential → Committing → ConfirmingSession → Done) z tekstami z listy;
   4. po `Done` — powiązanie urządzenia towarzyszącego (§5) i powrót do ekranu głównego.

   Rejestracja przerwana albo nieudana **przed wysłaniem `PAIR_COMMIT`** (zły PIN, odmowa sterownika, timeout próby, anulowanie) usuwa też bond systemowy z bramą (`BluetoothDevice.removeBond()` przez refleksję, `BleRadio.removeBond`): zostawiony zepsułby kolejną rejestrację (sterownik zna go po IRK). Po wysłaniu `COMMIT` bond zostaje.

   Systemowe parowanie BLE (dialog Androida) pojawia się samo w trakcie `pair()`.
3. **Ustawienia:**
   - przełącznik `autoCloseOnDisconnect` (włączenie przez potwierdzenie z `WARN_ENABLE_AUTO_CLOSE`);
   - przełącznik „Powiadomienie, gdy brama w zasięgu” (domyślnie włączony);
   - nazwa lokalna bramy;
   - „Instrukcja resetu autoryzacji” (`WARN_RESET_NO_SIGNAL`);
   - „Telefony z dostępem” (18.11.2026, `doc/client_management_design.md` §5.4): ekran z listą telefonów zapisanych na sterowniku (nazwa lub „Telefon N”, znaczniki „Ten telefon”/„Połączony”/„Zarządza bramą”), „Usuń” przy innych telefonach z potwierdzeniem, „Zmień nazwę” przy własnym; aktywne tylko w READY; trasa `clients`;
   - „Usuń z telefonu” (potwierdzenie z ostrzeżeniami według spec 4.1); usuwa też bond systemowy z bramą (jak wyżej) i wraca na ekran główny z „Dodaj bramę” (tylko przy przejściu ACTIVE → NONE, porażka rejestracji zostaje w kreatorze);
   - informacja, gdy brak zgody na powiadomienia.
4. **Historia:** lista do 200 wpisów (czas, komenda, boolean dla OPEN, wynik), najnowsze na górze.

## 4. Usługa połączenia (`GateConnectionService`, typ `connectedDevice`)

- **Kiedy działa:** gdy łącze jest potrzebne poza ekranem:
  - powiadomienie SHOWN;
  - własna uzbrojona reguła z łączem;
  - komenda w toku po zamknięciu ekranu;
  - start przed OPEN(true) przez `ConnectionKeeper.ensureBackgroundConnection()`.

  Gdy nic z tego nie zachodzi, usługa się zatrzymuje (spec 4.2). Decyzję podejmuje obserwator `GateUiState` w `Application`.
- **Jedno powiadomienie usługi** (kanał „Połączenie z bramą”):
  - w SHOWN: tytuł z nazwą bramy i jedna linia ze stanem połączenia (decyzja 17.11.2026: bez położenia, reguły, wyniku komendy, postępu firmware, problemów i ostrzeżenia o uzbrojeniu);
  - akcje OTWÓRZ/ZAMKNIJ tylko gdy `notificationActionsAvailable`;
  - akcje przez `BroadcastReceiver` → `notificationOpen()`/`notificationClose()`; działają na zablokowanym ekranie (bez `setAuthenticationRequired`);
  - poza SHOWN (np. tylko reguła): trwałe powiadomienie ze stanem połączenia, bez akcji (spec 4.2); mała ikona `ic_stat_gate` (jednokolorowa).
- Powiadomienie „Sterownik uruchomił się po zaniku zasilania…” dla właściciela idzie osobnym kanałem („Ostrzeżenia”).
- `ConnectionKeeper` próbuje `startForegroundService`; `ForegroundServiceStartNotAllowedException` → `false` (domena pokaże „Nie można uruchomić połączenia w tle…”).

## 5. Urządzenie towarzyszące (CompanionDeviceManager)

- Po pierwszym `RegistrationState.Done` aplikacja prosi o powiązanie: `CompanionDeviceManager.associate` z filtrem `BluetoothLeDeviceFilter` po UUID usługi bramy **i dokładnym adresie sterownika** (`ScanFilter.setDeviceAddress`), `setSingleDevice(true)`. Jest to systemowy dialog z jednym urządzeniem, a sterownik jest wtedy w zasięgu. Adres pochodzi z `:ble` (`BleGateLink.knownAddress(deviceId)`), ustalony dopiero po uwierzytelnionym PAIR_CHALLENGE lub SESSION_READY. Sam UUID jest wspólny dla wszystkich sterowników, a INFO można podrobić, więc nie wystarcza. Pominięte powiązanie wyłącza tylko powiadomienie „w zasięgu”; sterowanie z ekranu działa.
- Po powiązaniu: `startObservingDevicePresence` dla powiązanego urządzenia. `GateCompanionService : CompanionDeviceService` zamienia `onDeviceAppeared`/`onDeviceDisappeared` na `presenceAppeared()`/`presenceDisappeared()`, gdy ustawienie powiadomienia jest włączone. Przy wyłączonym ustawieniu **albo braku zgody `POST_NOTIFICATIONS`** wysyła `presenceDisappeared()`: brak widocznego powiadomienia oznacza brak połączeń w tle z powodu obecności. Inaczej telefon łączyłby się po cichu, a odrzucenie powiadomienia zamykałoby bramę bez widocznego ostrzeżenia. Ekran ustawień pokazuje wtedy, że funkcja wymaga zgody na powiadomienia. Przy usunięciu konfiguracji powiązanie jest kasowane (`disassociate`).
- Powiązanie daje systemowe wyjątki na start usługi pierwszoplanowej z tła (spec 7) — dokładne wymagania API 34–36 do weryfikacji w etapie 0.
- Adres i `BluetoothDevice` z powiązania (`AssociationInfo.associatedDevice`) przekazujemy do `:ble` jako znany adres sterownika. Potrzebna jest mała zmiana `:ble`, opisana niżej. Dzięki temu po restarcie procesu łączenie nie wymaga skanu, a typ adresu (static random) jest poprawny.

**Zmiana `:ble` (mała):**
- `BleGateLink.knownAddress(deviceId): String?` — adres zapamiętany po uwierzytelnieniu (do filtra powiązania);
- `BleGateLink.rememberAddress(deviceId, address)` — zasila `knownAddresses`;
- `AndroidBleRadio.registerDevice(BluetoothDevice)` — zasila mapę urządzeń z typem adresu.

## 6. Magazyny (porty `:domain`)

| Port | Implementacja |
|---|---|
| `CredentialStore` | `K_client` opakowany AES-256-GCM kluczem z Android Keystore (alias `brama_wrap`, nieeksportowalny, `setUnlockedDeviceRequired(false)`, bez biometrii). Plik w `noBackupFilesDir`: wersja, stan, `device_id`, `client_id`, IV, szyfrogram. Zapis atomowy (plik tymczasowy + rename). Klucz Keystore niedostępny przed pierwszym odblokowaniem → `CredentialLoad.Locked` (spec 9.3). |
| `SettingsStore` | DataStore Preferences: `autoCloseDefault` (false), `gateName`, `presenceNotificationEnabled` (true). |
| `HistoryStore` | Plik JSON-lines w `filesDir`; `trim(200)`. |
| `RuleCacheStore` | DataStore (snapshot zakodowany przez `RuleSnapshot.encode`, czas odbioru). |
| `PresenceStore` | DataStore (`quiet`, `ruleSuppressed`). |
| `ConnectionKeeper` | §4. |
| `WallClock` | `System.currentTimeMillis`. |

Backup: `allowBackup=false`; `data_extraction_rules` i `backup_rules` wykluczają wszystko (spec 9.3, A58).

## 6a. Zmiany portów `:domain` (z testami w `:domain`)

- `SettingsStore`: `presenceNotificationEnabled: Flow<Boolean>`, `setPresenceNotificationEnabled`, `setGateName`.
- `HistoryStore`: `entries: Flow<List<HistoryEntry>>` (najnowsze na górze) dla ekranu historii.
- `GateRepository` udostępnia te dane i intencje (`setGateName`, `setPresenceNotificationEnabled`, `history`), aby UI nie sięgało do magazynów bezpośrednio.

## 7. Uprawnienia i problemy systemowe (spec 7, A27)

- **Manifest:**
  - `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT`, `POST_NOTIFICATIONS`;
  - `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`;
  - `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`, `REQUEST_COMPANION_RUN_IN_BACKGROUND`, `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`;
  - `uses-feature bluetooth_le`.
- **Prośby o uprawnienia:**
  - BT i powiadomienia przy pierwszym ekranie, który ich potrzebuje (kreator, ekran główny);
  - odmowa → `reportSystemProblem(BLUETOOTH_PERMISSION_MISSING)` i przycisk do ustawień aplikacji.
- **Stan Bluetooth:** `BroadcastReceiver` `ACTION_STATE_CHANGED` plus sprawdzenie w `ON_START` → `reportSystemProblem(BLUETOOTH_OFF / null)`.
- **Skaner Google:**
  - niedostępny (brak usług Google) → komunikat instrukcji i przejście do PIN-u;
  - `CAMERA_PERMISSION_MISSING` nie występuje, bo skaner nie wymaga uprawnienia.

## 8. Składanie zależności

Ręcznie w `BramaApp : Application`:
- jeden `CoroutineScope` (`SupervisorJob + Dispatchers.Default`);
- `AndroidBleRadio`, `BleGateLink`, magazyny, `GateRepository`;
- `repository.start()` w `onCreate`.

Bez Hilt/Koin (mała aplikacja). ViewModele dostają repozytorium przez fabrykę.

## 9. Teksty

Wszystkie `MessageId` → polskie teksty dokładnie z rozdziału 3 specyfikacji, w Kotlinie (`Texts.kt`), z parametrami (`N s`, przyczyna FAULT). Etykiety przycisków i instrukcje są poza listą zamkniętą. Test JVM sprawdza, że każdy `MessageId` ma tekst i że żaden tekst nie opisuje położenia.

## 10. Testowanie

- **JVM (`app/src/test`):**
  - teksty;
  - mapowanie `GateUiState` → model ekranu i model powiadomienia (akcje tylko przy `notificationActionsAvailable`, treść z listy);
  - decyzja „usługa potrzebna”;
  - format pliku poświadczenia i historii (z wstrzykniętym szyfratorem);
  - ViewModele z fałszywym repozytorium przez `GateUiState`.
- **Bez testów automatycznych** (wymagają urządzenia lub płytki): Keystore, CDM, usługa, skaner, `AndroidBleRadio`. Sprawdzane kompilacją i listą kontrolną testów ręcznych w etapie 0 (A27, A58, A70–A73).
- **Build:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` z pobraniem nowych zależności (internet dostępny), potem jak dotąd.

## 11. Zależności (nowe)

`androidx.navigation:navigation-compose`, `androidx.lifecycle:lifecycle-viewmodel-compose`, `androidx.lifecycle:lifecycle-runtime-compose`, `androidx.datastore:datastore-preferences`, `com.google.android.gms:play-services-code-scanner`, `kotlinx-coroutines-android`, `material-icons-core`. Wersje przypięte w `libs.versions.toml` (aktualne stabilne w chwili implementacji).

## 12. Moje decyzje (do sprawdzenia)

1. Navigation Compose, DataStore, ręczne DI, historia w pliku JSON-lines.
2. Poświadczenie w pliku w `noBackupFilesDir`, opakowane kluczem Keystore bez biometrii.
3. Powiązanie CDM po udanej rejestracji, a nie przed nią (w trakcie rejestracji `device_id` przy PIN-ie jest jeszcze nieznany).
4. Mała zmiana `:ble`: zasilanie znanego adresu i `BluetoothDevice` z powiązania CDM.
5. Przełącznik powiadomienia w ustawieniach, domyślnie włączony.
6. Teksty w Kotlinie zamiast `strings.xml` (aplikacja tylko po polsku, test kompletności na JVM).
7. Usługa sterowana obserwatorem stanu w `Application`, nie samymi intencjami.
8. Powiązanie CDM po dokładnym adresie sterownika z `:ble` (z przeglądu projektu).
9. Brak zgody na powiadomienia = funkcja „w zasięgu” wyłączona: bez cichych połączeń w tle (z przeglądu projektu).
10. Porty `:domain` rozszerzone o ustawienia (nazwa, powiadomienie) i odczyt historii, z testami (z przeglądu projektu).

## 13. Odstępstwa przy implementacji

- `PresenceStore` udostępnia `DataStoreSettings.presence`: metody `load()` `RuleCacheStore` i `PresenceStore` kolidują w jednej klasie.
- Jeden `GateViewModel` (AndroidViewModel) dla wszystkich ekranów zamiast fabryki per ekran.
- `CompanionAssociation` pobiera `device_id` z `CredentialStore`.
- `AppGraph` obserwuje przełącznik powiadomienia i od razu odświeża `PresenceGate`.
- Po przeglądzie modułu `:app`:
  - `GateUiState.backgroundLinkNeeded` (z `ConnectionPolicy.shouldConnectWithoutPhoneUi`) decyduje o usłudze zamiast reguły odtwarzanej w `:app`. Chwilowe DISCONNECTED przy ponownym łączeniu właściciela nie zatrzymuje usługi. Usługa zatrzymuje się przez `stopSelfResult(startId)`.
  - Powiązanie CDM dotyczy tylko bieżącej bramy: usuwane przy starcie bez poświadczenia i po utworzeniu nowego (anulowanie okna systemowego nie kasuje starego). Obecność jest przekazywana tylko przy ACTIVE.
  - `ScanFilter.setDeviceAddress(address)` w publicznym API zakłada adres publiczny (wariant z typem adresu jest `@SystemApi`). Dopasowanie adresu static random w dialogu CDM sprawdzić w etapie 0.
  - Historia w `noBackupFilesDir` (nie `filesDir`).
- Po ponownym przeglądzie:
  - po utracie łącza nieaktualna własna reguła przestaje utrzymywać usługę i łączenie po `max(30 s, remainingMs) + 30 s` (`domain_design` §6.2, spec 4.2, A73);
  - „Usuń z telefonu” zachowuje ustawienie powiadomienia „w zasięgu”;
  - wyjście z kreatora zawsze zamyka wynik rejestracji;
  - powiązanie CDM można założyć także z ustawień (gdy powiadomienie „w zasięgu” jest włączone, a brama niepowiązana; przycisk aktywny przy READY);
  - domena odczytuje ustawienie powiadomienia przed poświadczeniem — zimny start z obecności nie pokazuje powiadomienia przy wyłączonej funkcji.

## 14. Aktualizacja firmware (spec 1.5)

Opis w `doc/ota_design.md` §6: sekcja „Firmware” w ustawieniach (wersja, wybór pliku przez `OpenDocument`, odczyt z limitem `0x1E0000 + 1` B), ekran `FirmwareUpdateScreen` (trasa `firmware`), (wiersz stanu aktualizacji w powiadomieniu usługi usunięty 17.11.2026; postęp tylko na ekranie aktualizacji). Usługa wynika z `backgroundLinkNeeded` — bez zmian w `serviceNeeded`.

## 15. Wygląd (17.11.2026, propozycja zaakceptowana przez właściciela)

- **Motyw marki** (`ui/theme`): stałe kolory zamiast kolorów dynamicznych systemu, jasny i ciemny: turkus `#0E7C86` (ciemny: `#6FD3DB`), granat (`#27475C`, `#12232E`), zieleń `#1F9D55` dla OTWÓRZ, granat/niebieski `#27475C` / `#3E7CA3` dla ZAMKNIJ, bursztyn `#F2A413` dla stanów przejściowych, szary dla braku połączenia. Dopisane kolory kontenerów i konturów (bez nich Material 3 podstawia fiolet).
- **Grafika:** `GateIllustration` rysowana w Compose (bez zasobów rastrowych): dwuskrzydłowa brama wjazdowa z murkami, słupkami i lampkami w kolorze stanu (zielony: połączono, bursztynowy: łączenie, pulsujące tło; szary: brak połączenia). Używana na ekranie głównym, w kreatorze, w historii (pusty stan) i na ekranie aktualizacji firmware.
- **Ikona aplikacji:** adaptacyjna (tło granatowy gradient, brama w tym samym stylu, warstwa monochromatyczna dla ikon motywowanych); mała ikona powiadomienia `ic_stat_gate`.
- **Wspólne elementy** (`ui/Common.kt`): `brandBackground` (gradient), `BackBar` (przezroczysty, tytuł wyśrodkowany), `ScreenColumn` (max 560 dp), `SectionCard` (karta z tytułem w kolorze marki), `PrimaryAction` / `SecondaryAction` / `DangerAction` (zaokrąglone 20 dp), `IllustrationHeader`. `Scaffold` z przezroczystym tłem musi mieć jawny `contentColor = onBackground` (inaczej tekst poza kartą jest czarny).
- **Ekrany:** ustawienia w kartach („Działanie”, „Brama”, „Firmware”, „Rejestracja telefonów”, „Reset autoryzacji sterownika”, „Usuń z telefonu” jako kontur w kolorze błędu); historia jako karty z kolorową kropką (zielona OTWÓRZ, niebieska ZAMKNIJ); kreator z grafiką wyśrodkowaną w pionie w trakcie rejestracji.
- **Nawigacja:** „wstecz” zdejmuje ekran tylko, gdy istnieje poprzedni (`previousBackStackEntry`); inaczej podwójne dotknięcie „Gotowe” zostawiało pusty stos (biały ekran).

## 16. Telefony z dostępem (18.11.2026)

Opis w `doc/client_management_design.md` §5.4: karta w Ustawieniach, ekran `ClientsScreen` (trasa `clients`, w stylu z §15: ilustracja nagłówka, `SectionCard` na wiersz, `DangerAction` „Usuń”), dialog potwierdzenia usunięcia, dialog zmiany nazwy (limit 20 znaków), stany ładowania i błędu. Nazwa domyślna: `Build.MANUFACTURER` + `Build.MODEL` przycięte do 20 B po granicy znaku UTF-8, wysyłana po pierwszej sesji READY po rejestracji. Bez zgodności ze starym firmware: wdrożenie przez reset autoryzacji i ponowną rejestrację.
