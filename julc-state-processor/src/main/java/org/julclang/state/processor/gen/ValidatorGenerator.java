package org.julclang.state.processor.gen;

import org.julclang.state.processor.ir.ContractIR;
import org.julclang.state.processor.ir.ParamIR;
import org.julclang.state.processor.ir.TransitionIR;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Generates {@code <Name>Validator}: a JuLC {@code @MultiValidator} (MINT + SPEND) that enforces the invariants of
 * ADR-0001 §4 around the user's own methods.
 * <p>
 * The output is plain Java text in JuLC's subset. Rules the template keeps, because JuLC enforces them: static calls
 * through imported simple class names; immutable locals except loop accumulators; every {@code if} branch that
 * fails returns after {@code Builtins.error()}.
 */
public final class ValidatorGenerator {

    private final ContractIR c;
    private final SourceWriter w = new SourceWriter();
    private final boolean traces;

    private ValidatorGenerator(ContractIR c, boolean traces) {
        this.c = c;
        this.traces = traces;
    }

    public static String generate(ContractIR contract) {
        return generate(contract, false);
    }

    /** {@code traces}: each failing check traces its name (processor option {@code -Ajoust.traces=true}; debugging only). */
    public static String generate(ContractIR contract, boolean traces) {
        return new ValidatorGenerator(contract, traces).render();
    }

    /** The conjunction of {@code checks}; with traces on, each named so a failure says which one. */
    private String conj(List<String> checks, String where) {
        if (!traces) return String.join(" && ", checks);
        var out = new ArrayList<String>();
        for (String k : checks) out.add("check(" + k + ", \"" + where + ": " + k + "\")");
        return String.join(" && ", out);
    }

    private String render() {
        String X = c.name();
        String S = c.stateType();

        w.line("package " + c.packageName() + ";").blank();
        var imports = new TreeSet<>(List.of(
                "java.math.BigInteger", "java.util.Optional",
                "org.julclang.core.PlutusData", "org.julclang.core.types.AssetEntry", "org.julclang.core.types.JulcList",
                "org.julclang.ledger.Address", "org.julclang.ledger.Credential", "org.julclang.ledger.Interval",
                "org.julclang.ledger.IntervalBoundType", "org.julclang.ledger.OutputDatum",
                "org.julclang.ledger.ScriptContext", "org.julclang.ledger.ScriptInfo", "org.julclang.ledger.TxInInfo",
                "org.julclang.ledger.TxInfo", "org.julclang.ledger.TxOutRef", "org.julclang.ledger.TxOut", "org.julclang.ledger.Value",
                "org.julclang.stdlib.Builtins", "org.julclang.stdlib.annotation.Entrypoint",
                "org.julclang.stdlib.annotation.MultiValidator", "org.julclang.stdlib.annotation.Purpose",
                "org.julclang.stdlib.lib.AddressLib", "org.julclang.stdlib.lib.ByteStringLib",
                "org.julclang.stdlib.lib.ContextsLib",
                "org.julclang.stdlib.lib.OutputLib", "org.julclang.stdlib.lib.ValuesLib",
                "org.julclang.state.api.Ctx", "org.julclang.state.api.Payout", "org.julclang.state.api.Payouts"));
        if (c.hasOwned() || c.hasBatched()) imports.add("org.julclang.stdlib.lib.CryptoLib");
        if (c.hasBatched()) imports.add("org.julclang.ledger.ScriptPurpose");
        if (c.assetAware()) {
            imports.addAll(List.of("org.julclang.core.types.JulcValue", "org.julclang.state.api.Funds",
                    "org.julclang.ledger.ScriptPurpose", "org.julclang.ledger.PolicyId"));
        }
        if (c.hasParams()) imports.add("org.julclang.stdlib.annotation.Param");
        if (anyReads()) imports.add("org.julclang.state.api.ForeignInstance");
        if (c.stakeSelf()) imports.addAll(List.of("org.julclang.ledger.StakingCredential", "org.julclang.ledger.ScriptHash"));
        if (!c.purposes().isEmpty()) {
            imports.addAll(List.of("org.julclang.stdlib.lib.CryptoLib", "org.julclang.ledger.TxId", "org.julclang.ledger.TxCert",
                    "org.julclang.ledger.Voter", "org.julclang.ledger.Vote", "org.julclang.ledger.GovernanceActionId",
                    "org.julclang.core.types.JulcMap"));
        }
        if (anyAuthorizedBy()) {
            imports.addAll(List.of("org.julclang.ledger.ScriptPurpose", "org.julclang.state.api.Approval",
                    "org.julclang.state.api.Approvals"));
        }
        if (c.hasSends()) {
            imports.addAll(List.of("org.julclang.state.api.Send", "org.julclang.state.api.OutputClaim",
                    "org.julclang.state.api.OutputClaims", "org.julclang.ledger.ScriptHash"));
        }
        if (c.hasMaps()) {
            imports.addAll(List.of("org.julclang.state.api.MapEntry", "org.julclang.state.api.MerkleMap",
                    "org.julclang.state.api.mpf.MapClaim", "org.julclang.state.api.mpf.MapClaims",
                    "org.julclang.state.api.mpf.MapWitnesses", "org.julclang.state.api.mpf.Mpf",
                    "org.julclang.ledger.ScriptPurpose", "org.julclang.ledger.ScriptHash"));
        }
        imports.addAll(c.imports());
        for (String i : imports) w.line("import " + i + ";");
        w.blank();

        w.line("/**");
        w.line(" * GENERATED by the JOUST processor from {@link " + X + "}. Do not edit.");
        w.line(" * <p>");
        w.line(" * One script per contract type: policy id == script hash == address credential. An instance is identified");
        w.line(" * by its thread-token name, {@code blake2b_256(seedTxId ++ I2OSP(seedIndex, 2))}. MINT creates (CreateInstance) or");
        w.line(" * burns (BurnInstance) instances; SPEND applies the contract's actions and destroys (ADR-0001 §4).");
        w.line(" */");
        w.line("@MultiValidator");
        w.open("public class " + c.validatorName() + " {").blank();
        w.line("static final BigInteger MIN_ADA = BigInteger.valueOf(" + c.minAda() + "L);");
        w.line("static final BigInteger MAX_DATUM_BYTES = BigInteger.valueOf(" + c.maxDatumBytes() + "L);");
        w.line("static final BigInteger MAX_VALIDITY_MS = BigInteger.valueOf(" + c.maxValidityMs() + "L);");
        if (c.hasParams()) {
            w.line("/** The deploy-time parameters (" + c.paramsType() + "), applied to the script (ADR-0009 D1). */");
            w.line("@Param static PlutusData PARAMS;");
        }
        if (c.hasBatched()) {
            w.line("/** Lovelace an intent carries beyond what it pays in: its refund, at least a min-UTxO output (ADR-0004 D2). */");
            w.line("static final BigInteger INTENT_DEPOSIT = BigInteger.valueOf(" + INTENT_DEPOSIT + "L);");
        }
        if (c.hasMaps()) {
            w.line("/** Script hash of the JOUST map verifier (ADR-0002 D9), which checks every MerkleMap claim. */");
            w.line("static final byte[] MAP_VERIFIER = " + MapVerifierInfo.hashLiteral() + ";");
        }
        if (c.hasSends()) {
            w.line("/** Script hash of the JOUST output verifier (ADR-0007 D5), which carries the output-claim registry. */");
            w.line("static final byte[] OUTPUT_VERIFIER = " + MapVerifierInfo.hashLiteral("OutputVerifier") + ";");
        }
        w.blank();

        // ---- redeemers
        w.line("// ---- redeemers ---------------------------------------------------------------------------");
        var lifecycle = new ArrayList<String>(List.of("CreateInstance", "BurnInstance"));
        c.ownedInits().forEach(t -> lifecycle.add(t.variant()));
        if (c.hasBatched()) lifecycle.add("CreateIntent");
        if (c.hasMints()) lifecycle.add("ByAction");
        if (c.migrate() != null) lifecycle.add("Adopt");
        w.line("public sealed interface Lifecycle permits " + String.join(", ", lifecycle) + " {}");
        w.line("public record CreateInstance(" + join(prepend(List.of("TxOutRef seed"),
                fields(c.init()))) + ") implements Lifecycle {}");
        w.line("public record BurnInstance() implements Lifecycle {}");
        for (var t : c.ownedInits()) {
            w.line("public record " + t.variant() + "(" + join(prepend(List.of("TxOutRef seed", "byte[] instance"), fields(t)))
                    + ") implements Lifecycle {}");
        }
        var spend = new ArrayList<TransitionIR>(c.spendTransitions());
        spend.addAll(c.ownedSpendTransitions());
        var callNames = new ArrayList<String>(spend.stream().map(TransitionIR::variant).toList());
        // Constructor tags of the batch redeemers: their index in the permits list (ADR-0004 D3).
        long batchTag = callNames.size();
        if (c.hasBatched()) callNames.addAll(List.of("Batch", "InBatch", "CancelIntent"));
        if (!callNames.isEmpty()) {
            w.line("public sealed interface Calls permits " + String.join(", ", callNames) + " {}");
            for (var t : spend) {
                w.line("public record " + t.variant() + "(" + join(fields(t)) + ") implements Calls {}");
            }
        }
        if (c.hasBatched()) {
            w.line("/** Applies every intent of this instance in the transaction, in ledger input order (ADR-0004 D3). */");
            w.line("public record Batch(BigInteger validFrom, BigInteger validTo) implements Calls {}");
            w.line("/** An intent consumed by its instance's Batch. */");
            w.line("public record InBatch() implements Calls {}");
            w.line("/** An intent withdrawn by its owner. */");
            w.line("public record CancelIntent() implements Calls {}");
            var batched = c.batched();
            w.line("/** The batched action an intent asks for, with its arguments (Ctx comes from the intent and the batch). */");
            w.line("public sealed interface IntentCall permits " + String.join(", ",
                    batched.stream().map(TransitionIR::intentVariant).toList()) + " {}");
            for (var t : batched) {
                var fs = new ArrayList<String>();
                for (ParamIR p : t.params()) fs.add(p.type() + " " + p.name());
                w.line("public record " + t.intentVariant() + "(" + join(fs) + ") implements IntentCall {}");
            }
            w.line("/** An intent's datum (ADR-0004 D2). */");
            w.line("public record Intent(byte[] instance, byte[] owner, Address refund, BigInteger deadline, BigInteger received,"
                    + " IntentCall call) {}");
            w.line("/** The datum of an intent's refund output: names the intent, distinct from payout tags. */");
            w.line("public record IntentRefund(TxOutRef intent) {}");
            w.line("/** One step of a batch: the next state and the action's payouts. */");
            w.line("public record Step(" + c.stateType() + " state, JulcList<Payout> payouts) {}");
            w.line("public record CreateIntent(TxOutRef seed, Intent intent) implements Lifecycle {}");
        }
        if (c.migrate() != null) {
            w.line("/** Adopts an instance of the previous version: the seed is its state UTxO, whose token burns (ADR-0011 D4). */");
            w.line("public record Adopt(" + join(prepend(List.of("TxOutRef seed"), fields(c.migrate()))) + ") implements Lifecycle {}");
        }
        if (c.hasMints()) {
            w.line("/** Mints or burns user tokens for the transition spending {@code by}, which checks them (ADR-0007 D4). */");
            w.line("public record ByAction(TxOutRef by) implements Lifecycle {}");
        }
        if (c.hasSends()) {
            w.line("/** The shape of a JOUST refund tag (IntentRefund): a send may never carry one (ADR-0007 I30). */");
            w.line("public record RefTag(TxOutRef ref) {}");
        }
        purposeRedeemers();
        if (c.hasOwned()) {
            w.line("/** An owned object's datum: its instance and its value (ADR-0003 D2). */");
            w.line("public record Holding(byte[] instance, " + c.ownedType() + " value) {}");
        }
        w.blank();

        // ---- MINT
        w.line("// ---- MINT: create / burn instances ------------------------------------------------------");
        w.line("@Entrypoint(purpose = Purpose.MINT)");
        w.open("public static boolean mint(Lifecycle redeemer, ScriptContext ctx) {");
        w.line("TxInfo tx = ctx.txInfo();");
        w.line("ScriptInfo.MintingScript mi = (ScriptInfo.MintingScript) ctx.scriptInfo();");
        w.line("byte[] policy = (byte[]) (Object) mi.policyId();");
        w.open("return switch (redeemer) {");
        w.line("case CreateInstance cr -> create(cr, tx, policy);");
        w.line("case BurnInstance b -> onlyBurns(tx.mint(), policy);");
        for (var t : c.ownedInits()) {
            w.line("case " + t.variant() + " o -> create" + t.variant() + "(o, tx, policy);");
        }
        if (c.hasBatched()) w.line("case CreateIntent ci -> createIntent(ci, tx, policy);");
        if (c.hasMints()) w.line("case ByAction b -> byAction(b, tx, policy);");
        if (c.migrate() != null) w.line("case Adopt a -> adopt(a, tx, policy);");
        w.close("};");
        w.close("}").blank();

        TransitionIR init = c.init();
        if (c.assetAware()) {
            createAssets(init);
        } else {
            createPlain(init);
        }
        if (c.hasMints()) byAction();
        if (c.migrate() != null) adoptMethod();

        for (var t : c.ownedInits()) createObject(t);

        // ---- SPEND
        spendSection(spend, callNames, batchTag);
        purposeEntrypoints();
        helpers();
        w.close("}");
        return w.toString();
    }

