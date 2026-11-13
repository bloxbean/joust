package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.AbiCheck;
import org.julclang.state.dsl.v2.Interpreter;
import org.julclang.state.dsl.v2.Interpreter.BigMapV;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.MapV;
import org.julclang.state.dsl.v2.Interpreter.Opt;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.julclang.state.dsl.v2.Interpreter.AddrV;
import org.julclang.state.dsl.v2.Interpreter.CredV;
import org.julclang.state.dsl.v2.Interpreter.StakeHash;
import org.julclang.state.dsl.v2.Interpreter.StakePtr;
import org.julclang.state.dsl.v2.Interpreter.ValueV;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * cross as the tagged JSON of {@code docs/reference/jsc-2-ir.md}. Each answer is held to three things (review r1: F1,
 * C1, S1, S4):
 * <ol>
 *   <li>its <b>form</b>, the tagged JSON of its value and payouts, equals the Java interpreter's outcome in the same
 *       form. The form keeps a map's entry order and duplicates, which the runtime's encoding does not (cardano-client-
 *       lib writes maps canonically), and a refusal's class ({@code require:…}, {@code input:…}, {@code domain:…});</li>
 *   <li>every inline map in it is canonical (keys strictly ascending, §1.1), whatever the Java leg says;</li>
 *   <li>encoded as the runtime encodes a result, it equals the bytes the JVM, transition and validator legs agreed on.</li>
 * </ol>
 * A {@code limit} answer (the interpreter's stack ran out, semantics §2.4) is a failure of the leg, never an agreement.
 */
final class JsLeg {
    private JsLeg() {}

    static final Path SCRIPT = Path.of("../js/jsc-interpreter/bin/run-vectors.js");
    static final Path DIR = Path.of("build/jsc2-five-way");
    static final Duration TIMEOUT = Duration.ofMinutes(5);
    static final HexFormat HEX = HexFormat.of();

    /**
     * One recorded call: its method, its JSON form, the agreed outcome as the other legs encode it ({@code want}), the
     * Java interpreter's outcome in the order-keeping form ({@link #form}), and a description for messages.
     */
    record Call(String method, String json, String want, String form, String what) {}

    /** A call, recorded from the Java interpreter's outcome (a refusal when {@code refused} is not null). */
    static Call call(String method, Rec state, List<Object> args, Tx t, String want, Interpreter.Outcome o, Interpreter.Refused refused,
                     String what) {
        return call(method, state, args, t, null, want, o, refused, what);
    }

    /** The same, for a contract deployed with {@code params} (M2b; null: none). */
    static Call call(String method, Rec state, List<Object> args, Tx t, Rec params, String want, Interpreter.Outcome o,
                     Interpreter.Refused refused, String what) {
        return new Call(method, call(method, state, args, t, params), want, refused != null ? refusedForm(refused.refusal()) : form(o), what);
    }

    /** A call of the transition alone (semantics §2.6: no modifiers), recorded from the Java interpreter's {@code transition}. */
    static Call transitionCall(String method, Rec state, List<Object> args, Tx t, String want, Interpreter.Outcome o,
                               Interpreter.Refused refused, String what) {
        Call c = call(method, state, args, t, want, o, refused, what);
        String json = c.json().substring(0, c.json().length() - 1) + ", \"transition\": true}";
        return new Call(c.method(), json, c.want(), c.form(), c.what());
    }

    /** Runs every recorded call of {@code f} on the JavaScript interpreter and asserts each agrees; returns the count. */
    static int check(FourWay f) throws IOException, InterruptedException {
        return check(f.name, f.irJson, f.jsCalls, f::encode);
    }

    static int check(String name, String irJson, List<Call> calls, BiFunction<String, Interpreter.Outcome, String> encode)
            throws IOException, InterruptedException {
        return check(name, irJson, calls, encode, SCRIPT, TIMEOUT);
    }

    /**
     * Runs {@code calls} with {@code script} (the interpreter's runner, or a test's stand-in) over {@code irJson}, within
     * {@code timeout}, and asserts every answer agrees (above); returns the count.
     */
    static int check(String name, String irJson, List<Call> calls, BiFunction<String, Interpreter.Outcome, String> encode, Path script,
                     Duration timeout) throws IOException, InterruptedException {
        if (calls.isEmpty()) return 0;
        nodeVersion();
        Files.createDirectories(DIR);
        Path ir = DIR.resolve(name + ".ir.json"), callsFile = DIR.resolve(name + ".calls.json"), answers = DIR.resolve(name + ".answers.json");
        Path log = DIR.resolve(name + ".node.log");
        Files.writeString(ir, irJson);
        var sb = new StringBuilder("[\n");
        for (int i = 0; i < calls.size(); i++) sb.append(calls.get(i).json()).append(i + 1 < calls.size() ? ",\n" : "\n");
        Files.writeString(callsFile, sb.append("]\n"));
        Files.deleteIfExists(answers);
        String out = run(List.of("node", script.toString(), ir.toString(), callsFile.toString(), answers.toString()), log, timeout,
                "the JavaScript interpreter on " + name);
        System.out.print(out);
        @SuppressWarnings("unchecked")
        var got = (List<Object>) AbiCheck.Json.parse(Files.readString(answers));
        assertEquals(calls.size(), got.size(), "one answer per call");
        int mismatches = 0;
        var report = new StringBuilder();
        for (int i = 0; i < got.size(); i++) {
            Call c = calls.get(i);
            String problem = mismatch(c, got.get(i), encode);
            if (problem != null && mismatches++ < 5) report.append("\n  ").append(c.what()).append("\n    ").append(problem);
        }
        assertEquals(0, mismatches, name + ": the JavaScript interpreter disagrees with the other legs on " + mismatches + " of "
                + got.size() + " calls:" + report);
        return got.size();
    }

    /**
     * Runs a command with its output in {@code log}, so nothing blocks on a pipe, and waits at most {@code timeout} (review
     * C2): on expiry the process is destroyed and the leg fails. Returns the output.
     */
    static String run(List<String> command, Path log, Duration timeout, String what) throws IOException, InterruptedException {
        Process proc;
        try {
            proc = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        } catch (IOException e) {
            throw new AssertionError("the JavaScript leg needs Node.js 22 or later on the PATH (node): " + e.getMessage(), e);
        }
        try {
            if (!proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                fail(what + " did not finish within " + timeout + "; it was stopped. Output so far:\n" + Files.readString(log));
            }
            String out = Files.readString(log);
            assertEquals(0, proc.exitValue(), what + " failed:\n" + out);
            return out;
        } finally {
            if (proc.isAlive()) {
                proc.destroyForcibly();
                proc.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static int nodeMajor;

    /** Node 22 or later (the interpreter's engines field; review F4), checked once. */
    static synchronized void nodeVersion() throws IOException, InterruptedException {
        if (nodeMajor == 0) {
            Path log = Files.createTempFile("node-version", ".log");
            String v = run(List.of("node", "--version"), log, Duration.ofSeconds(30), "node --version").strip();
            Files.deleteIfExists(log);
            nodeMajor = Integer.parseInt(v.replaceFirst("^v(\\d+)\\..*", "$1"));
        }
        assertTrue(nodeMajor >= 22, "the JavaScript leg needs Node.js 22 or later; found major version " + nodeMajor);
    }

    /** Why an answer disagrees with the recorded call, or null when it agrees. */
    @SuppressWarnings("unchecked")
    static String mismatch(Call c, Object answer, BiFunction<String, Interpreter.Outcome, String> encode) {
        var a = (Map<String, Object>) answer;
        if (a.containsKey("limit")) return "the JavaScript interpreter gave no answer (limit: " + a.get("limit") + ")";
        if (a.containsKey("refused")) {
            String form = refusedForm((String) a.get("class"));
            return form.equals(c.form()) && c.want().equals("refused") ? null
                    : "agreed: " + c.want() + " (" + c.form() + ")\n    JS:     " + form + " (" + a.get("refused") + ")";
        }
        Interpreter.Outcome o;
        try {
            o = outcome(a);
        } catch (IllegalArgumentException e) {
            return "the JavaScript answer is not well formed: " + e.getMessage();
        }
        String form = form(o);
        if (!form.equals(c.form())) return "Java interpreter: " + c.form() + "\n    JS:               " + form;
        String bytes = encode.apply(c.method(), o);
        if (!bytes.equals(c.want())) return "agreed: " + c.want() + "\n    JS:     " + bytes;
        return null;
    }

    /** Every inline map in an interpreter's value is canonical (F1): the Java leg is held to it as the JavaScript one is. */
    static void canonical(Object v, String what) {
        if (v == null) return;
        try {
            value(AbiCheck.Json.parse(json(v)), true);
        } catch (IllegalArgumentException e) {
            fail("the Java interpreter's outcome holds " + e.getMessage() + ": " + what);
        }
    }

    static String refusedForm(String refusalClass) {
        return "refused " + refusalClass;
    }

    /** An outcome in the order-keeping tagged form: its value (null for a destroy) and payouts; M2's effects when there are any. */
    static String form(Interpreter.Outcome o) {
        var ps = new ArrayList<String>();
        for (var p : o.payouts()) ps.add("[" + json(p.to()) + ", " + json(p.lovelace()) + "]");
        String out = "ok " + json(o.value()) + " pays [" + String.join(", ", ps) + "]";
        if (!o.sends().isEmpty()) {
            var ss = new ArrayList<String>();
            for (var x : o.sends()) ss.add("[" + json(x.to()) + ", " + json(x.value()) + "]");
            out += " sends [" + String.join(", ", ss) + "]";
        }
        if (!o.mint().entries().isEmpty()) out += " mints " + json(o.mint());
        if (!o.accepted().entries().isEmpty()) out += " accepts " + json(o.accepted());
        if (!o.events().isEmpty()) out += " emits " + json(o.events());
        return out;
    }

    /** An answer as an interpreter outcome; every inline map in it must be canonical (F1). */
    @SuppressWarnings("unchecked")
    static Interpreter.Outcome outcome(Map<String, Object> a) {
        var ok = (Map<String, Object>) a.get("ok");
        var payouts = new ArrayList<Interpreter.Payment>();
        for (Object p : (List<Object>) ok.get("payouts")) {
            var m = (Map<String, Object>) p;
            payouts.add(new Interpreter.Payment(Bytes.hex((String) m.get("to")), new BigInteger((String) m.get("lovelace"))));
        }
        var sends = new ArrayList<Interpreter.SendV>();
        if (ok.get("sends") instanceof List<?> l) {
            for (Object x : l) {
                var m = (Map<String, Object>) x;
                sends.add(new Interpreter.SendV((AddrV) value(m.get("to"), true), (ValueV) value(m.get("value"), true)));
            }
        }
        ValueV mint = ok.containsKey("mint") ? (ValueV) value(ok.get("mint"), true) : ValueV.NONE;
        ValueV accepted = ok.containsKey("accepted") ? (ValueV) value(ok.get("accepted"), true) : ValueV.NONE;
        var events = new ArrayList<Object>();
        if (ok.get("events") instanceof List<?> l) for (Object x : l) events.add(value(x, true));
        return new Interpreter.Outcome(ok.get("value") == null ? null : value(ok.get("value"), true), List.copyOf(payouts), List.copyOf(sends),
                mint, accepted, List.copyOf(events));
    }

    // ------------------------------------------------------------------ values as tagged JSON

    static Object value(Object j) {
        return value(j, false);
    }

    /** A tagged JSON value; with {@code canonical}, an inline map whose keys are not strictly ascending is refused. */
    @SuppressWarnings("unchecked")
    static Object value(Object j, boolean canonical) {
        if (j instanceof Boolean b) return b;
        if (j instanceof List<?> l) return l.stream().map(x -> value(x, canonical)).toList();
        var m = (Map<String, Object>) j;
        if (m.containsKey("int")) return new BigInteger((String) m.get("int"));
        if (m.containsKey("bytes")) return Bytes.hex((String) m.get("bytes"));
        if (m.containsKey("none")) return Opt.NONE;
        if (m.containsKey("some")) return Opt.some(value(m.get("some"), canonical));
        if (m.containsKey("rec")) return new Rec((String) m.get("rec"), ((List<Object>) m.get("fields")).stream().map(x -> value(x, canonical)).toList());
        if (m.containsKey("map")) {
            var ks = new ArrayList<Bytes>();
            var vs = new ArrayList<Object>();
            for (Object e : (List<Object>) m.get("map")) {
                Bytes k = (Bytes) value(((List<Object>) e).get(0), canonical);
                if (canonical && !ks.isEmpty() && MapV.compareKeys(ks.get(ks.size() - 1), k) >= 0) {
                    throw new IllegalArgumentException("a map whose keys are not strictly ascending in canonical order: " + json(ks.getLast())
                            + " then " + json(k));
                }
                ks.add(k);
                vs.add(value(((List<Object>) e).get(1), canonical));
            }
            return new MapV(ks, vs);
        }
        // M2's values, credentials and addresses (jsc-2-ir.md, "Values in vectors").
        if (m.containsKey("value")) {
            var es = new TreeMap<String, TreeMap<String, BigInteger>>();
            String prev = null;
            for (Object e : (List<Object>) m.get("value")) {
                var x = (List<Object>) e;
                String key = x.get(0) + ":" + x.get(1);
                if (canonical && prev != null && (prev.compareTo(key) >= 0 || new BigInteger((String) x.get(2)).signum() == 0)) {
                    throw new IllegalArgumentException("a value whose entries are not strictly ascending, or hold a zero quantity: " + j);
                }
                prev = key;
                es.computeIfAbsent((String) x.get(0), k -> new TreeMap<>()).put((String) x.get(1), new BigInteger((String) x.get(2)));
            }
            return new ValueV(es);
        }
        if (m.containsKey("keyCred")) return new CredV(false, Bytes.hex((String) m.get("keyCred")));
        if (m.containsKey("scriptCred")) return new CredV(true, Bytes.hex((String) m.get("scriptCred")));
        if (m.containsKey("address")) {
            var a = (List<Object>) m.get("address");
            var st = (Map<String, Object>) a.get(1);
            Opt stake = st == null ? Opt.NONE : st.containsKey("stakeHash") ? Opt.some(new StakeHash((CredV) value(st.get("stakeHash"), canonical)))
                    : Opt.some(new StakePtr(new BigInteger((String) ((List<Object>) st.get("stakePtr")).get(0)),
                    new BigInteger((String) ((List<Object>) st.get("stakePtr")).get(1)), new BigInteger((String) ((List<Object>) st.get("stakePtr")).get(2))));
            return new AddrV((CredV) value(a.get(0), canonical), stake);
        }
        if (m.containsKey("bigmap")) {
            var contents = new LinkedHashMap<Bytes, Object>();
            for (Object e : (List<Object>) m.get("bigmap")) {
                contents.put((Bytes) value(((List<Object>) e).get(0), canonical), value(((List<Object>) e).get(1), canonical));
            }
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
                // In its own order, duplicates kept.
                var parts = new ArrayList<String>();
                for (int i = 0; i < m.keys().size(); i++) parts.add("[" + json(m.keys().get(i)) + ", " + json(m.values().get(i)) + "]");
                yield "{\"map\": [" + String.join(", ", parts) + "]}";
            }
            case BigMapV m -> {
                // Its contents now, in canonical key order (a BigMap is a set of entries, stored as a Merkle root).
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
            case ValueV x -> {
                var parts = new ArrayList<String>();
                x.entries().forEach((p, ts) -> ts.forEach((n, q) -> parts.add("[\"" + p + "\", \"" + n + "\", \"" + q + "\"]")));
                yield "{\"value\": [" + String.join(", ", parts) + "]}";
            }
            case CredV x -> "{\"" + (x.script() ? "scriptCred" : "keyCred") + "\": \"" + HEX.formatHex(x.hash().bytes()) + "\"}";
            case AddrV x -> {
                String stake = !x.stake().present() ? "null" : x.stake().value() instanceof StakeHash h ? "{\"stakeHash\": " + json(h.credential()) + "}"
                        : "{\"stakePtr\": [\"" + ((StakePtr) x.stake().value()).slot() + "\", \"" + ((StakePtr) x.stake().value()).txIndex() + "\", \""
                        + ((StakePtr) x.stake().value()).certIndex() + "\"]}";
                yield "{\"address\": [" + json(x.payment()) + ", " + stake + "]}";
            }
            default -> throw new IllegalArgumentException("no vector form for " + v);
        };
    }

    static String call(String method, Rec state, List<Object> args, Tx t) {
        return call(method, state, args, t, null);
    }

    /** A call's JSON; M2b: the params it is deployed with (top level), the scripts approving it (in the transaction). */
    static String call(String method, Rec state, List<Object> args, Tx t, Rec params) {
        var parts = new ArrayList<String>();
        for (Object a : args) parts.add(json(a));
        String tx = t == null ? "null" : "{\"caller\": \"" + HEX.formatHex(t.caller().bytes()) + "\", \"received\": \"" + t.received()
                + "\", \"balance\": \"" + t.balance() + "\", \"validFrom\": \"" + t.validFrom() + "\", \"validTo\": \"" + t.validTo()
                + "\", \"signers\": [" + String.join(", ", t.signers().stream().map(k -> "\"" + HEX.formatHex(k.bytes()) + "\"").toList()) + "]"
                // M2: the native assets the call sees.
                + (t.funds() == null ? "" : ", \"funds\": {\"received\": " + json(t.funds().received()) + ", \"held\": " + json(t.funds().held())
                        + ", \"policy\": \"" + HEX.formatHex(t.funds().policy().bytes()) + "\", \"self\": \"" + HEX.formatHex(t.funds().self().bytes()) + "\"}")
                + (t.approvers().isEmpty() ? "" : ", \"approvers\": [" + String.join(", ", t.approvers().stream().map(k -> "\"" + HEX.formatHex(k.bytes()) + "\"").toList()) + "]")
                + "}";
        return "{\"method\": \"" + method + "\", \"state\": " + json(state) + ", \"args\": [" + String.join(", ", parts) + "], \"tx\": " + tx
                + (params == null ? "" : ", \"params\": " + json(params)) + "}";
    }
}
