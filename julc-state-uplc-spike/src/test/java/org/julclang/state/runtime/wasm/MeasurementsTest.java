package org.julclang.state.runtime.wasm;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.state.runtime.described.TransitionFn;
import org.julclang.state.toolchain.Toolchain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * ADR-0020 D5 (U6): the spike's measurements, written to build/spike/measurements.json with commit, machine and VM.
 * <ol>
 *   <li>latency: VM-only per evaluation and JVM-only per evaluation; whole prepares through the described core with
 *       each transition function (warm-up, then {@code spike.reps} repetitions; p50/p95);</li>
 *   <li>execution units per transition, against the validators' units in the same recorded transaction;</li>
 *   <li>sizes: programs per contract, against the validator; auxiliary and diagnostic sets;</li>
 *   <li>evaluations per call (map rounds);</li>
 *   <li>worst cases: a budget-exhausting evaluation; the call with the most evaluations.</li>
 * </ol>
 * It also writes build/spike/node-vectors.json for the Node run through the engine's VM.
 */
class MeasurementsTest {

    static final int REPS = Integer.getInteger("spike.reps", 10);

    @AfterEach
    void clear() throws Exception {
        Spike.uninstall();
    }

    /** One evaluation seen during a replay. */
    record Eval(String contract, String method, String side, long nanos, long cpu, long mem, PlutusData[] in, Outcome out, String program) {}

    static final List<Eval> EVALS = Collections.synchronizedList(new ArrayList<>());
    static volatile boolean recording;

    static TransitionFn recordingFn(Spike.Mode mode, Prepare.Contract c) {
        String name = Spike.title(c.description()).replaceAll(".*\\.", "");
        TransitionFn inner = Spike.fn(mode, c);
        ShadowTransitions.Side side = (ShadowTransitions.Side) inner;
        return new TransitionFn() {
            @Override
            public PlutusData apply(String m, PlutusData t, PlutusData s, PlutusData a, PlutusData x) {
                var r = run(m, t, s, a, x);
                if (r.step() == null) throw new org.julclang.state.api.RequireException(r.refusal());
                return r.step();
            }

            @Override
            public Run run(String m, PlutusData t, PlutusData s, PlutusData a, PlutusData x) {
                Outcome o = side.outcome(m, t, s, a, x);
                if (recording) EVALS.add(new Eval(name, m, mode.name(), o.nanos(), o.cpu(), o.mem(), new PlutusData[]{t, s, a, x}, o,
                        inner instanceof UplcRunner u ? u.hex.get(m) : null));
                return new Run(o.kind() == Outcome.Kind.STEP ? o.step() : null, o.kind() == Outcome.Kind.STEP ? null : o.message(), o.traces());
            }
        };
    }

    static double pct(List<Long> xs, double p) {
        var s = new ArrayList<>(xs);
        Collections.sort(s);
        if (s.isEmpty()) return 0;
        return s.get(Math.min(s.size() - 1, (int) Math.ceil(p * s.size()) - 1)) / 1e6;
    }

    static ObjectNode stats(List<Long> nanos) {
        ObjectNode o = Report.JSON.createObjectNode();
        o.put("n", nanos.size());
        o.put("p50ms", Math.round(pct(nanos, 0.50) * 1000) / 1000.0);
        o.put("p95ms", Math.round(pct(nanos, 0.95) * 1000) / 1000.0);
        o.put("maxms", Math.round(pct(nanos, 1.0) * 1000) / 1000.0);
        return o;
    }

