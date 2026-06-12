#!/usr/bin/env bash
# bootstrap.sh — fresh box to a verified-running helper in ONE command (target: well under 10 minutes).
#
#   bash scripts/bootstrap.sh
#
# It installs everything missing (base tools, JDK, Gradle), builds the helper, starts it, and VERIFIES it's
# answering — then prints how long it took and what to do next. Idempotent: safe to re-run. No prior context
# or configuration needed.
set -euo pipefail
START=$(date +%s)
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
step() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }

SUDO=""; [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1 && SUDO="sudo"
# Export rather than inline-prefix: `$SUDO VAR=val cmd` breaks when $SUDO is empty (root) because bash
# fixes assignment-recognition at PARSE time by position, so the expanded-away $SUDO leaves VAR=val as the
# command word ("command not found"). Exporting works whether or not sudo is present (sudo inherits it via -E
# is not needed for apt's DEBIAN_FRONTEND since we run apt as the same env). Found by container-based install testing.
export DEBIAN_FRONTEND=noninteractive

# ---------------------------------------------------------------- 0. base tools (git/curl/unzip/python3)
missing=""
for c in git curl unzip python3; do command -v "$c" >/dev/null 2>&1 || missing="$missing $c"; done
if [ -n "$missing" ]; then
  step "installing base tools:$missing"
  if   command -v apt-get >/dev/null 2>&1; then $SUDO apt-get update -qq && $SUDO apt-get install -y -qq $missing ca-certificates
  elif command -v dnf     >/dev/null 2>&1; then $SUDO dnf install -y -q $missing
  elif command -v yum     >/dev/null 2>&1; then $SUDO yum install -y -q $missing
  elif command -v apk     >/dev/null 2>&1; then $SUDO apk add --no-cache $missing
  elif command -v brew    >/dev/null 2>&1; then brew install $missing
  else echo "No supported package manager found. Please install:$missing  then re-run."; exit 1; fi
else
  step "base tools present (git, curl, unzip, python3)"
fi

# ---------------------------------------------------------------- 1. JDK + Gradle + build the helper
step "installing JDK + Gradle and building the helper (first run downloads ~150MB; later runs are instant)"
bash "$REPO_ROOT/scripts/install.sh"

# ---------------------------------------------------------------- 2. start (daemon only; connect a project later)
step "starting the helper"
# run from $HOME so it just starts the daemon (it auto-connects only the project you're standing in)
( cd "$HOME" && bash "$REPO_ROOT/scripts/arxael" up )

# ---------------------------------------------------------------- 3. verify it's actually answering
step "verifying"
if ( cd "$HOME" && bash "$REPO_ROOT/scripts/arxael" status ); then
  ELAPSED=$(( $(date +%s) - START ))
  printf '\n\033[32m✓ READY\033[0m in %dm%02ds.\n' $((ELAPSED/60)) $((ELAPSED%60))
  echo
  echo "Next: go into YOUR project (a git repo with a 'main' branch) and run:"
  echo "    $REPO_ROOT/scripts/arxael up        # connects that project"
  echo "    $REPO_ROOT/scripts/arxael status    # check on it any time"
  echo
  echo "AI agents: read $REPO_ROOT/AGENTS.md, or curl the live API:  curl -s 127.0.0.1:\$(cat ~/.arxael/port)/"
else
  echo "Started but it isn't answering. See what happened:  $REPO_ROOT/scripts/arxael logs"; exit 1
fi
