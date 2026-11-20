# Projekt: przypadki negatywne wektorów zgodności (spec 16.3)

Status: zatwierdzony 28.10.2026 (decyzje §5 przyjęte bez zmian); zaimplementowany. Data: 28.10.2026.
Specyfikacja: `doc/PLAN_I_SPECYFIKACJA_BRAMY.md` 16.3 (lista wymaganych wektorów), 10.2, 10.3, 12.1–12.4, 13.1–13.4.
Stan wyjściowy: `protocol/src/test/resources/vectors/` — scenariusze poprawnych wymian (`session`, `pair_qr`, `pair_pin`, `commands`, `ota`, `fragments`, `reject`), generowane przez `VectorScenarios` (Kotlin, JCA + BouncyCastle), odtwarzane przez firmware (`firmware/test/host/main/test_session.c`, `test_proto.c`, mbedTLS/PSA).

## 1. Cel

Dopisać do wspólnych plików wektorów przypadki negatywne z 16.3 jako **jawne bajty z oczekiwanym wynikiem**, sprawdzane po obu stronach: firmware jako serwer (ramki c2s), telefon jako klient (ramki s2c). Dziś część z nich firmware testuje, przerabiając bajty w C, a telefon — przez `FakeGate`; wspólne bajty usuwają ryzyko, że obie strony testują różne rzeczy.

**Nie-cele:** zmiana protokołu lub zachowania którejkolwiek strony (rozbieżność to znalezisko do decyzji, nie poprawka „pod wektor”); plik JSON (zostaje format `klucz=hex`, decyzja z firmware).

## 2. Format

Nowe pliki w `vectors/`, ten sam format `klucz=hex`. Każdy przypadek c2s (wejście sterownika) ma:

- `<przypadek>_frame` — ramka wejściowa;
- `<przypadek>_expect` — 1 B: `00` ignorowana (bez odpowiedzi i zdarzenia), `01` unieważnienie sesji (bez odpowiedzi), `02` odpowiedź (`<przypadek>_reply` — dokładne bajty), `03` komenda przekazana aktorowi (`<przypadek>_type`, `<przypadek>_plain`);
- opcjonalnie `<przypadek>_ack_plain` i `<przypadek>_ack` — odpowiedź aktora (ACK) i jej zaszyfrowana ramka, gdy wynik ACK jest częścią przypadku.

Przypadki s2c (wejście telefonu) mają `<przypadek>_frame` i wynik opisany w README (sprawdza go test Kotlina; firmware sprawdza bajty, które sam generuje — snapshoty, ACK, RULE_STATUS).

## 3. Przypadki

### 3.1. `negative_session.txt` — sesja READY (baza: klucze i handshake jak w `commands.txt`)

Każdy przypadek na świeżo ustanowionej sesji.

| Przypadek | Wejście | Oczekiwanie |
|---|---|---|
| `header_bit` | OPEN(false) seq 1, zmieniony bit typu (0x01 → 0x03): nieznany typ, tag GCM nie pasuje | `01` |
| `payload_bit` | OPEN(false), zmieniony bit ciphertextu | `01` |
| `tag_bit` | OPEN(false), zmieniony bit tagu | `01` |
| `seq_gap` | poprawne OPEN z seq 2 | `01` |
| `same_seq_changed` | po przyjęciu OPEN(false) seq 1 — poprawnie zaszyfrowany CLOSE z seq 1 | `01` |
| `prev_session` | poprawne OPEN z kluczami i `session_id` innej (poprzedniej) sesji | `00` |
| `version1` | OPEN z bajtem wersji `01` | `01` |
| `wrong_direction` | PONG (typ s2c) zaszyfrowany kluczem c2s | `01` |
| `reject_via_rx` | `HANDSHAKE_REJECT` przez RX | `01` |
| `open_bool_02` | OPEN z payloadem `02` | `03`; ACK INVALID_ARGUMENT |
| `close_extra_byte` | CLOSE z payloadem `00` | `03`; ACK INVALID_ARGUMENT |
| `unknown_type` | poprawny GCM, typ `0x60` | `03`; ACK UNSUPPORTED_TYPE |
| `rekey` | nowy poprawny SESSION_BEGIN (świeży nonce) na READY, potem CONFIRM | `02` CHALLENGE (+ unieważnienie starej sesji), potem `02` READY nowej sesji |

### 3.2. `negative_handshake.txt` — bez sesji

| Przypadek | Wejście | Oczekiwanie |
|---|---|---|
| `begin_wrong_device_id` | SESSION_BEGIN z HMAC policzonym z innym `device_id` | `02` AUTH_FAILED (`session_id` 0) |
| `begin_unknown_client` | SESSION_BEGIN nieznanego `client_id` | `02` AUTH_FAILED |
| `begin_wrong_key` | SESSION_BEGIN z HMAC innego `K_client` | `02` AUTH_FAILED |
| `confirm_without_begin` | SESSION_CONFIRM bez BEGIN | `02` AUTH_FAILED |
| `reflection` | CHALLENGE sterownika odesłany przez RX | `02` AUTH_FAILED |
| `pake_bad_zkp` | PAKE_ROUND1 klienta Kotlina ze zmienionym bajtem dowodu ZKP | `02` AUTH_FAILED |
| `pake_outside_window` | poprawne PAKE_ROUND1 przy zamkniętym oknie | `02` AUTH_FAILED |