    private void createPlain(TransitionIR init) {
        String X = c.name();
        String S = c.stateType();
        w.open("static boolean create(CreateInstance cr, TxInfo tx, byte[] policy) {");
        declareParams();
        w.line("byte[] id = ValuesLib.uniqueTokenName(cr.seed());");
        w.line("boolean seedSpent = seedConsumed(tx.inputs(), cr.seed());");
        w.line("boolean mintOk = mintsExactlyOne(tx.mint(), policy, id);");
        w.line("TxOut out = uniqueOutputWith(tx.outputs(), policy, id);");
        w.line("boolean shapeOk = isStateAddress(out.address(), policy) && out.referenceScript().isEmpty();");
        if (init.hasCtx()) {
            w.line("boolean ctxOk = ctxValid(tx, cr.ctx(), BigInteger.ZERO, " + init.payable() + ");");
        } else {
            w.line("boolean ctxOk = true;");
        }
        if (c.hasMaps()) {
            w.line(S + " made = " + X + "." + init.method() + "(" + args("cr", init) + ");");
            w.line("// Maps start empty; the init's writes are committed through the verifier's claims (ADR-0002 D9).");
            w.line("MapClaims mc = claims(tx);");
            w.line(S + " initial = " + rebuild("made", (i, a) -> "stored(MapWitnesses.commit(Mpf.emptyRoot(), " + a
                    + ", MapWitnesses.claimFor(mc, cr.seed(), " + i + "L)))") + ";");
        } else {
            w.line(S + " initial = " + X + "." + init.method() + "(" + args("cr", init) + ");");
        }
        w.line("boolean datumOk = Builtins.equalsData(OutputLib.getInlineDatum(out), (PlutusData) (Object) initial)");
        w.line("        && datumFits((PlutusData) (Object) initial);");
        w.line("boolean valueOk = exactValue(out.value(), policy, id, MIN_ADA.add(" + received("cr", init) + "));");
        var extra = new ArrayList<String>();
        outputChecks(false, "initial", extra);
        paramsQuorumChecks(extra);
        var all = new ArrayList<String>(List.of("seedSpent", "mintOk", "shapeOk", "ctxOk", "datumOk", "valueOk"));
        all.addAll(extra);
        w.line("return " + conj(all, "create") + ";");
        w.close("}").blank();
    }

    private void spendSection(List<TransitionIR> spend, List<String> callNames, long batchTag) {
        String S = c.stateType();
        if (!callNames.isEmpty()) {
            w.line("// ---- SPEND: actions and destroys -------------------------------------------------------");
            w.line("@Entrypoint(purpose = Purpose.SPEND)");
            w.open("public static boolean spend(Optional<PlutusData> datum, Calls redeemer, ScriptContext ctx) {");
            w.line("TxInfo tx = ctx.txInfo();");
            w.line("TxInInfo own = ContextsLib.findOwnInput(ctx).get();");
            w.line("TxOut ownOut = own.resolved();");
            w.line("byte[] policy = AddressLib.credentialHash(ownOut.address());");
            w.line("// Only a UTxO holding an instance or object token can be spent; anything else is junk (I10).");
            w.line("byte[] id = " + (c.assetAware() ? "lifecycleTokenOf" : "instanceIdOf") + "(ownOut.value(), policy);");
            w.line("BigInteger inLovelace = ValuesLib.lovelaceOf(ownOut.value());");
            w.open("return switch (redeemer) {");
            for (var t : spend) {
                if (t.owned()) {
                    w.line("// I21: owned redeemers only for object tokens.");
                    w.line("case " + t.variant() + " r -> isObjectToken(id) && on" + t.variant() + "(r, (Holding) (Object)"
                            + " OutputLib.getInlineDatum(ownOut), tx, own, policy, id, inLovelace);");
                } else {
                    w.line("case " + t.variant() + " r -> isInstanceToken(id) && on" + t.variant() + "(r, (" + S
                            + ") (Object) OutputLib.getInlineDatum(ownOut), tx, own, policy, id, inLovelace);");
                }
            }
            if (c.hasBatched()) {
                w.line("// Batch only on an instance; InBatch and CancelIntent only on intents (0x49 tokens).");
                w.line("case Batch r -> isInstanceToken(id) && onBatch(r, (" + S
                        + ") (Object) OutputLib.getInlineDatum(ownOut), tx, own, policy, id, inLovelace);");
                w.line("case InBatch r -> isIntentToken(id) && inBatch((Intent) (Object) OutputLib.getInlineDatum(ownOut), tx, policy);");
                w.line("case CancelIntent r -> isIntentToken(id) && cancelIntent((Intent) (Object) OutputLib.getInlineDatum(ownOut),"
                        + " tx, policy, id);");
            }
            w.close("};");
            w.close("}").blank();
            for (var t : spend) {
                if (c.assetAware()) transitionAssets(t);
                else transition(t);
            }
            if (c.hasBatched()) batch(batchTag);
        }
    }

    private void transition(TransitionIR t) {
        String X = c.name();
        boolean owned = t.owned();
        String S = owned ? c.ownedType() : c.stateType();      // the record this method acts on
        if (owned) {
            w.open("static boolean on" + t.variant() + "(" + t.variant() + " r, Holding held, TxInfo tx, TxInInfo own,"
                    + " byte[] policy, byte[] id, BigInteger inLovelace) {");
            w.line(S + " current = held.value();");
        } else {
            w.open("static boolean on" + t.variant() + "(" + t.variant() + " r, " + S
                    + " current, TxInfo tx, TxInInfo own, byte[] policy, byte[] id, BigInteger inLovelace) {");
        }
        List<String> checks = new ArrayList<>();
        declareParams(t);
        for (String f : t.signedBy()) {
            String v = "signed_" + f;
            w.line("boolean " + v + " = ContextsLib.signedBy(tx, current." + f + "());");
            checks.add(v);
        }
        for (String f : t.validAfter()) {
            String v = "after_" + f;
            w.line("boolean " + v + " = startsAtOrAfter(tx.validRange(), current." + f + "());");
            checks.add(v);
        }
        for (String f : t.validBefore()) {
            String v = "before_" + f;
            w.line("boolean " + v + " = endsAtOrBefore(tx.validRange(), current." + f + "());");
            checks.add(v);
        }
        if (t.hasCtx()) {
            String balance = t.destroys() ? "inLovelace" : "inLovelace.subtract(MIN_ADA)";
            w.line("boolean ctxOk = ctxValid(tx, r.ctx(), " + balance + ", " + t.payable() + ");");
            checks.add("ctxOk");
        }
        authChecks(t, checks);
        String stateArg = "current";
        if (t.shared()) {
            w.line("// I20: the instance state, read through a reference input of exactly this object's instance.");
            w.line(c.stateType() + " shared = sharedState(tx, policy, held.instance());");
        }
        if (c.hasMaps() && !owned) {
            w.line("// Each MerkleMap opened with the read set the map verifier proved (I15).");
            w.line("MapClaims mc = claims(tx);");
            w.line(S + " opened = openAll(current, mc, own.outRef());");
            stateArg = "opened";
        }
        String call = t.shared() ? X + "." + t.method() + "(" + args("r", t, stateArg, "shared") + ")"
                : X + "." + t.method() + "(" + args("r", t, stateArg) + ")";
        java.util.function.UnaryOperator<String> datum = owned
                ? v -> "(PlutusData) (Object) new Holding(held.instance(), " + v + ")"
                : v -> "(PlutusData) (Object) " + v;
        switch (t.returns()) {
            case STATE -> {
                w.line(S + " next = " + (owned ? call : committed(call)) + ";");
                w.line("boolean continues = continues(tx, own, policy, id, " + datum.apply("next") + ", inLovelace.add("
                        + received("r", t) + "));");
                checks.add("continues");
                outputChecks(owned, "next", checks);
            }
            case SETTLE -> {
                w.line(t.resultType() + " result = " + call + ";");
                w.line("JulcList<Payout> payouts = " + (t.resultPayouts() ? "result.payouts()" : "JulcList.empty()") + ";");
                w.line("boolean allocated = Payouts.total(payouts).compareTo(inLovelace.add(" + received("r", t) + ")) == 0;");
                w.line("boolean burned = ValuesLib.assetOf(tx.mint(), policy, id).compareTo(BigInteger.valueOf(-1)) == 0;");
                w.line("boolean paid = payoutsPaid(tx.outputs(), own, payouts);");
                checks.add("allocated");
                checks.add("burned");
                checks.add("paid");
                eventsCheck(t, checks);
            }
            case RESULT -> {
                w.line(t.resultType() + " result = " + call + ";");
                w.line("JulcList<Payout> payouts = " + (t.resultPayouts() ? "result.payouts()" : "JulcList.empty()") + ";");
                w.line(S + " next = " + (owned ? "result.state()" : committed("result.state()")) + ";");
                w.line("boolean continues = continues(tx, own, policy, id, " + datum.apply("next") + ","
                        + " inLovelace.add(" + received("r", t) + ").subtract(Payouts.total(payouts)));");
                w.line("boolean paid = payoutsPaid(tx.outputs(), own, payouts);");
                checks.add("continues");
                checks.add("paid");
                outputChecks(owned, "next", checks);
                eventsCheck(t, checks);
            }
            case PAYOUTS -> {
                w.line("JulcList<Payout> payouts = " + call + ";");
                if (t.migrateTo() != null) {
                    w.line("// I41: the instance migrates: its successor mints blake2b_256(own input) and holds the rest.");
                    w.line("byte[] successor = " + field(t.migrateTo()) + ";");
                    w.line("byte[] successorId = ValuesLib.uniqueTokenName(own.outRef());");
                    w.line("boolean successorMinted = ValuesLib.assetOf(tx.mint(), successor, successorId).compareTo(BigInteger.ONE) == 0;");
                    w.line("TxOut next = uniqueOutputWith(tx.outputs(), successor, successorId);");
                    w.line("boolean successorAt = AddressLib.isScriptAddress(next.address())");
                    w.line("        && ByteStringLib.equals(AddressLib.credentialHash(next.address()), successor);");
                    w.line("boolean allocated = successorMinted && successorAt && ValuesLib.lovelaceOf(next.value())");
                    w.line("        .compareTo(inLovelace.add(" + received("r", t) + ").subtract(Payouts.total(payouts))) >= 0;");
                } else {
                    w.line("// The instance ends: everything it holds, deposit included, is allocated (I8).");
                    w.line("boolean allocated = Payouts.total(payouts).compareTo(inLovelace.add(" + received("r", t) + ")) == 0;");
                }
                w.line("boolean burned = ValuesLib.assetOf(tx.mint(), policy, id).compareTo(BigInteger.valueOf(-1)) == 0;");
                w.line("boolean paid = payoutsPaid(tx.outputs(), own, payouts);");
                checks.add("allocated");
                checks.add("burned");
                checks.add("paid");
            }
        }
        w.line("return " + conj(checks, t.variant()) + ";");
        w.close("}").blank();
    }

    /** Lovelace an intent must carry beyond its payment (ADR-0004 r2: a tagged refund output needs about 1.2 ADA). */
    static final long INTENT_DEPOSIT = 2_000_000L;

