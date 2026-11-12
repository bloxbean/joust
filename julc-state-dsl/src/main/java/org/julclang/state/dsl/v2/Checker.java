package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.julclang.state.dsl.v2.Syntax.*;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.julclang.state.dsl.v2.Ir.TIME;
import static org.julclang.state.dsl.v2.Ir.WINDOW;

/**
 * Checks a {@code .jsc} 2 file and produces its typed, checked {@link Ir} (ADR-0018, milestone M0). Every rule of the
 * language is enforced here, at a {@code .jsc} position: names and shadowing (D3, J10), types and checked inputs (D5),
 * effects and audiences (D4), recursion (D7, J9) and time (D11, J6). No Java text is produced: the {@link JavaEmitter}
 * lowers the IR, and the reference {@link Interpreter} evaluates it.
 * <p>
 * The time rule needs a fixed point over the state fields that hold time values: the contract is checked again while
 * that set grows (at most once per field). The rule is monotone (more time fields only refuse more), so an error found
 * in any round is an error of the program.
 */
final class Checker {

    // ------------------------------------------------------------------ names

    static final Set<String> BUILTINS = Set.of("caller", "received", "balance", "validFrom", "validTo", "state", "params",
            "funds");
    static final Set<String> PRELUDE = Set.of("min", "max", "abs", "utf8", "asKey", "asHash", "range", "Some", "after", "before");
    /** Names no binding, field or declaration may take. */
    static final Set<String> RESERVED = Set.of(
            // Java keywords and literals
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "var", "record", "yield", "sealed",
            "permits", "null", "true", "false", "_",
            // members of Object: a record component of the same name is illegal Java
            "clone", "equals", "finalize", "getClass", "hashCode", "notify", "notifyAll", "toString", "wait",
            // names in the generated code
            "ctx", "State", "Outcome", "Optional", "JulcList", "BigInteger", "Payout", "Payouts", "Require", "Clock",
            // names the JOUST client generates (ClientGenerator.RESERVED_MEMBERS / RESERVED_PARAMS)
            "deploy", "at", "contract", "instance", "instanceId", "lovelace", "SPEC", "callerAccount", "payLovelace",
            "runtime", "INTENTS", "batch", "pendingIntents", "intent", "maps", "seed",
            // classes the generated code names as static receivers or annotations: a value of the same name would
            // obscure the class (JLS 6.4.2), a type of the same name would shadow it (Codex C6, F7, S6)
            "ByteStringLib", "CryptoLib", "BytesAdapter", "Builtins", "Ctx", "Init", "Action", "Destroy", "View",
            "MathLib", "BitwiseLib", "MathAdapter", "BitsAdapter", "CryptoAdapter", "JulcMap", "SortedMaps", "MerkleMap",
            "MerkleMaps", "MapEntry",
            "Payable", "SignedBy", "ValidAfter", "ValidBefore", "StateContract", "String", "Object", "Boolean",
            "Integer", "Long", "Byte", "Math", "System", "Record", "Override",
            // .jsc 2 type names and helper-name fragments: a declared type of the same name would share a helper (C6)
            "Int", "Bool", "Bytes", "Key", "List", "Option", "Opt", "Map", "BigMap", "Value", "Address", "Credential",
            "Hash", "Some", "None", "Pair", "Tuple2", "Enum");

    /**
     * A stopgap until bloxbean/joust#64 renames them: names the generated client binds where a method's parameters are in scope ({@code .run((s, ctx) -> { var r = …
     * })}, {@code (seed, ctx) ->}, {@code var result = …}: ClientGenerator): a parameter of the same name would not
     * compile, or would be captured (J10).
     */
    /**
     * Type names the JOUST processor generates beside a contract's own (ContractReader.GENERATED_NAMES and INTENT_NAMES):
     * a record, enum or variant of the same name would clash with them in the validator or the client (J10).
     */
    static final Set<String> PROCESSOR_TYPES = Set.of("PlutusData", "AssetEntry", "ScriptContext", "TxInfo", "TxOut", "TxInInfo",
            "TxOutRef", "ScriptInfo", "Entrypoint", "MultiValidator", "Purpose", "AddressLib", "ContextsLib", "OutputLib", "ValuesLib",
            "IntervalLib", "Interval", "IntervalBoundType", "OutputDatum", "Funds", "Send", "Sends", "Assets", "OutputClaim",
            "OutputClaims", "DepositClaim", "ByAction", "JulcValue", "Param", "Approval", "CreateInstance", "BurnInstance", "Account",
            "StateRuntime", "StateInstance", "TxResult", "Transition", "Lifecycle", "Calls", "Call", "MapWitness", "MapWitnesses",
            "Mpf", "Deploy", "ContractSpec", "StateContractHandle", "Utxo", "Arrays", "Holding", "ScriptPurpose", "Intent",
            "IntentCall", "Step", "IntentRefund", "Batch", "InBatch", "CancelIntent", "CreateIntent", "IntentCodec", "IntentData",
            "IntentHandle", "Batcher", "List");

    static Set<String> clientLocals(Kind k) {
        return switch (k) {
            case INIT -> Set.of("seed", "result");
            case VIEW -> Set.of("s");
            case ACTION, DESTROY -> Set.of("s", "r");
        };
    }

    // ------------------------------------------------------------------ state

    private final File file;
    private final String fileName;
    private final LinkedHashMap<String, LinkedHashMap<String, Ty>> records = new LinkedHashMap<>();
    private final LinkedHashMap<String, Ty> stateFields = new LinkedHashMap<>();
    /** enum → variant → fields, in declaration order (D5: the variant's index is its constructor tag). */
    private final LinkedHashMap<String, LinkedHashMap<String, LinkedHashMap<String, Ty>>> enums = new LinkedHashMap<>();
    private final Map<String, String> variantEnum = new HashMap<>();
    private final Map<String, Syntax.Fn> fns = new LinkedHashMap<>();
    private final Set<String> imported = new HashSet<>();

    /** State fields that hold time values (D11), contract-wide; grown to a fixed point. */
    private Set<String> timeFields = new TreeSet<>();
    private Set<String> nextTimeFields = new TreeSet<>();

    private final LinkedHashSet<Ir.Warning> warnings = new LinkedHashSet<>();
    private final Map<String, Set<String>> callGraph = new TreeMap<>();
    private final Set<String> analysed = new HashSet<>();
    private final Set<Ir.Local> used = Collections.newSetFromMap(new IdentityHashMap<>());
    private int seq;

    private Checker(File file, String fileName) {
        this.file = file;
        this.fileName = fileName;
    }

    static Ir.Program check(File file, String fileName) {
        var c = new Checker(file, fileName);
        c.declare();
        Ir.Program p;
        int round = 0;
        while (true) {
            c.resetRound();
            p = c.checkAll();
            if (c.nextTimeFields.equals(c.timeFields)) break;
            c.timeFields = new TreeSet<>(c.nextTimeFields);
            if (++round > c.stateFields.size() + 1) throw new IllegalStateException("time fields did not converge");
        }
        c.checkRecursion();
        return new Ir.Program(p.pkg(), p.fileName(), p.name(), p.options(), p.records(), p.enums(), p.state(), p.methods(), p.fns(),
                Set.copyOf(c.timeFields), c.recursive(), List.copyOf(c.warnings));
    }

    private void resetRound() {
        localFT.clear();
        foldFT.clear();
        warnings.clear();
        callGraph.clear();
        analysed.clear();
        used.clear();
        seq = 0;
        nextTimeFields = new TreeSet<>(timeFields);
    }

    // ------------------------------------------------------------------ declarations

    private void declare() {
        Contract c = file.contract();
        checkUserName(c.name(), c.pos(), "contract");
        var allTypes = new ArrayList<TypeDecl>(file.types());
        allTypes.addAll(c.types());
        var allEnums = new ArrayList<EnumDecl>(file.enums());
        allEnums.addAll(c.enums());
        // Records, enums and variants share one namespace: each becomes a class of the contract (D5).
        for (TypeDecl t : allTypes) {
            typeName(t.name(), t.pos(), "type", c);
            records.put(t.name(), new LinkedHashMap<>());
        }
        for (EnumDecl e : allEnums) {
            typeName(e.name(), e.pos(), "enum", c);
            if (e.variants().isEmpty()) throw e.pos().error("JSC0909", "the enum " + e.name() + " needs at least one variant");
            var vs = new LinkedHashMap<String, LinkedHashMap<String, Ty>>();
            enums.put(e.name(), vs);
            for (VariantDecl v : e.variants()) {
                typeName(v.name(), v.pos(), "variant", c);
                vs.put(v.name(), new LinkedHashMap<>());
                variantEnum.put(v.name(), e.name());
            }
        }
        for (TypeDecl t : allTypes) fillFields(records.get(t.name()), t.fields(), t.name(), false);
        for (EnumDecl e : allEnums) {
            for (VariantDecl v : e.variants()) fillFields(enums.get(e.name()).get(v.name()), v.fields(), v.name(), false);
        }
        fillFields(stateFields, c.state(), "state", true);
        for (TypeDecl t : allTypes) {
            if (reaches(new Ty.Rec(t.name()), t.name(), new HashSet<>())) {
                throw t.pos().error("JSC0301", "the type " + t.name() + " contains itself; recursive types are not supported");
            }
        }
        for (EnumDecl e : allEnums) {
            if (reaches(new Ty.EnumT(e.name()), e.name(), new HashSet<>())) {
                throw e.pos().error("JSC0302", "the enum " + e.name() + " contains itself; recursive types are not supported");
            }
        }
        if (stateFields.isEmpty()) throw c.pos().error("JSC0303", "the state needs at least one field");

        var methodNames = new HashSet<String>();
        for (Syntax.Method m : c.methods()) methodNames.add(m.name());
        var allFns = new ArrayList<Syntax.Fn>(file.fns());
        allFns.addAll(c.fns());
        for (Syntax.Fn f : allFns) {
            checkUserName(f.name(), f.pos(), "function");
            if (records.containsKey(f.name()) || enums.containsKey(f.name()) || variantEnum.containsKey(f.name())) {
                throw f.pos().error("JSC0221", "the fn " + f.name() + " has the name of a declared type or variant; rename one");
            }
            if (fns.containsKey(f.name()) || PRELUDE.contains(f.name()) || Std.ROWS.containsKey(f.name())) {
                throw f.pos().error("JSC0201", "the function " + f.name() + " is declared twice or shadows a built-in");
            }
            if (methodNames.contains(f.name())) {
                throw f.pos().error("JSC0202", "the fn " + f.name() + " has the name of a method of the contract; rename one (both become methods of one Java class)");
            }
            fns.put(f.name(), f);
        }
        for (Import im : file.imports()) {
            for (String n : im.names()) {
                Std.Binding b = Std.ROWS.get(n);
                String module = b != null ? b.module() : Std.ALSO_EXPORTED.get(n);
                if (module == null || !module.equals(im.from())) {
                    if (im.from().equals("std/value") || im.from().equals("std/address")) {
                        throw im.pos().error("JSC0304", im.from() + " comes with Value and Address in a later milestone of .jsc 2 (ADR-0018 §8: M2)");
                    }
                    throw im.pos().error("JSC0702", "'" + n + "' is not in " + im.from() + (module != null ? " (it is in " + module + ")" : ""));
                }
                if (b == null) continue;   // after/before: the prelude's
                if (fns.containsKey(n)) throw im.pos().error("JSC0703", "'" + n + "' is imported and also declared as a fn; rename the fn");
                imported.add(n);
            }
        }
        var seen = new HashSet<String>();
        // The processor names redeemer variants after methods (capitalised): they must not clash with generated or
        // declared types.
        var generated = new HashSet<>(Set.of("Payout", "State", "Outcome", "CreateInstance", "BurnInstance", "Batch", "InBatch",
                "CancelIntent", "Adopt", "Calls", "Step"));
        generated.addAll(records.keySet());
        generated.addAll(enums.keySet());
        generated.addAll(variantEnum.keySet());
        generated.add(c.name());   // the variant would shadow the contract's class in its validator
        for (Syntax.Method m : c.methods()) {
            if (m.kind() != Kind.INIT) checkUserName(m.name(), m.pos(), "method");
            String variant = Character.toUpperCase(m.name().charAt(0)) + m.name().substring(1);
            if (m.kind() != Kind.INIT && m.kind() != Kind.VIEW && !generated.add(variant)) {
                boolean declared = records.containsKey(variant) || enums.containsKey(variant) || variantEnum.containsKey(variant);
                throw m.pos().error("JSC0203", "the method name " + m.name() + " would clash with the generated name " + variant
                        + (declared ? " (a declared type or variant): a method's redeemer is a Java class named after it, beside the"
                        + " contract's types and variants" : "") + "; rename it");
            }
            if (!seen.add(m.name())) throw m.pos().error("JSC0204", "the method " + m.name() + " is declared twice");
        }
        if (c.methods().stream().noneMatch(m -> m.kind() == Kind.INIT)) throw c.pos().error("JSC0401", "the contract needs an init(…)");
    }

    /** A declared type, enum or variant name: capitalised, without '_', not reserved, not taken (D5, J10). */
    private void typeName(String n, Pos p, String what, Contract c) {
        if (!Character.isUpperCase(n.charAt(0))) throw p.error("JSC0205", what + " names start with a capital: " + n);
        if (n.contains("_")) throw p.error("JSC0206", what + " names are written without '_' (e.g. " + n.replace("_", "") + ")");
        checkUserName(n, p, what);
        if (PROCESSOR_TYPES.contains(n)) {
            throw p.error("JSC0220", "'" + n + "' is a name the generated validator or client uses for its own types; choose another " + what + " name");
        }
        if (n.equals(c.name())) {
            throw p.error("JSC0207", "the " + what + " " + n + " has the contract's name; rename one (it is generated inside the contract's class)");
        }
        if (records.containsKey(n) || enums.containsKey(n) || variantEnum.containsKey(n)) {
            throw p.error("JSC0910", "the name " + n + " is declared twice: types, enums and variants share one namespace (each becomes a Java class)");
        }
    }

