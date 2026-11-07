package org.julclang.state.server;

import com.bloxbean.cardano.client.account.Account;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.state.runtime.ContractSpec;
import org.julclang.state.runtime.JoustException;
import org.julclang.state.runtime.StateContractHandle;
import org.julclang.state.runtime.StateRuntime;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * One contract the service serves: its generated client class (driven by reflection, by the names its description
 * gives) and its description. Every call goes through the generated client, so it runs exactly what a Java caller runs.
 */
final class ServedContract {

    final String name;               // the contract's fully qualified class name (the description's preamble title)
    final Class<?> client;
    final JsonNode description;
    final JsonNode x;
    final ContractSpec<?> spec;

    private ServedContract(String name, Class<?> client, JsonNode description, ContractSpec<?> spec) {
        this.name = name;
        this.client = client;
        this.description = description;
        this.x = description.get("x-joust");
        this.spec = spec;
    }

    /** The contract of a generated {@code <Name>Client}. */
    static ServedContract of(Class<?> client) {
        ContractSpec<?> spec;
        try {
            spec = (ContractSpec<?>) client.getField("SPEC").get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(client.getName() + " is not a generated JOUST client (no SPEC)");
        }
        String fq = client.getPackageName() + "." + spec.name();
        String resource = "META-INF/joust/" + fq + ".json";
        try (InputStream in = client.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalArgumentException("no description " + resource + " (rebuild with the JOUST processor)");
            JsonNode d = JsonValues.JSON.readTree(in);
            if (d.get("x-joust").has("params")) {
                throw new IllegalArgumentException(spec.name() + " takes @Params: not served yet (ADR-0013 phase 3)");
            }
            return new ServedContract(fq, client, d, spec);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read " + resource, e);
        }
    }

