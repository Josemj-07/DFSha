#!/bin/sh
# Firma un CSR. La CA decide los SAN, no el solicitante.
# Uso: sign-csr.sh <archivo.csr> <salida.crt> "DNS:dn-1,IP:10.0.1.20"
set -eu
CSR="$1"; CRT="$2"; SAN="$3"
PKI_DIR="${PKI_DIR:-./pki-out}"
EXT="$(mktemp)"; trap 'rm -f "$EXT"' EXIT
cat > "$EXT" <<EXTEOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature
extendedKeyUsage=serverAuth
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
subjectAltName=$SAN
EXTEOF
openssl x509 -req -in "$CSR" -CA "$PKI_DIR/ca/ca.crt" -CAkey "$PKI_DIR/ca/ca.key" \
  -CAcreateserial -days 397 -sha256 -extfile "$EXT" -out "$CRT"
openssl verify -CAfile "$PKI_DIR/ca/ca.crt" "$CRT"
