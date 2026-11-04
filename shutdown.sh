#!/usr/bin/env bash
# Stops a Fabric Studio server started with launch.sh, launch.ps1 or launch.bat.
#
# Finds the process listening on the Studio port, or every Studio server with --all, and stops it only when its
# command line runs the Studio server jar, so an unrelated process that happens to use the port is never touched.
# Nothing running is not an error. The launch script that started the server exits by itself once Java exits.
#
# On Linux and macOS the server gets SIGTERM, so its shutdown hook closes the HTTP server, executor and event bus, and
# SIGKILL only if it is still running after the timeout. Under Git Bash, MSYS or Cygwin the Java process is a Windows
# process, so this script delegates to shutdown.ps1.
#
# Usage:
#   ./shutdown.sh                      Stop the Studio server on UNFURL_STUDIO_PORT, else port 7878
#   ./shutdown.sh --port <port>        Stop the Studio server on that port
#   ./shutdown.sh --all                Stop every Fabric Studio server on this machine
#   ./shutdown.sh [...] --timeout <s>  Seconds to wait before SIGKILL (default 10)
#   ./shutdown.sh --help
#
# FABRIC_STUDIO_JAR, when set, names the jar to look for instead of unfurl-fabric-studio-server.jar.
set -euo pipefail

FABRIC_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Windows shells: the server is a Windows process that MSYS signals cannot reach, so use the PowerShell script.
case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*)
        exec powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$FABRIC_HOME/shutdown.ps1")" "$@"
        ;;
esac

JAR_NAME="unfurl-fabric-studio-server.jar"
if [[ -n "${FABRIC_STUDIO_JAR:-}" ]]; then
    JAR_NAME="$(basename "$FABRIC_STUDIO_JAR")"
fi

# Error reporter: prints a shutdown error and exits non-zero.
fail() {
    echo "shutdown: $*" >&2
    exit 1
}

# Prints the usage summary, for --help and for usage errors.
usage() {
    cat <<'EOF'
Usage:
  shutdown.sh                      Stop the Studio server on UNFURL_STUDIO_PORT, else port 7878
  shutdown.sh --port <port>        Stop the Studio server on that port
  shutdown.sh --all                Stop every Fabric Studio server on this machine
  shutdown.sh [...] --timeout <s>  Seconds to wait before SIGKILL (default 10)
  shutdown.sh --help
EOF
}

# Specification: succeeds when a process's command line runs the Studio server, that is `java ... -jar <path>/<jar>`.
# The Fabric CLI runs the same jar with -cp and the FabricCli class, so CLI commands in progress are never stopped.
is_studio_pid() {
    local args
    args="$(ps -o args= -p "$1" 2>/dev/null || true)"
    [[ -n "$args" && "$args" =~ (^|[[:space:]])-jar[[:space:]]+[^[:space:]]*"$JAR_NAME"([[:space:]]|$) ]]
}

# Prints the pid listening on a local TCP port, or nothing when nothing is. Uses lsof, else ss. lsof and grep exit 1
# when they find nothing, which is an answer here, not a failure, so pipefail must not end the script.
port_listener() {
    local port="$1"
    if command -v lsof >/dev/null 2>&1; then
        { lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null || true; } | head -n 1
    elif command -v ss >/dev/null 2>&1; then
        { ss -ltnpH "sport = :$port" 2>/dev/null || true; } | { grep -o 'pid=[0-9]*' || true; } | head -n 1 | cut -d= -f2
    else
        fail "finding the process on a port needs lsof or ss"
    fi
}

# Prints the pids of every running Studio server.
studio_pids() {
    local pid
    for pid in $(pgrep -f -- "$JAR_NAME" 2>/dev/null || true); do
        if is_studio_pid "$pid"; then
            echo "$pid"
        fi
    done
}

# Stops one Studio server: SIGTERM, then SIGKILL if it is still running after the timeout.
stop_pid() {
    local pid="$1" timeout="$2" waited=0
    kill -TERM "$pid" 2>/dev/null || true
    while kill -0 "$pid" 2>/dev/null; do
        if (( waited >= timeout * 5 )); then
            echo "Fabric Studio (pid $pid) did not exit after ${timeout}s; sending SIGKILL." >&2
            kill -KILL "$pid" 2>/dev/null || true
            sleep 1
            kill -0 "$pid" 2>/dev/null && fail "Fabric Studio (pid $pid) is still running"
            break
        fi
        sleep 0.2
        waited=$((waited + 1))
    done
    echo "Stopped Fabric Studio (pid $pid)."
}

# Argument handling: every option is the script's own.
all=false
port=""
timeout=10
while [[ $# -gt 0 ]]; do
    case "$1" in
        --all) all=true ;;
        --port) [[ $# -ge 2 ]] || fail "--port needs a value"; port="$2"; shift ;;
        --timeout) [[ $# -ge 2 ]] || fail "--timeout needs a value"; timeout="$2"; shift ;;
        --help | -h) usage; exit 0 ;;
        *) usage >&2; fail "unknown option: $1" ;;
    esac
    shift
done
[[ "$all" == "true" && -n "$port" ]] && fail "use either --all or --port, not both"
[[ "$timeout" =~ ^[0-9]+$ ]] || fail "--timeout must be a number of seconds, got '$timeout'"

if [[ "$all" == "true" ]]; then
    pids="$(studio_pids)"
    if [[ -z "$pids" ]]; then
        echo "No Fabric Studio server is running."
        exit 0
    fi
    for pid in $pids; do
        stop_pid "$pid" "$timeout"
    done
    exit 0
fi

port="${port:-${UNFURL_STUDIO_PORT:-7878}}"
[[ "$port" =~ ^[0-9]+$ ]] || fail "--port must be a number, got '$port'"
pid="$(port_listener "$port")"
if [[ -z "$pid" ]]; then
    echo "No Fabric Studio server is running on port $port."
    exit 0
fi
is_studio_pid "$pid" \
    || fail "port $port is used by pid $pid ($(ps -o comm= -p "$pid" 2>/dev/null || echo unknown)), which is not Fabric Studio; nothing was stopped"
stop_pid "$pid" "$timeout"