    private void fillFields(LinkedHashMap<String, Ty> into, List<FieldDecl> fields, String owner, boolean state) {
        for (FieldDecl f : fields) {
            checkUserName(f.name(), f.pos(), "field");
            // A state field is read by its bare name, so it must not take the name of a built-in: a field called
            // caller would replace the signed caller in every method (Codex C1, security S2).
            if (state && (BUILTINS.contains(f.name()) || PRELUDE.contains(f.name()))) {
                throw f.pos().error("JSC0208", "'" + f.name() + "' is a built-in name, so it cannot be a state field: choose another name");
            }
            if (into.put(f.name(), resolve(f.type(), state)) != null) throw f.pos().error("JSC0209", owner + " declares " + f.name() + " twice");
        }
    }

    Ty resolve(TypeRef t) {
        return resolve(t, false);
    }

    /** A written type; {@code stateField}: the type of a state field, the only place a BigMap may be (D7). */
    Ty resolve(TypeRef t, boolean stateField) {
        int n = t.args().size();
        Ty ty = switch (t.name()) {
            case "Int" -> Ty.INT;
            case "Bool" -> Ty.BOOL;
            case "Bytes" -> Ty.BYTES;
            case "Key" -> Ty.KEY;
            case "Hash" -> Ty.HASH;
            case "List" -> n == 1 ? new Ty.ListT(resolve(t.args().get(0))) : null;
            case "Option" -> {
                if (n != 1) yield null;
                Ty e = resolve(t.args().get(0));
                if (e instanceof Ty.OptT) throw t.pos().error("JSC0305", "Option<Option<…>> is not allowed: JavaScript cannot tell Some(None) from None");
                yield new Ty.OptT(e);
            }
            case "Map" -> {
                if (n != 2) throw t.pos().error("JSC0801", "a Map has a key and a value type: Map<Key, Int>");
                yield new Ty.MapT(mapKey(t.args().get(0), "Map"), resolve(t.args().get(1)));
            }
            case "BigMap" -> {
                if (n != 2) throw t.pos().error("JSC0802", "a BigMap has a key and a value type: BigMap<Key, Int>");
                if (!stateField) {
                    throw t.pos().error("JSC0803", "a BigMap is a state field only: it cannot be a parameter, a record or enum field, an element"
                            + " or a const (ADR-0018 D7)");
                }
                Ty k = mapKey(t.args().get(0), "BigMap");
                Ty v = resolve(t.args().get(1));
                if (!v.equals(Ty.INT) && !v.equals(Ty.BYTES)) {
                    throw t.args().get(1).pos().error("JSC0804", "a BigMap holds Int or Bytes values (ADR-0018 D5), not " + Ty.show(v));
                }
                yield new Ty.BigMapT(k, v);
            }
            case "Value", "Address", "Credential" ->
                    throw t.pos().error("JSC0306", t.name() + " comes in a later milestone of .jsc 2 (ADR-0018 §8: M2)");
            default -> records.containsKey(t.name()) ? new Ty.Rec(t.name()) : enums.containsKey(t.name()) ? new Ty.EnumT(t.name()) : null;
        };
        if (ty == null && variantEnum.containsKey(t.name())) {
            throw t.pos().error("JSC0911", t.name() + " is a variant of " + variantEnum.get(t.name()) + ", not a type: write " + variantEnum.get(t.name()));
        }
        if (ty == null || (n > 0 && !(ty instanceof Ty.ListT || ty instanceof Ty.OptT || ty instanceof Ty.MapT || ty instanceof Ty.BigMapT))) {
            throw t.pos().error("JSC0307", "unknown type '" + t.name() + "' (Int, Bool, Bytes, Key, Hash, List<T>, Option<T>, Map<K, V>, BigMap<K, V>,"
                    + " or a declared type or enum)");
        }
        return ty;
    }

    /** A map key type: Bytes, Key or Hash, at most 64 bytes (D5, F19). */
    private Ty mapKey(TypeRef k, String what) {
        Ty t = resolve(k);
        if (!Ty.isBytes(t)) {
            throw k.pos().error("JSC0308", "a " + what + "'s keys are Bytes, Key or Hash (at most 64 bytes, canonical order: ADR-0018 D5), not " + Ty.show(t));
        }
        return t;
    }

    private boolean reaches(Ty t, String target, Set<String> seen) {
        return switch (t) {
            case Ty.Prim p -> false;
            case Ty.ListT l -> reaches(l.elem(), target, seen);
            case Ty.OptT o -> reaches(o.elem(), target, seen);
            case Ty.MapT m -> reaches(m.val(), target, seen);
            case Ty.BigMapT m -> false;
            case Ty.Rec r -> seen.add(r.name()) && records.getOrDefault(r.name(), new LinkedHashMap<>()).values().stream()
                    .anyMatch(f -> named(f, target) || reaches(f, target, seen));
            case Ty.EnumT e -> seen.add(e.name()) && components(e.name()).stream()
                    .anyMatch(f -> named(f, target) || reaches(f, target, seen));
        };
    }

    private static boolean named(Ty t, String name) {
        return t instanceof Ty.Rec r && r.name().equals(name) || t instanceof Ty.EnumT e && e.name().equals(name);
    }

    /** The fields of a record, the state or a variant. */
    private LinkedHashMap<String, Ty> fieldsOf(String rec) {
        if (rec.equals("State")) return stateFields;
        if (records.containsKey(rec)) return records.get(rec);
        String e = variantEnum.get(rec);
        return e == null ? null : enums.get(e).get(rec);
    }

    private List<Ty> fieldTypes(String rec) {
        var f = fieldsOf(rec);
        return f == null ? List.of() : List.copyOf(f.values());
    }

    /** The component types of a record, or of every variant of an enum (for checks and BigMap containment). */
    private List<Ty> components(String name) {
        if (enums.containsKey(name)) {
            var out = new ArrayList<Ty>();
            enums.get(name).values().forEach(v -> out.addAll(v.values()));
            return out;
        }
        return fieldTypes(name);
    }

    private static void checkUserName(String n, Pos p, String what) {
        if (n.contains("__")) throw p.error("JSC0210", "names cannot contain '__' (reserved for generated code): " + n);
        if (RESERVED.contains(n)) throw p.error("JSC0211", "'" + n + "' is reserved and cannot be a " + what + " name");
    }

    // ------------------------------------------------------------------ scopes

    /** Where code is being checked. */
    private final class Sc {
        final Ir.Kind kind;            // null in fn bodies
        final String owner;            // method or fn name
        final boolean hasState, hasCtx, effects, inLambda;
        /** Inside code the lowering extracts into a helper (lambda bodies, arms, loop bodies, ?? defaults, value blocks). */
        final int helperDepth;
        final Sc parent;
        final LinkedHashMap<String, Ir.Local> vars = new LinkedHashMap<>();
        final List<Ir.Local> consts = new ArrayList<>();

        Sc(Ir.Kind kind, String owner, boolean hasState, boolean hasCtx, boolean effects, boolean inLambda, int helperDepth,
           Sc parent) {
            this.kind = kind;
            this.owner = owner;
            this.hasState = hasState;
            this.hasCtx = hasCtx;
            this.effects = effects;
            this.inLambda = inLambda;
            this.helperDepth = helperDepth;
            this.parent = parent;
        }

        Sc child(boolean effects, boolean lambda) {
            return new Sc(kind, owner, hasState, hasCtx, effects, inLambda || lambda, helperDepth + 1, this);
        }

        Ir.Local lookup(String n) {
            for (Sc c = this; c != null; c = c.parent) {
                Ir.Local v = c.vars.get(n);
                if (v != null) return v;
            }
            return null;
        }

        Ir.Local declare(String n, Ty t, int taint, Pos p, boolean isConst, Map<String, Integer> fieldTaints) {
            checkUserName(n, p, "local");
            if (lookup(n) != null) throw p.error("JSC0212", "'" + n + "' is already declared; names are never redeclared or shadowed");
            if (hasState && (stateFields.containsKey(n) || BUILTINS.contains(n))) {
                throw p.error("JSC0213", "'" + n + "' would hide the state field or built-in of the same name; choose another name (e.g. new"
                        + Character.toUpperCase(n.charAt(0)) + n.substring(1) + ")");
            }
            if (!hasState && BUILTINS.contains(n)) throw p.error("JSC0214", "'" + n + "' is a built-in name");
            return put(n, t, taint, p, isConst, fieldTaints);
        }

        Ir.Local put(String n, Ty t, int taint, Pos p, boolean isConst, Map<String, Integer> fieldTaints) {
            declaredName(n, p);
            var l = new Ir.Local(n, t, taint, p, ++seq, fieldTaints);
            vars.put(n, l);
            if (isConst) consts.add(l);
            else used.add(l);
            return l;
        }
    }

    /**
     * Security S2 (D3): a binding (a parameter, const, lambda or loop variable, or match binding) never takes the name of
     * a declared type, enum, variant or fn, which it would silently replace: {@code const Open: Phase = phase} would turn
     * {@code phase == Open} into a check that always holds.
     */
    private void declaredName(String n, Pos p) {
        String what = records.containsKey(n) ? "the type" : enums.containsKey(n) ? "the enum" : variantEnum.containsKey(n)
                ? "the variant " + n + " of " + variantEnum.get(n) + "," : fns.containsKey(n) ? "the fn" : null;
        if (what == null) return;
        if (what.startsWith("the variant")) what = what.substring(0, what.length() - 1);
        else what = what + " " + n;
        throw p.error("JSC0222", "'" + n + "' would hide " + what + "; a name is bound once and never replaces a declaration");
    }

    private void unusedConsts(Sc sc) {
        for (Ir.Local v : sc.consts) {
            if (!used.contains(v)) throw v.pos.error("JSC0215", "'" + v.name + "' is never used (a const that is not a check must be used; use require(…) for checks)");
        }
    }

    // ------------------------------------------------------------------ the contract

    private Ir.Program checkAll() {
        Contract c = file.contract();
        var options = new ArrayList<Ir.ContractOption>();
        for (Syntax.Option o : c.options()) {
            switch (o.name()) {
                case "minAda", "maxDatumBytes", "maxValidityMs" -> {
                    if (o.value().signum() < 0 || o.value().bitLength() > 62) throw o.pos().error("JSC0402", o.name() + " is out of range");
                    options.add(new Ir.ContractOption(o.name(), o.value()));
                }
                default -> throw o.pos().error("JSC0403", "unknown contract option '" + o.name() + "' (minAda, maxDatumBytes, maxValidityMs)");
            }
        }
        var methods = new ArrayList<Ir.Method>();
        for (Syntax.Method m : c.methods()) methods.add(method(m));
        var irFns = new ArrayList<Ir.Fn>();
        for (Syntax.Fn f : fns.values()) irFns.add(checkFn(f, new int[f.params().size()]));
        var recs = new LinkedHashMap<String, Ir.RecordType>();
        var allTypes = new ArrayList<TypeDecl>(file.types());
        allTypes.addAll(c.types());
        for (TypeDecl t : allTypes) recs.put(t.name(), new Ir.RecordType(t.name(), records.get(t.name()), t.pos()));
        var ens = new LinkedHashMap<String, Ir.EnumType>();
        var allEnums = new ArrayList<EnumDecl>(file.enums());
        allEnums.addAll(c.enums());
        for (EnumDecl e : allEnums) {
            var vs = new LinkedHashMap<String, Ir.RecordType>();
            for (VariantDecl v : e.variants()) vs.put(v.name(), new Ir.RecordType(v.name(), enums.get(e.name()).get(v.name()), v.pos()));
            ens.put(e.name(), new Ir.EnumType(e.name(), vs, e.pos()));
        }
        return new Ir.Program(file.pkg(), fileName, c.name(), options, recs, ens, new Ir.RecordType("State", stateFields, c.pos()),
                methods, irFns, Set.of(), Set.of(), List.of());
    }

    private static Ir.Kind kind(Kind k) {
        return switch (k) {
            case INIT -> Ir.Kind.INIT;
            case ACTION -> Ir.Kind.ACTION;
            case DESTROY -> Ir.Kind.DESTROY;
            case VIEW -> Ir.Kind.VIEW;
        };
    }

    private boolean usesCtx(Syntax.Method m) {
        if (m.kind() == Kind.DESTROY) return true;
        if (m.kind() == Kind.VIEW) return false;
        for (Mod mod : m.mods()) if (!mod.name().equals("open") && !mod.name().equals("only")) return true;
        return mentionsCtx(m.body());
    }

