package org.julclang.state.host;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The I10 trace gate's syscall classification, on representative strace lines (any platform; #150 Codex review). */
class TraceGateTest {
    @Test void everyFileMutationIsRecognised_withAndWithoutItsAtSuffix() {
        for (String call : List.of("creat", "mkdir", "mkdirat", "rmdir", "unlink", "unlinkat", "rename", "renameat",
                "renameat2", "truncate", "ftruncate", "link", "linkat", "symlink", "symlinkat", "chmod", "fchmod",
                "fchmodat", "utimensat", "mknod", "mknodat", "setxattr", "lsetxattr", "fsetxattr", "removexattr",
                "lremovexattr", "fremovexattr")) {
            String line = "12345 " + call + "(\"/home/u/x\", 0644) = 0";
            assertTrue(ForkedSessionTest.mutatesFiles(line), line);
        }
        for (String call : List.of("read", "write", "close", "fstat", "mmap", "futex", "readlink", "unlinka", "mkdira", "getxattr", "fgetxattr", "listxattr")) {
            String line = "12345 " + call + "(3, ...) = 0";
            assertFalse(ForkedSessionTest.mutatesFiles(line), line);
        }
    }
}
