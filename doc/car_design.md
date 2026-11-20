# Projekt modułu `:car` (Android Auto)

> **Zmiana 19.11.2026:** ekran samochodowy (§4.1) ma stylistykę telefonu: grafika bramy (`gate_illustration.xml`), jedna linia stanu połączenia z kropką w kolorze stanu, OTWÓRZ (zielony, `FLAG_PRIMARY`) i ZAMKNIJ; bez pozostałych tekstów statusów, bez przycinania wierszy według `ConstraintManager`. Poniższe opisy wierszy i limitów są historyczne. Powiadomienie w samochodzie (§4.2) też ma tylko nazwę bramy i linię stanu połączenia (`UiMessages.carNotification(state)`; bez położenia, problemów i wyniku komendy; przy innym wyniku niż OUTPUT_TRIGGERED zostaje z przyciskiem, bez tekstu wyniku).

Data: 27.10.2026. Specyfikacja: `doc/PLAN_I_SPECYFIKACJA_BRAMY.md` 1.4, rozdz. 3, 4.3 i 5. Gotowe moduły: `:domain` (`UiMessages.car`, intencje `UiSource.CAR`, automat obecności), `:app` (`AppGraph`, `PresenceGate`, usługa połączenia).

## 1. Cel

Sterowanie bramą z ekranu samochodu, bez dotykania telefonu:

- **Powiadomienie przy wjeździe.** Telefon wykrywa sterownik (jak w spec 4.3). Gdy trwa projekcja Android Auto, na ekranie auta pojawia się powiadomienie z jednym przyciskiem OTWÓRZ.
- **Pełny ekran aplikacji w samochodzie.** Stan i oba przyciski OTWÓRZ/ZAMKNIJ, jak na telefonie, ale bez ustawień, parowania i rozłączania.

**Nie-cele:** parowanie, QR, PIN, ustawienia, „Rozłącz/Połącz” i historia w samochodzie (spec 5.2); Android Automotive OS; dystrybucja w Google Play (wymaga przeglądu jakości — Etap 5).

**Anty-cele:**
- operacje BLE w `onGetTemplate()`;
- tekst spoza listy zamkniętej rozdziału 3;
- sugerowanie położenia bramy;
- utożsamianie końca projekcji Android Auto z utratą łącza do sterownika (spec 5.2: koniec projekcji nie wywołuje autozamykania).

## 2. Decyzje właściciela (27.10.2026)

- **Powiadomienie w samochodzie:** pojawia się, gdy telefon wykryje sterownik w zasięgu i trwa projekcja Android Auto. Ma jeden przycisk OTWÓRZ. OPEN używa ustawienia autozamykania z aplikacji na telefonie.
- **Znikanie powiadomienia:**
  - po potwierdzeniu otwarcia przez sterownik;
  - po odjechaniu i utracie połączenia telefonu ze sterownikiem.
- **Wynik inny niż potwierdzenie** (odmowa, zajęty, nieznany): powiadomienie zostaje z wynikiem i pozwala spróbować ponownie (moje przypuszczenie, potwierdzone przez właściciela).
- **Ekran w samochodzie:** pełny widok jak na telefonie — status i oba przyciski. Bez ustawień, parowania i rozłączania.

## 3. Zmiana specyfikacji (do wprowadzenia w ramach tej pracy)

Nowy punkt **5.4 „Powiadomienie w samochodzie”**:
- warunki pojawienia się: powiadomienie z 4.3 w stanie SHOWN i aktywna projekcja Android Auto;
- jeden przycisk OTWÓRZ, działający jak przycisk OTWÓRZ z 4.3 (domyślny `autoCloseOnDisconnect`);
- zniknięcie po wyniku OUTPUT_TRIGGERED dowolnego OPEN wysłanego w bieżącym cyklu SHOWN (z samochodu albo z telefonu), po wyjściu z SHOWN (zniknięcie z zasięgu po odjechaniu, odrzucenie powiadomienia na telefonie albo wyjście ekranu telefonu na pierwszy plan), po końcu projekcji albo po odrzuceniu przez kierowcę;
- chwilowa utrata łącza w zasięgu nie ukrywa powiadomienia: pokazuje stan łączenia bez przycisku, a przycisk wraca w READY;
- przy innym wyniku zostaje z tekstem wyniku z listy i przyciskiem.

