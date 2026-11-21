package org.julclang.state.host;

import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.fasterxml.jackson.databind.node.NullNode;
import com.bloxbean.cardano.client.backend.api.*;
import com.bloxbean.cardano.client.common.model.Network;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.clientlib.eval.*;
import org.julclang.state.runtime.*;
import org.julclang.state.serve.JsonValues;
import org.julclang.vm.JulcVmProvider;

import java.math.BigInteger;
import java.util.*;
import static org.julclang.state.host.Wire.*;

/** One verified project, clock and reservation table (D1/D3/D4/D8). */
final class Session implements Transport.Handler {
    private record Limits(long deadline, long reads, long bytes, long ttl, long margin, long ahead) {}
    private record Clock(long zeroSlot, long zeroMs, long length) {
        static Clock parse(JsonNode n) {
            fields(n, "zeroSlot", "zeroTimeMs", "slotLengthMs");
            return new Clock(number(n, "zeroSlot", 0), number(n, "zeroTimeMs", 0), number(n, "slotLengthMs", 1));
        }
    }
    private final String fingerprint;
    private final java.util.concurrent.Callable<Contracts> catalog;
    private final java.util.function.Supplier<JsonNode> versions;
    private final boolean namedViews;
    private final ThreadLocal<Reads> reading = new ThreadLocal<>();
    private final ThreadLocal<TransactionEvaluator> evaluating = new ThreadLocal<>();
    private final BackendService backend = Reads.backend(reading::get);
    private Limits limits;
    private Network network;
    private Clock clock;
    private Contracts contracts;
    private Reservations reservations;
    private StateRuntime runtime;
    private final Listings listings = new Listings();
    private long highestNow = -1;
    private boolean shutdown;

    Session(Launch launch) {
        this(launch.fingerprint(), () -> Contracts.load(launch, Session.class.getClassLoader()), Session::jvmVersions, true);
    }
    Session(String fingerprint, java.util.concurrent.Callable<Contracts> catalog,
            java.util.function.Supplier<JsonNode> versions, boolean namedViews) {
        this.fingerprint = fingerprint;
        this.catalog = catalog;
        this.versions = versions;
        this.namedViews = namedViews;
    }
    public long deadlineMs() { return limits == null ? 60_000 : limits.deadline; }

    public Transport.Reply execute(Call call, JsonNode p) throws Exception {
        return execute(call.method, params -> call.reverse("chain/read", params), call::whileLive, p);
    }

    Transport.Reply execute(String method, Reads.Peer peer, java.util.function.Consumer<Runnable> whileLive,
                            JsonNode p) throws Exception {
        if (shutdown) throw bad("session has shut down");
        if (method.equals("initialize")) {
            if (contracts != null) throw bad("session already initialized");
            return initialize(p);
        }
        if (contracts == null) throw bad("initialize first");
        return switch (method) {
            case "describe" -> {
                fields(p, "contract");
                yield new Transport.Reply(contracts.get(str(p, "contract")).describe());
            }
            case "prepare", "publish", "deployment", "prerequisites", "view" -> withReads(method, peer, whileLive, p);
            case "instances", "objects" -> p.has("listing") ? listings.resume(method, p) : withReads(method, peer, whileLive, p);
            case "decode" -> {
                fields(p, "contract", "datum");
                var datums = new ContractDatums(contracts.get(str(p, "contract")));
                try { yield new Transport.Reply(datums.decode(str(p, "datum"), null)); }
                catch (IllegalArgumentException e) { throw bad("invalid or unrecognized contract datum"); }
            }
            case "registered" -> reservations.registered(p);
            case "restore" -> reservations.restore(p);
            case "settled" -> reservations.settled(p, whileLive);
            case "reset" -> reset(p);
            case "shutdown" -> {
                if (!p.isMissingNode()) fields(p);
                yield new Transport.Reply(obj(), () -> shutdown = true);
            }
            default -> throw HostError.bad("method", "unknown method " + method);
        };
    }