    private Ir.Method method(Syntax.Method m) {
        boolean hasState = m.kind() != Kind.INIT;
        boolean ctx = usesCtx(m);
        boolean pays = paysIn(m.body());
        if (pays && (m.kind() == Kind.INIT || m.kind() == Kind.VIEW)) {
            throw firstPay(m.body()).error("JSC0404", (m.kind() == Kind.INIT ? "an init" : "a view") + " cannot pay (ADR-0018 D4)");
        }
        var sc = new Sc(kind(m.kind()), m.name(), hasState, ctx, m.kind() == Kind.ACTION || m.kind() == Kind.DESTROY, false, 0, null);
        var mods = new ArrayList<Ir.Mod>();
        boolean audience = false;
        for (Mod mod : m.mods()) {
            if (m.kind() == Kind.INIT && !mod.name().equals("payable")) throw mod.pos().error("JSC0405", "an init takes only 'payable'");
            switch (mod.name()) {
                case "payable" -> {
                    if (m.kind() == Kind.DESTROY) throw mod.pos().error("JSC0406", "a destroy cannot be payable");
                }
                case "open" -> audience = true;
                case "only" -> {
                    stateField(mod.arg(), Ty.KEY, mod.pos(), "only");
                    audience = true;
                }
                case "after", "before" -> stateField(mod.arg(), Ty.INT, mod.pos(), mod.name());
                default -> throw mod.pos().error("JSC0407", "unknown modifier " + mod.name());
            }
            mods.add(new Ir.Mod(mod.name(), mod.arg()));
        }
        if (pays && !audience) {
            throw m.pos().error("JSC0408", m.name() + " can pay out, so it must say who may call it: add 'only <field>' or 'open' (ADR-0018 D4)");
        }
        if (m.kind() == Kind.DESTROY && !pays) {
            throw m.pos().error("JSC0409", "a destroy ends the instance, so it must pay out what it holds: pay(…) at least once (ADR-0018 D4)");
        }

        var params = new ArrayList<Ir.Local>();
        var checked = new ArrayList<Ir.Local>();
        for (Param p : m.params()) {
            if (clientLocals(m.kind()).contains(p.name())) {
                throw p.pos().error("JSC0216", "'" + p.name() + "' is a name the generated client uses for its own values; rename the parameter");
            }
            Ty t = resolve(p.type());
            Ir.Local l;
            if (m.kind() == Kind.INIT) {
                // An init has no input state: its parameters may share the names of the fields it creates (D3).
                checkUserName(p.name(), p.pos(), "parameter");
                if (BUILTINS.contains(p.name())) throw p.pos().error("JSC0214", "'" + p.name() + "' is a built-in name");
                if (sc.lookup(p.name()) != null) throw p.pos().error("JSC0309", "duplicate parameter " + p.name());
                l = sc.put(p.name(), t, 0, p.pos(), false, null);
            } else {
                l = sc.declare(p.name(), t, 0, p.pos(), false, null);
            }
            params.add(l);
            // Checked inputs (D5): every Key nested in a parameter is 28 bytes, before user code reads it.
            if (m.kind() != Kind.VIEW && Ty.needsCheck(t, this::components)) checked.add(l);
        }

        Syntax.Block b = m.body();
        var stmts = stmts(b.stmts(), sc);
        Ir.Expr result = null;
        Ty resultType;
        switch (m.kind()) {
            case INIT, ACTION -> {
                if (b.result() == null) {
                    throw b.pos().error("JSC0410", m.kind() == Kind.INIT ? "an init returns the first state: return { … }"
                            : "an action evaluates to the next state: return { ...state, … }");
                }
                result = expr(b.result(), sc, Ty.STATE);
                expectType(Ty.STATE, result, b.result().pos());
                recordTimeFields(result, m.name());
                resultType = Ty.STATE;
            }
            case DESTROY -> {
                if (b.result() != null) throw b.result().pos().error("JSC0411", "a destroy ends the instance: it has no next state");
                resultType = null;
                if (!mentions(b, "balance")) {
                    warn(m.pos(), "JSC9001", m.name() + " never reads balance; a destroy must pay out everything the instance holds");
                }
            }
            case VIEW -> {
                if (b.result() == null) throw b.pos().error("JSC0412", "a view evaluates to its value");
                resultType = resolve(m.viewType());
                result = expr(b.result(), sc, resultType);
                expectType(resultType, result, b.result().pos());
            }
            default -> throw new IllegalStateException();
        }
        unusedConsts(sc);
        return new Ir.Method(kind(m.kind()), m.name(), params, mods, checked, ctx, pays, new Ir.Block(stmts, result, b.pos()),
                resultType, m.pos());
    }

    private void stateField(String name, Ty want, Pos p, String mod) {
        Ty t = stateFields.get(name);
        if (t == null) throw p.error("JSC0310", "'" + mod + " " + name + "': no state field " + name);
        if (!t.equals(want)) throw p.error("JSC0311", "'" + mod + " " + name + "' needs a " + Ty.show(want) + " field; " + name + " is " + Ty.show(t));
    }

    private void warn(Pos p, String code, String message) {
        warnings.add(new Ir.Warning(p.line(), p.col(), code, message));
    }

    // ------------------------------------------------------------------ time fields (D11)

    /**
     * A field's taint in a state value; {@code warn}, the part that is not a plain copy of the same field (what the
     * bias warning is about); where it came from; and whether the value was followed as a whole rather than per field.
     */
    private record FT(int taint, int warn, Pos pos, boolean whole) {}

    /** Per-field taints of state-typed locals (consts, fold accumulators) and of folds over the state (S7, S8). */
    private final Map<Ir.Local, Map<String, FT>> localFT = new IdentityHashMap<>();
    private final Map<Ir.Expr, Map<String, FT>> foldFT = new IdentityHashMap<>();

    /**
     * State fields the method's result sets to a time value become time fields (D11). The result's per-field taint is
     * followed through every form a state value can take (Codex C2, F1, S1): object literals and their spread,
     * conditionals, value ifs and blocks, consts of the state type, and folds whose accumulator is the state (S7).
     * Anything else marks every field with the value's taint, and the warning says so. A time value reaching a field
     * other than as a copy of that same field is warned about: this call's window (C9, F4, S4), or a stored time copied
     * into another field (S8).
     */
    private void recordTimeFields(Ir.Expr result, String method) {
        var wholeWarned = new boolean[1];
        stateTaints(result).forEach((f, ft) -> {
            if ((ft.taint() & TIME) != 0) nextTimeFields.add(f);
            if ((ft.warn() & TIME) == 0) return;
            if (ft.whole()) {
                if (!wholeWarned[0]) {
                    wholeWarned[0] = true;
                    warn(ft.pos(), "JSC9002", method + " builds its next state from a time value with an expression the checker follows as a"
                            + " whole, not field by field, so every state field becomes a time field; build it as"
                            + " { ...state, f: … } to keep the others ordinary (ADR-0018 D11)");
                }
            } else if ((ft.warn() & WINDOW) != 0) {
                warn(ft.pos(), "JSC9003", method + " stores a value of the validity window in " + f + ": whoever submits the transaction"
                        + " chooses the window, so validFrom can be up to maxValidityMs early and validTo that much late"
                        + " (ADR-0018 D11)");
            } else {
                warn(ft.pos(), "JSC9004", method + " stores a value derived from a stored time in " + f + ": that time came from an earlier"
                        + " call's validity window, so its bias (up to maxValidityMs) carries into " + f + " (ADR-0018 D11)");
            }
        });
    }

    private Map<String, FT> stateTaints(Ir.Expr e) {
        var out = new LinkedHashMap<String, FT>();
        switch (e) {
            case Ir.New n when n.record().equals("State") -> {
                var names = List.copyOf(stateFields.keySet());
                for (int i = 0; i < names.size(); i++) out.put(names.get(i), fieldFT(names.get(i), n.values().get(i)));
                return out;
            }
            case Ir.StateRef s -> {
                for (String f : stateFields.keySet()) out.put(f, new FT(timeFields.contains(f) ? TIME : 0, 0, s.pos(), false));
                return out;
            }
            case Ir.LocalRef l when localFT.containsKey(l.local()) -> {
                localFT.get(l.local()).forEach((f, ft) -> out.put(f, new FT(ft.taint(), ft.warn(), ft.pos(), ft.whole())));
                return out;
            }
            case Ir.Cond c -> {
                return merge(stateTaints(c.then()), stateTaints(c.otherwise()));
            }
            case Ir.IfValue i -> {
                return merge(stateTaints(i.then().result()), stateTaints(i.otherwise().result()));
            }
            case Ir.BlockValue b -> {
                return stateTaints(b.block().result());
            }
            case Ir.Match m -> {
                Map<String, FT> acc = null;
                for (Ir.Arm a : m.arms()) acc = acc == null ? stateTaints(a.body().result()) : merge(acc, stateTaints(a.body().result()));
                return acc;
            }
            case Ir.Fold f when foldFT.containsKey(f) -> {
                return new LinkedHashMap<>(foldFT.get(f));
            }
            default -> {
                for (String f : stateFields.keySet()) out.put(f, new FT(e.taint(), e.taint(), e.pos(), true));
                return out;
            }
        }
    }

    /** One field of a state literal: a copy of the same field keeps that field's taint and warns about nothing new. */
    private FT fieldFT(String field, Ir.Expr v) {
        if (v instanceof Ir.StateField sf && sf.field().equals(field)) return new FT(v.taint(), 0, v.pos(), false);
        if (v instanceof Ir.GetField g && g.field().equals(field) && g.record().equals("State")) {
            if (g.target() instanceof Ir.StateRef) return new FT(v.taint(), 0, v.pos(), false);
            if (g.target() instanceof Ir.LocalRef l && localFT.containsKey(l.local())) {
                FT ft = localFT.get(l.local()).get(field);
                return new FT(ft.taint(), ft.warn(), ft.pos(), ft.whole());
            }
        }
        return new FT(v.taint(), v.taint(), v.pos(), false);
    }

    private static Map<String, FT> merge(Map<String, FT> a, Map<String, FT> b) {
        var out = new LinkedHashMap<String, FT>(a);
        b.forEach((f, ft) -> out.merge(f, ft, (x, y) -> new FT(x.taint() | y.taint(), x.warn() | y.warn(),
                (x.warn() & WINDOW) != 0 || (x.warn() != 0 && (y.warn() & WINDOW) == 0) || y.warn() == 0 ? x.pos() : y.pos(),
                x.whole() || y.whole())));
        return out;
    }

    private static Map<String, Integer> taintsOnly(Map<String, FT> m) {
        var out = new LinkedHashMap<String, Integer>();
        m.forEach((f, ft) -> out.put(f, ft.taint()));
        return out;
    }

    private int fieldTaint(Ir.Expr target, String field) {
        if (target instanceof Ir.StateRef) return timeFields.contains(field) ? TIME : 0;
        if (target instanceof Ir.LocalRef l && l.local().fieldTaints != null) return l.local().fieldTaints.getOrDefault(field, l.taint());
        return target.taint();
    }

    // ------------------------------------------------------------------ functions

    private Ir.Fn checkFn(Syntax.Fn f, int[] taints) {
        var sc = new Sc(null, f.name(), false, false, false, false, 0, null);
        var params = new ArrayList<Ir.Local>();
        for (int i = 0; i < f.params().size(); i++) {
            Param p = f.params().get(i);
            params.add(sc.declare(p.name(), resolve(p.type()), taints[i], p.pos(), false, null));
        }
        Ty ret = resolve(f.ret());
        var stmts = stmts(f.body().stmts(), sc);
        if (f.body().result() == null) throw f.body().pos().error("JSC0312", "a fn ends in its value");
        Ir.Expr r = expr(f.body().result(), sc, ret);
        expectType(ret, r, f.body().result().pos());
        unusedConsts(sc);
        return new Ir.Fn(f.name(), params, ret, new Ir.Block(stmts, r, f.body().pos()), f.pos());
    }

    /** D11 through calls: a fn called with time arguments is checked again with those parameters marked (S25). */
    private void analyseCall(Syntax.Fn f, int[] taints, Pos at) {
        boolean any = false;
        for (int t : taints) any |= t != 0;
        if (!any) return;
        if (!analysed.add(f.name() + "/" + Arrays.toString(taints))) return;
        try {
            checkFn(f, taints);
        } catch (JscException e) {
            throw new JscException(e.line, e.col, e.code, e.getMessage() + " (in " + f.name() + ", called with a time value at " + at + ")");
        }
    }

    // ------------------------------------------------------------------ statements

    private List<Ir.Stmt> stmts(List<Stmt> ss, Sc sc) {
        var out = new ArrayList<Ir.Stmt>();
        for (Stmt s : ss) {
            switch (s) {
                case Const c -> {
                    // A const may be typed State (the next state, built in steps); elsewhere State is not written.
                    boolean st = c.type() != null && c.type().name().equals("State") && c.type().args().isEmpty() && sc.kind != null;
                    Ty want = c.type() == null ? null : st ? Ty.STATE : resolve(c.type());
                    Ir.Expr v = expr(c.value(), sc, want);
                    if (want != null) expectType(want, v, c.value().pos());
                    Ty t = want != null ? want : v.type();
                    noBigWrite(t, v, c.value().pos(), "bound to a const");
                    if (v instanceof Ir.Range) throw c.value().pos().error("JSC1009", RANGE_ONLY);
                    var fts = t.equals(Ty.STATE) ? stateTaints(v) : null;
                    if ("None".equals(c.name()) || "Some".equals(c.name())) throw c.pos().error("JSC0214", "'" + c.name() + "' is a built-in name");
                    Ir.Local local = sc.declare(c.name(), t, v.taint(), c.pos(), true, fts == null ? null : taintsOnly(fts));
                    if (fts != null) localFT.put(local, fts);
                    out.add(new Ir.Let(local, v, c.pos()));
                }
                case Syntax.Require r -> out.add(new Ir.Require(requireCond(r.cond(), sc), r.message(), r.pos()));
                case Syntax.Pay p -> {
                    if (!sc.effects) {
                        throw p.pos().error("JSC0413", "pay(…) is allowed only in an action or destroy, not in a fn, view, lambda or value block (ADR-0018 D4)");
                    }
                    Ir.Expr to = expr(p.to(), sc, Ty.KEY);
                    expectType(Ty.KEY, to, p.to().pos());
                    Ir.Expr amount = expr(p.amount(), sc, Ty.INT);
                    expectType(Ty.INT, amount, p.amount().pos());
                    if ((amount.taint() & TIME) != 0) {
                        if (!isClampedAtZero(p.amount())) {
                            throw p.amount().pos().error("JSC0501", "a time value sets this payout: write pay(to, max(0, …)) so its sign cannot"
                                    + " make the call fail (ADR-0018 D11)");
                        }
                        warn(p.amount().pos(), "JSC9005", "a time value sets this payout: whoever submits the transaction chooses the"
                                + " window, so validFrom can be up to maxValidityMs early and validTo that much late, and the"
                                + " amount moves with it (ADR-0018 D11)");
                    }
                    out.add(new Ir.Pay(to, amount, p.pos()));
                }
                case IfStmt i -> {
                    Ir.Expr cond = condition(i.cond(), sc);
                    Ir.Block then = armBlock(i.then(), sc);
                    Ir.Block other = i.otherwise() == null ? null : armBlock(i.otherwise(), sc);
                    boolean pays = paysIn(i.then()) || (i.otherwise() != null && paysIn(i.otherwise()));
                    out.add(new Ir.If(cond, then, other, pays, i.pos()));
                }
                case MatchStmt ms -> out.add(matchStmt(ms.match(), sc));
                case For f -> {
                    Ir.Expr list = isRange(f.list(), sc) ? range((Call) f.list(), sc) : expr(f.list(), sc, null);
                    if (!(list.type() instanceof Ty.ListT lt)) throw f.list().pos().error("JSC1010", "for (const x of …) needs a List, not " + Ty.show(list.type()));
                    if (f.body().result() != null) throw f.body().result().pos().error("JSC1011", "a for loop's body has no value; accumulate with fold");
                    Sc inner = sc.child(sc.effects, false);
                    Ir.Local var = inner.declare(f.var(), lt.elem(), list.taint(), f.pos(), false, null);
                    var body = stmts(f.body().stmts(), inner);
                    unusedConsts(inner);
                    out.add(new Ir.For(var, list, new Ir.Block(body, null, f.body().pos()), paysIn(f.body()), f.pos()));
                }
            }
        }
        return out;
    }