Treść wyłącznie z listy zamkniętej. Testy akceptacyjne A74 (pojawienie się i otwarcie) oraz A75 (zniknięcie po wyjeździe, chwilowa utrata łącza, wynik inny niż potwierdzenie, odrzucenie przez kierowcę). Przy okazji poprawka Etapu 4 pkt 3 („CLOSE z potwierdzeniem” → CLOSE bez potwierdzenia, decyzja 1.4).

## 4. Architektura

**Moduł `:car`:** Android library `com.agendek.bramav2.car`, minSdk 33, compileSdk 37. Zależności: `:domain`, `androidx.car.app:app` 1.7.0, `androidx.car.app:app-projected` 1.7.0, coroutines. `:app` zależy od `:car`. Manifest `:car` (scalany z `:app`) deklaruje usługę, odbiornik i zasoby.

**Dostęp do repozytorium:** `:car` nie zależy od `:app`. Definiuje `interface CarDependencies { val repository: GateRepository }`, które implementuje `BramaApp` (Application). Usługa i odbiornik `:car` biorą zależności z `applicationContext as CarDependencies`.

**Teksty:** `Texts` przenoszę z `:app` do `:domain` (pakiet `com.agendek.bramav2.domain`), bo potrzebują go telefon i samochód. Dochodzi wariant samochodowy `Texts.car(message)` — „Położenie nieznane” zamiast „Położenie bramy: nieznane” (spec 3). Test kompletności przenosi się razem z kodem.

### 4.1. `GateCarAppService` i ekran

- **`GateCarAppService : CarAppService`:**
  - kategoria `androidx.car.app.category.IOT`;
  - `createHostValidator()`: w debug `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` (DHU), w release lista zaufanych hostów z biblioteki (`R.array.hosts_allowlist_sample`) (spec 5.1);
  - `automotive_app_desc.xml` z `<uses name="template"/>`;
  - `minCarApiLevel` zatwierdzić w Etapie 0 (DHU) — domyślnie 1.
- **`GateSession` → `GateScreen`:**
  - `PaneTemplate`: tytuł = nazwa bramy;
  - wiersze z `UiMessages.car(state)` przez `Texts.car`, przycięte do limitu `ConstraintManager.CONTENT_LIMIT_TYPE_PANE` od końca;
  - pozycje (1) i (2) z 5.2 zostają zawsze — przy limicie < 2 łączone w jednym wierszu.
- **Akcje (spec 5.2):**
  - OTWÓRZ → `repository.open()`;
  - ZAMKNIJ → `repository.requestClose(CAR)`;
  - aktywne według `controlsEnabled`;
  - bez akcji, gdy pierwszym komunikatem jest CONFIGURE_ON_PHONE.
- **Cykl życia i odświeżanie:**
  - `uiAttached(CAR)` w `onStart` ekranu, `uiDetached(CAR)` w `onStop` (spec 4.2);
  - `invalidate()` po każdej zmianie modelu ekranu (zbieranie `state` w `lifecycleScope`, deduplikacja);
  - żadnych operacji w `onGetTemplate()` poza odczytem ostatniego modelu.

### 4.2. Powiadomienie w samochodzie (`CarPresenceNotifier`)

- Obserwuje `repository.state` i stan projekcji (`CarConnection(context).type`, `CONNECTION_TYPE_PROJECTION`).
- Polityka `CarNotificationPolicy` (testowana na JVM) z małym stanem cyklu: wejścia `GateUiState`, `projecting` i zdarzenie „kierowca odrzucił”; wyjście: model powiadomienia albo `null`. Na początku cyklu (przejście do SHOWN) zapamiętuje bieżące `lastCommand`, aby wynik z poprzedniego pobytu przy bramie nie ukrył nowego powiadomienia.
  - Pokazane, gdy `presence == SHOWN`, `projecting` i nie zostało odrzucone w tym cyklu obecności.
  - Przycisk OTWÓRZ tylko przy `notificationActionsAvailable`.
  - Treść:
    - nazwa bramy;
    - stan połączenia;
    - „Położenie nieznane”;
    - wynik ostatniej komendy z tego cyklu, z listy.
  - **Zamknięcie w cyklu:** `lastCommand` różny od zapamiętanego na początku cyklu, będący OPEN z wynikiem OUTPUT_TRIGGERED (OPEN z dowolnego źródła), albo odrzucenie przez kierowcę (`setDeleteIntent` → odbiornik). Przy wyjściu z SHOWN cykl się kończy i znacznik zeruje.
  - **Ukrycie:**
    - `presence != SHOWN` (zniknięcie z zasięgu po odjechaniu, wyciszenie);
    - koniec projekcji.
  - **Chwilowa utrata łącza** w zasięgu nie ukrywa powiadomienia: zostaje stan łączenia, bez przycisku do READY.