    private Transport.Reply initialize(JsonNode p) throws Exception {
        fields(p, "protocol", "network", "clock", "time", "limits", "build", "restoring");
        boolean v1 = false;
        if (!p.path("protocol").isArray()) throw bad("expected a list of protocol versions");
        for (var v : p.get("protocol")) {
            if (!v.isIntegralNumber()) throw bad("protocol versions must be integers");
            v1 |= v.canConvertToInt() && v.intValue() == 1;
        }
        if (!v1) throw HostError.unsupported("protocol", "no common protocol version");
        var net = fields(p.path("network"), "networkId", "protocolMagic", "mainnet");
        long id = number(net, "networkId", 0);
        boolean mainnet = net.has("mainnet") && bool(net, "mainnet");
        if (id > 1 || id == 1 && !mainnet) throw HostError.bad("network", "unsupported network or missing mainnet opt-in");
        Network selected = new Network((int) id, number(net, "protocolMagic", 0));
        Clock time = Clock.parse(p.path("clock"));
        var t = fields(p.path("time"), "maxTtlSlots", "settlementSlots", "maxNowAheadSlots");
        var l = fields(p.path("limits"), "callDeadlineMs", "readsPerCall", "readBytesPerCall");
        Limits config = new Limits(number(l, "callDeadlineMs", 1), number(l, "readsPerCall", 1),
                number(l, "readBytesPerCall", 1), number(t, "maxTtlSlots", 1), number(t, "settlementSlots", 0),
                number(t, "maxNowAheadSlots", 0));
        if (config.margin > StateRuntime.Builder.MAX_SETTLEMENT_SLOTS) throw bad("settlementSlots exceeds runtime limit");
        var table = new Reservations(config.margin, bool(p, "restoring"), selected.getNetworkId());
        var entries = catalog.call();
        var rt = runtime(selected, time, table, config);
        var out = obj().put("protocol", 1).put("fingerprint", fingerprint);
        out.set("network", net);
        var capabilities = out.putArray("capabilities");
        if (namedViews) capabilities.add("views");
        capabilities.add("objects").add("quorum");
        out.set("reads", JSON.valueToTree(Reads.KINDS));
        out.set("versions", versions.get());
        out.set("contracts", entries.summary());
        return new Transport.Reply(out, () -> {
            limits = config;
            clock = time;
            network = selected;
            contracts = entries;
            reservations = table;
            runtime = rt;
        });
    }

