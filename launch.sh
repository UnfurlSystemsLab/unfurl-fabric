#!/usr/bin/env bash
# Launcher (Facade): runs Unfurl Fabric on its own, either the Fabric Studio server or the Fabric CLI.
#
# One entry point over the shaded jar target/unfurl-fabric-studio-server.jar, so Fabric can run without the UI, Flow
# or Foundry. It builds the jar when it is missing (or with --build), checks that Java 21 or later is on PATH, and, for
# the server, that the port is free. It then execs Java in the foreground, so Ctrl-C stops it and the script exits
# with Java's exit code.
#
# Usage:
#   ./launch.sh [--build] [server options]      Start the Fabric Studio server
#   ./launch.sh [--build] cli <command> [args]  Run a Fabric CLI command (compile, sign, plan, ...)
#   ./launch.sh --help
#
# Server options are passed to the server unchanged: --bind <address>, --port <port>, --state-path <file>,
# --asset-root <dir>, --event-bus <in-memory|redis|kafka>, --redis-url <url>, --kafka-bootstrap-servers <list>,
# --kafka-topic <topic>.
#
# Environment (the same names the Dockerfile uses):
#   FABRIC_STUDIO_JAR            Jar to run instead of target/unfurl-fabric-studio-server.jar
#   JAVA_OPTS                    Extra JVM options, split on spaces
#   UNFURL_STUDIO_BIND           Default for --bind (server default 127.0.0.1)
#   UNFURL_STUDIO_PORT           Default for --port (server default 7878)
#   UNFURL_STUDIO_STATE_PATH     Studio state file, read by the server itself
#   UNFURL_STUDIO_ASSET_ROOT     Asset root, read by the server itself
#   UNFURL_FOUNDRY_DCP_ENDPOINT  Foundry endpoint for authoring, read by the server itself
#
# Kept behaviourally identical to launch.ps1.
set -euo pipefail

FABRIC_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_JAR="$FABRIC_HOME/target/unfurl-fabric-studio-server.jar"
CLI_MAIN_CLASS="com.unfurl.fabric.cli.FabricCli"
MINIMUM_JAVA=21

# Error reporter: prints a launcher error and exits non-zero.
fail() {
    echo "launch: $*" >&2
    exit 1
}

# Prints the usage summary, for --help and for usage errors.
usage() {
    cat <<'EOF'
Usage:
  launch.sh [--build] [server options]      Start the Fabric Studio server
  launch.sh [--build] cli <command> [args]  Run a Fabric CLI command
  launch.sh --help

Server options: --bind, --port, --state-path, --asset-root, --event-bus, --redis-url,
--kafka-bootstrap-servers, --kafka-topic. Defaults come from UNFURL_STUDIO_BIND and
UNFURL_STUDIO_PORT, then the server's own defaults (127.0.0.1:7878).
EOF
}

# Builds the shaded jar with Maven (tests skipped) when it is missing or a rebuild was asked for, and prints the jar
# path to run. A FABRIC_STUDIO_JAR override is never built: it names a jar built elsewhere, so a missing one is an error.
resolve_jar() {
    local rebuild="$1"
    if [[ -n "${FABRIC_STUDIO_JAR:-}" ]]; then
        [[ -f "$FABRIC_STUDIO_JAR" ]] || fail "FABRIC_STUDIO_JAR points at '$FABRIC_STUDIO_JAR', which does not exist"
        echo "$FABRIC_STUDIO_JAR"
        return
    fi
    if [[ "$rebuild" == "true" || ! -f "$DEFAULT_JAR" ]]; then
        command -v mvn >/dev/null 2>&1 \
            || fail "the Fabric jar is not built and Maven (mvn) is not on PATH; build it with 'mvn package -DskipTests' in $FABRIC_HOME"
        echo "Building Fabric ($DEFAULT_JAR)..." >&2
        mvn -q -f "$FABRIC_HOME/pom.xml" package -DskipTests >&2 || fail "Maven build failed"
    fi
    echo "$DEFAULT_JAR"
}

