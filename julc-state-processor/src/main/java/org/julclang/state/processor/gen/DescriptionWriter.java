package org.julclang.state.processor.gen;

import org.julclang.state.processor.Records;
import org.julclang.state.processor.ir.ContractIR;
import org.julclang.state.processor.ir.ParamIR;
import org.julclang.state.processor.ir.TransitionIR;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The contract's description for JavaScript clients (ADR-0013 D3): a CIP-57 document whose definitions type the state,
 * the owned records and every redeemer variant, plus an {@code x-joust} section describing the contract's model
 * (actions, creations, views, owned types, policies, effects). Constructor indices follow the generated validator's
 * {@code permits} order, and the encodings follow JuLC's (records are constructor 0, sealed variants their position,
 * Bool False/True 0/1, Optional Some/None 0/1, ledger hashes and {@code @NewType}s their underlying type).
 */
public final class DescriptionWriter {

    static final String API = "org.julclang.state.api.";
    static final Set<String> LEDGER_HASHES = Set.of("PubKeyHash", "ScriptHash", "ValidatorHash", "PolicyId", "TokenName",
            "DatumHash", "TxId");

    private final ContractIR c;
    private final TypeElement contract;
    private final ProcessingEnvironment env;
    private final Map<String, Object> definitions = new LinkedHashMap<>();

    private DescriptionWriter(ContractIR c, TypeElement contract, ProcessingEnvironment env) {
        this.c = c;
        this.contract = contract;
        this.env = env;
    }

    /** The description as JSON. */
    public static String generate(ContractIR c, TypeElement contract, ProcessingEnvironment env) {
        return Json.write(new DescriptionWriter(c, contract, env).document());
    }

