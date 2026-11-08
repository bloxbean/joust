package org.julclang.state.processor.gen;

import org.julclang.state.processor.Records;
import org.julclang.state.processor.ir.ContractIR;
import org.julclang.state.processor.ir.ParamIR;
import org.julclang.state.processor.ir.TransitionIR;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * {@code <Name>Transitions} (ADR-0015 D3): the contract's transitions as on-chain Java, one static method per
 * transition, each compiled by JuLC on its own ({@code compileMethod}) into a <em>transition program</em> that the
 * runtime engine evaluates with the VM. A transition program is the contract's own method, compiled by the compiler
 * that compiles its validator, so its meaning is the chain's.
 * <p>
 * Every program takes four Data arguments:
 * <ol>
 *   <li>the target's value (the state, or an owned object's value);</li>
 *   <li>the instance state for {@code @Shared} reads (any Data when unused);</li>
 *   <li>the arguments, as {@code Constr 0 [args in declaration order]};</li>
 *   <li>the Ctx (any Data when unused).</li>
 * </ol>
 * It returns {@code Constr 0 [next, payouts]}, where {@code next} is {@code Some(value)} or {@code None} (the instance or
 * object ends). That is the format of phase 4's {@code <Name>Portable.step} (ADR-0013 §10.2). A failing
 * {@code Require} fails the program.
 * <p>
 * Transitions with MerkleMaps, foreign reads, params, native assets, batches, composition, sends, mints or events are
 * not generated here yet (D3's read discovery and M3); {@link #methods} names what is.
 */
public final class TransitionsGenerator {

    private static final String API = "org.julclang.state.api.";

    private final ContractIR c;
    private final TypeElement contract;
    private final ProcessingEnvironment env;

    private TransitionsGenerator(ContractIR c, TypeElement contract, ProcessingEnvironment env) {
        this.c = c;
        this.contract = contract;
        this.env = env;
    }

    /** The source, and the transition methods it defines (name → the contract method it runs). */
    public record Transitions(String className, String source, List<String> methods) {}

    public static Transitions generate(ContractIR c, TypeElement contract, ProcessingEnvironment env) {
        return new TransitionsGenerator(c, contract, env).source();
    }

    /** The contract's event list type (ADR-0009 D5), or null: its steps then carry the method's events. */
    private String eventsType() {
        var all = new ArrayList<TransitionIR>(c.spendTransitions());
        all.addAll(c.ownedSpendTransitions());
        for (TransitionIR t : all) if (t.resultEvents() && t.eventsType() != null) return t.eventsType();
        return null;
    }

    private Transitions source() {
        String X = c.name();
        String cls = X + "Transitions";
        var w = new SourceWriter();
        var methods = new ArrayList<String>();
        var stepTypes = new LinkedHashMap<String, String>();   // target type (source form) → step record name
        var body = new SourceWriter();
        var all = new ArrayList<TransitionIR>(c.spendTransitions());
        all.addAll(c.ownedSpendTransitions());
        // Batch-only actions (@Batched(only = true)) have no direct call, but a batch runs them (ADR-0004).
        for (TransitionIR t : c.batched()) if (!all.contains(t)) all.add(t);
        for (TransitionIR t : all) {
            if (unsupported(t)) continue;
            ExecutableElement m = method(t.method());
            if (m == null || m.getParameters().isEmpty()) continue;
            String targetType = render(m.getParameters().get(0).asType());
            String step = stepTypes.computeIfAbsent(targetType, k -> "Step" + (stepTypes.size() + 1));
            String code = transition(t, m, targetType, step);
            if (code == null) continue;
            body.line(code);
            methods.add(t.method());
        }
        // Creations (@Init, @OwnedInit): no target; the step's next is what they make.
        var creations = new ArrayList<TransitionIR>();
        if (c.init() != null) creations.add(c.init());
        creations.addAll(c.ownedInits());
        if (c.migrate() != null) creations.add(c.migrate());   // @Migrate: reads the prior state (ADR-0011 D4)
        for (TransitionIR t : creations) {
            if (unsupported(t)) continue;
            ExecutableElement m = method(t.method());
            if (m == null || methods.contains(t.method())) continue;
            String made = render(m.getReturnType());
            String step = stepTypes.computeIfAbsent(made, k -> "Step" + (stepTypes.size() + 1));
            String code = creation(t, m, step);
            if (code == null) continue;
            body.line(code);
            methods.add(t.method());
        }
        w.line("package " + c.packageName() + ";").blank();
        w.line("import java.math.BigInteger;");
        w.line("import java.util.Optional;");
        w.line("import org.julclang.core.PlutusData;");
        w.line("import org.julclang.core.types.JulcList;");
        w.line("import org.julclang.core.types.JulcMap;");
        w.line("import org.julclang.state.api.Ctx;");
        w.line("import org.julclang.state.api.Payout;");
        w.line("import org.julclang.state.api.Payouts;");
        if (c.assetAware()) {
            w.line("import org.julclang.ledger.Value;");
            w.line("import org.julclang.state.api.Assets;");
            w.line("import org.julclang.state.api.Send;");
            w.line("import org.julclang.state.api.Sends;");
            w.line("import org.julclang.state.api.Deposit;");
        }
        w.blank();
        w.line("/** GENERATED by the JOUST toolchain from {@link " + X + "}: transition programs (ADR-0015 D3). Do not edit. */");
        w.open("public class " + cls + " {").blank();
        // An asset-aware contract's step also carries sends, its mint and the assets it accepts (ADR-0007).
        String events = eventsType();
        String eventsField = events == null ? "" : ", " + events + " events";
        stepTypes.forEach((type, name) -> w.line(c.assetAware()
                ? "public record " + name + "(Optional<" + type + "> next, JulcList<Payout> payouts, JulcList<Send> sends, Value mint, Value accepted, JulcList<Deposit> deposits" + eventsField + ") {}"
                : "public record " + name + "(Optional<" + type + "> next, JulcList<Payout> payouts" + eventsField + ") {}"));
        w.blank();
        w.line(body.toString().stripTrailing());
        w.close("}");
        return new Transitions(c.packageName() + "." + cls, w.toString(), methods);
    }

    private static boolean unsupported(TransitionIR t) {
        return false;
    }

    /** One transition method; null when its parameters or result are not supported. */
    private String transition(TransitionIR t, ExecutableElement m, String targetType, String step) {
        var argNames = t.params().stream().map(ParamIR::name).toList();
        String argsRecord = cap(t.method()) + "Args";
        var argFields = leadingFields(t, m);
        for (ParamIR p : t.params()) argFields.add(paramType(m, p.name()) + " " + p.name());
        String sharedType = "PlutusData";
        var call = new ArrayList<String>();
        boolean first = true;
        boolean paramsTaken = false;
        var readNames = t.reads().stream().map(TransitionIR.ReadIR::name).toList();
        for (VariableElement p : m.getParameters()) {
            String name = p.getSimpleName().toString();
            if (first) {
                call.add("target");
                first = false;
            } else if (t.takesParams() && !paramsTaken) {
                call.add("args.joustParams()");
                paramsTaken = true;
            } else if (readNames.contains(name)) {
                call.add("args." + name + "()");
            } else if (named(p.asType(), API + "Funds")) {
                call.add("args.joustFunds()");
            } else if (p.getAnnotationMirrors().stream().anyMatch(a -> named(a.getAnnotationType(), API + "Into"))) {
                call.add("args.joustInto()");
            } else if (name.equals(t.arriving())) {
                call.add("args.joustAdmitted()");
            } else if (p.getAnnotationMirrors().stream().anyMatch(a -> named(a.getAnnotationType(), API + "Shared"))) {
                sharedType = render(p.asType());
                call.add("shared");
            } else if (named(p.asType(), API + "Ctx")) {
                call.add("ctx");
            } else if (argNames.contains(name)) {
                call.add("args." + name + "()");
            } else {
                return null;
            }
        }
        String invoke = c.name() + "." + t.method() + "(" + String.join(", ", call) + ")";
        TypeMirror r = m.getReturnType();
        String result;
        String tail = (c.assetAware() ? ", Sends.none(), Assets.none(), Assets.none(), JulcList.empty()" : "")
                + (eventsType() != null ? ", JulcList.empty()" : "");
        if (render(r).equals(targetType)) {
            result = "return new " + step + "(Optional.of(" + invoke + "), Payouts.none()" + tail + ");";
        } else if (isPayouts(r)) {
            result = "return new " + step + "(Optional.empty(), " + invoke + tail + ");";
        } else if (r instanceof DeclaredType d && d.asElement().getKind() == ElementKind.RECORD) {
            boolean hasState = false, hasPayouts = false, hasSends = false, hasAccepted = false, hasMint = false, hasDeposits = false;
            boolean hasEvents = false;
            for (RecordComponentElement rc : Records.components((TypeElement) d.asElement())) {
                switch (rc.getSimpleName().toString()) {
                    case "state" -> hasState = true;
                    case "payouts" -> hasPayouts = true;
                    case "sends" -> hasSends = true;
                    case "accepted" -> hasAccepted = true;
                    case "mint" -> hasMint = true;
                    case "deposits" -> hasDeposits = true;
                    case "events" -> hasEvents = true;
                    default -> {
                        return null;
                    }
                }
            }
            if ((hasSends || hasAccepted || hasMint || hasDeposits) && !c.assetAware()) return null;
            result = render(r) + " r = " + invoke + ";\n        return new " + step + "("
                    + (hasState ? "Optional.of(r.state())" : "Optional.empty()") + ", "
                    + (hasPayouts ? "r.payouts()" : "Payouts.none()")
                    + (c.assetAware() ? ", " + (hasSends ? "r.sends()" : "Sends.none()") + ", "
                            + (hasMint ? "r.mint()" : "Assets.none()") + ", "
                            + (hasAccepted ? "r.accepted()" : "Assets.none()") + ", "
                            + (hasDeposits ? "r.deposits()" : "JulcList.empty()") : "")
                    + (eventsType() != null ? ", " + (hasEvents ? "r.events()" : "JulcList.empty()") : "") + ");";
        } else {
            return null;
        }
        var sb = new StringBuilder();
        sb.append("    public record ").append(argsRecord).append("(").append(String.join(", ", argFields)).append(") {}\n\n");
        sb.append("    /** Runs {@link ").append(c.name()).append("#").append(t.method()).append("}. */\n");
        sb.append("    public static ").append(step).append(" ").append(t.method()).append("(").append(targetType)
                .append(" target, ").append(sharedType).append(" shared, ").append(argsRecord).append(" args, Ctx ctx) {\n");
        sb.append("        ").append(result).append("\n    }\n");
        return sb.toString();
    }

    /** One creation method: its arguments, the instance state ({@code @Shared}) and the Ctx in; what it makes out. */
    private String creation(TransitionIR t, ExecutableElement m, String step) {
        var argNames = t.params().stream().map(ParamIR::name).toList();
        String argsRecord = cap(t.method()) + "Args";
        var argFields = leadingFields(t, m);
        for (ParamIR p : t.params()) argFields.add(paramType(m, p.name()) + " " + p.name());
        String sharedType = "PlutusData";
        var call = new ArrayList<String>();
        boolean paramsTaken = false;
        for (VariableElement p : m.getParameters()) {
            String name = p.getSimpleName().toString();
            if (t.takesParams() && !paramsTaken) {
                call.add("args.joustParams()");
                paramsTaken = true;
            } else if (named(p.asType(), API + "Funds")) {
                call.add("args.joustFunds()");
            } else if (p.getAnnotationMirrors().stream().anyMatch(a -> named(a.getAnnotationType(), API + "Shared"))) {
                sharedType = render(p.asType());
                call.add("shared");
            } else if (named(p.asType(), API + "Ctx")) {
                call.add("ctx");
            } else if (t.reads().stream().anyMatch(rd -> rd.name().equals(name))) {
                call.add("args." + name + "()");   // a read state: @Reads, or @Migrate's prior state
            } else if (argNames.contains(name)) {
                call.add("args." + name + "()");
            } else {
                return null;
            }
        }
        var sb = new StringBuilder();
        sb.append("    public record ").append(argsRecord).append("(").append(String.join(", ", argFields)).append(") {}\n\n");
        sb.append("    /** Runs {@link ").append(c.name()).append("#").append(t.method()).append("} (a creation: no target). */\n");
        sb.append("    public static ").append(step).append(" ").append(t.method()).append("(PlutusData target, ").append(sharedType)
                .append(" shared, ").append(argsRecord).append(" args, Ctx ctx) {\n");
        sb.append("        return new ").append(step).append("(Optional.of(").append(c.name()).append(".").append(t.method())
                .append("(").append(String.join(", ", call)).append(")), Payouts.none()")
                .append(c.assetAware() ? ", Sends.none(), Assets.none(), Assets.none(), JulcList.empty()" : "")
                .append(eventsType() != null ? ", JulcList.empty()" : "").append(");\n    }\n");
        return sb.toString();
    }

    /** The args record's leading fields: the {@code @Params} record and the {@code @Reads} states, before the arguments. */
    private List<String> leadingFields(TransitionIR t, ExecutableElement m) {
        var out = new ArrayList<String>();
        if (t.takesParams()) out.add(c.paramsType() + " joustParams");
        for (var rd : t.reads()) out.add(paramType(m, rd.name()) + " " + rd.name());
        if (t.hasFunds()) out.add("org.julclang.state.api.Funds joustFunds");
        // A move reads its destination's state (@Into); an @Admits action may read the arriving object (ADR-0012 D3).
        for (VariableElement p : m.getParameters()) {
            if (p.getAnnotationMirrors().stream().anyMatch(a -> named(a.getAnnotationType(), API + "Into"))) {
                out.add(render(p.asType()) + " joustInto");
            }
        }
        if (t.arriving() != null) out.add(paramType(m, t.arriving()) + " joustAdmitted");
        return out;
    }

    private String paramType(ExecutableElement m, String name) {
        for (VariableElement p : m.getParameters()) if (p.getSimpleName().contentEquals(name)) return render(p.asType());
        throw new IllegalStateException("no parameter " + name);
    }

    /** A type as source, with the contract's own nested types by their short names ({@code Counter.State}). */
    private String render(TypeMirror t) {
        String s = t.toString();
        String pkg = c.packageName() + ".";
        return s.replace(pkg, "").replace("java.math.BigInteger", "BigInteger")
                .replace("org.julclang.core.types.JulcList", "JulcList").replace("org.julclang.core.types.JulcMap", "JulcMap")
                .replace("org.julclang.state.api.Payout", "Payout").replace("org.julclang.state.api.Ctx", "Ctx")
                .replace("java.util.Optional", "Optional").replace("org.julclang.core.PlutusData", "PlutusData");
    }

    private boolean isPayouts(TypeMirror t) {
        return t instanceof DeclaredType d && named(t, "org.julclang.core.types.JulcList") && d.getTypeArguments().size() == 1
                && named(d.getTypeArguments().get(0), API + "Payout");
    }

    private static boolean named(TypeMirror t, String fq) {
        return t instanceof DeclaredType d && ((TypeElement) d.asElement()).getQualifiedName().contentEquals(fq);
    }

    private static String cap(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private ExecutableElement method(String name) {
        ExecutableElement any = null;
        for (ExecutableElement m : ElementFilter.methodsIn(contract.getEnclosedElements())) {
            if (!m.getSimpleName().contentEquals(name)) continue;
            boolean joust = m.getAnnotationMirrors().stream().anyMatch(a -> ((TypeElement) a.getAnnotationType().asElement())
                    .getQualifiedName().toString().startsWith(API));
            if (joust) return m;
            if (any == null) any = m;
        }
        return any;
    }
}
