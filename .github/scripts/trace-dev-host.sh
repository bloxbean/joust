#!/usr/bin/env bash
# ADR-0022 I10, Linux CI. Drop privileges inside the isolated network namespace before running strace or Java.
set -euo pipefail
trace=$1
shift
exec sudo -n /usr/bin/unshare --net -- /usr/bin/setpriv --reuid="$(id -u)" --regid="$(id -g)" --clear-groups \
    --no-new-privs /usr/bin/env -i HOME="$HOME" PATH="$PATH" LANG="${LANG:-C}" \
    /usr/bin/strace -f -s 80 -e trace=read,connect,socket,execve,openat -o "$trace" -- "$@"
