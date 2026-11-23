package org.julclang.state.serve;

import com.bloxbean.cardano.client.account.Account;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.state.runtime.CallAbort;
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
 * One served contract: its generated client class (driven by reflection, by the names its description gives) and its
 * description. Every call goes through the generated client, so it runs exactly what a Java caller runs. Transport
 * independent (ADR-0022 D16): the HTTP prepare service ({@code julc-state-server}) and the dev host both serve through it.
 * <p>
 * Failures: a request's mistake is a {@link BadRequest} or a {@link JsonValues.BadValue}; what the contract or the
 * runtime throws reaches the caller as it was thrown, the reflection wrapper removed: a {@code RuntimeException} as
 * itself, and an {@link Error} that is a {@link CallAbort} (a read the call lacks, a failed or unsupported read, a
 * host's cancellation) as itself, so its kind is never inferred from text (D9). Any other {@code Error} or checked
 * exception from the generated client is wrapped in a {@link JoustException} with it as the cause, as before.
 */
public final class ServedContract implements ContractCalls {

    private final String name;               // the contract's fully qualified class name (the description's preamble title)
    private final Class<?> client;
    private final JsonNode description;
    private final JsonNode x;
    private final ContractSpec<?> spec;
    private final JsonValues codec;

    private ServedContract(String name, Class<?> client, JsonNode description, ContractSpec<?> spec, JsonValues codec) {
        this.name = name;
        this.client = client;
        this.description = description;
        this.x = description.get("x-joust");
        this.spec = spec;
        this.codec = codec;
    }

    /** The contract of a generated {@code <Name>Client}. */
    public static ServedContract of(Class<?> client) {
        return of(client, JsonValues.TYPED);
    }

    /** Serve a generated client using the given record conversion policy. */
    public static ServedContract of(Class<?> client, JsonValues codec) {
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
            return new ServedContract(fq, client, d, spec, codec);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read " + resource, e);
        }
    }

    /** The contract's fully qualified class name (its description's preamble title): the name it is served under. */
    public String name() {
        return name;
    }

    /** The generated client class. */
    public Class<?> client() {
        return client;
    }

    /** The contract's simple name ({@code x-joust.contract}). */
    public String contract() {
        return x.get("contract").asText();
    }

    /** The contract's description (a copy). */
    public JsonNode description() {
        return description.deepCopy();
    }

    /** The description's {@code x-joust} extension (a copy). */
    public JsonNode extension() {
        return x.deepCopy();
    }

    /** Whether the contract has keyed types (ADR-0016): its creation is then a keyed call. */
    public boolean hasKeyedTypes() {
        for (JsonNode k : x.path("keyed")) if (k.isObject()) return true;
        return false;
    }

    /** The generated client's {@code SPEC}. */
    public ContractSpec<?> spec() {
        return spec;
    }

    /** The generated client's handle on {@code runtime}. */
    public StateContractHandle<?> handle(StateRuntime runtime) {
        try {
            return (StateContractHandle<?>) client.getMethod("contract", StateRuntime.class).invoke(null, runtime);
        } catch (InvocationTargetException e) {
            throw unwrapped(e, "contract");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The x-joust entry of a creation or action called {@code method} of {@code kind} (a copy). */
    public JsonNode meta(String kind, String method) {
        String list = kind.equals("init") || kind.equals("ownedInit") ? "creations" : "actions";
        for (JsonNode m : x.get(list)) {
            if (m.get("name").asText().equals(method) && m.get("kind").asText().equals(kind)) return m.deepCopy();
        }
        throw new BadRequest(name + " has no " + kind + " " + method);
    }

    /**
     * Runs the call through the generated client; returns the instance or object it creates, if any. {@code meta} is
     * the call's {@link #meta} entry; {@code kind} one of {@code init}, {@code action}, {@code destroy},
     * {@code ownedInit}, {@code ownedAction}, {@code ownedDestroy}. Usually run inside a {@code Compose}, which builds
     * the transaction.
     */
    public Object call(StateRuntime runtime, Account caller, String kind, JsonNode meta, String instance, String object,
                JsonNode args, BigInteger lovelace, JsonNode assets) {
        return call(runtime, caller, kind, meta, instance, object, args, lovelace, assets, null);
    }

    /** Dispatch through the generated quorum signature when the caller supplies its Account array. */
    public Object call(StateRuntime runtime, Account caller, String kind, JsonNode meta, String instance, String object,
                       JsonNode args, BigInteger lovelace, JsonNode assets, Account[] signers) {
        if (signers != null && !meta.path("policies").path("quorum").isObject())
            throw new BadRequest("expected a quorum method");
        String method = meta.get("name").asText();
        int nArgs = meta.get("args").size();
        boolean payable = meta.get("payable").asBoolean();
        boolean takesAssets = meta.get("takesAssets").asBoolean();
        if (!payable && lovelace.signum() != 0) throw new BadRequest(method + " is not payable");
        if (lovelace.signum() < 0) throw new BadRequest("a negative payment");
        if (!takesAssets && assets != null && assets.isArray() && !assets.isEmpty()) throw new BadRequest(method + " takes no native assets");
        try {
            switch (kind) {
                case "init" -> {
                    Method m = find(client, "deploy", 2 + nArgs + (payable ? 1 : 0) + (takesAssets ? 1 : 0), true);
                    var values = new ArrayList<Object>(List.of(runtime, caller));
                    values.addAll(arguments(m, 2, meta, args, lovelace, assets, payable, takesAssets));
                    Object made = m.invoke(null, values.toArray());
                    return client.getMethod("instanceId").invoke(made);
                }
                case "action", "destroy", "ownedInit", "ownedAction", "ownedDestroy" -> {
                    boolean ownedSpend = kind.equals("ownedAction") || kind.equals("ownedDestroy");
                    if (ownedSpend && (object == null || !object.matches("4f[0-9a-f]{56}")))
                        throw new BadRequest("an owned call needs an object id");
                    Object at = at(runtime, instance);
                    if (ownedSpend) {
                        String lookup = null;
                        for (JsonNode o : x.get("owned"))
                            if (o.get("type").asText().equals(meta.get("owned").asText())) lookup = o.get("lookup").asText();
                        at = client.getMethod(lookup, String.class).invoke(at, object);
                    }
                    Method m = find(at.getClass(), method, 1 + nArgs + (payable ? 1 : 0) + (takesAssets ? 1 : 0)
                            + (signers == null ? 0 : 1), false);
                    var values = new ArrayList<Object>(List.of(caller));
                    values.addAll(arguments(m, 1, meta, args, lovelace, assets, payable, takesAssets));
                    if (signers != null) {
                        if (!m.isVarArgs() || m.getParameterTypes()[m.getParameterCount() - 1] != Account[].class)
                            throw new BadRequest("expected generated Account... co-signers");
                        values.add(signers);
                    }
                    Object r = m.invoke(at, values.toArray());
                    return kind.equals("ownedInit") ? r.getClass().getMethod("objectId").invoke(r) : null;
                }
                default -> throw new BadRequest("unknown kind " + kind);
            }
        } catch (InvocationTargetException e) {
            throw unwrapped(e, method);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A view on an instance with its arguments, or the instance's state ({@code view == null}), as the Java value the
     * generated client returns: callable without any transport (ADR-0022 D15).
     */
    public Object view(StateRuntime runtime, String instance, String view, JsonNode args) {
        try {
            Object at = at(runtime, instance);
            if (view == null) return client.getMethod("state").invoke(at);
            JsonNode meta = null;
            for (JsonNode v : x.get("views")) if (v.get("name").asText().equals(view)) meta = v;
            if (meta == null) throw new BadRequest(name + " has no view " + view);
            if (args != null && !args.isNull() && !args.isObject()) throw new BadRequest("args must be an object");
            var names = new ArrayList<String>();
            meta.path("args").forEach(a -> names.add(a.get("name").asText()));
            if (args != null && args.isObject()) {
                args.fieldNames().forEachRemaining(n -> {
                    if (!names.contains(n)) throw new BadRequest("unknown argument " + n);
                });
            }
            Method m = find(client, view, names.size(), false);
            Type[] types = m.getGenericParameterTypes();
            Object[] values = new Object[names.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = codec.decode(args == null ? null : args.get(names.get(i)), types[i], names.get(i));
            }
            return m.invoke(at, values);
        } catch (InvocationTargetException e) {
            throw unwrapped(e, view);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** As {@link #view}, in the description's JSON encoding ({@code x-joust.encoding.json}). */
    public JsonNode viewJson(StateRuntime runtime, String instance, String view, JsonNode args) {
        return codec.encode(view(runtime, instance, view, args));
    }

    /**
     * What a reflective call's failure becomes (ADR-0022 D9, F2): the generated client's {@code RuntimeException}, or a
     * {@link CallAbort} {@code Error}, as it was thrown; anything else wrapped, with it as the cause, as before.
     */
    static RuntimeException unwrapped(InvocationTargetException e, String what) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException r) return r;
        if (cause instanceof Error err && err instanceof CallAbort) throw err;
        return new JoustException(what + " failed", cause);
    }

    private Object at(StateRuntime runtime, String instance) throws ReflectiveOperationException {
        if (instance == null || !instance.matches("[0-9a-f]{64}")) throw new BadRequest("an instance id is 32 bytes of hex");
        return client.getMethod("at", StateRuntime.class, String.class).invoke(null, runtime, instance);
    }

    private List<Object> arguments(Method m, int offset, JsonNode meta, JsonNode args, BigInteger lovelace,
                                          JsonNode assets, boolean payable, boolean takesAssets) {
        if (args == null || !args.isObject()) throw new BadRequest("args must be an object");
        var names = new ArrayList<String>();
        meta.get("args").forEach(a -> names.add(a.get("name").asText()));
        args.fieldNames().forEachRemaining(n -> {
            if (!names.contains(n)) throw new BadRequest("unknown argument " + n);
        });
        Type[] types = m.getGenericParameterTypes();
        var out = new ArrayList<Object>();
        for (int i = 0; i < names.size(); i++) out.add(codec.decode(args.get(names.get(i)), types[offset + i], names.get(i)));
        int next = offset + names.size();
        if (payable) out.add(lovelace);
        if (takesAssets) {
            Object offered = codec.decode(assets == null ? JsonValues.JSON.createArrayNode() : assets, types[next + (payable ? 1 : 0)], "assets");
            // The wire orders a policy's token names as canonical CBOR (shorter first); the runtime's values are in
            // ledger order (bytewise), so convert, as the validator does (#154 Codex final).
            out.add(offered instanceof org.julclang.ledger.Value v ? org.julclang.state.api.ValueAdapter.fromWire(v) : offered);
        }
        return out;
    }

    private static Method find(Class<?> c, String name, int params, boolean isStatic) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == params && Modifier.isStatic(m.getModifiers()) == isStatic) return m;
        }
        throw new BadRequest(c.getSimpleName() + " has no " + name + " with " + params + " parameters");
    }

    /** The deployment the page pins: policy, address, network, reference script. */
    public ObjectNode deployment(StateRuntime runtime) {
        return deploymentOf(handle(runtime));
    }

    /** The deployment for an already constructed client handle. */
    public ObjectNode deploymentOf(StateContractHandle<?> h) {
        ObjectNode d = JsonValues.JSON.createObjectNode();
        d.put("contract", name);
        d.put("policy", h.policyId());
        d.put("address", h.address());
        d.put("networkId", h.runtime().network().getNetworkId());
        var refs = d.putArray("referenceScripts");
        var ref = h.findReferenceScript();
        if (ref != null) refs.add(ref.getTxHash() + "#" + ref.getOutputIndex());
        return d;
    }
}
