#!/bin/bash
set -euo pipefail

REPO_URL="$1"
BASE_DIR="${REPO_FOLDER:?REPO_FOLDER is not set}"

REPO_NAME=$(basename "$REPO_URL" .git)
REPO_PATH="$BASE_DIR/$REPO_NAME"
CACHE_DIR="$REPO_PATH/.cfg-java-cache"
FALLBACK_CLASSES_DIR="$REPO_PATH/.cfg-java-classes"
JAVA_CFG_CLI_CMD="${JAVA_CFG_CLI_CMD:-java -jar /app/java-cfg-cli/target/java-cfg-cli-1.0.0-all.jar}"

mkdir -p "$BASE_DIR"
cd "$BASE_DIR"

if [ -d "$REPO_PATH" ]; then
    echo "Repo already exists, removing: $REPO_PATH"
    rm -rf "$REPO_PATH"
fi

git clone --recurse-submodules "$REPO_URL"

if [ -f "$REPO_PATH/mvnw" ]; then
    chmod +x "$REPO_PATH/mvnw"
fi

if [ -f "$REPO_PATH/gradlew" ]; then
    chmod +x "$REPO_PATH/gradlew"
fi

build_with_maven() {
    if [ -f "$REPO_PATH/mvnw" ]; then
        (cd "$REPO_PATH" && ./mvnw -q -DskipTests compile)
    else
        (cd "$REPO_PATH" && mvn -q -DskipTests compile)
    fi
}

build_with_gradle() {
    if [ -f "$REPO_PATH/gradlew" ]; then
        (cd "$REPO_PATH" && ./gradlew --no-daemon classes)
    else
        (cd "$REPO_PATH" && gradle --no-daemon classes)
    fi
}

build_with_javac_fallback() {
    mkdir -p "$FALLBACK_CLASSES_DIR"
    mapfile -t java_files < <(find "$REPO_PATH" -type f -name '*.java' \
        ! -path '*/.git/*' \
        ! -path '*/target/*' \
        ! -path '*/build/*' \
        ! -path '*/.cfg-java-cache/*' \
        ! -path '*/.cfg-java-classes/*')

    if [ "${#java_files[@]}" -eq 0 ]; then
        echo "ERROR: No Java source files found in $REPO_PATH"
        exit 1
    fi

    javac -d "$FALLBACK_CLASSES_DIR" "${java_files[@]}"
}

if [ -f "$REPO_PATH/pom.xml" ]; then
    echo "Detected Maven project"
    build_with_maven
elif [ -f "$REPO_PATH/build.gradle" ] || [ -f "$REPO_PATH/build.gradle.kts" ] || [ -f "$REPO_PATH/settings.gradle" ] || [ -f "$REPO_PATH/settings.gradle.kts" ]; then
    echo "Detected Gradle project"
    build_with_gradle
else
    echo "No Maven/Gradle build detected, falling back to javac"
    build_with_javac_fallback
fi

mkdir -p "$CACHE_DIR"
# Build one cache file per Java source so cfg_build_script.sh can stay per-file like other languages.
eval "$JAVA_CFG_CLI_CMD --repo-root \"$REPO_PATH\" --cache-dir \"$CACHE_DIR\""

