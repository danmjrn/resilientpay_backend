#!/bin/sh

set -eu

MVNW_PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MVNW_PROPERTIES="$MVNW_PROJECT_DIR/.mvn/wrapper/maven-wrapper.properties"

if [ ! -f "$MVNW_PROPERTIES" ]; then
  echo "Missing $MVNW_PROPERTIES" >&2
  exit 1
fi

MVNW_DISTRIBUTION_URL=$(sed -n 's/^distributionUrl=//p' "$MVNW_PROPERTIES")
MVNW_EXPECTED_SHA=$(sed -n 's/^distributionSha256Sum=//p' "$MVNW_PROPERTIES")
MVNW_ARCHIVE_NAME=apache-maven-3.9.11-bin.zip
MVNW_INSTALL_ROOT="${MAVEN_USER_HOME:-${HOME}/.m2}/wrapper/dists/apache-maven-3.9.11-bin/resilientpay"
MVNW_MAVEN_HOME="$MVNW_INSTALL_ROOT/apache-maven-3.9.11"
MVNW_ARCHIVE="$MVNW_INSTALL_ROOT/$MVNW_ARCHIVE_NAME"

verify_archive() {
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s  %s\n' "$MVNW_EXPECTED_SHA" "$MVNW_ARCHIVE" | sha256sum -c - >/dev/null
  elif command -v shasum >/dev/null 2>&1; then
    MVNW_ACTUAL_SHA=$(shasum -a 256 "$MVNW_ARCHIVE" | awk '{print $1}')
    [ "$MVNW_ACTUAL_SHA" = "$MVNW_EXPECTED_SHA" ]
  else
    echo "A SHA-256 utility is required to verify Maven." >&2
    return 1
  fi
}

if [ ! -x "$MVNW_MAVEN_HOME/bin/mvn" ]; then
  mkdir -p "$MVNW_INSTALL_ROOT"
  if [ ! -f "$MVNW_ARCHIVE" ] || ! verify_archive; then
    rm -f "$MVNW_ARCHIVE"
    if command -v curl >/dev/null 2>&1; then
      curl --fail --location --silent --show-error "$MVNW_DISTRIBUTION_URL" --output "$MVNW_ARCHIVE"
    elif command -v wget >/dev/null 2>&1; then
      wget --quiet "$MVNW_DISTRIBUTION_URL" --output-document "$MVNW_ARCHIVE"
    else
      echo "curl or wget is required to download Maven." >&2
      exit 1
    fi
    verify_archive || {
      echo "Maven distribution checksum mismatch." >&2
      rm -f "$MVNW_ARCHIVE"
      exit 1
    }
  fi
  unzip -q -o "$MVNW_ARCHIVE" -d "$MVNW_INSTALL_ROOT"
fi

exec "$MVNW_MAVEN_HOME/bin/mvn" -f "$MVNW_PROJECT_DIR/pom.xml" "$@"