    /** An arm of an if statement: statements only, checked in their own scope. */
    private Ir.Block armBlock(Syntax.Block b, Sc sc) {
        if (b.result() != null) {
            if (b.returnPos() != null) {
                throw b.returnPos().error("JSC0313", "there is no early return in .jsc: a block's value is its last expression; make this if"
                        + " the block's last expression, with an else (if c { … } else { … })");
            }
            throw b.result().pos().error("JSC0314", "this if is a statement (it is not the block's last expression, or it has no else),"
                    + " so its arms end in statements, not a value");
        }
        Sc inner = sc.child(sc.effects, false);
        var stmts = stmts(b.stmts(), inner);
        unusedConsts(inner);
        return new Ir.Block(stmts, null, b.pos());
    }

    /** A block used as a value: {@code const} and {@code require} statements, then its value. */
    private Ir.Block valueBlock(Syntax.Block b, Sc inner, Ty expected) {
        for (Stmt s : b.stmts()) {
            if (!(s instanceof Const) && !(s instanceof Syntax.Require)) {
                throw s.pos().error("JSC0315", "a block used as a value holds only const and require statements");
            }
        }
        var stmts = stmts(b.stmts(), inner);
        Ir.Expr r = expr(b.result(), inner, expected);
        unusedConsts(inner);
        return new Ir.Block(stmts, r, b.pos());
    }

    private static boolean isClampedAtZero(Expr e) {
        return e instanceof Call c && c.fn().equals("max") && c.args().size() == 2
                && (isZero(c.args().get(0)) || isZero(c.args().get(1)));
    }

    private static boolean isZero(Expr e) {
        return e instanceof IntLit i && i.value().signum() == 0;
    }

    // ------------------------------------------------------------------ conditions and time positions (D11)

    /** A Bool that decides control flow: a time value may not (D11, S1's defence in depth). */
    private Ir.Expr condition(Expr e, Sc sc) {
        Ir.Expr c = expr(e, sc, Ty.BOOL);
        expectType(Ty.BOOL, c, e.pos());
        decides(c, e.pos());
        return c;
    }

    private static void decides(Ir.Expr c, Pos p) {
        if ((c.taint() & TIME) != 0) {
            throw p.error("JSC0502", "a time value cannot decide here: compare times only with after(t) or before(t) in a require (ADR-0018 D11)");
        }
    }

    /** A require's condition: after/before allowed at its top and under && / || there (positive positions). */
    private Ir.Expr requireCond(Expr e, Sc sc) {
        if (e instanceof Binary b && (b.op().equals("&&") || b.op().equals("||"))) {
            Ir.Expr l = requireCond(b.left(), sc);
            Ir.Expr r = requireCond(b.right(), sc);
            return new Ir.Logic(b.op(), l, r, Ty.BOOL, 0, b.pos());
        }
        if (e instanceof Call c && (c.fn().equals("after") || c.fn().equals("before"))) return timePredicate(c, sc);
        return condition(e, sc);
    }

    private Ir.Expr timePredicate(Call c, Sc sc) {
        if (!sc.hasCtx) throw c.pos().error("JSC0503", c.fn() + "(t) needs the transaction: not in a view or fn");
        if (sc.inLambda) {
            throw c.pos().error("JSC0504", c.fn() + "(t) is not used inside a lambda (ADR-0018 D11): require it outside the list method");
        }
        if (c.args().size() != 1) throw c.pos().error("JSC0316", c.fn() + " takes one argument");
        Ir.Expr t = expr(c.args().get(0), sc, Ty.INT);
        expectType(Ty.INT, t, c.args().get(0).pos());
        if ((t.taint() & WINDOW) != 0) {
            throw c.args().get(0).pos().error("JSC0505", "this threshold depends on the current call's validity window, which can invert "
                    + c.fn() + "(t): use a stored or given time (ADR-0018 D11)");
        }
        return new Ir.TimePred(c.fn().equals("after"), t, Ty.BOOL, 0, c.pos());
    }

    // ------------------------------------------------------------------ expressions

    /** Literals whose type only a context gives: {@code None} and {@code []}. */
    private static boolean needsContext(Expr e) {
        return e instanceof Name n && n.name().equals("None") || e instanceof ListLit l && l.items().isEmpty() && l.rest() == null;
    }

    Ir.Expr expr(Expr e, Sc sc, Ty expected) {
        return switch (e) {
            case IntLit i -> new Ir.Lit(i.value(), Ty.INT, i.pos());
            case BoolLit b -> new Ir.Lit(b.value(), Ty.BOOL, b.pos());
            // A hex literal is Bytes; a key literal is asKey(#…), checked here (F15).
            case BytesLit b -> new Ir.Lit(hexBytes(b.hex()), Ty.BYTES, b.pos());
            case StrLit s -> throw s.pos().error("JSC0317", "strings are only require messages and utf8(\"…\") arguments");
            case Name n -> name(n, sc, expected);
            case Field f -> field(f, sc);
            case Call c -> call(c, sc, expected);
            case MethodCall m -> methodCall(m, sc, expected);
            case Lambda l -> throw l.pos().error("JSC1012", "a lambda is only an argument of a list method (fold, any, all, find, filter, map)");
            case ObjLit o -> objLit(o, sc, expected);
            case ListLit l -> listLit(l, sc, expected);
            case Unary u -> unary(u, sc);
            case Binary b -> binary(b, sc, expected);
            case Syntax.Cond c -> cond(c.cond(), c.then(), c.otherwise(), c.pos(), sc, expected);
            case IfExpr i -> ifExpr(i, sc, expected);
            case BlockExpr b -> {
                Ir.Block blk = valueBlock(b.block(), sc.child(false, false), expected);
                noBigWrite(blk.result().type(), blk.result(), b.pos(), "the value of a block");
                yield new Ir.BlockValue(blk, blk.result().type(), blk.result().taint(), b.pos());
            }
            case Syntax.Match mt -> match(mt, sc, expected);
            case Index ix -> index(ix, sc);
        };
    }

    private Ir.Expr name(Name n, Sc sc, Ty expected) {
        Ir.Local v = sc.lookup(n.name());
        if (v != null) {
            used.add(v);
            return new Ir.LocalRef(v, v.type, v.taint, n.pos());
        }
        if (sc.hasState) {
            Ty ft = stateFields.get(n.name());
            if (ft != null) {
                if (ft instanceof Ty.BigMapT) bigMapRead(n.pos(), sc);
                return new Ir.StateField(n.name(), ft, timeFields.contains(n.name()) ? TIME : 0, n.pos());
            }
        }
        if (variantEnum.containsKey(n.name())) {
            String e = variantEnum.get(n.name());
            if (!enums.get(e).get(n.name()).isEmpty()) {
                throw n.pos().error("JSC0912", n.name() + " has fields: write " + n.name() + " { " + String.join(", ", enums.get(e).get(n.name()).keySet()) + " }");
            }
            return new Ir.Variant(e, n.name(), List.of(), new Ty.EnumT(e), 0, n.pos());
        }
        switch (n.name()) {
            case "state" -> {
                if (!sc.hasState) throw n.pos().error("JSC0414", "'state' is the input state of an action, destroy or view");
                return new Ir.StateRef(Ty.STATE, timeFields.isEmpty() ? 0 : TIME, n.pos());
            }
            case "caller" -> {
                needCtx(n, sc);
                return new Ir.CtxRef("caller", Ty.KEY, 0, n.pos());
            }
            case "received", "balance" -> {
                needCtx(n, sc);
                return new Ir.CtxRef(n.name(), Ty.INT, 0, n.pos());
            }
            case "validFrom", "validTo" -> {
                needCtx(n, sc);
                return new Ir.CtxRef(n.name(), Ty.INT, TIME | WINDOW, n.pos());
            }
            case "None" -> {
                if (!(expected instanceof Ty.OptT)) {
                    throw n.pos().error("JSC0318", "the type of None is unknown here: give it one (const o: Option<Int> = None)");
                }
                return new Ir.NoneLit(expected, n.pos());
            }
            case "params", "funds" -> throw n.pos().error("JSC0319", "'" + n.name() + "' comes in a later milestone of .jsc 2 (ADR-0018 §8)");
            case "Map", "BigMap" -> throw n.pos().error("JSC0805", "an empty map is " + n.name() + ".empty()");
            default -> {
            }
        }
        throw n.pos().error("JSC0217", "unknown name '" + n.name() + "'");
    }

    private static void needCtx(Name n, Sc sc) {
        if (!sc.hasCtx) {
            throw n.pos().error("JSC0415", n.name() + " is part of the transaction: it is not available in a view or fn (pass it as an argument)");
        }
    }

    /** {@code t.name}: a record field, or a list property; which one is decided by the target's type (C7). */
    private Ir.Expr field(Field f, Sc sc) {
        Ir.Expr target = expr(f.target(), sc, null);
        if (target instanceof Ir.Range) throw f.target().pos().error("JSC1009", RANGE_ONLY);
        if (target.type() instanceof Ty.MapT mt) {
            return switch (f.name()) {
                // The key set of a map never depends on a time value (every key position refuses one), so these are
                // ordinary values even when the map holds time values (D11).
                case "size" -> new Ir.MapOp("size", target, List.of(), Ty.INT, 0, f.pos());
                case "isEmpty" -> new Ir.MapOp("isEmpty", target, List.of(), Ty.BOOL, 0, f.pos());
                case "keys" -> new Ir.MapOp("keys", target, List.of(), new Ty.ListT(mt.key()), 0, f.pos());
                case "values" -> new Ir.MapOp("values", target, List.of(), new Ty.ListT(mt.val()), target.taint(), f.pos());
                case "length" -> throw f.pos().error("JSC0806", "a Map's size is m.size");
                default -> throw f.pos().error("JSC0807", "Map has no property '" + f.name() + "' (size, isEmpty, keys, values)");
            };
        }
        if (target.type() instanceof Ty.BigMapT) {
            if (f.name().equals("isEmpty")) {
                if (sc.kind != Ir.Kind.DESTROY) {
                    throw f.pos().error("JSC0808", "a BigMap's isEmpty means \"empty before this call\", so it is read only in a destroy (ADR-0018 D7)");
                }
                return new Ir.BigOp("isEmpty", target, List.of(), Ty.BOOL, 0, f.pos());
            }
            throw f.pos().error("JSC0809", "a BigMap has no property '" + f.name() + "': read it with get(k), has(k) or m[k] (ADR-0018 D7)");
        }
        if (target.type() instanceof Ty.ListT lt) {
            return switch (f.name()) {
                // A list's shape (its length) is never a time value (D11, as amended in r7): no construct makes it depend on one.
                case "length" -> new Ir.ListProp("length", target, Ty.INT, 0, f.pos());
                case "isEmpty" -> new Ir.ListProp("isEmpty", target, Ty.BOOL, 0, f.pos());
                case "head" -> new Ir.ListProp("head", target, option(lt.elem(), f.pos(), "xs.head"), target.taint(), f.pos());
                case "size" -> throw f.pos().error("JSC1013", "a List's size is xs.length");
                default -> throw f.pos().error("JSC1014", "List has no property '" + f.name() + "' (length, isEmpty, head)");
            };
        }
        if (target.type() instanceof Ty.Rec r) {
            Ty ft = fieldsOf(r.name()).get(f.name());
            if (ft == null) throw f.pos().error("JSC0320", r.name() + " has no field " + f.name());
            if (ft instanceof Ty.BigMapT) bigMapRead(f.pos(), sc);
            return new Ir.GetField(target, r.name(), f.name(), ft, fieldTaint(target, f.name()), f.pos());
        }
        if (target.type() instanceof Ty.EnumT e) {
            throw f.pos().error("JSC0913", e.name() + " is an enum: read its fields with match (match v { A { " + f.name() + " } => … })");
        }
        throw f.pos().error("JSC0321", "." + f.name() + " needs a record, a List or a Map, not " + Ty.show(target.type()));
    }

    private Ir.Expr call(Call c, Sc sc, Ty expected) {
        String fn = c.fn();
        Syntax.Fn user = fns.get(fn);
        if (user != null) return userCall(user, c, sc);
        Std.Binding b = Std.ROWS.get(fn);
        if (b != null) {
            if (!imported.contains(fn)) throw c.pos().error("JSC0704", fn + " is in " + b.module() + ": import { " + fn + " } from \"" + b.module() + "\"");
            return stdCall(b, c, sc);
        }
        switch (fn) {
            case "after", "before" -> throw c.pos().error("JSC0506", fn + "(t) holds for the whole validity window: use it only at the top of a require, under && or ||");
            case "range" -> throw c.pos().error("JSC1009", RANGE_ONLY);
            case "Some" -> {
                arity(c, 1);
                Expr a = c.args().get(0);
                if (a instanceof Name n && n.name().equals("None")) {
                    throw a.pos().error("JSC0322", "Some(None) is not allowed: an Option holds a value, and JavaScript cannot tell Some(None) from None (ADR-0018 D5)");
                }
                Ty inner = expected instanceof Ty.OptT o ? o.elem() : null;
                Ir.Expr v = expr(a, sc, inner);
                noBigMap(v.type(), a.pos(), "held in an Option");
                if (v.type() instanceof Ty.OptT) throw c.pos().error("JSC0305", "Option<Option<…>> is not allowed: JavaScript cannot tell Some(None) from None");
                return new Ir.SomeOf(v, new Ty.OptT(v.type()), v.taint(), c.pos());
            }
            case "min", "max" -> {
                arity(c, 2);
                Ir.Expr a = intArg(c, 0, sc), d = intArg(c, 1, sc);
                return new Ir.Prelude(fn, List.of(a, d), Ty.INT, a.taint() | d.taint(), c.pos());
            }
            case "abs" -> {
                arity(c, 1);
                Ir.Expr a = intArg(c, 0, sc);
                return new Ir.Prelude("abs", List.of(a), Ty.INT, a.taint(), c.pos());
            }
            case "utf8" -> {
                arity(c, 1);
                if (!(c.args().get(0) instanceof StrLit s)) throw c.pos().error("JSC1015", "utf8 takes a string literal");
                return new Ir.Lit(s.value().getBytes(StandardCharsets.UTF_8), Ty.BYTES, c.pos());
            }
            case "asKey", "asHash" -> {
                arity(c, 1);
                Ty to = fn.equals("asKey") ? Ty.KEY : Ty.HASH;
                String what = fn.equals("asKey") ? "a key hash" : "a script hash";
                if (c.args().get(0) instanceof BytesLit lit) {
                    // A literal is checked here, once: no run-time check is needed.
                    byte[] k = hexBytes(lit.hex());
                    if (k.length != 28) throw lit.pos().error("JSC0323", fn + ": this literal has " + k.length + " bytes; " + what + " has 28");
                    return new Ir.Lit(k, to, c.pos());
                }
                Ir.Expr a = expr(c.args().get(0), sc, Ty.BYTES);
                expectType(Ty.BYTES, a, c.args().get(0).pos());
                if ((a.taint() & TIME) != 0) {
                    throw c.pos().error("JSC0507", fn + " can fail, so a time value cannot become " + (fn.equals("asKey") ? "a key" : "a hash") + " (ADR-0018 D11)");
                }
                return new Ir.Prelude(fn, List.of(a), to, a.taint(), c.pos());
            }
            default -> throw c.pos().error("JSC0324", "unknown function '" + fn + "'");
        }
    }