    /** Intents (ADR-0004): creation, the batch fold, InBatch, cancel and the step functions. */
    private void batch(long batchTag) {
        String X = c.name();
        String S = c.stateType();
        var batched = c.batched();

        w.line("// ---- intents and batches (ADR-0004) -----------------------------------------------------");
        w.line("/** An intent's token name: 0x49 ++ blake2b_224(seed ref), 29 bytes (instances: 32; objects: 0x4F). */");
        w.open("static byte[] intentId(TxOutRef seed) {");
        w.line("return ByteStringLib.cons(73L, CryptoLib.blake2b_224(ValuesLib.refBytes(seed)));");
        w.close("}").blank();
        w.open("static boolean isIntentToken(byte[] id) {");
        w.line("return ByteStringLib.length(id) == 29 && ByteStringLib.at(id, 0L) == 73L;");
        w.close("}").blank();
        w.line("/** The intent token under this policy in {@code v}, or the empty name. */");
        w.open("static byte[] intentTokenOf(Value v, byte[] policy) {");
        w.line("byte[] name = ByteStringLib.empty();");
        w.open("for (var e : ValuesLib.flattenTyped(v)) {");
        w.open("if (ByteStringLib.equals(e.policyId(), policy) && isIntentToken(e.tokenName())) {");
        w.line("name = e.tokenName();");
        w.close("}");
        w.close("}");
        w.line("return name;");
        w.close("}").blank();

        w.open("static boolean intentPayable(IntentCall call) {");
        w.open("return switch (call) {");
        for (var t : batched) w.line("case " + t.intentVariant() + " a -> " + t.payable() + ";");
        w.close("};");
        w.close("}").blank();

        w.line("/** MINT CreateIntent: signed by its owner, under a 32-byte instance id, exact output (I22). */");
        w.open("static boolean createIntent(CreateIntent ci, TxInfo tx, byte[] policy) {");
        w.line("byte[] id = intentId(ci.seed());");
        w.line("Intent it = ci.intent();");
        w.line("boolean seedSpent = seedConsumed(tx.inputs(), ci.seed());");
        w.line("boolean mintOk = mintsExactlyOne(tx.mint(), policy, id);");
        w.line("// No reference input: the instance moves with every batch, and an intent must not contend with it.");
        w.line("boolean forInstance = isInstanceToken(it.instance());");
        w.line("boolean ownerOk = ByteStringLib.length(it.owner()) == 28 && ContextsLib.signedBy(tx, it.owner());");
        w.line("TxOut out = uniqueOutputWith(tx.outputs(), policy, id);");
        w.line("boolean shapeOk = isStateAddress(out.address(), policy) && out.referenceScript().isEmpty();");
        w.line("PlutusData data = (PlutusData) (Object) it;");
        w.line("boolean datumOk = Builtins.equalsData(OutputLib.getInlineDatum(out), data) && datumFits(data);");
        w.line("BigInteger lovelace = ValuesLib.lovelaceOf(out.value());");
        w.line("boolean valueOk = exactValue(out.value(), policy, id, lovelace)");
        w.line("        && lovelace.compareTo(it.received().add(INTENT_DEPOSIT)) >= 0;");
        w.line("// received is never negative, and zero unless the action is @Payable (ADR-0004 r2).");
        w.line("boolean receivedOk = intentPayable(it.call()) ? it.received().compareTo(BigInteger.ZERO) >= 0");
        w.line("        : it.received().compareTo(BigInteger.ZERO) == 0;");
        w.line("return seedSpent && mintOk && forInstance && ownerOk && shapeOk && datumOk && valueOk && receivedOk;");
        w.close("}").blank();

        w.open("static Step step(" + S + " s, IntentCall call, Ctx cx) {");
        w.open("return switch (call) {");
        for (var t : batched) w.line("case " + t.intentVariant() + " a -> step" + t.variant() + "(s, a, cx);");
        w.close("};");
        w.close("}").blank();
        for (var t : batched) {
            w.open("static Step step" + t.variant() + "(" + S + " s, " + t.intentVariant() + " a, Ctx cx) {");
            declareParams();
            var args = new ArrayList<String>(List.of("s"));
            if (t.takesParams()) args.add(paramsExpr());
            for (ParamIR p : t.params()) args.add("a." + p.name() + "()");
            args.add("cx");
            String call = X + "." + t.method() + "(" + String.join(", ", args) + ")";
            if (t.returns() == TransitionIR.Returns.RESULT) {
                w.line(t.resultType() + " r = " + call + ";");
                w.line("return new Step(r.state(), r.payouts());");
            } else {
                w.line(S + " next = " + call + ";");
                w.line("JulcList<Payout> none = JulcList.empty();");
                w.line("return new Step(next, none);");
            }
            w.close("}").blank();
        }

        w.line("/**");
        w.line(" * SPEND Batch on an instance: folds the action over every intent of this instance among the inputs, in ledger");
        w.line(" * order. Each step: Ctx from the intent and the batch bounds, before its deadline, spent with InBatch, token");
        w.line(" * burned, refund to its refund address and payouts tagged with the intent's own input, balance never negative.");
        w.line(" * The continuation holds the final state and exactly the final balance (I24, I25).");
        w.line(" */");
        w.open("static boolean onBatch(Batch r, " + S + " current, TxInfo tx, TxInInfo own, byte[] policy, byte[] id,"
                + " BigInteger inLovelace) {");
        w.line("boolean fromOk = startsAt(tx.validRange(), r.validFrom());");
        w.line("boolean toOk = endsAt(tx.validRange(), r.validTo());");
        w.line("boolean narrow = r.validTo().subtract(r.validFrom()).compareTo(MAX_VALIDITY_MS) <= 0;");
        w.line(S + " state = current;");
        w.line("BigInteger balance = inLovelace.subtract(MIN_ADA);");
        w.line("long applied = 0;");
        w.line("boolean ok = true;");
        w.open("for (var in : tx.inputs()) {");
        w.line("TxOut o = in.resolved();");
        w.line("byte[] tok = intentTokenOf(o.value(), policy);");
        w.open("if (isIntentToken(tok) && isStateAddress(o.address(), policy)) {");
        w.line("Intent it = (Intent) (Object) OutputLib.getInlineDatum(o);");
        w.open("if (ByteStringLib.equals(it.instance(), id)) {");
        w.line("Ctx cx = new Ctx(it.owner(), it.received(), balance, r.validFrom(), r.validTo());");
        w.line("Step st = step(state, it.call(), cx);");
        w.line("JulcList<Payout> payouts = st.payouts();");
        w.line("BigInteger nextBalance = balance.add(it.received()).subtract(Payouts.total(payouts));");
        w.line("PlutusData rd = tx.redeemers().get(new ScriptPurpose.Spending(in.outRef()));");
        w.line("boolean viaInBatch = Builtins.constrTag(rd) == " + (batchTag + 1) + "L;");
        w.line("boolean inTime = r.validTo().compareTo(it.deadline()) <= 0;");
        w.line("boolean burned = ValuesLib.assetOf(tx.mint(), policy, tok).compareTo(BigInteger.valueOf(-1)) == 0;");
        w.line("BigInteger refund = ValuesLib.lovelaceOf(o.value()).subtract(it.received());");
        w.line("boolean refunded = paidTo(tx.outputs(), it.refund(), (PlutusData) (Object) new IntentRefund(in.outRef()))"
                + ".compareTo(refund) >= 0;");
        w.line("boolean paid = payoutsTagged(tx.outputs(), (PlutusData) (Object) in.outRef(), payouts);");
        w.line("boolean solvent = nextBalance.compareTo(BigInteger.ZERO) >= 0;");
        w.line("ok = ok && viaInBatch && inTime && burned && refunded && paid && solvent;");
        w.line("state = st.state();");
        w.line("balance = nextBalance;");
        w.line("applied = applied + 1;");
        w.close("}");
        w.close("}");
        w.close("}");
        w.line("boolean continues = continues(tx, own, policy, id, (PlutusData) (Object) state, balance.add(MIN_ADA));");
        w.line("return fromOk && toOk && narrow && ok && applied > 0 && continues;");
        w.close("}").blank();

        w.line("/** SPEND InBatch on an intent: its instance is spent in this transaction with Batch, which applies it (I23). */");
        w.open("static boolean inBatch(Intent it, TxInfo tx, byte[] policy) {");
        w.line("boolean found = false;");
        w.open("for (var in : tx.inputs()) {");
        w.line("TxOut o = in.resolved();");
        w.open("if (!found && ValuesLib.assetOf(o.value(), policy, it.instance()).compareTo(BigInteger.ONE) == 0"
                + " && isStateAddress(o.address(), policy)) {");
        w.line("PlutusData rd = tx.redeemers().get(new ScriptPurpose.Spending(in.outRef()));");
        w.line("found = Builtins.constrTag(rd) == " + batchTag + "L;");
        w.close("}");
        w.close("}");
        w.line("return found;");
        w.close("}").blank();

        w.line("/** SPEND CancelIntent: the owner signs and the intent token is burned (I23). */");
        w.open("static boolean cancelIntent(Intent it, TxInfo tx, byte[] policy, byte[] id) {");
        w.line("boolean signed = ContextsLib.signedBy(tx, it.owner());");
        w.line("boolean burned = ValuesLib.assetOf(tx.mint(), policy, id).compareTo(BigInteger.valueOf(-1)) == 0;");
        w.line("return signed && burned;");
        w.close("}").blank();

        w.line("/** Lovelace paid to exactly {@code to} in outputs whose inline datum is {@code tag}. */");
        w.open("static BigInteger paidTo(JulcList<TxOut> outputs, Address to, PlutusData tag) {");
        w.line("BigInteger paid = BigInteger.ZERO;");
        w.open("for (var o : outputs) {");
        w.open("if (Builtins.equalsData(o.address(), to) && taggedWith(o, tag)) {");
        w.line("paid = paid.add(ValuesLib.lovelaceOf(o.value()));");
        w.close("}");
        w.close("}");
        w.line("return paid;");
        w.close("}").blank();
    }

    /** MINT for an {@code @OwnedInit}: one object under an existing instance (I18). */
    private void createObject(TransitionIR t) {
        if (c.assetAware()) {
            createObjectAssets(t);
            return;
        }
        String X = c.name();
        String R = c.ownedType();
        w.open("static boolean create" + t.variant() + "(" + t.variant() + " o, TxInfo tx, byte[] policy) {");
        declareParams();
        w.line("byte[] id = objectId(o.seed());");
        w.line("boolean seedSpent = seedConsumed(tx.inputs(), o.seed());");
        w.line("boolean mintOk = mintsExactlyOne(tx.mint(), policy, id);");
        w.line("boolean underInstance = ByteStringLib.length(o.instance()) == 32;");
        w.line("// The instance exists: a reference input holds its token (and gives the @Shared state).");
        w.line(c.stateType() + " shared = sharedState(tx, policy, o.instance());");
        w.line("TxOut out = uniqueOutputWith(tx.outputs(), policy, id);");
        w.line("boolean shapeOk = isStateAddress(out.address(), policy) && out.referenceScript().isEmpty();");
        if (t.hasCtx()) {
            w.line("boolean ctxOk = ctxValid(tx, o.ctx(), BigInteger.ZERO, " + t.payable() + ");");
        } else {
            w.line("boolean ctxOk = true;");
        }
        String call = t.shared() ? X + "." + t.method() + "(" + args("o", t, "shared") + ")"
                : X + "." + t.method() + "(" + args("o", t) + ")";
        w.line(R + " value = " + call + ";");
        w.line("PlutusData held = (PlutusData) (Object) new Holding(o.instance(), value);");
        w.line("boolean datumOk = Builtins.equalsData(OutputLib.getInlineDatum(out), held) && datumFits(held);");
        w.line("boolean valueOk = exactValue(out.value(), policy, id, MIN_ADA.add(" + received("o", t) + "));");
        var extra = new ArrayList<String>(List.of("seedSpent", "mintOk", "underInstance", "shapeOk", "ctxOk", "datumOk", "valueOk"));
        outputChecks(true, "value", extra);
        w.line("return " + conj(extra, "create" + t.variant()) + ";");
        w.close("}").blank();
    }




    /** MINT Adopt: the new version takes over an instance of the previous one, in the same transaction (I41). */
    private void adoptMethod() {
        TransitionIR t = c.migrate();
        var old = t.reads().get(0);
        String S = c.stateType();
        w.open("static boolean adopt(Adopt a, TxInfo tx, byte[] policy) {");
        declareParams();
        w.line("byte[] previous = " + field(old.path()) + ";");
        w.line("byte[] id = ValuesLib.uniqueTokenName(a.seed());");
        w.line("TxOut prior = spentOutput(tx.inputs(), a.seed());");
        w.line("// The seed is a state UTxO of the previous policy: one 32-byte token at its script address, inline datum.");
        w.line("byte[] priorId = instanceIdOf(prior.value(), previous);");
        w.line("boolean priorOk = ByteStringLib.length(priorId) == 32 && AddressLib.isScriptAddress(prior.address())");
        w.line("        && ByteStringLib.equals(AddressLib.credentialHash(prior.address()), previous);");
        w.line("PlutusData priorDatum = OutputLib.getInlineDatum(prior);");
        w.line("boolean shapeOk = Builtins.constrTag(priorDatum) == 0 && fieldCount(priorDatum) == " + old.fields() + "L;");
        w.line("// The previous instance ends here: its token burns.");
        w.line("boolean priorBurned = ValuesLib.assetOf(tx.mint(), previous, priorId).compareTo(BigInteger.valueOf(-1)) == 0;");
        w.line("boolean mintOk = mintsExactlyOne(tx.mint(), policy, id);");
        w.line("TxOut out = uniqueOutputWith(tx.outputs(), policy, id);");
        w.line("boolean addressOk = isStateAddress(out.address(), policy) && out.referenceScript().isEmpty();");
        var checks = new ArrayList<String>(List.of("priorOk", "shapeOk", "priorBurned", "mintOk", "addressOk"));
        if (t.hasCtx()) {
            w.line("boolean ctxOk = ctxValid(tx, a.ctx(), BigInteger.ZERO, false);");
            checks.add("ctxOk");
        }
        w.line(old.type() + " " + old.name() + " = (" + old.type() + ") (Object) priorDatum;");
        w.line(S + " initial = " + c.name() + "." + t.method() + "(" + args("a", t) + ");");
        w.line("boolean datumOk = Builtins.equalsData(OutputLib.getInlineDatum(out), (PlutusData) (Object) initial)");
        w.line("        && datumFits((PlutusData) (Object) initial);");
        w.line("// Value conservation: exactly the previous instance's lovelace, now with the new token.");
        w.line("BigInteger lovelace = ValuesLib.lovelaceOf(prior.value());");
        w.line("boolean valueOk = lovelace.compareTo(MIN_ADA) >= 0 && exactValue(out.value(), policy, id, lovelace);");
        checks.add("datumOk");
        checks.add("valueOk");
        outputChecks(false, "initial", checks);
        paramsQuorumChecks(checks);
        w.line("return " + conj(checks, "adopt") + ";");
        w.close("}").blank();
        w.open("static TxOut spentOutput(JulcList<TxInInfo> inputs, TxOutRef ref) {");
        w.line("TxOut hit = inputs.head().resolved();");
        w.line("boolean found = false;");
        w.open("for (var in : inputs) {");
        w.open("if (Builtins.equalsData(in.outRef(), ref)) {");
        w.line("hit = in.resolved();");
        w.line("found = true;");
        w.close("}");
        w.close("}");
        w.open("if (!found) {");
        w.line("Builtins.error();");
        w.line("return hit;");
        w.close("} else {");
        w.indent();
        w.line("return hit;");
        w.close("}");
        w.close("}").blank();
        w.open("static long fieldCount(PlutusData d) {");
        w.line("long n = 0;");
        w.line("PlutusData fs = Builtins.constrFields(d);");
        w.open("while (!Builtins.nullList(fs)) {");
        w.line("n = n + 1;");
        w.line("fs = Builtins.tailList(fs);");
        w.close("}");
        w.line("return n;");
        w.close("}").blank();
    }

    // ------------------------------------------------------------------ ADR-0010: other script purposes

