package org.julclang.state.host;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** The I10 trace gate's syscall classification, on representative strace lines (any platform; #150 Codex review). */
class TraceGateTest {
    /** Every file-mutating call the gate refuses while serving (#150 reviews: the chown family, utimes, fallocate too). */
    static final List<String> MUTATIONS = List.of("creat", "mkdir", "mkdirat", "rmdir", "unlink", "unlinkat", "rename",
            "renameat", "renameat2", "truncate", "ftruncate", "fallocate", "link", "linkat", "symlink", "symlinkat", "chmod",
            "fchmod", "fchmodat", "fchmodat2", "chown", "fchown", "lchown", "fchownat", "utime", "utimes", "futimesat",
            "utimensat", "mknod", "mknodat", "setxattr", "lsetxattr", "fsetxattr", "removexattr", "lremovexattr",
            "fremovexattr");

    @Test void everyFileMutationIsRecognised_withAndWithoutItsAtSuffix() {
        for (String call : MUTATIONS) {
            String line = "12345 " + call + "(\"/home/u/x\", 0644) = 0";
            assertTrue(ForkedSessionTest.mutatesFiles(line), line);
        }
        for (String call : List.of("read", "write", "close", "fstat", "mmap", "futex", "readlink", "unlinka", "mkdira", "getxattr",
                "fgetxattr", "listxattr", "chowna", "utimesa")) {
            String line = "12345 " + call + "(3, ...) = 0";
            assertFalse(ForkedSessionTest.mutatesFiles(line), line);
        }
    }

    @Test void theCiScriptTracesEveryCallTheGateChecks() throws Exception {
        // A name the gate recognises but strace is not asked to record would never appear in the log (#150 Fable F7).
        Path script = Path.of("..", ".github", "scripts", "trace-dev-host.sh");
        String text = Files.readString(script);
        var m = java.util.regex.Pattern.compile("-e trace=([^ \\n]+)").matcher(text);
        assertTrue(m.find(), "no -e trace= list in " + script);
        Set<String> traced = Arrays.stream(m.group(1).split(",")).map(n -> n.startsWith("?") ? n.substring(1) : n)
                .collect(Collectors.toSet());
        for (String call : MUTATIONS) assertTrue(traced.contains(call), call + " is not traced by " + script);
    }
}
