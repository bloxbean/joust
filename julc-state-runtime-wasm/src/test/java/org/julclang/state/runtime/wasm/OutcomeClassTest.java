package org.julclang.state.runtime.wasm;

import org.julclang.core.PlutusData;
import org.julclang.state.runtime.described.TransitionFn;
import org.julclang.state.runtime.described.UplcTransitions;
import org.julclang.state.toolchain.Toolchain;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0020 D9, adopted (§11.4): the engine's programs carry the tagged {@code Require}, and a failed run is classified
 * by the tag, never by its last trace. On the test-only {@code Probe} contract (src/test/resources/probe):
 * <ul>
 *   <li>a refusal keeps the contract's message;</li>
 *   <li>a message that starts with {@code joust:} is kept (the last-trace rule read it as a discovery trace and lost
 *       it);</li>
 *   <li>a failure that is not a refusal is an error with the VM's cause, and a trace before it is not its message;</li>
 *   <li>a forged tag only changes the text a failure shows: no step comes out of it;</li>
 *   <li>a run over the budget says so.</li>
 * </ul>
 */
class OutcomeClassTest {

    static TransitionFn programs;

    @BeforeAll
    static void build() throws Exception {
        String path = "org/julclang/state/probe/Probe.java";
        var r = Toolchain.build(Map.of(path, Files.readString(Path.of("src/test/resources/probe").resolve(path))), Map.of());
        assertTrue(r.ok(), "Probe: " + r.diagnostics());
        programs = new UplcTransitions(r.contracts().get(0).transitions());
    }

    static final PlutusData NONE = PlutusData.constr(0);

    static TransitionFn.Run run(String method, long arg) {
        var state = PlutusData.constr(0, PlutusData.integer(7), PlutusData.bytes(new byte[28]));
        return programs.run(method, state, NONE, PlutusData.constr(0, PlutusData.integer(arg)), NONE);
    }

    @Test
    void aRefusalKeepsTheContractsMessage() {
        var r = run("positive", 0);
        assertNull(r.step());
        assertEquals("by must be positive", r.refusal());
        assertTrue(r.traces().contains(UplcTransitions.REQUIRE_TAG + "by must be positive"), String.valueOf(r.traces()));
        assertNotNull(run("positive", 1).step());
    }

    @Test
    void aMessageWithTheFrameworksPrefixIsKept() {
        var r = run("prefixed", 0);
        assertNull(r.step());
        assertEquals("joust:read 00 01", r.refusal());
    }

    @Test
    void aFailureThatIsNotARefusalIsAnErrorWithTheVmsCause() {
        var r = run("divide", 0);
        assertNull(r.step());
        assertTrue(r.refusal().startsWith("divide failed: "), r.refusal());
        assertFalse(r.refusal().contains("dividing"), "a trace is never taken as the message: " + r.refusal());
        assertTrue(r.traces().contains("dividing"), String.valueOf(r.traces()));
    }

    @Test
    void aForgedTagChangesOnlyTheText() {
        var r = run("forged", 0);
        assertNull(r.step(), "a forged tag never makes a step");
        assertEquals("fake", r.refusal());
        assertNotNull(run("forged", 1).step());
    }

    @Test
    void aRunOverTheBudgetSaysSo() {
        var r = run("spin", 1_000_000_000L);
        assertNull(r.step());
        assertEquals("spin: over the execution budget", r.refusal());
    }
}
