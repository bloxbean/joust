package org.julclang.state.processor;

import org.julclang.state.processor.ir.ContractIR;
import org.julclang.state.processor.ir.ParamIR;
import org.julclang.state.processor.ir.Strategies;
import org.julclang.state.processor.ir.TransitionIR;
import org.julclang.state.processor.ir.ViewIR;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads a {@code @StateContract} class into a {@link ContractIR}, enforcing the method shapes of ADR-0001 D2.
 * Every violation is reported as a compiler error on the offending element; {@link #read} then returns empty.
 */
final class ContractReader {

    static final String API = "org.julclang.state.api.";
    static final String STATE_CONTRACT = API + "StateContract";
    static final String INIT = API + "Init";
    static final String ACTION = API + "Action";
    static final String DESTROY = API + "Destroy";
    static final String VIEW = API + "View";
    static final String PAYABLE = API + "Payable";
    static final String SIGNED_BY = API + "SignedBy";
    static final String ONLY_OWNER = API + "OnlyOwner";
    static final String VALID_AFTER = API + "ValidAfter";
    static final String VALID_BEFORE = API + "ValidBefore";
    static final String CTX = API + "Ctx";
    static final String OWNED = API + "Owned";
    static final String OWNED_INIT = API + "OwnedInit";
    static final String OWNED_ACTION = API + "OwnedAction";
    static final String OWNED_DESTROY = API + "OwnedDestroy";
    static final String SHARED = API + "Shared";
    static final String BATCHED = API + "Batched";
    static final String PAYOUT = API + "Payout";
    static final String FUNDS = API + "Funds";
    static final String SEND = API + "Send";
    static final String VALUE = "org.julclang.ledger.Value";
    static final String ONCHAIN_LIBRARY = "org.julclang.stdlib.annotation.OnchainLibrary";
    static final String JULC_LIST = "org.julclang.core.types.JulcList";
    static final String BIG_INTEGER = "java.math.BigInteger";

    private final ProcessingEnvironment env;
    private final Messager messager;
    private boolean failed;

    ContractReader(ProcessingEnvironment env) {
        this.env = env;
        this.messager = env.getMessager();
    }

    Optional<ContractIR> read(TypeElement type) {
        failed = false;
        if (type.getNestingKind() != NestingKind.TOP_LEVEL || type.getKind() != ElementKind.CLASS) {
            error(type, "@StateContract must be on a top-level class");
            return Optional.empty();
        }
        if (!type.getModifiers().contains(Modifier.PUBLIC)) {
            error(type, "@StateContract class must be public");
        }
        String pkg = env.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        var types = new TypeRenderer(pkg);

        List<ExecutableElement> inits = new ArrayList<>();
        List<ExecutableElement> actions = new ArrayList<>();
        List<ExecutableElement> destroys = new ArrayList<>();
        List<ExecutableElement> views = new ArrayList<>();
        List<ExecutableElement> ownedInits = new ArrayList<>();
        List<ExecutableElement> ownedActions = new ArrayList<>();
        List<ExecutableElement> ownedDestroys = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ExecutableElement m : ElementFilter.methodsIn(type.getEnclosedElements())) {
            int kinds = 0;
            if (annotation(m, INIT).isPresent()) { inits.add(m); kinds++; }
            if (annotation(m, ACTION).isPresent()) { actions.add(m); kinds++; }
            if (annotation(m, DESTROY).isPresent()) { destroys.add(m); kinds++; }
            if (annotation(m, VIEW).isPresent()) { views.add(m); kinds++; }
            if (annotation(m, OWNED_INIT).isPresent()) { ownedInits.add(m); kinds++; }
            if (annotation(m, OWNED_ACTION).isPresent()) { ownedActions.add(m); kinds++; }
            if (annotation(m, OWNED_DESTROY).isPresent()) { ownedDestroys.add(m); kinds++; }
            if (kinds == 0) {
                for (String policy : List.of(PAYABLE, SIGNED_BY, ONLY_OWNER, VALID_AFTER, VALID_BEFORE, BATCHED)) {
                    if (annotation(m, policy).isPresent()) {
                        error(m, "@" + simple(policy) + " needs @Init, @Action or @Destroy on the same method");
                    }
                }
                continue;
            }
            if (kinds > 1) {
                error(m, "a method can be only one of @Init, @Action, @Destroy, @View, @OwnedInit, @OwnedAction,"
                        + " @OwnedDestroy");
                continue;
            }
            if (!m.getModifiers().contains(Modifier.PUBLIC) || !m.getModifiers().contains(Modifier.STATIC)) {
                error(m, "contract methods must be public static");
            }
            if (!m.getTypeParameters().isEmpty()) {
                error(m, "contract methods cannot be generic");
            }
            if (org.julclang.state.processor.gen.ClientGenerator.RESERVED_MEMBERS.contains(m.getSimpleName().toString())) {
                error(m, "\"" + m.getSimpleName() + "\" is a member of the generated client; rename the method");
            }
            for (var p : m.getParameters()) {
                if (org.julclang.state.processor.gen.ClientGenerator.RESERVED_PARAMS.contains(p.getSimpleName().toString())) {
                    error(p, "parameter name \"" + p.getSimpleName() + "\" is used by the generated client; rename it");
                }
            }
            if (!names.add(m.getSimpleName().toString())) {
                error(m, "contract method names must be unique (no overloading): " + m.getSimpleName());
            }
        }
        if (inits.size() != 1) {
            error(type, "a @StateContract needs exactly one @Init method, found " + inits.size());
            return Optional.empty();
        }

        ExecutableElement initMethod = inits.get(0);
        TypeMirror stateType = initMethod.getReturnType();
        TypeElement stateElement = asRecord(stateType);
        if (stateElement == null) {
            error(initMethod, "@Init must return the state record");
            return Optional.empty();
        }
        for (RecordComponentElement c : stateElement.getRecordComponents()) {
            if (!isNamed(c.asType(), API + "MerkleMap") && mentionsMap(c.asType(), new java.util.HashSet<>())) {
                error(initMethod, "state component '" + c.getSimpleName() + "' nests a MerkleMap or MapEntry; MerkleMaps"
                        + " are supported only as direct components of the state record (ADR-0002 D6)");
            }
        }
        List<ParamIR> stateComponents = new ArrayList<>();
        Map<String, ParamIR> componentsByName = new LinkedHashMap<>();
        for (RecordComponentElement c : stateElement.getRecordComponents()) {
            var p = new ParamIR(c.getSimpleName().toString(), types.render(c.asType()), kindOf(c.asType()));
            stateComponents.add(p);
            componentsByName.put(p.name(), p);
        }

        // ---- the @Owned record (ADR-0003)
        TypeElement ownedElement = null;
        for (TypeElement nested : ElementFilter.typesIn(type.getEnclosedElements())) {
            if (annotation(nested, OWNED).isPresent()) {
                if (nested.getKind() != ElementKind.RECORD) error(nested, "@Owned must be on a record");
                else if (ownedElement != null) error(nested, "at most one @Owned record per contract (ADR-0003 D1)");
                else ownedElement = nested;
            }
        }
        boolean anyOwnedMethod = !ownedInits.isEmpty() || !ownedActions.isEmpty() || !ownedDestroys.isEmpty();
        if (anyOwnedMethod && ownedElement == null) {
            error(type, "@OwnedInit/@OwnedAction/@OwnedDestroy need an @Owned record in the contract");
            return Optional.empty();
        }
        if (ownedElement != null && ownedInits.isEmpty()) {
            error(ownedElement, "an @Owned record needs at least one @OwnedInit method to create objects");
        }
        List<ParamIR> ownedComponents = new ArrayList<>();
        Map<String, ParamIR> ownedByName = new LinkedHashMap<>();
        if (ownedElement != null) {
            for (RecordComponentElement c : ownedElement.getRecordComponents()) {
                if (mentionsMap(c.asType(), new java.util.HashSet<>())) {
                    error(ownedElement, "owned records cannot hold MerkleMaps in Option C (ADR-0003 §4)");
                }
                var p = new ParamIR(c.getSimpleName().toString(), types.render(c.asType()), kindOf(c.asType()));
                ownedComponents.add(p);
                ownedByName.put(p.name(), p);
            }
        }
        TypeMirror ownedType = ownedElement == null ? null : ownedElement.asType();

        var ctx = new Reading(types, stateType, componentsByName, stateType);
        var ownedCtx = ownedElement == null ? null : new Reading(types, ownedType, ownedByName, stateType);
        TransitionIR init = readTransition(initMethod, TransitionIR.Kind.INIT, ctx);
        List<TransitionIR> actionIRs = new ArrayList<>();
        for (var m : actions) actionIRs.add(readTransition(m, TransitionIR.Kind.ACTION, ctx));
        List<TransitionIR> destroyIRs = new ArrayList<>();
        for (var m : destroys) destroyIRs.add(readTransition(m, TransitionIR.Kind.DESTROY, ctx));
        List<ViewIR> viewIRs = new ArrayList<>();
        for (var m : views) viewIRs.add(readView(m, ctx));
        List<TransitionIR> ownedInitIRs = new ArrayList<>();
        List<TransitionIR> ownedActionIRs = new ArrayList<>();
        List<TransitionIR> ownedDestroyIRs = new ArrayList<>();
        if (ownedCtx != null) {
            for (var m : ownedInits) ownedInitIRs.add(readTransition(m, TransitionIR.Kind.OWNED_INIT, ownedCtx));
            for (var m : ownedActions) ownedActionIRs.add(readTransition(m, TransitionIR.Kind.OWNED_ACTION, ownedCtx));
            for (var m : ownedDestroys) ownedDestroyIRs.add(readTransition(m, TransitionIR.Kind.OWNED_DESTROY, ownedCtx));
        }

        var sc = annotation(type, STATE_CONTRACT);
        int maxDatumBytes = sc.flatMap(a -> value(a, "maxDatumBytes"))
                .map(v -> ((Number) v.getValue()).intValue()).orElse(512);
        long maxValidityMs = sc.flatMap(a -> value(a, "maxValidityMs"))
                .map(v -> ((Number) v.getValue()).longValue()).orElse(3_600_000L);
        boolean assetAware = concat(List.of(init), actionIRs, destroyIRs, ownedInitIRs, ownedActionIRs, ownedDestroyIRs)
                .stream().anyMatch(TransitionIR::assetAware);
        int assetSlots = sc.flatMap(a -> value(a, "assetSlots"))
                .map(v -> ((Number) v.getValue()).intValue()).filter(n -> n >= 0).orElse(assetAware ? 4 : 0);
        if (assetSlots < 0 || assetSlots > 64) error(type, "@StateContract(assetSlots) must be between 0 and 64");
        long requiredMinAda = requiredMinAda(maxDatumBytes, assetSlots);
        long minAda = sc.flatMap(a -> value(a, "minAda"))
                .map(v -> ((Number) v.getValue()).longValue()).orElse(0L);
        if (maxDatumBytes < 16 || maxDatumBytes > 8192) {
            error(type, "@StateContract(maxDatumBytes) must be between 16 and 8192");
        }
        if (maxValidityMs < 60_000L) {
            error(type, "@StateContract(maxValidityMs) must be at least 60000 (one minute)");
        }
        if (minAda == 0L) {
            minAda = requiredMinAda;
        } else if (minAda < requiredMinAda) {
            error(type, "@StateContract(minAda = " + minAda + ") is below the " + requiredMinAda
                    + " lovelace a state UTxO with a " + maxDatumBytes + "-byte datum needs; raise it or lower maxDatumBytes");
        }

        String stateText = types.render(stateType);
        var reserved = new HashSet<String>(GENERATED_NAMES);
        boolean anyBatched = actionIRs.stream().anyMatch(TransitionIR::batched);
        if (anyBatched) {
            reserved.addAll(INTENT_NAMES);
            types.checkNoClash(INTENT_NAMES).forEach(msg -> error(type, msg));
            for (var t : concat(actionIRs, destroyIRs, ownedActionIRs, ownedDestroyIRs, ownedInitIRs)) {
                if (org.julclang.state.processor.gen.ClientGenerator.INTENT_MEMBERS.contains(t.method())
                        || names.contains(t.method() + "Intent") && !t.batched()) {
                    error(type, "method " + t.method() + " clashes with a member of the generated client for @Batched"
                            + " contracts; rename it");
                }
            }
        }
        reserved.add(type.getSimpleName() + "Validator");
        reserved.add(type.getSimpleName() + "Client");
        for (var t : concat(List.of(init), actionIRs, destroyIRs, ownedInitIRs, ownedActionIRs, ownedDestroyIRs)) {
            if (!reserved.add(t.variant()) && t.kind() != TransitionIR.Kind.INIT) {
                error(type, "redeemer variant name " + t.variant() + " (from method " + t.method()
                        + ") clashes with a generated name; rename the method");
            }
        }
        for (var t : actionIRs) {
            if (t.batched() && !reserved.add(t.intentVariant())) {
                error(type, "intent variant name " + t.intentVariant() + " (from method " + t.method()
                        + ") clashes with a generated name; rename the method");
            }
        }
        types.checkNoClash(GENERATED_NAMES).forEach(msg -> error(type, msg));

        boolean anyMap = stateComponents.stream().anyMatch(p -> p.kind() == ParamIR.TypeKind.MERKLE_MAP);
        if (anyMap && !destroyIRs.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.WARNING, "a @Destroy discards every MerkleMap's contents: check"
                    + " MerkleMaps.isEmpty(...) in its rules unless losing them is intended (ADR-0002 r2)", type);
        }
        if (ownedCtx != null) {
            if (!destroyIRs.isEmpty()) {
                error(type, "a contract with @Owned objects cannot @Destroy its instance: objects that read @Shared state"
                        + " would be locked forever (ADR-0003 r2)");
            }
            if (ownedDestroyIRs.stream().noneMatch(t -> !t.shared())) {
                error(ownedElement, "owned objects need an escape hatch: an @OwnedDestroy without @Shared, so an owner can"
                        + " always close their object whatever the instance state (ADR-0003 r2)");
            }
            if (anyMap) {
                error(type, "@Owned objects and MerkleMap state cannot be combined yet: a @Shared read has no proof path"
                        + " for map reads (ADR-0003 r2)");
            }
            for (var t : concat(ownedActionIRs, ownedDestroyIRs)) {
                if (t.signedBy().isEmpty()) {
                    messager.printMessage(Diagnostic.Kind.WARNING, "owned method " + t.method() + " has no @SignedBy:"
                            + " anyone can call it on anyone's object (and keep the owner's own calls failing)", type);
                }
            }
        }
        if (assetAware && anyMap) {
            error(type, "MerkleMap state cannot be combined with native assets (Funds, sends, mint) yet (ADR-0007)");
        }
        if (assetAware && anyBatched) {
            error(type, "@Batched actions cannot be combined with native assets (Funds, sends, mint) yet: intents carry"
                    + " only lovelace (ADR-0007 r2)");
        }
        if (anyMap && actionIRs.stream().anyMatch(TransitionIR::batched)) {
            error(type, "@Batched actions and MerkleMap state cannot be combined yet: each step of a batch would need its"
                    + " own map claim (ADR-0004 D1)");
        }
        if (destroyIRs.isEmpty() && ownedCtx == null) {
            messager.printMessage(Diagnostic.Kind.WARNING, "this @StateContract has no @Destroy method: an instance can"
                    + " never end, so its min-ADA deposit (" + minAda + " lovelace) is locked forever", type);
        }
        if (failed) return Optional.empty();
        return Optional.of(new ContractIR(pkg, type.getSimpleName().toString(), stateText, stateComponents, minAda,
                maxDatumBytes, maxValidityMs,
                init, actionIRs, destroyIRs, viewIRs, types.imports(), Strategies.OPTION_A,
                ownedType == null ? null : types.render(ownedType), List.copyOf(ownedComponents), ownedInitIRs,
                ownedActionIRs, ownedDestroyIRs));
    }

    /** Coins per UTxO byte on mainnet and the public testnets (Conway). Governance can change it; ADR-0001 §10. */
    static final long COINS_PER_UTXO_BYTE = 4310L;

    /**
     * Deposit covering the ledger minimum for a state output: 160 bytes of ledger overhead, about 200 bytes of address,
     * value (lovelace plus the thread token) and CBOR framing, and the datum. Rounded up to 0.1 ADA.
     */
    static long requiredMinAda(int maxDatumBytes, int assetSlots) {
        // Each native asset the UTxO may hold adds about 80 bytes: policy (28), name (up to 32), quantity, framing.
        long raw = COINS_PER_UTXO_BYTE * (160L + 200L + maxDatumBytes + 80L * assetSlots);
        return (raw + 99_999L) / 100_000L * 100_000L;
    }

    /** Simple names the generated validator and client use for their own imports and nested types. */
    static final Set<String> GENERATED_NAMES = Set.of(
            "BigInteger", "Optional", "List", "PlutusData", "JulcList", "AssetEntry", "ScriptContext", "TxInfo",
            "TxOut", "TxInInfo", "TxOutRef", "Value", "Address", "Credential", "ScriptInfo", "Builtins", "Entrypoint",
            "MultiValidator", "Purpose", "AddressLib", "ByteStringLib", "ContextsLib", "OutputLib", "ValuesLib",
            "IntervalLib", "CryptoLib", "Interval", "IntervalBoundType", "OutputDatum", "Ctx", "Clock", "Payout", "Payouts",
            "Funds", "Send", "Sends", "Assets", "OutputClaim", "OutputClaims", "ByAction", "JulcValue", "CreateInstance", "BurnInstance", "Account", "StateRuntime",
            "StateInstance", "TxResult", "Transition", "Lifecycle", "Calls", "Call", "MerkleMap", "MapEntry", "MapWitness",
            "MapWitnesses", "Mpf", "Deploy", "ContractSpec",
            "StateContractHandle", "Utxo", "Arrays", "Holding", "ScriptPurpose");

    /** Names generated only for contracts with {@code @Batched} actions (ADR-0004). */
    static final Set<String> INTENT_NAMES = Set.of("Intent", "IntentCall", "Step", "IntentRefund", "Batch", "InBatch",
            "CancelIntent", "CreateIntent", "IntentCodec", "IntentData", "IntentHandle", "Batcher");

    /** What a transition acts on: {@code stateType} is its subject (the state, or the owned record), {@code sharedType}
     * the instance state (for {@code @Shared}). */
    private record Reading(TypeRenderer types, TypeMirror stateType, Map<String, ParamIR> components, TypeMirror sharedType) {}

    private TransitionIR readTransition(ExecutableElement m, TransitionIR.Kind kind, Reading r) {
        List<? extends VariableElement> ps = m.getParameters();
        int first = 0;
        boolean creates = kind == TransitionIR.Kind.INIT || kind == TransitionIR.Kind.OWNED_INIT;
        if (!creates) {
            if (ps.isEmpty() || !same(ps.get(0).asType(), r.stateType())) {
                error(m, "the first parameter of @" + label(kind) + " must be the "
                        + (kind == TransitionIR.Kind.ACTION || kind == TransitionIR.Kind.DESTROY ? "state" : "owned record")
                        + " (" + r.types().render(r.stateType()) + ")");
            }
            first = 1;
        }
        boolean shared = false;
        boolean ownedKind = kind == TransitionIR.Kind.OWNED_INIT || kind == TransitionIR.Kind.OWNED_ACTION
                || kind == TransitionIR.Kind.OWNED_DESTROY;
        for (int i = 0; i < ps.size(); i++) {
            if (annotation(ps.get(i), SHARED).isPresent()) {
                if (!ownedKind) error(ps.get(i), "@Shared is for owned methods; shared methods get the state as their first parameter");
                else if (i != first) error(ps.get(i), "the @Shared parameter must come right after the owned record");
                else if (!same(ps.get(i).asType(), r.sharedType())) error(ps.get(i), "@Shared must have the state type");
                else shared = true;
            }
        }
        if (shared) first++;
        boolean hasFunds = ps.size() > first && isNamed(ps.get(ps.size() - 1).asType(), FUNDS);
        int end = hasFunds ? ps.size() - 1 : ps.size();
        boolean hasCtx = end > first && isNamed(ps.get(end - 1).asType(), CTX);
        int last = hasCtx ? end - 1 : end;
        List<ParamIR> params = new ArrayList<>();
        for (int i = first; i < last; i++) {
            var p = ps.get(i);
            if (isNamed(p.asType(), FUNDS)) {
                error(p, "Funds must be the last parameter (after Ctx, if any)");
            } else if (isNamed(p.asType(), CTX)) {
                error(p, "Ctx must be the last parameter, or the last before Funds");
            } else if (same(p.asType(), r.stateType()) || same(p.asType(), r.sharedType())) {
                error(p, ownedKind ? "the state reaches an owned method only through one @Shared parameter"
                        : "only the first parameter may be the state");
            }
            if (mentionsMap(p.asType(), new java.util.HashSet<>())) {
                error(p, "a MerkleMap or MapEntry can only be a component of the state record: one passed in an argument"
                        + " carries reads nobody proved (ADR-0002 r2)");
            }
            params.add(new ParamIR(p.getSimpleName().toString(), r.types().render(p.asType()), kindOf(p.asType())));
        }

        TransitionIR.Returns returns;
        String resultType = null;
        ResultShape shape = null;
        TypeMirror ret = m.getReturnType();
        switch (kind) {
            case INIT, OWNED_INIT -> {
                if (same(ret, r.stateType())) {
                    returns = TransitionIR.Returns.STATE;
                } else {
                    returns = TransitionIR.Returns.RESULT;
                    shape = resultShape(m, ret, r, true);
                    resultType = r.types().render(ret);
                    if (shape.payouts() || shape.sends() || shape.mint()) {
                        error(m, "@" + label(kind) + " cannot pay out, send or mint; its record may only add 'accepted'"
                                + " (ADR-0007 D1)");
                    }
                }
            }
            case DESTROY, OWNED_DESTROY -> {
                if (isPayoutList(ret)) {
                    returns = TransitionIR.Returns.PAYOUTS;
                } else {
                    returns = TransitionIR.Returns.SETTLE;
                    shape = resultShape(m, ret, r, false);
                    resultType = r.types().render(ret);
                }
            }
            default -> {
                if (same(ret, r.stateType())) {
                    returns = TransitionIR.Returns.STATE;
                } else {
                    returns = TransitionIR.Returns.RESULT;
                    shape = resultShape(m, ret, r, true);
                    resultType = r.types().render(ret);
                }
            }
        }

        boolean payable = annotation(m, PAYABLE).isPresent();
        boolean destroys = kind == TransitionIR.Kind.DESTROY || kind == TransitionIR.Kind.OWNED_DESTROY;
        if (payable && !hasCtx) error(m, "@Payable needs a Ctx parameter, to read ctx.received()");
        if (destroys && !hasCtx) {
            error(m, "@Destroy needs a Ctx parameter: it must pay out ctx.balance(), everything the instance holds"
                    + " (deposit included)");
        }
        if (creates) {
            for (ParamIR p : params) {
                if (p.name().equals("seed") || p.name().equals("ctx") || p.name().equals("instance")) {
                    error(m, "@Init parameter name \"" + p.name() + "\" is reserved by the generated redeemer; rename it");
                }
            }
        }
        for (ParamIR p : params) {
            if (p.name().equals("ctx")) error(m, "parameter name \"ctx\" is reserved for the Ctx parameter; rename it");
            if (p.name().equals("maps")) error(m, "parameter name \"maps\" is reserved for the MerkleMap witnesses; rename it");
        }

        List<String> signedBy = new ArrayList<>();
        annotation(m, SIGNED_BY).flatMap(a -> value(a, "value")).ifPresent(v -> signedBy.add((String) v.getValue()));
        if (annotation(m, ONLY_OWNER).isPresent()) signedBy.add("owner");
        List<String> validAfter = new ArrayList<>();
        annotation(m, VALID_AFTER).flatMap(a -> value(a, "value")).ifPresent(v -> validAfter.add((String) v.getValue()));
        List<String> validBefore = new ArrayList<>();
        annotation(m, VALID_BEFORE).flatMap(a -> value(a, "value")).ifPresent(v -> validBefore.add((String) v.getValue()));
        if (creates && !(signedBy.isEmpty() && validAfter.isEmpty() && validBefore.isEmpty())) {
            error(m, "@SignedBy, @OnlyOwner, @ValidAfter and @ValidBefore check the current state, which @Init does not"
                    + " have; use Ctx and Require.that in the body instead");
        }
        for (String f : signedBy) checkField(m, r, f, ParamIR.TypeKind.BYTES, "byte[]", "@SignedBy/@OnlyOwner");
        for (String f : validAfter) checkField(m, r, f, ParamIR.TypeKind.INTEGER, "BigInteger", "@ValidAfter");
        for (String f : validBefore) checkField(m, r, f, ParamIR.TypeKind.INTEGER, "BigInteger", "@ValidBefore");

        var batchedAnn = annotation(m, BATCHED);
        boolean batched = batchedAnn.isPresent();
        boolean batchOnly = batchedAnn.flatMap(a -> value(a, "only")).map(v -> (Boolean) v.getValue()).orElse(false);
        if (batched) {
            if (kind != TransitionIR.Kind.ACTION) {
                error(m, "@Batched is for @Action methods (ADR-0004 D1)");
            } else {
                if (!hasCtx) {
                    error(m, "@Batched needs a Ctx parameter: in a batch, ctx.caller() is the intent's owner");
                }
                if (hasFunds || (shape != null && (shape.sends() || shape.mint()))) {
                    error(m, "@Batched actions cannot take Funds, send or mint yet (ADR-0007 D1)");
                }
                if (!(signedBy.isEmpty() && validAfter.isEmpty() && validBefore.isEmpty())) {
                    error(m, "@Batched cannot use @SignedBy, @OnlyOwner, @ValidAfter or @ValidBefore: the batcher, not the"
                            + " caller, signs a batch; authorise with ctx.caller() and check time with Clock (ADR-0004 D1)");
                }
            }
        }

        String name = m.getSimpleName().toString();
        String variant = kind == TransitionIR.Kind.INIT ? "CreateInstance"
                : Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (ParamIR p : params) {
            if (p.name().equals("funds") || p.name().equals("assetsIn")) {
                error(m, "parameter name \"" + p.name() + "\" is reserved for Funds and its redeemer field; rename it");
            }
        }
        boolean accepts = shape != null && shape.accepted();
        if (payable && hasFunds && !accepts) {
            error(m, "a @Payable method with Funds must return a record with 'accepted' (Value): exactly the native assets"
                    + " it takes in, so nobody can push unwanted tokens into the contract (ADR-0007 r2)");
        }
        if (accepts && !(payable && hasFunds)) {
            error(m, "'accepted' needs @Payable and a Funds parameter: only then can the caller add native assets");
        }
        return new TransitionIR(kind, name, variant, List.copyOf(params), hasCtx, payable, returns, resultType,
                List.copyOf(signedBy), List.copyOf(validAfter), List.copyOf(validBefore), shared, batched, batchOnly,
                hasFunds, shape != null && shape.payouts(), shape != null && shape.sends(), shape != null && shape.mint(),
                accepts);
    }

    /** Which effect components a result record has (ADR-0007 D1). */
    private record ResultShape(boolean payouts, boolean sends, boolean mint, boolean accepted) {}

    /**
     * Reads a result record: components named {@code state} (actions: required; destroys: forbidden), {@code payouts}
     * ({@code JulcList<Payout>}), {@code sends} ({@code JulcList<Send>}) and {@code mint} ({@code Value}), nothing else.
     */
    private ResultShape resultShape(ExecutableElement m, TypeMirror ret, Reading r, boolean action) {
        String what = action ? "@" + "Action" : "@Destroy";
        String expected = action
                ? "the state (" + r.types().render(r.stateType()) + ") or a record with a 'state' component and any of"
                + " 'payouts' (JulcList<Payout>), 'sends' (JulcList<Send>), 'mint' (Value) and 'accepted' (Value)"
                : "JulcList<Payout>, or a record with any of 'payouts' (JulcList<Payout>), 'sends' (JulcList<Send>),"
                + " 'mint' (Value) and 'accepted' (Value)";
        TypeElement e = asRecord(ret);
        if (e == null) {
            error(m, what + " must return " + expected);
            return new ResultShape(false, false, false, false);
        }
        boolean state = false, payouts = false, sends = false, mint = false, accepted = false;
        var known = java.util.Set.of("state", "payouts", "sends", "mint", "accepted");
        for (RecordComponentElement c : e.getRecordComponents()) {
            if (!known.contains(c.getSimpleName().toString())) {
                error(m, what + " must return " + expected + "; component '" + c.getSimpleName() + "' is none of these");
                return new ResultShape(false, false, false, false);
            }
        }
        for (RecordComponentElement c : e.getRecordComponents()) {
            String n = c.getSimpleName().toString();
            TypeMirror t = c.asType();
            switch (n) {
                case "state" -> {
                    if (!action) error(m, "a destroy's result has no 'state': the instance or object ends");
                    else if (!same(t, r.stateType())) error(m, "the result's 'state' must be " + r.types().render(r.stateType()));
                    state = true;
                }
                case "payouts" -> {
                    if (!isPayoutList(t)) error(m, "the result's 'payouts' must be JulcList<Payout>");
                    payouts = true;
                }
                case "sends" -> {
                    if (!isListOf(t, SEND)) error(m, "the result's 'sends' must be JulcList<Send>");
                    sends = true;
                }
                case "mint" -> {
                    if (!isNamed(t, VALUE)) error(m, "the result's 'mint' must be a Value");
                    mint = true;
                }
                case "accepted" -> {
                    if (!isNamed(t, VALUE)) error(m, "the result's 'accepted' must be a Value");
                    accepted = true;
                }
                default -> error(m, "result record component '" + n + "' is not one of state, payouts, sends, mint,"
                        + " accepted;"
                        + " " + what + " must return " + expected);
            }
        }
        if (action && !state) error(m, what + " must return " + expected);
        if (!action && !(payouts || sends || mint)) error(m, what + " must return " + expected);
        return new ResultShape(payouts, sends, mint, accepted);
    }

    private boolean isListOf(TypeMirror t, String element) {
        if (!(t instanceof DeclaredType d) || !isNamed(t, JULC_LIST) || d.getTypeArguments().size() != 1) return false;
        return isNamed(d.getTypeArguments().get(0), element);
    }

    private ViewIR readView(ExecutableElement m, Reading r) {
        List<? extends VariableElement> ps = m.getParameters();
        if (ps.isEmpty() || !same(ps.get(0).asType(), r.stateType())) {
            error(m, "the first parameter of @View must be the state (" + r.types().render(r.stateType()) + ")");
        }
        if (m.getReturnType().getKind() == TypeKind.VOID) error(m, "@View must return a value");
        List<ParamIR> params = new ArrayList<>();
        for (int i = 1; i < ps.size(); i++) {
            var p = ps.get(i);
            if (isNamed(p.asType(), CTX)) error(p, "@View cannot take Ctx: views run off-chain, without a transaction");
            params.add(new ParamIR(p.getSimpleName().toString(), r.types().render(p.asType()), kindOf(p.asType())));
        }
        return new ViewIR(m.getSimpleName().toString(), List.copyOf(params), r.types().render(m.getReturnType()));
    }

    private void checkField(ExecutableElement m, Reading r, String field, ParamIR.TypeKind kind, String typeName,
                            String what) {
        ParamIR c = r.components().get(field);
        if (c == null) {
            error(m, what + " names \"" + field + "\", which is not a component of the state record");
        } else if (c.kind() != kind) {
            error(m, what + " names \"" + field + "\", which must be " + typeName + " but is " + c.type());
        }
    }

    // ------------------------------------------------------------------ type helpers

    /** True if {@code t} is, or contains (in record components or type arguments), a MerkleMap or MapEntry. */
    private boolean mentionsMap(TypeMirror t, java.util.Set<String> seen) {
        if (!(t instanceof DeclaredType d) || !(d.asElement() instanceof TypeElement e)) return false;
        String name = e.getQualifiedName().toString();
        if (name.equals(API + "MerkleMap") || name.equals(API + "MapEntry")) return true;
        if (!seen.add(name)) return false;
        for (TypeMirror a : d.getTypeArguments()) if (mentionsMap(a, seen)) return true;
        if (e.getKind() == ElementKind.RECORD) {
            for (RecordComponentElement c : e.getRecordComponents()) if (mentionsMap(c.asType(), seen)) return true;
        }
        return false;
    }

    private boolean isPayoutList(TypeMirror t) {
        if (!(t instanceof DeclaredType d) || !isNamed(t, JULC_LIST) || d.getTypeArguments().size() != 1) return false;
        return isNamed(d.getTypeArguments().get(0), PAYOUT);
    }

    private TypeElement asRecord(TypeMirror t) {
        if (t instanceof DeclaredType d && d.asElement() instanceof TypeElement e && e.getKind() == ElementKind.RECORD) {
            return e;
        }
        return null;
    }

    private boolean same(TypeMirror a, TypeMirror b) {
        return env.getTypeUtils().isSameType(a, b);
    }

    private static boolean isNamed(TypeMirror t, String fqcn) {
        return t instanceof DeclaredType d && d.asElement() instanceof TypeElement e
                && e.getQualifiedName().contentEquals(fqcn);
    }

    static ParamIR.TypeKind kindOf(TypeMirror t) {
        if (t.getKind() == TypeKind.BOOLEAN) return ParamIR.TypeKind.BOOLEAN;
        if (t.getKind() == TypeKind.ARRAY && ((javax.lang.model.type.ArrayType) t).getComponentType().getKind()
                == TypeKind.BYTE) return ParamIR.TypeKind.BYTES;
        if (isNamed(t, BIG_INTEGER)) return ParamIR.TypeKind.INTEGER;
        if (isNamed(t, "java.lang.String")) return ParamIR.TypeKind.STRING;
        if (isNamed(t, API + "MerkleMap")) return ParamIR.TypeKind.MERKLE_MAP;
        return ParamIR.TypeKind.OTHER;
    }

    // ------------------------------------------------------------------ annotation helpers

    static Optional<AnnotationMirror> annotation(Element e, String fqcn) {
        for (AnnotationMirror a : e.getAnnotationMirrors()) {
            if (((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().contentEquals(fqcn)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    static Optional<AnnotationValue> value(AnnotationMirror a, String name) {
        for (var entry : a.getElementValues().entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(name)) return Optional.of(entry.getValue());
        }
        return Optional.empty();
    }

    private static String simple(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf('.') + 1);
    }

    private static String label(TransitionIR.Kind k) {
        return switch (k) {
            case INIT -> "Init";
            case ACTION -> "Action";
            case DESTROY -> "Destroy";
            case OWNED_INIT -> "OwnedInit";
            case OWNED_ACTION -> "OwnedAction";
            case OWNED_DESTROY -> "OwnedDestroy";
        };
    }

    @SafeVarargs
    private static <T> List<T> concat(List<T>... lists) {
        var all = new ArrayList<T>();
        for (var l : lists) all.addAll(l);
        return all;
    }

    private void error(Element e, String message) {
        failed = true;
        messager.printMessage(Diagnostic.Kind.ERROR, message, e);
    }

    /**
     * Renders types as source text relative to the contract's package and collects the imports that text needs.
     * Nested types are written through their top-level class ({@code Counter.State}), so only top-level classes are
     * imported.
     */
    static final class TypeRenderer {
        private final String pkg;
        private final Map<String, String> imports = new LinkedHashMap<>();   // simple name -> fqcn
        private final List<String> clashes = new ArrayList<>();

        TypeRenderer(String pkg) {
            this.pkg = pkg;
        }

        String render(TypeMirror t) {
            return switch (t.getKind()) {
                case BOOLEAN -> "boolean";
                case LONG -> "long";
                case INT -> "int";
                case BYTE -> "byte";
                case ARRAY -> render(((javax.lang.model.type.ArrayType) t).getComponentType()) + "[]";
                case DECLARED -> renderDeclared((DeclaredType) t);
                default -> t.toString();
            };
        }

        private String renderDeclared(DeclaredType d) {
            TypeElement e = (TypeElement) d.asElement();
            var chain = new ArrayList<String>();
            Element cur = e;
            while (cur instanceof TypeElement te) {
                chain.add(0, te.getSimpleName().toString());
                cur = te.getEnclosingElement();
            }
            String topPkg = cur instanceof PackageElement p ? p.getQualifiedName().toString() : "";
            String top = chain.get(0);
            String topFqcn = topPkg.isEmpty() ? top : topPkg + "." + top;
            if (!topPkg.equals(pkg) && !topPkg.equals("java.lang")) {
                String prev = imports.putIfAbsent(top, topFqcn);
                if (prev != null && !prev.equals(topFqcn)) {
                    clashes.add("types " + prev + " and " + topFqcn + " share the simple name " + top
                            + "; JOUST's generated code imports by simple name, so rename one");
                }
            }
            String text = String.join(".", chain);
            if (!d.getTypeArguments().isEmpty()) {
                var args = new ArrayList<String>();
                for (TypeMirror a : d.getTypeArguments()) args.add(render(a));
                text += "<" + String.join(", ", args) + ">";
            }
            return text;
        }

        List<String> imports() {
            return List.copyOf(imports.values());
        }

        List<String> checkNoClash(Set<String> generatedNames) {
            var out = new ArrayList<>(clashes);
            for (var entry : imports.entrySet()) {
                String fqcn = entry.getValue();
                boolean ours = fqcn.startsWith("org.julclang.state.api.") || fqcn.startsWith("org.julclang.core.")
                        || fqcn.startsWith("org.julclang.ledger.") || fqcn.startsWith("java.math.")
                        || fqcn.startsWith("java.util.");
                if (generatedNames.contains(entry.getKey()) && !ours) {
                    out.add("type " + fqcn + " has the simple name " + entry.getKey()
                            + ", which JOUST's generated code uses for its own import; rename it");
                }
            }
            return out;
        }
    }
}
