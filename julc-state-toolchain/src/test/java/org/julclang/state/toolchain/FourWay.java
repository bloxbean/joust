package org.julclang.state.toolchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.julclang.core.types.JulcList;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payout;
import org.julclang.state.api.Payouts;
import org.julclang.state.api.RequireException;
import org.julclang.state.dsl.v2.Interpreter;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.Opt;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.julclang.state.dsl.v2.JscV2;
import org.julclang.state.testkit.StateHarness;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.julclang.state.toolchain.TransitionParityTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * J3's four legs for one {@code .jsc} 2 contract (ADR-0018 §9): the reference interpreter over the checked IR (the
 * independent oracle), the generated Java on the JVM (Gradle's build of examples/dsl), the UPLC transition program (the
 * npm toolchain's build, as the engine runs it) and the validator on-chain ({@link StateHarness}: the interpreter's
 * outcome must be accepted, a refusal rejected). Values cross between the legs as data, encoded as the runtime does.
 */
final class FourWay {

    /** blake2b from cardano-client-lib, independent of JuLC's crypto binding. */
    static final Interpreter.Hashes HASHES = (name, in) -> switch (name) {
        case "blake2b_224" -> Blake2bUtil.blake2bHash224(in);
        case "blake2b_256" -> Blake2bUtil.blake2bHash256(in);
        default -> Interpreter.JDK_HASHES.hash(name, in);
    };

    static final String PKG = "org.julclang.state.examples.dsl";

    final String name;
    final Class<?> cls;
    final Interpreter interp;
    final Map<String, String> transitions;
    final StateHarness<Object> harness;
    int calls, refusals, validatorRuns;
    StateHarness.Verdict lastVerdict;

    @SuppressWarnings({"unchecked", "rawtypes"})
    FourWay(String name, String jscSource, Map<String, String> transitions) throws Exception {
        this.name = name;
        this.cls = Class.forName(PKG + "." + name);
        this.interp = JscV2.interpreter(jscSource, name + ".jsc", HASHES);
        this.transitions = transitions;
        var spec = (org.julclang.state.runtime.ContractSpec) Class.forName(PKG + "." + name + "Client").getField("SPEC").get(null);
        this.harness = (StateHarness<Object>) StateHarness.of(spec);
    }

    // ------------------------------------------------------------------ values

    static Bytes b(byte[] x) {
        return Bytes.of(x);
    }

    static Rec rec(String type, Object... fields) {
        return new Rec(type, List.of(fields));
    }

    static Tx tx(byte[] caller, long received, long balance, BigInteger from, BigInteger to) {
        return new Tx(Bytes.of(caller), BigInteger.valueOf(received), BigInteger.valueOf(balance), from, to);
    }

