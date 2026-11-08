package org.julclang.state.runtime.described;

import com.bloxbean.cardano.client.account.Account;
import com.fasterxml.jackson.databind.JsonNode;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.TxOutRef;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payout;
import org.julclang.state.runtime.Call;
import org.julclang.state.runtime.ContractSpec;
import org.julclang.state.runtime.Deploy;
import org.julclang.state.runtime.OwnedObject;
import org.julclang.state.runtime.StateContractHandle;
import org.julclang.state.runtime.StateInstance;
import org.julclang.state.runtime.StateRuntime;
import org.julclang.state.runtime.Transition;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * A contract known by its description (version 2, with call recipes), its compiled script and its transitions
 * (ADR-0015 D4): it runs call requests through the runtime's core ({@link StateContractHandle}, {@link StateInstance},
 * {@link OwnedObject}, {@code TxRunner}) with the state as Plutus data. What the generated client's
 * {@code Call.Builder} lambdas encode comes from the recipe; the method runs through the {@link TransitionFn}.
 * <p>
 * No contract classes and no reflection on them: the same class serves the prepare service and the Wasm runtime.
 * Supported: creations, actions and destroys, owned objects with {@code @Shared}, payable calls. Not yet (ADR-0015 M3):
 * MerkleMaps, native assets, {@code @Params}, batches, composition, moves, sends, mints, events.
 */
public final class DescribedContract {

    /** An unused argument (the target of a creation, a shared state not taken, a Ctx not declared). */
    static final PlutusData NONE = PlutusData.constr(0);

    private final String name;
    private final JsonNode description;
    private final JsonNode x;
    private final SchemaCodec codec;
    private final ContractSpec<PlutusData> spec;
    private final TransitionFn fn;

    public DescribedContract(JsonNode description, String scriptCborHex, TransitionFn fn) {
        this.description = description;
        this.x = description.get("x-joust");
        if (x == null || x.path("version").asInt() < 2) throw new IllegalArgumentException("a JOUST description of version 2 is needed (with call recipes)");
        this.name = description.path("preamble").path("title").asText();
        this.codec = new SchemaCodec(description);
        this.spec = ContractSpec.described(x.get("contract").asText(), scriptCborHex, x.get("minAda").asLong(),
                x.get("maxDatumBytes").asInt(), 3_600_000L, x.path("stakeSelf").asBoolean());
        this.fn = fn;
    }

    /** The contract's fully qualified name (the description's title). */
    public String name() {
        return name;
    }

    public JsonNode description() {
        return description;
    }

    public SchemaCodec codec() {
        return codec;
    }

    public StateContractHandle<PlutusData> handle(StateRuntime runtime) {
        if (x.has("params")) throw new IllegalArgumentException(name + " takes @Params: give them (handle(runtime, params))");
        return new StateContractHandle<>(runtime, spec);
    }

    /** The contract on a network with its deploy-time {@code @Params} (JSON, by the description's schema; ADR-0009 D1). */
    public StateContractHandle<PlutusData> handle(StateRuntime runtime, JsonNode params) {
        if (!x.has("params")) return new StateContractHandle<>(runtime, spec);
        if (params == null || params.isNull() || params.isMissingNode()) throw new IllegalArgumentException(name + " takes @Params: the call must give them");
        return new StateContractHandle<>(runtime, spec, DataFidelity.check(codec.encode(x.get("params"), params, "params"), "the params"));
    }

    /** Whether this contract takes {@code @Params}. */
    public boolean takesParams() {
        return x.has("params");
    }

    /** The x-joust entry of a creation or action called {@code method} of {@code kind}. */
    public JsonNode meta(String kind, String method) {
        String list = kind.equals("init") || kind.equals("ownedInit") ? "creations" : "actions";
        for (JsonNode m : x.get(list)) {
            if (m.get("name").asText().equals(method) && m.get("kind").asText().equals(kind)) return m;
        }
        throw new IllegalArgumentException(name + " has no " + kind + " " + method);
    }

    /**
     * Runs one call: {@code args} by name (JSON, the description's encoding), {@code lovelace} paid to a payable
     * method. Returns the instance or object a creation makes, else null.
     */
    public String call(StateRuntime runtime, Account caller, String kind, String method, String instance, String object,
                       JsonNode args, BigInteger lovelace) {
        return call(runtime, caller, kind, method, instance, object, args, lovelace, null);
    }