    private void purposeRedeemers() {
        c.purpose("WITHDRAW").ifPresent(pr -> {
            var fs = new ArrayList<String>(List.of("BigInteger marker", "BigInteger amount"));
            for (ParamIR a : pr.args()) fs.add(a.type() + " " + a.name());
            if (pr.hasCtx()) fs.add("Ctx ctx");
            w.line("/** The @Withdraw redeemer; its leading integer marker (74) means it never reads as Approvals (ADR-0010 r2). */");
            w.line("public record Rewards(" + String.join(", ", fs) + ") {}");
        });
    }

    /** Authorization of a purpose handler: quorum and governors over params.*, bound to this script info (I33). */
    private void purposeAuth(ContractIR.PurposeIR pr, long purposeTag, List<String> checks) {
        if (pr.quorumKeys() != null) {
            w.line("boolean quorum = quorumOf(tx, " + field(pr.quorumKeys()) + ", " + field(pr.quorumThreshold()) + ");");
            checks.add("quorum");
        }
        if (!pr.authorizedBy().isEmpty()) {
            w.line("byte[] target = CryptoLib.blake2b_224(Builtins.serialiseData((PlutusData) (Object) ctx.scriptInfo()));");
            int i = 0;
            for (String f : pr.authorizedBy()) {
                String v = "authorized_" + (i++);
                w.line("boolean " + v + " = authorized(tx, " + field(f) + ", policy, target, " + (1000 + purposeTag) + "L);");
                checks.add(v);
            }
        }
    }

    private void purposeEntrypoints() {
        String X = c.name();
        if (c.purposes().isEmpty()) return;
        w.line("// ---- other purposes (ADR-0010) -------------------------------------------------------------");
        c.purpose("WITHDRAW").ifPresent(pr -> {
            w.line("@Entrypoint(purpose = Purpose.WITHDRAW)");
            w.open("public static boolean withdraw(Rewards r, ScriptContext ctx) {");
            w.line("TxInfo tx = ctx.txInfo();");
            w.line("ScriptInfo.RewardingScript ri = (ScriptInfo.RewardingScript) ctx.scriptInfo();");
            w.line("Credential own = ri.credential();");
            w.line("byte[] policy = scriptHashOf(own);");
            declareParams();
            var checks = new ArrayList<String>();
            w.line("boolean marked = r.marker().compareTo(BigInteger.valueOf(74)) == 0;");
            w.line("// The ledger requires the whole balance; the redeemer states it exactly (I37).");
            w.line("boolean amountOk = r.amount().compareTo(tx.withdrawals().get(own)) == 0;");
            checks.add("marked");
            checks.add("amountOk");
            if (pr.hasCtx()) {
                w.line("boolean ctxOk = ctxValid(tx, r.ctx(), r.amount(), false);");
                checks.add("ctxOk");
            }
            purposeAuth(pr, 2, checks);
            var args = new ArrayList<String>(List.of("params", "r.amount()"));
            for (ParamIR a : pr.args()) args.add("r." + a.name() + "()");
            if (pr.hasCtx()) args.add("r.ctx()");
            w.line("JulcList<Send> sends = " + X + "." + pr.method() + "(" + String.join(", ", args) + ");");
            w.line("// Fully allocated, lovelace only, each send claimed in the registry by RewardRef(own) (I37).");
            w.line("boolean allocated = Builtins.equalsData(Builtins.valueData(sendsTotal(sends, policy)),"
                    + " Builtins.valueData(lovelaceValue(r.amount())));");
            w.line("boolean sent = sendsClaimed(tx, rewardRef(policy), sends, policy);");
            checks.add("allocated");
            checks.add("sent");
            w.line("return " + conj(checks, "withdraw") + ";");
            w.close("}").blank();
            w.line("/** The claimant of reward sends: a reference no real input can have (index -1). */");
            w.open("static TxOutRef rewardRef(byte[] h) {");
            w.line("return new TxOutRef(new TxId(CryptoLib.blake2b_256(h)), BigInteger.valueOf(-1));");
            w.close("}").blank();
        });
        c.purpose("CERTIFY").ifPresent(pr -> {
            w.line("@Entrypoint(purpose = Purpose.CERTIFY)");
            w.open("public static boolean certify(PlutusData redeemer, ScriptContext ctx) {");
            w.line("TxInfo tx = ctx.txInfo();");
            w.line("ScriptInfo.CertifyingScript ci = (ScriptInfo.CertifyingScript) ctx.scriptInfo();");
            w.line("TxCert cert = ci.cert();");
            w.line("// Only registration, delegation and DRep registration/update; unregistration, committee and pool certificates");
            w.line("// are refused before the handler runs (ADR-0010 r2).");
            w.line("byte[] policy = certOwner(cert);");
            declareParams();
            var checks = new ArrayList<String>();
            purposeAuth(pr, 3, checks);
            w.line("boolean allowed = " + X + "." + pr.method() + "(params, cert);");
            checks.add("allowed");
            w.line("return " + conj(checks, "certify") + ";");
            w.close("}").blank();
            w.open("static byte[] certOwner(TxCert c) {");
            w.open("return switch (c) {");
            w.line("case TxCert.RegStaking x -> scriptHashOf(x.credential());");
            w.line("case TxCert.DelegStaking x -> scriptHashOf(x.credential());");
            w.line("case TxCert.RegDeleg x -> scriptHashOf(x.credential());");
            w.line("case TxCert.RegDRep x -> scriptHashOf(x.credential());");
            w.line("case TxCert.UpdateDRep x -> scriptHashOf(x.credential());");
            w.line("default -> refused();");
            w.close("};");
            w.close("}").blank();
        });
        c.purpose("VOTE").ifPresent(pr -> {
            w.line("@Entrypoint(purpose = Purpose.VOTE)");
            w.open("public static boolean vote(PlutusData redeemer, ScriptContext ctx) {");
            w.line("TxInfo tx = ctx.txInfo();");
            w.line("ScriptInfo.VotingScript vi = (ScriptInfo.VotingScript) ctx.scriptInfo();");
            w.line("Voter voter = vi.voter();");
            w.line("byte[] policy = voterOwner(voter);");
            declareParams();
            var checks = new ArrayList<String>();
            purposeAuth(pr, 4, checks);
            w.line("// Exactly this voter's votes; every one must be allowed (fails if there are none).");
            w.line("JulcMap<GovernanceActionId, Vote> mine = tx.votes().get(voter);");
            w.line("boolean any = !mine.isEmpty();");
            w.line("boolean all = true;");
            w.open("for (var id : mine.keys()) {");
            w.line("all = all && " + X + "." + pr.method() + "(params, voter, id, mine.get(id));");
            w.close("}");
            checks.add("any");
            checks.add("all");
            w.line("return " + conj(checks, "vote") + ";");
            w.close("}").blank();
            w.open("static byte[] voterOwner(Voter v) {");
            w.open("return switch (v) {");
            w.line("case Voter.DRepVoter d -> scriptHashOf(d.credential());");
            w.line("case Voter.CommitteeVoter m -> scriptHashOf(m.credential());");
            w.line("default -> refused();");
            w.close("};");
            w.close("}").blank();
        });
        c.purpose("PROPOSE").ifPresent(pr -> {
            w.line("@Entrypoint(purpose = Purpose.PROPOSE)");
            w.open("public static boolean propose(PlutusData redeemer, ScriptContext ctx) {");
            w.line("ScriptInfo.ProposingScript pi = (ScriptInfo.ProposingScript) ctx.scriptInfo();");
            declareParams();
            w.line("return " + X + "." + pr.method() + "(params, pi.procedure());");
            w.close("}").blank();
        });
        w.open("static byte[] scriptHashOf(Credential c) {");
        w.open("return switch (c) {");
        w.line("case Credential.ScriptCredential sc -> (byte[]) (Object) sc.hash();");
        w.line("case Credential.PubKeyCredential pk -> refused();");
        w.close("};");
        w.close("}").blank();
        w.open("static byte[] refused() {");
        w.line("Builtins.error();");
        w.line("return ByteStringLib.empty();");
        w.close("}").blank();
    }

    // ------------------------------------------------------------------ ADR-0009: params, quorum, authorization, invariants, events

    /** The @Params record, as an expression. */
    private String paramsExpr() {
        return "params";
    }

    /** Declares the typed {@code params} local (JuLC needs a typed local before an accessor). */
    private void declareParams() {
        if (c.hasParams()) w.line(c.paramsType() + " params = (" + c.paramsType() + ") (Object) PARAMS;");
    }

    /** {@link #declareParams()}, then each {@code @Reads} value, authenticated and shape-checked (I39). */
    private void declareParams(TransitionIR t) {
        declareParams();
        for (var rd : t.reads()) {
            w.line(rd.type() + " " + rd.name() + " = (" + rd.type() + ") (Object) foreignState(tx, " + field(rd.path()) + ", "
                    + rd.fields() + "L);");
        }
    }

    private boolean anyReads() {
        return c.allTransitions().stream().anyMatch(t -> !t.reads().isEmpty());
    }

    private void readsHelpers() {
        if (!anyReads()) return;
        w.line("/**");
        w.line(" * The state of another JOUST contract's instance (I39): the reference input holding exactly one token of");
        w.line(" * {@code fi.policy}, named {@code fi.instance} (32 bytes), at that script's address, with an inline datum of");
        w.line(" * constructor 0 and {@code fields} fields.");
        w.line(" */");
        w.open("static PlutusData foreignState(TxInfo tx, ForeignInstance fi, long fields) {");
        w.line("PlutusData one = Builtins.mapData(Builtins.mkCons(Builtins.mkPairData(Builtins.bData(fi.instance()), Builtins.iData(1L)),"
                + " Builtins.mkNilPairData()));");
        w.line("boolean found = false;");
        w.line("PlutusData datum = Builtins.iData(0L);");
        w.open("for (var ref : tx.referenceInputs()) {");
        w.line("TxOut o = ref.resolved();");
        w.open("if (!found && AddressLib.isScriptAddress(o.address()) && ByteStringLib.equals(AddressLib.credentialHash(o.address()),"
                + " fi.policy()) && Builtins.equalsData(ledgerTokensOf(o.value(), fi.policy()), one)) {");
        w.line("found = true;");
        w.line("datum = OutputLib.getInlineDatum(o);");
        w.close("}");
        w.close("}");
        w.line("long n = 0;");
        w.line("PlutusData fs = Builtins.constrFields(datum);");
        w.open("while (!Builtins.nullList(fs)) {");
        w.line("n = n + 1;");
        w.line("fs = Builtins.tailList(fs);");
        w.close("}");
        w.open("if (!found || ByteStringLib.length(fi.instance()) != 32 || Builtins.constrTag(datum) != 0 || n != fields) {");
        w.line("Builtins.error();");
        w.line("return datum;");
        w.close("} else {");
        w.indent();
        w.line("return datum;");
        w.close("}");
        w.close("}").blank();
    }

    /** A field path of the current subject ({@code current.x()}) or of the params ({@code params.x}). */
    private String field(String path) {
        return path.startsWith("params.") ? paramsExpr() + "." + path.substring(7) + "()" : "current." + path + "()";
    }

    /** The redeemer constructor tag of a spend transition (its position in Calls). */
    private long spendTag(TransitionIR t) {
        var spend = new ArrayList<TransitionIR>(c.spendTransitions());
        spend.addAll(c.ownedSpendTransitions());
        for (int i = 0; i < spend.size(); i++) if (spend.get(i).method().equals(t.method())) return i;
        throw new IllegalStateException("not a spend transition: " + t.method());
    }

    /** @Quorum and @AuthorizedBy checks on the current (input) state (I32, I33). */
    private void authChecks(TransitionIR t, List<String> checks) {
        if (t.quorumKeys() != null) {
            w.line("boolean quorum = quorumOf(tx, " + field(t.quorumKeys()) + ", " + field(t.quorumThreshold()) + ");");
            checks.add("quorum");
        }
        int i = 0;
        for (String f : t.authorizedBy()) {
            String v = "authorized_" + (i++);
            w.line("boolean " + v + " = authorized(tx, " + field(f) + ", policy, id, " + spendTag(t) + "L);");
            checks.add(v);
        }
    }

    /** Invariants and quorum shapes of a state the validator accepts as an output (I32, I34). */
    private void outputChecks(boolean owned, String subject, List<String> checks) {
        int i = 0;
        for (var inv : c.invariants()) {
            if (inv.owned() != owned) continue;
            String v = "invariant_" + (i++);
            w.line("boolean " + v + " = " + c.name() + "." + inv.method() + "(" + subject
                    + (inv.takesParams() ? ", " + paramsExpr() : "") + ");");
            checks.add(v);
        }
        var seen = new java.util.LinkedHashSet<String>();
        for (var t : c.allTransitions()) {
            if (t.quorumKeys() == null || t.owned() != owned || t.quorumKeys().startsWith("params.")) continue;
            String pair = t.quorumKeys() + "," + t.quorumThreshold();
            if (!seen.add(pair)) continue;
            String v = "quorumShape_" + (i++);
            w.line("boolean " + v + " = quorumWellFormed(" + subject + "." + t.quorumKeys() + "(), " + subject + "."
                    + t.quorumThreshold() + "());");
            checks.add(v);
        }
    }

    /** Params-level quorum shapes, checked when an instance is created. */
    private void paramsQuorumChecks(List<String> checks) {
        var pairs = new java.util.LinkedHashSet<String>();
        for (var t : c.allTransitions()) {
            if (t.quorumKeys() != null && t.quorumKeys().startsWith("params.")) pairs.add(t.quorumKeys() + "," + t.quorumThreshold());
        }
        for (var pr : c.purposes()) {
            if (pr.quorumKeys() != null) pairs.add(pr.quorumKeys() + "," + pr.quorumThreshold());
        }
        int i = 0;
        for (String pair : pairs) {
            String[] kt = pair.split(",");
            String v = "paramsQuorum_" + (i++);
            w.line("boolean " + v + " = quorumWellFormed(" + field(kt[0]) + ", " + field(kt[1]) + ");");
            checks.add(v);
        }
    }

