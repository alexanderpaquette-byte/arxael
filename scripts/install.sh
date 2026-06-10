#!/usr/bin/env bash
# arxael-dev-kit — deterministic core install script.
#
# This is the substrate's source of truth for "make this box able to run the daemon".
# The agent-native setup (docs/SETUP.md) WRAPS this; power users / CI run it directly.
# It is idempotent and prints what it did. No agent reasoning is required to run it.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_VERSION="${ARXAEL_GRADLE_VERSION:-8.10.2}"
GRADLE_HOME="${ARXAEL_GRADLE_HOME:-/opt/gradle/gradle-${GRADLE_VERSION}}"
# Use sudo only when needed: not as root, and only if it exists (works on bare/container/root boxes too).
SUDO=""; [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1 && SUDO="sudo"
export DEBIAN_FRONTEND=noninteractive  # NOT inline `$SUDO VAR=val cmd` — that breaks as root (empty $SUDO)

log() { printf '[install] %s\n' "$*"; }

# 1. JDK 21 — required to run the daemon and (via the gradle adapter) build targets.
if ! command -v java >/dev/null 2>&1; then
  log "installing OpenJDK 21 (no java found)"
  $SUDO apt-get update -qq
  $SUDO apt-get install -y -qq openjdk-21-jdk-headless
else
  log "java present: $(java -version 2>&1 | head -1)"
fi

# 2. A pinned Gradle installation — the gradle adapter points the Tooling API at this
#    (useInstallation) so builds are offline + version-deterministic.
if [ ! -x "${GRADLE_HOME}/bin/gradle" ]; then
  log "installing Gradle ${GRADLE_VERSION} -> ${GRADLE_HOME}"
  tmp="$(mktemp -d)"
  curl -sSL -o "${tmp}/gradle.zip" "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  $SUDO mkdir -p "$(dirname "${GRADLE_HOME}")"
  $SUDO python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" \
    "${tmp}/gradle.zip" "$(dirname "${GRADLE_HOME}")"
  $SUDO chmod +x "${GRADLE_HOME}/bin/gradle"
  rm -rf "${tmp}"
else
  log "gradle present: ${GRADLE_HOME}"
fi

# 3. Build the daemon distribution.
log "building daemon (./gradlew :core:installDist)"
( cd "${REPO_ROOT}" && ./gradlew :core:installDist --console=plain -q )

LAUNCHER="${REPO_ROOT}/core/build/install/core/bin/core"
log "done. daemon launcher: ${LAUNCHER}"
log ""
log "Easiest next step (no jargon):  ${REPO_ROOT}/scripts/arxael up"
log "  then: arxael status / arxael logs / arxael stop"
log ""
log "Power users — start the daemon directly:  ARXAEL_GRADLE_HOME=${GRADLE_HOME} ${LAUNCHER}"
log "Smoke test:  ${REPO_ROOT}/scripts/smoke.sh"