    private Map<String, Object> document() {
        String V = c.validatorName();
        var doc = new LinkedHashMap<String, Object>();
        doc.put("preamble", map("title", c.packageName() + "." + c.name(), "description",
                "JOUST contract " + c.name() + " (ADR-0013 D3)", "version", "0.0.0", "plutusVersion", "v3",
                "compiler", map("name", "joust")));

        // ---- redeemers, in the generated validator's permits order
        var spend = new ArrayList<TransitionIR>(c.spendTransitions());
        spend.addAll(c.ownedSpendTransitions());
        var calls = new ArrayList<Object>();
        for (int i = 0; i < spend.size(); i++) {
            calls.add(constructor(spend.get(i).variant(), i, redeemerFields(spend.get(i))));
        }
        if (c.hasBatched()) {
            int b = spend.size();
            calls.add(constructor("Batch", b, List.of(field("validFrom", integer()), field("validTo", integer()))));
            calls.add(constructor("InBatch", b + 1, List.of()));
            calls.add(constructor("CancelIntent", b + 2, List.of()));
        }
        String callsKey = c.packageName() + "." + V + ".Calls";
        if (!calls.isEmpty()) definitions.put(callsKey, map("title", "Calls", "anyOf", calls));

        var lifecycle = new ArrayList<Object>();
        var creations = new ArrayList<Object>();
        int li = 0;
        var createFields = new ArrayList<Object>(List.of(field("seed", txOutRef())));
        createFields.addAll(redeemerFields(c.init()));
        lifecycle.add(constructor("CreateInstance", li, createFields));
        creations.add(creation(c.init(), "init", li++));
        lifecycle.add(constructor("BurnInstance", li++, List.of()));
        for (var t : c.ownedInits()) {
            var fs = new ArrayList<Object>(List.of(field("seed", txOutRef()), field("instance", bytes())));
            fs.addAll(redeemerFields(t));
            lifecycle.add(constructor(t.variant(), li, fs));
            creations.add(creation(t, "ownedInit", li++));
        }
        if (c.hasBatched()) {
            lifecycle.add(constructor("CreateIntent", li++, List.of(field("seed", txOutRef()),
                    field("intent", data()))));
        }
        if (c.hasMints()) lifecycle.add(constructor("ByAction", li++, List.of(field("by", txOutRef()))));
        if (c.migrate() != null) {
            var fs = new ArrayList<Object>(List.of(field("seed", txOutRef())));
            fs.addAll(redeemerFields(c.migrate()));
            lifecycle.add(constructor("Adopt", li, fs));
            creations.add(creation(c.migrate(), "migrate", li++));
        }
        String lifecycleKey = c.packageName() + "." + V + ".Lifecycle";
        definitions.put(lifecycleKey, map("title", "Lifecycle", "anyOf", lifecycle));

        // ---- the state, and the owned objects' Holding datums
        String stateKey = keyOf(stateMirror());
        schemaOf(stateMirror());
        var owned = new ArrayList<Object>();
        for (var o : c.owned()) {
            TypeMirror m = ownedMirror(o.simple());
            String valueKey = keyOf(m);
            schemaOf(m);
            String holdingKey = c.packageName() + "." + V + "." + o.holding();
            definitions.put(holdingKey, map("title", o.holding(), "anyOf", List.of(constructor(o.holding(), o.tag(),
                    List.of(field("instance", bytes()), field("value", ref(valueKey)))))));
            owned.add(map("type", valueKey, "simple", o.simple(), "lookup",
                    Character.toLowerCase(o.simple().charAt(0)) + o.simple().substring(1), "holding", ref(holdingKey),
                    "tag", o.tag(), "multi", c.multiOwned()));
        }

        var validators = new ArrayList<Object>();
        validators.add(map("title", V + ".mint", "redeemer", map("title", "redeemer", "schema", ref(lifecycleKey))));
        if (!calls.isEmpty()) {
            // The spend validator's datums: the state, and each owned type's Holding (told apart by token kind).
            Object datum = ref(stateKey);
            if (!owned.isEmpty()) {
                var alts = new ArrayList<Object>(List.of(ref(stateKey)));
                for (var o : c.owned()) alts.add(ref(c.packageName() + "." + V + "." + o.holding()));
                datum = map("title", "Datum", "description", "The state (instance tokens) or an owned object's Holding (0x4f tokens)",
                        "anyOf", alts);
            }
            validators.add(map("title", V + ".spend", "datum", map("title", "datum", "schema", datum),
                    "redeemer", map("title", "redeemer", "schema", ref(callsKey))));
        }
        doc.put("validators", validators);

        // ---- x-joust: the contract's model
        var actions = new ArrayList<Object>();
        for (int i = 0; i < spend.size(); i++) actions.add(action(spend.get(i), i));
        var views = new ArrayList<Object>();
        for (var v : c.views()) views.add(view(v));
        var xj = new LinkedHashMap<String, Object>();
        // Version 2 (ADR-0015 D4): each creation and action also carries a call recipe.
        xj.put("version", 2);
        xj.put("contract", c.name());
        xj.put("package", c.packageName());
        xj.put("validator", V);
        xj.put("client", c.clientName());
        xj.put("state", ref(stateKey));
        if (c.hasParams()) {
            TypeMirror p = paramsMirror();
            schemaOf(p);
            xj.put("params", ref(keyOf(p)));
        }
        xj.put("stakeSelf", c.stakeSelf());
        xj.put("minAda", c.minAda());
        xj.put("maxDatumBytes", c.maxDatumBytes());
        xj.put("assetAware", c.assetAware());
        xj.put("owned", owned);
        xj.put("creations", creations);
        xj.put("actions", actions);
        xj.put("views", views);
        xj.put("tokens", map(
                "instance", "blake2b_256(seed txId ++ I2OSP(seed index, 2)), 32 bytes",
                "object", "0x4f ++ blake2b_224(seed txId ++ I2OSP(seed index, 2)), 29 bytes",
                "intent", "0x49 ++ blake2b_224(seed txId ++ I2OSP(seed index, 2)), 29 bytes"));
        xj.put("encoding", map("canonicalMaps", true,
                "note", "inline datums are canonical CBOR: map keys sorted (SortedMaps); records are constructor 0",
                "json", map(
                        "integer", "a decimal string (or a safe JavaScript integer)",
                        "bytes", "lowercase hex",
                        "constructor", "an object by field title; a sum type adds \"$variant\" (its constructor title)",
                        "Bool", "true or false",
                        "Optional", "null for None, else the value",
                        "list", "an array",
                        "map", "an array of [key, value] pairs, in canonical key order",
                        "Data", "hex CBOR of any Plutus data")));
        doc.put("x-joust", xj);
        doc.put("definitions", definitions);
        return doc;
    }

    // ------------------------------------------------------------------ the model

