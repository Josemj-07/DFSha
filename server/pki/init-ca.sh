#!/bin/sh
# Crea la CA una sola vez. Uso: PKI_DIR=/etc/dfsha/pki init-ca.sh
set -eu
PKI_DIR="${PKI_DIR:-./pki-out}"
mkdir -p "$PKI_DIR/ca"; chmod 700 "$PKI_DIR/ca"
if [ -f "$PKI_DIR/ca/ca.key" ]; then echo "La CA ya existe en $PKI_DIR/ca"; exit 0; fi
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$PKI_DIR/ca/ca.key"
chmod 600 "$PKI_DIR/ca/ca.key"
openssl req -x509 -new -key "$PKI_DIR/ca/ca.key" -sha256 -days 825 \
  -subj "/O=DFSha/CN=DFSha Root CA" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -addext "subjectKeyIdentifier=hash" \
  -out "$PKI_DIR/ca/ca.crt"
