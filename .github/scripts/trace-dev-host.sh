#!/usr/bin/env bash
# ADR-0022 I10, Linux CI. A "?" name is traced where the kernel and strace know it, ignored elsewhere.
# Drop privileges inside the isolated network namespace before running strace or Java.
set -euo pipefail
trace=$1
shift
exec sudo -n /usr/bin/unshare --net -- /usr/bin/setpriv --reuid="$(id -u)" --regid="$(id -g)" --clear-groups \
    --no-new-privs /usr/bin/env -i HOME="$HOME" PATH="$PATH" LANG="${LANG:-C}" \
    /usr/bin/strace -f -s 80 \
    -e trace=read,connect,socket,socketpair,bind,listen,accept,accept4,execve,execveat,fork,vfork,clone,clone3,openat,openat2,open,creat,mkdir,mkdirat,rmdir,unlink,unlinkat,rename,renameat,renameat2,truncate,ftruncate,link,linkat,symlink,symlinkat,chmod,fchmod,fchmodat,?fchmodat2,chown,fchown,lchown,fchownat,?utime,?utimes,?futimesat,utimensat,fallocate,mknod,mknodat,setxattr,lsetxattr,fsetxattr,removexattr,lremovexattr,fremovexattr,sendto,sendmsg \
    -o "$trace" -- "$@"
