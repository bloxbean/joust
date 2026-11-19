package org.julclang.state.host;

import com.bloxbean.cardano.client.api.TransactionEvaluator;
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
    private final Launch launch;
    private final ThreadLocal<Reads> reading = new ThreadLocal<>();
    private final ThreadLocal<TransactionEvaluator> evaluating = new ThreadLocal<>();
    private final BackendService backend = Reads.backend(reading::get);
    private Limits limits;
    private Network network;
    private Clock clock;
    private Contracts contracts;
    private Reservations reservations;
    private StateRuntime runtime;
    private long highestNow = -1;
    private boolean shutdown;

    Session(Launch launch) { this.launch = launch; }
    public long deadlineMs() { return limits == null ? 60_000 : limits.deadline; }

    public Transport.Reply execute(Call call, JsonNode p) throws Exception {
        if (shutdown) throw bad("session has shut down");
        if (call.method.equals("initialize")) {
            if (contracts != null) throw bad("session already initialized");
            return initialize(p);
        }
        if (contracts == null) throw bad("initialize first");
        return switch (call.method) {
            case "describe" -> {
                fields(p, "contract");
                yield new Transport.Reply(contracts.get(str(p, "contract")).describe());
            }
            case "prepare" -> prepare(call, p);
            case "restore" -> reservations.restore(p);
            case "settled" -> reservations.settled(p, call::whileLive);
            case "reset" -> reset(p);
            case "shutdown" -> {
                if (!p.isMissingNode()) fields(p);
                yield new Transport.Reply(obj(), () -> shutdown = true);
            }
            case "deployment", "prerequisites", "publish", "view", "instances", "objects", "decode", "registered" ->
                    throw HostError.unsupported("notYet", call.method + " is not served in M1a");
            default -> throw HostError.bad("method", "unknown method " + call.method);
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
        var loader = getClass().getClassLoader();
        var catalog = Contracts.load(launch, loader);
        var rt = runtime(selected, time, table, config);
        var out = obj().put("protocol", 1).put("fingerprint", launch.fingerprint());
        out.set("network", net);
        out.putArray("capabilities");
        out.set("reads", JSON.valueToTree(Reads.KINDS));
        var engine = out.putObject("versions").putNull("build").putObject("engine");
        var vm = ServiceLoader.load(JulcVmProvider.class).stream().map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(JulcVmProvider::priority))
                .orElseThrow(() -> HostError.internal("evaluator", "no JuLC VM provider"));
        engine.put("joust", HostVersion.RUNTIME).put("ccl", Network.class.getPackage().getImplementationVersion())
                .put("julc", JulcTransactionEvaluator.class.getPackage().getImplementationVersion())
                .put("vmProvider", vm.name()).put("vmVersion", vm.getClass().getPackage().getImplementationVersion());
        out.set("contracts", catalog.summary());
        return new Transport.Reply(out, () -> {
            limits = config;
            clock = time;
            network = selected;
            contracts = catalog;
            reservations = table;
            runtime = rt;
        });
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
            highestNow = now;
            clock = time;
            runtime = rt;
            if (tip != null) reservations.rewind(tip);
        });
    }

    private Transport.Reply prepare(Call execution, JsonNode p) {
        fields(p, "now", "ttlSlots", "call");
        reservations.allowBuild();
        long now = number(p, "now", 0), ttl = number(p, "ttlSlots", 1);
        if (now < highestNow) throw HostError.bad("nowDecreased", "now decreased since the previous call");
        execution.whileLive(() -> highestNow = now);
        if (ttl > limits.ttl || now > Long.MAX_VALUE - ttl || ttl > Long.MAX_VALUE / clock.length)
            throw bad("ttlSlots exceeds the session's time limits");
        try {
            runtime.chainTime().slotToPosixMs(now);
            runtime.chainTime().slotToPosixMs(now + ttl);
        } catch (ArithmeticException overflow) { throw bad("call window exceeds the clock range"); }
        var request = fields(p.path("call"), "contract", "kind", "method", "instance", "object", "args", "pay", "address", "cosigners");
        var c = contracts.get(str(request, "contract"));
        if (c.unsupported() != null) throw HostError.unsupported(c.unsupported(), "unsupported contract " + c.name());
        String kind = str(request, "kind");
        if (Set.of("intent", "cancelIntent", "batch", "compose").contains(kind))
            throw HostError.unsupported("unverifiable", "unsupported call kind " + kind);
        String method = str(request, "method");
        var meta = c.meta(kind, method);
        String code = c.code(kind, meta);
        if (code != null) throw HostError.unsupported(code, "unsupported call " + method);
        String address = str(request, "address");
        Reads.checkAddress(address, network.getNetworkId());
        if (request.has("cosigners") && (!request.get("cosigners").isArray() || !request.get("cosigners").isEmpty()))
            throw bad("plain calls take no co-signers");
        checkAddressArguments(request.path("args"));
        WalletAccount wallet;
        try { wallet = WalletAccount.of(address, network); }
        catch (IllegalArgumentException e) { throw bad("invalid caller address"); }
        var pay = request.has("pay") ? fields(request.get("pay"), "lovelace", "assets") : obj();
        var amount = pay.has("lovelace") ? (BigInteger) JsonValues.fromJson(pay.get("lovelace"), BigInteger.class, "pay.lovelace") : BigInteger.ZERO;
        var reads = new Reads(params -> execution.reverse("chain/read", params), now, limits.ahead, limits.reads,
                limits.bytes, network.getNetworkId(), tip -> execution.whileLive(() -> reservations.observe(tip)));
        reading.set(reads);
        evaluating.set(new JulcTransactionEvaluator(new DefaultUtxoSupplier(backend.getUtxoService()),
                new DefaultProtocolParamsSupplier(backend.getEpochService()), new DefaultScriptSupplier(backend.getScriptService()),
                new SlotConfig(clock.zeroSlot, clock.zeroMs, clock.length)));
        try {
            return runtime.inScope(CallScope.hosted(now).ttlSlots(ttl), () -> {
                // D12: never enter a generated client's on-demand publication path.
                var handle = c.adapter().handle(runtime);
                if (handle.findReferenceScript() == null)
                    throw HostError.refusal("prerequisite", "contract reference script missing");
                if (!kind.equals("init")) {
                    String instance = str(request, "instance");
                    if (!instance.matches("[0-9a-f]{64}")) throw bad("instance must be 32 bytes of hex");
                    var state = handle.at(instance).utxo();
                    String ref = state.getTxHash() + "#" + state.getOutputIndex();
                    if (reservations.avoids(ref)) throw HostError.refusal("pending", "reserved state input " + ref);
                }
                Object[] made = new Object[1];
                var prepared = Compose.of(runtime, wallet).add(() -> made[0] = c.adapter().call(runtime, wallet, kind,
                        meta, request.path("instance").textValue(), request.path("object").textValue(), request.path("args"),
                        amount, pay.get("assets"))).prepare();
                long end = prepared.validToSlot().orElseThrow(() -> HostError.refusal("window", "no validity upper bound"));
                if (end > now + ttl) throw HostError.refusal("window", "upper bound exceeds the call ceiling");
                execution.checkLive();
                var entry = reservations.prepare(Reservations.Body.of(prepared.transaction(), prepared.txHash(), network.getNetworkId()), reads.greatestTip);
                var out = obj();
                var result = out.putObject("prepared").put("reservation", entry.id()).put("cbor", prepared.cbor())
                        .put("txHash", prepared.txHash()).put("validTo", end);
                if (made[0] != null) result.put("instance", (String) made[0]);
                if (prepared.validFromSlot().isPresent()) result.put("validFrom", prepared.validFromSlot().getAsLong());
                else result.putNull("validFrom");
                result.set("signers", JSON.valueToTree(prepared.requiredSigners()));
                result.set("plan", JSON.valueToTree(prepared.plan()));
                return new Transport.Reply(out, () -> reservations.commit(entry));
            });
        } finally {
            evaluating.remove();
            reading.remove();
        }
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