    private Map<String, Object> creation(TransitionIR t, String kind, int index) {
        var m = new LinkedHashMap<String, Object>();
        m.put("name", t.method());
        m.put("kind", kind);
        m.put("redeemer", map("purpose", "mint", "constructor", kind.equals("init") ? "CreateInstance" : kind.equals("migrate") ? "Adopt" : t.variant(),
                "index", index));
        m.put("args", args(t));
        m.put("ctx", t.hasCtx());
        m.put("payable", t.payable());
        m.put("takesAssets", ValidatorGenerator.takesAssets(t));
        if (t.owned()) m.put("owned", keyOf(ownedMirror(c.ownedOf(t).simple())));
        m.put("needsService", (t.owned() ? c.ownedOf(t).hasMaps() : c.hasMaps()) || !t.reads().isEmpty() || t.resultSends()
                || t.resultMint() || t.takesParams());
        var fields = new ArrayList<String>(List.of("seed"));
        if (kind.equals("ownedInit")) fields.add("instance");
        fields.addAll(redeemerFieldNames(t));
        m.put("recipe", recipe(t, fields, null));
        return m;
    }

    private Map<String, Object> action(TransitionIR t, int index) {
        var m = new LinkedHashMap<String, Object>();
        m.put("name", t.method());
        m.put("kind", switch (t.kind()) {
            case ACTION -> "action";
            case DESTROY -> "destroy";
            case OWNED_ACTION -> "ownedAction";
            case OWNED_DESTROY -> "ownedDestroy";
            default -> t.kind().name();
        });
        m.put("redeemer", map("purpose", "spend", "constructor", t.variant(), "index", index));
        m.put("fields", redeemerFieldNames(t));
        m.put("args", args(t));
        m.put("ctx", t.hasCtx());
        m.put("payable", t.payable());
        m.put("takesAssets", ValidatorGenerator.takesAssets(t));
        if (t.owned()) m.put("owned", keyOf(ownedMirror(c.ownedOf(t).simple())));
        m.put("policies", map(
                "signedBy", t.signedBy(),
                "validAfter", t.validAfter(),
                "validBefore", t.validBefore(),
                "quorum", t.quorumKeys() == null ? null : map("keys", t.quorumKeys(), "threshold", t.quorumThreshold()),
                "authorizedBy", t.authorizedBy(),
                "shared", t.shared(),
                "into", t.moves(),
                "admits", t.admits() ? t.admitsType() : null,
                "receives", t.receives(),
                "batched", t.batched()));
        m.put("returns", map(
                "state", t.returns() == TransitionIR.Returns.STATE || t.returns() == TransitionIR.Returns.RESULT,
                "payouts", t.hasPayouts(),
                "sends", t.resultSends(),
                "mint", t.resultMint(),
                "accepted", t.resultAccepted(),
                "events", t.resultEvents(),
                "deposits", t.resultDeposits()));
        // What a JavaScript client cannot compute alone: map proofs, intents, foreign reads, compositions.
        boolean maps = t.owned() ? c.ownedOf(t).hasMaps() : c.hasMaps();
        // Sends and mints go through the output verifier or ByAction (ADR-0007), which the page does not verify yet.
        m.put("needsService", maps || t.batched() || !t.reads().isEmpty() || t.moves() || t.resultDeposits() || t.receives()
                || t.resultSends() || t.resultMint() || t.takesParams());
        boolean destroys = t.kind() == TransitionIR.Kind.DESTROY || t.kind() == TransitionIR.Kind.OWNED_DESTROY;
        m.put("recipe", recipe(t, redeemerFieldNames(t), destroys ? map("constructor", "BurnInstance", "index", 1) : null));
        return m;
    }

    /**
     * The call recipe (ADR-0015 D4): what the generated client's {@code Call.Builder} lambdas encode, as data. The
     * transition the call runs (by method name: a transition program, or the JVM method), the redeemer's fields in
     * order ({@code seed}, {@code instance}, an argument's name, {@code ctx}, …), the burn redeemer of a destroy, and the
     * signer and time rules as fields of the target record (the state, or the owned value) with their indexes.
     */
    private Map<String, Object> recipe(TransitionIR t, List<String> redeemerFields, Map<String, Object> burn) {
        TypeMirror target = t.owned() ? ownedMirror(c.ownedOf(t).simple()) : stateMirror();
        var r = new LinkedHashMap<String, Object>();
        r.put("transition", t.method());
        r.put("redeemerFields", redeemerFields);
        if (burn != null) r.put("burn", burn);
        r.put("target", keyOf(target));
        r.put("shared", t.shared());
        r.put("signers", fieldPaths(target, t.signedBy()));
        r.put("validAfter", fieldPaths(target, t.validAfter()));
        r.put("validBefore", fieldPaths(target, t.validBefore()));
        return r;
    }

