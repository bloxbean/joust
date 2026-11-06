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
    static final String PAYOUT = API + "Payout";
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
        if (annotation(type, ONCHAIN_LIBRARY).isEmpty()) {
            error(type, "@StateContract class must also be annotated @OnchainLibrary"
                    + " (import org.julclang.stdlib.annotation.OnchainLibrary) so JuLC can compile it on-chain."
                    + " This goes away once JuLC accepts meta-annotated library annotations (ADR-0001 §12)");
        }
        String pkg = env.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        var types = new TypeRenderer(pkg);

        List<ExecutableElement> inits = new ArrayList<>();
        List<ExecutableElement> actions = new ArrayList<>();
        List<ExecutableElement> destroys = new ArrayList<>();
        List<ExecutableElement> views = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ExecutableElement m : ElementFilter.methodsIn(type.getEnclosedElements())) {
            int kinds = 0;
            if (annotation(m, INIT).isPresent()) { inits.add(m); kinds++; }
            if (annotation(m, ACTION).isPresent()) { actions.add(m); kinds++; }
            if (annotation(m, DESTROY).isPresent()) { destroys.add(m); kinds++; }
            if (annotation(m, VIEW).isPresent()) { views.add(m); kinds++; }
            if (kinds == 0) {
                for (String policy : List.of(PAYABLE, SIGNED_BY, ONLY_OWNER, VALID_AFTER, VALID_BEFORE)) {
                    if (annotation(m, policy).isPresent()) {
                        error(m, "@" + simple(policy) + " needs @Init, @Action or @Destroy on the same method");
                    }
                }
                continue;
            }
            if (kinds > 1) {
                error(m, "a method can be only one of @Init, @Action, @Destroy, @View");
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
        List<ParamIR> stateComponents = new ArrayList<>();
        Map<String, ParamIR> componentsByName = new LinkedHashMap<>();
        for (RecordComponentElement c : stateElement.getRecordComponents()) {
            var p = new ParamIR(c.getSimpleName().toString(), types.render(c.asType()), kindOf(c.asType()));
            stateComponents.add(p);
            componentsByName.put(p.name(), p);
        }

        var ctx = new Reading(types, stateType, componentsByName);
        TransitionIR init = readTransition(initMethod, TransitionIR.Kind.INIT, ctx);
        List<TransitionIR> actionIRs = new ArrayList<>();
        for (var m : actions) actionIRs.add(readTransition(m, TransitionIR.Kind.ACTION, ctx));
        List<TransitionIR> destroyIRs = new ArrayList<>();
        for (var m : destroys) destroyIRs.add(readTransition(m, TransitionIR.Kind.DESTROY, ctx));
        List<ViewIR> viewIRs = new ArrayList<>();
        for (var m : views) viewIRs.add(readView(m, ctx));

        var sc = annotation(type, STATE_CONTRACT);
        int maxDatumBytes = sc.flatMap(a -> value(a, "maxDatumBytes"))
                .map(v -> ((Number) v.getValue()).intValue()).orElse(512);
        long maxValidityMs = sc.flatMap(a -> value(a, "maxValidityMs"))
                .map(v -> ((Number) v.getValue()).longValue()).orElse(3_600_000L);
        long requiredMinAda = requiredMinAda(maxDatumBytes);
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
        reserved.add(type.getSimpleName() + "Validator");
        reserved.add(type.getSimpleName() + "Client");
        for (var t : concat(List.of(init), actionIRs, destroyIRs)) {
            if (!reserved.add(t.variant()) && t.kind() != TransitionIR.Kind.INIT) {
                error(type, "redeemer variant name " + t.variant() + " (from method " + t.method()
                        + ") clashes with a generated name; rename the method");
            }
        }
        types.checkNoClash(GENERATED_NAMES).forEach(msg -> error(type, msg));

        if (destroyIRs.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.WARNING, "this @StateContract has no @Destroy method: an instance can"
                    + " never end, so its min-ADA deposit (" + minAda + " lovelace) is locked forever", type);
        }
        if (failed) return Optional.empty();
        return Optional.of(new ContractIR(pkg, type.getSimpleName().toString(), stateText, stateComponents, minAda,
                maxDatumBytes, maxValidityMs,
                init, actionIRs, destroyIRs, viewIRs, types.imports(), Strategies.OPTION_A));
    }

    /** Coins per UTxO byte on mainnet and the public testnets (Conway). Governance can change it; ADR-0001 §10. */
    static final long COINS_PER_UTXO_BYTE = 4310L;

    /**
     * Deposit covering the ledger minimum for a state output: 160 bytes of ledger overhead, about 200 bytes of address,
     * value (lovelace plus the thread token) and CBOR framing, and the datum. Rounded up to 0.1 ADA.
     */
    static long requiredMinAda(int maxDatumBytes) {
        long raw = COINS_PER_UTXO_BYTE * (160L + 200L + maxDatumBytes);
        return (raw + 99_999L) / 100_000L * 100_000L;
    }

    /** Simple names the generated validator and client use for their own imports and nested types. */
    static final Set<String> GENERATED_NAMES = Set.of(
            "BigInteger", "Optional", "List", "PlutusData", "JulcList", "AssetEntry", "ScriptContext", "TxInfo",
            "TxOut", "TxInInfo", "TxOutRef", "Value", "Address", "Credential", "ScriptInfo", "Builtins", "Entrypoint",
            "MultiValidator", "Purpose", "AddressLib", "ByteStringLib", "ContextsLib", "OutputLib", "ValuesLib",
            "IntervalLib", "CryptoLib", "Interval", "IntervalBoundType", "OutputDatum", "Ctx", "Clock", "Payout", "Payouts", "Create", "Burn", "Account", "StateRuntime",
            "StateInstance", "TxResult", "Transition", "Mint", "Spend", "Call", "Deploy", "ContractSpec",
            "StateContractHandle", "Utxo", "Arrays");

    private record Reading(TypeRenderer types, TypeMirror stateType, Map<String, ParamIR> components) {}

    private TransitionIR readTransition(ExecutableElement m, TransitionIR.Kind kind, Reading r) {
        List<? extends VariableElement> ps = m.getParameters();
        int first = 0;
        if (kind != TransitionIR.Kind.INIT) {
            if (ps.isEmpty() || !same(ps.get(0).asType(), r.stateType())) {
                error(m, "the first parameter of @" + label(kind) + " must be the state ("
                        + r.types().render(r.stateType()) + ")");
            }
            first = 1;
        }
        boolean hasCtx = ps.size() > first && isNamed(ps.get(ps.size() - 1).asType(), CTX);
        int last = hasCtx ? ps.size() - 1 : ps.size();
        List<ParamIR> params = new ArrayList<>();
        for (int i = first; i < last; i++) {
            var p = ps.get(i);
            if (isNamed(p.asType(), CTX)) {
                error(p, "Ctx must be the last parameter");
            } else if (same(p.asType(), r.stateType())) {
                error(p, "only the first parameter may be the state");
            }
            params.add(new ParamIR(p.getSimpleName().toString(), r.types().render(p.asType()), kindOf(p.asType())));
        }

        TransitionIR.Returns returns;
        String resultType = null;
        TypeMirror ret = m.getReturnType();
        switch (kind) {
            case INIT -> returns = TransitionIR.Returns.STATE;
            case DESTROY -> {
                returns = TransitionIR.Returns.PAYOUTS;
                if (!isPayoutList(ret)) {
                    error(m, "@Destroy must return JulcList<Payout>: the payouts that allocate the contract's balance");
                }
            }
            default -> {
                if (same(ret, r.stateType())) {
                    returns = TransitionIR.Returns.STATE;
                } else if (isResultRecord(ret, r.stateType())) {
                    returns = TransitionIR.Returns.RESULT;
                    resultType = r.types().render(ret);
                } else {
                    returns = TransitionIR.Returns.STATE;
                    error(m, "@Action must return the state (" + r.types().render(r.stateType())
                            + ") or a record (" + r.types().render(r.stateType())
                            + " state, JulcList<Payout> payouts) to pay funds out");
                }
            }
        }

        boolean payable = annotation(m, PAYABLE).isPresent();
        if (payable && kind == TransitionIR.Kind.DESTROY) error(m, "@Payable does not apply to @Destroy");
        if (payable && !hasCtx) error(m, "@Payable needs a Ctx parameter, to read ctx.received()");
        if (kind == TransitionIR.Kind.DESTROY && !hasCtx) {
            error(m, "@Destroy needs a Ctx parameter: it must pay out ctx.balance(), everything the instance holds"
                    + " (deposit included)");
        }
        if (kind == TransitionIR.Kind.INIT) {
            for (ParamIR p : params) {
                if (p.name().equals("seed") || p.name().equals("ctx")) {
                    error(m, "@Init parameter name \"" + p.name() + "\" is reserved by the generated redeemer; rename it");
                }
            }
        }
        for (ParamIR p : params) {
            if (p.name().equals("ctx")) error(m, "parameter name \"ctx\" is reserved for the Ctx parameter; rename it");
        }

        List<String> signedBy = new ArrayList<>();
        annotation(m, SIGNED_BY).flatMap(a -> value(a, "value")).ifPresent(v -> signedBy.add((String) v.getValue()));
        if (annotation(m, ONLY_OWNER).isPresent()) signedBy.add("owner");
        List<String> validAfter = new ArrayList<>();
        annotation(m, VALID_AFTER).flatMap(a -> value(a, "value")).ifPresent(v -> validAfter.add((String) v.getValue()));
        List<String> validBefore = new ArrayList<>();
        annotation(m, VALID_BEFORE).flatMap(a -> value(a, "value")).ifPresent(v -> validBefore.add((String) v.getValue()));
        if (kind == TransitionIR.Kind.INIT && !(signedBy.isEmpty() && validAfter.isEmpty() && validBefore.isEmpty())) {
            error(m, "@SignedBy, @OnlyOwner, @ValidAfter and @ValidBefore check the current state, which @Init does not"
                    + " have; use Ctx and Require.that in the body instead");
        }
        for (String f : signedBy) checkField(m, r, f, ParamIR.TypeKind.BYTES, "byte[]", "@SignedBy/@OnlyOwner");
        for (String f : validAfter) checkField(m, r, f, ParamIR.TypeKind.INTEGER, "BigInteger", "@ValidAfter");
        for (String f : validBefore) checkField(m, r, f, ParamIR.TypeKind.INTEGER, "BigInteger", "@ValidBefore");

        String name = m.getSimpleName().toString();
        String variant = kind == TransitionIR.Kind.INIT ? "Create"
                : Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return new TransitionIR(kind, name, variant, List.copyOf(params), hasCtx, payable, returns, resultType,
                List.copyOf(signedBy), List.copyOf(validAfter), List.copyOf(validBefore));
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

    private boolean isResultRecord(TypeMirror t, TypeMirror stateType) {
        TypeElement e = asRecord(t);
        if (e == null) return false;
        var cs = e.getRecordComponents();
        return cs.size() == 2 && same(cs.get(0).asType(), stateType) && isPayoutList(cs.get(1).asType());
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