    private Ir.Expr intArg(Call c, int i, Sc sc) {
        Ir.Expr a = expr(c.args().get(i), sc, Ty.INT);
        expectType(Ty.INT, a, c.args().get(i).pos());
        return a;
    }

    private static void arity(Call c, int n) {
        if (c.args().size() != n) throw c.pos().error("JSC0325", c.fn() + " takes " + n + " argument" + (n == 1 ? "" : "s"));
    }

    private Ir.Expr userCall(Syntax.Fn f, Call c, Sc sc) {
        if (c.args().size() != f.params().size()) throw c.pos().error("JSC0326", f.name() + " takes " + f.params().size() + " arguments");
        callGraph.computeIfAbsent(sc.kind == null ? sc.owner : "", k -> new TreeSet<>()).add(f.name());
        if (sc.kind == null && f.name().equals(sc.owner) && sc.helperDepth > 0) {
            throw c.pos().error("JSC0601", f.name() + " calls itself from inside a lambda, an if or match arm, a value block or a ?? default: the generated helper would join"
                    + " a cycle that is not a self-cycle (ADR-0018 D7, J9); move the recursive call out");
        }
        var args = new ArrayList<Ir.Expr>();
        int taint = 0;
        int[] taints = new int[c.args().size()];
        for (int i = 0; i < c.args().size(); i++) {
            Ty pt = resolve(f.params().get(i).type());
            Ir.Expr a = expr(c.args().get(i), sc, pt);
            expectType(pt, a, c.args().get(i).pos());
            args.add(a);
            taint |= a.taint();
            taints[i] = a.taint();
        }
        analyseCall(f, taints, c.pos());
        return new Ir.CallFn(f.name(), args, resolve(f.ret()), taint, c.pos());
    }

    private Ir.Expr stdCall(Std.Binding b, Call c, Sc sc) {
        if (c.args().size() != b.params().size()) throw c.pos().error("JSC0325", b.name() + " takes " + b.params().size() + " argument"
                + (b.params().size() == 1 ? "" : "s"));
        if (b.module().equals("std/time")) {
            // Sources of time values (D11): this call's window, with a bias the docs name.
            if (!sc.hasCtx) throw c.pos().error("JSC0508", b.name() + " reads the validity window: it is not available in a view or fn (pass it as an argument)");
            var args = new ArrayList<Ir.Expr>();
            for (int i = 0; i < c.args().size(); i++) args.add(intArg(c, i, sc));
            return new Ir.CallStd(b, args, Ty.INT, TIME | WINDOW | (args.isEmpty() ? 0 : args.get(0).taint()), c.pos());
        }
        if (b.generic()) return listFn(b, c, sc);
        var args = new ArrayList<Ir.Expr>();
        int taint = 0;
        for (int i = 0; i < c.args().size(); i++) {
            Ir.Expr a = expr(c.args().get(i), sc, b.params().get(i));
            expectType(b.params().get(i), a, c.args().get(i).pos());
            if ((a.taint() & TIME) != 0 && !b.timeTotal()) {
                throw c.args().get(i).pos().error("JSC0509", b.name() + " can fail for some arguments, so a time value cannot reach it (ADR-0018 D11)");
            }
            args.add(a);
            taint |= a.taint();
        }
        return new Ir.CallStd(b, args, b.result(), taint, c.pos());
    }

    /** std/list's generic functions: instantiated at the list's element type (D5). */
    private Ir.Expr listFn(Std.Binding b, Call c, Sc sc) {
        Ir.Expr xs = isRange(c.args().get(0), sc) ? null : expr(c.args().get(0), sc, null);
        if (xs == null) throw c.args().get(0).pos().error("JSC1009", RANGE_ONLY);
        if (!(xs.type() instanceof Ty.ListT lt)) throw c.args().get(0).pos().error("JSC1016", b.name() + " takes a List, not " + Ty.show(xs.type()));
        Ty e = lt.elem();
        var args = new ArrayList<Ir.Expr>(List.of(xs));
        int taint = xs.taint();
        switch (b.name()) {
            case "last" -> {
                return new Ir.CallStd(b, args, option(e, c.pos(), "last"), taint, c.pos());
            }
            case "nth" -> {
                Ir.Expr i = intArg(c, 1, sc);
                if ((i.taint() & TIME) != 0) {
                    throw c.args().get(1).pos().error("JSC0510", "nth fails for a negative index, so a time value cannot be its index (ADR-0018 D11)");
                }
                args.add(i);
                return new Ir.CallStd(b, args, option(e, c.pos(), "nth"), taint, c.pos());
            }
            case "indexOf" -> {
                Ir.Expr v = expr(c.args().get(1), sc, e);
                expectType(e, v, c.args().get(1).pos());
                if (((v.taint() | taint) & TIME) != 0) throw c.pos().error("JSC0511", "a time value cannot decide here: indexOf compares it (ADR-0018 D11)");
                noBigMap(e, c.pos(), "compared");
                args.add(v);
                return new Ir.CallStd(b, args, new Ty.OptT(Ty.INT), 0, c.pos());
            }
            default -> throw new IllegalStateException(b.name());
        }
    }

    // ------------------------------------------------------------------ list methods

    private Ir.Expr methodCall(MethodCall m, Sc sc, Ty expected) {
        if (m.target() instanceof Name n && sc.lookup(n.name()) == null && (n.name().equals("Map") || n.name().equals("BigMap"))) {
            return emptyMap(n.name(), m, sc, expected);
        }
        Ir.Expr target = isRange(m.target(), sc) ? range((Call) m.target(), sc) : expr(m.target(), sc, null);
        if (target.type() instanceof Ty.MapT mt) return mapMethod(m, target, mt, sc, expected);
        if (target.type() instanceof Ty.BigMapT bt) return bigMethod(m, target, bt, sc);
        if (!(target.type() instanceof Ty.ListT lt)) {
            throw m.pos().error("JSC0327", "." + m.method() + "(…) needs a List or a Map; " + Ty.show(target.type()) + " has no methods");
        }
        if (target instanceof Ir.Range && !Set.of("any", "all", "fold", "filter", "map").contains(m.method())) {
            throw m.target().pos().error("JSC1009", RANGE_ONLY);
        }
        Ty e = lt.elem();
        List<Expr> args = m.args();
        int t = target.taint();
        switch (m.method()) {
            case "take", "drop" -> {
                if (args.size() != 1) throw m.pos().error("JSC1017", m.method() + " takes one Int: xs." + m.method() + "(n)");
                Ir.Expr n = expr(args.get(0), sc, Ty.INT);
                expectType(Ty.INT, n, args.get(0).pos());
                if ((n.taint() & TIME) != 0) {
                    throw args.get(0).pos().error("JSC0512", m.method() + " fails for a negative count, so a time value cannot be its count (ADR-0018 D11)");
                }
                return new Ir.ListArg(m.method(), target, n, target.type(), t, m.pos());
            }
            case "length", "isEmpty", "head" -> throw m.pos().error("JSC1018", "xs." + m.method() + " is a property: write it without parentheses");
            case "sum" -> {
                if (!args.isEmpty()) throw m.pos().error("JSC1019", "sum() takes no arguments");
                if (!e.equals(Ty.INT)) throw m.pos().error("JSC1020", "sum() needs a List<Int>");
                return new Ir.ListProp("sum", target, Ty.INT, t, m.pos());
            }
            case "reverse" -> {
                if (!args.isEmpty()) throw m.pos().error("JSC1021", "reverse() takes no arguments");
                return new Ir.ListProp("reverse", target, target.type(), t, m.pos());
            }
            case "contains" -> {
                if (args.size() != 1) throw m.pos().error("JSC1022", "contains takes one value");
                Ir.Expr v = expr(args.get(0), sc, e);
                expectType(e, v, args.get(0).pos());
                if (((v.taint() | t) & TIME) != 0) {
                    throw m.pos().error("JSC0513", "a time value cannot decide here: contains compares it (ADR-0018 D11)");
                }
                return new Ir.Contains(target, v, e, Ty.BOOL, 0, m.pos());
            }
            case "concat" -> {
                if (args.size() != 1) throw m.pos().error("JSC1023", "concat takes one list");
                Ir.Expr o = expr(args.get(0), sc, target.type());
                expectType(target.type(), o, args.get(0).pos());
                return new Ir.Concat(target, o, target.type(), t | o.taint(), m.pos());
            }
            case "any", "all", "find", "filter", "map" -> {
                if (args.size() != 1 || !(args.get(0) instanceof Lambda l) || l.params().size() != 1) {
                    throw m.pos().error("JSC1024", m.method() + " takes a one-parameter lambda: xs." + m.method() + "(x => …)");
                }
                return hof(m.method(), target, e, l, sc, expected, m.pos());
            }
            case "fold" -> {
                if (args.size() != 2 || !(args.get(1) instanceof Lambda l) || l.params().size() != 2) {
                    throw m.pos().error("JSC1025", "fold takes an initial value and a two-parameter lambda: xs.fold(0, (acc, x) => acc + x)");
                }
                return fold(target, e, args.get(0), l, sc, expected, m.pos());
            }
            default -> throw m.pos().error("JSC1026", "List has no method '" + m.method() + "'");
        }
    }

    /** A lambda's body in its scope: an expression, or a block of const and require statements ending in its value. */
    private Ir.Block lambdaBody(Lambda l, Sc inner, Ty want) {
        if (l.body() instanceof BlockExpr be) return valueBlock(be.block(), inner, want);
        Ir.Expr r = expr(l.body(), inner, want);
        unusedConsts(inner);
        return new Ir.Block(List.of(), r, l.pos());
    }

    private Ir.Expr hof(String kind, Ir.Expr list, Ty e, Lambda l, Sc sc, Ty expected, Pos pos) {
        Sc inner = sc.child(false, true);
        Ir.Local x = inner.declare(l.params().get(0), e, list.taint(), l.pos(), false, null);
        boolean map = kind.equals("map");
        Ty want = map ? (expected instanceof Ty.ListT el ? el.elem() : null) : Ty.BOOL;
        Ir.Block body = lambdaBody(l, inner, want);
        Ir.Expr r = body.result();
        if (!map) {
            expectType(Ty.BOOL, r, r.pos());
            if ((r.taint() & TIME) != 0) throw l.pos().error("JSC0514", "a time value cannot decide " + kind + "'s predicate (ADR-0018 D11)");
        } else if (want != null) {
            expectType(want, r, r.pos());
        }
        return switch (kind) {
            case "any", "all" -> new Ir.Hof(kind, list, x, body, Ty.BOOL, 0, pos);
            case "find" -> new Ir.Hof(kind, list, x, body, option(e, pos, "xs.find"), list.taint(), pos);
            case "filter" -> new Ir.Hof(kind, list, x, body, list.type(), list.taint(), pos);
            default -> new Ir.Hof(kind, list, x, body, new Ty.ListT(want != null ? want : r.type()), list.taint() | r.taint(), pos);
        };
    }

    /** {@code Option<elem>}; an Option of an Option does not exist in .jsc (D5, F20), whatever builds it. */
    private static Ty option(Ty elem, Pos pos, String what) {
        if (elem instanceof Ty.OptT) {
            boolean map = what.startsWith("m.");
            throw pos.error("JSC0328", what + " on a " + (map ? "Map" : "List") + " of Options would be an Option<Option<…>>, which .jsc"
                    + " does not have (JavaScript cannot tell Some(None) from None): " + (map ? "test m.has(k), or store values that are not Options"
                    : "use xs.isEmpty or a fold"));
        }
        return new Ty.OptT(elem);
    }

    private Ir.Expr fold(Ir.Expr list, Ty e, Expr init, Lambda l, Sc sc, Ty expected, Pos pos) {
        Ir.Expr start = expr(init, sc, expected);
        Ty acc = expected != null && Ty.assignable(expected, start.type()) ? expected : start.type();
        noBigWrite(acc, start, init.pos(), "a fold accumulator");
        // The accumulator carries whatever the body returns into the next iteration: its taint is a fixed point over the
        // body (Codex C3). Two bits, so at most three rounds.
        int accTaint = start.taint() | list.taint();
        FoldBody fb;
        if (acc.equals(Ty.STATE)) {
            // A fold over the state: each field's taint is its own fixed point, so a body that writes one field leaves
            // the others ordinary (S7). The elements reach a field only through the body, which the per-field taint sees.
            Map<String, FT> accFT = stateTaints(start);
            while (true) {
                fb = foldBody(l, sc, acc, e, accFT, list.taint());
                var next = merge(accFT, stateTaints(fb.body().result()));
                if (sameTaints(next, accFT)) break;
                accFT = next;
            }
            expectType(acc, fb.body().result(), fb.body().result().pos());
            int t = 0;
            for (FT ft : accFT.values()) t |= ft.taint();
            var fold = new Ir.Fold(list, start, fb.acc(), fb.x(), fb.body(), acc, t, pos);
            foldFT.put(fold, accFT);
            return fold;
        }
        while (true) {
            fb = foldBody(l, sc, acc, e, accTaint, list.taint());
            int out = fb.body().result().taint();
            if ((out | accTaint) == accTaint) break;
            accTaint |= out;
        }
        expectType(acc, fb.body().result(), fb.body().result().pos());
        return new Ir.Fold(list, start, fb.acc(), fb.x(), fb.body(), acc, accTaint | fb.body().result().taint(), pos);
    }

