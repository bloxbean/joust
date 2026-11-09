#!/usr/bin/env bash
# Yaci DevKit for the devnet jobs of .github/workflows/build.yml.
#   devkit.sh start   start a new devnet with Yaci Store in the background (log: $RUNNER_TEMP/yaci-devkit.log)
#   devkit.sh wait    wait until it is ready; if it is not, restart it once and wait again
# Ready means the admin API answers and Yaci Store follows the chain: its latest block height advances. Once in
# three CI runs Yaci Store stayed at an early height after DevKit's bootstrap while the node went on producing
# blocks ("Yaci Store did not reach chain tip within 60s. Proceeding anyway"): every test's top-up then timed out.
set -euo pipefail

LOG="${RUNNER_TEMP:-/tmp}/yaci-devkit.log"
# 200 ms slots and blocks (default 1 s) make every confirmation wait shorter. The runtime reads the slot length from
# DevKit; the time horizon (3k/f = 300 slots) is then 60 s, still above the runtime's 20 s TTL (100 ms would leave 30 s).
ARGS=(--enable-yaci-store --block-time 0.2 --slot-length 0.2)

start() {
  nohup yaci-devkit up "${ARGS[@]}" >> "$LOG" 2>&1 &
}

store_height() {
  curl -sf -m 5 http://localhost:8080/api/v1/blocks/latest | python3 -c 'import json,sys; print(json.load(sys.stdin)["height"])' 2>/dev/null || echo -1
}

# Up to $1 seconds: the admin API answers, and Yaci Store's height advances over 10 s (50 blocks at 200 ms).
ready_within() {
  local deadline=$((SECONDS + $1)) h1 h2
  while ((SECONDS < deadline)); do
    if curl -sf -m 5 -o /dev/null http://localhost:10000/local-cluster/api/admin/devnet; then
      h1=$(store_height); sleep 10; h2=$(store_height)
      echo "Yaci Store height: $h1 -> $h2"
      if ((h1 >= 0 && h2 > h1)); then return 0; fi
    else
      sleep 5
    fi
  done
  return 1
}

stop() {
  pkill -f yaci-devkit || true
  pkill -f yaci-cli || true
  pkill -f cardano-node || true
  pkill -f yaci-store || true
  pkill -f ogmios || true
  pkill -f yano || true
  sleep 5
}

case "${1:-}" in
  start) start ;;
  wait)
    if ready_within 300; then echo "Yaci DevKit is up"; exit 0; fi
    echo "::warning::Yaci DevKit was not ready (Yaci Store not following the chain): restarting it"
    tail -n 60 "$LOG"
    stop
    echo "===== restart =====" >> "$LOG"
    start
    if ready_within 300; then echo "Yaci DevKit is up after a restart"; exit 0; fi
    echo "::error::Yaci DevKit did not become ready"
    tail -n 200 "$LOG"
    exit 1
    ;;
  *) echo "usage: $0 start|wait" >&2; exit 2 ;;
esac