### 3.3. `negative_client.txt` — wejście telefonu (s2c)

| Przypadek | Oczekiwanie (telefon) | Firmware |
|---|---|---|
| `status_before_ack` — po OPEN(true): RULE_STATUS (seq 2), potem ACK (seq 3) | status przyjęty, potem wynik komendy | identyczne bajty z `seal_event` i `respond` |
| `old_ack_after_status` — kopia ACK po nowszym RULE_STATUS | duplikat, snapshot się nie cofa | — |
| `ack_bad_tag`, `ack_wrong_ref` (ACK do seq 9) | unieważnienie sesji | — |
| `reject_unknown_code` (`0x7F`), `reject_other_sid` | jak AUTH_FAILED; ignorowana | — |
| `snapshot_<faza>_<właściciel>_<przyczyna>` — wszystkie poprawne kombinacje 12.2 (15 B jawnie) | dekodowanie | identyczne bajty z kodera snapshotu |
| `snapshot_invalid_*` — niespójne kombinacje (np. DISARMED z SELF) | odrzucenie | — |
| `ack_<wynik>` — jawny COMMAND_ACK (25 B) dla każdego wyniku 13.2 | dekodowanie | identyczne bajty z kodera ACK |

### 3.3a. Zarządzanie telefonami (18.11.2026, `doc/client_management_design.md`)

Nowe przypadki jawnych bajtów w `commands.txt` i `negative_client.txt`: `list_clients` (c2s, 0 B) i `client_list_1`, `client_list_8` (s2c, każda kombinacja flag, nazwy 0/20 B), `remove_client`, `set_client_name` (nazwa 0, 20 B), `ack_<wynik>` dla `09`–`0D`, negatywne: `set_client_name_21b`, `set_client_name_bad_utf8`, `remove_client_short_payload` (INVALID_ARGUMENT), `client_list_count_over_8` i `client_list_bad_len` (odrzucenie po stronie telefonu).

### 3.4. `negative_fragments.txt` (10.2) — obie strony

| Przypadek | Oczekiwanie |
|---|---|
| `restart_new_tid` — fragment 0 (tid 1) ramki 3-fragmentowej, potem komplet tej samej ramki z tid 2 | kompletna ramka z tid 2 |
| `restart_same_tid` — fragment 0 (tid 1), ponownie fragment 0 (tid 1), potem fragmenty 1 i 2 | błąd kolejności, brak ramki |

### 3.5. Pokryte gdzie indziej, bez nowych bajtów

- Duplikat komendy i OTA_DATA z cache — istniejący test firmware na `commands.txt` (`duplicate_frame_returns_cached_response`) i telefonu (`ClientSessionTest`, `ClientSessionOtaTest`).
- Zły PIN, jednorazowość `K_pin`, piąta nieudana próba zamykająca okno — wymagają rund serwera, a firmware losuje je z PSA bez możliwości wstrzyknięcia skalarów. Zostają testy firmware z klientem `mbedtls_ecjpake` (`k_pin_is_single_use`) i testy telefonu z `FakeGate`; zapisane w README jako wyjątek.
- MTU 23/247/517 i ramka 512 B — `fragments.txt`. Każdy kod `HANDSHAKE_REJECT` — `reject.txt`. Każdy wynik OTA_ACK — `ota.txt`.

## 4. Testy

- `:protocol`: generator w `VectorScenarios` (dalej pilnowany przez `vectorScenariosMatchCurrentImplementation`) i nowy `NegativeVectorsTest` — telefon na przypadkach s2c (`ClientSession`, `Reassembler`, `RuleSnapshot.decode`, `CommandAck.decode`, odmowy) oraz kontrola, że przypadki c2s naprawdę są tym, co opisują (np. `FakeGate` daje oczekiwany wynik).
- Firmware: nowy `test_negative.c` — każdy przypadek c2s przez `brama_session_on_frame` na świeżej sesji, porównanie z `_expect`/`_reply`/`_plain`; kodery snapshotu i ACK na `snapshot_*`/`ack_*`; reasembler na `negative_fragments.txt`. Uruchamiany na Ubuntu (brak ESP-IDF na Macu).

## 5. Do decyzji

1. Format oczekiwań jako 1-bajtowy kod `_expect` w pliku `klucz=hex` (zamiast osobnego pliku opisów) — moja propozycja.
2. Przypadki PIN wymagające rund serwera zostają poza wspólnymi bajtami (§3.5) — chyba że firmware dostanie wstrzykiwane skalary EC-JPAKE w buildzie testowym (zmiana `brama_ecjpake`).
3. Rozbieżność wykryta przez wektory (firmware lub telefon zachowuje się inaczej niż spec) — zgłaszam do decyzji, nie zmieniam zachowania sam.
