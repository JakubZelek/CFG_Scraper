#!/bin/bash
set -euo pipefail

REPO_URL="$1"
BASE_DIR="${REPO_FOLDER:?REPO_FOLDER is not set}"

REPO_NAME=$(basename "$REPO_URL" .git)
REPO_PATH="$BASE_DIR/$REPO_NAME"
CACHE_DIR="$REPO_PATH/.cfg-java-cache"
FALLBACK_CLASSES_DIR="$REPO_PATH/.cfg-java-classes"
JAVA_CFG_CLI_CMD="${JAVA_CFG_CLI_CMD:-java -jar /app/java-cfg-cli/target/java-cfg-cli-1.0.0-all.jar}"
JAVA_CFG_REMOTE_DEBUG="${JAVA_CFG_REMOTE_DEBUG:-false}"
JAVA_CFG_REMOTE_DEBUG_PORT="${JAVA_CFG_REMOTE_DEBUG_PORT:-5005}"
JAVA_CFG_REMOTE_DEBUG_SUSPEND="${JAVA_CFG_REMOTE_DEBUG_SUSPEND:-n}"
JAVA_ALLOW_WRAPPER_DOWNLOADS="${JAVA_ALLOW_WRAPPER_DOWNLOADS:-false}"
JAVA_BUILD_OFFLINE="${JAVA_BUILD_OFFLINE:-true}"

run_java_cfg_cli() {
    local args="$1"
    local debug_opts=""

    if [ "$JAVA_CFG_REMOTE_DEBUG" = "true" ]; then
        debug_opts="-agentlib:jdwp=transport=dt_socket,server=y,suspend=$JAVA_CFG_REMOTE_DEBUG_SUSPEND,address=*:$JAVA_CFG_REMOTE_DEBUG_PORT"
        echo "Java remote debugging enabled on port $JAVA_CFG_REMOTE_DEBUG_PORT (suspend=$JAVA_CFG_REMOTE_DEBUG_SUSPEND)"
    fi

    JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} $debug_opts" eval "$JAVA_CFG_CLI_CMD $args"
}

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
    local maven_offline=""
    if [ "$JAVA_BUILD_OFFLINE" = "true" ]; then
        maven_offline="-o"
    fi

    set +e
    if [ -f "$REPO_PATH/mvnw" ] && [ "$JAVA_ALLOW_WRAPPER_DOWNLOADS" = "true" ]; then
        (cd "$REPO_PATH" && ./mvnw -q $maven_offline -DskipTests compile)
    elif command -v mvn >/dev/null 2>&1; then
        (cd "$REPO_PATH" && mvn -q $maven_offline -DskipTests compile)
    else
        echo "No usable Maven binary for this configuration, falling back to javac"
        set -e
        build_with_javac_fallback
        return
    fi
    maven_code=$?
    set -e

    if [ "$maven_code" -ne 0 ]; then
        echo "Maven build failed (exit=$maven_code), falling back to javac"
        build_with_javac_fallback
    fi
}

build_with_gradle() {
    local gradle_code=0
    local gradle_offline=""
    if [ "$JAVA_BUILD_OFFLINE" = "true" ]; then
        gradle_offline="--offline"
    fi

    if command -v gradle >/dev/null 2>&1; then
        set +e
        (cd "$REPO_PATH" && gradle --no-daemon $gradle_offline classes)
        gradle_code=$?
        set -e
    elif [ -f "$REPO_PATH/gradlew" ] && [ "$JAVA_ALLOW_WRAPPER_DOWNLOADS" = "true" ]; then
        set +e
        (cd "$REPO_PATH" && ./gradlew --no-daemon $gradle_offline classes)
        gradle_code=$?
        set -e
    else
        echo "No usable Gradle binary for this configuration, falling back to javac"
        build_with_javac_fallback
        return
    fi

    if [ "$gradle_code" -ne 0 ]; then
        echo "Gradle build failed (exit=$gradle_code), falling back to javac"
        build_with_javac_fallback
    fi
}

build_with_ant() {
    if command -v ant >/dev/null 2>&1; then
        (cd "$REPO_PATH" && ant -noinput -q)
    else
        echo "Ant build file detected but ant binary not found"
        return 1
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

    mapfile -t project_jars < <(find "$REPO_PATH" -type f -name '*.jar' \
        ! -path '*/.git/*' \
        ! -path '*/target/*' \
        ! -path '*/build/*' \
        ! -path '*/.cfg-java-cache/*' \
        ! -path '*/.cfg-java-classes/*')

    cp_separator=':'
    classpath=""
    if [ "${#project_jars[@]}" -gt 0 ]; then
        classpath="$(IFS="$cp_separator"; echo "${project_jars[*]}")"
    fi

    # Best-effort compilation: some repos (e.g. NetBeans projects) reference local IDE libs.
    set +e
    if [ -n "$classpath" ]; then
        javac -cp "$classpath" -d "$FALLBACK_CLASSES_DIR" "${java_files[@]}" 2>"$REPO_PATH/.cfg-java-javac.err"
    else
        javac -d "$FALLBACK_CLASSES_DIR" "${java_files[@]}" 2>"$REPO_PATH/.cfg-java-javac.err"
    fi
    javac_code=$?
    set -e

    if [ "$javac_code" -ne 0 ]; then
        echo "javac fallback failed (exit=$javac_code), continuing with any classes that compiled"
        if [ -f "$REPO_PATH/.cfg-java-javac.err" ]; then
            echo "First javac errors:"
            head -n 20 "$REPO_PATH/.cfg-java-javac.err"
        fi
    fi
}

if [ -f "$REPO_PATH/pom.xml" ]; then
    echo "Detected Maven project"
    build_with_maven
elif [ -f "$REPO_PATH/build.gradle" ] || [ -f "$REPO_PATH/build.gradle.kts" ] || [ -f "$REPO_PATH/settings.gradle" ] || [ -f "$REPO_PATH/settings.gradle.kts" ]; then
    echo "Detected Gradle project"
    build_with_gradle
elif [ -f "$REPO_PATH/build.xml" ]; then
    echo "Detected Ant project"
    if ! build_with_ant; then
        echo "Ant build failed, falling back to javac"
        build_with_javac_fallback
    fi
else
    echo "No Maven/Gradle build detected, falling back to javac"
    build_with_javac_fallback
fi

mkdir -p "$CACHE_DIR"
run_java_cfg_cli "--repo-root \"$REPO_PATH\" --cache-dir \"$CACHE_DIR\""

