#!/bin/sh
# ----------------------------------------------------------------------------
# Maven Wrapper Shell Script
# ----------------------------------------------------------------------------

set -e

DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_PROPERTIES="$DIR/.mvn/wrapper/maven-wrapper.properties"

if [ ! -f "$WRAPPER_PROPERTIES" ]; then
    echo "Error: Cannot find $WRAPPER_PROPERTIES" >&2
    exit 1
fi

DISTRIBUTION_URL=$(grep 'distributionUrl=' "$WRAPPER_PROPERTIES" | cut -d= -f2-)
DIST_FILE=$(basename "$DISTRIBUTION_URL")
MAVEN_USER_HOME="${HOME}/.m2"
MAVEN_WRAPPER_DIR="${MAVEN_USER_HOME}/wrapper/dists/${DIST_FILE}"

# Check if Maven is already present
if [ -d "$MAVEN_WRAPPER_DIR" ]; then
    for d in "$MAVEN_WRAPPER_DIR"/*; do
        if [ -x "$d/bin/mvn" ]; then
            exec "$d/bin/mvn" "$@"
        fi
    done
fi

echo "Downloading Maven from $DISTRIBUTION_URL..."
mkdir -p "$MAVEN_WRAPPER_DIR/download"
curl -sSL "$DISTRIBUTION_URL" -o "$MAVEN_WRAPPER_DIR/download/$DIST_FILE"
unzip -q "$MAVEN_WRAPPER_DIR/download/$DIST_FILE" -d "$MAVEN_WRAPPER_DIR"
rm -rf "$MAVEN_WRAPPER_DIR/download"

for d in "$MAVEN_WRAPPER_DIR"/*; do
    if [ -x "$d/bin/mvn" ]; then
        exec "$d/bin/mvn" "$@"
    fi
done

echo "Error: Failed to set up Maven." >&2
exit 1