    StateContractHandle<?> handle(StateRuntime runtime) {
        try {
            return (StateContractHandle<?>) client.getMethod("contract", StateRuntime.class).invoke(null, runtime);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The x-joust entry of a creation or action called {@code method} of {@code kind}. */
    JsonNode meta(String kind, String method) {
        String list = kind.equals("init") || kind.equals("ownedInit") ? "creations" : "actions";
        for (JsonNode m : x.get(list)) {
            if (m.get("name").asText().equals(method) && m.get("kind").asText().equals(kind)) return m;
        }
        throw new Request.Bad(name + " has no " + kind + " " + method);
    }

    /** Runs the call through the generated client; returns the instance or object it creates, if any. */
    Object call(StateRuntime runtime, Account caller, String kind, JsonNode meta, String instance, String object,
                JsonNode args, BigInteger lovelace, JsonNode assets) {
        String method = meta.get("name").asText();
        int nArgs = meta.get("args").size();
        boolean payable = meta.get("payable").asBoolean();
        boolean takesAssets = meta.get("takesAssets").asBoolean();
        if (!payable && lovelace.signum() != 0) throw new Request.Bad(method + " is not payable");
        if (lovelace.signum() < 0) throw new Request.Bad("a negative payment");
        if (!takesAssets && assets != null && assets.isArray() && !assets.isEmpty()) throw new Request.Bad(method + " takes no native assets");
        try {
            switch (kind) {
                case "init" -> {
                    Method m = find(client, "deploy", 2 + nArgs + (payable ? 1 : 0) + (takesAssets ? 1 : 0), true);
                    var values = new ArrayList<Object>(List.of(runtime, caller));
                    values.addAll(arguments(m, 2, meta, args, lovelace, assets, payable, takesAssets));
                    Object made = m.invoke(null, values.toArray());
                    return client.getMethod("instanceId").invoke(made);
                }
                case "action", "destroy", "ownedInit" -> {
                    Object at = at(runtime, instance);
                    Method m = find(client, method, 1 + nArgs + (payable ? 1 : 0) + (takesAssets ? 1 : 0), false);
                    var values = new ArrayList<Object>(List.of(caller));
                    values.addAll(arguments(m, 1, meta, args, lovelace, assets, payable, takesAssets));
                    Object r = m.invoke(at, values.toArray());
                    return kind.equals("ownedInit") ? r.getClass().getMethod("objectId").invoke(r) : null;
                }
                case "ownedAction", "ownedDestroy" -> {
                    if (object == null || !object.matches("4f[0-9a-f]{56}")) throw new Request.Bad("an owned call needs an object id");
                    String lookup = null;
                    for (JsonNode o : x.get("owned")) if (o.get("type").asText().equals(meta.get("owned").asText())) lookup = o.get("lookup").asText();
                    Object at = at(runtime, instance);
                    Object obj = client.getMethod(lookup, String.class).invoke(at, object);
                    Method m = find(obj.getClass(), method, 1 + nArgs + (payable ? 1 : 0) + (takesAssets ? 1 : 0), false);
                    var values = new ArrayList<Object>(List.of(caller));
                    values.addAll(arguments(m, 1, meta, args, lovelace, assets, payable, takesAssets));
                    m.invoke(obj, values.toArray());
                    return null;
                }
                default -> throw new Request.Bad("unknown kind " + kind);
            }
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw new JoustException(method + " failed", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A view on an instance with its arguments, or the instance's state ({@code view == null}). */
    Object view(StateRuntime runtime, String instance, String view, JsonNode args) {
        try {
            Object at = at(runtime, instance);
            if (view == null) return client.getMethod("state").invoke(at);
            JsonNode meta = null;
            for (JsonNode v : x.get("views")) if (v.get("name").asText().equals(view)) meta = v;
            if (meta == null) throw new Request.Bad(name + " has no view " + view);
            if (args != null && !args.isNull() && !args.isObject()) throw new Request.Bad("args must be an object");
            var names = new ArrayList<String>();
            meta.path("args").forEach(a -> names.add(a.get("name").asText()));
            if (args != null && args.isObject()) {
                args.fieldNames().forEachRemaining(n -> {
                    if (!names.contains(n)) throw new Request.Bad("unknown argument " + n);
                });
            }
            Method m = find(client, view, names.size(), false);
            Type[] types = m.getGenericParameterTypes();
            Object[] values = new Object[names.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = JsonValues.fromJson(args == null ? null : args.get(names.get(i)), types[i], names.get(i));
            }
            return m.invoke(at, values);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw new JoustException(view + " failed", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object at(StateRuntime runtime, String instance) throws ReflectiveOperationException {
        if (instance == null || !instance.matches("[0-9a-f]{64}")) throw new Request.Bad("an instance id is 32 bytes of hex");
        return client.getMethod("at", StateRuntime.class, String.class).invoke(null, runtime, instance);
    }

    private static List<Object> arguments(Method m, int offset, JsonNode meta, JsonNode args, BigInteger lovelace,
                                          JsonNode assets, boolean payable, boolean takesAssets) {
        if (args == null || !args.isObject()) throw new Request.Bad("args must be an object");
        var names = new ArrayList<String>();
        meta.get("args").forEach(a -> names.add(a.get("name").asText()));
        args.fieldNames().forEachRemaining(n -> {
            if (!names.contains(n)) throw new Request.Bad("unknown argument " + n);
        });
        Type[] types = m.getGenericParameterTypes();
        var out = new ArrayList<Object>();
        for (int i = 0; i < names.size(); i++) out.add(JsonValues.fromJson(args.get(names.get(i)), types[offset + i], names.get(i)));
        int next = offset + names.size();
        if (payable) out.add(lovelace);
        if (takesAssets) out.add(JsonValues.fromJson(assets == null ? JsonValues.JSON.createArrayNode() : assets, types[next + (payable ? 1 : 0)], "assets"));
        return out;
    }

    private static Method find(Class<?> c, String name, int params, boolean isStatic) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == params && Modifier.isStatic(m.getModifiers()) == isStatic) return m;
        }
        throw new Request.Bad(c.getSimpleName() + " has no " + name + " with " + params + " parameters");
    }

    /** The deployment the page pins: policy, address, network, reference script. */
    ObjectNode deployment(StateRuntime runtime) {
        var h = handle(runtime);
        ObjectNode d = JsonValues.JSON.createObjectNode();
        d.put("contract", name);
        d.put("policy", h.policyId());
        d.put("address", h.address());
        d.put("networkId", runtime.network().getNetworkId());
        var refs = d.putArray("referenceScripts");
        var ref = h.findReferenceScript();
        if (ref != null) refs.add(ref.getTxHash() + "#" + ref.getOutputIndex());
        return d;
    }
}