# Guard: Java must be on PATH and at least the version Fabric is built for ("1.8" style versions map to 8).
assert_java() {
    command -v java >/dev/null 2>&1 || fail "java is not on PATH; install Java $MINIMUM_JAVA or later"
    local text major minor
    text="$(java -version 2>&1)"
    if [[ "$text" =~ version\ \"([0-9]+)(\.([0-9]+))? ]]; then
        major="${BASH_REMATCH[1]}"
        minor="${BASH_REMATCH[3]:-}"
    else
        fail "could not read the Java version from: $text"
    fi
    if [[ "$major" == "1" && -n "$minor" ]]; then
        major="$minor"
    fi
    (( major >= MINIMUM_JAVA )) || fail "Fabric needs Java $MINIMUM_JAVA or later, but java on PATH is version $major"
}

# Prints the value following a flag in the remaining arguments, or nothing when the flag is absent.
option_value() {
    local flag="$1"
    shift
    while [[ $# -gt 1 ]]; do
        if [[ "$1" == "$flag" ]]; then
            echo "$2"
            return
        fi
        shift
    done
}

# Guard: fails when something already accepts connections on the server's port, instead of letting the server die
# with a bind error. A wildcard bind address is checked through the loopback interface.
assert_port_free() {
    local address="$1" port="$2" target="$1"
    if [[ "$target" == "0.0.0.0" || "$target" == "::" ]]; then
        target="127.0.0.1"
    fi
    if (exec 3<>"/dev/tcp/$target/$port") 2>/dev/null; then
        fail "port $port on $address is already in use; stop what is using it or pass --port <other port>"
    fi
}

# Argument handling: --build and --help are the launcher's own; 'cli' switches mode; everything else is forwarded.
rebuild=false
while [[ $# -gt 0 && ( "$1" == "--build" || "$1" == "--help" || "$1" == "-h" ) ]]; do
    if [[ "$1" == "--build" ]]; then
        rebuild=true
    else
        usage
        exit 0
    fi
    shift
done
cli_mode=false
if [[ $# -gt 0 && "$1" == "cli" ]]; then
    cli_mode=true
    shift
fi

assert_java
jar="$(resolve_jar "$rebuild")"
java_args=()
if [[ -n "${JAVA_OPTS:-}" ]]; then
    read -r -a java_args <<<"$JAVA_OPTS"
fi

if [[ "$cli_mode" == "true" ]]; then
    if [[ $# -eq 0 ]]; then
        usage >&2
        fail "cli needs a Fabric command, for example: cli plan --catalog <dir> --needs <file>"
    fi
    exec java "${java_args[@]+"${java_args[@]}"}" -cp "$jar" "$CLI_MAIN_CLASS" "$@"
fi

server_args=("$@")
if [[ -n "${UNFURL_STUDIO_BIND:-}" && " ${server_args[*]+"${server_args[*]}"} " != *" --bind "* ]]; then
    server_args+=(--bind "$UNFURL_STUDIO_BIND")
fi
if [[ -n "${UNFURL_STUDIO_PORT:-}" && " ${server_args[*]+"${server_args[*]}"} " != *" --port "* ]]; then
    server_args+=(--port "$UNFURL_STUDIO_PORT")
fi
bind="$(option_value --bind "${server_args[@]+"${server_args[@]}"}")"
bind="${bind:-127.0.0.1}"
port="$(option_value --port "${server_args[@]+"${server_args[@]}"}")"
port="${port:-7878}"
[[ "$port" =~ ^[0-9]+$ ]] || fail "--port must be a number, got '$port'"
assert_port_free "$bind" "$port"
echo "Starting Fabric Studio on http://$bind:$port (health: http://$bind:$port/health). Press Ctrl-C to stop."
exec java "${java_args[@]+"${java_args[@]}"}" -jar "$jar" "${server_args[@]+"${server_args[@]}"}"