    private static boolean sameTaints(Map<String, FT> a, Map<String, FT> b) {
        for (var en : a.entrySet()) {
            FT y = b.get(en.getKey());
            if (y == null || y.taint() != en.getValue().taint() || y.warn() != en.getValue().warn() || y.whole() != en.getValue().whole()) return false;
        }
        return a.size() == b.size();
    }

    private record FoldBody(Ir.Local acc, Ir.Local x, Ir.Block body) {}

    private FoldBody foldBody(Lambda l, Sc sc, Ty acc, Ty e, int accTaint, int elemTaint) {
        Sc inner = sc.child(false, true);
        Ir.Local a = inner.declare(l.params().get(0), acc, accTaint, l.pos(), false, null);
        Ir.Local x = inner.declare(l.params().get(1), e, elemTaint, l.pos(), false, null);
        return new FoldBody(a, x, lambdaBody(l, inner, acc));
    }

    /** A fold body whose accumulator is the state, with the accumulator's per-field taints. */
    private FoldBody foldBody(Lambda l, Sc sc, Ty acc, Ty e, Map<String, FT> accFT, int elemTaint) {
        Sc inner = sc.child(false, true);
        int t = 0;
        for (FT ft : accFT.values()) t |= ft.taint();
        Ir.Local a = inner.declare(l.params().get(0), acc, t, l.pos(), false, taintsOnly(accFT));
        localFT.put(a, accFT);
        Ir.Local x = inner.declare(l.params().get(1), e, elemTaint, l.pos(), false, null);
        return new FoldBody(a, x, lambdaBody(l, inner, acc));
    }

    // ------------------------------------------------------------------ literals

    private Ir.Expr objLit(ObjLit o, Sc sc, Ty expected) {
        String recName;
        if (o.type() != null) {
            if (variantEnum.containsKey(o.type())) return variantLit(o, sc);
            if (!records.containsKey(o.type())) throw o.pos().error("JSC0329", "unknown type " + o.type());
            recName = o.type();
        } else if (expected instanceof Ty.Rec r) {
            recName = r.name();
        } else if (expected instanceof Ty.EnumT e) {
            throw o.pos().error("JSC0914", "an " + e.name() + " is one of its variants: write " + String.join(" { … }, ", enums.get(e.name()).keySet()) + " { … }");
        } else {
            throw o.pos().error("JSC0330", "this object's type is unknown here: write Type { … }, or give the const a type");
        }
        var fields = fieldsOf(recName);
        Ty type = new Ty.Rec(recName);
        Ir.Expr spread = null;
        if (o.spread() != null) {
            spread = expr(o.spread(), sc, type);
            expectType(type, spread, o.spread().pos());
            if (!(spread instanceof Ir.LocalRef) && !(spread instanceof Ir.StateRef)) {
                throw o.spread().pos().error("JSC0218", "spread a named value: { ...state, … } or { ...r, … }");
            }
        }
        var given = new LinkedHashMap<String, Ir.Expr>();
        for (FieldInit f : o.fields()) {
            Ty ft = fields.get(f.name());
            if (ft == null) throw f.pos().error("JSC0320", recName + " has no field " + f.name());
            if (given.containsKey(f.name())) throw f.pos().error("JSC0219", f.name() + " is given twice");
            Expr ve = f.value() != null ? f.value() : new Name(f.name(), f.pos());
            // A BigMap is written only here: in the value of its own field in a state (D7, security S4).
            boolean bigField = ft instanceof Ty.BigMapT && recName.equals("State");
            if (bigField) bigWriteOk++;
            Ir.Expr v;
            try {
                v = expr(ve, sc, ft);
            } finally {
                if (bigField) bigWriteOk--;
            }
            expectType(ft, v, ve.pos());
            given.put(f.name(), v);
        }
        var values = new ArrayList<Ir.Expr>();
        int taint = 0;
        for (var en : fields.entrySet()) {
            Ir.Expr v = given.get(en.getKey());
            if (v == null) {
                if (spread == null) throw o.pos().error("JSC0331", recName + " needs " + en.getKey() + " (or start with ...state)");
                v = new Ir.GetField(spread, recName, en.getKey(), en.getValue(), fieldTaint(spread, en.getKey()), o.pos());
            }
            values.add(v);
            taint |= v.taint();
            if (en.getValue() instanceof Ty.BigMapT) bigMapField(en.getKey(), v, sc, given.containsKey(en.getKey()) ? null : o.pos());
        }
        return new Ir.New(recName, values, type, taint, o.pos());
    }

    /** {@code Closed { at }}: every field given, no spread (D5). */
    private Ir.Expr variantLit(ObjLit o, Sc sc) {
        String e = variantEnum.get(o.type());
        var fields = enums.get(e).get(o.type());
        if (o.spread() != null) throw o.spread().pos().error("JSC0915", "a variant is built from its fields: " + o.type() + " { … } takes no spread");
        var given = new LinkedHashMap<String, Ir.Expr>();
        for (FieldInit f : o.fields()) {
            Ty ft = fields.get(f.name());
            if (ft == null) throw f.pos().error("JSC0320", o.type() + " has no field " + f.name());
            if (given.containsKey(f.name())) throw f.pos().error("JSC0219", f.name() + " is given twice");
            Expr ve = f.value() != null ? f.value() : new Name(f.name(), f.pos());
            Ir.Expr v = expr(ve, sc, ft);
            expectType(ft, v, ve.pos());
            given.put(f.name(), v);
        }
        var values = new ArrayList<Ir.Expr>();
        int taint = 0;
        for (String n : fields.keySet()) {
            Ir.Expr v = given.get(n);
            if (v == null) throw o.pos().error("JSC0332", o.type() + " needs " + n);
            values.add(v);
            taint |= v.taint();
        }
        return new Ir.Variant(e, o.type(), values, new Ty.EnumT(e), taint, o.pos());
    }

    /**
     * A BigMap state field's new value is only its own field transformed by set/delete, or BigMap.empty() in an init
     * (D7). {@code copied}: the position of a spread that copies it unchanged (always allowed), else null.
     */
    private void bigMapField(String field, Ir.Expr v, Sc sc, Pos copied) {
        if (copied != null) return;
        if (v instanceof Ir.BigEmpty) {
            if (sc.kind != Ir.Kind.INIT) throw v.pos().error("JSC0810", "BigMap.empty() starts a map in an init; elsewhere a BigMap field keeps its own entries");
            return;
        }
        Ir.Expr root = v;
        while (root instanceof Ir.BigOp b && (b.op().equals("set") || b.op().equals("delete"))) root = b.map();
        boolean own = root instanceof Ir.StateField f && f.field().equals(field)
                || root instanceof Ir.GetField g && g.field().equals(field) && g.record().equals("State");
        if (!own) {
            throw v.pos().error("JSC0811", "the new value of the BigMap field " + field + " must be " + field + " itself, changed by set/delete"
                    + (sc.kind == Ir.Kind.INIT ? ", or BigMap.empty() in an init" : "") + " (ADR-0018 D7)");
        }
    }

    private Ir.Expr listLit(ListLit l, Sc sc, Ty expected) {
        Ty elem = expected instanceof Ty.ListT lt ? lt.elem() : null;
        if (elem == null) {
            for (Expr x : l.items()) {
                if (!needsContext(x)) {
                    elem = expr(x, sc, null).type();
                    break;
                }
            }
        }
        Ir.Expr rest = null;
        if (elem == null && l.rest() != null) {
            rest = expr(l.rest(), sc, null);
            if (!(rest.type() instanceof Ty.ListT rt)) throw l.rest().pos().error("JSC1027", "...rest needs a List");
            elem = rt.elem();
        }
        if (elem == null) throw l.pos().error("JSC1028", "the type of this list is unknown here: give it one (const xs: List<Int> = [])");
        noBigMap(elem, l.pos(), "an element of a list");
        var items = new ArrayList<Ir.Expr>();
        int taint = 0;
        for (Expr x : l.items()) {
            Ir.Expr v = expr(x, sc, elem);
            expectType(elem, v, x.pos());
            items.add(v);
            taint |= v.taint();
        }
        Ty type = new Ty.ListT(elem);
        if (l.rest() != null) {
            if (rest == null) rest = expr(l.rest(), sc, type);
            if (!(rest.type() instanceof Ty.ListT)) throw l.rest().pos().error("JSC1027", "...rest needs a List");
            expectType(type, rest, l.rest().pos());
            taint |= rest.taint();
        }
        return new Ir.ListLit(items, rest, type, taint, l.pos());
    }

    // ------------------------------------------------------------------ operators

    private Ir.Expr unary(Unary u, Sc sc) {
        if (u.op().equals("!")) {
            Ir.Expr v = condition(u.operand(), sc);
            return new Ir.Not(v, Ty.BOOL, 0, u.pos());
        }
        Ir.Expr v = expr(u.operand(), sc, Ty.INT);
        expectType(Ty.INT, v, u.operand().pos());
        return new Ir.Neg(v, Ty.INT, v.taint(), u.pos());
    }

    private Ir.Expr binary(Binary b, Sc sc, Ty expected) {
        String op = b.op();
        if (op.equals("&&") || op.equals("||")) {
            Ir.Expr l = condition(b.left(), sc);
            Ir.Expr r = condition(b.right(), sc);
            return new Ir.Logic(op, l, r, Ty.BOOL, 0, b.pos());
        }
        if (op.equals("??")) return coalesce(b, sc, expected);
        Ir.Expr l, r;
        if (needsContext(b.left()) && !needsContext(b.right())) {
            r = expr(b.right(), sc, null);
            l = expr(b.left(), sc, r.type());
        } else if (needsContext(b.left())) {
            throw b.pos().error("JSC0333", "the type of this comparison is unknown: give one side a type (const none: Option<Int> = None)");
        } else {
            l = expr(b.left(), sc, null);
            r = expr(b.right(), sc, l.type());
        }
        switch (op) {
            case "+", "-", "*", "/", "%" -> {
                expectType(Ty.INT, l, b.left().pos());
                expectType(Ty.INT, r, b.right().pos());
                if ((op.equals("/") || op.equals("%")) && (r.taint() & TIME) != 0) {
                    throw b.right().pos().error("JSC0515", "a time value cannot be a divisor: it could make the call fail (ADR-0018 D11)");
                }
                return new Ir.Arith(op, l, r, Ty.INT, l.taint() | r.taint(), b.pos());
            }
            case "<", "<=", ">", ">=" -> {
                timeCompare(b, l, r);
                boolean ints = l.type().equals(Ty.INT) && r.type().equals(Ty.INT);
                boolean bytes = isBytes(l.type()) && isBytes(r.type());
                if (!ints && !bytes) {
                    throw b.pos().error("JSC0334", op + " compares two Ints or two Bytes, not " + Ty.show(l.type()) + " and " + Ty.show(r.type()));
                }
                return new Ir.Compare(op, l, r, Ty.BOOL, 0, b.pos());
            }
            case "==", "!=" -> {
                timeCompare(b, l, r);
                Ty t = unify(l.type(), r.type(), b.pos());
                if (Ty.hasBigMap(t, this::components) || t.equals(Ty.STATE) && stateFields.values().stream().anyMatch(x -> x instanceof Ty.BigMapT)) {
                    throw b.pos().error("JSC0812", "a BigMap is not comparable: it is a witness of a Merkle root, not its contents (ADR-0018 D6)");
                }
                return new Ir.Equals(op.equals("!="), l, r, t, Ty.BOOL, 0, b.pos());
            }
            default -> throw b.pos().error("JSC0335", "unknown operator " + op);
        }
    }

    private static void timeCompare(Binary b, Ir.Expr l, Ir.Expr r) {
        // Comparing with a constructor that has no payload (a variant without fields, None, [], Map.empty()) decides on
        // the other side's shape only, and a shape is never a time value (D11, as amended in r7).
        if ((b.op().equals("==") || b.op().equals("!=")) && (shapeOnly(l) || shapeOnly(r))) return;
        if (((l.taint() | r.taint()) & TIME) != 0) {
            throw b.pos().error("JSC0502", "a time value cannot decide here: compare times only with after(t) or before(t) in a require (ADR-0018 D11)");
        }
    }

    private static boolean isBytes(Ty t) {
        return t.equals(Ty.BYTES) || t.equals(Ty.KEY);
    }

    /** A constructor with no payload: comparing with it compares shapes only. */
    static boolean shapeOnly(Ir.Expr e) {
        return e instanceof Ir.Variant v && v.values().isEmpty() || e instanceof Ir.NoneLit
                || e instanceof Ir.ListLit l && l.items().isEmpty() && l.rest() == null || e instanceof Ir.MapEmpty;
    }

    private static Ty unify(Ty a, Ty b, Pos p) {
        if (Ty.assignable(a, b)) return a;
        if (Ty.assignable(b, a)) return b;
        throw p.error("JSC0336", "cannot compare " + Ty.show(a) + " with " + Ty.show(b));
    }

    /** {@code o ?? d}: the default runs only when the option is empty (a helper, D6). */
    private Ir.Expr coalesce(Binary b, Sc sc, Ty expected) {
        if (b.left() instanceof Name n && n.name().equals("None")) throw b.left().pos().error("JSC0337", "None ?? d is always d: write d");
        Ir.Expr o = expr(b.left(), sc, expected == null ? null : new Ty.OptT(expected));
        if (!(o.type() instanceof Ty.OptT ot)) throw b.left().pos().error("JSC0338", "?? needs an Option on its left, not " + Ty.show(o.type()));
        Ty t = ot.elem();
        if (expected != null && Ty.assignable(expected, t)) t = expected;
        Ir.Expr d = expr(b.right(), sc.child(false, false), t);
        expectType(t, d, b.right().pos());
        return new Ir.Coalesce(o, d, t, o.taint() | d.taint(), b.pos());
    }

    private Ir.Expr cond(Expr ce, Expr te, Expr oe, Pos pos, Sc sc, Ty expected) {
        Ir.Expr k = condition(ce, sc);
        Ir.Expr a, d;
        if (expected == null && needsContext(te) && !needsContext(oe)) {
            d = expr(oe, sc, null);
            a = expr(te, sc, d.type());
        } else {
            a = expr(te, sc, expected);
            d = expr(oe, sc, expected != null ? expected : a.type());
        }
        Ty t = expected != null ? expected : unify(a.type(), d.type(), pos);
        expectType(t, a, te.pos());
        expectType(t, d, oe.pos());
        noBigWrite(t, a, pos, "chosen by a condition");
        noBigWrite(t, d, pos, "chosen by a condition");
        return new Ir.Cond(k, a, d, t, a.taint() | d.taint(), pos);
    }

