#!/bin/bash
set -euo pipefail

FILEPATH="$1"
BASE_DIR="${REPO_FOLDER:?REPO_FOLDER is not set}"
JAVA_CFG_CLI_CMD="${JAVA_CFG_CLI_CMD:-java -jar /app/java-cfg-cli/target/java-cfg-cli-1.0.0-all.jar}"
JAVA_CFG_REMOTE_DEBUG="${JAVA_CFG_REMOTE_DEBUG:-false}"
JAVA_CFG_REMOTE_DEBUG_PORT="${JAVA_CFG_REMOTE_DEBUG_PORT:-5005}"
JAVA_CFG_REMOTE_DEBUG_SUSPEND="${JAVA_CFG_REMOTE_DEBUG_SUSPEND:-n}"
REPO_DIR=$(echo "$FILEPATH" | sed "s|^\($BASE_DIR/[^/]*\)/.*|\1|")
REL_PATH="${FILEPATH#"$REPO_DIR/"}"
CACHE_FILE="$REPO_DIR/.cfg-java-cache/$REL_PATH.json"

if [ -f "$CACHE_FILE" ]; then
    cat "$CACHE_FILE"
    exit 0
fi
echo "testing invocation: $JAVA_CFG_CLI_CMD --repo-root \"$REPO_DIR\" --filepath \"$FILEPATH\""
debug_opts=""
if [ "$JAVA_CFG_REMOTE_DEBUG" = "true" ]; then
    debug_opts="-agentlib:jdwp=transport=dt_socket,server=y,suspend=$JAVA_CFG_REMOTE_DEBUG_SUSPEND,address=*:$JAVA_CFG_REMOTE_DEBUG_PORT"
fi

JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} $debug_opts" eval "$JAVA_CFG_CLI_CMD --repo-root \"$REPO_DIR\" --filepath \"$FILEPATH\""