    @Test
    void measure() throws Exception {
        var cases = new ArrayList<ReplayDifferentialTest.Case>();
        cases.addAll(ReplayDifferentialTest.dapp());
        cases.addAll(ReplayDifferentialTest.m3());
        var contracts = new ArrayList<Prepare.Contract>();
        for (var c : cases) for (var pc : c.request().contracts()) if (!contracts.contains(pc)) contracts.add(pc);
        ObjectNode body = Report.JSON.createObjectNode();
        body.put("calls", cases.size());
        body.put("reps", REPS);
        body.put("command", "./gradlew :julc-state-uplc-spike:test --tests '*MeasurementsTest' -Dspike.reps=" + REPS);

        // 1. Whole prepares per mode, and per-evaluation times.
        ObjectNode prepares = body.putObject("preparePerCall");
        ObjectNode evaluations = body.putObject("perEvaluation");
        var callEvals = new TreeMap<String, Integer>();
        for (Spike.Mode mode : List.of(Spike.Mode.UPLC, Spike.Mode.JVM)) {
            Spike.install(contracts, pc -> recordingFn(mode, pc));
            for (var c : cases) Spike.prepare(c.request());                       // warm-up
            EVALS.clear();
            recording = true;
            List<Long> times = new ArrayList<>();
            for (int rep = 0; rep < REPS; rep++) {
                for (var c : cases) {
                    Latch.begin();
                    long t = System.nanoTime();
                    var r = Prepare.prepare(c.request());
                    times.add(System.nanoTime() - t);
                    var st = Latch.endQuietly();
                    if (!r.ok()) throw new IllegalStateException(mode + " " + c.name() + ": " + r.error());
                    if (rep == 0 && mode == Spike.Mode.UPLC) callEvals.put(c.name(), st.evaluations);
                }
            }
            recording = false;
            prepares.set(mode.name(), stats(times));
            var ev = new ArrayList<Long>();
            for (var e : EVALS) ev.add(e.nanos());
            evaluations.set(mode.name() + (mode == Spike.Mode.UPLC ? " (VM only)" : " (javac method only)"), stats(ev));
            if (mode == Spike.Mode.UPLC) units(body, cases);
        }

        // 4. Evaluations per call (map rounds).
        var hist = new TreeMap<Integer, Integer>();
        callEvals.values().forEach(n -> hist.merge(n, 1, Integer::sum));
        body.set("evaluationsPerCall", Report.JSON.valueToTree(hist));
        var most = callEvals.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
        body.put("mostEvaluations", most.getKey() + ": " + most.getValue());

        // 3. Sizes.
        sizes(body);

        // 5. Worst case: a budget-exhausting evaluation.
        worstCase(body);

        Report.write("measurements", body);
    }

    /** 2. Units per transition (from the UPLC pass's recorded evaluations) and the recorded transactions' validators. */
    static void units(ObjectNode body, List<ReplayDifferentialTest.Case> cases) throws Exception {
        var perMethod = new TreeMap<String, long[]>();
        var vectors = Report.JSON.createArrayNode();
        var seen = new java.util.HashSet<String>();
        synchronized (EVALS) {
            for (var e : EVALS) {
                if (e.out().kind() != Outcome.Kind.STEP || !e.out().isFinal()) continue;
                perMethod.merge(e.contract() + "." + e.method(), new long[]{e.cpu(), e.mem(), 1},
                        (a, b) -> new long[]{Math.max(a[0], b[0]), Math.max(a[1], b[1]), a[2] + b[2]});
                String key = e.program() + Arrays.toString(e.in());
                if (e.program() != null && seen.add(key)) {
                    var v = vectors.addObject();
                    v.put("program", e.program());
                    var a = v.putArray("args");
                    for (PlutusData d : e.in()) a.add(HexFormat.of().formatHex(PlutusDataCborEncoder.encode(d)));
                    v.put("expected", "ok:" + e.out().stepHex());
                }
            }
        }
        ObjectNode u = body.putObject("unitsPerTransition (max over the corpus)");
        perMethod.forEach((k, v) -> u.put(k, v[0] + " cpu / " + v[1] + " mem (" + v[2] + " evaluations)"));
        long maxCpu = perMethod.values().stream().mapToLong(v -> v[0]).max().orElse(0);
        body.put("maxTransitionCpu", maxCpu);
        // The validators' own units in the recorded transactions (sum of redeemer ExUnits per transaction).
        var txCpu = new ArrayList<Long>();
        for (var c : cases) {
            Transaction tx = Transaction.deserialize(HexFormat.of().parseHex(c.expected()));
            long cpu = 0;
            if (tx.getWitnessSet() != null && tx.getWitnessSet().getRedeemers() != null) {
                for (var r : tx.getWitnessSet().getRedeemers()) cpu += r.getExUnits().getSteps().longValue();
            }
            if (cpu > 0) txCpu.add(cpu);
        }
        Collections.sort(txCpu);
        body.put("validatorCpuPerTransaction p50", txCpu.get(txCpu.size() / 2));
        body.put("validatorCpuPerTransaction max", txCpu.get(txCpu.size() - 1));
        Files.createDirectories(Report.DIR);
        Files.writeString(Report.DIR.resolve("node-vectors.json"), Report.JSON.writeValueAsString(vectors));
        body.put("nodeVectors", vectors.size());
    }