    /** An if used as a value. Arms without statements are a {@link Ir.Cond}; arms with statements, an {@link Ir.IfValue}. */
    private Ir.Expr ifExpr(IfExpr i, Sc sc, Ty expected) {
        if (i.then().stmts().isEmpty() && i.otherwise().stmts().isEmpty()) {
            return cond(i.cond(), i.then().result(), i.otherwise().result(), i.pos(), sc, expected);
        }
        Ir.Expr k = condition(i.cond(), sc);
        Ir.Block a, d;
        if (expected == null && needsContext(i.then().result()) && !needsContext(i.otherwise().result())) {
            d = valueBlock(i.otherwise(), sc.child(false, false), null);
            a = valueBlock(i.then(), sc.child(false, false), d.result().type());
        } else {
            a = valueBlock(i.then(), sc.child(false, false), expected);
            d = valueBlock(i.otherwise(), sc.child(false, false), expected != null ? expected : a.result().type());
        }
        Ty t = expected != null ? expected : unify(a.result().type(), d.result().type(), i.pos());
        expectType(t, a.result(), a.result().pos());
        expectType(t, d.result(), d.result().pos());
        noBigWrite(t, a.result(), i.pos(), "chosen by a condition");
        noBigWrite(t, d.result(), i.pos(), "chosen by a condition");
        return new Ir.IfValue(k, a, d, t, a.result().taint() | d.result().taint(), i.pos());
    }

    // ------------------------------------------------------------------ BigMap positions (D7)

    static final String RANGE_ONLY = "range(a, b) is only the receiver of any, all, fold, filter or map, or the list of a for loop"
            + " (no list is built: ADR-0018 D7)";

    /** A BigMap is never bound, chosen, compared, held or accumulated (D7). */
    private void noBigMap(Ty t, Pos p, String what) {
        if (t instanceof Ty.BigMapT || Ty.hasBigMap(t, this::components)) {
            throw p.error("JSC0813", "a BigMap cannot be " + what + ": it is read and changed only as its state field (ADR-0018 D7)");
        }
    }

    /**
     * A value of the state's type may be bound, chosen or accumulated only if none of its BigMaps is written: a BigMap
     * is changed only in the next state's own field value (D7, security S4). Any other type holding a BigMap is refused.
     */
    private void noBigWrite(Ty t, Ir.Expr v, Pos p, String what) {
        if (!t.equals(Ty.STATE)) {
            noBigMap(t, p, what);
            return;
        }
        if (writesBigMap(v)) {
            throw p.error("JSC0813", "a state whose BigMap is written cannot be " + what + ": write a BigMap only in the next state's own"
                    + " field, { ...state, f: f.set(k, v) } (ADR-0018 D7)");
        }
    }

    /** Whether a value of the state's type holds a BigMap other than the input's field, unchanged. */
    private boolean writesBigMap(Ir.Expr e) {
        return switch (e) {
            case Ir.StateRef s -> false;
            case Ir.LocalRef l -> false;   // a bound state was checked when it was bound
            case Ir.New n -> {
                var names = List.copyOf(stateFields.keySet());
                for (int i = 0; i < names.size(); i++) {
                    if (!(stateFields.get(names.get(i)) instanceof Ty.BigMapT)) continue;
                    Ir.Expr f = n.values().get(i);
                    boolean copy = f instanceof Ir.StateField sf && sf.field().equals(names.get(i))
                            || f instanceof Ir.GetField g && g.field().equals(names.get(i)) && !writesBigMap(g.target());
                    if (!copy) yield true;
                }
                yield false;
            }
            case Ir.Cond c -> writesBigMap(c.then()) || writesBigMap(c.otherwise());
            case Ir.IfValue i -> writesBigMap(i.then().result()) || writesBigMap(i.otherwise().result());
            case Ir.Match m -> m.arms().stream().anyMatch(a -> writesBigMap(a.body().result()));
            case Ir.BlockValue b -> writesBigMap(b.block().result());
            case Ir.Fold f -> writesBigMap(f.init()) || writesBigMap(f.body().result());
            default -> stateFields.values().stream().anyMatch(t -> t instanceof Ty.BigMapT);
        };
    }

    /** A BigMap is not captured by a lambda: a list method's body cannot read or change one (D7). */
    private static void bigMapRead(Pos p, Sc sc) {
        if (sc.inLambda) {
            throw p.error("JSC0814", "a BigMap cannot be captured by a lambda: read the keys you need before the list method (ADR-0018 D7)");
        }
    }

    private boolean isRange(Expr e, Sc sc) {
        return e instanceof Call c && c.fn().equals("range") && !fns.containsKey("range");
    }

    /** {@code range(a, b)}: Int bounds, never time values (D11: bounds can make a budget or a failure depend on time). */
    private Ir.Expr range(Call c, Sc sc) {
        arity(c, 2);
        Ir.Expr a = intArg(c, 0, sc), b = intArg(c, 1, sc);
        Ir.Expr[] bounds = {a, b};
        for (int i = 0; i < 2; i++) {
            if ((bounds[i].taint() & TIME) != 0) {
                throw c.args().get(i).pos().error("JSC0516", "a time value cannot be a bound of range: the number of steps, and so the budget, would"
                        + " depend on the window (ADR-0018 D11)");
            }
        }
        return new Ir.Range(a, b, new Ty.ListT(Ty.INT), 0, c.pos());
    }

    private Ir.Expr emptyMap(String which, MethodCall m, Sc sc, Ty expected) {
        if (!m.method().equals("empty") || !m.args().isEmpty()) throw m.pos().error("JSC0815", which + " has one function: " + which + ".empty()");
        if (which.equals("Map")) {
            if (!(expected instanceof Ty.MapT)) {
                throw m.pos().error("JSC0816", "the type of this map is unknown here: give it one (const m: Map<Key, Int> = Map.empty())");
            }
            return new Ir.MapEmpty(expected, m.pos());
        }
        if (!(expected instanceof Ty.BigMapT)) throw m.pos().error("JSC0817", "BigMap.empty() is only the value of a BigMap state field in an init (ADR-0018 D7)");
        return new Ir.BigEmpty(expected, m.pos());
    }

    /** A map key argument: never a time value (D11: a time-derived key would make presence depend on the window). */
    private Ir.Expr mapKeyArg(Expr k, Ty keyType, Sc sc, String what) {
        Ir.Expr v = expr(k, sc, keyType);
        expectType(keyType, v, k.pos());
        if ((v.taint() & TIME) != 0) {
            throw k.pos().error("JSC0517", "a time value cannot be a map key: " + what + " would depend on the validity window (ADR-0018 D11)");
        }
        return v;
    }

    private Ir.Expr mapMethod(MethodCall m, Ir.Expr target, Ty.MapT mt, Sc sc, Ty expected) {
        List<Expr> args = m.args();
        int t = target.taint();
        switch (m.method()) {
            case "get" -> {
                if (args.size() != 1) throw m.pos().error("JSC0818", "get takes one key: m.get(k)");
                Ir.Expr k = mapKeyArg(args.get(0), mt.key(), sc, "which entry is read");
                return new Ir.MapOp("get", target, List.of(k), option(mt.val(), m.pos(), "m.get"), t, m.pos());
            }
            case "has" -> {
                if (args.size() != 1) throw m.pos().error("JSC0819", "has takes one key: m.has(k)");
                Ir.Expr k = mapKeyArg(args.get(0), mt.key(), sc, "whether it is present");
                return new Ir.MapOp("has", target, List.of(k), Ty.BOOL, 0, m.pos());
            }
            case "set" -> {
                if (args.size() != 2) throw m.pos().error("JSC0820", "set takes a key and a value: m.set(k, v)");
                Ir.Expr k = mapKeyArg(args.get(0), mt.key(), sc, "the map's keys");
                Ir.Expr v = expr(args.get(1), sc, mt.val());
                expectType(mt.val(), v, args.get(1).pos());
                return new Ir.MapOp("set", target, List.of(k, v), mt, t | v.taint(), m.pos());
            }
            case "delete" -> {
                if (args.size() != 1) throw m.pos().error("JSC0821", "delete takes one key: m.delete(k)");
                Ir.Expr k = mapKeyArg(args.get(0), mt.key(), sc, "the map's keys");
                return new Ir.MapOp("delete", target, List.of(k), mt, t, m.pos());
            }
            case "any", "all" -> {
                if (args.size() != 1 || !(args.get(0) instanceof Lambda l) || l.params().size() != 2) {
                    throw m.pos().error("JSC0822", m.method() + " on a Map takes a two-parameter lambda: m." + m.method() + "((k, v) => …)");
                }
                Sc inner = sc.child(false, true);
                Ir.Local k = inner.declare(l.params().get(0), mt.key(), 0, l.pos(), false, null);
                Ir.Local v = inner.declare(l.params().get(1), mt.val(), t, l.pos(), false, null);
                Ir.Block body = lambdaBody(l, inner, Ty.BOOL);
                expectType(Ty.BOOL, body.result(), body.result().pos());
                if ((body.result().taint() & TIME) != 0) throw l.pos().error("JSC0514", "a time value cannot decide " + m.method() + "'s predicate (ADR-0018 D11)");
                return new Ir.MapHof(m.method(), target, k, v, body, Ty.BOOL, 0, m.pos());
            }
            case "fold" -> {
                if (args.size() != 2 || !(args.get(1) instanceof Lambda l) || l.params().size() != 3) {
                    throw m.pos().error("JSC0823", "fold on a Map takes an initial value and a three-parameter lambda: m.fold(0, (acc, k, v) => acc + v)");
                }
                Ir.Expr start = expr(args.get(0), sc, expected);
                Ty acc = expected != null && Ty.assignable(expected, start.type()) ? expected : start.type();
                if (acc.equals(Ty.STATE)) {
                    throw args.get(0).pos().error("JSC0824", "a Map fold does not accumulate the state: fold the values you need, then build the state");
                }
                noBigMap(acc, args.get(0).pos(), "a fold accumulator");
                int accTaint = start.taint() | t;
                Ir.Local a, k, v;
                Ir.Block body;
                while (true) {
                    Sc inner = sc.child(false, true);
                    a = inner.declare(l.params().get(0), acc, accTaint, l.pos(), false, null);
                    k = inner.declare(l.params().get(1), mt.key(), 0, l.pos(), false, null);
                    v = inner.declare(l.params().get(2), mt.val(), t, l.pos(), false, null);
                    body = lambdaBody(l, inner, acc);
                    int out = body.result().taint();
                    if ((out | accTaint) == accTaint) break;
                    accTaint |= out;
                }
                expectType(acc, body.result(), body.result().pos());
                return new Ir.MapFold(target, start, a, k, v, body, acc, accTaint, m.pos());
            }
            case "contains" -> throw m.pos().error("JSC0825", "a Map's membership test is m.has(k)");
            default -> throw m.pos().error("JSC0826", "Map has no method '" + m.method() + "' (get, has, set, delete, any, all, fold)");
        }
    }

    private Ir.Expr bigMethod(MethodCall m, Ir.Expr target, Ty.BigMapT bt, Sc sc) {
        List<Expr> args = m.args();
        switch (m.method()) {
            case "get" -> {
                if (args.size() != 1) throw m.pos().error("JSC0818", "get takes one key: m.get(k)");
                Ir.Expr k = mapKeyArg(args.get(0), bt.key(), sc, "which entry is read");
                return new Ir.BigOp("get", target, List.of(k), new Ty.OptT(bt.val()), target.taint(), m.pos());
            }
            case "has" -> {
                if (args.size() != 1) throw m.pos().error("JSC0819", "has takes one key: m.has(k)");
                Ir.Expr k = mapKeyArg(args.get(0), bt.key(), sc, "whether it is present");
                return new Ir.BigOp("has", target, List.of(k), Ty.BOOL, 0, m.pos());
            }
            case "set" -> {
                if (args.size() != 2) throw m.pos().error("JSC0820", "set takes a key and a value: m.set(k, v)");
                bigWriteHere(m.pos());
                Ir.Expr k = mapKeyArg(args.get(0), bt.key(), sc, "the map's keys");
                Ir.Expr v = expr(args.get(1), sc, bt.val());
                expectType(bt.val(), v, args.get(1).pos());
                if (bt.val().equals(Ty.INT) && (v.taint() & TIME) != 0) {
                    throw args.get(1).pos().error("JSC0518", "a time value cannot be stored in a BigMap<…, Int>: storing it can fail (a negative value, or"
                            + " one too large to encode), so the window would decide whether the call fails (ADR-0018 D11)");
                }
                return new Ir.BigOp("set", target, List.of(k, v), bt, target.taint() | v.taint(), m.pos());
            }
            case "delete" -> {
                if (args.size() != 1) throw m.pos().error("JSC0821", "delete takes one key: m.delete(k)");
                bigWriteHere(m.pos());
                Ir.Expr k = mapKeyArg(args.get(0), bt.key(), sc, "the map's keys");
                return new Ir.BigOp("delete", target, List.of(k), bt, target.taint(), m.pos());
            }
            default -> throw m.pos().error("JSC0828", "BigMap has no method '" + m.method() + "' (get, has, set, delete; m[k] on BigMap<K, Int>)");
        }
    }

    /** Inside the value of a BigMap field of a state literal: where a BigMap may be written (D7). */
    private int bigWriteOk;

    private void bigWriteHere(Pos p) {
        if (bigWriteOk == 0) {
            throw p.error("JSC0827", "a BigMap is changed only in the value of its own field in the next state, { ...state, f: f.set(k, v) },"
                    + " not in a require, a condition or a view (ADR-0018 D7)");
        }
    }

