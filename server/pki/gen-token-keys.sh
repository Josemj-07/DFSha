#!/bin/sh
# Claves Ed25519: jwt.* firma sesiones y blocktoken.* firma block tokens.
# Uso: gen-token-keys.sh <directorio-salida>
set -eu
OUT="$1"; mkdir -p "$OUT"
for k in jwt blocktoken; do
  [ -f "$OUT/$k.key" ] || openssl genpkey -algorithm ed25519 -out "$OUT/$k.key"
  chmod 600 "$OUT/$k.key"
  openssl pkey -in "$OUT/$k.key" -pubout -out "$OUT/$k.pub"
done
