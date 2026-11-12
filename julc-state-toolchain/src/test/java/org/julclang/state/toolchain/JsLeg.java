package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.AbiCheck;
import org.julclang.state.dsl.v2.Interpreter;
import org.julclang.state.dsl.v2.Interpreter.BigMapV;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.MapV;
import org.julclang.state.dsl.v2.Interpreter.Opt;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * J3's fifth leg (ADR-0018 D15, M1b): the JavaScript reference interpreter ({@code js/jsc-interpreter}), run in Node
 * over the contract's IR export ({@code Name.ir.json}) and every call {@link FourWay#check} made. Calls and answers
 * cross as the tagged JSON of {@code docs/reference/jsc-2-ir.md}. An answer is turned back into a value, and that value
 * is encoded exactly as {@link FourWay} encodes the Java interpreter's outcome. It must then equal the outcome the other
 * four legs agreed on, byte for byte, or both must be refused.
 */
final class JsLeg {
    private JsLeg() {}

    static final Path SCRIPT = Path.of("../js/jsc-interpreter/bin/run-vectors.js");
    static final Path DIR = Path.of("build/jsc2-five-way");
    static final HexFormat HEX = HexFormat.of();

    /** One recorded call: its method, its JSON form, the agreed outcome, and a description for messages. */
    record Call(String method, String json, String want, String what) {}

    /** Runs every recorded call of {@code f} on the JavaScript interpreter and asserts each agrees; returns the count. */
    static int check(FourWay f) throws IOException, InterruptedException {
        return check(f.name, f.irJson, f.jsCalls, f::encode);
    }

