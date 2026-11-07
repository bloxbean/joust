package org.julclang.state.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks a contract's JavaScript description ({@code META-INF/joust/<package>.<Contract>.json}, ADR-0013 D3) against
 * the validator the processor generated: every redeemer and datum constructor at the validator's own index, with the
 * validator's own fields, in order. A JavaScript client that encodes from the description encodes what the validator
 * decodes.
 */
public final class Descriptions {

    private Descriptions() {
    }

    /** The description of {@code contract} on the classpath. */
    public static JsonNode of(Class<?> contract) {
        String name = "META-INF/joust/" + contract.getName() + ".json";
        try (InputStream in = contract.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new AssertionError("no description " + name);
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new AssertionError("cannot read " + name, e);
        }
    }

    /** Fails with every mismatch between the description of {@code contract} and {@code validator}. */
    public static void assertMatches(Class<?> contract, Class<?> validator) {
        JsonNode d = of(contract);
        var problems = new ArrayList<String>();
        JsonNode defs = d.get("definitions");
        String prefix = validator.getName() + ".";
        sum(defs, prefix + "Lifecycle", nested(validator, "Lifecycle"), problems);
        Class<?> calls = nested(validator, "Calls");
        if (calls != null) sum(defs, prefix + "Calls", calls, problems);
        else if (defs.has(prefix + "Calls")) problems.add("Calls described but not generated");
        JsonNode state = d.get("x-joust").get("state").get("$ref");
        String stateKey = unref(state.asText());
        Class<?> stateClass = load(validator, stateKey);
        if (stateClass == null) problems.add("no state class " + stateKey);
        else record(defs.get(stateKey).get("anyOf").get(0), stateClass, problems);
        for (JsonNode o : d.get("x-joust").get("owned")) {
            Class<?> value = load(validator, o.get("type").asText());
            if (value == null) problems.add("no owned class " + o.get("type").asText());
            else record(defs.get(o.get("type").asText()).get("anyOf").get(0), value, problems);
            JsonNode holding = defs.get(unref(o.get("holding").get("$ref").asText())).get("anyOf").get(0);
            Class<?> h = nested(validator, holding.get("title").asText());
            if (h == null) problems.add("no Holding record " + holding.get("title").asText());
            else {
                record(holding, h, problems);
                Class<?> sealed = nested(validator, "Holding");
                int tag = sealed != null && sealed.isSealed() ? List.of(sealed.getPermittedSubclasses()).indexOf(h) : 0;
                if (holding.get("index").asInt() != tag) problems.add(h.getSimpleName() + ": index " + holding.get("index") + ", validator " + tag);
            }
        }
        if (!problems.isEmpty()) throw new AssertionError("description of " + contract.getSimpleName() + ":\n  " + String.join("\n  ", problems));
    }

    private static void sum(JsonNode defs, String key, Class<?> sealed, List<String> problems) {
        JsonNode def = defs.get(key);
        if (def == null || sealed == null) {
            problems.add("missing " + key);
            return;
        }
        Class<?>[] permitted = sealed.getPermittedSubclasses();
        JsonNode alts = def.get("anyOf");
        if (alts.size() != permitted.length) problems.add(key + ": " + alts.size() + " constructors, validator " + permitted.length);
        for (int i = 0; i < Math.min(alts.size(), permitted.length); i++) {
            JsonNode alt = alts.get(i);
            if (!alt.get("title").asText().equals(permitted[i].getSimpleName())) {
                problems.add(key + "[" + i + "]: " + alt.get("title").asText() + ", validator " + permitted[i].getSimpleName());
            }
            if (alt.get("index").asInt() != i) problems.add(key + "." + alt.get("title").asText() + ": index " + alt.get("index"));
            record(alt, permitted[i], problems);
        }
    }

    private static void record(JsonNode constructor, Class<?> record, List<String> problems) {
        RecordComponent[] comps = record.getRecordComponents();
        JsonNode fields = constructor.get("fields");
        if (comps == null) {
            problems.add(record.getSimpleName() + " is not a record");
            return;
        }
        var described = new ArrayList<String>();
        fields.forEach(f -> described.add(f.get("title").asText()));
        var actual = new ArrayList<String>();
        for (RecordComponent c : comps) actual.add(c.getName());
        if (!described.equals(actual)) problems.add(record.getSimpleName() + ": fields " + described + ", validator " + actual);
    }

    private static Class<?> nested(Class<?> outer, String simple) {
        for (Class<?> c : outer.getDeclaredClasses()) if (c.getSimpleName().equals(simple)) return c;
        return null;
    }

    /** A class by its canonical name (nested classes with dots). */
    private static Class<?> load(Class<?> near, String canonical) {
        String name = canonical;
        while (true) {
            try {
                return Class.forName(name, false, near.getClassLoader());
            } catch (ClassNotFoundException e) {
                int dot = name.lastIndexOf('.');
                if (dot < 0) return null;
                name = name.substring(0, dot) + "$" + name.substring(dot + 1);
            }
        }
    }

    private static String unref(String ref) {
        return ref.substring("#/definitions/".length()).replace("~1", "/").replace("~0", "~");
    }
}
