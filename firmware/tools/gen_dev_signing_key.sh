#!/usr/bin/env bash
# Tworzy deweloperski klucz podpisu obrazów (ECDSA P-256, spec 15.5) poza repozytorium, tylko gdy go nie ma.
# Klucz produkcyjny jest osobny i opisuje go instrukcja produkcyjna (Etap 5).
set -euo pipefail
KEY="${BRAMA_SIGNING_KEY:-$HOME/.config/brama/dev_signing_key.pem}"
LINK="$(cd "$(dirname "$0")/.." && pwd)/keys/dev_signing_key.pem"
if [ -f "$KEY" ]; then
    echo "Klucz istnieje: $KEY"
else
    mkdir -p "$(dirname "$KEY")"
    (umask 077 && "$HOME/.espressif/tools/python/v6.0.1/venv/bin/python" -m espsecure generate-signing-key --version 1 --scheme ecdsa256 "$KEY")
    echo "Utworzono klucz: $KEY"
fi
# Build szuka klucza względem katalogu projektu (CONFIG_SECURE_BOOT_SIGNING_KEY); w repozytorium jest tylko dowiązanie.
mkdir -p "$(dirname "$LINK")"
ln -sfn "$KEY" "$LINK"
