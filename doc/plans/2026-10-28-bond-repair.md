# Plan: naprawa bondu przy zachowanym `K_client`

Projekt: `doc/bond_repair_design.md` (zatwierdzony 28.10.2026). Kolejność: TDD w każdym kroku, potem niezależny przegląd i dokumentacja.

1. **Firmware — sesja.** `session_admit` w `brama_session_env_t` (kod odmowy + flaga naprawy), stan `BRAMA_SS_REPAIR_COMMITTING`, zdarzenie `BRAMA_EV_REPAIR_COMMIT`, `brama_session_repair_finish`. Testy w `test_session.c` na wektorach `session.txt`.
2. **Firmware — aktor.** `BRAMA_AMSG_REPAIR_COMMIT`, `on_repair_commit`, `env.repair_result`; wspólne sprawdzenie blokad `brama_actor_repair_blocker`. Testy w `test_actor.c` (podmiana bondu, blokady, NVS, generacja).
3. **Firmware — `brama_ble`.** `on_encrypted` (łącze ze stagingiem = CANDIDATE), `env_client_key`/`env_session_admit`, obsługa zdarzenia i wyniku, rozłączenie po odmowie, `ble_gap_unpair` starego adresu. Kompilacja na Ubuntu.
4. **`:ble`.** `GateLink.repairBond`, `LinkEvent.BondMissing`/`BondFailed`; symulator z bondem; testy rdzenia.
5. **`:domain`.** `MessageId` (4 nowe), teksty, `bondRepairAvailable`, intencja `repairBond()`, obsługa zdarzeń; testy.
6. **`:app`.** Przycisk „Napraw połączenie” na ekranie sterowania; etykieta.
7. **Przegląd** niezależnym agentem, poprawki z testami.
8. **Dokumentacja:** spec rozdz. 3 (teksty) i 9.5 (przepływ telefonu), `implementation_status.md`, `ble_design.md`, `domain_design.md`, `app_design.md`, lista etapu 0 w `firmware/README.md`.