    /** Each field name with its index in {@code record}'s components (a path into the target's Data). */
    private List<Object> fieldPaths(TypeMirror record, List<String> names) {
        var out = new ArrayList<Object>();
        if (names == null) return out;
        var comps = Records.components((TypeElement) ((DeclaredType) record).asElement());
        for (String n : names) {
            int index = -1;
            for (int i = 0; i < comps.size(); i++) if (comps.get(i).getSimpleName().contentEquals(n)) index = i;
            out.add(map("field", n, "index", index));
        }
        return out;
    }

    /** A view: its name, its arguments (every parameter after the state) and its result's schema. */
    private Map<String, Object> view(org.julclang.state.processor.ir.ViewIR v) {
        var m = new LinkedHashMap<String, Object>();
        m.put("name", v.method());
        ExecutableElement e = method(v.method());
        var args = new ArrayList<Object>();
        if (e != null) {
            var ps = e.getParameters();
            for (int i = 1; i < ps.size(); i++) {
                args.add(map("name", ps.get(i).getSimpleName().toString(), "schema", schemaOf(ps.get(i).asType())));
            }
            m.put("returns", schemaOf(e.getReturnType()));
        }
        m.put("args", args);
        return m;
    }

    /** The method's arguments that the caller supplies (not the state, params, reads, Ctx, Funds), with their schemas. */
    private List<Object> args(TransitionIR t) {
        var out = new ArrayList<Object>();
        ExecutableElement m = method(t.method());
        for (ParamIR p : t.params()) {
            TypeMirror type = m == null ? null : paramType(m, p.name());
            out.add(map("name", p.name(), "type", p.type(), "schema", type == null ? data() : schemaOf(type)));
        }
        return out;
    }

    /** Redeemer fields, as the validator generator lays them out ({@code fields(t)}). */
    private List<Object> redeemerFields(TransitionIR t) {
        var out = new ArrayList<Object>();
        ExecutableElement m = method(t.method());
        if (t.moves()) out.add(field("into", bytes()));
        for (ParamIR p : t.params()) {
            TypeMirror type = m == null ? null : paramType(m, p.name());
            out.add(field(p.name(), type == null ? data() : schemaOf(type)));
        }
        if (t.hasCtx()) out.add(field("ctx", schemaOf(env.getElementUtils().getTypeElement(API + "Ctx").asType())));
        if (ValidatorGenerator.takesAssets(t)) out.add(field("assetsIn", valueSchema()));
        if (t.resultEvents()) out.add(field("events", eventsSchema(m)));
        return out;
    }

    private List<String> redeemerFieldNames(TransitionIR t) {
        var out = new ArrayList<String>();
        if (t.moves()) out.add("into");
        for (ParamIR p : t.params()) out.add(p.name());
        if (t.hasCtx()) out.add("ctx");
        if (ValidatorGenerator.takesAssets(t)) out.add("assetsIn");
        if (t.resultEvents()) out.add("events");
        return out;
    }

    // ------------------------------------------------------------------ types to CIP-57 schemas

    private Object schemaOf(TypeMirror t) {
        switch (t.getKind()) {
            case BOOLEAN: return bool();
            case INT, LONG, SHORT, BYTE: return integer();
            case ARRAY:
                if (((ArrayType) t).getComponentType().getKind() == TypeKind.BYTE) return bytes();
                return data();
            case DECLARED: break;
            default: return data();
        }
        DeclaredType d = (DeclaredType) t;
        TypeElement e = (TypeElement) d.asElement();
        String fq = e.getQualifiedName().toString();
        var args = d.getTypeArguments();
        switch (fq) {
            case "java.lang.Boolean": return bool();
            case "java.math.BigInteger", "java.lang.Long", "java.lang.Integer": return integer();
            case "java.lang.String": return bytes();
            case "org.julclang.core.PlutusData": return data();
            case "org.julclang.ledger.Value":
                return valueSchema();
            case "org.julclang.core.types.JulcList":
                return map("dataType", "list", "items", args.isEmpty() ? data() : schemaOf(args.get(0)));
            case "org.julclang.core.types.JulcMap":
                return map("dataType", "map", "keys", args.isEmpty() ? data() : schemaOf(args.get(0)),
                        "values", args.size() < 2 ? data() : schemaOf(args.get(1)));
            case "java.util.Optional":
                return map("title", "Optional", "anyOf", List.of(
                        constructor("Some", 0, List.of(field("value", args.isEmpty() ? data() : schemaOf(args.get(0))))),
                        constructor("None", 1, List.of())));
            default: break;
        }
        if (fq.startsWith("org.julclang.ledger.") && LEDGER_HASHES.contains(e.getSimpleName().toString())) return bytes();
        if (fq.startsWith("org.julclang.core.") && fq.endsWith("PlutusData")) return data();
        if (e.getKind() == ElementKind.RECORD && isNewType(e)) {
            var comps = Records.components(e);
            return comps.size() == 1 ? schemaOf(comps.get(0).asType()) : data();
        }
        if (e.getKind() == ElementKind.RECORD || isSealed(e)) {
            define(e);
            return ref(keyOf(t));
        }
        return data();
    }

