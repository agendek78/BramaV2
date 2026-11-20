# Plan: okno rejestracji otwierane z telefonu

Projekt: `doc/phone_window_design.md` (zatwierdzony 28.10.2026). TDD w każdym kroku, potem przegląd i dokumentacja.

1. **`:protocol`** — `MessageType.OPEN_PAIRING_WINDOW` (0x03), `AckResult.WINDOW_OPENED` (8), `SessionRequest.OpenPairingWindow`; wektory: przypadek w `commands.txt`, `ack_window_opened` w `negative_client.txt`; `FakeGate`.
2. **Firmware** — `BRAMA_MSG_OPEN_PAIRING_WINDOW`, `BRAMA_ACK_WINDOW_OPENED`, aktor (okno, licznik PIN-u raz na godzinę), INFO `0xFF`; testy aktora/limitów i wektory.
3. **`:ble`** — `GateInfo.supportsPhoneWindow`, `LinkInfo.pairingWindowSupported`; symulator.
4. **`:domain`** — `openPairingWindow()`, `pairingWindowAvailable`, `PAIRING_WINDOW_OPENED`, zmienione teksty WINDOW_CLOSED i BOND_MISSING.
5. **`:app`** — pozycja w Ustawieniach.
6. Przegląd, dokumentacja (spec 1, 3, 8.2, 9.1, 9.4, 11.4, 13.1, 13.2, testy akceptacyjne; status).