    /** As {@link #call(StateRuntime, Account, String, String, String, String, JsonNode, BigInteger)}, with the {@code @Params}. */
    public String call(StateRuntime runtime, Account caller, String kind, String method, String instance, String object,
                       JsonNode args, BigInteger lovelace, JsonNode params) {
        JsonNode meta = meta(kind, method);
        JsonNode recipe = meta.get("recipe");
        if (recipe == null) throw new IllegalArgumentException(method + ": no call recipe (rebuild with JOUST's processor)");
        if (meta.path("takesAssets").asBoolean()) throw new IllegalArgumentException(method + " takes native assets: not supported yet (ADR-0015 M3)");
        if (unsupported(meta)) throw new IllegalArgumentException(method + " needs maps, intents, moves, sends, mints or composition: not supported yet (ADR-0015 M3)");
        if (!meta.path("payable").asBoolean() && lovelace.signum() != 0) throw new IllegalArgumentException(method + " is not payable");
        if (lovelace.signum() < 0) throw new IllegalArgumentException("a negative payment");

        // The arguments, by the description's schemas: for the redeemer, and as the transition's args record.
        var byName = new java.util.LinkedHashMap<String, PlutusData>();
        var argList = new ArrayList<PlutusData>();
        if (args == null || !args.isObject()) throw new IllegalArgumentException("args must be an object");
        var names = new ArrayList<String>();
        meta.path("args").forEach(a -> names.add(a.get("name").asText()));
        args.fieldNames().forEachRemaining(n -> {
            if (!names.contains(n)) throw new IllegalArgumentException("unknown argument " + n);
        });
        for (JsonNode a : meta.path("args")) {
            String n = a.get("name").asText();
            PlutusData d = codec.encode(a.get("schema"), args.get(n), n);
            byName.put(n, d);
            argList.add(d);
        }
        var handle = handle(runtime, params);
        // @Params and @Reads lead the transition's arguments (the recipe's transitionArgs); reads are reference inputs.
        var reads = new ArrayList<org.julclang.state.runtime.ForeignReads.Read<PlutusData>>();
        var lead = new ArrayList<PlutusData>();
        if (recipe.path("params").asBoolean()) lead.add(codec.encode(x.get("params"), params, "params"));
        for (JsonNode rd : recipe.path("reads")) {
            var fi = (PlutusData.ConstrData) field(lead.isEmpty() ? codec.encode(x.get("params"), params, "params") : lead.get(0),
                    rd.get("index").asInt());
            var read = org.julclang.state.runtime.ForeignReads.read(runtime, new org.julclang.state.api.ForeignInstance(
                    ((PlutusData.BytesData) fi.fields().get(0)).value(), ((PlutusData.BytesData) fi.fields().get(1)).value()),
                    PlutusData.class);
            reads.add(read);
            lead.add(read.state());
        }
        lead.addAll(argList);
        PlutusData argsData = new PlutusData.ConstrData(0, lead);
        boolean usesCtx = meta.path("ctx").asBoolean();
        String transition = recipe.get("transition").asText();
        int alternative = meta.get("redeemer").get("index").asInt();
        JsonNode fields = recipe.get("redeemerFields");

        switch (kind) {
            case "init" -> {
                var d = new Deploy<PlutusData>(caller, lovelace, usesCtx,
                        ctx -> next(fn.apply(transition, NONE, NONE, argsData, ctxData(ctx, usesCtx)), method),
                        (seed, ctx) -> redeemer(alternative, fields, byName, ctxData(ctx, usesCtx), seed, null));
                return handle.deploy(d).instance().instanceId();
            }
            case "action", "destroy" -> {
                StateInstance<PlutusData> at = handle.at(requireInstance(instance));
                var b = kind.equals("destroy")
                        ? Call.<PlutusData>destroy(method, caller, burn(recipe))
                        : Call.<PlutusData>action(method, caller);
                rules(b, recipe);
                for (var rd : reads) b.referenceInputs(rd.utxo());
                b.usesCtx(usesCtx).received(lovelace)
                        .run((s, ctx) -> transition(fn.apply(transition, s, NONE, argsData, ctxData(ctx, usesCtx))))
                        .redeemer(ctx -> redeemer(alternative, fields, byName, ctxData(ctx, usesCtx), null, null));
                at.call(b.build());
                return null;
            }
            case "ownedInit" -> {
                StateInstance<PlutusData> at = handle.at(requireInstance(instance));
                boolean shared = recipe.path("shared").asBoolean();
                var create = new OwnedObject.Create<PlutusData>(method, caller, lovelace, usesCtx, shared,
                        (sh, ctx) -> next(fn.apply(transition, NONE, shared ? (PlutusData) sh : NONE, argsData, ctxData(ctx, usesCtx)), method),
                        (seed, inst, ctx) -> redeemer(alternative, fields, byName, ctxData(ctx, usesCtx), seed, inst));
                return OwnedObject.create(at, create, ownedCodec(meta.get("owned").asText())).objectId();
            }
            case "ownedAction", "ownedDestroy" -> {
                if (object == null || !object.matches("4f[0-9a-f]{56}")) throw new IllegalArgumentException("an owned call needs an object id");
                StateInstance<PlutusData> at = handle.at(requireInstance(instance));
                var obj = OwnedObject.at(at, object, ownedCodec(meta.get("owned").asText()));
                var b = kind.equals("ownedDestroy")
                        ? Call.<PlutusData>destroy(method, caller, burn(recipe))
                        : Call.<PlutusData>action(method, caller);
                rules(b, recipe);
                b.usesCtx(usesCtx).received(lovelace)
                        .redeemer(ctx -> redeemer(alternative, fields, byName, ctxData(ctx, usesCtx), null, null));
                if (recipe.path("shared").asBoolean()) {
                    b.runShared((o, sh, ctx) -> transition(fn.apply(transition, o, (PlutusData) sh, argsData, ctxData(ctx, usesCtx))));
                } else {
                    b.run((o, ctx) -> transition(fn.apply(transition, o, NONE, argsData, ctxData(ctx, usesCtx))));
                }
                obj.call(b.build());
                return null;
            }
            default -> throw new IllegalArgumentException("unknown kind " + kind);
        }
    }

