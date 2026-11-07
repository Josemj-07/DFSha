#!/bin/sh
# Solo para docker compose local. Corre dentro del servicio "pki" como root.
set -eu
HERE="$(dirname "$0")"
export PKI_DIR=/dev-secrets
sh "$HERE/init-ca.sh"
issue() { # nombre uid:gid SAN
  d="$PKI_DIR/$1"
  sh "$HERE/gen-csr.sh" "$1" "$d"
  sh "$HERE/sign-csr.sh" "$d/$1.csr" "$d/tls.crt" "$3"
  cp "$PKI_DIR/ca/ca.crt" "$d/ca.crt"
  chown -R "$2" "$d"
}
issue server   10001:10001 "DNS:server,DNS:localhost,IP:127.0.0.1"
issue rabbitmq 100:101     "DNS:rabbitmq,DNS:localhost,IP:127.0.0.1"
for n in 1 2 3; do issue "dn-$n" 10001:10001 "DNS:dn-$n,DNS:localhost,IP:127.0.0.1"; done
sh "$HERE/gen-token-keys.sh" "$PKI_DIR/server/keys"
chown -R 10001:10001 "$PKI_DIR/server/keys"
for n in 1 2 3; do cp "$PKI_DIR/server/keys/blocktoken.pub" "$PKI_DIR/dn-$n/"; chown 10001:10001 "$PKI_DIR/dn-$n/blocktoken.pub"; done
mkdir -p "$PKI_DIR/client"; cp "$PKI_DIR/ca/ca.crt" "$PKI_DIR/client/ca.crt"; chmod 644 "$PKI_DIR/client/ca.crt"
sed -e "s/__MDS_PASS__/$RABBIT_MDS_PASS/" -e "s/__DN1_PASS__/$RABBIT_DN1_PASS/" -e "s/__DN2_PASS__/$RABBIT_DN2_PASS/" -e "s/__DN3_PASS__/$RABBIT_DN3_PASS/" \
  "$HERE/../rabbitmq/definitions.template.json" > "$PKI_DIR/rabbitmq/definitions.json"
chown 100:101 "$PKI_DIR/rabbitmq/definitions.json"; chmod 600 "$PKI_DIR/rabbitmq/definitions.json"