- **Wysyłka:** `NotificationCompat` z `CarAppExtender` (tytuł, treść, akcja OTWÓRZ z `PendingIntent` do `CarNotificationActionReceiver` → `repository.notificationOpen()`). Kanał „Brama w zasięgu (samochód)” o wysokiej ważności (powiadomienie typu heads-up). `CarNotificationManager.from(context).notify/cancel`.
- Działa w procesie aplikacji: uruchamiany przez `BramaApp` (obok `ServiceController`). Proces trzyma usługa połączenia, bo SHOWN oznacza, że usługa działa.
- **Zgoda na powiadomienia:** jak w `:app` — bez zgody nie ma SHOWN (bramka obecności), więc nie ma też powiadomienia w samochodzie.

## 5. Testowanie

- **JVM (`car/src/test`):**
  - `CarNotificationPolicy`: pojawienie się przy SHOWN i projekcji; brak bez projekcji; przycisk tylko przy `notificationActionsAvailable`; zniknięcie po OUTPUT_TRIGGERED OPEN z cyklu (także z telefonu) i ponowne pojawienie się dopiero w nowym cyklu; wynik OPEN z poprzedniego cyklu nie ukrywa nowego; pozostaje przy BUSY/OUTCOME_UNKNOWN z tekstem wyniku; chwilowa utrata łącza — bez przycisku, ale widoczne; zniknięcie przy wyjściu z SHOWN i końcu projekcji; odrzucenie przez kierowcę do końca cyklu.
  - Model ekranu `carPane(state, rowLimit)`: kolejność z 5.2, przycinanie od końca, łączenie (1)+(2) przy limicie 1, brak akcji przy CONFIGURE_ON_PHONE, akcje według `controlsEnabled`, „Położenie nieznane” w wariancie samochodowym.
  - Teksty po przeniesieniu do `:domain`.
- **Bez testów automatycznych:** `CarAppService`, `Session`, `Screen`, `CarConnection`, `CarNotificationManager` — kompilacja plus lista kontrolna DHU i auta (spec 5.3, A73–A74) w Etapie 0.

## 6. Moje decyzje (do sprawdzenia)

1. `Texts` przeniesione do `:domain` (wspólne dla telefonu i samochodu) z wariantem `Texts.car`.
2. Dostęp `:car` do repozytorium przez interfejs `CarDependencies` implementowany przez `BramaApp`.
3. Przycisk OTWÓRZ w samochodzie wywołuje `notificationOpen()` — ta sama ścieżka co w powiadomieniu telefonu (domyślny boolean).
4. Powiadomienie samochodowe jest osobnym powiadomieniem (`CarAppExtender`) obok powiadomienia telefonu, tylko w czasie projekcji.
5. Chwilowa utrata łącza nie ukrywa powiadomienia (tylko przycisk do READY); odjechanie kończy cykl przez zniknięcie z zasięgu (z przeglądu projektu).
8. Powiadomienie znika po potwierdzonym OPEN z dowolnego źródła w bieżącym cyklu — brama już otwarta, przycisk niepotrzebny (z przeglądu projektu).
9. Odrzucenie powiadomienia przez kierowcę ukrywa je do końca cyklu (z przeglądu projektu).
10. W samochodzie skrócony jest tylko tekst położenia; pozostałe teksty z listy w pełnej postaci, a kolejność wierszy z 5.2 chroni najważniejsze przy obcinaniu przez host — do sprawdzenia na DHU w Etapie 0 (z przeglądu projektu).
6. Debug — dowolny host (DHU); release — lista hostów z biblioteki.
7. `minCarApiLevel = 1` do potwierdzenia w Etapie 0.

## 7. Odstępstwa przy implementacji

- `PaneTemplate.setTitle/setHeaderAction` (przestarzałe) zamiast `Header`, bo `Header` wymaga Car App API 7, a `minCarApiLevel` = 1.
- `Action.setEnabled` tylko przy Car App API ≥ 5; na starszych hostach domena i tak odrzuca komendę bez sesji READY.
- `CarDependencies` udostępnia też `carNotifier` (odbiornik akcji „odrzucono”).
