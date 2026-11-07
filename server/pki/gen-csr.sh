#!/bin/sh
# Genera clave privada y CSR. Uso: gen-csr.sh <nombre> <directorio-salida>
set -eu
NAME="$1"; OUT="$2"
mkdir -p "$OUT"
[ -f "$OUT/tls.key" ] || openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$OUT/tls.key"
chmod 600 "$OUT/tls.key"
openssl req -new -key "$OUT/tls.key" -subj "/O=DFSha/CN=$NAME" -out "$OUT/$NAME.csr"
