package org.julclang.state.host;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.state.api.RequireException;
import org.julclang.state.runtime.*;
import org.julclang.state.runtime.described.DescribedContract;
import org.julclang.state.runtime.described.UplcTransitions;
import org.julclang.state.runtime.described.TransitionFn;
import org.julclang.state.runtime.wasm.Prepare;
import org.julclang.state.serve.ContractCalls;

import org.julclang.core.PlutusData;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import static org.julclang.state.host.Wire.*;

/** One engine worker's in-memory session. The transport owns the generation and the frame-write commit point. */
public final class EngineSession {
    private Session session;
    private Transport.Reply pending;

    public String execute(String frame, String setup, Function<String, String> reverse, BooleanSupplier live) {
        pending = null;
        try {
            var request = JSON.readTree(frame);
            String method = str(request, "method");
            if (session == null) {
                if (!method.equals("initialize")) throw bad("initialize first");
                var data = JSON.readTree(setup);
                session = new Session(str(data, "fingerprint"), () -> catalog(data.required("contracts")),
                        () -> data.required("versions"), false);
            }
            Reads.Peer peer = params -> {
                String answer = reverse.apply(params.toString());
                if (answer == null || !live.getAsBoolean()) throw HostError.internal("cancelled", "call cancelled");
                try { return new Call.Answer(JSON.readTree(answer), answer.getBytes(StandardCharsets.UTF_8).length); }
                catch (java.io.IOException malformed) { throw HostError.internal("inconsistentRead", "invalid reverse answer"); }
            };
            pending = session.execute(method, peer, mutation -> {
                if (!live.getAsBoolean()) throw HostError.internal("cancelled", "call cancelled");
                mutation.run();
            }, request.path("params"));
            return obj().set("result", pending.value()).toString();
        } catch (Throwable failure) {
            return obj().set("error", Errors.rpcError(failure)).toString();
        }
    }

    public void commit() {
        var reply = pending;
        pending = null;
        if (reply != null) reply.commit().run();
    }

    private static Contracts catalog(JsonNode entries) throws Exception {
        var contracts = new TreeMap<String, Contracts.Contract>();
        for (JsonNode entry : entries) {
            var source = JSON.treeToValue(entry, Prepare.Contract.class);
            var desc = JSON.readTree(source.description());
            var x = desc.required("x-joust");
            String name = str(desc.required("preamble"), "title");
            String hash = HexFormat.of().formatHex(PlutusV3Script.builder().cborHex(source.script()).build().getScriptHash());
            String code = x.has("params") ? "params" : null;
            var messages = source.messages() == null ? null : JSON.readTree(source.messages());
            if (messages instanceof ObjectNode map) map.put("scriptHash", x.has("params") ? "" : hash);
            var calls = code == null ? new Described(load(source, desc), messages) : null;
            var c = new Contracts.Contract(name, desc, !x.path("keyed").isEmpty(), x.path("stakeSelf").asBoolean(),
                    messages, calls, hash, code, false);
            if (contracts.put(name, c) != null) throw bad("duplicate contract " + name);
        }
        return new Contracts(contracts);
    }

    private static DescribedContract load(Prepare.Contract source, JsonNode desc) {
        var vm = UplcTransitions.verified(source.manifest(), source.description(), source.script(), source.transitions());
        return new DescribedContract(desc, source.script(), new TransitionFn() {
            public PlutusData apply(String method, PlutusData target,
                    PlutusData shared, PlutusData args, PlutusData ctx) {
                var run = run(method, target, shared, args, ctx);
                if (run.step() == null) throw new RequireException(run.refusal());
                return run.step();
            }
            public Run run(String method, PlutusData target, PlutusData shared,
                           PlutusData args, PlutusData ctx) {
                var result = vm.evaluate(method, target, shared, args, ctx);
                return switch (result.kind()) {
                    case STEP, REFUSAL -> result.run(); // Includes map-read discovery traces, also on a tentative refusal.
                    case ERROR, BUDGET -> throw HostError.internal("evaluator", result.run().refusal());
                };
            }
        });
    }

    private record Described(DescribedContract contract, JsonNode messages) implements ContractCalls {
        public StateContractHandle<?> handle(StateRuntime rt) { return contract.handle(rt); }

        public JsonNode viewJson(StateRuntime rt, String instance, String view, JsonNode args) {
            if (view != null) throw HostError.unsupported("views", "the engine serves state only");
            return contract.state(rt, instance);
        }

        public Object call(StateRuntime rt, Account caller, String kind, JsonNode meta, String instance, String object,
                           JsonNode args, BigInteger amount, JsonNode assets, Account[] signers) {
            try {
                return contract.call(rt, caller, kind, str(meta, "name"), instance, object, args, amount,
                        null, assets, null, signers == null ? List.of() : Arrays.asList(signers));
            } catch (RequireException e) {
                throw JscMessages.explained(messages, e);
            } catch (IllegalArgumentException e) {
                throw bad(e.getMessage());
            }
        }
    }
}