    private static JsonNode jvmVersions() {
        var version = obj();
        var engine = version.putNull("build").putObject("engine");
        var vm = ServiceLoader.load(JulcVmProvider.class).stream().map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(JulcVmProvider::priority))
                .orElseThrow(() -> HostError.internal("evaluator", "no JuLC VM provider"));
        engine.put("joust", HostVersion.RUNTIME).put("ccl", Network.class.getPackage().getImplementationVersion())
                .put("julc", JulcTransactionEvaluator.class.getPackage().getImplementationVersion())
                .put("vmProvider", vm.name()).put("vmVersion", vm.getClass().getPackage().getImplementationVersion());
        return version;
    }

    private StateRuntime runtime(Network net, Clock time, Reservations table, Limits config) {
        return StateRuntime.builder(backend).network(net)
                .chainTime(new SlotChainTime(time.zeroSlot, time.zeroMs, time.length, backend))
                .evaluator((tx, utxos) -> evaluating.get().evaluateTx(tx, utxos))
                .claimStore(table).settlementSlots(config.margin).mapStore(new InMemoryNodeStore()).build();
    }

    private Transport.Reply reset(JsonNode p) {
        fields(p, "now", "clock", "rewound");
        long now = number(p, "now", 0);
        Clock time = p.has("clock") ? Clock.parse(p.get("clock")) : clock;
        Long tip = p.has("rewound") ? number(fields(p.get("rewound"), "tip"), "tip", 0) : null;
        var rt = runtime(network, time, reservations, limits);
        return new Transport.Reply(obj(), () -> {
            listings.clear();
            highestNow = now;
            clock = time;
            runtime = rt;
            if (tip != null) reservations.rewind(tip);
        });
    }

    private Transport.Reply withReads(String method, Reads.Peer peer, java.util.function.Consumer<Runnable> whileLive, JsonNode p) {
        boolean building = method.equals("publish") || method.equals("prepare");
        if (building) reservations.allowBuild();
        long now = number(p, "now", 0);
        long ttl = building ? number(p, "ttlSlots", 1) : 0;
        if (now < highestNow) throw HostError.bad("nowDecreased", "now decreased since the previous call");
        whileLive.accept(() -> highestNow = now);
        if (ttl > limits.ttl || now > Long.MAX_VALUE - ttl || ttl > Long.MAX_VALUE / clock.length)
            throw bad("ttlSlots exceeds the session's time limits");
        try {
            runtime.chainTime().slotToPosixMs(now);
            runtime.chainTime().slotToPosixMs(now + ttl);
        } catch (ArithmeticException overflow) { throw bad("call window exceeds the clock range"); }
        var reads = new Reads(peer, now, limits.ahead, limits.reads,
                limits.bytes, network.getNetworkId(), tip -> whileLive.accept(() -> reservations.observe(tip)));
        reading.set(reads);
        evaluating.set(new JulcTransactionEvaluator(new DefaultUtxoSupplier(backend.getUtxoService()),
                new DefaultProtocolParamsSupplier(backend.getEpochService()), new DefaultScriptSupplier(backend.getScriptService()),
                new SlotConfig(clock.zeroSlot, clock.zeroMs, clock.length)));
        try {
            var scope = CallScope.hosted(now);
            if (building) scope = scope.ttlSlots(ttl);
            return runtime.inScope(scope, () -> method.equals("prepare")
                    ? prepare(p, reads, now + ttl) : query(method, p, reads, now + ttl));
        } finally {
            evaluating.remove();
            reading.remove();
        }
    }

    private Contracts.Contract served(JsonNode request) {
        var c = contracts.get(str(request, "contract"));
        if (c.unsupported() != null) throw HostError.unsupported(c.unsupported(), "unsupported contract " + c.name());
        return c;
    }

    private Transport.Reply query(String method, JsonNode p, Reads reads, long ceiling) {
        var c = served(p);
        var handle = c.adapter().handle(runtime);
        switch (method) {
            case "instances", "objects" -> {
                boolean objects = method.equals("objects");
                if (objects) fields(p, "now", "contract", "instance", "limit");
                else fields(p, "now", "contract", "limit");
                String owner = objects ? instance(p) : null;
                int limit = Listings.limit(p);
                return listings.start(method, c, owner, reads.outputs(handle.address()), reads.tipHash(), limit);
            }
            case "view" -> {
                fields(p, "now", "contract", "instance", "view", "args");
                if (!p.path("args").isObject()) throw bad("expected view arguments");
                String view = p.path("view").isNull() ? null : str(p, "view");
                if (view == null && !p.get("args").isEmpty()) throw bad("state takes no arguments");
                checkAddressArguments(p.get("args"));
                return new Transport.Reply(obj().set("value", c.adapter().viewJson(runtime, instance(p), view, p.get("args"))));
            }
            case "publish" -> fields(p, "now", "ttlSlots", "contract", "prerequisite", "address");
            default -> fields(p, "now", "contract");
        }
        String payer = null;
        if (method.equals("publish")) payer = wallet(str(p, "address")).address();
        var prerequisites = new Prerequisites(c, handle, reads, reservations, !method.equals("deployment"));
        if (method.equals("deployment")) return new Transport.Reply(prerequisites.deployment());
        if (method.equals("prerequisites")) return new Transport.Reply(prerequisites.missing());
        var part = prerequisites.next(str(p, "prerequisite"));
        if (part == null) return new Transport.Reply(NullNode.instance);
        if (reservations.publishing(part.id()))
            throw HostError.refusal("pending", "a publication of " + part.id() + " is still pending; settle it first");
        var built = prerequisites.publish(part, payer, ceiling);
        return prepared(built, obj(), reads, ceiling, part.registration());
    }

    private static String instance(JsonNode request) {
        String id = str(request, "instance");
        if (!id.matches("[0-9a-f]{64}")) throw bad("instance must be 32 bytes of hex");
        return id;
    }

    private WalletAccount wallet(String address) {
        Reads.checkAddress(address, network.getNetworkId());
        try { return WalletAccount.of(address, network); }
        catch (JoustException e) { throw bad("invalid caller address"); }
    }

    /** A body holds at most this many co-signers' required signatures (dev-host-1 `prepare`, both hosts). */
    static final int MAX_COSIGNERS = 64;

    private Account[] signers(WalletAccount wallet, JsonNode request, boolean quorum) {
        JsonNode signers = request.path("cosigners");
        if (!signers.isMissingNode() && !signers.isArray()) throw bad("expected co-signer array");
        if (!quorum && !signers.isEmpty()) throw bad("plain calls take no co-signers");
        // Each co-signer builds an account (milliseconds of key generation): bound the list before any work (#151 SB1).
        if (signers.size() > MAX_COSIGNERS) throw bad("at most " + MAX_COSIGNERS + " co-signers");
        var accounts = new LinkedHashMap<String, Account>();
        for (JsonNode signer : signers) {
            String hash = signer.asText("");
            if (!signer.isTextual() || !hash.matches("[0-9a-f]{56}")) throw bad("invalid payment key hash");
            accounts.computeIfAbsent(hash, h -> WalletAccount.of(AddressProvider.getEntAddress(
                    Credential.fromKey(HexFormat.of().parseHex(h)), network).toBech32(), network));
        }
        if (quorum) accounts.put(HexFormat.of().formatHex(wallet.paymentKeyHash()), wallet);
        return accounts.values().toArray(Account[]::new);
    }

    private Transport.Reply prepare(JsonNode p, Reads reads, long ceiling) {
        fields(p, "now", "ttlSlots", "call");
        var request = fields(p.path("call"), "contract", "kind", "method", "instance", "object", "args", "pay", "address", "cosigners");
        var c = served(request);
        String kind = str(request, "kind");
        if (Set.of("intent", "cancelIntent", "batch", "compose").contains(kind))
            throw HostError.unsupported("unverifiable", "unsupported call kind " + kind);
        String method = str(request, "method");
        var meta = c.meta(kind, method);
        if (kind.equals("view")) throw bad("use the view method");
        String code = c.code(kind, meta);
        if (code != null) throw HostError.unsupported(code, "unsupported call " + method);
        var wallet = wallet(str(request, "address"));
        boolean quorum = meta.at("/policies/quorum").isObject();
        Account[] signers = signers(wallet, request, quorum);
        checkAddressArguments(request.path("args"));
        var pay = request.has("pay") ? fields(request.get("pay"), "lovelace", "assets") : obj();
        if (pay.has("assets") && !pay.get("assets").isArray()) throw bad("expected pay.assets array");
        var amount = pay.has("lovelace") ? (BigInteger) JsonValues.fromJson(pay.get("lovelace"), BigInteger.class, "pay.lovelace") : BigInteger.ZERO;
        var handle = c.adapter().handle(runtime);
        new Prerequisites(c, handle, reads, reservations, true).requireReady();
        // A field the call kind does not take is refused, not ignored (final review F-B4).
        if (kind.equals("init") && !request.path("instance").isMissingNode() && !request.get("instance").isNull())
            throw bad("init takes no instance");
        if (!kind.startsWith("owned") && !request.path("object").isMissingNode() && !request.get("object").isNull())
            throw bad(kind + " takes no object");
        String instance = kind.equals("init") ? null : instance(request);
        String object = kind.startsWith("owned") ? request.path("object").textValue() : null;
        String spent = null;
        if (kind.equals("ownedAction") || kind.equals("ownedDestroy")) {
            if (object == null || !object.matches("4f[0-9a-f]{56}")) throw bad("invalid owned object id");
            var datums = new ContractDatums(c);
            for (var u : reads.outputs(handle.address())) {
                if (u.getAmount().stream().noneMatch(a -> a.getUnit().equals(c.hash() + object))) continue;
                var item = datums.recognize(u);
                if (!instance.equals(item.path("instance").textValue())) throw bad("object does not belong to instance");
                spent = str(item, "ref");
                break;
            }
        } else if (instance != null) {
            var target = handle.at(instance).utxo();
            spent = target.getTxHash() + "#" + target.getOutputIndex();
        }
        if (spent != null && reservations.avoids(spent)) throw HostError.refusal("pending", "reserved input " + spent);
        Object[] made = new Object[1];
        var built = Compose.of(runtime, wallet).add(() -> made[0] = c.adapter().call(runtime, wallet, kind, meta,
                instance, object, request.path("args"), amount, pay.get("assets"), quorum ? signers : null)).prepare();
        var out = obj();
        var result = out.putObject("prepared");
        String created = (String) made[0];
        if (created != null) result.put(kind.equals("ownedInit") ? "object" : "instance", created);
        var plan = JSON.valueToTree(built.plan());
        result.set("plan", plan);
        out.set("predicted", Effects.of(c, meta, plan, built, handle.address(),
                kind.equals("init") ? created : instance, kind.equals("ownedInit") ? created : object, spent));
        BodyBalance.checkCall(built.transaction().getBody(),
                Set.of(handle.rewardAddress(), handle.runtime().outputVerifier().rewardAddress()), handle.policyId());
        return prepared(built, out, reads, ceiling, null);
    }

    private Transport.Reply prepared(PreparedTx built, ObjectNode out, Reads reads, long ceiling, String credential) {
        long end = built.validToSlot().orElseThrow(() -> HostError.refusal("window", "no validity upper bound"));
        if (end > ceiling) throw HostError.refusal("window", "upper bound exceeds the call ceiling");
        var tx = built.transaction();
        reads.checkBalance(tx.getBody());
        var entry = reservations.prepare(Reservations.Body.of(tx, built.txHash(), network.getNetworkId()), reads.greatestTip);
        var result = out.withObject("/prepared").put("reservation", entry.id()).put("cbor", built.cbor())
                .put("txHash", built.txHash()).put("validTo", end);
        if (built.validFromSlot().isPresent()) result.put("validFrom", built.validFromSlot().getAsLong());
        else result.putNull("validFrom");
        result.set("signers", JSON.valueToTree(built.requiredSigners()));
        if (!result.has("plan")) result.set("plan", JSON.valueToTree(built.plan()));
        return new Transport.Reply(out, () -> reservations.commit(entry, credential));
    }

    private void checkAddressArguments(JsonNode n) {
        if (n.isContainerNode()) n.forEach(this::checkAddressArguments);
        else if (n.isTextual()) {
            String s = n.textValue();
            if (s.startsWith("addr1") || s.startsWith("addr_test1") || s.startsWith("stake1") || s.startsWith("stake_test1"))
                Reads.checkAddress(s, network.getNetworkId());
        }
    }
}