    /** An interpreter value as the generated Java's value (records by their generated classes). */
    Object java(Object v) {
        try {
            return switch (v) {
                case BigInteger i -> i;
                case Boolean x -> x;
                case Bytes x -> x.bytes();
                case Opt o -> o.present() ? Optional.of(java(o.value())) : Optional.empty();
                case List<?> l -> {
                    if (l.isEmpty()) yield JulcList.empty();
                    var xs = new ArrayList<Object>();
                    for (Object x : l) xs.add(java(x));
                    yield JulcList.of(xs.toArray());
                }
                case Rec r -> {
                    Class<?> rc = Class.forName(cls.getName() + "$" + r.type());
                    var comps = rc.getRecordComponents();
                    var types = new Class<?>[comps.length];
                    var vals = new Object[comps.length];
                    for (int i = 0; i < comps.length; i++) {
                        types[i] = comps[i].getType();
                        vals[i] = java(r.fields().get(i));
                    }
                    yield rc.getDeclaredConstructor(types).newInstance(vals);
                }
                default -> throw new IllegalArgumentException("value " + v);
            };
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static Ctx ctx(Tx t) {
        return t == null ? null : new Ctx(t.caller().bytes(), t.received(), t.balance(), t.validFrom(), t.validTo());
    }

    static JulcList<Payout> payouts(List<Interpreter.Payment> ps) {
        if (ps.isEmpty()) return JulcList.empty();
        var xs = new ArrayList<Payout>();
        for (var p : ps) xs.add(new Payout(p.to().bytes(), p.lovelace()));
        return JulcList.of(xs.toArray(new Payout[0]));
    }

    // ------------------------------------------------------------------ the legs

    /** The interpreter's outcome, encoded as the runtime encodes a transition's result; "refused" when it fails. */
    String interpreted(String method, Rec state, List<Object> args, Tx t) {
        try {
            var o = interp.call(method, state, args, t);
            return switch (interp.kind(method)) {
                case "view" -> "ok:" + data(java(o.value())).serializeToHex();
                case "destroy" -> "ok:" + result(null, payouts(o.payouts()));
                default -> "ok:" + result(java(o.value()), payouts(o.payouts()));
            };
        } catch (Interpreter.Refused e) {
            return "refused";
        }
    }

    String jvm(String method, Rec state, List<Object> args, Tx t) throws Exception {
        Method m = null;
        for (Method x : cls.getDeclaredMethods()) if (x.getName().equals(method) && java.lang.reflect.Modifier.isStatic(x.getModifiers())) m = x;
        assertNotNull(m, method);
        var call = new ArrayList<Object>();
        if (state != null) call.add(java(state));
        for (Object a : args) call.add(java(a));
        if (interp.usesTx(method)) call.add(ctx(t));
        Object r;
        try {
            r = m.invoke(null, call.toArray());
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RequireException || e.getCause() instanceof ArithmeticException) return "refused";
            throw e;
        }
        if (interp.kind(method).equals("view")) return "ok:" + data(r).serializeToHex();
        if (r instanceof JulcList<?> l) return "ok:" + result(null, (JulcList<Payout>) l);
        if (r.getClass().getSimpleName().equals("Outcome")) {
            var c = r.getClass();
            return "ok:" + result(c.getMethod("state").invoke(r), (JulcList<Payout>) c.getMethod("payouts").invoke(r));
        }
        return "ok:" + result(r, null);
    }

    String transition(String method, Rec state, List<Object> args, Tx t) {
        String program = transitions.get(method);
        assertNotNull(program, name + " has no transition program " + method);
        var javaArgs = args.stream().map(this::java).toArray();
        return evaluate(program, state == null ? NONE : data(java(state)), NONE, args(javaArgs),
                interp.usesTx(method) ? data(ctx(t)) : NONE);
    }

    /**
     * The validator, given the interpreter's outcome: accepted when the call succeeded, rejected when it was refused.
     * For a refusal the transaction proposes the input state again (an action), everything to the caller (a destroy),
     * or {@code candidate} (an init; skipped when null).
     */
    Boolean validator(String method, Rec state, List<Object> args, Tx t, byte[] signer, Rec candidate) throws Exception {
        String kind = interp.kind(method);
        Interpreter.Outcome o;
        try {
            o = interp.call(method, state, args, t);
        } catch (Interpreter.Refused e) {
            o = null;
        }
        var redeemerArgs = new ArrayList<Object>();
        if (kind.equals("init")) redeemerArgs.add(harness.seed());
        for (Object a : args) redeemerArgs.add(java(a));
        if (interp.usesTx(method)) redeemerArgs.add(ctx(t));
        Object redeemer = construct(kind.equals("init") ? "CreateInstance" : Character.toUpperCase(method.charAt(0)) + method.substring(1),
                redeemerArgs);
        Ctx c = interp.usesTx(method) ? ctx(t) : null;
        StateHarness.Verdict v;
        switch (kind) {
            case "init" -> {
                Object first = o != null ? java(o.value()) : candidate == null ? null : java(candidate);
                if (first == null) return null;
                v = harness.evaluate(harness.create(redeemer, first, c));
            }
            case "action" -> {
                // An action's Ctx balance is what it may allocate: the instance's lovelace less the deposit (Ctx).
                var live = harness.live(java(state), t == null ? BigInteger.ZERO : t.balance());
                v = harness.evaluate(o != null ? harness.act(live, redeemer, java(o.value()), payouts(o.payouts()), signer, c)
                        : harness.act(live, redeemer, java(state), JulcList.empty(), signer, c));
            }
            case "destroy" -> {
                // A destroy's balance is everything the instance holds, deposit included.
                var live = harness.live(java(state), t.balance().subtract(BigInteger.valueOf(harness.minAda())));
                v = harness.evaluate(harness.destroy(live, redeemer, construct("BurnInstance", List.of()),
                        o != null ? payouts(o.payouts()) : Payouts.of(signer, live.lovelace()), signer, c));
            }
            default -> {
                return null;
            }
        }
        validatorRuns++;
        lastVerdict = v;
        return v.accepted();
    }

    private Object construct(String variant, List<Object> values) throws Exception {
        Class<?> rc = Class.forName(PKG + "." + name + "Validator$" + variant);
        var comps = rc.getRecordComponents();
        var types = new Class<?>[comps.length];
        for (int i = 0; i < comps.length; i++) types[i] = comps[i].getType();
        return rc.getDeclaredConstructor(types).newInstance(values.toArray());
    }

    /** All four legs (two for a view), asserted equal; returns the agreed outcome. */
    String check(String method, Rec state, List<Object> args, Tx t, byte[] signer) throws Exception {
        return check(method, state, args, t, signer, null);
    }

    String check(String method, Rec state, List<Object> args, Tx t, byte[] signer, Rec candidate) throws Exception {
        String what = name + "." + method + " " + args + " " + (t == null ? "" : t);
        String want = interpreted(method, state, args, t);
        assertEquals(want, jvm(method, state, args, t), "JVM vs interpreter: " + what);
        if (!interp.kind(method).equals("view")) {
            assertEquals(want, transition(method, state, args, t), "transition program vs interpreter: " + what);
            Boolean accepted = validator(method, state, args, t, signer, candidate);
            if (accepted != null) assertEquals(!want.equals("refused"), accepted, "validator vs interpreter: " + what + " " + lastVerdict);
        }
        calls++;
        if (want.equals("refused")) refusals++;
        return want;
    }
}
