# Wektory scenariuszy dla firmware

Deterministyczne wymiany protokołu v2 wygenerowane przez `:protocol` (klient) i `FakeGate` (serwer w teście).
Firmware odtwarza je w testach hostowych jako strona serwera (`firmware/test/host`).

Generacja (po zmianie protokołu): `./gradlew :protocol:test -PvectorsGen=true --tests '*VectorScenariosTest*'`.
Test `vectorScenariosMatchCurrentImplementation` pilnuje, że pliki odpowiadają bieżącej implementacji.

Format: jedna para `klucz=hex` na linię, pierwsza linia to komentarz `#`. Wymiany w kolejności: `c2s_N` (telefon →
sterownik) i `s2c_N` (sterownik → telefon); dla ramek AES-GCM dodatkowo `c2s_N_plain` / `s2c_N_plain` — jawny payload.

| Klucz | Znaczenie |
|---|---|
| `device_id`, `client_id` | Identyfikatory (16 B). |
| `k_client` | `K_client` (12.1); w plikach PAIR — klucz wydany w PAIR_GRANT. |
| `k_handshake` | Klucz HMAC handshake PAIR: `K_setup` (QR) albo `K_pin` (PIN). |
| `client_nonce`, `server_nonce` | Nonce handshake (32 B); `server_nonce` to losowość sterownika. |
| `session_id` | LE64 nadany w CHALLENGE (losowość sterownika). |
| `c2s_key`, `s2c_key`, `c2s_iv`, `s2c_iv` | Klucze sesji z HKDF (11.2/12.1). |
| `pin`, `pake_secret`, `k_pin` | PIN (ASCII), sekret `S` EC-JPAKE i `K_pin` (11.4). Skalary: SHA-256(`BRAMA/test/ecjpake/client` albo `/server` ‖ i) mod (n−1) + 1. |
| `image`, `image_sha256` | Obraz testowy OTA (471 B) i jego SHA-256 (15.5). |
| `ota_ack_<wynik>` | Payload OTA_ACK (14 B) dla każdego wyniku, `request_type = 51`, `request_seq = 9`, `next_offset = 940`. |
| `<mtu>_frame`, `<mtu>_frag_N` | Ramka i jej fragmenty GATT (10.2); `transfer_id` 1/2/255, interwał 30 ms. |
| `reject_<kod>_<begin|session>` | HANDSHAKE_REJECT z `session_id` 0 albo z CHALLENGE (12.4). |

Zarządzanie telefonami (client_management_design.md): `commands.txt` kończy się wymianami `SET_CLIENT_NAME`, `LIST_CLIENTS`, `REMOVE_CLIENT`, `LIST_CLIENTS` (klucze `other_client_id`, `third_client_id`); `negative_client.txt` ma jawne payloady `client_list_1`, `client_list_8` i niepoprawne `client_list_*`.

Pliki: `session.txt`, `pair_qr.txt`, `pair_pin.txt` (PAKE `c2s_1`–`s2c_2`, potem PAIR), `commands.txt`, `ota.txt`,
`fragments.txt`, `reject.txt`.

## Przypadki negatywne (spec 16.3, `doc/vectors_design.md`)

Sprawdzane po obu stronach: telefon — `NegativeVectorsTest` (`:protocol`), sterownik — `firmware/test/host/main/test_negative.c`.

Przypadek wejścia sterownika (c2s) ma `<przypadek>_frame` i `<przypadek>_expect` (1 B):

| `_expect` | Skutek po stronie sterownika |
|---|---|
| `00` | ramka ignorowana: bez odpowiedzi i zdarzenia, sesja bez zmian |
| `01` | unieważnienie sesji, bez odpowiedzi |
| `02` | odpowiedź dokładnie `<przypadek>_reply` |
| `03` | komenda przekazana aktorowi: `<przypadek>_type`, `<przypadek>_plain`; odpowiedź aktora `<przypadek>_ack_plain` szyfrowana do `<przypadek>_ack` |

| Plik | Zawartość |
|---|---|
| `negative_session.txt` | baza jak `commands.txt` (każdy przypadek na świeżej sesji): `header_bit`, `payload_bit`, `tag_bit`, `seq_gap`, `same_seq_changed` (po `same_seq_changed_first`), `prev_session`, `version1`, `wrong_direction`, `reject_via_rx`, `open_bool_02`, `close_extra_byte`, `unknown_type`; `rekey_*` — nowy BEGIN na READY (losowość sterownika `rekey_session_id`, `rekey_server_nonce`), CHALLENGE z unieważnieniem starej sesji, potem READY |
| `negative_handshake.txt` | bez sesji, wszystkie `02` z AUTH_FAILED: `begin_wrong_device_id`, `begin_unknown_client`, `begin_wrong_key`, `confirm_without_begin`, `reflection` (CHALLENGE przez RX), `pake_bad_zkp`; `pake_outside_window` — przy zamkniętym oknie |
| `negative_client.txt` | wejście telefonu: `status_before_ack_*` (RULE_STATUS przed ACK — status przyjęty, potem wynik; bajty generuje też sterownik), `old_ack_after_status_status` + kopia `status_before_ack_ack` (duplikat, stan się nie cofa), `ack_bad_tag_frame`/`ack_wrong_ref_frame` po `ack_c2s` (unieważnienie), `reject_unknown_code_frame` (jak AUTH_FAILED), `reject_other_sid_frame` (ignorowana); `snapshot_<faza>_<właściciel>_<przyczyna>` — wszystkie 16 poprawnych kombinacji 12.2, `snapshot_invalid_*` — 7 niespójnych; `ack_<wynik>` — jawny COMMAND_ACK każdego wyniku 13.2 |
| `negative_fragments.txt` | `restart_new_tid_in_*` — fragment 0 z nowym `transfer_id` zaczyna nową ramkę (wynik `frame`); `restart_same_tid_in_*` — fragment 0 z tym samym `transfer_id` to błąd kolejności, brak ramki |

Poza wspólnymi bajtami (rundy serwera EC-JPAKE losowane z PSA w firmware): zły PIN, jednorazowość `K_pin` i piąta
nieudana próba zamykająca okno — testy firmware z klientem `mbedtls_ecjpake` (`test_session.c`) i telefonu z `FakeGate`.
