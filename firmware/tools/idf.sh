#!/usr/bin/env bash
# Uruchamia idf.py z ESP-IDF v6.0.1. Skrypt aktywacji działa tylko „sourced” w powłoce interaktywnej,
# więc zmienne środowiska pobieramy jego trybem -e.
set -e
ACT="$HOME/.espressif/tools/activate_idf_v6.0.1.sh"
while IFS='=' read -r k v; do
    case "$k" in
        # Katalogi */<triplet>/<triplet>/bin zawierają binutils bez prefiksu (np. `as` dla Xtensa), które
        # przesłaniałyby systemowe narzędzia potrzebne targetowi `linux` — pomijamy je.
        PATH) v=$(printf '%s' "$v" | tr ':' '\n' | grep -v -E '/([a-z0-9-]+-elf)/\1/bin/?$' | paste -sd:)
              export PATH="$v:$PATH" ;;
        SYSTEM_PATH|"") ;;
        *) export "$k=$v" ;;
    esac
done < <(bash "$ACT" -e)
exec "$IDF_PYTHON_ENV_PATH/bin/python" "$IDF_PATH/tools/idf.py" "$@"
