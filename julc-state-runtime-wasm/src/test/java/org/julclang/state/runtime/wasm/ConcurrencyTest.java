package org.julclang.state.runtime.wasm;

import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.state.runtime.described.TransitionFn;
import org.julclang.state.runtime.described.TransitionInputs;
import org.julclang.state.runtime.described.UplcTransitions;
import org.julclang.state.toolchain.Toolchain;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0020 D11 (PR #62 F5, S7): one engine shared by concurrent calls gives the sequential answers.
 * <ul>
 *   <li>one {@link UplcTransitions} with its {@link TransitionInputs}, shared by 16 threads over steps, refusals, VM
 *       errors and malformed inputs (Counter and the test-only Probe);</li>
 *   <li>{@link Prepare} itself (its contract cache), preparing the 34 recorded dApp calls from 8 threads at once, each
 *       byte-identical to its recording.</li>
 * </ul>
 */
class ConcurrencyTest {

    static final PlutusData NONE = PlutusData.constr(0);

    static String show(TransitionFn f, String m, PlutusData t, PlutusData a) {
        try {
            var r = f.run(m, t, NONE, a, NONE);
            return (r.step() == null ? "-" : HexFormat.of().formatHex(PlutusDataCborEncoder.encode(r.step()))) + "/" + r.refusal();
        } catch (TransitionInputs.Malformed e) {
            return "malformed/" + e.getMessage();
        }
    }

    @Test
    void sharedTransitionsGiveTheSequentialAnswers() throws Exception {
        var counter = Builds.prepareContract(Builds.contract("org.julclang.state.examples.counter.Counter"));
        TransitionFn c = new TransitionInputs(Comparison.JSON.readTree(counter.description()),
                UplcTransitions.verified(counter.manifest(), counter.description(), counter.script(), counter.transitions()));
        String path = "org/julclang/state/probe/Probe.java";
        var built = Toolchain.build(Map.of(path, Files.readString(Path.of("src/test/resources/probe").resolve(path))), Map.of());
        assertTrue(built.ok(), String.valueOf(built.diagnostics()));
        var pc = built.contracts().get(0);
        TransitionFn probe = new TransitionInputs(Comparison.JSON.readTree(pc.descriptionJson()),
                UplcTransitions.verified(pc.manifestJson(), pc.descriptionJson(), pc.scriptCborHex(), pc.transitions()));
        record In(TransitionFn f, String m, PlutusData t, PlutusData a) {}
        var ins = new ArrayList<In>();
        for (int i = 0; i < 40; i++) {
            var st = PlutusData.constr(0, PlutusData.integer(i * 13), PlutusData.bytes(new byte[28]));
            ins.add(new In(c, "increment", st, PlutusData.constr(0, PlutusData.integer(i % 5))));               // steps and refusals
            ins.add(new In(c, "increment", PlutusData.constr(1, PlutusData.integer(i)), PlutusData.constr(0, PlutusData.integer(1))));   // malformed
            ins.add(new In(probe, "divide", st, PlutusData.constr(0, PlutusData.integer(i % 3))));             // VM errors
            ins.add(new In(probe, "positive", st, PlutusData.constr(0, PlutusData.integer(i % 2))));
        }
        var expected = new ArrayList<String>();
        for (In in : ins) expected.add(show(in.f(), in.m(), in.t(), in.a()));
        try (var pool = Executors.newFixedThreadPool(16)) {
            var futures = new ArrayList<Future<Integer>>();
            for (int k = 0; k < 32; k++) {
                final int off = k;
                futures.add(pool.submit((Callable<Integer>) () -> {
                    int diffs = 0;
                    for (int j = 0; j < ins.size(); j++) {
                        int i = (j + off) % ins.size();
                        In in = ins.get(i);
                        if (!show(in.f(), in.m(), in.t(), in.a()).equals(expected.get(i))) diffs++;
                    }
                    return diffs;
                }));
            }
            int diffs = 0;
            for (var f : futures) diffs += f.get();
            assertEquals(0, diffs, "answers that differ from the sequential ones");
        }
        System.out.println("[D11] " + 32 * ins.size() + " concurrent runs on 16 threads, as sequential");
    }

    @Test
    void concurrentPreparesGiveTheRecordedTransactions() throws Exception {
        var cases = ReplayDifferentialTest.dapp();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<List<String>>>();
            for (int k = 0; k < 8; k++) {
                final int off = k;
                futures.add(pool.submit((Callable<List<String>>) () -> {
                    var bad = new ArrayList<String>();
                    for (int j = 0; j < cases.size(); j++) {
                        var c = cases.get((j + off * 5) % cases.size());
                        var r = Prepare.prepare(c.request());
                        if (!r.ok() || !c.expected().equals(r.cbor())) bad.add(c.name() + ": " + (r.ok() ? "different bytes" : r.error()));
                    }
                    return bad;
                }));
            }
            var bad = new ArrayList<String>();
            for (var f : futures) bad.addAll(f.get());
            assertEquals(List.of(), bad);
        }
        System.out.println("[D11] " + 8 * 34 + " concurrent prepares on 8 threads, each byte-identical to its recording");
    }
}
