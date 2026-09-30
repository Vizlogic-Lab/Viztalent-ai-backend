#!/usr/bin/env bash
# Installs the code-runner languages (java, python, node for javascript,
# gcc for c++) into the Piston container from docker-compose.yml.
#
# Piston sits on an internal-only network, so it can't download packages by
# itself. This attaches it to a temporary network for the download and
# detaches it again when done (also on failure).
#
# Usage: scripts/install-runtimes.sh
#   PISTON_CONTAINER   container name        (default backend-classic-piston)
#   RUNNER_NETWORK     compose runner network (default backend-classic_runner)
#   PACKAGES           "name=version ..."    (default java, python, node, gcc; * = latest)
set -euo pipefail

CONTAINER="${PISTON_CONTAINER:-backend-classic-piston}"
RUNNER_NETWORK="${RUNNER_NETWORK:-backend-classic_runner}"
PACKAGES="${PACKAGES:-java=* python=* node=* gcc=*}"
TEMP_NETWORK="piston-install-$$"

docker network create "$TEMP_NETWORK" >/dev/null
cleanup() {
  docker network disconnect "$TEMP_NETWORK" "$CONTAINER" >/dev/null 2>&1 || true
  docker network rm "$TEMP_NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT
docker network connect "$TEMP_NETWORK" "$CONTAINER"

api() {
  docker run --rm --network "$RUNNER_NETWORK" curlimages/curl:8.10.1 -sS -f "$@"
}

for pkg in $PACKAGES; do
  name="${pkg%%=*}"
  version="${pkg#*=}"
  echo "Installing $name ($version)..."
  api -X POST -H 'Content-Type: application/json' \
      -d "{\"language\":\"$name\",\"version\":\"$version\"}" \
      http://piston:2000/api/v2/packages
  echo
done

echo "Installed runtimes:"
api http://piston:2000/api/v2/runtimes
echo