    private void define(TypeElement e) {
        String key = e.getQualifiedName().toString();
        if (definitions.containsKey(key)) return;
        definitions.put(key, map("title", e.getSimpleName().toString()));   // placeholder: recursive types
        if (isSealed(e)) {
            var alts = new ArrayList<Object>();
            int i = 0;
            for (TypeMirror sub : e.getPermittedSubclasses()) {
                TypeElement se = (TypeElement) ((DeclaredType) sub).asElement();
                alts.add(constructor(se.getSimpleName().toString(), i++, fieldsOf(se)));
            }
            definitions.put(key, map("title", e.getSimpleName().toString(), "anyOf", alts));
        } else {
            definitions.put(key, map("title", e.getSimpleName().toString(), "anyOf",
                    List.of(constructor(e.getSimpleName().toString(), tagOf(e), fieldsOf(e)))));
        }
    }

    private List<Object> fieldsOf(TypeElement record) {
        var out = new ArrayList<Object>();
        for (RecordComponentElement rc : Records.components(record)) {
            out.add(field(rc.getSimpleName().toString(), schemaOf(rc.asType())));
        }
        return out;
    }

    /** A record's constructor tag: its position in its sealed parent's permits, or 0. */
    private int tagOf(TypeElement record) {
        for (TypeMirror iface : record.getInterfaces()) {
            TypeElement p = (TypeElement) ((DeclaredType) iface).asElement();
            if (isSealed(p)) {
                int i = 0;
                for (TypeMirror sub : p.getPermittedSubclasses()) {
                    if (((DeclaredType) sub).asElement().equals(record)) return i;
                    i++;
                }
            }
        }
        return 0;
    }

    private static boolean isSealed(TypeElement e) {
        return e.getKind() == ElementKind.INTERFACE && e.getModifiers().contains(Modifier.SEALED);
    }

