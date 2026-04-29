#!/bin/bash
set -e

REPO_URL="$1"
BASE_DIR="$REPO_FOLDER"

REPO_NAME=$(basename "$REPO_URL" .git)
REPO_PATH="$BASE_DIR/$REPO_NAME"

mkdir -p "$BASE_DIR"
cd "$BASE_DIR"

if [ -d "$REPO_PATH" ]; then
    echo "Repo already exists, removing: $REPO_PATH"
    rm -rf "$REPO_PATH"
fi

git clone "$REPO_URL"