    /** The redeemer carries exactly the method's events (I35). */
    private void eventsCheck(TransitionIR t, List<String> checks) {
        if (!t.resultEvents()) return;
        w.line("boolean eventsOk = Builtins.equalsData(Builtins.listData(r.events()), Builtins.listData(result.events()));");
        checks.add("eventsOk");
    }

    private boolean anyQuorum() {
        return c.allTransitions().stream().anyMatch(t -> t.quorumKeys() != null)
                || c.purposes().stream().anyMatch(p -> p.quorumKeys() != null);
    }

    private boolean anyAuthorizedBy() {
        return c.allTransitions().stream().anyMatch(t -> !t.authorizedBy().isEmpty())
                || c.purposes().stream().anyMatch(p -> !p.authorizedBy().isEmpty());
    }

    private void authHelpers() {
        if (anyQuorum()) {
            w.line("/** At least {@code threshold >= 1} of the transaction's signatories are in {@code keys} (I32). */");
            w.open("static boolean quorumOf(TxInfo tx, JulcList<byte[]> keys, BigInteger threshold) {");
            w.line("long count = 0;");
            w.open("for (var sig : tx.signatories()) {");
            w.line("byte[] s = (byte[]) (Object) sig;");
            w.line("boolean member = false;");
            w.open("for (var k : keys) {");
            w.open("if (ByteStringLib.equals(k, s)) {");
            w.line("member = true;");
            w.close("}");
            w.close("}");
            w.open("if (member) {");
            w.line("count = count + 1;");
            w.close("}");
            w.close("}");
            w.line("return threshold.compareTo(BigInteger.ONE) >= 0 && BigInteger.valueOf(count).compareTo(threshold) >= 0;");
            w.close("}").blank();

            w.line("/** Keys strictly ascending (so distinct), each 28 bytes, and 1 <= threshold <= their number (I32). */");
            w.open("static boolean quorumWellFormed(JulcList<byte[]> keys, BigInteger threshold) {");
            w.line("boolean ok = true;");
            w.line("long n = 0;");
            w.line("byte[] prev = ByteStringLib.empty();");
            w.open("for (var k : keys) {");
            w.open("if (ByteStringLib.length(k) != 28 || (n > 0 && !Builtins.lessThanByteString(prev, k))) {");
            w.line("ok = false;");
            w.close("}");
            w.line("prev = k;");
            w.line("n = n + 1;");
            w.close("}");
            w.line("return ok && threshold.compareTo(BigInteger.ONE) >= 0 && threshold.compareTo(BigInteger.valueOf(n)) <= 0;");
            w.close("}").blank();
        }
        if (anyAuthorizedBy()) {
            w.line("/**");
            w.line(" * The credential approves this action on {@code target} (I33): a key signed; a script was withdrawn from in the");
            w.line(" * transaction and, if it is a Plutus script, its Rewarding redeemer lists Approval(policy, target, action).");
            w.line(" * Never this contract's own script or a JOUST verifier, which approve anything.");
            w.line(" */");
            w.open("static boolean authorized(TxInfo tx, Credential c, byte[] policy, byte[] target, long action) {");
            w.open("return switch (c) {");
            w.line("case Credential.PubKeyCredential pk -> ContextsLib.signedBy(tx, (byte[]) (Object) pk.hash());");
            w.line("case Credential.ScriptCredential sc -> scriptApproves(tx, c, (byte[]) (Object) sc.hash(), policy, target, action);");
            w.close("};");
            w.close("}").blank();
            w.open("static boolean scriptApproves(TxInfo tx, Credential c, byte[] h, byte[] policy, byte[] target, long action) {");
            w.line("boolean forbidden = ByteStringLib.equals(h, policy) || ByteStringLib.equals(h, " + MapVerifierInfo.hashLiteral()
                    + ") || ByteStringLib.equals(h, " + MapVerifierInfo.hashLiteral("OutputVerifier") + ");");
            w.line("boolean withdrawn = tx.withdrawals().containsKey(c);");
            w.line("ScriptPurpose purpose = new ScriptPurpose.Rewarding(c);");
            w.line("// A Plutus script has a redeemer and must name this approval; a native script (no redeemer) signs for all.");
            w.line("boolean bound = !tx.redeemers().containsKey(purpose) || approves(tx.redeemers().get(purpose), policy, target, action);");
            w.line("return !forbidden && withdrawn && bound;");
            w.close("}").blank();
            w.open("static boolean approves(PlutusData rd, byte[] policy, byte[] target, long action) {");
            w.line("Approvals list = (Approvals) (Object) rd;");
            w.line("boolean found = false;");
            w.open("for (var a : list.approvals()) {");
            w.open("if (ByteStringLib.equals(a.policy(), policy) && ByteStringLib.equals(a.target(), target)"
                    + " && a.action().compareTo(BigInteger.valueOf(action)) == 0) {");
            w.line("found = true;");
            w.close("}");
            w.close("}");
            w.line("return found;");
            w.close("}").blank();
        }
    }

    // ------------------------------------------------------------------ native assets (ADR-0007)

    /** Lines declaring {@code Funds funds} for a method that takes it; the redeemer carries only what the caller offers. */
    private void fundsLocal(TransitionIR t, String redeemerVar, String selfExpr, String heldExpr) {
        if (!t.hasFunds()) return;
        String offered = takesAssets(t) ? "(Value) (Object) " + redeemerVar + ".assetsIn()" : "noAssets()";
        w.line("Funds funds = new Funds(policy, " + selfExpr + ", " + offered + ", " + heldExpr + ");");
    }

    /**
     * Lines checking the method's {@code accepted} against what the caller offered, and declaring {@code acceptedV}
     * (native) for the value equation (I31).
     */
    private void acceptedLocal(TransitionIR t, String redeemerVar, List<String> checks) {
        if (t.resultAccepted()) {
            w.line("JulcValue acceptedV = methodValue(result.accepted(), policy, 1L);");
            w.line("boolean acceptedOk = Builtins.equalsData(Builtins.valueData(acceptedV), " + redeemerVar + ".assetsIn());");
            checks.add("acceptedOk");
        } else {
            w.line("JulcValue acceptedV = noValue();");
        }
    }

    /** MINT CreateInstance for an asset-aware contract: as A, with the full value equation (I26). */
    private void createAssets(TransitionIR init) {
        String X = c.name();
        String S = c.stateType();
        w.open("static boolean create(CreateInstance cr, TxInfo tx, byte[] policy) {");
        declareParams();
        w.line("byte[] id = ValuesLib.uniqueTokenName(cr.seed());");
        w.line("boolean seedSpent = seedConsumed(tx.inputs(), cr.seed());");
        w.line("boolean mintOk = mintsExactlyOne(tx.mint(), policy, id);");
        w.line("TxOut out = uniqueOutputWith(tx.outputs(), policy, id);");
        w.line("boolean shapeOk = isStateAddress(out.address(), policy) && out.referenceScript().isEmpty();");
        var checks = new ArrayList<String>(List.of("seedSpent", "mintOk", "shapeOk"));
        if (init.hasCtx()) {
            w.line("boolean ctxOk = ctxValid(tx, cr.ctx(), BigInteger.ZERO, " + init.payable() + ");");
            checks.add("ctxOk");
        }
        fundsLocal(init, "cr", "id", "noAssets()");
        String call = X + "." + init.method() + "(" + args("cr", init) + ")";
        String made;
        if (init.returnsRecord()) {
            w.line(init.resultType() + " result = " + call + ";");
            made = "result.state()";
        } else {
            made = call;
        }
        if (c.hasMaps()) {
            w.line(S + " made = " + made + ";");
            w.line("MapClaims mc = claims(tx);");
            w.line(S + " initial = " + rebuild("made", (i, a) -> "stored(MapWitnesses.commit(Mpf.emptyRoot(), " + a
                    + ", MapWitnesses.claimFor(mc, cr.seed(), " + i + "L)))") + ";");
        } else {
            w.line(S + " initial = " + made + ";");
        }
        acceptedLocal(init, "cr", checks);
        w.line("boolean datumOk = Builtins.equalsData(OutputLib.getInlineDatum(out), (PlutusData) (Object) initial)");
        w.line("        && datumFits((PlutusData) (Object) initial);");
        outputChecks(false, "initial", checks);
        paramsQuorumChecks(checks);
        w.line("JulcValue expected = Builtins.insertCoin(policy, id, BigInteger.ONE,");
        w.line("        Builtins.unionValue(lovelaceValue(MIN_ADA.add(" + received("cr", init) + ")), acceptedV));");
        w.line("boolean valueOk = Builtins.equalsData(Builtins.valueData(expected), (PlutusData) (Object) out.value());");
        checks.add("datumOk");
        checks.add("valueOk");
        w.line("return " + conj(checks, "create") + ";");
        w.close("}").blank();
    }

    /** MINT for an {@code @OwnedInit} in an asset-aware contract (I18, I26). */
    private void createObjectAssets(TransitionIR t) {
        String X = c.name();
        String R = c.ownedType();
        w.open("static boolean create" + t.variant() + "(" + t.variant() + " o, TxInfo tx, byte[] policy) {");
        declareParams();
        w.line("byte[] id = objectId(o.seed());");
        w.line("boolean seedSpent = seedConsumed(tx.inputs(), o.seed());");
        w.line("boolean mintOk = mintsExactlyOne(tx.mint(), policy, id);");
        w.line("boolean underInstance = ByteStringLib.length(o.instance()) == 32;");
        w.line(c.stateType() + " shared = sharedState(tx, policy, o.instance());");
        w.line("TxOut out = uniqueOutputWith(tx.outputs(), policy, id);");
        w.line("boolean shapeOk = isStateAddress(out.address(), policy) && out.referenceScript().isEmpty();");
        var checks = new ArrayList<String>(List.of("seedSpent", "mintOk", "underInstance", "shapeOk"));
        if (t.hasCtx()) {
            w.line("boolean ctxOk = ctxValid(tx, o.ctx(), BigInteger.ZERO, " + t.payable() + ");");
            checks.add("ctxOk");
        }
        fundsLocal(t, "o", "id", "noAssets()");
        String call = t.shared() ? X + "." + t.method() + "(" + args("o", t, "shared") + ")"
                : X + "." + t.method() + "(" + args("o", t) + ")";
        if (t.returnsRecord()) {
            w.line(t.resultType() + " result = " + call + ";");
            w.line(R + " value = result.state();");
        } else {
            w.line(R + " value = " + call + ";");
        }
        acceptedLocal(t, "o", checks);
        outputChecks(true, "value", checks);
        w.line("PlutusData held = (PlutusData) (Object) new Holding(o.instance(), value);");
        w.line("boolean datumOk = Builtins.equalsData(OutputLib.getInlineDatum(out), held) && datumFits(held);");
        w.line("JulcValue expected = Builtins.insertCoin(policy, id, BigInteger.ONE,");
        w.line("        Builtins.unionValue(lovelaceValue(MIN_ADA.add(" + received("o", t) + ")), acceptedV));");
        w.line("boolean valueOk = Builtins.equalsData(Builtins.valueData(expected), (PlutusData) (Object) out.value());");
        checks.add("datumOk");
        checks.add("valueOk");
        w.line("return " + conj(checks, "create" + t.variant()) + ";");
        w.close("}").blank();
    }

    /** MINT ByAction: the named input is a state UTxO spent with a method that may mint (I28). */
    private void byAction() {
        var tags = new ArrayList<String>();
        var spend = new ArrayList<TransitionIR>(c.spendTransitions());
        spend.addAll(c.ownedSpendTransitions());
        for (int i = 0; i < spend.size(); i++) {
            if (spend.get(i).resultMint()) tags.add("tag == " + i + "L");
        }
        w.line("/** MINT ByAction: input {@code by} is a state UTxO spent with a method that may mint, which checks the mint (I28). */");
        w.open("static boolean byAction(ByAction b, TxInfo tx, byte[] policy) {");
        w.line("boolean found = false;");
        w.open("for (var in : tx.inputs()) {");
        w.open("if (!found && Builtins.equalsData(in.outRef(), b.by()) && isStateAddress(in.resolved().address(), policy)) {");
        w.line("byte[] tok = lifecycleTokenOf(in.resolved().value(), policy);");
        w.line("PlutusData rd = tx.redeemers().get(new ScriptPurpose.Spending(in.outRef()));");
        w.line("long tag = Builtins.constrTag(rd);");
        w.line("found = ByteStringLib.length(tok) > 0 && (" + String.join(" || ", tags) + ");");
        w.close("}");
        w.close("}");
        w.line("return found;");
        w.close("}").blank();
    }