    private static boolean isNewType(TypeElement e) {
        return e.getAnnotationMirrors().stream().anyMatch(a ->
                ((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().contentEquals("org.julclang.stdlib.annotation.NewType"));
    }

    private static String keyOf(TypeMirror t) {
        return t instanceof DeclaredType d ? ((TypeElement) d.asElement()).getQualifiedName().toString() : t.toString();
    }

    // ------------------------------------------------------------------ the contract's elements

    /** The contract's method {@code name}: the one carrying a JOUST annotation (an unannotated overload is a helper). */
    private ExecutableElement method(String name) {
        ExecutableElement any = null;
        for (ExecutableElement m : ElementFilter.methodsIn(contract.getEnclosedElements())) {
            if (!m.getSimpleName().contentEquals(name)) continue;
            boolean joust = m.getAnnotationMirrors().stream().anyMatch(a -> ((TypeElement) a.getAnnotationType().asElement())
                    .getQualifiedName().toString().startsWith("org.julclang.state.api."));
            if (joust) return m;
            if (any == null) any = m;
        }
        return any;
    }

    private static TypeMirror paramType(ExecutableElement m, String name) {
        for (var p : m.getParameters()) if (p.getSimpleName().contentEquals(name)) return p.asType();
        return null;
    }

    private TypeMirror stateMirror() {
        ExecutableElement init = method(c.init().method());
        TypeMirror ret = init.getReturnType();
        // An init may return a record with a `state` component (assets): the state is that component.
        if (ret instanceof DeclaredType d && ((TypeElement) d.asElement()).getKind() == ElementKind.RECORD
                && !keyOf(ret).equals(c.packageName() + "." + c.stateType())) {
            for (var rc : Records.components(((TypeElement) d.asElement()))) {
                if (rc.getSimpleName().contentEquals("state")) return rc.asType();
            }
        }
        return ret;
    }

    private TypeMirror ownedMirror(String simple) {
        for (TypeElement nested : ElementFilter.typesIn(contract.getEnclosedElements())) {
            if (nested.getSimpleName().contentEquals(simple)) return nested.asType();
        }
        throw new IllegalStateException("no owned record " + simple);
    }

    private TypeMirror paramsMirror() {
        String simple = c.paramsType().substring(c.paramsType().lastIndexOf('.') + 1);
        return ownedMirror(simple);
    }

    /** The ledger's TxOutRef: Constr 0 [txId bytes, index] (Plutus V3). */
    private Map<String, Object> txOutRef() {
        schemaOf(env.getElementUtils().getTypeElement("org.julclang.ledger.TxOutRef").asType());
        return ref("org.julclang.ledger.TxOutRef");
    }

    /** The {@code events} component of the method's result record, or any list. */
    private Object eventsSchema(ExecutableElement m) {
        if (m != null && m.getReturnType() instanceof DeclaredType d) {
            for (var rc : Records.components(((TypeElement) d.asElement()))) {
                if (rc.getSimpleName().contentEquals("events")) return schemaOf(rc.asType());
            }
        }
        return map("dataType", "list", "items", data());
    }

    private static Map<String, Object> valueSchema() {
        return map("title", "Value", "dataType", "map", "keys", bytes(),
                "values", map("dataType", "map", "keys", bytes(), "values", integer()));
    }

    // ------------------------------------------------------------------ schema builders

    private static Map<String, Object> integer() { return map("dataType", "integer"); }
    private static Map<String, Object> bytes() { return map("dataType", "bytes"); }
    private static Map<String, Object> data() { return map("title", "Data", "description", "Any Plutus data."); }
    private static Map<String, Object> bool() {
        return map("title", "Bool", "anyOf", List.of(constructor("False", 0, List.of()), constructor("True", 1, List.of())));
    }
    private static Map<String, Object> ref(String key) { return map("$ref", "#/definitions/" + key.replace("~", "~0").replace("/", "~1")); }
    private static Map<String, Object> constructor(String title, int index, List<Object> fields) {
        return map("title", title, "dataType", "constructor", "index", index, "fields", fields);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> field(String title, Object schema) {
        var m = new LinkedHashMap<String, Object>();
        m.put("title", title);
        m.putAll((Map<String, Object>) schema);
        if (((Map<String, Object>) schema).containsKey("title") && !((Map<String, Object>) schema).containsKey("$ref")) {
            m.put("title", title);
        }
        return m;
    }

    static Map<String, Object> map(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** A small JSON writer (no dependency in the processor). */
    static final class Json {
        static String write(Object o) {
            var sb = new StringBuilder();
            write(o, sb, 0);
            return sb.append('\n').toString();
        }

        @SuppressWarnings("unchecked")
        private static void write(Object o, StringBuilder sb, int indent) {
            if (o == null) {
                sb.append("null");
            } else if (o instanceof String s) {
                sb.append('"');
                for (char ch : s.toCharArray()) {
                    switch (ch) {
                        case '"' -> sb.append("\\\"");
                        case '\\' -> sb.append("\\\\");
                        case '\n' -> sb.append("\\n");
                        case '\t' -> sb.append("\\t");
                        default -> {
                            if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                            else sb.append(ch);
                        }
                    }
                }
                sb.append('"');
            } else if (o instanceof Number || o instanceof Boolean) {
                sb.append(o);
            } else if (o instanceof Map<?, ?> m) {
                if (m.isEmpty()) {
                    sb.append("{}");
                    return;
                }
                sb.append("{\n");
                int i = 0;
                for (var e : ((Map<String, Object>) m).entrySet()) {
                    sb.append("  ".repeat(indent + 1));
                    write(e.getKey(), sb, indent + 1);
                    sb.append(": ");
                    write(e.getValue(), sb, indent + 1);
                    if (++i < m.size()) sb.append(',');
                    sb.append('\n');
                }
                sb.append("  ".repeat(indent)).append('}');
            } else if (o instanceof List<?> l) {
                if (l.isEmpty()) {
                    sb.append("[]");
                    return;
                }
                sb.append("[\n");
                for (int i = 0; i < l.size(); i++) {
                    sb.append("  ".repeat(indent + 1));
                    write(l.get(i), sb, indent + 1);
                    if (i + 1 < l.size()) sb.append(',');
                    sb.append('\n');
                }
                sb.append("  ".repeat(indent)).append(']');
            } else {
                write(String.valueOf(o), sb, indent);
            }
        }
    }
}