    private static String requireInstance(String instance) {
        if (instance == null || !instance.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("an instance id is 32 bytes of hex");
        return instance;
    }

    /** {@code @SignedBy}, {@code @ValidAfter}, {@code @ValidBefore}: fields of the target, by index. */
    private static void rules(Call.Builder<PlutusData> b, JsonNode recipe) {
        for (JsonNode r : recipe.path("signers")) {
            int i = r.get("index").asInt();
            b.signedBy(r.get("field").asText(), s -> ((PlutusData.BytesData) field(s, i)).value());
        }
        for (JsonNode r : recipe.path("validAfter")) {
            int i = r.get("index").asInt();
            b.validAfter(r.get("field").asText(), s -> ((PlutusData.IntData) field(s, i)).value());
        }
        for (JsonNode r : recipe.path("validBefore")) {
            int i = r.get("index").asInt();
            b.validBefore(r.get("field").asText(), s -> ((PlutusData.IntData) field(s, i)).value());
        }
    }

    private static PlutusData field(PlutusData record, int index) {
        return ((PlutusData.ConstrData) record).fields().get(index);
    }

    private static PlutusData burn(JsonNode recipe) {
        JsonNode b = recipe.get("burn");
        if (b == null) throw new IllegalArgumentException("a destroy's recipe has no burn redeemer");
        return PlutusData.constr(b.get("index").asInt());
    }

    /** The redeemer: the recipe's fields in order. */
    private static PlutusData redeemer(int alternative, JsonNode fields, java.util.Map<String, PlutusData> args, PlutusData ctx,
                                       TxOutRef seed, byte[] instance) {
        var out = new ArrayList<PlutusData>();
        for (JsonNode f : fields) {
            String n = f.asText();
            switch (n) {
                case "seed" -> out.add(seed.toPlutusData());
                case "instance" -> out.add(PlutusData.bytes(instance));
                case "ctx" -> out.add(ctx);
                default -> {
                    PlutusData a = args.get(n);
                    if (a == null) throw new IllegalArgumentException("redeemer field " + n + " is not supported yet (ADR-0015 M3)");
                    out.add(a);
                }
            }
        }
        return DataFidelity.check(new PlutusData.ConstrData(alternative, out), "the redeemer");
    }

    /** The Ctx as Data, or NONE when the method takes none. */
    private static PlutusData ctxData(Ctx ctx, boolean usesCtx) {
        if (!usesCtx || ctx == null) return NONE;
        return PlutusDataAdapter.fromClientLib(PlutusDataAdapter.convert(ctx));
    }

    /** A step ({@code Constr 0 [Some next | None, payouts]}) as the runtime's transition. */
    static Transition<PlutusData> transition(PlutusData step) {
        var c = (PlutusData.ConstrData) DataFidelity.check(step, "the method's result");
        var opt = (PlutusData.ConstrData) c.fields().get(0);
        PlutusData next = opt.tag() == 0 ? opt.fields().get(0) : null;
        var items = ((PlutusData.ListData) c.fields().get(1)).items();
        JulcList<Payout> payouts = JulcList.empty();
        for (int i = items.size() - 1; i >= 0; i--) {
            payouts = payouts.prepend(PlutusDataAdapter.convert(PlutusDataAdapter.toClientLib(items.get(i)), Payout.class));
        }
        return next != null ? Transition.of(next, payouts) : Transition.end(payouts);
    }

    /** What a creation makes. */
    private static PlutusData next(PlutusData step, String method) {
        PlutusData n = transition(step).next();
        if (n == null) throw new IllegalStateException(method + ": a creation made nothing");
        return n;
    }

    /** An owned type's Holding: {@code Constr tag [instance, value]}. */
    private OwnedObject.Codec<PlutusData> ownedCodec(String type) {
        for (JsonNode o : x.path("owned")) {
            if (o.get("type").asText().equals(type)) {
                int tag = o.get("tag").asInt();
                return new OwnedObject.Codec<>(PlutusData.class, h -> field((PlutusData) h, 1),
                        (inst, v) -> PlutusData.constr(tag, PlutusData.bytes(inst), v), null);
            }
        }
        throw new IllegalArgumentException(name + " has no owned type " + type);
    }

    /** What this dispatcher cannot run yet (ADR-0015 M3): maps, assets, batches, composition, moves, sends, mints, events. */
    boolean unsupported(JsonNode meta) {
        JsonNode p = meta.path("policies");
        boolean cosigned = !p.path("quorum").isNull() && !p.path("quorum").isMissingNode() || !p.path("authorizedBy").isEmpty();
        JsonNode r = meta.path("returns");
        return meta.path("takesAssets").asBoolean() || p.path("batched").asBoolean() || p.path("into").asBoolean()
                || !p.path("admits").isNull() && !p.path("admits").isMissingNode() || p.path("receives").asBoolean()
                || r.path("sends").asBoolean() || r.path("mint").asBoolean() || r.path("deposits").asBoolean()
                || r.path("events").asBoolean() || cosigned || hasMaps(meta.path("recipe").path("target").asText());
    }

    /** The record {@code key} has a MerkleMap component (ADR-0002): its calls need proofs (M3). */
    private boolean hasMaps(String key) {
        if (key.isEmpty()) return false;
        JsonNode d = description.path("definitions").path(key);
        for (JsonNode alt : d.path("anyOf")) {
            for (JsonNode f : alt.path("fields")) {
                if (f.path("$ref").asText().endsWith("/org.julclang.state.api.MerkleMap")) return true;
            }
        }
        return false;
    }

    /** The current state of {@code instance}, as JSON by the description's state schema. */
    public JsonNode state(StateRuntime runtime, String instance) {
        return codec.decode(x.get("state"), handle(runtime, null).at(requireInstance(instance)).state());
    }

    /** Names of the creations and actions this dispatcher can run (no maps, assets, params, batches, …). */
    public List<String> supported() {
        var out = new ArrayList<String>();
        for (String list : List.of("creations", "actions")) {
            for (JsonNode m : x.path(list)) {
                if (m.has("recipe") && !unsupported(m)) {
                    out.add(m.get("kind").asText() + " " + m.get("name").asText());
                }
            }
        }
        return out;
    }
}
