#!/bin/bash
set -euo pipefail

FILEPATH="$1"
BASE_DIR="${REPO_FOLDER:?REPO_FOLDER is not set}"
JAVA_CFG_CLI_CMD="${JAVA_CFG_CLI_CMD:-java -jar /app/java-cfg-cli/target/java-cfg-cli-1.0.0-all.jar}"
REPO_DIR=$(echo "$FILEPATH" | sed "s|^\($BASE_DIR/[^/]*\)/.*|\1|")
REL_PATH="${FILEPATH#"$REPO_DIR/"}"
CACHE_FILE="$REPO_DIR/.cfg-java-cache/$REL_PATH.json"

if [ -f "$CACHE_FILE" ]; then
    cat "$CACHE_FILE"
    exit 0
fi

eval "$JAVA_CFG_CLI_CMD --repo-root \"$REPO_DIR\" --filepath \"$FILEPATH\""