    /** SPEND for an asset-aware contract: A's checks with the full value equation, sends and user mints (I26–I31). */
    private void transitionAssets(TransitionIR t) {
        String X = c.name();
        boolean owned = t.owned();
        String S = owned ? c.ownedType() : c.stateType();
        if (owned) {
            w.open("static boolean on" + t.variant() + "(" + t.variant() + " r, Holding held, TxInfo tx, TxInInfo own,"
                    + " byte[] policy, byte[] id, BigInteger inLovelace) {");
            w.line(S + " current = held.value();");
        } else {
            w.open("static boolean on" + t.variant() + "(" + t.variant() + " r, " + S
                    + " current, TxInfo tx, TxInInfo own, byte[] policy, byte[] id, BigInteger inLovelace) {");
        }
        List<String> checks = new ArrayList<>();
        declareParams(t);
        for (String f : t.signedBy()) {
            w.line("boolean signed_" + f + " = ContextsLib.signedBy(tx, current." + f + "());");
            checks.add("signed_" + f);
        }
        for (String f : t.validAfter()) {
            w.line("boolean after_" + f + " = startsAtOrAfter(tx.validRange(), current." + f + "());");
            checks.add("after_" + f);
        }
        for (String f : t.validBefore()) {
            w.line("boolean before_" + f + " = endsAtOrBefore(tx.validRange(), current." + f + "());");
            checks.add("before_" + f);
        }
        if (t.hasCtx()) {
            String balance = t.destroys() ? "inLovelace" : "inLovelace.subtract(MIN_ADA)";
            w.line("boolean ctxOk = ctxValid(tx, r.ctx(), " + balance + ", " + t.payable() + ");");
            checks.add("ctxOk");
        }
        authChecks(t, checks);
        if (t.shared()) {
            w.line(c.stateType() + " shared = sharedState(tx, policy, held.instance());");
        }
        String stateArg = "current";
        if (c.hasMaps() && !owned) {
            w.line("MapClaims mc = claims(tx);");
            w.line(S + " opened = openAll(current, mc, own.outRef());");
            stateArg = "opened";
        }
        w.line("JulcValue inValue = Builtins.unValueData((PlutusData) (Object) ownOutValue(own));");
        fundsLocal(t, "r", "id", "heldOf(inValue, policy, id)");
        String call = t.shared() ? X + "." + t.method() + "(" + args("r", t, stateArg, "shared") + ")"
                : X + "." + t.method() + "(" + args("r", t, stateArg) + ")";
        java.util.function.UnaryOperator<String> datum = owned
                ? v -> "(PlutusData) (Object) new Holding(held.instance(), " + v + ")"
                : v -> "(PlutusData) (Object) " + v;
        String next = "Builtins.iData(0L)";
        String payouts = "JulcList.empty()";
        String sends = "JulcList.empty()";
        String mint = "noAssets()";
        String accepted = "noAssets()";
        switch (t.returns()) {
            case STATE -> {
                w.line(S + " next = " + (owned ? call : committed(call)) + ";");
                next = datum.apply("next");
            }
            case PAYOUTS -> {
                w.line("JulcList<Payout> payouts = " + call + ";");
                payouts = "payouts";
            }
            case RESULT, SETTLE -> {
                w.line(t.resultType() + " result = " + call + ";");
                if (t.returns() == TransitionIR.Returns.RESULT) {
                    w.line(S + " next = " + (owned ? "result.state()" : committed("result.state()")) + ";");
                    next = datum.apply("next");
                }
                if (t.resultPayouts()) payouts = "result.payouts()";
                if (t.resultSends()) sends = "result.sends()";
                if (t.resultMint()) mint = "result.mint()";
                if (t.resultAccepted()) accepted = "result.accepted()";
            }
        }
        if (!t.destroys()) outputChecks(owned, "next", checks);
        if (t.returnsRecord()) eventsCheck(t, checks);
        if (!t.destroys() && next.equals("Builtins.iData(0L)")) {
            throw new IllegalStateException(t.method() + ": a continuing transition without a next state");
        }
        // One shared settlement helper for every transition: the value equation, payouts, sends, mint (ADR-0008).
        var settleArgs = new ArrayList<String>(List.of("tx", "own", "policy", "id", "inValue", next, String.valueOf(t.destroys()),
                received("r", t), payouts));
        if (c.hasSends()) settleArgs.add(sends);
        if (c.hasMints()) {
            settleArgs.add(mint);
            settleArgs.add(String.valueOf(t.resultMint()));
        }
        if (anyAccepts()) {
            settleArgs.add(accepted);
            settleArgs.add(String.valueOf(t.resultAccepted()));
            settleArgs.add(t.resultAccepted() ? "r.assetsIn()" : "Builtins.iData(0L)");
        }
        checks.add("settle(" + String.join(", ", settleArgs) + ")");
        w.line("return " + conj(checks, t.variant()) + ";");
        w.close("}").blank();
    }

    /** Some transition returns {@code accepted} (ADR-0007 r2). */
    private boolean anyAccepts() {
        return c.allTransitions().stream().anyMatch(TransitionIR::resultAccepted);
    }

    /** The shared settlement of an asset-aware transition (I26, I28, I29, I31). */
    private void settleHelper() {
        var params = new ArrayList<String>(List.of("TxInfo tx", "TxInInfo own", "byte[] policy", "byte[] id", "JulcValue inValue",
                "PlutusData next", "boolean destroy", "BigInteger received", "JulcList<Payout> payouts"));
        if (c.hasSends()) params.add("JulcList<Send> sends");
        if (c.hasMints()) {
            params.add("Value mint");
            params.add("boolean mints");
        }
        if (anyAccepts()) {
            params.add("Value accepted");
            params.add("boolean accepts");
            params.add("PlutusData assetsIn");
        }
        w.line("/**");
        w.line(" * Settles a transition: out = in + received + accepted + mint - payouts - sends (I26), or for a destroy exactly");
        w.line(" * the burned token; payouts tagged (I7); sends claimed (I29); the user mint exact (I28); accepted == offered (I31).");
        w.line(" */");
        w.open("static boolean settle(" + String.join(", ", params) + ") {");
        if (anyAccepts()) {
            w.line("JulcValue acceptedV = accepts ? methodValue(accepted, policy, 1L) : noValue();");
            w.line("boolean acceptedOk = !accepts || Builtins.equalsData(Builtins.valueData(acceptedV), assetsIn);");
        } else {
            w.line("JulcValue acceptedV = noValue();");
            w.line("boolean acceptedOk = true;");
        }
        w.line("JulcValue mintV = " + (c.hasMints() ? "mints ? methodValue(mint, policy, 2L) : noValue()" : "noValue()") + ";");
        w.line("JulcValue sentV = " + (c.hasSends() ? "sendsTotal(sends, policy)" : "noValue()") + ";");
        w.line("JulcValue expected = Builtins.unionValue(inValue, Builtins.unionValue(lovelaceValue(received.subtract(Payouts.total(payouts))),");
        w.line("        Builtins.unionValue(acceptedV, Builtins.unionValue(mintV, Builtins.scaleValue(BigInteger.valueOf(-1), sentV)))));");
        w.line("boolean valueOk = destroy");
        w.line("        ? Builtins.equalsData(Builtins.valueData(expected), Builtins.valueData(Builtins.insertCoin(policy, id, BigInteger.ONE, noValue())))");
        w.line("          && ValuesLib.assetOf(tx.mint(), policy, id).compareTo(BigInteger.valueOf(-1)) == 0");
        w.line("        : continuesValue(tx, own, policy, id, next, expected);");
        w.line("boolean paid = payouts.isEmpty() || payoutsPaid(tx.outputs(), own, payouts);");
        w.line("boolean sent = " + (c.hasSends() ? "sendsClaimed(tx, own.outRef(), sends, policy)" : "true") + ";");
        w.line("boolean minted = " + (c.hasMints() ? "!mints || mintMatches(tx, policy, own.outRef(), mintV, destroy, id)" : "true") + ";");
        w.line("return " + conj(List.of("acceptedOk", "valueOk", "paid", "sent", "minted"), "settle") + ";");
        w.close("}").blank();
    }