    /** {@code m[k]} on a BigMap<K, Int>: 0 when absent (D7). */
    private Ir.Expr index(Index ix, Sc sc) {
        Ir.Expr target = expr(ix.target(), sc, null);
        if (!(target.type() instanceof Ty.BigMapT bt)) {
            throw ix.pos().error("JSC0829", "m[k] reads a BigMap<K, Int>; " + (target.type() instanceof Ty.MapT ? "a Map is read with m.get(k)"
                    : target.type() instanceof Ty.ListT ? "a List has no index: use xs.head, find or fold" : Ty.show(target.type()) + " has no index"));
        }
        if (!bt.val().equals(Ty.INT)) throw ix.pos().error("JSC0830", "m[k] reads a BigMap<K, Int>; a BigMap<K, Bytes> is read with m.get(k)");
        Ir.Expr k = mapKeyArg(ix.key(), bt.key(), sc, "which entry is read");
        return new Ir.BigOp("at", target, List.of(k), Ty.INT, target.taint(), ix.pos());
    }

    // ------------------------------------------------------------------ match (D6)

    /** The constructors a scrutinee's type can have, in tag order: an enum's variants, or Some and None. */
    private List<String> constructors(Ty t, Pos p) {
        if (t instanceof Ty.EnumT e) return List.copyOf(enums.get(e.name()).keySet());
        if (t instanceof Ty.OptT) return List.of("Some", "None");
        throw p.error("JSC0916", "match needs an enum or an Option, not " + Ty.show(t));
    }

    /** The fields a constructor binds: a variant's, or Some's one value. */
    private LinkedHashMap<String, Ty> ctorFields(Ty t, String ctor) {
        if (t instanceof Ty.OptT o) {
            var f = new LinkedHashMap<String, Ty>();
            if (ctor.equals("Some")) f.put("value", o.elem());
            return f;
        }
        return enums.get(((Ty.EnumT) t).name()).get(ctor);
    }

    private record ArmShape(List<String> ctors, List<String> fields, List<Bind> binds, List<Ty> types) {}

    /**
     * Checks the patterns of a match (D6): every constructor exactly once, {@code _} only for the ones left (at least
     * one), no arm after it, binds of fields that exist.
     */
    private List<ArmShape> patterns(Syntax.Match m, Ty t) {
        List<String> all = constructors(t, m.scrutinee().pos());
        var covered = new LinkedHashSet<String>();
        var out = new ArrayList<ArmShape>();
        for (Syntax.Arm a : m.arms()) {
            Syntax.Pattern p = a.pattern();
            if (covered.size() == all.size()) throw p.pos().error("JSC0917", "this arm is unreachable: the arms before it cover every case");
            if (p.ctor().equals("_")) {
                if (!p.binds().isEmpty()) throw p.pos().error("JSC0918", "_ binds nothing");
                var rest = new ArrayList<String>();
                for (String c : all) if (!covered.contains(c)) rest.add(c);
                covered.addAll(rest);
                out.add(new ArmShape(rest, List.of(), List.of(), List.of()));
                continue;
            }
            if (!all.contains(p.ctor())) {
                String owner = variantEnum.get(p.ctor());
                throw p.pos().error("JSC0919", p.ctor() + " is not a case of " + Ty.show(t) + (owner != null ? " (it is a variant of " + owner + ")" : "")
                        + ": the cases are " + String.join(", ", all));
            }
            if (!covered.add(p.ctor())) throw p.pos().error("JSC0920", p.ctor() + " is matched twice");
            var fields = ctorFields(t, p.ctor());
            if (t instanceof Ty.OptT && p.ctor().equals("Some") && p.binds().size() != 1) {
                throw p.pos().error("JSC0921", "Some binds its value: Some(x) => …");
            }
            var fs = new ArrayList<String>();
            var types = new ArrayList<Ty>();
            for (Bind b : p.binds()) {
                String f = b.field() == null ? "value" : b.field();
                if (t instanceof Ty.OptT && b.field() != null) throw b.pos().error("JSC0922", "an Option's value is bound as Some(x)");
                Ty ft = fields.get(f);
                if (ft == null) throw b.pos().error("JSC0320", p.ctor() + " has no field " + f);
                if (fs.contains(f)) throw b.pos().error("JSC0923", f + " is bound twice");
                fs.add(f);
                types.add(ft);
            }
            out.add(new ArmShape(List.of(p.ctor()), fs, p.binds(), types));
        }
        if (covered.size() < all.size()) {
            var missing = new ArrayList<String>();
            for (String c : all) if (!covered.contains(c)) missing.add(c);
            throw m.pos().error("JSC0924", "this match is not exhaustive: add an arm for " + String.join(", ", missing) + " (or _ => …)");
        }
        return out;
    }

    /**
     * The scrutinee. A time value may be matched: a match decides on the constructor, and no constructor depends on a
     * time value, because every construct that could choose one from a time value is refused (conditions, predicates,
     * map keys, list bounds and indices; D11). Its fields keep their taint: the bindings carry the scrutinee's.
     */
    private Ir.Expr scrutinee(Syntax.Match m, Sc sc) {
        Ir.Expr s = expr(m.scrutinee(), sc, null);
        if (s instanceof Ir.Range) throw m.scrutinee().pos().error("JSC1009", RANGE_ONLY);
        constructors(s.type(), m.scrutinee().pos());
        return s;
    }

    private List<Ir.Local> bind(ArmShape shape, Sc inner, int taint) {
        var out = new ArrayList<Ir.Local>();
        for (int i = 0; i < shape.binds().size(); i++) {
            Bind b = shape.binds().get(i);
            if ("None".equals(b.name()) || "Some".equals(b.name())) throw b.pos().error("JSC0214", "'" + b.name() + "' is a built-in name");
            if (b.field() == null && (variantEnum.containsKey(b.name()) || records.containsKey(b.name()))) {
                throw b.pos().error("JSC0930", "nested patterns are not supported: bind the value, Some(v), and match v again");
            }
            Ir.Local l = inner.declare(b.name(), shape.types().get(i), taint, b.pos(), false, null);
            used.remove(l);   // a binding must be used: unusedBinds checks it after the arm
            out.add(l);
        }
        return out;
    }

    private void unusedBinds(List<Ir.Local> binds) {
        for (Ir.Local l : binds) {
            if (!used.contains(l)) throw l.pos.error("JSC0925", "'" + l.name + "' is bound but never used: leave the field out of the pattern");
        }
    }

    private Ir.Expr match(Syntax.Match m, Sc sc, Ty expected) {
        Ir.Expr s = scrutinee(m, sc);
        var shapes = patterns(m, s.type());
        // The arms' type: the expected one, else the first arm whose value has a type of its own.
        Ty t = expected;
        int first = -1;
        if (t == null) {
            for (int i = 0; i < m.arms().size() && first < 0; i++) if (!needsContext(m.arms().get(i).body().result())) first = i;
            if (first < 0) throw m.pos().error("JSC0926", "the type of this match is unknown here: give it one (const o: Option<Int> = match …)");
        }
        var order = new ArrayList<Integer>();
        if (first >= 0) order.add(first);
        for (int i = 0; i < m.arms().size(); i++) if (i != first) order.add(i);
        var arms = new Ir.Arm[m.arms().size()];
        int taint = 0;
        for (int i : order) {
            Syntax.Arm a = m.arms().get(i);
            Sc inner = sc.child(false, false);
            var binds = bind(shapes.get(i), inner, s.taint());
            Ir.Block body = valueBlock(a.body(), inner, t);
            if (t == null) t = body.result().type();
            expectType(t, body.result(), body.result().pos());
            unusedBinds(binds);
            taint |= body.result().taint();
            arms[i] = new Ir.Arm(shapes.get(i).ctors(), shapes.get(i).fields(), binds, body, a.pos());
        }
        for (Ir.Arm a : arms) noBigWrite(t, a.body().result(), m.pos(), "chosen by a match");
        return new Ir.Match(s, List.of(arms), t, taint, m.pos());
    }

    private Ir.Stmt matchStmt(Syntax.Match m, Sc sc) {
        Ir.Expr s = scrutinee(m, sc);
        var shapes = patterns(m, s.type());
        var arms = new ArrayList<Ir.Arm>();
        boolean pays = false;
        for (int i = 0; i < m.arms().size(); i++) {
            Syntax.Arm a = m.arms().get(i);
            Sc inner = sc.child(sc.effects, false);
            var binds = bind(shapes.get(i), inner, s.taint());
            if (a.body().result() != null) {
                throw a.body().result().pos().error("JSC0927", "this match is a statement (it is not the block's last expression, or an arm has no"
                        + " value), so its arms end in statements, not a value");
            }
            var stmts = stmts(a.body().stmts(), inner);
            unusedConsts(inner);
            unusedBinds(binds);
            pays |= paysIn(a.body());
            arms.add(new Ir.Arm(shapes.get(i).ctors(), shapes.get(i).fields(), binds, new Ir.Block(stmts, null, a.body().pos()), a.pos()));
        }
        return new Ir.MatchStmt(s, arms, pays, m.pos());
    }

    // ------------------------------------------------------------------ recursion (D7, J9)

    private void checkRecursion() {
        // Any cycle through more than one function is refused (direct self-recursion is allowed).
        for (String f : callGraph.keySet()) {
            if (f.isEmpty()) continue;
            var seen = new HashSet<String>();
            var stack = new ArrayDeque<String>();
            for (String g : callGraph.getOrDefault(f, Set.of())) if (!g.equals(f)) stack.push(g);
            while (!stack.isEmpty()) {
                String g = stack.pop();
                if (g.equals(f)) {
                    throw fns.get(f).pos().error("JSC0602", f + " is part of a mutual recursion: only direct self-recursion is allowed (ADR-0018 D7)");
                }
                if (seen.add(g)) for (String h : callGraph.getOrDefault(g, Set.of())) stack.push(h);
            }
        }
    }

    private Set<String> recursive() {
        var out = new TreeSet<String>();
        callGraph.forEach((f, gs) -> {
            if (!f.isEmpty() && gs.contains(f)) out.add(f);
        });
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static void expectType(Ty want, Ir.Expr v, Pos p) {
        if (!Ty.assignable(want, v.type())) {
            String hint = want.equals(Ty.KEY) && v.type().equals(Ty.BYTES) ? " (Bytes is not a Key: use asKey(b))" : "";
            throw p.error("JSC0339", "expected " + Ty.show(want) + " but this is " + Ty.show(v.type()) + hint);
        }
    }

    static byte[] hexBytes(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    // ------------------------------------------------------------------ syntax queries

    private static boolean paysIn(Syntax.Block b) {
        for (Stmt s : b.stmts()) {
            switch (s) {
                case Syntax.Pay p -> {
                    return true;
                }
                case IfStmt i -> {
                    if (paysIn(i.then()) || (i.otherwise() != null && paysIn(i.otherwise()))) return true;
                }
                case For f -> {
                    if (paysIn(f.body())) return true;
                }
                case MatchStmt ms -> {
                    for (Syntax.Arm a : ms.match().arms()) if (paysIn(a.body())) return true;
                }
                default -> {
                }
            }
        }
        return false;
    }

    private static Pos firstPay(Syntax.Block b) {
        for (Stmt s : b.stmts()) {
            if (s instanceof Syntax.Pay p) return p.pos();
            if (s instanceof IfStmt i) {
                if (paysIn(i.then())) return firstPay(i.then());
                if (i.otherwise() != null && paysIn(i.otherwise())) return firstPay(i.otherwise());
            }
            if (s instanceof For f && paysIn(f.body())) return firstPay(f.body());
            if (s instanceof MatchStmt ms) {
                for (Syntax.Arm a : ms.match().arms()) if (paysIn(a.body())) return firstPay(a.body());
            }
        }
        return b.pos();
    }

    private static boolean mentionsCtx(Syntax.Block b) {
        return mentions(b, "caller") || mentions(b, "received") || mentions(b, "balance") || mentions(b, "validFrom")
                || mentions(b, "validTo") || mentionsCall(b, "after") || mentionsCall(b, "before")
                || mentionsCall(b, "elapsedSince") || mentionsCall(b, "elapsedSinceAtMost") || mentionsCall(b, "windowStart")
                || mentionsCall(b, "windowEnd");
    }

    private static boolean mentions(Syntax.Block b, String name) {
        return walk(b, e -> e instanceof Name n && n.name().equals(name));
    }

    private static boolean mentionsCall(Syntax.Block b, String fn) {
        return walk(b, e -> e instanceof Call c && c.fn().equals(fn));
    }

    private static boolean walk(Syntax.Block b, java.util.function.Predicate<Expr> p) {
        for (Stmt s : b.stmts()) {
            boolean hit = switch (s) {
                case Const c -> walk(c.value(), p);
                case Syntax.Require r -> walk(r.cond(), p);
                case Syntax.Pay y -> walk(y.to(), p) || walk(y.amount(), p);
                case For f -> walk(f.list(), p) || walk(f.body(), p);
                case IfStmt i -> walk(i.cond(), p) || walk(i.then(), p) || (i.otherwise() != null && walk(i.otherwise(), p));
                case MatchStmt ms -> walk(ms.match(), p);
            };
            if (hit) return true;
        }
        return b.result() != null && walk(b.result(), p);
    }

    private static boolean walk(Expr e, java.util.function.Predicate<Expr> p) {
        if (p.test(e)) return true;
        return switch (e) {
            case Field f -> walk(f.target(), p);
            case Call c -> c.args().stream().anyMatch(a -> walk(a, p));
            case MethodCall m -> walk(m.target(), p) || m.args().stream().anyMatch(a -> walk(a, p));
            case Lambda l -> walk(l.body(), p);
            case ObjLit o -> (o.spread() != null && walk(o.spread(), p))
                    || o.fields().stream().anyMatch(f -> f.value() != null ? walk(f.value(), p) : p.test(new Name(f.name(), f.pos())));
            case ListLit l -> l.items().stream().anyMatch(a -> walk(a, p)) || (l.rest() != null && walk(l.rest(), p));
            case Unary u -> walk(u.operand(), p);
            case Binary b -> walk(b.left(), p) || walk(b.right(), p);
            case Syntax.Cond c -> walk(c.cond(), p) || walk(c.then(), p) || walk(c.otherwise(), p);
            case IfExpr i -> walk(i.cond(), p) || walk(i.then(), p) || walk(i.otherwise(), p);
            case BlockExpr be -> walk(be.block(), p);
            case Syntax.Match m -> walk(m.scrutinee(), p) || m.arms().stream().anyMatch(a -> walk(a.body(), p));
            case Index ix -> walk(ix.target(), p) || walk(ix.key(), p);
            default -> false;
        };
    }
}