    /**
     * Runs {@code calls} on the JavaScript interpreter over {@code irJson} and asserts that each answer, turned back into
     * an outcome and encoded by {@code encode} (the encoding the call's {@code want} was made with), equals {@code want};
     * returns the count.
     */
    static int check(String name, String irJson, List<Call> calls, BiFunction<String, Interpreter.Outcome, String> encode)
            throws IOException, InterruptedException {
        if (calls.isEmpty()) return 0;
        Files.createDirectories(DIR);
        Path ir = DIR.resolve(name + ".ir.json"), callsFile = DIR.resolve(name + ".calls.json"), answers = DIR.resolve(name + ".answers.json");
        Files.writeString(ir, irJson);
        var sb = new StringBuilder("[\n");
        for (int i = 0; i < calls.size(); i++) sb.append(calls.get(i).json()).append(i + 1 < calls.size() ? ",\n" : "\n");
        Files.writeString(callsFile, sb.append("]\n"));
        Files.deleteIfExists(answers);
        var pb = new ProcessBuilder("node", SCRIPT.toString(), ir.toString(), callsFile.toString(), answers.toString()).redirectErrorStream(true);
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new AssertionError("the JavaScript leg needs Node.js 22 on the PATH (node): " + e.getMessage(), e);
        }
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(proc.waitFor(5, TimeUnit.MINUTES), "node timed out");
        assertEquals(0, proc.exitValue(), "the JavaScript interpreter failed on " + name + ":\n" + out);
        System.out.print(out);
        @SuppressWarnings("unchecked")
        var got = (List<Object>) AbiCheck.Json.parse(Files.readString(answers));
        assertEquals(calls.size(), got.size(), "one answer per call");
        int mismatches = 0;
        var report = new StringBuilder();
        for (int i = 0; i < got.size(); i++) {
            Call c = calls.get(i);
            Interpreter.Outcome o = outcome(got.get(i));
            String js = o == null ? "refused" : encode.apply(c.method(), o);
            if (!js.equals(c.want())) {
                if (mismatches++ < 5) report.append("\n  ").append(c.what()).append("\n    agreed: ").append(c.want()).append("\n    JS:     ").append(js);
            }
        }
        assertEquals(0, mismatches, name + ": the JavaScript interpreter disagrees with the other legs on " + mismatches + " of "
                + got.size() + " calls:" + report);
        return got.size();
    }

    /** An answer as an interpreter outcome, or null when it is refused. */
    @SuppressWarnings("unchecked")
    private static Interpreter.Outcome outcome(Object answer) {
        var a = (Map<String, Object>) answer;
        if (a.containsKey("refused")) return null;
        var ok = (Map<String, Object>) a.get("ok");
        var payouts = new ArrayList<Interpreter.Payment>();
        for (Object p : (List<Object>) ok.get("payouts")) {
            var m = (Map<String, Object>) p;
            payouts.add(new Interpreter.Payment(Bytes.hex((String) m.get("to")), new BigInteger((String) m.get("lovelace"))));
        }
        return new Interpreter.Outcome(ok.get("value") == null ? null : value(ok.get("value")), List.copyOf(payouts));
    }

    // ------------------------------------------------------------------ values as tagged JSON

    @SuppressWarnings("unchecked")
    static Object value(Object j) {
        if (j instanceof Boolean b) return b;
        if (j instanceof List<?> l) return l.stream().map(JsLeg::value).toList();
        var m = (Map<String, Object>) j;
        if (m.containsKey("int")) return new BigInteger((String) m.get("int"));
        if (m.containsKey("bytes")) return Bytes.hex((String) m.get("bytes"));
        if (m.containsKey("none")) return Opt.NONE;
        if (m.containsKey("some")) return Opt.some(value(m.get("some")));
        if (m.containsKey("rec")) return new Rec((String) m.get("rec"), ((List<Object>) m.get("fields")).stream().map(JsLeg::value).toList());
        if (m.containsKey("map")) {
            var ks = new ArrayList<Bytes>();
            var vs = new ArrayList<Object>();
            for (Object e : (List<Object>) m.get("map")) {
                ks.add((Bytes) value(((List<Object>) e).get(0)));
                vs.add(value(((List<Object>) e).get(1)));
            }
            return new MapV(ks, vs);
        }
        if (m.containsKey("bigmap")) {
            var contents = new LinkedHashMap<Bytes, Object>();
            for (Object e : (List<Object>) m.get("bigmap")) contents.put((Bytes) value(((List<Object>) e).get(0)), value(((List<Object>) e).get(1)));
            return BigMapV.of(contents);
        }
        throw new IllegalArgumentException("not a vector value: " + j);
    }

    static String json(Object v) {
        return switch (v) {
            case null -> "null";
            case BigInteger i -> "{\"int\": \"" + i + "\"}";
            case Boolean b -> b.toString();
            case Bytes b -> "{\"bytes\": \"" + HEX.formatHex(b.bytes()) + "\"}";
            case Opt o -> o.present() ? "{\"some\": " + json(o.value()) + "}" : "{\"none\": true}";
            case List<?> l -> {
                var parts = new ArrayList<String>();
                for (Object x : l) parts.add(json(x));
                yield "[" + String.join(", ", parts) + "]";
            }
            case MapV m -> {
                var parts = new ArrayList<String>();
                for (int i = 0; i < m.keys().size(); i++) parts.add("[" + json(m.keys().get(i)) + ", " + json(m.values().get(i)) + "]");
                yield "{\"map\": [" + String.join(", ", parts) + "]}";
            }
            case BigMapV m -> {
                // Its contents now, in canonical key order.
                var sorted = new TreeMap<Bytes, Object>(MapV::compareKeys);
                sorted.putAll(m.now());
                var parts = new ArrayList<String>();
                sorted.forEach((k, x) -> parts.add("[" + json(k) + ", " + json(x) + "]"));
                yield "{\"bigmap\": [" + String.join(", ", parts) + "]}";
            }
            case Rec r -> {
                var parts = new ArrayList<String>();
                for (Object x : r.fields()) parts.add(json(x));
                yield "{\"rec\": \"" + r.type() + "\", \"fields\": [" + String.join(", ", parts) + "]}";
            }
            default -> throw new IllegalArgumentException("no vector form for " + v);
        };
    }

    static String call(String method, Rec state, List<Object> args, Tx t) {
        var parts = new ArrayList<String>();
        for (Object a : args) parts.add(json(a));
        String tx = t == null ? "null" : "{\"caller\": \"" + HEX.formatHex(t.caller().bytes()) + "\", \"received\": \"" + t.received()
                + "\", \"balance\": \"" + t.balance() + "\", \"validFrom\": \"" + t.validFrom() + "\", \"validTo\": \"" + t.validTo() + "\"}";
        return "{\"method\": \"" + method + "\", \"state\": " + json(state) + ", \"args\": [" + String.join(", ", parts) + "], \"tx\": " + tx + "}";
    }
}