    private void assetHelpers() {
        w.line("// ---- native assets (ADR-0007) ---------------------------------------------------------------");
        settleHelper();
        w.line("/** Reserved token kinds: 32 bytes (instance), 29 bytes with 0x4F (object) or 0x49 (intent) (I27). */");
        w.open("static boolean isReserved(byte[] n) {");
        w.line("long len = ByteStringLib.length(n);");
        w.line("return len == 32 || (len == 29 && (ByteStringLib.at(n, 0L) == 79L || ByteStringLib.at(n, 0L) == 73L));");
        w.close("}").blank();

        w.line("/** The single reserved token (quantity 1) under this policy in a state UTxO; user tokens are ignored (I27). */");
        w.open("static byte[] lifecycleTokenOf(Value v, byte[] policy) {");
        w.line("PlutusData rest = Builtins.unMapData(ledgerTokensOf(v, policy));");
        w.line("long entries = 0;");
        w.line("byte[] name = ByteStringLib.empty();");
        w.line("boolean ok = true;");
        w.open("while (!Builtins.nullList(rest)) {");
        w.line("PlutusData pair = Builtins.headList(rest);");
        w.line("byte[] n = Builtins.unBData(Builtins.fstPair(pair));");
        w.open("if (isReserved(n)) {");
        w.line("entries = entries + 1;");
        w.line("name = n;");
        w.line("ok = ok && Builtins.unIData(Builtins.sndPair(pair)).compareTo(BigInteger.ONE) == 0;");
        w.close("}");
        w.line("rest = Builtins.tailList(rest);");
        w.close("}");
        w.open("if (!ok || entries != 1) {");
        w.line("Builtins.error();");
        w.line("return name;");
        w.close("} else {");
        w.indent();
        w.line("return name;");
        w.close("}");
        w.close("}").blank();

        w.open("static Value ownOutValue(TxInInfo own) {");
        w.line("return own.resolved().value();");
        w.close("}").blank();

        w.open("static JulcValue noValue() {");
        w.line("return Builtins.unValueData(Builtins.mapData(Builtins.mkNilPairData()));");
        w.close("}").blank();

        w.open("static Value noAssets() {");
        w.line("return (Value) (Object) Builtins.mapData(Builtins.mkNilPairData());");
        w.close("}").blank();

        w.open("static JulcValue lovelaceValue(BigInteger q) {");
        w.line("byte[] ada = ByteStringLib.empty();");
        w.line("return Builtins.insertCoin(ada, ada, q, noValue());");
        w.close("}").blank();

        w.line("/** The native assets of a UTxO value, without lovelace and without its own token (Funds.held). */");
        w.open("static Value heldOf(JulcValue v, byte[] policy, byte[] id) {");
        w.line("byte[] ada = ByteStringLib.empty();");
        w.line("return (Value) (Object) Builtins.valueData(Builtins.insertCoin(policy, id, BigInteger.ZERO,");
        w.line("        Builtins.insertCoin(ada, ada, BigInteger.ZERO, v)));");
        w.close("}").blank();

        w.line("/**");
        w.line(" * A value a method computed, as a native value: folded entry by entry, so any order and zero entries are fine.");
        w.line(" * Mode 0 (sends): no negative amounts. Mode 1 (accepted): no negative amounts, no lovelace. Mode 2 (mint):");
        w.line(" * only this policy, user names. Never a reserved name of this policy (I31).");
        w.line(" */");
        w.open("static JulcValue methodValue(Value v, byte[] policy, long mode) {");
        w.line("return foldPolicies(Builtins.unMapData((PlutusData) (Object) v), noValue(), policy, mode);");
        w.close("}").blank();

        w.line("/** Folds each policy's tokens into {@code acc} (recursive: a native value cannot be a loop accumulator). */");
        w.open("static JulcValue foldPolicies(PlutusData ps, JulcValue acc, byte[] policy, long mode) {");
        w.open("if (Builtins.nullList(ps)) {");
        w.line("return acc;");
        w.close("} else {");
        w.indent();
        w.line("PlutusData pair = Builtins.headList(ps);");
        w.line("byte[] p = Builtins.unBData(Builtins.fstPair(pair));");
        w.line("boolean own = ByteStringLib.equals(p, policy);");
        w.line("boolean ada = ByteStringLib.length(p) == 0;");
        w.line("// An empty token map adds nothing, and is accepted as before (ADR-0008 review).");
        w.line("boolean empty = Builtins.nullList(Builtins.unMapData(Builtins.sndPair(pair)));");
        w.open("if (!empty && ((mode == 1L && ada) || (mode == 2L && !own))) {");
        w.line("Builtins.error();");
        w.line("return acc;");
        w.close("} else {");
        w.indent();
        w.line("return foldPolicies(Builtins.tailList(ps), foldTokens(Builtins.unMapData(Builtins.sndPair(pair)), p, own, acc, mode),"
                + " policy, mode);");
        w.close("}");
        w.close("}");
        w.close("}").blank();

        w.open("static JulcValue foldTokens(PlutusData ts, byte[] p, boolean own, JulcValue acc, long mode) {");
        w.open("if (Builtins.nullList(ts)) {");
        w.line("return acc;");
        w.close("} else {");
        w.indent();
        w.line("PlutusData pair = Builtins.headList(ts);");
        w.line("byte[] n = Builtins.unBData(Builtins.fstPair(pair));");
        w.line("BigInteger q = Builtins.unIData(Builtins.sndPair(pair));");
        w.open("if ((own && isReserved(n)) || (mode != 2L && q.compareTo(BigInteger.ZERO) < 0)) {");
        w.line("Builtins.error();");
        w.line("return acc;");
        w.close("} else {");
        w.indent();
        w.line("return foldTokens(Builtins.tailList(ts), p, own, Builtins.insertCoin(p, n, q.add(Builtins.lookupCoin(p, n, acc)), acc),"
                + " mode);");
        w.close("}");
        w.close("}");
        w.close("}").blank();

        w.line("/** The unique continuing output: same address, inline datum == next, value exactly {@code expected} (I26). */");
        w.open("static boolean continuesValue(TxInfo tx, TxInInfo own, byte[] policy, byte[] id, PlutusData next,"
                + " JulcValue expected) {");
        w.line("TxOut out = uniqueOutputWith(tx.outputs(), policy, id);");
        w.line("boolean sameAddress = Builtins.equalsData(out.address(), own.resolved().address());");
        w.line("boolean datumOk = Builtins.equalsData(OutputLib.getInlineDatum(out), next);");
        w.line("boolean noRefScript = out.referenceScript().isEmpty();");
        w.line("boolean fits = datumFits(next);");
        w.line("byte[] ada = ByteStringLib.empty();");
        w.line("boolean deposit = Builtins.lookupCoin(ada, ada, expected).compareTo(MIN_ADA) >= 0;");
        w.line("boolean valueOk = Builtins.equalsData(Builtins.valueData(expected), (PlutusData) (Object) out.value());");
        w.line("return sameAddress && datumOk && noRefScript && fits && deposit && valueOk;");
        w.close("}").blank();

        if (c.hasSends()) {
            w.open("static JulcValue sendsTotal(JulcList<Send> sends, byte[] policy) {");
            w.open("if (sends.isEmpty()) {");
            w.line("return noValue();");
            w.close("} else {");
            w.indent();
            w.line("return Builtins.unionValue(methodValue(sends.head().value(), policy, 0L), sendsTotal(sends.tail(), policy));");
            w.close("}");
            w.close("}").blank();

            w.line("/** Output {@code index}, by an explicit walk; fails if there is none (ADR-0007 r2). */");
            w.open("static TxOut outputAt(JulcList<TxOut> outputs, BigInteger index) {");
            w.line("TxOut hit = outputs.head();");
            w.line("boolean found = false;");
            w.line("BigInteger k = BigInteger.ZERO;");
            w.open("for (var o : outputs) {");
            w.open("if (k.compareTo(index) == 0) {");
            w.line("hit = o;");
            w.line("found = true;");
            w.close("}");
            w.line("k = k.add(BigInteger.ONE);");
            w.close("}");
            w.open("if (!found) {");
            w.line("Builtins.error();");
            w.line("return hit;");
            w.close("} else {");
            w.indent();
            w.line("return hit;");
            w.close("}");
            w.close("}").blank();

            w.line("/** A JOUST payout or refund tag: the reference of input {@code ref}, or a refund naming it (I30). */");
            w.open("static boolean isTag(OutputDatum d, TxOutRef ref) {");
            w.open("return switch (d) {");
            w.line("case OutputDatum.OutputDatumInline i -> Builtins.equalsData(i.datum(), (PlutusData) (Object) ref)");
            w.line("        || Builtins.equalsData(i.datum(), (PlutusData) (Object) new RefTag(ref));");
            w.line("case OutputDatum.NoOutputDatum n -> false;");
            w.line("case OutputDatum.OutputDatumHash h -> false;");
            w.close("};");
            w.close("}").blank();

            w.line("/**");
            w.line(" * Output {@code o} delivers send {@code s}: exact address and datum, no reference script, at least its value;");
            w.line(" * not at a spent script's credential (so never a continuing output) and never a JOUST tag (I30).");
            w.line(" */");
            w.open("static boolean sendMatches(TxInfo tx, TxOut o, Send s, byte[] policy) {");
            w.line("boolean shape = Builtins.equalsData(o.address(), s.to()) && Builtins.equalsData(o.datum(), s.datum())");
            w.line("        && o.referenceScript().isEmpty();");
            w.line("boolean covers = Builtins.valueContains(Builtins.unValueData((PlutusData) (Object) o.value()),");
            w.line("        methodValue(s.value(), policy, 0L));");
            w.line("boolean script = AddressLib.isScriptAddress(o.address());");
            w.line("boolean fresh = true;");
            w.open("for (var in : tx.inputs()) {");
            w.open("if ((script && Builtins.equalsData(in.resolved().address().credential(), o.address().credential()))"
                    + " || isTag(o.datum(), in.outRef())) {");
            w.line("fresh = false;");
            w.close("}");
            w.close("}");
            w.line("return shape && covers && fresh;");
            w.close("}").blank();

            w.line("/** Every send is delivered by an output claimed for {@code by} in the output registry, in order (I29). */");
            w.open("static boolean sendsClaimed(TxInfo tx, TxOutRef by, JulcList<Send> sends, byte[] policy) {");
            w.line("return sends.isEmpty() || claimedAll(tx, by, sends, policy);");
            w.close("}").blank();
            w.open("static boolean claimedAll(TxInfo tx, TxOutRef by, JulcList<Send> sends, byte[] policy) {");
            w.line("ScriptPurpose purpose = new ScriptPurpose.Rewarding(new Credential.ScriptCredential(new ScriptHash(OUTPUT_VERIFIER)));");
            w.line("OutputClaims oc = (OutputClaims) (Object) tx.redeemers().get(purpose);");
            w.line("JulcList<Send> rest = sends;");
            w.line("boolean ok = true;");
            w.open("for (var cl : oc.claims()) {");
            w.open("if (Builtins.equalsData(cl.by(), by)) {");
            w.open("if (rest.isEmpty()) {");
            w.line("ok = false;");
            w.close("} else {");
            w.indent();
            w.line("ok = ok && sendMatches(tx, outputAt(tx.outputs(), cl.output()), rest.head(), policy);");
            w.line("rest = rest.tail();");
            w.close("}");
            w.close("}");
            w.close("}");
            w.line("return ok && rest.isEmpty();");
            w.close("}").blank();
        }

        if (c.hasMints()) {
            w.line("/**");
            w.line(" * The user mint: if the mint redeemer is ByAction(own), the policy's whole mint equals the declared mint (and");
            w.line(" * the own burn for a destroy); otherwise the method declared none (I28).");
            w.line(" */");
            w.open("static boolean mintMatches(TxInfo tx, byte[] policy, TxOutRef own, JulcValue declared, boolean destroy,"
                    + " byte[] id) {");
            w.line("PlutusData.MapData minted = ValuesLib._tokensOf(Builtins.unMapData((PlutusData) (Object) tx.mint()),"
                    + " Builtins.bData(policy));");
            w.line("boolean any = !Builtins.nullList(Builtins.unMapData(minted));");
            w.line("boolean byOwn = any && Builtins.equalsData(tx.redeemers().get(new ScriptPurpose.Minting(new PolicyId(policy))),");
            w.line("        (PlutusData) (Object) new ByAction(own));");
            w.line("JulcValue expected = destroy ? Builtins.insertCoin(policy, id, BigInteger.valueOf(-1), declared) : declared;");
            w.line("PlutusData single = Builtins.mapData(Builtins.mkCons(Builtins.mkPairData(Builtins.bData(policy), minted),"
                    + " Builtins.mkNilPairData()));");
            w.line("boolean exact = Builtins.equalsData(Builtins.valueData(expected), single);");
            w.line("boolean none = Builtins.nullList(Builtins.unMapData(Builtins.valueData(declared)));");
            w.line("return byOwn ? exact : none;");
            w.close("}").blank();
        }
    }

    /** {@code expr} (the method's next state) with each MerkleMap committed to its new root (I16, I17). */
    private String committed(String expr) {
        if (!c.hasMaps()) return expr;
        return "commitAll(current, " + expr + ", mc, own.outRef())";
    }

    private String S() {
        return c.stateType();
    }

    private void ownedHelpers() {
        w.line("/** An object's token name: 0x4F ++ blake2b_224(seed ref), 29 bytes, never an instance id (ADR-0003 D2). */");
        w.open("static byte[] objectId(TxOutRef seed) {");
        w.line("return ByteStringLib.cons(79L, CryptoLib.blake2b_224(ValuesLib.refBytes(seed)));");
        w.close("}").blank();
        w.open("static boolean isObjectToken(byte[] id) {");
        w.line("return ByteStringLib.length(id) == 29 && ByteStringLib.at(id, 0L) == 79L;");
        w.close("}").blank();
        w.line("/**");
        w.line(" * The state of instance {@code instance}: the reference input holding its token at this script's address (I20).");
        w.line(" */");
        w.open("static " + c.stateType() + " sharedState(TxInfo tx, byte[] policy, byte[] instance) {");
        w.line("boolean found = !isInstanceToken(instance);   // only a 32-byte instance id names state (ADR-0003 r2)");
        w.open("if (found) {");
        w.line("Builtins.error();");
        w.close("}");
        w.line("TxOut hit = tx.outputs().head();");
        w.open("for (var ref : tx.referenceInputs()) {");
        w.line("TxOut o = ref.resolved();");
        w.open("if (!found && ValuesLib.assetOf(o.value(), policy, instance).compareTo(BigInteger.ONE) == 0 && isStateAddress(o.address(), policy)) {");
        w.line("found = true;");
        w.line("hit = o;");
        w.close("}");
        w.close("}");
        w.open("if (!found) {");
        w.line("Builtins.error();");
        w.close("}");
        w.line("return (" + c.stateType() + ") (Object) OutputLib.getInlineDatum(hit);");
        w.close("}").blank();
    }

