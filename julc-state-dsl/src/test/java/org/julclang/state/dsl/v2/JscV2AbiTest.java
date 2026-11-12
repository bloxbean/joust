package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D12, J17 (review F1, F2, S1, S3, C2): the ABI manifest states every encoded layout (the datum, each
 * redeemer with its constructor index and every field, the generated seed and ctx included), changes exactly when an
 * encoding changes, and the Gradle path's gate refuses a missing or changed manifest unless the build accepts it.
 */
class JscV2AbiTest {

    static final String COUNTER = """
            jsc 2
            package app
            contract Counter {
              state { count: Int, owner: Key }
              init() { return { count: 0, owner: caller } }
              action bump(by: Int) {
                require(by > 0, "positive")
                return { ...state, count: count + by }
              }
              destroy close() only owner { pay(owner, balance) }
              view current(): Int = count
            }
            """;

    static String abi(String src) {
        return JscV2.lower(src, "Counter.jsc").abiJson();
    }

    @Test
    void redeemers_stateEveryEncodedField_theGeneratedCtxAndSeedIncluded() {
        String a = abi(COUNTER);
        assertTrue(a.contains("\"spend\": [\n      { \"name\": \"Bump\", \"constr\": 0, \"fields\": [\n          { \"name\": \"by\""), a);
        assertTrue(a.contains("{ \"name\": \"Close\", \"constr\": 1, \"fields\": [\n          { \"name\": \"ctx\", \"type\": \"Ctx\", \"data\": \"Ctx\" }"), a);
        assertTrue(a.contains("{ \"name\": \"CreateInstance\", \"constr\": 0, \"fields\": [\n          { \"name\": \"seed\", \"type\": \"TxOutRef\""), a);
        assertTrue(a.contains("{ \"name\": \"BurnInstance\", \"constr\": 1, \"fields\": [] }"), a);
        assertTrue(a.contains("\"Ctx\": { \"kind\": \"api\""), a);
        assertFalse(a.contains("current"), "views are not encoded on-chain: " + a);
    }

    /** Security S1, Codex C2: a body-only read of caller adds ctx to the redeemer, so the manifest changes. */
    @Test
    void aBodyOnlyCallerRead_changesTheManifest() {
        String withCaller = COUNTER.replace("require(by > 0, \"positive\")", "require(by > 0 && caller != owner, \"positive\")");
        String before = abi(COUNTER), after = abi(withCaller);
        assertNotEquals(before, after);
        assertTrue(after.contains("{ \"name\": \"Bump\", \"constr\": 0, \"fields\": [\n          { \"name\": \"by\", \"type\": \"Int\", \"data\": \"int\" },\n"
                + "          { \"name\": \"ctx\""), after);
        // payable and after/before add ctx too.
        assertNotEquals(before, abi(COUNTER.replace("action bump(by: Int) {", "action bump(by: Int) payable {")));
    }

    /** Design F2: moving a view or a destroy changes no encoding, so the manifest stays; reordering actions does not. */
    @Test
    void reorderingsThatKeepEncodings_keepTheManifest() {
        String moved = COUNTER.replace("  view current(): Int = count\n", "").replace("  init() {", "  view current(): Int = count\n  init() {");
        assertEquals(abi(COUNTER), abi(moved));
        String destroyFirst = COUNTER.replace("  destroy close() only owner { pay(owner, balance) }\n", "")
                .replace("  action bump", "  destroy close() only owner { pay(owner, balance) }\n  action bump");
        assertEquals(abi(COUNTER), abi(destroyFirst), "the processor numbers actions first, then destroys");
        String twoActions = COUNTER.replace("  destroy close", "  action reset() only owner { return { ...state, count: 0 } }\n  destroy close");
        String swapped = twoActions.replace("  action reset() only owner { return { ...state, count: 0 } }\n", "")
                .replace("  action bump", "  action reset() only owner { return { ...state, count: 0 } }\n  action bump");
        assertNotEquals(abi(twoActions), abi(swapped), "swapping two actions swaps their constructor indices");
    }

    // ------------------------------------------------------------------ the Gradle path's gate (JscMain)

    record Run(int code, String err) {}

    static Run run(Path src, Path out, boolean accept) throws Exception {
        var err = new ByteArrayOutputStream();
        PrintStream old = System.err;
        System.setErr(new PrintStream(err, true));
        try {
            int code = accept ? JscMain.run(new String[]{src.toString(), out.toString(), "--accept-abi"})
                    : JscMain.run(new String[]{src.toString(), out.toString()});
            return new Run(code, err.toString());
        } finally {
            System.setErr(old);
        }
    }

    @Test
    void theGate_missing_equal_changed_accepted(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("jsc"), out = dir.resolve("out");
        Files.createDirectories(src.resolve("app"));
        Path jsc = src.resolve("app/Counter.jsc"), manifest = src.resolve("app/Counter.abi.json");
        Files.writeString(jsc, COUNTER);
        // Missing: refused (JSC0003), nothing written.
        Run r = run(src, out, false);
        assertEquals(1, r.code(), r.err());
        assertTrue(r.err().contains("app/Counter.jsc:1:1: error[JSC0003]: no ABI manifest Counter.abi.json"), r.err());
        assertFalse(Files.exists(manifest));
        // Accepted: created.
        r = run(src, out, true);
        assertEquals(0, r.code(), r.err());
        assertEquals(abi(COUNTER), Files.readString(manifest));
        // Equal: passes.
        assertEquals(0, run(src, out, false).code());
        // Changed (a body-only caller read): refused (JSC0002), naming the first difference; the committed one is kept.
        String committed = Files.readString(manifest);
        Files.writeString(jsc, COUNTER.replace("require(by > 0, \"positive\")", "require(by > 0 && caller != owner, \"positive\")"));
        r = run(src, out, false);
        assertEquals(1, r.code(), r.err());
        assertTrue(r.err().contains("error[JSC0002]: the ABI manifest Counter.abi.json differs from the committed one at line "), r.err());
        assertTrue(r.err().contains("\"name\": \"ctx\""), r.err());
        assertEquals(committed, Files.readString(manifest));
        // A system property does not accept (only --accept-abi does).
        System.setProperty("joust.acceptAbi", "true");
        try {
            assertEquals(1, run(src, out, false).code());
        } finally {
            System.clearProperty("joust.acceptAbi");
        }
        // Accepted: rewritten.
        assertEquals(0, run(src, out, true).code());
        assertNotEquals(committed, Files.readString(manifest));
    }
}
