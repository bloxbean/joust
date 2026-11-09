#!/usr/bin/env bash
# ADR-0015 I4 on linux-x64: no-java.sh in a linux/amd64 Node 22 container (no Java in the image at all), against the
# local registry and the DevKit on the host. Needs Docker (on Apple silicon the amd64 image runs emulated).
set -euo pipefail
cd "$(dirname "$0")"
docker run --rm --platform linux/amd64 \
  -e REG=http://host.docker.internal:4873/ -e DEVKIT_HOST=host.docker.internal \
  -v "$PWD:/acceptance:ro" node:22-bookworm-slim \
  bash -c 'uname -sm; node -v; command -v java || echo "no java in the image"; bash /acceptance/no-java.sh'
