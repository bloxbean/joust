package org.julclang.state.runtime.described;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.julclang.core.PlutusData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The strict input check before every evaluation of a transition (ADR-0020 S15, §11.4): each input a transition takes
 * is decoded against the type the contract declares for it, with {@link SchemaCodec}'s strict decoder (constructor
 * tag, field count and every field's kind), before the transition runs. A transition program decodes its inputs
 * leniently (the spike's malformed targets ran to a step half of the time: a wrong constructor tag or an extra field is
 * not checked by the program's wrapper), so a malformed input is refused here, with the argument it concerns named.
 * <p>
 * What is checked, from the description's call recipes ({@code x-joust}):
 * <ul>
 *   <li>the target: the recipe's {@code target} type, for every call on an existing state or object (a creation and
 *       a purpose handler have no target);</li>
 *   <li>the shared instance state, when the recipe takes it ({@code shared});</li>
 *   <li>the arguments: {@code Constr 0} with exactly the recipe's {@code transitionArgs}, each by its declared schema
 *       ({@code $params} by the params type, {@code $into} by the state, {@code $admitted} by the arriving owned type,
 *       a {@code $read} by its schema when the description carries one, {@code $amount} as an integer); {@code $funds}
 *       is built by the runtime itself and is not checked;</li>
 *   <li>the Ctx, when the call declares one.</li>
 * </ul>
 * An input the transition does not take is not checked (the program ignores it). No reflection: this runs in the Wasm
 * engine.
 */
public final class TransitionInputs implements TransitionFn {

    /** An input that does not fit its declared type: the engine refuses the call before evaluating anything. */
    public static final class Malformed extends IllegalArgumentException {
        public Malformed(String message) {
            super(message);
        }
    }

    private static final JsonNode INTEGER = JsonNodeFactory.instance.objectNode().put("dataType", "integer");

    /** One argument of the args record: its name in the recipe, and its schema (null: not checked). */
    record Arg(String name, JsonNode schema) {}

    /** What a transition takes: each schema null when the transition does not take that input. */
    record Abi(JsonNode target, String targetName, JsonNode shared, List<Arg> args, JsonNode ctx) {}

    private final String contract;
    private final SchemaCodec codec;
    private final TransitionFn fn;
    private final Map<String, Abi> abis = new HashMap<>();

    /** {@code fn} with every input checked against {@code description}'s declared types first. */
    public TransitionInputs(JsonNode description, TransitionFn fn) {
        this.fn = fn;
        this.codec = new SchemaCodec(description);
        String title = description.path("preamble").path("title").asText();
        this.contract = title.substring(title.lastIndexOf('.') + 1);
        JsonNode x = description.path("x-joust");
        for (JsonNode c : x.path("creations")) add(x, c, false);
        for (JsonNode a : x.path("actions")) add(x, a, true);
        for (JsonNode i : x.path("intents").path("calls")) add(x, i, true);
        for (JsonNode p : x.path("purposes")) add(x, p, false);
    }

    private void add(JsonNode x, JsonNode meta, boolean hasTarget) {
        JsonNode recipe = meta.path("recipe");
        String transition = recipe.path("transition").asText(null);
        if (transition == null) return;
        JsonNode target = null;
        String targetName = null;
        if (hasTarget && recipe.has("target")) {
            targetName = recipe.get("target").asText();
            target = ref(targetName);
        }
        JsonNode shared = recipe.path("shared").asBoolean() ? x.get("state") : null;
        // Intents have no ctx flag: their Ctx is the batch's, built by the runtime, and not checked.
        JsonNode ctx = meta.path("ctx").asBoolean() ? ref("org.julclang.state.api.Ctx") : null;
        List<Arg> args = null;
        if (recipe.has("transitionArgs")) {
            args = new ArrayList<>();
            var byName = new HashMap<String, JsonNode>();
            meta.path("args").forEach(a -> byName.put(a.path("name").asText(), a.get("schema")));
            var reads = new HashMap<String, JsonNode>();
            recipe.path("reads").forEach(r -> reads.put(r.path("name").asText(), r.get("schema")));
            for (JsonNode n : recipe.get("transitionArgs")) {
                String a = n.asText();
                JsonNode schema;
                if (a.equals("$params")) schema = x.get("params");
                else if (a.startsWith("$read:")) schema = reads.get(a.substring(6));
                else if (a.equals("$funds")) schema = null;
                else if (a.equals("$amount")) schema = INTEGER;
                else if (a.equals("$into")) schema = x.get("state");
                else if (a.equals("$admitted")) schema = owned(x, recipe.path("admits").asText());
                else schema = byName.get(a);
                args.add(new Arg(a, schema));
            }
        }
        abis.put(transition, new Abi(target, targetName, shared, args, ctx));
    }

    private JsonNode ref(String key) {
        return JsonNodeFactory.instance.objectNode().put("$ref", "#/definitions/" + key.replace("~", "~0").replace("/", "~1"));
    }

    /** The owned type an {@code @Admits} action reads ({@code admits}: its simple name, {@code Guild.Hero}). */
    private JsonNode owned(JsonNode x, String admits) {
        String simple = admits.substring(admits.lastIndexOf('.') + 1);
        for (JsonNode o : x.path("owned")) if (o.path("simple").asText().equals(simple)) return ref(o.path("type").asText());
        return null;
    }

    /**
     * Checks one evaluation's inputs; throws {@link Malformed} naming the input that does not fit. A transition the
     * description does not declare is refused too: its inputs have no declared types.
     */
    public void check(String method, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx) {
        Abi abi = abis.get(method);
        String where = contract + "." + method;
        if (abi == null) throw new Malformed(where + ": the description declares no such transition");
        if (abi.target() != null) fits(where, "the target (" + simpleName(abi.targetName()) + ")", abi.target(), target, "target");
        if (abi.shared() != null) fits(where, "the shared instance state", abi.shared(), shared, "shared");
        if (abi.args() != null) {
            if (!(args instanceof PlutusData.ConstrData c) || c.tag() != 0) {
                throw new Malformed(where + ": the arguments are not a Constr 0 record");
            }
            if (c.fields().size() != abi.args().size()) {
                throw new Malformed(where + ": " + abi.args().size() + " arguments declared, " + c.fields().size() + " given");
            }
            for (int i = 0; i < c.fields().size(); i++) {
                Arg a = abi.args().get(i);
                if (a.schema() != null) fits(where, "the argument " + a.name(), a.schema(), c.fields().get(i), a.name());
            }
        }
        if (abi.ctx() != null) fits(where, "the Ctx", abi.ctx(), ctx, "ctx");
    }

    private void fits(String where, String what, JsonNode schema, PlutusData d, String path) {
        if (d == null) throw new Malformed(where + ": " + what + " is missing");
        try {
            codec.decode(schema, d, path);
        } catch (SchemaCodec.DataError e) {
            throw new Malformed(where + ": " + what + " does not fit its declared type (" + e.getMessage() + ")");
        }
    }

    private static String simpleName(String key) {
        String[] p = key.split("\\.");
        return p.length >= 2 ? p[p.length - 2] + "." + p[p.length - 1] : key;
    }

    @Override
    public PlutusData apply(String method, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx) {
        check(method, target, shared, args, ctx);
        return fn.apply(method, target, shared, args, ctx);
    }

    @Override
    public Run run(String method, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx) {
        check(method, target, shared, args, ctx);
        return fn.run(method, target, shared, args, ctx);
    }
}
