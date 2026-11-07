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
    static final String INTO = API + "Into";
    static final String ADMITS = API + "Admits";
    static final String RECEIVES = API + "Receives";
    static final String SHARED = API + "Shared";
    static final String BATCHED = API + "Batched";
    static final String PAYOUT = API + "Payout";
    static final String FUNDS = API + "Funds";
    static final String SEND = API + "Send";
    static final String VALUE = "org.julclang.ledger.Value";
    static final String PARAMS = API + "Params";
    static final String QUORUM = API + "Quorum";
    static final String AUTHORIZED_BY = API + "AuthorizedBy";
    static final String INVARIANT = API + "Invariant";
    static final String CREDENTIAL = "org.julclang.ledger.Credential";
    static final String READS = API + "Reads";
    static final String MIGRATE = API + "Migrate";
    static final String MIGRATE_TO = API + "MigrateTo";
    static final String FOREIGN = API + "ForeignInstance";
    static final java.util.Map<String, String> PURPOSES = java.util.Map.of(API + "Withdraw", "WITHDRAW",
            API + "Certify", "CERTIFY", API + "Votes", "VOTE", API + "Propose", "PROPOSE");
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
        List<ExecutableElement> invariantMethods = new ArrayList<>();
        List<ExecutableElement> migrateMethods = new ArrayList<>();
        Map<String, ExecutableElement> purposeMethods = new LinkedHashMap<>();
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
            String purposeKind = null;
            for (var e : PURPOSES.entrySet()) if (annotation(m, e.getKey()).isPresent()) purposeKind = e.getValue();
            if (purposeKind != null) {
                if (kinds > 0) error(m, "a purpose handler cannot also be @Init/@Action/@Destroy/@View/owned");
                if (purposeMethods.put(purposeKind, m) != null) {
                    error(m, "at most one handler per purpose (" + purposeKind + ") (ADR-0010)");
                }
                continue;
            }
            if (annotation(m, MIGRATE).isPresent()) {
                if (kinds > 0) error(m, "@Migrate is its own kind of creation; drop the other annotation");
                migrateMethods.add(m);
                continue;
            }
            if (annotation(m, INVARIANT).isPresent()) {
                if (kinds > 0) error(m, "@Invariant cannot be combined with @Init/@Action/@Destroy/@View/owned annotations");
                invariantMethods.add(m);
                continue;
            }
            if (kinds == 0) {
                for (String policy : List.of(PAYABLE, SIGNED_BY, ONLY_OWNER, VALID_AFTER, VALID_BEFORE, BATCHED, QUORUM,
                        AUTHORIZED_BY, ADMITS, RECEIVES)) {
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

        // ---- the @Owned records (ADR-0003; several since ADR-0012 D1)
        List<TypeElement> ownedElements = new ArrayList<>();
        for (TypeElement nested : ElementFilter.typesIn(type.getEnclosedElements())) {
            if (annotation(nested, OWNED).isPresent()) {
                if (nested.getKind() != ElementKind.RECORD) error(nested, "@Owned must be on a record");
                else ownedElements.add(nested);
            }
        }
        boolean anyOwnedMethod = !ownedInits.isEmpty() || !ownedActions.isEmpty() || !ownedDestroys.isEmpty();
        if (anyOwnedMethod && ownedElements.isEmpty()) {
            error(type, "@OwnedInit/@OwnedAction/@OwnedDestroy need an @Owned record in the contract");
            return Optional.empty();
        }
        List<ContractIR.OwnedIR> ownedIRs = new ArrayList<>();
        Map<String, Map<String, ParamIR>> ownedFields = new LinkedHashMap<>();
        for (TypeElement oe : ownedElements) {
            List<ParamIR> comps = new ArrayList<>();
            Map<String, ParamIR> byName = new LinkedHashMap<>();
            for (RecordComponentElement c : oe.getRecordComponents()) {
                if (!isNamed(c.asType(), API + "MerkleMap") && mentionsMap(c.asType(), new java.util.HashSet<>())) {
                    error(oe, "owned component '" + c.getSimpleName() + "' nests a MerkleMap or MapEntry; MerkleMaps are"
                            + " supported only as direct components of an owned record (ADR-0012 D2)");
                }
                var p = new ParamIR(c.getSimpleName().toString(), types.render(c.asType()), kindOf(c.asType()));
                comps.add(p);
                byName.put(p.name(), p);
            }
            String text = types.render(oe.asType());
            String simpleName = oe.getSimpleName().toString();
            String holding = ownedElements.size() == 1 ? "Holding" : "Holding_" + simpleName;
            ownedIRs.add(new ContractIR.OwnedIR(text, simpleName, List.copyOf(comps), holding, ownedIRs.size()));
            ownedFields.put(text, byName);
        }
        // ---- the @Params record (ADR-0009 D1)
        TypeElement paramsElement = null;
        for (TypeElement nested : ElementFilter.typesIn(type.getEnclosedElements())) {
            if (annotation(nested, PARAMS).isPresent()) {
                if (nested.getKind() != ElementKind.RECORD) error(nested, "@Params must be on a record");
                else if (paramsElement != null) error(nested, "at most one @Params record per contract (ADR-0009 D1)");
                else paramsElement = nested;
            }
        }
        Map<String, ParamIR> paramsByName = new LinkedHashMap<>();
        if (paramsElement != null) {
            for (RecordComponentElement c : paramsElement.getRecordComponents()) {
                if (mentionsMap(c.asType(), new java.util.HashSet<>())) error(paramsElement, "@Params cannot hold MerkleMaps");
                paramsByName.put(c.getSimpleName().toString(),
                        new ParamIR(c.getSimpleName().toString(), types.render(c.asType()), kindOf(c.asType())));
            }
        }
        TypeMirror paramsType = paramsElement == null ? null : paramsElement.asType();

        var ctx = new Reading(types, stateType, componentsByName, stateType, paramsType, paramsByName);
        Map<String, Reading> ownedCtxs = new LinkedHashMap<>();
        for (TypeElement oe : ownedElements) {
            String text = types.render(oe.asType());
            ownedCtxs.put(text, new Reading(types, oe.asType(), ownedFields.get(text), stateType, paramsType, paramsByName));
        }
        var ownedCtx = ownedElements.isEmpty() ? null : ownedCtxs.values().iterator().next();
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
            for (var m : ownedInits) {
                ownedInitIRs.add(readTransition(m, TransitionIR.Kind.OWNED_INIT, ownedReading(m, true, ownedCtxs)));
            }
            for (var m : ownedActions) {
                ownedActionIRs.add(readTransition(m, TransitionIR.Kind.OWNED_ACTION, ownedReading(m, false, ownedCtxs)));
            }
            for (var m : ownedDestroys) {
                ownedDestroyIRs.add(readTransition(m, TransitionIR.Kind.OWNED_DESTROY, ownedReading(m, false, ownedCtxs)));
            }
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
        boolean stakeSelf = sc.flatMap(a -> value(a, "stakeSelf")).map(v -> (Boolean) v.getValue()).orElse(false);
        long requiredMinAda = requiredMinAda(maxDatumBytes, assetSlots + (stakeSelf ? 1 : 0));   // a base address: ~28 B more
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
        if (concat(actionIRs, destroyIRs, ownedActionIRs, ownedDestroyIRs).stream().anyMatch(TransitionIR::resultDeposits)) {
            reserved.add("Deposit");   // the validator imports org.julclang.state.api.Deposit (ADR-0011 D3)
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
        if (ownedIRs.size() > 1) {
            var holdings = new HashSet<String>();
            for (var o : ownedIRs) {
                holdings.add(o.holding());
                if (!reserved.add(o.holding())) error(type, "generated datum name " + o.holding() + " clashes; rename a method");
            }
            types.checkNoClash(holdings).forEach(msg -> error(type, msg));
        }

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
            for (var o : ownedIRs) {
                if (ownedInitIRs.stream().noneMatch(t -> t.ownedType().equals(o.type()))) {
                    error(type, "@Owned record " + o.simple() + " needs at least one @OwnedInit method to create objects");
                }
                if (ownedDestroyIRs.stream().noneMatch(t -> !t.shared() && t.ownedType().equals(o.type()))) {
                    error(type, "owned objects of type " + o.simple() + " need an escape hatch: an @OwnedDestroy on "
                            + o.simple() + " without @Shared, so an owner can always close their object whatever the"
                            + " instance state (ADR-0003 r2, ADR-0012 D1)");
                }
            }
            var movesIR = ownedActionIRs.stream().filter(TransitionIR::moves).toList();
            var admitsIR = actionIRs.stream().filter(TransitionIR::admits).toList();
            if (!movesIR.isEmpty() && admitsIR.isEmpty()) {
                error(type, "an @Into move needs an @Admits action: the destination must consent (ADR-0012 D3)");
            }
            if (movesIR.isEmpty() && !admitsIR.isEmpty()) {
                error(type, "an @Admits action needs an @Into move: no object could ever arrive (ADR-0012 D3)");
            }
            // Bind each @Admits to one owned type: only that type's moves arrive through it (ADR-0012 r3).
            var movingTypes = new java.util.LinkedHashSet<String>();
            for (var mv : movesIR) movingTypes.add(mv.ownedType());
            for (int i = 0; i < actionIRs.size(); i++) {
                var a = actionIRs.get(i);
                if (!a.admits()) continue;
                String resolved = null;
                if (a.admitsType().isEmpty()) {
                    if (movingTypes.size() == 1) resolved = movingTypes.iterator().next();
                    else if (movingTypes.size() > 1) {
                        error(type, "@Admits on " + a.method() + " must name the owned type it admits (@Admits(\"Hero\")):"
                                + " several owned types have moves (ADR-0012)");
                    }
                } else {
                    for (var o : ownedIRs) if (o.simple().equals(a.admitsType())) resolved = o.type();
                    if (resolved == null) error(type, "@Admits(\"" + a.admitsType() + "\") on " + a.method()
                            + " does not name an @Owned record of this contract");
                    else if (!movingTypes.contains(resolved)) error(type, "@Admits(\"" + a.admitsType() + "\") on "
                            + a.method() + ": that type has no @Into move");
                }
                if (resolved != null) {
                    // An optional parameter right after the object id, of the admitted type: the arriving object as it
                    // was before the move, read from its input on-chain (ADR-0012 r4). Not a redeemer field.
                    var ps = new ArrayList<ParamIR>(a.params());
                    String arriving = null;
                    if (ps.size() >= 2 && ps.get(1).type().equals(resolved)) arriving = ps.remove(1).name();
                    if (arriving != null && GENERATED_LOCALS.contains(arriving)) {
                        error(type, "parameter name \"" + arriving + "\" of " + a.method() + " is used by the generated"
                                + " validator; rename it");
                    }
                    actionIRs.set(i, a.withAdmits(resolved, arriving, List.copyOf(ps)));
                }
            }
            for (String mt : movingTypes) {
                boolean admitted = actionIRs.stream().anyMatch(a -> a.admits() && mt.equals(a.admitsType()));
                if (!admitted && !actionIRs.stream().filter(TransitionIR::admits).toList().isEmpty()) {
                    error(type, "moves of " + mt + " need an @Admits action for that type (ADR-0012 D3)");
                }
            }
            boolean withdraws = purposeMethods.containsKey("WITHDRAW");   // the generated code is asset-aware then too
            if (anyMap) {
                error(type, "@Owned objects and MerkleMap state cannot be combined yet: a @Shared read has no proof path"
                        + " for map reads (ADR-0003 r2); MerkleMaps may go in the owned records instead (ADR-0012 D2)");
            }
            boolean ownedMaps = ownedIRs.stream().anyMatch(ContractIR.OwnedIR::hasMaps);
            if (ownedMaps && (assetAware || withdraws)) {
                error(type, "owned MerkleMaps cannot be combined with native assets (Funds, sends, mint) yet (ADR-0007)");
            }
            for (var inv : invariantMethods) {
                var ps0 = inv.getParameters();
                for (var o : ownedIRs) {
                    if (o.hasMaps() && !ps0.isEmpty() && types.render(ps0.get(0).asType()).equals(o.type())) {
                        error(inv, "@Invariant cannot read an owned record with MerkleMaps: on-chain sees only the opened"
                                + " read set (ADR-0009 r2)");
                    }
                }
            }
            for (var t : concat(ownedActionIRs, ownedDestroyIRs)) {
                if (t.signedBy().isEmpty()) {
                    messager.printMessage(Diagnostic.Kind.WARNING, "owned method " + t.method() + " has no @SignedBy:"
                            + " anyone can call it on anyone's object (and keep the owner's own calls failing)", type);
                }
            }
        }
        if (ownedCtx == null && actionIRs.stream().anyMatch(TransitionIR::admits)) {
            error(type, "@Admits admits owned objects: the contract needs an @Owned record and an @Into move (ADR-0012 D3)");
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
        // ---- @Invariant methods (ADR-0009 D4)
        List<ContractIR.InvariantIR> invariants = new ArrayList<>();
        for (ExecutableElement m : invariantMethods) {
            if (!m.getModifiers().contains(Modifier.STATIC)) error(m, "@Invariant methods must be static");
            if (m.getReturnType().getKind() != TypeKind.BOOLEAN) error(m, "@Invariant must return boolean");
            var ps = m.getParameters();
            String onOwnedType = null;
            for (TypeElement oe : ownedElements) {
                if (!ps.isEmpty() && same(ps.get(0).asType(), oe.asType())) onOwnedType = types.render(oe.asType());
            }
            boolean onOwned = onOwnedType != null;
            boolean onState = !ps.isEmpty() && same(ps.get(0).asType(), stateType);
            if (!onOwned && !onState) error(m, "@Invariant's first parameter must be the state or the owned record");
            boolean withParams = ps.size() == 2 && paramsType != null && same(ps.get(1).asType(), paramsType);
            if (ps.size() > 2 || (ps.size() == 2 && !withParams)) {
                error(m, "@Invariant takes the state (or owned record) and optionally the @Params record, nothing else");
            }
            if (onState && anyMap) {
                error(m, "@Invariant cannot read state with MerkleMaps: on-chain sees only the opened read set (ADR-0009 r2)");
            }
            invariants.add(new ContractIR.InvariantIR(m.getSimpleName().toString(), onOwnedType, withParams));
        }
        // ---- purpose handlers (ADR-0010)
        List<ContractIR.PurposeIR> purposes = new ArrayList<>();
        for (var e : purposeMethods.entrySet()) {
            purposes.add(readPurpose(e.getKey(), e.getValue(), types, paramsType, paramsByName));
        }
        if (stakeSelf && !(purposeMethods.containsKey("WITHDRAW") && purposeMethods.containsKey("CERTIFY"))) {
            error(type, "@StateContract(stakeSelf = true) needs a @Withdraw and a @Certify handler, or the rewards and the"
                    + " credential of its funds are unreachable (ADR-0010 D2)");
        }
        TransitionIR migrateIR = null;
        if (migrateMethods.size() > 1) error(type, "at most one @Migrate per contract");
        if (!migrateMethods.isEmpty()) {
            migrateIR = readTransition(migrateMethods.get(0), TransitionIR.Kind.MIGRATE, ctx);
            if (anyMap || ownedCtx != null || assetAware) {
                error(type, "@Migrate is not supported yet with MerkleMaps, owned objects or native assets (ADR-0011 r2)");
            }
        }
        for (var t : destroyIRs) {
            if (t.migrateTo() != null && (anyMap || ownedCtx != null || assetAware)) {
                error(type, "@MigrateTo is not supported yet with MerkleMaps, owned objects or native assets (ADR-0011 r2)");
            }
        }
        if (!invariants.isEmpty() && anyBatched) {
            error(type, "@Invariant cannot be combined with @Batched yet: a batch passes through intermediate states that"
                    + " the validator does not check (ADR-0009 r2)");
        }
        if (failed) return Optional.empty();
        return Optional.of(new ContractIR(pkg, type.getSimpleName().toString(), stateText, stateComponents, minAda,
                maxDatumBytes, maxValidityMs,
                init, actionIRs, destroyIRs, viewIRs, types.imports(), Strategies.OPTION_A,
                List.copyOf(ownedIRs), ownedInitIRs,
                ownedActionIRs, ownedDestroyIRs, paramsType == null ? null : types.render(paramsType), List.copyOf(invariants),
                List.copyOf(purposes), stakeSelf, migrateIR));
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
            "Funds", "Send", "Sends", "Assets", "OutputClaim", "OutputClaims", "DepositClaim", "ByAction", "JulcValue", "Param", "Approval", "CreateInstance", "BurnInstance", "Account", "StateRuntime",
            "StateInstance", "TxResult", "Transition", "Lifecycle", "Calls", "Call", "MerkleMap", "MapEntry", "MapWitness",
            "MapWitnesses", "Mpf", "Deploy", "ContractSpec",
            "StateContractHandle", "Utxo", "Arrays", "Holding", "ScriptPurpose");

    /** Locals of generated validator methods that a derived argument must not shadow (ADR-0012 r4). */
    static final Set<String> GENERATED_LOCALS = Set.of("r", "tx", "own", "policy", "id", "inLovelace", "current", "next",
            "result", "payouts", "continues", "paid", "shared", "destination", "mc", "opened", "arrives", "held",
            "params", "ctxOk", "allocated", "burned", "inValue", "funds");

    /** Names generated only for contracts with {@code @Batched} actions (ADR-0004). */
    static final Set<String> INTENT_NAMES = Set.of("Intent", "IntentCall", "Step", "IntentRefund", "Batch", "InBatch",
            "CancelIntent", "CreateIntent", "IntentCodec", "IntentData", "IntentHandle", "Batcher");

    /** What a transition acts on: {@code stateType} is its subject (the state, or the owned record), {@code sharedType}
     * the instance state (for {@code @Shared}). */
    private record Reading(TypeRenderer types, TypeMirror stateType, Map<String, ParamIR> components, TypeMirror sharedType,
                           TypeMirror paramsType, Map<String, ParamIR> paramsComponents) {}

    /**
     * The owned record an owned method acts on (ADR-0012 D1): an action's or destroy's first parameter; an init's return
     * type, or its result record's {@code state} component.
     */
    private Reading ownedReading(ExecutableElement m, boolean init, Map<String, Reading> byType) {
        TypeMirror subject = null;
        if (init) {
            subject = m.getReturnType();
            TypeElement rec = asRecord(subject);
            boolean direct = byType.values().stream().anyMatch(r -> same(r.stateType(), m.getReturnType()));
            if (!direct && rec != null) {
                for (RecordComponentElement c : rec.getRecordComponents()) {
                    if (c.getSimpleName().contentEquals("state")) subject = c.asType();
                }
            }
        } else if (!m.getParameters().isEmpty()) {
            subject = m.getParameters().get(0).asType();
        }
        for (Reading r : byType.values()) {
            if (subject != null && same(r.stateType(), subject)) return r;
        }
        if (byType.size() > 1) {
            error(m, "an owned method acts on one @Owned record: " + (init ? "return it (or a record with a 'state' of it)"
                    : "take it as the first parameter") + " (ADR-0012 D1)");
        }
        return byType.values().iterator().next();
    }

    private TransitionIR readTransition(ExecutableElement m, TransitionIR.Kind kind, Reading r) {
        List<? extends VariableElement> ps = m.getParameters();
        int first = 0;
        boolean creates = kind == TransitionIR.Kind.INIT || kind == TransitionIR.Kind.OWNED_INIT
                || kind == TransitionIR.Kind.MIGRATE;
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
        String into = null;
        for (int i = 0; i < ps.size(); i++) {
            if (annotation(ps.get(i), INTO).isPresent()) {
                if (kind != TransitionIR.Kind.OWNED_ACTION) error(ps.get(i), "@Into is for @OwnedAction methods (ADR-0012 D3)");
                else if (i != first) error(ps.get(i), "the @Into parameter must come right after the owned record (and @Shared)");
                else if (!same(ps.get(i).asType(), r.sharedType())) error(ps.get(i), "@Into must have the state type");
                else into = ps.get(i).getSimpleName().toString();
            }
        }
        if (into != null) first++;
        boolean takesParams = r.paramsType() != null && ps.size() > first && same(ps.get(first).asType(), r.paramsType());
        if (takesParams) first++;
        List<TransitionIR.ReadIR> reads = new ArrayList<>();
        if (kind == TransitionIR.Kind.MIGRATE) {
            String from = annotation(m, MIGRATE).flatMap(a -> value(a, "from")).map(v -> (String) v.getValue()).orElse("");
            if (!takesParams || !from.startsWith("params.") || !r.paramsComponents().containsKey(from.substring(7))
                    || r.paramsComponents().get(from.substring(7)).kind() != ParamIR.TypeKind.BYTES) {
                error(m, "@Migrate takes (Params, Old.State old, ...) and from = \"params.x\" naming a byte[] (the previous"
                        + " policy) of the @Params record (ADR-0011 D4)");
            }
            TypeElement rec = ps.size() > first ? asRecord(ps.get(first).asType()) : null;
            if (rec == null) {
                error(m, "@Migrate's parameter after the params must be the previous contract's state record");
            } else {
                reads.add(new TransitionIR.ReadIR(ps.get(first).getSimpleName().toString(), from,
                        r.types().render(ps.get(first).asType()), rec.getRecordComponents().size()));
                first++;
            }
        }
        while (ps.size() > first && annotation(ps.get(first), READS).isPresent()) {
            var p = ps.get(first);
            String path = annotation(p, READS).flatMap(a -> value(a, "value")).map(v -> (String) v.getValue()).orElse("");
            TypeElement rec = asRecord(p.asType());
            if (!path.startsWith("params.")) {
                error(p, "@Reads must name a ForeignInstance of the @Params record (\"params.x\"): the trusted script is"
                        + " fixed at deploy (ADR-0011 r2)");
            } else {
                ParamIR f = r.paramsComponents().get(path.substring(7));
                if (f == null || !f.type().equals("ForeignInstance")) {
                    error(p, "@Reads(\"" + path + "\") must name a ForeignInstance component of the @Params record");
                }
            }
            if (rec == null) error(p, "a @Reads parameter must be the other contract's state record");
            else if (mentionsMap(p.asType(), new java.util.HashSet<>())) {
                error(p, "@Reads cannot read state with MerkleMaps: there is no proof path for them (ADR-0011 r2)");
            }
            if (creates) error(p, "@Reads is not supported in a creation yet");
            reads.add(new TransitionIR.ReadIR(p.getSimpleName().toString(), path, r.types().render(p.asType()),
                    rec == null ? 0 : rec.getRecordComponents().size()));
            first++;
        }
        for (int i = first; i < ps.size(); i++) {
            if (annotation(ps.get(i), READS).isPresent()) {
                error(ps.get(i), "@Reads parameters must come right after the @Params record");
            }
        }
        if (!reads.isEmpty() && !takesParams) error(m, "@Reads needs the @Params record parameter before it");
        for (int i = first; i < ps.size(); i++) {
            if (r.paramsType() != null && same(ps.get(i).asType(), r.paramsType())) {
                error(ps.get(i), "the @Params record must come right after the state (or @Shared; first in @Init)");
            }
        }
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
            case INIT, OWNED_INIT, MIGRATE -> {
                if (same(ret, r.stateType())) {
                    returns = TransitionIR.Returns.STATE;
                } else {
                    returns = TransitionIR.Returns.RESULT;
                    shape = resultShape(m, ret, r, true);
                    resultType = r.types().render(ret);
                    if (shape.payouts() || shape.sends() || shape.mint() || shape.deposits()) {
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
        String quorumKeys = annotation(m, QUORUM).flatMap(a -> value(a, "keys")).map(v -> (String) v.getValue()).orElse(null);
        String quorumThreshold = annotation(m, QUORUM).flatMap(a -> value(a, "threshold")).map(v -> (String) v.getValue())
                .orElse(null);
        if (quorumKeys != null) {
            ParamIR k = fieldOf(m, r, quorumKeys, "@Quorum(keys)");
            if (k != null && !k.type().equals("JulcList<byte[]>")) {
                error(m, "@Quorum(keys) names \"" + quorumKeys + "\", which must be JulcList<byte[]> but is " + k.type());
            }
            ParamIR t = fieldOf(m, r, quorumThreshold, "@Quorum(threshold)");
            if (t != null && t.kind() != ParamIR.TypeKind.INTEGER) {
                error(m, "@Quorum(threshold) names \"" + quorumThreshold + "\", which must be BigInteger but is " + t.type());
            }
        }
        List<String> authorizedBy = new ArrayList<>();
        annotation(m, AUTHORIZED_BY).flatMap(a -> value(a, "value")).ifPresent(v -> authorizedBy.add((String) v.getValue()));
        for (String f : authorizedBy) {
            ParamIR c = fieldOf(m, r, f, "@AuthorizedBy");
            if (c != null && !c.type().equals("Credential")) {
                error(m, "@AuthorizedBy names \"" + f + "\", which must be a Credential but is " + c.type());
            }
        }
        if (creates && (quorumKeys != null || !authorizedBy.isEmpty())) {
            error(m, "@Quorum and @AuthorizedBy check the current state, which a creation does not have");
        }
        boolean events = shape != null && shape.events();
        String eventsType = events ? shape.eventsType() : null;
        if (events && creates) error(m, "an init cannot emit events yet (ADR-0009 r2)");
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
                if (takesParams) error(m, "@Batched actions cannot take the @Params record or @Reads yet (ADR-0009)");
                if (quorumKeys != null || !authorizedBy.isEmpty() || (shape != null && shape.events())) {
                    error(m, "@Batched actions cannot use @Quorum, @AuthorizedBy or events: an intent's owner is the caller"
                            + " (ADR-0009)");
                }
                if (hasFunds || (shape != null && (shape.sends() || shape.mint() || shape.deposits()))) {
                    error(m, "@Batched actions cannot take Funds, send or mint yet (ADR-0007 D1)");
                }
                if (!(signedBy.isEmpty() && validAfter.isEmpty() && validBefore.isEmpty())) {
                    error(m, "@Batched cannot use @SignedBy, @OnlyOwner, @ValidAfter or @ValidBefore: the batcher, not the"
                            + " caller, signs a batch; authorise with ctx.caller() and check time with Clock (ADR-0004 D1)");
                }
            }
        }

        String migrateTo = annotation(m, MIGRATE_TO).flatMap(a -> value(a, "value")).map(v -> (String) v.getValue()).orElse(null);
        if (migrateTo != null) {
            if (kind != TransitionIR.Kind.DESTROY) error(m, "@MigrateTo goes on an instance @Destroy");
            ParamIR f = fieldOf(m, r, migrateTo, "@MigrateTo");
            if (f != null && f.kind() != ParamIR.TypeKind.BYTES) error(m, "@MigrateTo must name a byte[] (the successor's policy)");
        }
        String name = m.getSimpleName().toString();
        String variant = kind == TransitionIR.Kind.MIGRATE ? "Adopt" : kind == TransitionIR.Kind.INIT ? "CreateInstance"
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
        String admitsType = annotation(m, ADMITS).map(a -> value(a, "value").map(v -> (String) v.getValue()).orElse(""))
                .orElse(null);
        boolean admits = admitsType != null;
        if (admits) {
            if (kind != TransitionIR.Kind.ACTION) error(m, "@Admits goes on an instance @Action (ADR-0012 D3)");
            else if (params.isEmpty() || params.get(0).kind() != ParamIR.TypeKind.BYTES) {
                error(m, "@Admits: the first argument after the state (and the params) must be the object's id (byte[])");
            }
            if (batched) error(m, "@Admits cannot be @Batched: the object's move must be in the same transaction");
            if (!reads.isEmpty()) error(m, "@Admits cannot take @Reads yet");
        }
        if (into != null) {
            for (ParamIR p : params) {
                if (p.name().equals("into") || p.name().equals("destination") || p.name().equals("destination_state")) {
                    error(m, "parameter name \"" + p.name() + "\" is used by a move's generated redeemer and client; rename it");
                }
            }
            if (returns != TransitionIR.Returns.STATE && returns != TransitionIR.Returns.RESULT) {
                error(m, "a move continues its object: return the owned record (or a record with its state)");
            }
            if (!reads.isEmpty()) error(m, "a move cannot take @Reads yet");
        }
        boolean receives = annotation(m, RECEIVES).isPresent();
        if (receives) {
            if (kind != TransitionIR.Kind.ACTION && kind != TransitionIR.Kind.OWNED_ACTION) {
                error(m, "@Receives goes on an @Action or @OwnedAction: it continues, and its output takes the deposits");
            }
            if (!hasFunds) error(m, "@Receives needs a Funds parameter: the method sees the deposits in funds.deposits()");
            if (batched) error(m, "@Receives cannot be @Batched");
        }
        boolean resultDeposits = shape != null && shape.deposits();
        String ownedTypeText = ownedKind ? r.types().render(r.stateType()) : null;
        return new TransitionIR(kind, name, variant, List.copyOf(params), hasCtx, payable, returns, resultType,
                List.copyOf(signedBy), List.copyOf(validAfter), List.copyOf(validBefore), shared, batched, batchOnly,
                hasFunds, shape != null && shape.payouts(), shape != null && shape.sends(), shape != null && shape.mint(),
                accepts, takesParams, quorumKeys, quorumThreshold, List.copyOf(authorizedBy), events, eventsType,
                List.copyOf(reads), migrateTo, ownedTypeText, into, admitsType, null, resultDeposits, receives);
    }

    /** Which effect components a result record has (ADR-0007 D1). */
    private record ResultShape(boolean payouts, boolean sends, boolean mint, boolean accepted, boolean events,
                               String eventsType, boolean deposits) {}

    /**
     * Reads a result record: components named {@code state} (actions: required; destroys: forbidden), {@code payouts}
     * ({@code JulcList<Payout>}), {@code sends} ({@code JulcList<Send>}) and {@code mint} ({@code Value}), nothing else.
     */
    private ResultShape resultShape(ExecutableElement m, TypeMirror ret, Reading r, boolean action) {
        String what = action ? "@" + "Action" : "@Destroy";
        String expected = action
                ? "the state (" + r.types().render(r.stateType()) + ") or a record with a 'state' component and any of"
                + " 'payouts' (JulcList<Payout>), 'sends' (JulcList<Send>), 'deposits' (JulcList<Deposit>), 'mint' (Value)"
                + " and 'accepted' (Value)"
                : "JulcList<Payout>, or a record with any of 'payouts' (JulcList<Payout>), 'sends' (JulcList<Send>),"
                + " 'deposits' (JulcList<Deposit>), 'mint' (Value) and 'accepted' (Value)";
        TypeElement e = asRecord(ret);
        if (e == null) {
            error(m, what + " must return " + expected);
            return new ResultShape(false, false, false, false, false, null, false);
        }
        boolean state = false, payouts = false, sends = false, mint = false, accepted = false, events = false, deposits = false;
        String eventsType = null;
        var known = java.util.Set.of("state", "payouts", "sends", "mint", "accepted", "events", "deposits");
        for (RecordComponentElement c : e.getRecordComponents()) {
            if (!known.contains(c.getSimpleName().toString())) {
                error(m, what + " must return " + expected + "; component '" + c.getSimpleName() + "' is none of these");
                return new ResultShape(false, false, false, false, false, null, false);
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
                case "deposits" -> {
                    if (!isListOf(t, API + "Deposit")) error(m, "the result's 'deposits' must be JulcList<Deposit>");
                    deposits = true;
                }
                case "mint" -> {
                    if (!isNamed(t, VALUE)) error(m, "the result's 'mint' must be a Value");
                    mint = true;
                }
                case "accepted" -> {
                    if (!isNamed(t, VALUE)) error(m, "the result's 'accepted' must be a Value");
                    accepted = true;
                }
                case "events" -> {
                    if (!isNamed(t, JULC_LIST)) error(m, "the result's 'events' must be a JulcList of event records");
                    events = true;
                    eventsType = r.types().render(t);
                }
                default -> error(m, "result record component '" + n + "' is not one of state, payouts, sends, mint,"
                        + " accepted;"
                        + " " + what + " must return " + expected);
            }
        }
        if (action && !state) error(m, what + " must return " + expected);
        if (!action && !(payouts || sends || mint || deposits)) error(m, what + " must return " + expected);
        return new ResultShape(payouts, sends, mint, accepted, events, eventsType, deposits);
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

    /** A purpose handler's shape and authorization (ADR-0010 D1). */
    private ContractIR.PurposeIR readPurpose(String kind, ExecutableElement m, TypeRenderer types, TypeMirror paramsType,
                                             Map<String, ParamIR> paramsByName) {
        if (!m.getModifiers().contains(Modifier.PUBLIC) || !m.getModifiers().contains(Modifier.STATIC)) {
            error(m, "purpose handlers must be public static");
        }
        var ps = m.getParameters();
        if (paramsType == null || ps.isEmpty() || !same(ps.get(0).asType(), paramsType)) {
            error(m, "a purpose handler's first parameter must be the @Params record: it is governed by the deploy-time"
                    + " parameters, since no instance is spent (ADR-0010 D1)");
        }
        List<ParamIR> args = new ArrayList<>();
        boolean hasCtx = false;
        switch (kind) {
            case "WITHDRAW" -> {
                if (ps.size() < 2 || !isNamed(ps.get(1).asType(), BIG_INTEGER)) {
                    error(m, "@Withdraw takes (Params, BigInteger amount, args..., [Ctx])");
                }
                hasCtx = ps.size() > 2 && isNamed(ps.get(ps.size() - 1).asType(), CTX);
                int last = hasCtx ? ps.size() - 1 : ps.size();
                for (int i = 2; i < last; i++) {
                    args.add(new ParamIR(ps.get(i).getSimpleName().toString(), types.render(ps.get(i).asType()),
                            kindOf(ps.get(i).asType())));
                }
                if (!isListOf(m.getReturnType(), SEND)) {
                    error(m, "@Withdraw must return JulcList<Send>: where the rewards go, claimed in the output registry");
                }
            }
            case "CERTIFY" -> requireShape(m, ps, List.of("org.julclang.ledger.TxCert"), "@Certify takes (Params, TxCert)");
            case "VOTE" -> requireShape(m, ps, List.of("org.julclang.ledger.Voter", "org.julclang.ledger.GovernanceActionId",
                    "org.julclang.ledger.Vote"), "@Vote takes (Params, Voter, GovernanceActionId, Vote)");
            case "PROPOSE" -> requireShape(m, ps, List.of("org.julclang.ledger.ProposalProcedure"),
                    "@Propose takes (Params, ProposalProcedure)");
            default -> throw new IllegalStateException(kind);
        }
        if (!kind.equals("WITHDRAW") && m.getReturnType().getKind() != TypeKind.BOOLEAN) {
            error(m, "@" + kind.charAt(0) + kind.substring(1).toLowerCase() + " must return boolean");
        }
        String qk = annotation(m, QUORUM).flatMap(a -> value(a, "keys")).map(v -> (String) v.getValue()).orElse(null);
        String qt = annotation(m, QUORUM).flatMap(a -> value(a, "threshold")).map(v -> (String) v.getValue()).orElse(null);
        List<String> auth = new ArrayList<>();
        annotation(m, AUTHORIZED_BY).flatMap(a -> value(a, "value")).ifPresent(v -> auth.add((String) v.getValue()));
        for (String path : concat(qk == null ? List.<String>of() : List.of(qk, qt), auth)) {
            if (path == null || !path.startsWith("params.")) {
                error(m, "a purpose handler's @Quorum/@AuthorizedBy must name params.* fields (ADR-0010 D1)");
            } else if (!paramsByName.containsKey(path.substring(7))) {
                error(m, "\"" + path + "\" is not a component of the @Params record");
            }
        }
        if (!kind.equals("PROPOSE") && qk == null && auth.isEmpty()) {
            error(m, "@" + kind.charAt(0) + kind.substring(1).toLowerCase() + " needs @Quorum or @AuthorizedBy over"
                    + " params.*: an open handler lets anyone drain rewards, redelegate or vote (ADR-0010 r2)");
        }
        return new ContractIR.PurposeIR(kind, m.getSimpleName().toString(), List.copyOf(args), hasCtx, qk, qt, List.copyOf(auth));
    }

    private void requireShape(ExecutableElement m, List<? extends VariableElement> ps, List<String> rest, String message) {
        boolean ok = ps.size() == rest.size() + 1;
        for (int i = 0; ok && i < rest.size(); i++) ok = isNamed(ps.get(i + 1).asType(), rest.get(i));
        if (!ok) error(m, message);
    }

    /** A field of the state/owned record, or {@code params.x} of the @Params record; reports an error if absent. */
    private ParamIR fieldOf(ExecutableElement m, Reading r, String path, String what) {
        if (path == null || path.isEmpty()) {
            error(m, what + " needs a field name");
            return null;
        }
        if (path.startsWith("params.")) {
            ParamIR p = r.paramsComponents().get(path.substring(7));
            if (p == null) error(m, what + " names \"" + path + "\", which is not a component of the @Params record");
            return p;
        }
        ParamIR c = r.components().get(path);
        if (c == null) error(m, what + " names \"" + path + "\", which is not a component of the state record");
        return c;
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
            case MIGRATE -> "Migrate";
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
            // Same-package types are imported too: JuLC follows imports only (ADR-0011: a contract reading another).
            if (!topPkg.equals("java.lang") && !topPkg.isEmpty()) {
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
