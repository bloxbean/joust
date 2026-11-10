#!/usr/bin/env bash
# Yaci DevKit for the devnet jobs of .github/workflows/build.yml.
#   devkit.sh start   start a new devnet with Yaci Store in the background (log: $RUNNER_TEMP/yaci-devkit.log)
#   devkit.sh wait    wait until it is ready; if it is not, restart it once and wait again
#   devkit.sh monitor log the node's and Yaci Store's block heights every 15 s (in the background, until the job ends)
# Ready means the admin API answers and Yaci Store follows the chain: its latest block height advances and is within
# a few blocks of the node's (Ogmios). Once in three CI runs Yaci Store stayed at an early height after DevKit's
# bootstrap while the node went on producing blocks ("Yaci Store did not reach chain tip within 60s. Proceeding
# anyway"): every test's top-up then timed out. Advancing alone is not enough: in bloxbean/joust#37 Yaci Store moved
# from 10 to 11 once, passed, and stayed at 11 while the node reached 200.
set -euo pipefail

LOG="${RUNNER_TEMP:-/tmp}/yaci-devkit.log"
HEIGHTS="${RUNNER_TEMP:-/tmp}/yaci-devkit-heights.log"
# DevKit's default 1 s slots. 200 ms slots saved only ~10% of the DevKit test time (the tests wait mostly on Yaci Store
# polling and fixed sleeps) and DevKit stalled in 2 of 12 shard runs with them (bloxbean/joust#39).
ARGS=(--enable-yaci-store)

start() {
  nohup yaci-devkit up "${ARGS[@]}" >> "$LOG" 2>&1 &
}

store_height() {
  curl -sf -m 5 http://localhost:8080/api/v1/blocks/latest | python3 -c 'import json,sys; print(json.load(sys.stdin)["height"])' 2>/dev/null || echo -1
}

node_height() {
  curl -sf -m 5 -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","method":"queryNetwork/blockHeight"}' \
    http://localhost:1337 | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"])' 2>/dev/null || echo -1
}

# Up to $1 seconds: the admin API answers, Yaci Store's height advances over 10 s (~10 blocks at 1 s), and it is at
# most MAX_LAG blocks behind the node.
MAX_LAG=5
ready_within() {
  local deadline=$((SECONDS + $1)) h1 h2 n
  while ((SECONDS < deadline)); do
    if curl -sf -m 5 -o /dev/null http://localhost:10000/local-cluster/api/admin/devnet; then
      h1=$(store_height); sleep 10; h2=$(store_height); n=$(node_height)
      echo "Yaci Store height: $h1 -> $h2 (node $n)"
      if ((h1 >= 0 && h2 > h1 && n >= 0 && n - h2 <= MAX_LAG)); then return 0; fi
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
  monitor)
    nohup bash -c 'while true; do echo "$(date -u +%H:%M:%S) node $('"$0"' node-height) store $('"$0"' store-height)"; sleep 15; done' \
      > "$HEIGHTS" 2>&1 &
    ;;
  node-height) node_height ;;
  store-height) store_height ;;
  *) echo "usage: $0 start|wait|monitor" >&2; exit 2 ;;
esac
