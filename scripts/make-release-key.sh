#!/bin/zsh
# Creates the release signing key once. The passwords are random, go straight into the macOS
# Keychain, and are never printed or written to disk. keystore.properties (not in git) only
# names the keystore file and the Keychain items; app/build.gradle.kts reads them at build time.
set -euo pipefail

ROOT="${0:A:h:h}"
KEYSTORE="${KEYSTORE:-$HOME/.config/megaglass/release.jks}"
ALIAS="megaglass"
STORE_ITEM="megaglass-android-keystore"
KEY_ITEM="megaglass-android-key"

if [[ -f "$KEYSTORE" ]]; then
  print -u2 "Keystore already exists: $KEYSTORE"
  exit 1
fi
mkdir -p "${KEYSTORE:h}"
chmod 700 "${KEYSTORE:h}"

export MG_STORE_PASS="$(openssl rand -base64 32)"
export MG_KEY_PASS="$MG_STORE_PASS"   # PKCS12 keystores use one password for store and key.
security add-generic-password -U -s "$STORE_ITEM" -a "$USER" -w "$MG_STORE_PASS"
security add-generic-password -U -s "$KEY_ITEM" -a "$USER" -w "$MG_KEY_PASS"

keytool -genkeypair -v -storetype PKCS12 -keystore "$KEYSTORE" -alias "$ALIAS" \
  -keyalg EC -groupname secp256r1 -validity 10000 -dname "CN=MegaGlass" \
  -storepass:env MG_STORE_PASS -keypass:env MG_KEY_PASS > /dev/null
chmod 600 "$KEYSTORE"
unset MG_STORE_PASS MG_KEY_PASS

cat > "$ROOT/keystore.properties" <<PROPS
storeFile=$KEYSTORE
keyAlias=$ALIAS
storePasswordKeychain=$STORE_ITEM
keyPasswordKeychain=$KEY_ITEM
PROPS
print "Release key created: $KEYSTORE (passwords in Keychain: $STORE_ITEM, $KEY_ITEM)"
