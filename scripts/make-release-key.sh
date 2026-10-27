#!/bin/zsh
# Creates the release signing key once. The passwords are random, go straight into the macOS
# Keychain, and are never printed or written to disk. keystore.properties (not in git) only
# names the keystore file and the Keychain items; app/build.gradle.kts reads them at build time.
set -euo pipefail

ROOT="${0:A:h:h}"
# Absolute: app/build.gradle.kts would resolve a relative path against app/.
KEYSTORE="${${KEYSTORE:-$HOME/.config/megaglass/release.jks}:A}"
ALIAS="megaglass"
STORE_ITEM="megaglass-android-keystore"
KEY_ITEM="megaglass-android-key"

# A second key would replace the stored password of the first key and sign later releases with
# a different certificate, which Android refuses as an update. Stop if any part already exists.
if [[ -e "$KEYSTORE" ]]; then
  print -u2 "Keystore already exists: $KEYSTORE"
  exit 1
fi
if [[ -e "$ROOT/keystore.properties" ]]; then
  print -u2 "keystore.properties already exists: a release key was made before."
  exit 1
fi
for item in "$STORE_ITEM" "$KEY_ITEM"; do
  if security find-generic-password -s "$item" > /dev/null 2>&1; then
    print -u2 "Keychain item already exists: $item. It holds the password of an existing release key."
    exit 1
  fi
done
# Only a folder that this script makes gets mode 700; an existing folder keeps its mode.
if [[ ! -d "${KEYSTORE:h}" ]]; then
  mkdir -p "${KEYSTORE:h}"
  chmod 700 "${KEYSTORE:h}"
fi

# A run that stops before keystore.properties is written removes what it made: the checks above
# found none of it, and no release can be signed with a key that has no keystore.properties.
created=()
cleanup() {
  for item in $created; do
    security delete-generic-password -s "$item" -a "$USER" > /dev/null 2>&1 || true
  done
  rm -f "$KEYSTORE" "$ROOT/keystore.properties"
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP

export MG_STORE_PASS="$(openssl rand -base64 32)"
export MG_KEY_PASS="$MG_STORE_PASS"   # PKCS12 keystores use one password for store and key.
# The passwords go to `security` on stdin, not in its arguments, so `ps` cannot show them.
print -r -- "add-generic-password -s \"$STORE_ITEM\" -a \"$USER\" -w \"$MG_STORE_PASS\"" | security -i
created+=("$STORE_ITEM")
print -r -- "add-generic-password -s \"$KEY_ITEM\" -a \"$USER\" -w \"$MG_KEY_PASS\"" | security -i
created+=("$KEY_ITEM")

# `security -i` can exit with 0 when the command in it fails. Stop before keytool makes a key with a lost password.
for item in "$STORE_ITEM" "$KEY_ITEM"; do
  if ! security find-generic-password -s "$item" > /dev/null 2>&1; then
    print -u2 "Keychain item was not added: $item"
    exit 1
  fi
done

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
trap - EXIT
print "Release key created: $KEYSTORE (passwords in Keychain: $STORE_ITEM, $KEY_ITEM)"
print "Back up the keystore and its Keychain items together. Without both, no update can be signed."
