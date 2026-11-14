package org.julclang.state.toolchain;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.julclang.state.dsl.v2.AbiCheck;
import org.junit.jupiter.api.AfterAll;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The npm joust's NODE_OPTIONS parser ({@code js/joust/src/toolchain.js}: {@code parseNodeOptions}, a port of Node 22's
 * {@code ParseNodeOptionsEnvVar}; and {@code stripHeapFlags}, #80 review C14, S12) against real Node. Inputs are
 * NODE_OPTIONS values made of {@code --conditions=} arguments whose values hold spaces, tabs, double quotes, backslashes
 * and non-ASCII text, heap flags in their spellings (one or two dashes, underscores, quoted parts, a separate value) and
 * other flags, separated by spaces or tabs. For each value:
 * <ul>
 *   <li>the port refuses it (an unterminated quote, a trailing escape) exactly when Node refuses it at startup as an
 *   invalid NODE_OPTIONS, for the same reason;</li>
 *   <li>otherwise, when Node starts, the {@code --conditions} values Node read (its {@code getOptionValue}, with
 *   {@code --expose-internals}) are the port's, in order;</li>
 *   <li>{@code stripHeapFlags} keeps every other argument exactly (the port reads its output back as the input's arguments
 *   less the heap flags and a separate value after one), and Node reads the same conditions from its output.</li>
 * </ul>
 * The port runs in one Node process for the run ({@code src/test/js/node-options-server.mjs}); real Node is started once
 * per input with the value as its NODE_OPTIONS.
 */
class NodeOptionsFuzzTest {

    static final Path SERVER = Path.of("src/test/js/node-options-server.mjs");
    static final Path TOOLCHAIN = Path.of("../js/joust/src/toolchain.js");
    static final FuzzData.Tally TALLY = new FuzzData.Tally("NodeOptionsFuzzTest");
    static final String PROBE = "const { getOptionValue } = require('internal/options'); process.stdout.write(JSON.stringify(getOptionValue('--conditions')))";
    static Process port;
    static BufferedWriter toPort;
    static BufferedReader fromPort;

    @AfterAll
    static void report() {
        TALLY.write();
        if (port != null) port.destroy();
    }

    // ------------------------------------------------------------------ inputs

    static final String[] PIECES = {"a", "b", "c1", "é", " ", "  ", "\t", "\"", "\\", "\\\"", "=", "-x", "--y", "\"q r\""};
    static final String[] HEAP = {"--max-old-space-size=4096", "--max_old_space_size=4096", "-max-old-space-size=4096",
            "--max-\"old-space-size\"=4096", "--max-old-space-size 4096", "--max-heap-size=4096", "\"--max-old-space-size=4096\""};
    static final String[] OTHER = {"--no-deprecation", "--no-warnings", "--trace-uncaught"};

    static String value(FuzzedDataProvider d) {
        var sb = new StringBuilder();
        if (d.consumeInt(0, 5) == 0) sb.append(new String[]{" ", "\t", "  "}[d.consumeInt(0, 2)]);
        for (int t = d.consumeInt(1, 4); t > 0; t--) {
            switch (d.consumeInt(0, 5)) {
                case 0 -> sb.append(HEAP[d.consumeInt(0, HEAP.length - 1)]);
                case 1 -> sb.append(OTHER[d.consumeInt(0, OTHER.length - 1)]);
                default -> {
                    boolean quoted = d.consumeInt(0, 2) == 0;
                    sb.append(quoted ? "\"--conditions=" : "--conditions=");
                    sb.append(new String[]{"a", "b", "x"}[d.consumeInt(0, 2)]);
                    for (int p = d.consumeInt(0, 5); p > 0; p--) sb.append(PIECES[d.consumeInt(0, PIECES.length - 1)]);
                    if (quoted && d.consumeInt(0, 5) != 0) sb.append('"');
                }
            }
            if (t > 1) sb.append(new String[]{" ", " ", "  ", "\t"}[d.consumeInt(0, 3)]);
        }
        if (d.consumeInt(0, 5) == 0) sb.append(' ');
        return sb.toString();
    }

    // ------------------------------------------------------------------ the two parsers

