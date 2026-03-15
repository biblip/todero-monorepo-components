#!/bin/bash

set -euo pipefail

VERSION_FILE="$HOME/.todero/dev-version.txt"

if [ ! -f "$VERSION_FILE" ]; then
  echo "Error: version file not found at $VERSION_FILE"
  exit 1
fi

VERSION="$(tr -d '[:space:]' < "$VERSION_FILE")"

if [[ ! "$VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "Error: invalid version format in $VERSION_FILE"
  echo "Expected format: x.y.z"
  exit 1
fi

MAJOR="${BASH_REMATCH[1]}"
MINOR="${BASH_REMATCH[2]}"
PATCH="${BASH_REMATCH[3]}"

if [ "$PATCH" -le 0 ]; then
  echo "Error: patch version must be greater than 0 to use x.y.z-1"
  exit 1
fi

BUILD_PATCH=$((PATCH - 1))
BUILD_VERSION="${MAJOR}.${MINOR}.${BUILD_PATCH}"

echo "Source version from file : $VERSION"
echo "Build version to use     : $BUILD_VERSION"

mvn clean install -Dtodero.version="$BUILD_VERSION"

echo "Build finished successfully."