    static final class Arrays {
        static String toString(PlutusData[] in) {
            var sb = new StringBuilder();
            for (PlutusData d : in) sb.append(HexFormat.of().formatHex(PlutusDataCborEncoder.encode(d))).append('|');
            return sb.toString();
        }
    }

    static void sizes(ObjectNode body) throws Exception {
        ArrayNode rows = body.putArray("sizes (bytes)");
        for (String s : ManifestTest.CONTRACTS) {
            Toolchain.Contract c = Spike.contract(ManifestTest.fqcn(s));
            long total = 0, max = 0;
            for (String h : c.transitions().values()) {
                long n = h.length() / 2;
                total += n;
                max = Math.max(max, n);
            }
            var row = rows.addObject();
            row.put("contract", s);
            row.put("validator", c.scriptSizeBytes());
            row.put("programs", c.transitions().size());
            row.put("programsTotal", total);
            row.put("largestProgram", max);
        }
        ObjectNode sets = body.putObject("programSets (total bytes)");
        for (String s : List.of("counter.Counter", "vault.Vault", "token.Token", "registry.Registry")) {
            Toolchain.Contract c = Spike.contract(ManifestTest.fqcn(s));
            var o = sets.putObject(s);
            for (Programs.Set set : Programs.Set.values()) {
                var compiled = Programs.compile(s.split("\\.")[0], c, set);
                o.put(set.name().toLowerCase(), compiled.programs().values().stream().mapToLong(h -> h.length() / 2).sum());
            }
        }
    }

    static void worstCase(ObjectNode body) throws Exception {
        Path root = Path.of("src/test/resources/probe");
        String path = "org/julclang/state/spike/probe/Probe.java";
        var r = Toolchain.build(Map.of(path, Files.readString(root.resolve(path))), Map.of());
        if (!r.ok()) throw new IllegalStateException(String.valueOf(r.diagnostics()));
        var runner = new UplcRunner(r.contracts().get(0).transitions());
        PlutusData state = PlutusData.constr(0, PlutusData.integer(0), PlutusData.bytes(new byte[28]));
        PlutusData none = PlutusData.constr(0);
        runner.outcome("spin", state, none, PlutusData.constr(0, PlutusData.integer(10)), none);   // warm-up
        List<Long> times = new ArrayList<>();
        Outcome last = null;
        for (int i = 0; i < 5; i++) {
            last = runner.outcome("spin", state, none, PlutusData.constr(0, PlutusData.integer(100_000_000)), none);
            times.add(last.nanos());
        }
        var o = body.putObject("worstCase: a budget-exhausting evaluation (Probe.spin, 1e8 recursion steps)");
        o.put("outcome", last.kind().name());
        o.put("units", last.cpu() + " cpu / " + last.mem() + " mem");
        o.set("time", stats(times));
    }
}
