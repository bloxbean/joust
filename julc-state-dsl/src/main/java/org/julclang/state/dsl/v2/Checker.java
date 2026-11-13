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
            "Payable", "SignedBy", "ValidAfter", "ValidBefore", "Batched", "StateContract", "String", "Object", "Boolean",
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
            "IntentHandle", "Batcher", "List",
            // M1c review C4: the ledger schemas a batched contract's ABI manifest states beside the contract's types
            // (an intent's refund Address); a declared type of the same name would be overwritten there.
            "StakingCredential", "PubKeyCredential", "ScriptCredential", "StakingHash", "StakingPtr");

    /**
     * Security review S6 (ADR-0004 §3): in a batched action a check on the caller is not 'only f'. An intent authorises
     * its action until its deadline, against whatever state the batch finds, in the order the batcher chooses.
     */
    static final String INTENT_CAVEAT = "Note: an intent authorises its action until its deadline, against the state a batcher applies it"
            + " to, in the batcher's order (ADR-0004 §3): keep deadlines short and bound order-sensitive arguments";

    /** The form of a generated check's message key (D12): an author's require message may not take it. */
    static final java.util.regex.Pattern GENERATED_KEY = java.util.regex.Pattern.compile("#[a-z]+:");

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

    /** State fields that hold time values (D11), contract-wide; grown to a fixed point. */
    private Set<String> timeFields = new TreeSet<>();
    private Set<String> nextTimeFields = new TreeSet<>();

    private final LinkedHashSet<Ir.Warning> warnings = new LinkedHashSet<>();
    private final Map<String, Set<String>> callGraph = new TreeMap<>();
    private final Set<String> analysed = new HashSet<>();
    private final Set<Ir.Local> used = Collections.newSetFromMap(new IdentityHashMap<>());
    private int seq;

    // ------------------------------------------------------------------ modules (D9, M4)

    /**
     * A file of the build: the main file (a contract, or a library lowered on its own) or a {@code .jsc} library it
     * imports, transitively. {@code cls} is a library's class (its capitalised file name), null for a contract;
     * {@code importedAt} is where the main file imports it (directly, or through the library that does), so an error in
     * a library is reported there, naming the library's own position. {@code visible}: the names its imports bring;
     * {@code std}: its std imports; {@code deps}: the library each relative specifier names.
     */
    static final class Module {
        final String id;
        final String fileName;
        final String text;
        final File file;
        final String cls;
        final Pos importedAt;
        final Map<String, Module> deps;
        final Set<String> visible = new HashSet<>();
        final Set<String> std = new HashSet<>();

        Module(String id, String fileName, String text, File file, String cls, Pos importedAt, Map<String, Module> deps) {
            this.id = id;
            this.fileName = fileName;
            this.text = text;
            this.file = file;
            this.cls = cls;
            this.importedAt = importedAt;
            this.deps = deps;
        }

        String qualified() {
            return file.pkg() + "." + cls;
        }

        String where() {
            return id != null ? id : fileName;
        }

        /** The type, enum and variant names it declares: what its importers' parser reads as constructors. */
        Set<String> typeNames() {
            var out = new HashSet<String>();
            for (TypeDecl t : file.types()) out.add(t.name());
            for (EnumDecl e : file.enums()) {
                out.add(e.name());
                for (VariantDecl v : e.variants()) out.add(v.name());
            }
            return out;
        }
    }

    /** The main file. */
    private final Module main;
    /** The libraries the main file imports, transitively, in dependency order (a library before its importers). */
    private final List<Module> libraries;
    /** The module whose code is being checked: names resolve in its scope (its declarations and imports). */
    private Module current;
    /** The module declaring each type, enum, variant and fn of the build. */
    private final Map<String, Module> owner = new HashMap<>();

    // ------------------------------------------------------------------ Java libraries (D9, M4b)

    /** The certified Java libraries a {@code java:} import may name. */
    private final JavaLibraries javaLibraries;
    /** The Java libraries this build imports, by class: their records and imported fns. */
    private final LinkedHashMap<String, Ir.JavaLib> javaUsed = new LinkedHashMap<>();
    /** Each imported Java fn's library (by its {@code .jsc} name, which is its Java name). */
    private final Map<String, String> javaFnLib = new HashMap<>();
    /** Each Java library record's library (by its simple name). */
    private final Map<String, String> javaRecLib = new HashMap<>();
    /** The Java library records that are opaque: no fields to read, no equality, never written as a type. */
    private final Set<String> javaOpaque = new HashSet<>();

    private Checker(Module main, List<Module> libraries, JavaLibraries javaLibraries) {
        this.file = main.file;
        this.fileName = main.fileName;
        this.main = main;
        this.libraries = libraries;
        this.current = main;
        this.javaLibraries = javaLibraries;
    }

    /** All modules, libraries first (dependency order), the main file last. */
    private List<Module> modules() {
        var out = new ArrayList<Module>(libraries);
        out.add(main);
        return out;
    }

    /** A file lowered on its own: no {@code .jsc} imports resolve. */
    static Ir.Program check(File file, String fileName) {
        Module m = new Module(null, fileName, null, file, file.library() ? libraryClass(fileName, file, new Pos(1, 1)) : null, null, Map.of());
        try {
            for (Import im : file.imports()) {
                if (isModuleSpec(im.from())) throw noModule(im.pos(), im.from(), null);
            }
            return checkFile(m, List.of(), JavaLibraries.NONE);
        } catch (JscException e) {
            throw Parser.hinted(e, file.leadingMinus(), file.members());
        }
    }

    /**
     * Checks {@code source}, the module {@code id} of a build whose {@code .jsc} libraries {@code modules} resolves (ADR-0018
     * D9, M4): its imports are loaded first, transitively, each parsed and declared in the build's one namespace.
     */
    static Ir.Program check(String source, String fileName, String id, JscModules modules) {
        return check(source, fileName, id, modules, JavaLibraries.NONE);
    }

    /** As above, with the certified Java libraries {@code java:} imports may name (D9, M4b). */
    static Ir.Program check(String source, String fileName, String id, JscModules modules, JavaLibraries javaLibraries) {
        var loader = new Loader(modules);
        Module main = loader.main(source, fileName, id);
        try {
            return checkFile(main, loader.order, javaLibraries);
        } catch (JscException e) {
            throw Parser.hinted(e, main.file.leadingMinus(), main.file.members());
        }
    }

    /** Whether an import names a {@code .jsc} module (not {@code std/…} or {@code java:…}). */
    static boolean isModuleSpec(String spec) {
        return !spec.startsWith("std/") && !spec.startsWith("java:");
    }

    private static JscException noModule(Pos p, String spec, String from) {
        if (JscModules.isPackage(spec)) {
            return p.error("JSC0706", "no .jsc library " + spec + " in the project's npm packages (a package ships its libraries under"
                    + " joust-lib/: " + JscModules.packageOf(spec) + "/joust-lib/" + spec.substring(JscModules.packageOf(spec).length() + 1) + ")");
        }
        if (!JscModules.isRelative(spec)) {
            return p.error("JSC0706", "'" + spec + "' is not a module: a .jsc library is imported by its path relative to this file,"
                    + " ending in .jsc (import { fee } from \"./fees.jsc\"), or from an npm package (\"@acme/fees/fees.jsc\"); the standard"
                    + " library is std/…, a Java library java:…");
        }
        return p.error("JSC0706", "no .jsc library " + spec + (from == null ? " (this file is lowered on its own, so it imports no libraries)"
                : " beside " + from + " under the sources (a library is imported by its path relative to the importing file, and never from"
                + " outside the source directory)"));
    }

    /**
     * A library's class: its file name without {@code .jsc}, capitalised ({@code fees.jsc} is {@code Fees}). It must be a
     * plain class name, and not one the generated code uses.
     */
    static String libraryClass(String fileName, File file, Pos at) {
        String base = fileName.endsWith(".jsc") ? fileName.substring(0, fileName.length() - 4) : fileName;
        String cls = base.isEmpty() ? base : Character.toUpperCase(base.charAt(0)) + base.substring(1);
        if (!cls.matches("[A-Z][A-Za-z0-9]*") || RESERVED.contains(cls) || PROCESSOR_TYPES.contains(cls)) {
            throw at.error("JSC0705", "a library's file name is its Java class (fees.jsc is the class Fees of its package): '" + fileName
                    + "' gives '" + cls + "', which is not a class name the generated code can use; name the file with letters and"
                    + " digits only (fees.jsc, FeeMath.jsc)");
        }
        return cls;
    }

    /** Loads the main file and its libraries: each module once, dependencies first; an import cycle is refused. */
    private static final class Loader {
        final JscModules modules;
        final Map<String, Module> loaded = new HashMap<>();
        final List<Module> order = new ArrayList<>();
        final ArrayDeque<String> stack = new ArrayDeque<>();

        Loader(JscModules modules) {
            this.modules = modules;
        }

        Module main(String source, String fileName, String id) {
            if (id != null) stack.push(id);
            var deps = new LinkedHashMap<String, Module>();
            var types = new HashSet<String>();
            for (Import im : Parser.imports(source)) {
                // A Java library's records (M4b): a capitalised imported name is read as a type, so a constructor of one
                // reaches the checker, which refuses it (read-only, JSC0724).
                if (im.from().startsWith("java:")) for (String n : im.names()) if (Character.isUpperCase(n.charAt(0))) types.add(n);
                if (!isModuleSpec(im.from()) || deps.containsKey(im.from())) continue;
                JscModules.Source s = JscModules.isRelative(im.from()) || JscModules.isPackage(im.from()) ? modules.resolve(id, im.from()) : null;
                if (s == null) throw noModule(im.pos(), im.from(), id == null ? null : id);
                Module d = load(s, im.pos());
                deps.put(im.from(), d);
                types.addAll(d.typeNames());
            }
            File f = Parser.parse(source, types);
            String cls = f.library() ? libraryClass(fileName, f, new Pos(1, 1)) : null;
            return new Module(id, fileName, source, f, cls, null, deps);
        }

        /** The library {@code s}, imported (directly or through other libraries) by the main file at {@code at}. */
        Module load(JscModules.Source s, Pos at) {
            Module done = loaded.get(s.id());
            if (done != null) return done;
            if (stack.contains(s.id())) {
                var chain = new ArrayList<String>(stack);
                java.util.Collections.reverse(chain);
                chain.add(s.id());
                throw at.error("JSC0707", "these libraries import each other: " + String.join(" -> ", chain) + "; a library's imports form"
                        + " no cycle (move what both need into a third library)");
            }
            stack.push(s.id());
            try {
                var deps = new LinkedHashMap<String, Module>();
                var types = new HashSet<String>();
                List<Import> imports;
                try {
                    imports = Parser.imports(s.text());
                } catch (JscException e) {
                    throw inLibrary(s.id(), at, e);
                }
                for (Import im : imports) {
                    if (im.from().startsWith("java:")) for (String n : im.names()) if (Character.isUpperCase(n.charAt(0))) types.add(n);
                    if (!isModuleSpec(im.from()) || deps.containsKey(im.from())) continue;
                    JscModules.Source d = JscModules.isRelative(im.from()) || JscModules.isPackage(im.from()) ? modules.resolve(s.id(), im.from()) : null;
                    if (d == null) throw inLibrary(s.id(), at, noModule(im.pos(), im.from(), s.id()));
                    Module dm = load(d, at);
                    deps.put(im.from(), dm);
                    types.addAll(dm.typeNames());
                }
                File f;
                String cls;
                try {
                    JscV2.checkFileName(s.fileName());
                    f = Parser.parse(s.text(), types);
                    if (!f.library()) {
                        throw new Pos(1, 1).error("JSC0708", s.fileName() + " is a contract, not a library: only a file that starts"
                                + " 'jsc 2' then 'library <package>' can be imported");
                    }
                    cls = libraryClass(s.fileName(), f, new Pos(1, 1));
                } catch (JscException e) {
                    throw inLibrary(s.id(), at, e);
                }
                Module m = new Module(s.id(), s.fileName(), s.text(), f, cls, at, deps);
                loaded.put(s.id(), m);
                order.add(m);
                return m;
            } finally {
                stack.pop();
            }
        }
    }

    /** An error in a library, reported where the main file imports it, naming the library's own position (J5). */
    static JscException inLibrary(String where, Pos at, JscException e) {
        if ("JSC0712".equals(e.code)) return e;
        return new JscException(at.line(), at.col(), "JSC0712", "in the imported library " + where + ":" + e.line + ":" + e.col + ": "
                + (e.code == null ? "" : "error[" + e.code + "]: ") + e.getMessage());
    }

    /** Runs {@code r} with {@code m} as the current module; an error in a library is reported at its import. */
    private void inModule(Module m, Runnable r) {
        Module was = current;
        current = m;
        try {
            r.run();
        } catch (JscException e) {
            if (m == main) throw e;
            throw inLibrary(m.where(), m.importedAt, e);
        } finally {
            current = was;
        }
    }

    /**
     * Whether the current module may name {@code name}: its own declarations, and what its imports bring (an imported
     * enum brings its variants). A library's other declarations are reachable only through the values its fns return.
     */
    private void visible(String name, Pos p) {
        Module o = owner.get(name);
        if (o == null || o == current || current.visible.contains(name)) return;
        String what = variantEnum.containsKey(name) ? "the variant " + name + " of " + variantEnum.get(name) + ", which is declared in "
                + o.where() + ": import { " + variantEnum.get(name) + " }" : "'" + name + "' is declared in " + o.where() + ": import { " + name + " }";
        throw p.error("JSC0710", what + " from it to use it here (a library's declarations are seen only where they are imported)");
    }

    private static Ir.Program checkFile(Module main, List<Module> libraries, JavaLibraries javaLibraries) {
        var c = new Checker(main, libraries, javaLibraries);
        File file = main.file;
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
        for (Pos m : file.leadingMinus()) {
            c.warn(m, "JSC9007", "this line starts with '-', which continues the expression on the line above (a subtraction):"
                    + " newlines do not end statements in .jsc; if that is meant, join the lines or put the expression in parentheses;"
                    + " if the line is meant to stand alone, end the line above with ';'");
        }
        return new Ir.Program(p.pkg(), p.fileName(), p.name(), p.options(), p.records(), p.enums(), p.state(), p.methods(), p.fns(),
                Set.copyOf(c.timeFields), c.recursive(), List.copyOf(c.warnings), p.library(), p.libraries(), p.javaLibraries());
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

    /** The types and enums a module declares (a contract's inside its body too). */
    private static List<TypeDecl> typesOf(Module m) {
        var out = new ArrayList<TypeDecl>(m.file.types());
        if (m.file.contract() != null) out.addAll(m.file.contract().types());
        return out;
    }

    private static List<EnumDecl> enumsOf(Module m) {
        var out = new ArrayList<EnumDecl>(m.file.enums());
        if (m.file.contract() != null) out.addAll(m.file.contract().enums());
        return out;
    }

    private static List<Syntax.Fn> fnsOf(Module m) {
        var out = new ArrayList<Syntax.Fn>(m.file.fns());
        if (m.file.contract() != null) out.addAll(m.file.contract().fns());
        return out;
    }

    private void declare() {
        Contract c = file.contract();
        String contractName = c == null ? null : c.name();
        if (c != null) checkUserName(c.name(), c.pos(), "contract");
        // Each library is a Java class of its package (D9, M4): two may not share one, and no declaration or contract of the
        // build takes a library's class name (it would shadow the class in the generated Java).
        var classes = new HashMap<String, Module>();
        for (Module m : libraries) {
            Module other = classes.putIfAbsent(m.qualified(), m);
            if (other != null) {
                throw m.importedAt.error("JSC0709", m.where() + " and " + other.where() + " are both the library " + m.qualified()
                        + " (a library's package and file name are its class): rename one");
            }
            if (m.cls.equals(contractName)) {
                throw m.importedAt.error("JSC0709", "the library " + m.where() + " is the class " + m.cls + ", the contract's name; rename one");
            }
        }
        if (main.cls != null && libraries.stream().anyMatch(l -> l.cls.equals(main.cls))) {
            throw new Pos(1, 1).error("JSC0709", "this library and an imported one are both classes named " + main.cls + "; rename one");
        }
        var libraryClasses = new HashSet<String>();
        for (Module m : libraries) libraryClasses.add(m.cls);
        // Records, enums and variants share one namespace, across the build's files (D5, D9): each becomes a class.
        for (Module m : modules()) {
            inModule(m, () -> {
                for (TypeDecl t : typesOf(m)) {
                    typeName(t.name(), t.pos(), "type", contractName, libraryClasses);
                    records.put(t.name(), new LinkedHashMap<>());
                    owner.put(t.name(), m);
                }
                for (EnumDecl e : enumsOf(m)) {
                    typeName(e.name(), e.pos(), "enum", contractName, libraryClasses);
                    if (e.variants().isEmpty()) throw e.pos().error("JSC0909", "the enum " + e.name() + " needs at least one variant");
                    var vs = new LinkedHashMap<String, LinkedHashMap<String, Ty>>();
                    enums.put(e.name(), vs);
                    owner.put(e.name(), m);
                    for (VariantDecl v : e.variants()) {
                        typeName(v.name(), v.pos(), "variant", contractName, libraryClasses);
                        vs.put(v.name(), new LinkedHashMap<>());
                        variantEnum.put(v.name(), e.name());
                        owner.put(v.name(), m);
                    }
                }
            });
        }
        var methodNames = new HashSet<String>();
        if (c != null) for (Syntax.Method m : c.methods()) methodNames.add(m.name());
        for (Module mod : modules()) {
            inModule(mod, () -> {
                for (Syntax.Fn f : fnsOf(mod)) {
                    checkUserName(f.name(), f.pos(), "function");
                    if (records.containsKey(f.name()) || enums.containsKey(f.name()) || variantEnum.containsKey(f.name())) {
                        throw f.pos().error("JSC0221", "the fn " + f.name() + " has the name of a declared type or variant; rename one");
                    }
                    Module other = owner.get(f.name());
                    if (fns.containsKey(f.name()) && other != null && other != mod) {
                        throw f.pos().error("JSC0709", "the fn " + f.name() + " is also declared in " + other.where() + ": the declarations of a"
                                + " build (a contract and the libraries it imports) share one namespace; rename one");
                    }
                    if (fns.containsKey(f.name()) || PRELUDE.contains(f.name()) || Std.ROWS.containsKey(f.name())) {
                        throw f.pos().error("JSC0201", "the function " + f.name() + " is declared twice or shadows a built-in");
                    }
                    if (mod == main && methodNames.contains(f.name())) {
                        throw f.pos().error("JSC0202", "the fn " + f.name() + " has the name of a method of the contract; rename one (both become methods of one Java class)");
                    }
                    if (libraryClasses.contains(f.name())) {
                        throw f.pos().error("JSC0709", "the fn " + f.name() + " has the name of an imported library's class; rename it");
                    }
                    fns.put(f.name(), f);
                    owner.put(f.name(), mod);
                }
            });
        }
        for (Module m : modules()) inModule(m, () -> imports(m));
        for (Module m : modules()) {
            inModule(m, () -> {
                for (TypeDecl t : typesOf(m)) fillFields(records.get(t.name()), t.fields(), t.name(), false);
                for (EnumDecl e : enumsOf(m)) {
                    for (VariantDecl v : e.variants()) fillFields(enums.get(e.name()).get(v.name()), v.fields(), v.name(), false);
                }
            });
        }
        if (c != null) fillFields(stateFields, c.state(), "state", true);
        for (Module m : modules()) {
            inModule(m, () -> {
                for (TypeDecl t : typesOf(m)) {
                    if (cyclic(t.name())) {
                        throw t.pos().error("JSC0301", "the type " + t.name() + " contains itself; recursive types are not supported");
                    }
                }
                for (EnumDecl e : enumsOf(m)) {
                    if (cyclic(e.name())) {
                        throw e.pos().error("JSC0302", "the enum " + e.name() + " contains itself; recursive types are not supported");
                    }
                }
            });
        }
        for (Module m : modules()) {
            var declaredAt = new LinkedHashMap<String, Pos>();
            for (TypeDecl t : typesOf(m)) declaredAt.put(t.name(), t.pos());
            for (EnumDecl e : enumsOf(m)) declaredAt.put(e.name(), e.pos());
            inModule(m, () -> typeDepths(declaredAt));
        }
        if (c == null) return;
        // The state is a root too: each field's type nests at most MAX_DEPTH levels (J18).
        for (FieldDecl f : c.state()) measured(stateFields.get(f.name()), f.type().pos());
        if (stateFields.isEmpty()) throw c.pos().error("JSC0303", "the state needs at least one field");
        // Security S7: a state field is read by its bare name, so it never takes a declared type's, enum's, variant's
        // or fn's name, which it would hide in every method (a field Open would replace the variant Open).
        for (FieldDecl f : c.state()) declaredName(f.name(), f.pos());
        var seen = new HashSet<String>();
        // The processor names redeemer variants after methods (capitalised): they must not clash with generated or
        // declared types.
        var generated = new HashSet<>(Set.of("Payout", "State", "Outcome", "CreateInstance", "BurnInstance", "Batch", "InBatch",
                "CancelIntent", "Adopt", "Calls", "Step"));
        generated.addAll(records.keySet());
        generated.addAll(enums.keySet());
        generated.addAll(variantEnum.keySet());
        generated.addAll(libraryClasses);
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
        // M1c: a batched action's intent form is the client's method <m>Intent and the validator's record <M>Intent
        // (ADR-0004; ContractReader refuses the clash), so neither name may be taken.
        for (Syntax.Method m : c.methods()) {
            if (m.kind() != Kind.ACTION || m.mods().stream().noneMatch(x -> x.name().equals("batched"))) continue;
            String variant = Character.toUpperCase(m.name().charAt(0)) + m.name().substring(1) + "Intent";
            if (seen.contains(m.name() + "Intent") || !generated.add(variant)) {
                throw m.pos().error("JSC0223", "the batched action " + m.name() + " has an intent form named " + m.name()
                        + "Intent (the client's method) and " + variant + " (the validator's record); a method or type of"
                        + " that name would clash with it: rename one of them");
            }
        }
        if (c.methods().stream().noneMatch(m -> m.kind() == Kind.INIT)) throw c.pos().error("JSC0401", "the contract needs an init(…)");
    }

    /** A module's imports: std rows into its {@code std} set, a library's declarations into {@code visible} (D8, D9). */
    private void imports(Module m) {
        for (Import im : m.file.imports()) {
            if (im.from().startsWith("java:")) {
                javaImport(m, im);
                continue;
            }
            if (isModuleSpec(im.from())) {
                Module dep = m.deps.get(im.from());
                if (dep == null) throw noModule(im.pos(), im.from(), m.where());
                for (String n : im.names()) {
                    if (owner.get(n) != dep) {
                        throw im.pos().error("JSC0708", "'" + n + "' is not declared in " + im.from() + (owner.containsKey(n) ? " (it is in "
                                + owner.get(n).where() + ")" : "") + ": a library exports its types, enums and fns");
                    }
                    m.visible.add(n);
                    // An imported enum brings its variants, which build and match its values.
                    if (enums.containsKey(n)) m.visible.addAll(enums.get(n).keySet());
                }
                continue;
            }
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
                m.std.add(n);
            }
        }
    }

    /**
     * {@code import { f, R } from "java:com.acme.Lib"} (D9, M4b): the library must be on the library path and certified;
     * each name is one of its public static methods, admitted (its signature maps to {@code .jsc} types: exact types, from
     * javac's attribution of its on-chain sources), or one of its records. A library's records and the class itself join
     * the build's namespace.
     */
    private void javaImport(Module m, Import im) {
        String fqcn = im.from().substring("java:".length());
        if (!fqcn.matches("([A-Za-z_][A-Za-z0-9_]*\\.)+[A-Za-z_][A-Za-z0-9_]*")) {
            throw im.pos().error("JSC0720", "'" + im.from() + "' does not name a class: write java:<package>.<Class>");
        }
        JavaLibraries.Library lib = javaLibraries.describe(fqcn);
        if (lib == null) {
            throw im.pos().error("JSC0720", "no on-chain sources of the Java library " + fqcn + " are on the library path (Gradle: the"
                    + " joustLibrary configuration; npm: a package's joust-lib/): a java: import compiles the library from its sources, on"
                    + " both sides (ADR-0018 D9)");
        }
        if (!lib.problems().isEmpty()) {
            throw im.pos().error("JSC0721", "the Java library " + fqcn + " is not certified, so it cannot be imported (ADR-0018 D9): "
                    + String.join("; ", lib.problems()));
        }
        javaRegister(lib, im.pos());
        Ir.JavaLib used = javaUsed.get(fqcn);
        for (String n : im.names()) {
            var candidates = lib.fns().stream().filter(f -> f.name().equals(n)).toList();
            if (candidates.size() > 1) {
                throw im.pos().error("JSC0723", "java:" + fqcn + "." + n + " is overloaded: .jsc calls a library fn by name, so an overloaded"
                        + " method cannot be imported (give each its own name in the library)");
            }
            if (candidates.size() == 1) {
                JavaLibraries.Fn f = candidates.get(0);
                if (f.refusal() != null) {
                    throw im.pos().error("JSC0722", "java:" + fqcn + "." + n + " cannot be imported: " + f.refusal());
                }
                if (fns.containsKey(n) || PRELUDE.contains(n) || Std.ROWS.containsKey(n) || (javaFnLib.containsKey(n) && !javaFnLib.get(n).equals(fqcn))
                        || records.containsKey(n) || enums.containsKey(n) || variantEnum.containsKey(n)) {
                    throw im.pos().error("JSC0709", "java:" + fqcn + "." + n + " has the name of another declaration or import of this build; rename"
                            + " one (the declarations of a build share one namespace)");
                }
                var params = new ArrayList<Ty>();
                for (JavaLibraries.JType t : f.params()) params.add(javaTy(lib, t));
                var jf = new Ir.JavaFn(n, List.copyOf(params), javaTy(lib, f.result()));
                used.fns().put(n, jf);
                javaFnLib.put(n, fqcn);
                m.visible.add(n);
                continue;
            }
            if (lib.records().stream().anyMatch(r -> r.simpleName().equals(n))) {
                m.visible.add(n);
                continue;
            }
            throw im.pos().error("JSC0708", "'" + n + "' is not a public static method or a record of java:" + fqcn);
        }
    }

    /** A Java library's class and records, into the build's namespace (once per library). */
    private void javaRegister(JavaLibraries.Library lib, Pos at) {
        String fqcn = lib.fqcn();
        if (javaUsed.containsKey(fqcn)) return;
        String cls = lib.simpleName();
        if (records.containsKey(cls) || enums.containsKey(cls) || variantEnum.containsKey(cls) || fns.containsKey(cls) || cls.equals(main.cls)
                || libraries.stream().anyMatch(l -> l.cls.equals(cls)) || (file.contract() != null && file.contract().name().equals(cls))
                || javaUsed.values().stream().anyMatch(j -> j.simpleName().equals(cls)) || RESERVED.contains(cls) || PROCESSOR_TYPES.contains(cls)) {
            throw at.error("JSC0709", "the Java library " + fqcn + " is the class " + cls + ", the name of a declaration, library or contract of this"
                    + " build (or of the generated code); rename one");
        }
        var recs = new LinkedHashMap<String, Ir.JavaRec>();
        for (JavaLibraries.Rec r : lib.records()) {
            String n = r.simpleName();
            if (records.containsKey(n) || enums.containsKey(n) || variantEnum.containsKey(n) || fns.containsKey(n) || javaRecLib.containsKey(n)
                    || RESERVED.contains(n) || PROCESSOR_TYPES.contains(n) || n.equals(cls) || n.contains("_")) {
                throw at.error("JSC0709", "the record " + r.binaryName() + " of java:" + fqcn + " is named " + n + ", the name of a declaration of this"
                        + " build (or of the generated code); rename one");
            }
            records.put(n, new LinkedHashMap<>());
            javaRecLib.put(n, fqcn);
            if (!r.verified()) javaOpaque.add(n);
        }
        for (JavaLibraries.Rec r : lib.records()) {
            var fields = records.get(r.simpleName());
            if (r.verified()) for (int i = 0; i < r.fieldNames().size(); i++) fields.put(r.fieldNames().get(i), javaTy(lib, r.fieldTypes().get(i)));
            recs.put(r.simpleName(), new Ir.JavaRec(new Ir.RecordType(r.simpleName(), fields, at), r.canonicalOwner(), r.importClass(),
                    r.sourceName(), r.verified()));
        }
        javaUsed.put(fqcn, new Ir.JavaLib(fqcn, lib.sourceSha256(), recs, new LinkedHashMap<>(), lib.messages()));
    }

    /** A Java type as a {@code .jsc} type (an admitted signature maps; a record by its simple name). */
    private static Ty javaTy(JavaLibraries.Library lib, JavaLibraries.JType t) {
        return switch (t.kind()) {
            case "Int" -> Ty.INT;
            case "Bool" -> Ty.BOOL;
            case "Bytes" -> Ty.BYTES;
            case "List" -> new Ty.ListT(javaTy(lib, t.elem()));
            case "Option" -> new Ty.OptT(javaTy(lib, t.elem()));
            case "Record" -> {
                JavaLibraries.Rec r = lib.record(t.record());
                if (r == null) throw new IllegalStateException("the certifier described no record " + t.record());
                yield new Ty.Rec(r.simpleName());
            }
            default -> throw new IllegalStateException("an unmappable type in an admitted signature: " + t.why());
        };
    }

    /** A Java library's name is seen only where it is imported (D9). */
    private void visibleJava(String name, Pos p) {
        if (current.visible.contains(name)) return;
        String lib = javaFnLib.containsKey(name) ? javaFnLib.get(name) : javaRecLib.get(name);
        throw p.error("JSC0710", "'" + name + "' is from java:" + lib + ": import { " + name + " } from \"java:" + lib + "\" to use it here");
    }

    /** Whether a type holds an opaque Java record anywhere (it is then not comparable: D6). */
    private boolean hasOpaque(Ty t) {
        return switch (t) {
            case Ty.Rec r -> javaOpaque.contains(r.name()) || components(r.name()).stream().anyMatch(this::hasOpaque);
            case Ty.ListT l -> hasOpaque(l.elem());
            case Ty.OptT o -> hasOpaque(o.elem());
            case Ty.MapT m -> hasOpaque(m.val());
            default -> false;
        };
    }

    /** A declared type, enum or variant name: capitalised, without '_', not reserved, not taken (D5, J10). */
    private void typeName(String n, Pos p, String what, String contractName, Set<String> libraryClasses) {
        if (!Character.isUpperCase(n.charAt(0))) throw p.error("JSC0205", what + " names start with a capital: " + n);
        if (n.contains("_")) throw p.error("JSC0206", what + " names are written without '_' (e.g. " + n.replace("_", "") + ")");
        checkUserName(n, p, what);
        if (PROCESSOR_TYPES.contains(n)) {
            throw p.error("JSC0220", "'" + n + "' is a name the generated validator or client uses for its own types; choose another " + what + " name");
        }
        if (n.equals(contractName)) {
            throw p.error("JSC0207", "the " + what + " " + n + " has the contract's name; rename one (it is generated inside the contract's class)");
        }
        if (libraryClasses.contains(n) || n.equals(main.cls)) {
            throw p.error("JSC0709", "the " + what + " " + n + " has the name of a library's class in this build; rename one");
        }
        if (records.containsKey(n) || enums.containsKey(n) || variantEnum.containsKey(n)) {
            Module other = owner.get(n);
            if (other != null && other != current) {
                throw p.error("JSC0709", "the " + what + " " + n + " is also declared in " + other.where() + ": the declarations of a build (a"
                        + " contract and the libraries it imports) share one namespace; rename one");
            }
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
        if (ty instanceof Ty.Rec && javaRecLib.containsKey(t.name())) {
            visibleJava(t.name(), t.pos());
            if (javaOpaque.contains(t.name())) {
                throw t.pos().error("JSC0725", t.name() + " is an opaque record of java:" + javaRecLib.get(t.name()) + " (its encoding is not"
                        + " verified): it can be passed between the library's fns, never written as a type, stored, compared or read (ADR-0018 D9)");
            }
        } else if (ty instanceof Ty.Rec || ty instanceof Ty.EnumT) visible(t.name(), t.pos());
        if (ty == null && variantEnum.containsKey(t.name())) {
            throw t.pos().error("JSC0911", t.name() + " is a variant of " + variantEnum.get(t.name()) + ", not a type: write " + variantEnum.get(t.name()));
        }
        if (ty == null || (n > 0 && !(ty instanceof Ty.ListT || ty instanceof Ty.OptT || ty instanceof Ty.MapT || ty instanceof Ty.BigMapT))) {
            throw t.pos().error("JSC0307", "unknown type '" + t.name() + "' (Int, Bool, Bytes, Key, Hash, List<T>, Option<T>, Map<K, V>, BigMap<K, V>,"
                    + " or a declared type or enum)");
        }
        return measured(ty, t.pos());
    }

    /** A map key type: Bytes, Key or Hash, at most 64 bytes (D5, F19). */
    private Ty mapKey(TypeRef k, String what) {
        Ty t = resolve(k);
        if (!Ty.isBytes(t)) {
            throw k.pos().error("JSC0308", "a " + what + "'s keys are Bytes, Key or Hash (at most 64 bytes, canonical order: ADR-0018 D5), not " + Ty.show(t));
        }
        return t;
    }

    /**
     * Whether a declared record or enum contains itself, directly or through other types, inside a List, an Option, a
     * Map or a BigMap's values too (review r4 C8): a cycle is refused at its declaration, so every later walk over the
     * types ends.
     */
    private boolean cyclic(String name) {
        // A work list, not recursion: a long chain of types cannot exhaust the stack (ADR-0018 J18).
        var seen = new HashSet<String>();
        var work = new ArrayDeque<>(typeRefs(name));
        while (!work.isEmpty()) {
            String n = work.pop();
            if (n.equals(name)) return true;
            if (seen.add(n)) work.addAll(typeRefs(n));
        }
        return false;
    }

    /** The declared types a record's or an enum's components name, inside lists, options and maps too. */
    private List<String> typeRefs(String name) {
        var out = new ArrayList<String>();
        for (Ty t : components(name)) named(t, out);
        return out;
    }

    /** The declared types {@code t} names (a written type nests at most {@link Parser#MAX_DEPTH} deep). */
    private static void named(Ty t, List<String> out) {
        switch (t) {
            case Ty.Prim p -> { }
            case Ty.ListT l -> named(l.elem(), out);
            case Ty.OptT o -> named(o.elem(), out);
            case Ty.MapT m -> {
                named(m.key(), out);
                named(m.val(), out);
            }
            case Ty.BigMapT m -> named(m.val(), out);
            case Ty.Rec r -> out.add(r.name());
            case Ty.EnumT e -> out.add(e.name());
        }
    }

    /**
     * The nesting cap on types (ADR-0018 J18): a type contains others at most {@link Parser#MAX_DEPTH} deep, since every
     * walk over a value's type (checked inputs, equality, the codecs) follows that chain. Measured on the acyclic type
     * graph, in a loop; refused at the type whose chain is too long.
     */
    private void typeDepths(Map<String, Pos> declared) {
        var depth = new HashMap<String, Integer>();
        for (String root : declared.keySet()) {
            var stack = new ArrayDeque<String>();
            stack.push(root);
            while (!stack.isEmpty()) {
                String n = stack.peek();
                if (depth.containsKey(n)) {
                    stack.pop();
                    continue;
                }
                boolean ready = true;
                for (String m : typeRefs(n)) {
                    if (!depth.containsKey(m)) {
                        stack.push(m);
                        ready = false;
                    }
                }
                if (!ready) continue;
                stack.pop();
                // A value of n nests one level, then its deepest component: every List, Option and Map layer and every
                // named type counts (review r1 C2, S3), as every walk over the value's type follows them all.
                int d = 1;
                for (Ty t : components(n)) d = Math.max(d, 1 + structuralDepth(t, depth));
                if (d > Parser.MAX_DEPTH) {
                    throw declared.get(n).error("JSC0340", "the type " + n + " nests more than " + Parser.MAX_DEPTH
                            + " levels deep, counting each List, Option and Map layer and each type it contains (ADR-0018 J18): flatten it");
                }
                depth.put(n, d);
            }
        }
        namedDepth = depth;
    }

    /** Each declared type's structural depth (set once declared types are measured; null before). */
    private Map<String, Integer> namedDepth;

    /** How deep a value of {@code t} nests: a layer per List, Option and Map, and a named type's own depth. */
    private static int structuralDepth(Ty t, Map<String, Integer> named) {
        return switch (t) {
            case Ty.Prim p -> 0;
            case Ty.ListT l -> 1 + structuralDepth(l.elem(), named);
            case Ty.OptT o -> 1 + structuralDepth(o.elem(), named);
            case Ty.MapT m -> 1 + Math.max(structuralDepth(m.key(), named), structuralDepth(m.val(), named));
            case Ty.BigMapT m -> 1 + structuralDepth(m.val(), named);
            case Ty.Rec r -> named.getOrDefault(r.name(), 0);
            case Ty.EnumT e -> named.getOrDefault(e.name(), 0);
        };
    }

    /**
     * A written type, measured once the declared types are (a state field, a parameter, a const's or fn's type): it nests
     * at most {@link Parser#MAX_DEPTH} levels, refused where it is written, before any code using it is checked (J18).
     */
    private Ty measured(Ty t, Pos p) {
        if (namedDepth != null && structuralDepth(t, namedDepth) > Parser.MAX_DEPTH) {
            throw p.error("JSC0340", "this type nests more than " + Parser.MAX_DEPTH + " levels deep, counting each List, Option and Map"
                    + " layer and each type it contains (ADR-0018 J18): flatten it");
        }
        return t;
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
        // M1c review F4: 'only f', 'after f' and 'before f' name a state field after a modifier, and 'batched only' is
        // intent-only before another modifier, so a field named like a modifier would change what a header means
        // ('batched only open'). The modifier words are no field or binding name (methods and fns may use them).
        if (Parser.MODIFIERS.contains(n) && !what.equals("method") && !what.equals("function") && !what.equals("contract")) {
            throw p.error("JSC0211", "'" + n + "' is reserved and cannot be a " + what + " name: it is a method modifier");
        }
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
        // Only a declaration this file can name can be hidden: a library's unimported names are not in scope (D9, M4).
        Module o = owner.get(n);
        if (o != null && o != current && !current.visible.contains(n)) return;
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
        var methods = new ArrayList<Ir.Method>();
        if (c != null) {
            for (Syntax.Option o : c.options()) {
                switch (o.name()) {
                    case "minAda", "maxDatumBytes", "maxValidityMs" -> {
                        if (o.value().signum() < 0 || o.value().bitLength() > 62) throw o.pos().error("JSC0402", o.name() + " is out of range");
                        options.add(new Ir.ContractOption(o.name(), o.value()));
                    }
                    default -> throw o.pos().error("JSC0403", "unknown contract option '" + o.name() + "' (minAda, maxDatumBytes, maxValidityMs)");
                }
            }
            for (Syntax.Method m : c.methods()) methods.add(method(m));
        }
        // Every fn of the build, each in its module's scope: the program's own, then each library's (the interpreters
        // evaluate library fns from the program's IR; D9, M4).
        var byModule = new HashMap<Module, List<Ir.Fn>>();
        for (Syntax.Fn f : fns.values()) {
            byModule.computeIfAbsent(owner.get(f.name()), k -> new ArrayList<>()).add(checkFn(f, new int[f.params().size()]));
        }
        var libs = new ArrayList<Ir.Library>();
        for (Module m : libraries) {
            libs.add(new Ir.Library(m.qualified(), m.fileName, IrJson.sourceHash(m.text), recordsOf(m), enumsIrOf(m),
                    List.copyOf(byModule.getOrDefault(m, List.of())), m.id));
        }
        List<Ir.Fn> own = List.copyOf(byModule.getOrDefault(main, List.of()));
        List<Ir.JavaLib> java = List.copyOf(javaUsed.values());
        if (c == null) {
            return new Ir.Program(file.pkg(), fileName, main.cls, List.of(), recordsOf(main), enumsIrOf(main), null, List.of(), own,
                    Set.of(), Set.of(), List.of(), true, List.copyOf(libs), java);
        }
        return new Ir.Program(file.pkg(), fileName, c.name(), options, recordsOf(main), enumsIrOf(main),
                new Ir.RecordType("State", stateFields, c.pos()), methods, own, Set.of(), Set.of(), List.of(), false, List.copyOf(libs), java);
    }

    private LinkedHashMap<String, Ir.RecordType> recordsOf(Module m) {
        var recs = new LinkedHashMap<String, Ir.RecordType>();
        for (TypeDecl t : typesOf(m)) recs.put(t.name(), new Ir.RecordType(t.name(), records.get(t.name()), t.pos()));
        return recs;
    }

    private LinkedHashMap<String, Ir.EnumType> enumsIrOf(Module m) {
        var ens = new LinkedHashMap<String, Ir.EnumType>();
        for (EnumDecl e : enumsOf(m)) {
            var vs = new LinkedHashMap<String, Ir.RecordType>();
            for (VariantDecl v : e.variants()) vs.put(v.name(), new Ir.RecordType(v.name(), enums.get(e.name()).get(v.name()), v.pos()));
            ens.put(e.name(), new Ir.EnumType(e.name(), vs, e.pos()));
        }
        return ens;
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
        boolean batched = m.mods().stream().anyMatch(x -> x.name().equals("batched"));
        var modNames = new HashSet<String>();
        for (Mod mod : m.mods()) {
            if (!modNames.add(mod.name())) throw mod.pos().error("JSC0416", "the modifier '" + mod.name() + "' is given twice");
            if (m.kind() == Kind.INIT && !mod.name().equals("payable")) throw mod.pos().error("JSC0405", "an init takes only 'payable'");
            if (batched && (mod.name().equals("only") || mod.name().equals("after") || mod.name().equals("before"))) {
                // Review F4: the hint names the check that replaces this modifier; S6: with ADR-0004's caveat.
                String instead = mod.name().equals("only")
                        ? "the batcher, not the caller, signs a batch: check the caller in the body with require(caller == " + mod.arg() + ", …)"
                        : "the batcher sets a batch's window: check time in the body with require(" + mod.name() + "(" + mod.arg() + "), …)";
                throw mod.pos().error("JSC0417", "a batched action cannot use '" + mod.name() + "': " + instead + " (ADR-0004 D1). " + INTENT_CAVEAT);
            }
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
                case "batched" -> {
                    // ADR-0018 D10 (gated by pool, M1c): @Batched, or @Batched(only = true) for 'batched only'.
                    if (m.kind() != Kind.ACTION) throw mod.pos().error("JSC0418", "only an action can be batched (ADR-0004 D1)");
                    if (stateFields.values().stream().anyMatch(t -> t instanceof Ty.BigMapT)) {
                        throw mod.pos().error("JSC0419", "a contract with BigMap state cannot have batched actions: each step of a batch"
                                + " would need its own map proofs (ADR-0004 D1, ADR-0018 D7)");
                    }
                }
                default -> throw mod.pos().error("JSC0407", "unknown modifier " + mod.name());
            }
            mods.add(new Ir.Mod(mod.name(), mod.arg()));
        }
        if (pays && !audience && batched) {
            throw m.pos().error("JSC0420", m.name() + " can pay out, so it must say who may call it; a batched action cannot use"
                    + " 'only' (any intent's owner is its caller), so write 'open' and check the caller in the body (ADR-0018 D4). " + INTENT_CAVEAT);
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
            // M1c review C1: a batched action's intent method (ClientGenerator: <m>Intent(callerAccount, args…,
            // [payLovelace,] deadline)) takes the intent's deadline after the arguments.
            if (batched && p.name().equals("deadline")) {
                throw p.pos().error("JSC0216", "'deadline' is a name the generated client uses for its own values (the intent's deadline in "
                        + m.name() + "Intent(…, deadline)); rename the parameter");
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
        // A library's own build reports its warnings, at its positions; here they would name this file's lines.
        if (current != main) return;
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
                Map<String, FT> acc = stateTaints(i.otherwise().result());
                for (Ir.Branch b : i.branches()) acc = merge(stateTaints(b.then().result()), acc);
                return acc;
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
        Module m = owner.getOrDefault(f.name(), main);
        if (m == current) return checkFnHere(f, taints);
        Ir.Fn[] out = new Ir.Fn[1];
        inModule(m, () -> out[0] = checkFnHere(f, taints));
        return out[0];
    }

    private Ir.Fn checkFnHere(Syntax.Fn f, int[] taints) {
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

    /** A fn to check again with time parameters marked, and the call chain that reached it (for the message). */
    private record Pending(Syntax.Fn f, int[] taints, String context) {}

    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private boolean draining;
    private String analysing = "";

    /**
     * D11 through calls: a fn called with time arguments is checked again with those parameters marked (S25). The fns a
     * call chain reaches are checked from a work list, not by recursion, so a long chain of fns cannot exhaust the stack
     * (ADR-0018 J18); an error names every call on the way, innermost first, as before.
     */
    private void analyseCall(Syntax.Fn f, int[] taints, Pos at) {
        boolean any = false;
        for (int t : taints) any |= t != 0;
        if (!any) return;
        if (!analysed.add(f.name() + "/" + Arrays.toString(taints))) return;
        pending.add(new Pending(f, taints, " (in " + f.name() + ", called with a time value at " + at + ")" + analysing));
        if (draining) return;
        draining = true;
        try {
            while (!pending.isEmpty()) {
                Pending p = pending.poll();
                analysing = p.context();
                try {
                    checkFn(p.f(), p.taints());
                } catch (JscException e) {
                    throw new JscException(e.line, e.col, e.code, e.getMessage() + p.context());
                }
            }
        } finally {
            draining = false;
            analysing = "";
            pending.clear();
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
                case Syntax.Require r -> {
                    // A generated check's key ("#input:spend2.1", D12) names one construct: an author's message never
                    // holds that form, anywhere, so no trace of it can be taken for a generated check (review r1 S2, C3).
                    if (GENERATED_KEY.matcher(r.message()).find()) {
                        throw r.pos().error("JSC0430", "a require message may not contain '#word:', the form of the checks the lowering"
                                + " generates (ADR-0018 D12): reword it");
                    }
                    out.add(new Ir.Require(requireCond(r.cond(), sc), r.message(), r.pos()));
                }
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
                    // An else-if chain is one node: its branches are checked in order, in a loop (J18).
                    var branches = new ArrayList<Ir.Branch>();
                    boolean pays = false;
                    for (Syntax.Branch b : i.branches()) {
                        // The first condition is tested where the if is; a later one inside the helper of the branch
                        // before's else, so a recursive call there is refused as in any arm (JSC0601, review r1 S5).
                        Ir.Expr cond = condition(b.cond(), branches.isEmpty() ? sc : sc.child(false, false));
                        branches.add(new Ir.Branch(cond, armBlock(b.then(), sc), b.pos()));
                        pays |= paysIn(b.then());
                    }
                    Ir.Block other = i.otherwise() == null ? null : armBlock(i.otherwise(), sc);
                    pays |= i.otherwise() != null && paysIn(i.otherwise());
                    out.add(new Ir.If(List.copyOf(branches), other, pays, i.pos()));
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
            visible(n.name(), n.pos());
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
            if (javaOpaque.contains(r.name())) {
                throw f.pos().error("JSC0725", r.name() + " is an opaque record of java:" + javaRecLib.get(r.name()) + ": its fields cannot be read"
                        + " (its encoding is not verified; ADR-0018 D9)");
            }
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
        if (user != null) {
            visible(fn, c.pos());
            return userCall(user, c, sc);
        }
        if (javaFnLib.containsKey(fn)) return javaCall(c, sc);
        Std.Binding b = Std.ROWS.get(fn);
        if (b != null) {
            if (!current.std.contains(fn)) throw c.pos().error("JSC0704", fn + " is in " + b.module() + ": import { " + fn + " } from \"" + b.module() + "\"");
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

    /**
     * A certified Java library's fn (D9, M4b): its exact signature, mapped; a time value never reaches it (D11: a
     * {@code java:} import never accepts one), and its result is a checked input (D5; the mapped types hold no Key, Hash or
     * Map, so it needs no generated check).
     */
    private Ir.Expr javaCall(Call c, Sc sc) {
        String fn = c.fn();
        visibleJava(fn, c.pos());
        String lib = javaFnLib.get(fn);
        Ir.JavaFn jf = javaUsed.get(lib).fns().get(fn);
        if (c.args().size() != jf.params().size()) {
            throw c.pos().error("JSC0325", fn + " takes " + jf.params().size() + " argument" + (jf.params().size() == 1 ? "" : "s"));
        }
        var args = new ArrayList<Ir.Expr>();
        for (int i = 0; i < c.args().size(); i++) {
            Ir.Expr a = expr(c.args().get(i), sc, jf.params().get(i));
            expectType(jf.params().get(i), a, c.args().get(i).pos());
            if ((a.taint() & TIME) != 0) {
                throw c.args().get(i).pos().error("JSC0726", fn + " is a Java library's (java:" + lib + "): a time value never reaches one, since its"
                        + " body is not checked for the time rule (ADR-0018 D11)");
            }
            args.add(a);
        }
        return new Ir.CallJava(lib, fn, args, jf.result(), 0, c.pos());
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
                    + " a cycle that is not a self-cycle (ADR-0018 D7, J9); move the recursive call out (an else-if chain with statements"
                    + " in an arm lowers each branch, its condition included, to a helper)");
        }
        var args = new ArrayList<Ir.Expr>();
        int taint = 0;
        int[] taints = new int[c.args().size()];
        for (int i = 0; i < c.args().size(); i++) {
            Ty pt = signatureType(f, f.params().get(i).type());
            Ir.Expr a = expr(c.args().get(i), sc, pt);
            expectType(pt, a, c.args().get(i).pos());
            args.add(a);
            taint |= a.taint();
            taints[i] = a.taint();
        }
        analyseCall(f, taints, c.pos());
        return new Ir.CallFn(f.name(), args, signatureType(f, f.ret()), taint, c.pos());
    }

    /**
     * A type in {@code f}'s signature, resolved where {@code f} is declared: a library fn's parameter and result types are
     * its library's, whether or not the caller imports them (D9, M4).
     */
    private Ty signatureType(Syntax.Fn f, TypeRef t) {
        Module m = owner.getOrDefault(f.name(), main);
        if (m == current) return resolve(t);
        Ty[] out = new Ty[1];
        inModule(m, () -> out[0] = resolve(t));
        return out[0];
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
            if (variantEnum.containsKey(o.type())) {
                visible(o.type(), o.pos());
                return variantLit(o, sc);
            }
            if (!records.containsKey(o.type())) throw o.pos().error("JSC0329", "unknown type " + o.type());
            visible(o.type(), o.pos());
            javaReadOnly(o.type(), o.pos());
            recName = o.type();
        } else if (expected instanceof Ty.Rec r) {
            recName = r.name();
            javaReadOnly(recName, o.pos());
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
            String outer = bigWriteField;
            if (bigField) bigWriteField = f.name();
            Ir.Expr v;
            try {
                v = expr(ve, sc, ft);
            } finally {
                bigWriteField = outer;
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

    /** A Java library's records are built by its fns, never in {@code .jsc} (D9: read-only). */
    private void javaReadOnly(String rec, Pos p) {
        if (javaRecLib.containsKey(rec)) {
            throw p.error("JSC0724", rec + " is a record of java:" + javaRecLib.get(rec) + ": read-only in .jsc (ADR-0018 D9); build it with the"
                    + " library's fns");
        }
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
                if (hasOpaque(t)) {
                    throw b.pos().error("JSC0725", "an opaque Java library record is not comparable: only through an equality fn its library"
                            + " exports (ADR-0018 D6, D9)");
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

    /**
     * Whether an if used as a value lowers to nested conditionals: at most {@link Parser#CHAIN} branches, no statements in
     * any arm. {@link Depth} counts a level per branch of such a chain, as the conditionals nest.
     */
    static boolean plainChain(IfExpr i, String fn) {
        if (!i.otherwise().stmts().isEmpty()) return false;
        for (Syntax.Branch b : i.branches()) if (!b.then().stmts().isEmpty()) return false;
        // A longer chain lowers to helpers, unless it calls the fn it is in: then it stays inline so the call stays a
        // direct self-call (as it was before r12, review r1 C1), and nests a level per branch against the cap.
        return i.branches().size() <= Parser.CHAIN || fn != null && Depth.calls(i, fn);
    }

    /**
     * An if used as a value. A short chain without statements is nested {@link Ir.Cond}s; a longer chain, or arms with
     * statements, an {@link Ir.IfValue}.
     */
    private Ir.Expr ifExpr(IfExpr i, Sc sc, Ty expected) {
        if (plainChain(i, sc.kind == null ? sc.owner : null)) {
            // Up to Parser.CHAIN branches without statements: nested conditionals, inline (a recursive call may sit in an arm).
            Syntax.Branch b = i.branches().get(0);
            Expr rest = i.branches().size() == 1 ? i.otherwise().result()
                    : new IfExpr(i.branches().subList(1, i.branches().size()), i.otherwise(), i.branches().get(1).pos());
            return cond(b.cond(), b.then().result(), rest, i.pos(), sc, expected);
        }
        // A longer chain, or one with statements, is one node (J18): its conditions, then its arms, each checked in a loop;
        // the lowering makes each arm a helper.
        var conds = new ArrayList<Ir.Expr>();
        // Every condition is tested inside the chain's helpers, so a recursive call there is refused as in an arm
        // (JSC0601 at the call, review r1 S5), never left for JuLC.
        for (Syntax.Branch b : i.branches()) conds.add(condition(b.cond(), sc.child(false, false)));
        var arms = new ArrayList<Syntax.Block>();
        for (Syntax.Branch b : i.branches()) arms.add(b.then());
        arms.add(i.otherwise());
        // Without an expected type, the first arm that does not need one gives it to the others.
        int first = 0;
        if (expected == null) {
            for (int k = 0; k < arms.size(); k++) {
                if (!needsContext(arms.get(k).result())) {
                    first = k;
                    break;
                }
            }
        }
        var out = new Ir.Block[arms.size()];
        out[first] = valueBlock(arms.get(first), sc.child(false, false), expected);
        Ty want = expected != null ? expected : out[first].result().type();
        for (int k = 0; k < arms.size(); k++) if (k != first) out[k] = valueBlock(arms.get(k), sc.child(false, false), want);
        Ty t = expected;
        if (t == null) {
            t = out[out.length - 1].result().type();
            for (int k = out.length - 2; k >= 0; k--) t = unify(out[k].result().type(), t, i.pos());
        }
        int taint = 0;
        for (Ir.Block b : out) {
            expectType(t, b.result(), b.result().pos());
            noBigWrite(t, b.result(), i.pos(), "chosen by a condition");
            taint |= b.result().taint();
        }
        var branches = new ArrayList<Ir.Branch>();
        for (int k = 0; k < conds.size(); k++) branches.add(new Ir.Branch(conds.get(k), out[k], i.branches().get(k).pos()));
        return new Ir.IfValue(List.copyOf(branches), out[out.length - 1], t, taint, i.pos());
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
            case Ir.IfValue i -> writesBigMap(i.otherwise().result()) || i.branches().stream().anyMatch(b -> writesBigMap(b.then().result()));
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
                bigWriteHere(m.pos(), target);
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
                bigWriteHere(m.pos(), target);
                Ir.Expr k = mapKeyArg(args.get(0), bt.key(), sc, "the map's keys");
                return new Ir.BigOp("delete", target, List.of(k), bt, target.taint(), m.pos());
            }
            default -> throw m.pos().error("JSC0828", "BigMap has no method '" + m.method() + "' (get, has, set, delete; m[k] on BigMap<K, Int>)");
        }
    }

    /** The BigMap field whose new value is being checked: the only BigMap that may be written there (D7, S11). */
    private String bigWriteField;

    private void bigWriteHere(Pos p, Ir.Expr target) {
        Ir.Expr root = target;
        while (root instanceof Ir.BigOp b) root = b.map();
        String field = root instanceof Ir.StateField sf ? sf.field() : root instanceof Ir.GetField g ? g.field() : null;
        if (bigWriteField == null || !bigWriteField.equals(field)) {
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
                    for (Syntax.Branch br : i.branches()) if (paysIn(br.then())) return true;
                    if (i.otherwise() != null && paysIn(i.otherwise())) return true;
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
                for (Syntax.Branch br : i.branches()) if (paysIn(br.then())) return firstPay(br.then());
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
                case IfStmt i -> i.branches().stream().anyMatch(br -> walk(br.cond(), p) || walk(br.then(), p))
                        || (i.otherwise() != null && walk(i.otherwise(), p));
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
            case IfExpr i -> i.branches().stream().anyMatch(br -> walk(br.cond(), p) || walk(br.then(), p)) || walk(i.otherwise(), p);
            case BlockExpr be -> walk(be.block(), p);
            case Syntax.Match m -> walk(m.scrutinee(), p) || m.arms().stream().anyMatch(a -> walk(a.body(), p));
            case Index ix -> walk(ix.target(), p) || walk(ix.key(), p);
            default -> false;
        };
    }
}
