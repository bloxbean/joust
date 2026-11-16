package org.julclang.state.toolchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.core.types.JulcAssocMap;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.ledger.Credential;
import org.julclang.ledger.GovernanceActionId;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.ScriptInfo;
import org.julclang.ledger.TxId;
import org.julclang.ledger.Vote;
import org.julclang.ledger.Voter;
import org.julclang.state.api.Approval;
import org.julclang.state.api.Approvals;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.RequireException;
import org.julclang.state.api.Send;
import org.julclang.state.dsl.v2.Interpreter;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.CredV;
import org.julclang.state.dsl.v2.Interpreter.DataV;
import org.julclang.state.dsl.v2.Interpreter.GovV;
import org.julclang.state.dsl.v2.Interpreter.LedgerMapV;
import org.julclang.state.dsl.v2.Interpreter.Opt;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.julclang.state.toolchain.TransitionParityTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3c (J3, semantics §2.10): a script purpose's call on every leg of {@link FourWay}: both reference interpreters
 * (the JavaScript one over the calls recorded in {@link FourWay#jsCalls}, at the suite's end), the generated Java handler
 * on the JVM, the transition program (a withdrawal without a Ctx: certify, vote and propose only decide on chain, so they
 * have none) and the validator, run for the purpose ({@link StateHarness#evaluateWithdraw}, {@code evaluateCertify},
 * {@code evaluateVotes}, {@code evaluatePropose}). As for the modifiers (§2.6), the JVM method and the transition program
 * compute the handler's body alone, and are compared with the interpreter's transition; the purpose's built-in rules (the
 * certificate filter, the voter, the allocation of the rewards) and the audience are the validator's, so a refusal by one
 * of them is the interpreters' and the validator's.
 */
final class PurposeLegs {
    final FourWay f;

    PurposeLegs(FourWay f) {
        this.f = f;
    }

    static final HexFormat HEX = HexFormat.of();

    /** The purpose's tag in an approval's action (1000 + tag: ADR-0010 r2; the processor's purposeAuth). */
    static final Map<String, Integer> TAG = Map.of("withdraw", 2, "certify", 3, "vote", 4);

    int purposeRefusals, purposeMutants;

    // ------------------------------------------------------------------ ledger values: the interpreters' as JuLC's

    /** An interpreter value as the JuLC ledger value of Java type {@code t} (a ledger record, a hash newtype, …). */
    static Object ledger(Object v, Type t) throws Exception {
        Class<?> raw = t instanceof ParameterizedType p ? (Class<?>) p.getRawType() : (Class<?>) t;
        if (raw == BigInteger.class) return v;
        if (raw == boolean.class || raw == Boolean.class) return v;
        if (raw == byte[].class) return ((Bytes) v).bytes();
        if (raw == PubKeyHash.class) return new PubKeyHash(((Bytes) v).bytes());
        if (raw == ScriptHash.class) return new ScriptHash(((Bytes) v).bytes());
        if (raw == TxId.class) return new TxId(((Bytes) v).bytes());
        if (raw == Credential.class) return FourWay.credential((CredV) v);
        if (raw == org.julclang.core.PlutusData.class) {
            return PlutusDataAdapter.fromClientLib(com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(HEX.parseHex(((DataV) v).cborHex())));
        }
        if (raw == Optional.class) {
            Opt o = (Opt) v;
            return o.present() ? Optional.of(ledger(o.value(), ((ParameterizedType) t).getActualTypeArguments()[0])) : Optional.empty();
        }
        if (raw == JulcList.class) {
            Type e = ((ParameterizedType) t).getActualTypeArguments()[0];
            var xs = new ArrayList<Object>();
            for (Object x : (List<?>) v) xs.add(ledger(x, e));
            return xs.isEmpty() ? JulcList.empty() : JulcList.of(xs.toArray());
        }
        if (raw == JulcMap.class) {
            Type[] kv = ((ParameterizedType) t).getActualTypeArguments();
            LedgerMapV m = (LedgerMapV) v;
            // insert prepends: from the last entry, so the map keeps the ledger's order (and its duplicates).
            JulcMap<Object, Object> out = JulcAssocMap.empty();
            for (int i = m.keys().size() - 1; i >= 0; i--) out = out.insert(ledger(m.keys().get(i), kv[0]), ledger(m.values().get(i), kv[1]));
            return out;
        }
        GovV g = (GovV) v;
        Class<?> c = Class.forName("org.julclang.ledger." + (g.ctor().equals(g.type()) ? g.type() : g.type() + "$" + g.ctor()));
        var comps = c.getRecordComponents();
        var types = new Class<?>[comps.length];
        var vals = new Object[comps.length];
        for (int i = 0; i < comps.length; i++) {
            types[i] = comps[i].getType();
            vals[i] = ledger(g.fields().get(i), comps[i].getGenericType());
        }
        return c.getDeclaredConstructor(types).newInstance(vals);
    }

    static GovV gov(String type, String ctor, Object... fields) {
        return new GovV(type, ctor, List.of(fields));
    }

    static GovV cert(String ctor, Object... fields) {
        return gov("TxCert", ctor, fields);
    }

    static GovV voter(String ctor, Object field) {
        return gov("Voter", ctor, field);
    }

    static GovV vote(String ctor) {
        return gov("Vote", ctor);
    }

    static GovV actionId(byte[] txId, long index) {
        return gov("GovernanceActionId", "GovernanceActionId", Bytes.of(txId), BigInteger.valueOf(index));
    }

    static GovV proposal(BigInteger deposit, CredV returnTo, GovV action) {
        return gov("ProposalProcedure", "ProposalProcedure", deposit, returnTo, action);
    }

    static GovV action(String ctor, Object... fields) {
        return gov("GovernanceAction", ctor, fields);
    }

    /** A JVM result's sends as data, hex (as the runtime encodes them, and the encode of an interpreter's outcome). */
    static String sendsHex(JulcList<Send> sends) {
        return data(sends).serializeToHex();
    }

    // ------------------------------------------------------------------ the call on every leg

    /** The keys that sign the next call and the scripts that approve it; the approvals' identity, for mutants. */
    List<byte[]> signers = List.of();
    List<byte[]> approvers = List.of();
    /** Applied to the validator's transaction before it is evaluated (a mutant), then cleared. */
    UnaryOperator<TxModel> mutate;
    /** The withdrawal the ledger makes (null: the amount the call names; a mutant names another). */
    BigInteger withdrawn;

    PurposeLegs as(List<byte[]> signers, List<byte[]> approvers) {
        this.signers = signers;
        this.approvers = approvers;
        return this;
    }

    /** The interpreters' transaction: the signers and approvers, with {@code t}'s caller and window when it has them. */
    Tx tx(Tx t) {
        if (t == null && signers.isEmpty() && approvers.isEmpty()) return null;
        Tx x = t != null ? t : new Tx(Bytes.of(signers.isEmpty() ? new byte[28] : signers.get(0)), BigInteger.ZERO, BigInteger.ZERO,
                BigInteger.ZERO, BigInteger.ZERO);
        var keys = new ArrayList<Bytes>(signers.stream().map(Bytes::of).toList());
        if (!keys.contains(x.caller())) keys.add(x.caller());
        return x.signedBy(keys).approvedBy(approvers.stream().map(Bytes::of).toList());
    }

    /**
     * A purpose's call on every leg; returns the agreed outcome ("refused", or "ok:" and the sends or the Bool). For a vote
     * handler, {@code args} are the voter and its votes ({@code List.of(voter, List.of(List.of(id, vote), …))}).
     */
    String check(String method, List<Object> args, Tx t) throws Exception {
        try {
            return checkOnce(method, args, t);
        } finally {
            mutate = null;
            withdrawn = null;
        }
    }

    private String checkOnce(String method, List<Object> args, Tx t) throws Exception {
        String kind = f.interp.kind(method);
        Tx ti = tx(t);
        String what = f.name + "." + method + " " + args;
        Interpreter.Outcome o = null;
        Interpreter.Refused refused = null;
        try {
            o = f.interp.call(method, null, args, ti);
        } catch (Interpreter.Refused e) {
            refused = e;
        }
        String want = refused != null ? "refused" : f.encode(method, o);
        f.lastRefusal = refused == null ? null : refused.refusal();
        // The handler's body alone (the transition, §2.6, §2.10): what the JVM method and the transition program compute.
        Interpreter.Outcome body = null;
        String bodyOut;
        try {
            body = f.interp.transition(method, null, args, ti);
            bodyOut = f.encode(method, body);
        } catch (Interpreter.Refused e) {
            bodyOut = "refused";
        }
        assertEquals(bodyOut, jvm(method, args, t, f.cls), "JVM vs interpreter: " + what);
        if (f.reference != null) {
            // The Java example's handler (examples/staking), on the same vector: a difference is recorded, each test states which.
            String ref = jvm(method, args, t, f.reference);
            if (!ref.equals(bodyOut)) f.javaDiffs.add(what + ": the Java example's handler gives " + ref + ", the port's " + bodyOut);
        }
        if (kind.equals("withdraw") && f.transitions.containsKey(method)) {
            assertEquals(bodyOut.equals("refused") ? "refused" : "ok:" + ConstrPlutusData.of(0, data(f.sends(body.sends()))).serializeToHex(),
                    program(method, args), "transition program vs interpreter: " + what);
        }
        f.jsCalls.add(JsLeg.call(method, null, args, ti, f.params, want, o, refused, what));
        var verdict = validator(kind, method, args, t, body);
        f.validatorRuns++;
        f.lastVerdict = verdict;
        assertEquals(!want.equals("refused"), verdict.accepted(), "validator vs interpreter: " + what + " " + verdict);
        f.calls++;
        if (want.equals("refused")) {
            f.refusals++;
            if (f.lastRefusal.startsWith("purpose:")) purposeRefusals++;
            if (f.lastRefusal.startsWith("modifier:")) f.modifierRefusals++;
        }
        return want;
    }

    /** As {@link #check}, refused with the class {@code cls}. */
    void refused(String cls, String method, List<Object> args, Tx t) throws Exception {
        assertEquals("refused", check(method, args, t), f.name + "." + method + " " + args);
        assertEquals(cls, f.lastRefusal, f.name + "." + method + " " + args);
    }

    /** The generated handler's JVM result, encoded as {@link FourWay#encode} encodes an interpreter's ("refused" on a Require). */
    @SuppressWarnings("unchecked")
    String jvm(String method, List<Object> args, Tx t, Class<?> cls) throws Exception {
        Method m = null;
        for (Method x : cls.getDeclaredMethods()) if (x.getName().equals(method) && java.lang.reflect.Modifier.isStatic(x.getModifiers())) m = x;
        assertNotNull(m, cls.getName() + "." + method);
        var ps = m.getGenericParameterTypes();
        Object params = f.java(f.params, cls);
        try {
            if (f.interp.kind(method).equals("vote")) {
                boolean all = true;
                for (Object e : (List<?>) args.get(1)) {
                    var pair = (List<?>) e;
                    all &= (Boolean) m.invoke(null, params, ledger(args.get(0), ps[1]), ledger(pair.get(0), ps[2]), ledger(pair.get(1), ps[3]));
                }
                return "ok:" + all;
            }
            var call = new ArrayList<Object>(List.of(params));
            for (int i = 0; i < args.size(); i++) {
                Object a = args.get(i);
                call.add(a instanceof GovV ? ledger(a, ps[i + 1]) : f.java(a, cls));
            }
            if (call.size() < ps.length) call.add(FourWay.ctx(t));   // a withdrawal's Ctx
            Object r = m.invoke(null, call.toArray());
            return r instanceof JulcList<?> l ? "ok:" + sendsHex((JulcList<Send>) l) : "ok:" + r;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RequireException || e.getCause() instanceof ArithmeticException) return "refused";
            throw e;
        }
    }

    /** A withdrawal's transition program (no Ctx): (target, shared, Constr 0 [params, amount, args…], ctx). */
    String program(String method, List<Object> args) {
        var in = new ArrayList<org.julclang.core.PlutusData>();
        in.add(f.raw(f.params));
        for (Object a : args) in.add(f.raw(a));
        return evaluateRaw(f.transitions.get(method), List.of(julc(NONE), julc(NONE), new org.julclang.core.PlutusData.ConstrData(0, in), julc(NONE)));
    }

    /** The approvals of the scripts that approve this call: each names this policy, the purpose's identity and its action. */
    void approve(TxModel tx, ScriptInfo info, int tag) {
        byte[] target = Blake2bUtil.blake2bHash224(org.julclang.core.cbor.PlutusDataCborEncoder.encode(info.toPlutusData()));
        var a = new Approval(f.harness.policy(), target, BigInteger.valueOf(1000 + tag));
        var data = StateHarness.data(new Approvals(JulcList.<Approval>empty().prepend(a)));
        for (byte[] h : approvers) tx.withdrawals.put(new Credential.ScriptCredential(new ScriptHash(h)), data);
    }

    @SuppressWarnings("unchecked")
    StateHarness.Verdict validator(String kind, String method, List<Object> args, Tx t, Interpreter.Outcome body) throws Exception {
        Credential own = f.harness.ownCredential();
        switch (kind) {
            case "withdraw" -> {
                // The body's sends (a body that fails proposes none): outputs claimed by RewardRef(own).
                List<Send> sends = new ArrayList<>();
                if (body != null) f.sends(body.sends()).forEach(sends::add);
                var tx = f.harness.withdrawTx(sends, signers.toArray(new byte[0][]));
                Ctx c = null;
                if (f.interp.usesTx(method)) {
                    c = new Ctx(t.caller().bytes(), BigInteger.ZERO, (BigInteger) args.get(0), t.validFrom(), t.validTo());
                    tx.range = StateHarness.window(t.validFrom(), t.validTo());
                    if (tx.signers.stream().noneMatch(k -> java.util.Arrays.equals(k, t.caller().bytes()))) tx.signers.add(t.caller().bytes());
                }
                approve(tx, new ScriptInfo.RewardingScript(own), TAG.get("withdraw"));
                var fields = new ArrayList<Object>(List.of(BigInteger.valueOf(74), args.get(0)));
                for (Object a : args.subList(1, args.size())) fields.add(f.java(a));
                if (c != null) fields.add(c);
                var rewards = (org.julclang.core.PlutusData.ConstrData) StateHarness.data(f.construct("Rewards", fields));
                // The arguments as an attacker may send them (raw).
                var rf = new ArrayList<>(rewards.fields());
                for (int i = 1; i < args.size(); i++) rf.set(1 + i, f.raw(args.get(i)));
                Object redeemer = new org.julclang.core.PlutusData.ConstrData(0, rf);
                if (mutate != null) tx = mutate.apply(tx);
                if (redeemerMutant != null) redeemer = redeemerMutant.apply((org.julclang.core.PlutusData) redeemer);
                redeemerMutant = null;
                return f.harness.evaluateWithdraw(tx, redeemer, withdrawn != null ? withdrawn : (BigInteger) args.get(0));
            }
            case "certify" -> {
                var tx = f.harness.withdrawTx(List.of(), signers.toArray(new byte[0][]));
                var cert = (org.julclang.ledger.TxCert) ledger(args.get(0), org.julclang.ledger.TxCert.class);
                approve(tx, new ScriptInfo.CertifyingScript(BigInteger.ZERO, approvedCert == null ? cert
                        : (org.julclang.ledger.TxCert) ledger(approvedCert, org.julclang.ledger.TxCert.class)), TAG.get("certify"));
                approvedCert = null;
                if (mutate != null) tx = mutate.apply(tx);
                return f.harness.evaluateCertify(tx, cert);
            }
            case "vote" -> {
                var tx = f.harness.withdrawTx(List.of(), signers.toArray(new byte[0][]));
                var voter = (Voter) ledger(args.get(0), Voter.class);
                approve(tx, new ScriptInfo.VotingScript(approvedVoter == null ? voter : (Voter) ledger(approvedVoter, Voter.class)), TAG.get("vote"));
                approvedVoter = null;
                var votes = new LinkedHashMap<Voter, Map<GovernanceActionId, Vote>>();
                var mine = new LinkedHashMap<GovernanceActionId, Vote>();
                for (Object e : (List<?>) args.get(1)) {
                    var pair = (List<?>) e;
                    mine.put((GovernanceActionId) ledger(pair.get(0), GovernanceActionId.class), (Vote) ledger(pair.get(1), Vote.class));
                }
                // Another voter's votes before this voter's, when a vector adds them (K44); this voter's entry, even empty.
                if (otherVoter != null) {
                    var theirs = new LinkedHashMap<GovernanceActionId, Vote>();
                    for (Object e : otherVotes) {
                        var pair = (List<?>) e;
                        theirs.put((GovernanceActionId) ledger(pair.get(0), GovernanceActionId.class), (Vote) ledger(pair.get(1), Vote.class));
                    }
                    votes.put((Voter) ledger(otherVoter, Voter.class), theirs);
                }
                if (!dropOwnVotes) votes.put(voter, mine);
                otherVoter = null;
                otherVotes = List.of();
                dropOwnVotes = false;
                if (mutate != null) tx = mutate.apply(tx);
                return f.harness.evaluateVotes(tx, voter, votes);
            }
            case "propose" -> {
                var tx = f.harness.withdrawTx(List.of(), signers.toArray(new byte[0][]));
                if (mutate != null) tx = mutate.apply(tx);
                return f.harness.evaluatePropose(tx, (org.julclang.ledger.ProposalProcedure) ledger(args.get(0), org.julclang.ledger.ProposalProcedure.class));
            }
            default -> throw new IllegalArgumentException(kind);
        }
    }

    /** A vector's variations of the validator's transaction (K44, K46): consumed by the next call. */
    Object approvedCert, approvedVoter, otherVoter;
    List<?> otherVotes = List.of();
    boolean dropOwnVotes;
    UnaryOperator<org.julclang.core.PlutusData> redeemerMutant;

    // ------------------------------------------------------------------ mutants (A47: one step away, the validator alone)

    /**
     * Mutants of an accepted withdrawal (A36, K41, K42): each changes one obligation of the transaction the interpreters
     * accept, and the validator must reject it: the sends one lovelace short or over, to another address, a send added to
     * a stranger, the claims dropped, claimed by another contract's reward reference or by a real input, the redeemer's
     * amount or marker changed, a signer or approval dropped, another credential's withdrawal beside it. Returns how many.
     */
    int withdrawMutants(String method, List<Object> args, Tx t) throws Exception {
        assertTrue(check(method, args, t).startsWith("ok:"), "the control is accepted");
        var muts = new LinkedHashMap<String, Runnable>();
        byte[] thief = StateHarness.fill(28, 0xEE);
        muts.put("a send one lovelace short", () -> mutate = tx -> sendsChanged(tx, BigInteger.ONE.negate(), null));
        // A send's output may hold more than the send (ADR-0007 D5: it covers the send; the extra is the builder's own), so
        // one lovelace over is no mutant.
        muts.put("a send to a thief", () -> mutate = tx -> sendsChanged(tx, BigInteger.ZERO, thief));
        muts.put("a thief paid beside the sends", () -> mutate = tx -> {
            var c = tx.copy();
            c.outputs.add(new org.julclang.ledger.TxOut(StateHarness.pkhAddress(thief), org.julclang.ledger.Value.lovelace(BigInteger.valueOf(1_000_000)),
                    new org.julclang.ledger.OutputDatum.NoOutputDatum(), Optional.empty()));
            c.sendOutputs.add(c.outputs.size() - 1);
            c.outputClaims = new ArrayList<>(c.outputClaims == null ? List.of() : c.outputClaims);
            c.outputClaims.add(new org.julclang.state.api.OutputClaim(BigInteger.valueOf(c.outputs.size() - 1), f.harness.rewardRef()));
            return c;
        });
        muts.put("the claims dropped", () -> mutate = tx -> {
            var c = tx.copy();
            if (c.outputClaims == null || c.outputClaims.isEmpty()) return null;
            c.outputClaims = List.of();
            return c;
        });
        muts.put("claimed by another contract's reward reference", () -> mutate = tx -> claimedBy(tx, new org.julclang.ledger.TxOutRef(
                new TxId(Blake2bUtil.blake2bHash256(StateHarness.fill(28, 0x0F))), BigInteger.valueOf(-1))));
        muts.put("claimed by a real input", () -> mutate = tx -> claimedBy(tx, tx.inputs.get(0).outRef()));
        muts.put("the redeemer's amount one lovelace over", () -> redeemerMutant = d -> field(d, 1,
                org.julclang.core.PlutusData.integer(((org.julclang.core.PlutusData.IntData) ((org.julclang.core.PlutusData.ConstrData) d).fields().get(1)).value().add(BigInteger.ONE))));
        muts.put("the marker of an Approvals list (0)", () -> redeemerMutant = d -> field(d, 0, org.julclang.core.PlutusData.integer(BigInteger.ZERO)));
        muts.put("the ledger withdraws one lovelace more", () -> withdrawn = ((BigInteger) args.get(0)).add(BigInteger.ONE));
        muts.put("a signer dropped", () -> mutate = tx -> {
            if (signers.isEmpty()) return null;
            var c = tx.copy();
            c.signers.remove(0);
            return c;
        });
        muts.put("an approval dropped", () -> mutate = tx -> {
            if (approvers.isEmpty()) return null;
            var c = tx.copy();
            c.withdrawals.clear();
            return c;
        });
        int n = 0;
        for (var e : muts.entrySet()) {
            e.getValue().run();
            var v = mutantVerdict(method, args, t);
            if (v == null) continue;
            assertFalse(v.accepted(), f.name + "." + method + ": the mutant \"" + e.getKey() + "\" is accepted\n" + v);
            n++;
        }
        f.mutantsRejected += n;
        purposeMutants += n;
        return n;
    }

    /** The validator's verdict on the control's transaction, mutated (null: the mutant does not apply). */
    StateHarness.Verdict mutantVerdict(String method, List<Object> args, Tx t) throws Exception {
        Interpreter.Outcome body = f.interp.transition(method, null, args, tx(t));
        UnaryOperator<TxModel> m = mutate;
        boolean[] skipped = {false};
        if (m != null) {
            mutate = tx -> {
                TxModel out = m.apply(tx);
                if (out == null) {
                    skipped[0] = true;
                    return tx;
                }
                return out;
            };
        }
        try {
            var v = validator(f.interp.kind(method), method, args, t, body);
            f.validatorRuns++;
            return skipped[0] ? null : v;
        } finally {
            mutate = null;
            withdrawn = null;
            redeemerMutant = null;
        }
    }

    private static org.julclang.core.PlutusData field(org.julclang.core.PlutusData d, int i, org.julclang.core.PlutusData v) {
        var c = (org.julclang.core.PlutusData.ConstrData) d;
        var fs = new ArrayList<>(c.fields());
        fs.set(i, v);
        return new org.julclang.core.PlutusData.ConstrData(c.constructorTag(), fs);
    }

    private static TxModel sendsChanged(TxModel tx, BigInteger delta, byte[] to) {
        if (tx.sendOutputs.isEmpty()) return null;
        var c = tx.copy();
        int i = c.sendOutputs.get(0);
        var o = c.outputs.get(i);
        c.outputs.set(i, new org.julclang.ledger.TxOut(to == null ? o.address() : StateHarness.pkhAddress(to),
                o.value().merge(org.julclang.ledger.Value.lovelace(delta)), o.datum(), o.referenceScript()));
        return c;
    }

    private static TxModel claimedBy(TxModel tx, org.julclang.ledger.TxOutRef by) {
        if (tx.outputClaims == null || tx.outputClaims.isEmpty()) return null;
        var c = tx.copy();
        var cs = new ArrayList<org.julclang.state.api.OutputClaim>();
        for (var x : c.outputClaims) cs.add(new org.julclang.state.api.OutputClaim(x.output(), by));
        c.outputClaims = cs;
        return c;
    }

    /** A credential of a key (KeyCred) or a script (ScriptCred), as the interpreters hold it. */
    static CredV keyCred(byte[] h) {
        return new CredV(false, Bytes.of(h));
    }

    static CredV scriptCred(byte[] h) {
        return new CredV(true, Bytes.of(h));
    }

    static List<Object> pair(Object id, Object vote) {
        return List.of(id, vote);
    }
}