    private void helpers() {
        if (traces) {
            w.open("static boolean check(boolean ok, String what) {");
            w.open("if (!ok) {");
            w.line("ContextsLib.trace(what);");
            w.line("return false;");
            w.close("} else {");
            w.indent();
            w.line("return true;");
            w.close("}");
            w.close("}").blank();
        }
        w.open("static boolean isInstanceToken(byte[] id) {");
        w.line("return ByteStringLib.length(id) == 32;");
        w.close("}").blank();
        if (c.hasOwned()) ownedHelpers();
        if (c.assetAware()) assetHelpers();
        authHelpers();
        readsHelpers();
        if (c.hasMaps()) {
            String S = c.stateType();
            w.line("/** The state a method sees: each MerkleMap opened with its proven read set (I15). */");
            w.open("static " + S + " openAll(" + S + " current, MapClaims mc, TxOutRef owner) {");
            w.line("return " + rebuild("current", (i, a) -> "MapWitnesses.open(" + a + ".root(), MapWitnesses.claimFor(mc, owner, "
                    + i + "L))") + ";");
            w.close("}").blank();
            w.line("/** The state to store: each MerkleMap of {@code raw} committed from {@code current}'s root (I16, I17). */");
            w.open("static " + S + " commitAll(" + S + " current, " + S + " raw, MapClaims mc, TxOutRef owner) {");
            w.line("return " + rebuild("raw", (i, a) -> "stored(MapWitnesses.commit(current."
                    + c.stateComponents().get(c.mapComponents().get(i)).name() + "().root(), " + a
                    + ", MapWitnesses.claimFor(mc, owner, " + i + "L)))") + ";");
            w.close("}").blank();
            w.line("/** Every MerkleMap claim of this transaction: the map verifier's redeemer (its presence means it ran). */");
            w.open("static MapClaims claims(TxInfo tx) {");
            w.line("ScriptPurpose purpose = new ScriptPurpose.Rewarding(new Credential.ScriptCredential(new ScriptHash(MAP_VERIFIER)));");
            w.line("return (MapClaims) (Object) tx.redeemers().get(purpose);");
            w.close("}").blank();
            w.line("/** A MerkleMap as the datum stores it: the root only (I17). */");
            w.open("static MerkleMap stored(byte[] root) {");
            w.line("JulcList<MapEntry> none = JulcList.empty();");
            w.line("return new MerkleMap(root, none, none);");
            w.close("}").blank();
        }
        w.line("// ---- generated helpers ---------------------------------------------------------------------");
        w.blank();
        w.open("static boolean seedConsumed(JulcList<TxInInfo> inputs, TxOutRef seed) {");
        w.line("boolean found = false;");
        w.open("for (var in : inputs) {");
        w.open("if (Builtins.equalsData(in.outRef(), seed)) {");
        w.line("found = true;");
        w.close("}");
        w.close("}");
        w.line("return found;");
        w.close("}").blank();

        w.line("/**");
        w.line(" * The token map of {@code policy} in a value (empty if absent): one pass over the policies (ADR-0008). Takes the");
        w.line(" * first entry of the policy, so only for ledger values (tx.mint(), an input's or output's value), which have no");
        w.line(" * repeated policy; never for a value a method or redeemer supplies.");
        w.line(" */");
        w.open("static PlutusData.MapData ledgerTokensOf(Value v, byte[] policy) {");
        w.line("return ValuesLib._tokensOf(Builtins.unMapData((PlutusData) (Object) v), Builtins.bData(policy));");
        w.close("}").blank();

        w.line("/** Under this policy the transaction mints exactly (id, 1) and nothing else (I1). */");
        w.open("static boolean mintsExactlyOne(Value mint, byte[] policy, byte[] id) {");
        w.line("PlutusData expected = Builtins.mapData(Builtins.mkCons(Builtins.mkPairData(Builtins.bData(id), Builtins.iData(1L)),"
                + " Builtins.mkNilPairData()));");
        w.line("return Builtins.equalsData(ledgerTokensOf(mint, policy), expected);");
        w.close("}").blank();

        w.line("/** Under this policy the transaction only burns, one token per name (I9). */");
        w.open("static boolean onlyBurns(Value mint, byte[] policy) {");
        w.line("PlutusData tokens = Builtins.unMapData(ledgerTokensOf(mint, policy));");
        w.line("boolean ok = !Builtins.nullList(tokens);");
        w.line("PlutusData rest = tokens;");
        w.open("while (!Builtins.nullList(rest)) {");
        w.line("PlutusData pair = Builtins.headList(rest);");
        w.open("if (Builtins.unIData(Builtins.sndPair(pair)).compareTo(BigInteger.valueOf(-1)) != 0"
                + (c.assetAware() ? " || !isReserved(Builtins.unBData(Builtins.fstPair(pair)))" : "") + ") {");
        w.line("ok = false;");
        w.close("}");
        w.line("rest = Builtins.tailList(rest);");
        w.close("}");
        w.line("return ok;");
        w.close("}").blank();

        w.line("/** The name of the single thread token (quantity 1) under this policy in a state UTxO; fails otherwise. */");
        w.open("static byte[] instanceIdOf(Value v, byte[] policy) {");
        w.line("PlutusData tokens = Builtins.unMapData(ledgerTokensOf(v, policy));");
        w.open("if (Builtins.nullList(tokens)) {");
        w.line("Builtins.error();");
        w.line("return ByteStringLib.empty();");
        w.close("} else {");
        w.indent();
        w.line("PlutusData pair = Builtins.headList(tokens);");
        w.open("if (!Builtins.nullList(Builtins.tailList(tokens)) || Builtins.unIData(Builtins.sndPair(pair)).compareTo(BigInteger.ONE) != 0) {");
        w.line("Builtins.error();");
        w.line("return ByteStringLib.empty();");
        w.close("} else {");
        w.indent();
        w.line("return Builtins.unBData(Builtins.fstPair(pair));");
        w.close("}");
        w.close("}");
        w.close("}").blank();

        w.line("/** Exactly one output carries the thread token; returns it (fails otherwise). One pass, no list built. */");
        w.open("static TxOut uniqueOutputWith(JulcList<TxOut> outputs, byte[] policy, byte[] id) {");
        w.line("PlutusData.BytesData p = Builtins.bData(policy);");
        w.line("PlutusData.BytesData n = Builtins.bData(id);");
        w.line("long count = 0;");
        w.line("TxOut hit = outputs.head();");
        w.open("for (var o : outputs) {");
        w.open("if (ValuesLib._assetOf(o.value(), p, n).compareTo(BigInteger.ZERO) > 0) {");
        w.line("count = count + 1;");
        w.line("hit = o;");
        w.close("}");
        w.close("}");
        w.open("if (count != 1) {");
        w.line("Builtins.error();");
        w.line("return hit;");
        w.close("} else {");
        w.indent();
        w.line("return hit;");
        w.close("}");
        w.close("}").blank();

        if (c.stakeSelf()) {
            w.line("/** This script's payment credential, staked to this script: one address for all instances (I36). */");
            w.open("static boolean isStateAddress(Address address, byte[] policy) {");
            w.line("Optional<StakingCredential> self = Optional.of(new StakingCredential.StakingHash(");
            w.line("        new Credential.ScriptCredential(new ScriptHash(policy))));");
            w.line("return AddressLib.isScriptAddress(address) && ByteStringLib.equals(AddressLib.credentialHash(address), policy)");
            w.line("        && Builtins.equalsData((PlutusData) (Object) address.stakingCredential(), (PlutusData) (Object) self);");
            w.close("}").blank();
        } else {
            w.line("/** This script's payment credential and no stake credential: one address for all instances (I12). */");
            w.open("static boolean isStateAddress(Address address, byte[] policy) {");
            w.line("return AddressLib.isScriptAddress(address) && ByteStringLib.equals(AddressLib.credentialHash(address), policy)");
            w.line("        && address.stakingCredential().isEmpty();");
            w.close("}").blank();
        }

        w.line("/** The serialised state is at most MAX_DATUM_BYTES (I13). */");
        w.open("static boolean datumFits(PlutusData d) {");
        w.line("return BigInteger.valueOf(Builtins.lengthOfByteString(Builtins.serialiseData(d))).compareTo(MAX_DATUM_BYTES) <= 0;");
        w.close("}").blank();

        w.line("/**");
        w.line(" * The value is exactly {lovelace, policy.id: 1}: no token dust, no skim (I3). Ledger values are canonical (lovelace");
        w.line(" * first, under the empty policy), so this is one Data comparison (ADR-0008).");
        w.line(" */");
        w.open("static boolean exactValue(Value v, byte[] policy, byte[] id, BigInteger lovelace) {");
        w.line("PlutusData none = Builtins.mkNilPairData();");
        w.line("PlutusData ada = Builtins.mkPairData(Builtins.bData(ByteStringLib.empty()), Builtins.mapData(Builtins.mkCons("
                + "Builtins.mkPairData(Builtins.bData(ByteStringLib.empty()), Builtins.iData(lovelace)), none)));");
        w.line("PlutusData thread = Builtins.mkPairData(Builtins.bData(policy), Builtins.mapData(Builtins.mkCons("
                + "Builtins.mkPairData(Builtins.bData(id), Builtins.iData(1L)), none)));");
        w.line("return Builtins.equalsData((PlutusData) (Object) v, Builtins.mapData(Builtins.mkCons(ada, Builtins.mkCons(thread, none))));");
        w.close("}").blank();

        w.line("/** The unique continuing output: same address, inline datum == next, exact value (I3, I4). */");
        w.open("static boolean continues(TxInfo tx, TxInInfo own, byte[] policy, byte[] id, PlutusData next, BigInteger lovelace) {");
        w.line("TxOut out = uniqueOutputWith(tx.outputs(), policy, id);");
        w.line("boolean sameAddress = Builtins.equalsData(out.address(), own.resolved().address());");
        w.line("boolean datumOk = Builtins.equalsData(OutputLib.getInlineDatum(out), next);");
        w.line("boolean noRefScript = out.referenceScript().isEmpty();");
        w.line("boolean fits = datumFits(next);");
        w.line("// The deposit is never spent by an action (I14), and the value is exact (I3).");
        w.line("boolean valueOk = lovelace.compareTo(MIN_ADA) >= 0 && exactValue(out.value(), policy, id, lovelace);");
        w.line("return sameAddress && datumOk && noRefScript && fits && valueOk;");
        w.close("}").blank();

        w.line("/** Every Ctx field matches the transaction (I5, I6). */");
        w.open("static boolean ctxValid(TxInfo tx, Ctx cx, BigInteger balance, boolean payable) {");
        w.line("boolean signed = ContextsLib.signedBy(tx, cx.caller());");
        w.line("boolean receivedOk = payable ? cx.received().compareTo(BigInteger.ZERO) >= 0");
        w.line("        : cx.received().compareTo(BigInteger.ZERO) == 0;");
        w.line("boolean balanceOk = cx.balance().compareTo(balance) == 0;");
        w.line("// validFrom/validTo equal the transaction's finite bounds, and the interval is narrow (I6).");
        w.line("boolean fromOk = startsAt(tx.validRange(), cx.validFrom());");
        w.line("boolean toOk = endsAt(tx.validRange(), cx.validTo());");
        w.line("boolean narrow = cx.validTo().subtract(cx.validFrom()).compareTo(MAX_VALIDITY_MS) <= 0;");
        w.line("return signed && receivedOk && balanceOk && fromOk && toOk && narrow;");
        w.close("}").blank();

        w.line("/** Finite, inclusive lower bound equal to t (Conway: lower bounds are closed). */");
        w.open("static boolean startsAt(Interval range, BigInteger t) {");
        w.open("return switch (range.from().boundType()) {");
        w.line("case IntervalBoundType.Finite f -> f.time().compareTo(t) == 0 && range.from().isInclusive();");
        w.line("case IntervalBoundType.NegInf n -> false;");
        w.line("case IntervalBoundType.PosInf p -> false;");
        w.close("};");
        w.close("}").blank();

        w.line("/** Finite, exclusive upper bound equal to t (Conway: invalidHereafter is open). */");
        w.open("static boolean endsAt(Interval range, BigInteger t) {");
        w.open("return switch (range.to().boundType()) {");
        w.line("case IntervalBoundType.Finite f -> f.time().compareTo(t) == 0 && !range.to().isInclusive();");
        w.line("case IntervalBoundType.NegInf n -> false;");
        w.line("case IntervalBoundType.PosInf p -> false;");
        w.close("};");
        w.close("}").blank();

        w.line("/** The validity interval has a finite lower bound, at or after t. */");
        w.open("static boolean startsAtOrAfter(Interval range, BigInteger t) {");
        w.open("return switch (range.from().boundType()) {");
        w.line("case IntervalBoundType.Finite f -> f.time().compareTo(t) >= 0;");
        w.line("case IntervalBoundType.NegInf n -> false;");
        w.line("case IntervalBoundType.PosInf p -> false;");
        w.close("};");
        w.close("}").blank();

        w.line("/** The validity interval has a finite upper bound, at or before t. An unbounded end never passes. */");
        w.open("static boolean endsAtOrBefore(Interval range, BigInteger t) {");
        w.open("return switch (range.to().boundType()) {");
        w.line("case IntervalBoundType.Finite f -> f.time().compareTo(t) <= 0;");
        w.line("case IntervalBoundType.NegInf n -> false;");
        w.line("case IntervalBoundType.PosInf p -> false;");
        w.close("};");
        w.close("}").blank();

        w.line("/**");
        w.line(" * Each recipient receives at least what the payouts owe it, in outputs to its key whose inline datum is this");
        w.line(" * instance's input reference. The tag stops one output from satisfying two instances (I7).");
        w.line(" */");
        w.open("static boolean payoutsPaid(JulcList<TxOut> outputs, TxInInfo own, JulcList<Payout> payouts) {");
        w.line("return payoutsTagged(outputs, (PlutusData) (Object) own.outRef(), payouts);");
        w.close("}").blank();
        w.line("/** Each recipient receives what {@code payouts} owe it, in outputs to its key tagged with {@code tag}. */");
        w.open("static boolean payoutsTagged(JulcList<TxOut> outputs, PlutusData tag, JulcList<Payout> payouts) {");
        w.line("boolean ok = true;");
        w.open("for (var p : payouts) {");
        w.line("BigInteger owed = BigInteger.ZERO;");
        w.open("for (var q : payouts) {");
        w.open("if (ByteStringLib.equals(q.to(), p.to())) {");
        w.line("owed = owed.add(q.lovelace());");
        w.close("}");
        w.close("}");
        w.line("BigInteger paid = BigInteger.ZERO;");
        w.open("for (var o : outputs) {");
        w.open("if (paysKey(o, p.to()) && taggedWith(o, tag)) {");
        w.line("paid = paid.add(ValuesLib.lovelaceOf(o.value()));");
        w.close("}");
        w.close("}");
        w.open("if (p.lovelace().compareTo(BigInteger.ZERO) <= 0 || Builtins.lengthOfByteString(p.to()) != 28"
                + " || paid.compareTo(owed) < 0) {");
        w.line("ok = false;");
        w.close("}");
        w.close("}");
        w.line("return ok;");
        w.close("}").blank();

        w.open("static boolean paysKey(TxOut o, byte[] pkh) {");
        w.open("return switch (o.address().credential()) {");
        w.line("case Credential.PubKeyCredential pk -> ByteStringLib.equals((byte[]) (Object) pk.hash(), pkh);");
        w.line("case Credential.ScriptCredential sc -> false;");
        w.close("};");
        w.close("}").blank();

        w.open("static boolean taggedWith(TxOut o, PlutusData tag) {");
        w.open("return switch (o.datum()) {");
        w.line("case OutputDatum.OutputDatumInline d -> Builtins.equalsData(d.datum(), tag);");
        w.line("case OutputDatum.NoOutputDatum n -> false;");
        w.line("case OutputDatum.OutputDatumHash h -> false;");
        w.close("};");
        w.close("}");
    }

    // ------------------------------------------------------------------ text helpers

    /** Redeemer record components: the method's arguments, then {@code Ctx ctx}, then the map witnesses. */
    List<String> fields(TransitionIR t) {
        var out = new ArrayList<String>();
        for (ParamIR p : t.params()) out.add(p.type() + " " + p.name());
        if (t.hasCtx()) out.add("Ctx ctx");
        // PlutusData, not Value: JuLC decodes a redeemer field typed Value as a constructor (julc issue); cast on use.
        if (takesAssets(t)) out.add("PlutusData assetsIn");
        if (t.resultEvents()) out.add(t.eventsType() + " events");
        return out;
    }

    /** The redeemer carries the native assets the caller offers: @Payable methods with Funds (ADR-0007 r2). */
    static boolean takesAssets(TransitionIR t) {
        return t.payable() && t.hasFunds();
    }

    /** {@code new S(...)} from {@code var}, with each MerkleMap component replaced by {@code mapExpr(i, access)}. */
    private String rebuild(String var, java.util.function.BiFunction<Integer, String, String> mapExpr) {
        var parts = new ArrayList<String>();
        var cs = c.stateComponents();
        int mapIndex = 0;
        for (int i = 0; i < cs.size(); i++) {
            String access = var + "." + cs.get(i).name() + "()";
            if (cs.get(i).kind() == ParamIR.TypeKind.MERKLE_MAP) {
                parts.add(mapExpr.apply(mapIndex++, access));
            } else {
                parts.add(access);
            }
        }
        return "new " + c.stateType() + "(" + String.join(", ", parts) + ")";
    }

    /** Call arguments from a redeemer variable, optionally after the current state. */
    private String args(String redeemerVar, TransitionIR t, String... leading) {
        var out = new ArrayList<>(List.of(leading));
        if (t.takesParams()) out.add(paramsExpr());
        for (var rd : t.reads()) out.add(rd.name());
        for (ParamIR p : t.params()) out.add(redeemerVar + "." + p.name() + "()");
        if (t.hasCtx()) out.add(redeemerVar + ".ctx()");
        if (t.hasFunds()) out.add("funds");
        return String.join(", ", out);
    }

    private static String received(String redeemerVar, TransitionIR t) {
        return t.hasCtx() ? redeemerVar + ".ctx().received()" : "BigInteger.ZERO";
    }

    private static List<String> prepend(List<String> head, List<String> tail) {
        var out = new ArrayList<>(head);
        out.addAll(tail);
        return out;
    }

    private static String join(List<String> parts) {
        return String.join(", ", parts);
    }
}