    @SuppressWarnings("unchecked")
    static synchronized Map<String, Object> port(String value) throws IOException {
        if (port == null || !port.isAlive()) {
            port = new ProcessBuilder("node", SERVER.toString(), TOOLCHAIN.toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            toPort = new BufferedWriter(new OutputStreamWriter(port.getOutputStream(), StandardCharsets.UTF_8));
            fromPort = new BufferedReader(new InputStreamReader(port.getInputStream(), StandardCharsets.UTF_8));
        }
        toPort.write(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        toPort.newLine();
        toPort.flush();
        String line = fromPort.readLine();
        assertNotNull(line, "the port's process ended");
        return (Map<String, Object>) AbiCheck.Json.parse(line);
    }

    /** Real Node with {@code value} as its NODE_OPTIONS: its exit code, its output (the conditions as JSON) and its error output. */
    record NodeRun(int exit, String out, String err) {}

    static NodeRun node(String value) throws IOException, InterruptedException {
        var pb = new ProcessBuilder("node", "--expose-internals", "-e", PROBE);
        pb.environment().put("NODE_OPTIONS", value);
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("node did not finish with NODE_OPTIONS=" + value);
        }
        return new NodeRun(p.exitValue(), out, err);
    }

    static List<String> conditions(List<?> args) {
        var out = new ArrayList<String>();
        for (Object a : args) if (((String) a).startsWith("--conditions=")) out.add(((String) a).substring("--conditions=".length()));
        return out;
    }

    /** The port's rule for a heap flag (V8 reads '-' and '_' alike, and one or two leading dashes), restated. */
    static boolean heapFlag(String arg) {
        String name = arg.split("=", -1)[0].replace('_', '-').replaceFirst("^-{1,2}", "--");
        return name.equals("--max-old-space-size") || name.equals("--max-heap-size");
    }

    static List<String> withoutHeapFlags(List<?> args) {
        var out = new ArrayList<String>();
        for (int i = 0; i < args.size(); i++) {
            String a = (String) args.get(i);
            if (heapFlag(a)) {
                if (!a.contains("=") && i + 1 < args.size() && ((String) args.get(i + 1)).matches("[0-9]+")) i++;
                continue;
            }
            out.add(a);
        }
        return out;
    }

    static String reason(String text) {
        var m = java.util.regex.Pattern.compile("invalid value for NODE_OPTIONS \\(([^)]*)\\)").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    @FuzzTest(maxDuration = "30s")
    void portAgreesWithNode(FuzzedDataProvider data) throws Exception {
        TALLY.input();
        String value = value(data);
        var a = port(value);
        NodeRun n = node(value);
        String what = "NODE_OPTIONS=" + AbiCheckJson.quote(value) + "\n  port: " + a + "\n  node: exit " + n.exit() + ", out " + n.out() + ", err " + n.err().strip();
        try {
            if (a.containsKey("error")) {
                TALLY.count("refused by both: " + reason((String) a.get("error")));
                assertNotEquals(0, n.exit(), "the port refuses, Node starts: " + what);
                assertEquals(reason((String) a.get("error")), reason(n.err()), "the port and Node refuse for different reasons: " + what);
                return;
            }
            assertNull(reason(n.err()), "Node refuses as an invalid NODE_OPTIONS what the port reads: " + what);
            var args = (List<?>) a.get("args");
            // The strip keeps every other argument, as the port reads it back.
            assertEquals(withoutHeapFlags(args), a.get("strippedArgs"), "stripHeapFlags changes what it keeps: " + what);
            if (n.exit() != 0 || !n.out().startsWith("[")) {
                // Node refuses an argument it does not allow in NODE_OPTIONS (a piece a space split off), or V8 a flag's value.
                TALLY.count("Node refuses an argument (no comparison)");
                return;
            }
            var nodeConditions = (List<?>) AbiCheck.Json.parse(n.out());
            assertEquals(conditions(args), nodeConditions, "the port and Node read different --conditions: " + what);
            TALLY.count("conditions agree");
            if (args.stream().anyMatch(x -> heapFlag((String) x))) {
                NodeRun s = node((String) a.get("stripped"));
                if (s.exit() == 0 && s.out().startsWith("[")) {
                    assertEquals(nodeConditions, AbiCheck.Json.parse(s.out()), "Node reads other conditions after the strip: " + what);
                    TALLY.count("conditions agree after the strip");
                } else {
                    fail("Node does not start with the stripped value " + a.get("stripped") + ": " + s.err() + "\n" + what);
                }
            }
        } catch (AssertionError e) {
            var p = FuzzData.reproducer("NodeOptionsFuzzTest", what + "\n" + e.getMessage() + "\n");
            throw new AssertionError(e.getMessage() + " (" + p + ")", e);
        }
    }

    /** A Java string literal of a value, for the failure record. */
    static final class AbiCheckJson {
        static String quote(String s) {
            return org.julclang.state.dsl.v2.JscV2.javaString(s);
        }
    }
}
