package org.julclang.state.runtime.wasm;

import org.julclang.core.PlutusData;
import org.julclang.state.runtime.described.UplcTransitions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0020 D3, U8: the per-call latch. A JVM side that disagrees on selected evaluations (a tampered step) must fail
 * the call, with no transaction, whether the batch still had a matching intent to build or none at all; a latched call
 * must not fail a clean concurrent one. Also D11: production {@code UplcTransitions} shared between threads.
 */
class LatchTest {

    @AfterEach
    void clear() throws Exception {
        Spike.uninstall();
    }

    /** Which evaluations of the current thread's call the JVM side gets wrong: none, the first, or all. */
    enum Tamper { NONE, FIRST, ALL }

    static final ThreadLocal<Tamper> TAMPER = ThreadLocal.withInitial(() -> Tamper.NONE);
    static final ThreadLocal<int[]> SEEN = ThreadLocal.withInitial(() -> new int[1]);

    /** A JVM side whose step is altered (an extra field) for the evaluations {@link #TAMPER} selects. */
    static ShadowTransitions.Side tampering(JvmTransitions jvm) {
        return (method, target, shared, args, ctx) -> {
            Outcome o = jvm.outcome(method, target, shared, args, ctx);
            if (o.kind() != Outcome.Kind.STEP || !o.isFinal()) return o;
            int n = SEEN.get()[0]++;
            boolean wrong = switch (TAMPER.get()) {
                case NONE -> false;
                case FIRST -> n == 0;
                case ALL -> true;
            };
            if (!wrong) return o;
            var c = (PlutusData.ConstrData) o.step();
            var fields = new ArrayList<>(c.fields());
            fields.add(PlutusData.integer(1));
            return new Outcome(Outcome.Kind.STEP, new PlutusData.ConstrData(c.tag(), fields), null, o.traces(), 0, 0, 0);
        };
    }

    static List<ReplayDifferentialTest.Case> batches() throws Exception {
        return ReplayDifferentialTest.m3().stream().filter(c -> c.name().startsWith("m3/pool/") && c.name().contains("batch")).toList();
    }

    static void installTampering(List<ReplayDifferentialTest.Case> cases) throws Exception {
        var all = new ArrayList<Prepare.Contract>();
        for (var c : cases) for (var pc : c.request().contracts()) if (!all.contains(pc)) all.add(pc);
        Spike.install(all, pc -> {
            try {
                var built = Spike.contract(Spike.title(pc.description()));
                return new ShadowTransitions(built.name(), new UplcRunner(pc.transitions()),
                        tampering(new JvmTransitions(Spike.transitionsClass(built))));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    static Prepare.Result run(ReplayDifferentialTest.Case c, Tamper t) {
        TAMPER.set(t);
        SEEN.get()[0] = 0;
        try {
            return Spike.prepare(c.request());
        } finally {
            TAMPER.remove();
        }
    }

    @Test
    void aMismatchFailsTheCallWithNoTransaction() throws Exception {
        var cases = batches();
        installTampering(cases);
        int multi = 0, checked = 0;
        for (var c : cases) {
            var clean = run(c, Tamper.NONE);
            assertTrue(clean.ok() && clean.cbor().equals(c.expected()), c.name() + " clean: " + clean.error());
            int evaluations = SEEN.get()[0];
            // One matching and one mismatching intent: Batcher skips the bad one and could build; the latch refuses.
            var one = run(c, Tamper.FIRST);
            assertFalse(one.ok(), c.name() + ": first-intent mismatch built a transaction");
            assertNull(one.cbor());
            assertTrue(one.error().contains("shadow mismatch"), one.error());
            // Every intent mismatching: Batcher returns without building; the end-of-call check still fails it.
            var all = run(c, Tamper.ALL);
            assertFalse(all.ok(), c.name() + ": all-mismatching batch did not fail");
            assertNull(all.cbor());
            if (evaluations > 1) multi++;
            checked++;
        }
        assertTrue(multi > 0, "no batch with two or more intents");
        var body = Report.JSON.createObjectNode();
        body.put("batches", checked);
        body.put("batchesWithSeveralIntents", multi);
        body.put("result", "a mismatch on the first intent, and on every intent, failed the call with no transaction");
        Report.write("latch", body);
    }

    @Test
    void aLatchedCallDoesNotFailACleanConcurrentOne() throws Exception {
        var cases = batches();
        installTampering(cases);
        var c = cases.get(cases.size() - 1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<Boolean>> clean = new ArrayList<>(), bad = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                clean.add(pool.submit((Callable<Boolean>) () -> {
                    var r = run(c, Tamper.NONE);
                    return r.ok() && r.cbor().equals(c.expected());
                }));
                bad.add(pool.submit((Callable<Boolean>) () -> !run(c, Tamper.ALL).ok()));
            }
            for (var f : clean) assertTrue(f.get(), "a clean call failed beside latched ones");
            for (var f : bad) assertTrue(f.get(), "a latched call succeeded");
        }
    }

    @Test
    void productionUplcTransitionsIsSafeToShare() throws Exception {
        var counter = Spike.contract("org.julclang.state.examples.counter.Counter");
        var fn = new UplcTransitions(counter.transitions());
        var owner = new byte[28];
        PlutusData none = PlutusData.constr(0);
        try (var pool = Executors.newFixedThreadPool(16)) {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                final long by = 1 + (i % 7);
                results.add(pool.submit(() -> {
                    var state = PlutusData.constr(0, PlutusData.integer(10), PlutusData.bytes(owner));
                    var step = fn.apply("increment", state, none, PlutusData.constr(0, PlutusData.integer(by)), none);
                    var next = (PlutusData.ConstrData) ((PlutusData.ConstrData) ((PlutusData.ConstrData) step).fields().get(0)).fields().get(0);
                    return ((PlutusData.IntData) next.fields().get(0)).value() + "/" + (10 + by);
                }));
            }
            for (var f : results) {
                String[] p = f.get().split("/");
                assertEquals(p[1], p[0]);
            }
        }
        // A malformed program now fails when the instance is built, not on its first call.
        assertThrows(RuntimeException.class, () -> new UplcTransitions(Map.of("x", "00")));
    }
}
