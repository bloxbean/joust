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
import org.julclang.state.runtime.Batcher;
import org.julclang.state.runtime.IntentHandle;
import org.julclang.state.runtime.IntentCodec;
import org.julclang.state.runtime.Keys;
import org.julclang.state.runtime.ContractSpec;
import org.julclang.state.runtime.Deploy;
import org.julclang.state.runtime.OwnedObject;
import org.julclang.state.runtime.StateContractHandle;
import org.julclang.state.runtime.StateInstance;
import org.julclang.state.runtime.StateRuntime;
import org.julclang.state.runtime.Transition;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.julclang.state.runtime.MerkleSupport;

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
                x.get("maxDatumBytes").asInt(), 3_600_000L, x.path("stakeSelf").asBoolean(),
                mapFields(refKey(x.get("state"))));
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
        return call(runtime, caller, kind, method, instance, object, args, lovelace, params, null);
    }

    /**
     * As {@link #call(StateRuntime, Account, String, String, String, String, JsonNode, BigInteger, JsonNode)}, offering
     * native assets ({@code [[policy, [[name, quantity]]]]}, hex; ADR-0007) to a method that takes them.
     */
    public String call(StateRuntime runtime, Account caller, String kind, String method, String instance, String object,
                       JsonNode args, BigInteger lovelace, JsonNode params, JsonNode payAssets) {
        return call(runtime, caller, kind, method, instance, object, args, lovelace, params, payAssets, null);
    }

    /** As above; {@code into}: a move's destination instance (ADR-0012 D3), composed with its {@code @Admits} call. */
    public String call(StateRuntime runtime, Account caller, String kind, String method, String instance, String object,
                       JsonNode args, BigInteger lovelace, JsonNode params, JsonNode payAssets, String into) {
        return call(runtime, caller, kind, method, instance, object, args, lovelace, params, payAssets, into, List.of());
    }

    /**
     * As above; {@code cosigners}: the further keys a {@code @Quorum} call needs (ADR-0009 D2). For a wallet's prepare
     * they are address-only accounts: each co-signer's wallet signs the prepared transaction.
     */
    public String call(StateRuntime runtime, Account caller, String kind, String method, String instance, String object,
                       JsonNode args, BigInteger lovelace, JsonNode params, JsonNode payAssets, String into,
                       List<Account> cosigners) {
        JsonNode meta = meta(kind, method);
        JsonNode recipe = meta.get("recipe");
        if (recipe == null) throw new IllegalArgumentException(method + ": no call recipe (rebuild with JOUST's processor)");
        org.julclang.ledger.Value offered = assets(payAssets);
        if (!meta.path("takesAssets").asBoolean() && !org.julclang.state.api.Assets.isEmpty(offered)) {
            throw new IllegalArgumentException(method + " takes no native assets");
        }
        boolean assetAware = x.path("assetAware").asBoolean();
        boolean signedByOthers = meta.path("policies").path("quorum").isObject() || !meta.path("policies").path("authorizedBy").isEmpty();
        if (signedByOthers && !kind.equals("action") && !kind.equals("destroy")) {
            throw new IllegalArgumentException(method + ": @Quorum and @AuthorizedBy on " + kind + " calls: not supported yet (ADR-0015 M3)");
        }
        if (!signedByOthers && !cosigners.isEmpty()) throw new IllegalArgumentException(method + " takes no co-signers");
        if (unsupported(meta)) throw new IllegalArgumentException(method + " is batched (@Batched intents): not supported yet (ADR-0015 M3)");
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
        // The recipe's layout of the transition's arguments; Funds (an asset-aware method's) are filled in per run.
        var layoutArgs = new ArrayList<PlutusData>();
        int fundsAt = -1;
        int readAt = 0;
        int leadParams = recipe.path("params").asBoolean() ? 1 : 0;
        if (recipe.has("transitionArgs")) {
            for (JsonNode n : recipe.get("transitionArgs")) {
                String a = n.asText();
                if (a.equals("$params")) layoutArgs.add(lead.get(0));
                else if (a.startsWith("$read:")) layoutArgs.add(lead.get(leadParams + readAt++));
                else if (a.equals("$funds")) {
                    fundsAt = layoutArgs.size();
                    layoutArgs.add(NONE);
                } else if (a.equals("$into")) {
                    // A move reads its destination's state (@Into).
                    if (into == null || !into.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(method + " moves the object: give its destination (into)");
                    layoutArgs.add(handle.at(into).state());
                } else if (a.equals("$admitted")) {
                    // An @Admits action reads the arriving object as it is before the move (its id is the first argument).
                    String id = HexFormat.of().formatHex(((PlutusData.BytesData) argList.get(0)).value());
                    String admitted = recipe.path("admits").asText();
                    String simple = admitted.substring(admitted.lastIndexOf('.') + 1);
                    String typeKey = null;
                    for (JsonNode o : x.path("owned")) if (o.path("simple").asText().equals(simple)) typeKey = o.path("type").asText();
                    if (typeKey == null) throw new IllegalArgumentException(name + " has no owned type " + admitted);
                    layoutArgs.add(OwnedObject.at(handle.at(requireInstance(instance)), id, ownedCodec(typeKey)).value());
                } else layoutArgs.add(byName.get(a));
            }
        } else {
            layoutArgs.addAll(lead);
        }
        int fundsSlot = fundsAt;
        java.util.function.Function<org.julclang.state.api.Funds, PlutusData> argsWith = funds -> {
            var l = new ArrayList<>(layoutArgs);
            if (fundsSlot >= 0) l.set(fundsSlot, PlutusDataAdapter.fromClientLib(PlutusDataAdapter.convert(funds)));
            return new PlutusData.ConstrData(0, l);
        };
        PlutusData offeredData = offered.toPlutusData();
        if (into != null) byName.put("into", PlutusData.bytes(HexFormat.of().parseHex(into)));
        boolean usesCtx = meta.path("ctx").asBoolean();
        String transition = recipe.get("transition").asText();
        int alternative = meta.get("redeemer").get("index").asInt();
        JsonNode fields = recipe.get("redeemerFields");

        switch (kind) {
            case "init" -> {
                if (assetAware) {
                    var d = new Deploy<PlutusData>(caller, lovelace, usesCtx, null,
                            (seed, ctx) -> redeemer(alternative, fields, byName, ctxData(ctx, usesCtx), seed, null, offeredData),
                            offered, (ctx, funds) -> full(fn.apply(transition, NONE, NONE, argsWith.apply(funds), ctxData(ctx, usesCtx))));
                    return handle.deploy(d).instance().instanceId();
                }
                var d = new Deploy<PlutusData>(caller, lovelace, usesCtx,
                        ctx -> next(fn.apply(transition, NONE, NONE, argsWith.apply(null), ctxData(ctx, usesCtx)), method),
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
                if (!cosigners.isEmpty()) b.cosigners(cosigners.toArray(new Account[0]));
                authorize(method, meta, params, caller, cosigners, b);
                // @Quorum counts the transaction's required signers: the caller (who signs anyway) is one of them.
                if (meta.path("policies").path("quorum").isObject()) b.cosigners(caller);
                b.usesCtx(usesCtx).received(lovelace);
                if (hasField(fields, "events")) {
                    // The method's events go into the redeemer (ADR-0009 D5): built from its result.
                    b.redeemerWith((ctx, t) -> redeemer(alternative, fields, withEvents(byName, t), ctxData(ctx, usesCtx), null, null, offeredData));
                } else {
                    b.redeemer(ctx -> redeemer(alternative, fields, byName, ctxData(ctx, usesCtx), null, null, offeredData));
                }
                if (meta.path("policies").path("receives").asBoolean()) b.receives();   // takes deposits (ADR-0011 D3)
                if (assetAware) {
                    b.receivedAssets(offered).runAssets(false, (s, sh, ctx, funds) -> full(discover(transition, spec.layout(), s, NONE,
                            argsWith.apply(funds), ctxData(ctx, usesCtx))));
                    // A method that mints the contract's own tokens: the ByAction mint redeemer (ADR-0007 D4).
                    if (meta.path("returns").path("mint").asBoolean()) {
                        int byAction = lifecycleIndex("ByAction");
                        b.byAction(ref -> new PlutusData.ConstrData(byAction, List.of(ref.toPlutusData())));
                    }
                } else {
                    b.run((s, ctx) -> transition(discover(transition, spec.layout(), s, NONE, argsWith.apply(null), ctxData(ctx, usesCtx))));
                }
                at.call(b.build());
                return null;
            }
            case "ownedInit" -> {
                StateInstance<PlutusData> at = handle.at(requireInstance(instance));
                boolean shared = recipe.path("shared").asBoolean();
                if (assetAware) {
                    var create = new OwnedObject.Create<PlutusData>(method, caller, lovelace, usesCtx, shared, null,
                            (seed, inst, ctx) -> redeemer(alternative, fields, byName, ctxData(ctx, usesCtx), seed, inst, offeredData),
                            offered, (sh, ctx, funds) -> full(fn.apply(transition, NONE, shared ? (PlutusData) sh : NONE,
                                    argsWith.apply(funds), ctxData(ctx, usesCtx))));
                    return OwnedObject.create(at, create, ownedCodec(meta.get("owned").asText())).objectId();
                }
                var create = new OwnedObject.Create<PlutusData>(method, caller, lovelace, usesCtx, shared,
                        (sh, ctx) -> next(fn.apply(transition, NONE, shared ? (PlutusData) sh : NONE, argsWith.apply(null), ctxData(ctx, usesCtx)), method),
                        (seed, inst, ctx) -> redeemer(alternative, fields, byName, ctxData(ctx, usesCtx), seed, inst));
                return OwnedObject.create(at, create, ownedCodec(meta.get("owned").asText())).objectId();
            }
            case "ownedAction", "ownedDestroy" -> {
                if (object == null || !object.matches("4f[0-9a-f]{56}")) throw new IllegalArgumentException("an owned call needs an object id");
                StateInstance<PlutusData> at = handle.at(requireInstance(instance));
                var codec = ownedCodec(meta.get("owned").asText());
                var ownedLayout = MerkleSupport.dataLayout(codec.mapFields());
                var obj = OwnedObject.at(at, object, codec);
                var b = kind.equals("ownedDestroy")
                        ? Call.<PlutusData>destroy(method, caller, burn(recipe))
                        : Call.<PlutusData>action(method, caller);
                rules(b, recipe);
                if (meta.path("policies").path("into").asBoolean()) b.movesTo(into);   // ADR-0012 D3
                b.usesCtx(usesCtx).received(lovelace)
                        .redeemer(ctx -> redeemer(alternative, fields, byName, ctxData(ctx, usesCtx), null, null, offeredData));
                if (assetAware) {
                    boolean sharedObj = recipe.path("shared").asBoolean();
                    b.receivedAssets(offered).runAssets(sharedObj, (o, sh, ctx, funds) -> full(discover(transition, ownedLayout, o,
                            sharedObj ? (PlutusData) sh : NONE, argsWith.apply(funds), ctxData(ctx, usesCtx))));
                } else if (recipe.path("shared").asBoolean()) {
                    b.runShared((o, sh, ctx) -> transition(discover(transition, ownedLayout, o, (PlutusData) sh, argsWith.apply(null), ctxData(ctx, usesCtx))));
                } else {
                    b.run((o, ctx) -> transition(discover(transition, ownedLayout, o, NONE, argsWith.apply(null), ctxData(ctx, usesCtx))));
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
        return redeemer(alternative, fields, args, ctx, seed, instance, null);
    }

    private static PlutusData redeemer(int alternative, JsonNode fields, java.util.Map<String, PlutusData> args, PlutusData ctx,
                                       TxOutRef seed, byte[] instance, PlutusData assetsIn) {
        var out = new ArrayList<PlutusData>();
        for (JsonNode f : fields) {
            String n = f.asText();
            switch (n) {
                case "seed" -> out.add(seed.toPlutusData());
                case "instance" -> out.add(PlutusData.bytes(instance));
                case "ctx" -> out.add(ctx);
                case "assetsIn" -> {
                    if (assetsIn == null) throw new IllegalArgumentException("the redeemer's assetsIn without offered assets");
                    out.add(assetsIn);
                }
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

    private static boolean hasField(JsonNode fields, String name) {
        for (JsonNode f : fields) if (f.asText().equals(name)) return true;
        return false;
    }

    /** The arguments by name, with the transition's events (as Data) for the redeemer's {@code events} field. */
    private static java.util.Map<String, PlutusData> withEvents(java.util.Map<String, PlutusData> args, Transition<?> t) {
        var out = new java.util.LinkedHashMap<>(args);
        var items = new ArrayList<PlutusData>();
        for (Object e : t.events()) items.add((PlutusData) e);
        out.put("events", new PlutusData.ListData(items));
        return out;
    }

    /** The events a step carries after its other components (index {@code at}), as Data. */
    private static JulcList<PlutusData> eventsOf(PlutusData.ConstrData c, int at) {
        JulcList<PlutusData> out = JulcList.empty();
        if (c.fields().size() <= at) return out;
        var items = ((PlutusData.ListData) c.fields().get(at)).items();
        for (int i = items.size() - 1; i >= 0; i--) out = out.prepend(items.get(i));
        return out;
    }

    /** A step ({@code Constr 0 [Some next | None, payouts, events?]}) as the runtime's transition. */
    static Transition<PlutusData> transition(PlutusData step) {
        var c = (PlutusData.ConstrData) DataFidelity.check(step, "the method's result");
        var opt = (PlutusData.ConstrData) c.fields().get(0);
        PlutusData next = opt.tag() == 0 ? opt.fields().get(0) : null;
        var items = ((PlutusData.ListData) c.fields().get(1)).items();
        JulcList<Payout> payouts = JulcList.empty();
        for (int i = items.size() - 1; i >= 0; i--) {
            payouts = payouts.prepend(PlutusDataAdapter.convert(PlutusDataAdapter.toClientLib(items.get(i)), Payout.class));
        }
        Transition<PlutusData> t = next != null ? Transition.of(next, payouts) : Transition.end(payouts);
        return c.fields().size() > 2 ? t.withEvents(eventsOf(c, 2)) : t;
    }

    /** An asset-aware step ({@code Constr 0 [next?, payouts, sends, mint, accepted]}) as the runtime's full transition. */
    static Transition<PlutusData> full(PlutusData step) {
        var c = (PlutusData.ConstrData) DataFidelity.check(step, "the method's result");
        var base = transition(new PlutusData.ConstrData(0, List.of(c.fields().get(0), c.fields().get(1))));
        var items = ((PlutusData.ListData) c.fields().get(2)).items();
        JulcList<org.julclang.state.api.Send> sends = JulcList.empty();
        for (int i = items.size() - 1; i >= 0; i--) {
            sends = sends.prepend(PlutusDataAdapter.convert(PlutusDataAdapter.toClientLib(items.get(i)), org.julclang.state.api.Send.class));
        }
        var mint = PlutusDataAdapter.convert(PlutusDataAdapter.toClientLib(c.fields().get(3)), org.julclang.ledger.Value.class);
        var accepted = PlutusDataAdapter.convert(PlutusDataAdapter.toClientLib(c.fields().get(4)), org.julclang.ledger.Value.class);
        JulcList<org.julclang.state.api.Deposit> deposits = JulcList.empty();
        if (c.fields().size() > 5) {
            var ds = ((PlutusData.ListData) c.fields().get(5)).items();
            for (int i = ds.size() - 1; i >= 0; i--) {
                deposits = deposits.prepend(PlutusDataAdapter.convert(PlutusDataAdapter.toClientLib(ds.get(i)), org.julclang.state.api.Deposit.class));
            }
        }
        return Transition.full(base.next(), base.payouts(), sends, mint, accepted).withDeposits(deposits).withEvents(eventsOf(c, 6));
    }

    /** Offered native assets: {@code [[policy, [[name, quantity]]]]} (hex), as the JS client sends them. */
    static org.julclang.ledger.Value assets(JsonNode json) {
        var v = org.julclang.state.api.Assets.none();
        if (json == null || json.isNull() || json.isMissingNode()) return v;
        for (JsonNode p : json) {
            byte[] policy = HexFormat.of().parseHex(p.get(0).asText());
            for (JsonNode a : p.get(1)) {
                v = org.julclang.state.api.Assets.add(v, org.julclang.state.api.Assets.of(policy, HexFormat.of().parseHex(a.get(0).asText()),
                        new BigInteger(a.get(1).asText())));
            }
        }
        return v;
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
                        (inst, v) -> PlutusData.constr(tag, PlutusData.bytes(inst), v), null, mapFields(type));
            }
        }
        throw new IllegalArgumentException(name + " has no owned type " + type);
    }

    /** What this dispatcher cannot run yet (ADR-0015 M3): batches, composition and deposits, moves, mints, events, co-signers. */
    boolean unsupported(JsonNode meta) {
        return false;   // a @Batched action that is not batch-only may also be called directly
    }

    /**
     * {@code @AuthorizedBy} (ADR-0009 D3) with a key credential: the key must sign, as the caller or a co-signer (it is
     * made a required signer). A script credential (a governor's zero withdrawal) is not supported here yet.
     */
    private static void authorize(String method, JsonNode meta, JsonNode params, Account caller, List<Account> cosigners,
                                  Call.Builder<PlutusData> b) {
        for (JsonNode path : meta.path("policies").path("authorizedBy")) {
            String at = path.asText();
            if (!at.startsWith("params.")) throw new IllegalArgumentException(method + ": @AuthorizedBy(\"" + at + "\"): only @Params credentials are supported here yet (ADR-0015 M3)");
            JsonNode cred = params == null ? null : params.get(at.substring("params.".length()));
            if (cred == null) throw new IllegalArgumentException(method + ": no " + at + " in the call's params");
            if (!"PubKeyCredential".equals(cred.path("$variant").asText())) {
                throw new IllegalArgumentException(method + ": " + at + " is a script credential: a governor's approval is not supported here yet (ADR-0015 M3)");
            }
            String key = cred.path("hash").asText();
            Account signer = null;
            if (HexFormat.of().formatHex(Keys.pkh(caller)).equals(key)) signer = caller;
            for (Account a : cosigners) if (HexFormat.of().formatHex(Keys.pkh(a)).equals(key)) signer = a;
            if (signer == null) throw new IllegalArgumentException(method + " needs the signature of " + at + " (" + key + "): the caller or a co-signer");
            if (signer == caller) b.cosigners(caller);   // a co-signer is a required signer already
        }
    }

    /** The index of the generated validator's Lifecycle constructor {@code title} (CreateInstance, BurnInstance, ByAction…). */
    private int lifecycleIndex(String title) {
        var it = description.path("definitions").fields();
        while (it.hasNext()) {
            var e = it.next();
            if (!e.getKey().endsWith(".Lifecycle")) continue;
            for (JsonNode alt : e.getValue().path("anyOf")) {
                if (alt.path("title").asText().equals(title)) return alt.path("index").asInt();
            }
        }
        throw new IllegalArgumentException(name + " has no " + title + " redeemer");
    }

    private static String refKey(JsonNode schema) {
        String ref = schema == null ? "" : schema.path("$ref").asText("");
        return ref.startsWith("#/definitions/") ? ref.substring("#/definitions/".length()) : ref;
    }

    /** The indexes of the record {@code key}'s MerkleMap components (ADR-0002), from the description's schema. */
    private List<Integer> mapFields(String key) {
        var out = new ArrayList<Integer>();
        if (key == null || key.isEmpty()) return out;
        JsonNode alts = description.path("definitions").path(key).path("anyOf");
        if (alts.size() != 1) return out;
        JsonNode fs = alts.get(0).path("fields");
        for (int i = 0; i < fs.size(); i++) {
            if (fs.get(i).path("$ref").asText().endsWith("/org.julclang.state.api.MerkleMap")) out.add(i);
        }
        return out;
    }

    /**
     * Runs a transition with MerkleMap read discovery (ADR-0015 D3): each key a run reports ({@code joust:read}) is read
     * through the call's bound ReadSource (so the runtime records it and proves it) and given to the next run in the
     * map's reads, until a run reports nothing new. Starts from the reads the target already carries (the replay check
     * opens the maps with exactly the witnessed reads).
     */
    private PlutusData discover(String transition, MerkleSupport.Layout<PlutusData> layout, PlutusData target,
                                PlutusData shared, PlutusData args, PlutusData ctx) {
        if (!layout.hasMaps() || target == NONE) {
            var r = fn.run(transition, target, shared, args, ctx);
            if (r.step() == null) throw new org.julclang.state.api.RequireException(r.refusal());
            return r.step();
        }
        var known = new java.util.LinkedHashMap<String, List<org.julclang.state.api.MapEntry>>();
        for (int idx : layout.maps()) {
            var m = layout.map(target, idx);
            var list = known.computeIfAbsent(HexFormat.of().formatHex(m.root()), k -> new ArrayList<>());
            for (var e : m.reads()) list.add(e);
        }
        for (int round = 1; round <= MAX_READ_ROUNDS; round++) {
            var reads = new ArrayList<JulcList<org.julclang.state.api.MapEntry>>();
            for (int idx : layout.maps()) {
                var list = known.get(HexFormat.of().formatHex(layout.map(target, idx).root()));
                JulcList<org.julclang.state.api.MapEntry> l = JulcList.empty();
                for (int i = list.size() - 1; i >= 0; i--) l = l.prepend(list.get(i));
                reads.add(l);
            }
            var r = fn.run(transition, layout.opened(target, reads), shared, args, ctx);
            boolean fresh = false;
            for (String t : r.traces()) {
                if (!t.startsWith("joust:read ")) continue;
                String[] p = t.split(" ");
                String root = p[1];
                String key = p.length > 2 ? p[2] : "";
                var list = known.computeIfAbsent(root, k -> new ArrayList<>());
                if (list.stream().anyMatch(e -> HexFormat.of().formatHex(e.key()).equals(key))) continue;
                list.add(org.julclang.state.api.MerkleMaps.read(HexFormat.of().parseHex(root), HexFormat.of().parseHex(key)));
                fresh = true;
            }
            if (!fresh) {
                if (r.step() == null) throw new org.julclang.state.api.RequireException(r.refusal());
                return r.step();
            }
        }
        throw new org.julclang.state.api.RequireException(transition + ": more than " + MAX_READ_ROUNDS
                + " rounds of MerkleMap reads (ADR-0015 D3)");
    }

    /** The round limit of read discovery (ADR-0015 D3). */
    // ------------------------------------------------------------------ intents (ADR-0004; ADR-0015 M3)

    /** The intent entry of the batched action {@code method}: its IntentCall constructor, arguments and recipe. */
    JsonNode intentMeta(String method) {
        for (JsonNode i : x.path("intents").path("calls")) if (i.path("name").asText().equals(method)) return i;
        throw new IllegalArgumentException(name + " has no @Batched action " + method);
    }

    /**
     * The contract's intents over its description: datums, redeemers and the batch fold's steps, which run the batched
     * actions as transition programs. {@code params} are the deployment's (a batched action may read them).
     */
    public IntentCodec<PlutusData> intents(JsonNode params) {
        JsonNode in = x.get("intents");
        if (in == null) throw new IllegalArgumentException(name + " has no @Batched actions");
        if (x.path("assetAware").asBoolean()) throw new IllegalArgumentException(name + ": batches of an asset-aware contract: not supported yet (ADR-0015 M3)");
        if (spec.layout().hasMaps()) throw new IllegalArgumentException(name + ": batches over MerkleMaps: not supported yet (ADR-0015 M3)");
        PlutusData paramsData = x.has("params") && params != null ? codec.encode(x.get("params"), params, "params") : null;
        int create = in.path("create").path("index").asInt();
        int batch = in.path("batch").path("index").asInt();
        int inBatch = in.path("inBatch").path("index").asInt();
        int cancel = in.path("cancel").path("index").asInt();
        int burn = in.path("burn").path("index").asInt();
        return new IntentCodec<>() {
            @Override public Class<?> intentClass() { return PlutusData.class; }

            @Override public IntentData read(Object o) {
                var f = ((PlutusData.ConstrData) o).fields();
                var refund = PlutusDataAdapter.convert(PlutusDataAdapter.toClientLib(f.get(2)), org.julclang.ledger.Address.class);
                return new IntentData(((PlutusData.BytesData) f.get(0)).value(), ((PlutusData.BytesData) f.get(1)).value(), refund,
                        ((PlutusData.IntData) f.get(3)).value(), ((PlutusData.IntData) f.get(4)).value(), f.get(5));
            }

            @Override public Object intent(byte[] instance, byte[] owner, org.julclang.ledger.Address refund, BigInteger deadline,
                                           BigInteger received, Object call) {
                return new PlutusData.ConstrData(0, List.of(PlutusData.bytes(instance), PlutusData.bytes(owner),
                        PlutusDataAdapter.fromClientLib(PlutusDataAdapter.convert(refund)), PlutusData.integer(deadline),
                        PlutusData.integer(received), (PlutusData) call));
            }

            @Override public Object create(org.julclang.ledger.TxOutRef seed, Object intent) {
                return new PlutusData.ConstrData(create, List.of(PlutusDataAdapter.fromClientLib(PlutusDataAdapter.convert(seed)), (PlutusData) intent));
            }

            @Override public Object batch(BigInteger from, BigInteger to) {
                return new PlutusData.ConstrData(batch, List.of(PlutusData.integer(from), PlutusData.integer(to)));
            }

            @Override public Object inBatch() { return new PlutusData.ConstrData(inBatch, List.of()); }

            @Override public Object cancel() { return new PlutusData.ConstrData(cancel, List.of()); }

            @Override public Object burn() { return new PlutusData.ConstrData(burn, List.of()); }

            @Override public Object refundTag(org.julclang.ledger.TxOutRef ref) {
                return new PlutusData.ConstrData(0, List.of(PlutusDataAdapter.fromClientLib(PlutusDataAdapter.convert(ref))));
            }

            @Override public Transition<PlutusData> step(PlutusData state, Object call, Ctx ctx) {
                var c = (PlutusData.ConstrData) call;
                JsonNode meta = null;
                for (JsonNode i : in.path("calls")) if (i.path("call").path("index").asInt() == c.tag()) meta = i;
                if (meta == null) throw new IllegalArgumentException("not an intent call of " + name + ": constructor " + c.tag());
                var byName = new java.util.HashMap<String, PlutusData>();
                int k = 0;
                for (JsonNode a : meta.path("args")) byName.put(a.get("name").asText(), c.fields().get(k++));
                var layout = new ArrayList<PlutusData>();
                for (JsonNode n : meta.path("recipe").path("transitionArgs")) {
                    String a = n.asText();
                    if (a.equals("$params")) layout.add(paramsData);
                    else if (a.startsWith("$")) throw new IllegalArgumentException(meta.path("name").asText() + ": " + a + " in a batched action: not supported yet (ADR-0015 M3)");
                    else layout.add(byName.get(a));
                }
                var r = fn.run(meta.path("recipe").path("transition").asText(), state, NONE,
                        new PlutusData.ConstrData(0, layout), ctxData(ctx, true));
                if (r.step() == null) throw new org.julclang.state.api.RequireException(r.refusal());
                return transition(r.step());
            }
        };
    }

    /**
     * Locks an intent to call the batched action {@code method} on {@code instance} (ADR-0004 D2): {@code received}
     * goes to the contract when a batch applies it, before {@code deadlineMs}. Returns the intent id.
     */
    public String intent(StateRuntime runtime, Account owner, String method, String instance, JsonNode args,
                         BigInteger received, JsonNode params, long deadlineMs) {
        JsonNode meta = intentMeta(method);
        if (!meta.path("payable").asBoolean() && received.signum() != 0) throw new IllegalArgumentException(method + " is not payable");
        if (args == null || !args.isObject()) throw new IllegalArgumentException("args must be an object");
        var names = new ArrayList<String>();
        meta.path("args").forEach(a -> names.add(a.get("name").asText()));
        args.fieldNames().forEachRemaining(n -> {
            if (!names.contains(n)) throw new IllegalArgumentException("unknown argument " + n);
        });
        var fields = new ArrayList<PlutusData>();
        for (JsonNode a : meta.path("args")) {
            String n = a.get("name").asText();
            fields.add(codec.encode(a.get("schema"), args.get(n), n));
        }
        var call = new PlutusData.ConstrData(meta.path("call").path("index").asInt(), fields);
        var at = handle(runtime, params).at(requireInstance(instance));
        return IntentHandle.create(at, intents(params), owner, call, received, IntentHandle.DEFAULT_DEPOSIT,
                java.time.Instant.ofEpochMilli(deadlineMs)).intentId();
    }

    /** Withdraws a pending intent (its owner signs): burns its token and returns what it holds. */
    public String cancelIntent(StateRuntime runtime, Account owner, String instance, String intentId, JsonNode params) {
        if (intentId == null || !intentId.matches("49[0-9a-f]{56}")) throw new IllegalArgumentException("an intent id is 0x49 and 28 bytes, in hex");
        var at = handle(runtime, params).at(requireInstance(instance));
        return IntentHandle.at(at, intents(params), intentId).cancel(owner);
    }

    /** Applies up to {@code max} pending intents of {@code instance} in one transaction, paid by {@code batcher}. */
    public Batcher.BatchResult<PlutusData> batch(StateRuntime runtime, Account batcher, String instance, int max, JsonNode params) {
        var at = handle(runtime, params).at(requireInstance(instance));
        return new Batcher<>(at, intents(params)).run(batcher, max);
    }

    /** Pending intents of {@code instance}: id, owner, deadline, received and the call, as JSON. */
    public JsonNode pendingIntents(StateRuntime runtime, String instance, JsonNode params) {
        var at = handle(runtime, params).at(requireInstance(instance));
        var out = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (var p : new Batcher<>(at, intents(params)).pending()) {
            var o = out.addObject();
            o.put("intent", p.intentId());
            o.put("ref", HexFormat.of().formatHex(p.ref().txId().hash()) + "#" + p.ref().index());
            o.put("owner", HexFormat.of().formatHex(p.data().owner()));
            o.put("deadline", p.data().deadline().toString());
            o.put("received", p.data().received().toString());
            o.put("lovelace", p.lovelace().toString());
        }
        return out;
    }

    static final int MAX_READ_ROUNDS = 4;

    /** The current state of {@code instance}, as JSON by the description's state schema. */
    public JsonNode state(StateRuntime runtime, String instance) {
        return state(runtime, instance, null);
    }

    /** The current state of {@code instance} of a contract with {@code @Params} (they fix its script and address). */
    public JsonNode state(StateRuntime runtime, String instance, JsonNode params) {
        return codec.decode(x.get("state"), handle(runtime, params).at(requireInstance(instance)).state());
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
