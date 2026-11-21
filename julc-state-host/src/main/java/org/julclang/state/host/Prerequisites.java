package org.julclang.state.host;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.fasterxml.jackson.databind.node.*;
import org.julclang.state.runtime.*;
import java.util.*;
import static org.julclang.state.host.Wire.*;

/** D14. Re-evaluate both components against this call's answers before choosing a publication. */
final class Prerequisites {
    record Part(String kind, String target, String hash, Boolean present, VerifierHandle verifier) {
        String id() { return kind + ":" + hash; }
        ObjectNode json() {
            var out = obj().put("id", id()).put("kind", kind).put("target", target);
            if (present == null) out.put("present", "unknown");
            else out.put("present", present.booleanValue());
            return out;
        }
        String registration() { return kind.equals("referenceScript") ? null : hash; }
    }

    private final StateContractHandle<?> handle;
    private final Reads reads;
    private final Reservations reservations;
    private final List<Part> missing = new ArrayList<>();
    private final Set<String> satisfied = new HashSet<>();
    private final ObjectNode deployment = obj();

    Prerequisites(Contracts.Contract contract, StateContractHandle<?> handle, Reads reads, Reservations reservations,
                  boolean checkRegistration) {
        this.handle = handle;
        this.reads = reads;
        this.reservations = reservations;
        var addresses = deployment.putObject("addresses").put("contract", handle.address());
        deployment.putObject("policies").put("contract", handle.policyId());
        var hashes = deployment.putObject("scriptHashes").put("mint", handle.policyId()).put("spend", handle.policyId());
        deployment.putObject("referenceScripts");
        // TODO(ADR-0022 D14/D16, Q7): add governor registrations when deployments can pin applied governor scripts.
        deployment.putArray("governors");
        boolean own = reference("contract", handle.address(), handle.policyId());
        component("referenceScript", handle.address(), handle.policyId(), own, null);
        var x = contract.description().path("x-joust");
        boolean withdraw = false, sends = false;
        for (String group : List.of("actions", "creations", "purposes")) {
            for (var method : x.path(group)) {
                withdraw |= method.path("purpose").asText().equals("withdraw");
                sends |= method.path("returns").path("sends").asBoolean()
                        || method.path("returns").path("deposits").asBoolean() || method.at("/policies/receives").asBoolean();
            }
        }
        if (contract.stakeSelf() || withdraw || x.has("purposes")) {
            addresses.put("reward", handle.rewardAddress());
            hashes.put("stake", handle.policyId());
        }
        if (withdraw && checkRegistration)
            component("stakeRegistration", handle.rewardAddress(), handle.policyId(), registered(handle.policyId()), null);
        if (sends) verifier("outputVerifier", handle.runtime().outputVerifier(), checkRegistration);
        if (contract.maps()) verifier("mapVerifier", handle.runtime().mapVerifier(), checkRegistration);
    }

    private void verifier(String label, VerifierHandle v, boolean checkRegistration) {
        deployment.withObject("/addresses").put(label, v.holder()).put(label + "Reward", v.rewardAddress());
        deployment.withObject("/scriptHashes").put(label, v.hash());
        boolean script = reference(label, v.holder(), v.hash());
        if (checkRegistration) {
            Boolean registration = registered(v.hash());
            if (!script && Boolean.FALSE.equals(registration)) {
                component("verifier", v.holder(), v.hash(), false, v);
            } else {
                component("referenceScript", v.holder(), v.hash(), script, v);
                component("stakeRegistration", v.rewardAddress(), v.hash(), registration, v);
                if (script && Boolean.TRUE.equals(registration)) satisfied.add("verifier:" + v.hash());
            }
        }
    }

    private Boolean registered(String hash) {
        return reads.stakeRegistration(hash).orElseGet(() -> reservations.registered(hash) ? Boolean.TRUE : null);
    }

    private boolean reference(String label, String address, String hash) {
        for (Utxo u : reads.outputs(address)) {
            if (hash.equalsIgnoreCase(u.getReferenceScriptHash())) {
                deployment.withObject("/referenceScripts").put(label, u.getTxHash() + "#" + u.getOutputIndex());
                return true;
            }
        }
        return false;
    }

    private void component(String kind, String target, String hash, Boolean present, VerifierHandle v) {
        var part = new Part(kind, target, hash, present, v);
        if (Boolean.TRUE.equals(present)) satisfied.add(part.id());
        else missing.add(part);
    }

    ObjectNode deployment() { return deployment; }

    ArrayNode missing() {
        var list = JSON.createArrayNode();
        missing.forEach(p -> list.add(p.json()));
        return list;
    }

    void requireReady() {
        if (!missing.isEmpty()) throw HostError.refusal("prerequisite", "missing " + missing.getFirst().id());
    }

    Part next(String id) {
        if (satisfied.contains(id)) return null;
        if (!missing.isEmpty() && missing.getFirst().id().equals(id)) return missing.getFirst();
        throw HostError.refusal("prerequisite", "refresh the prerequisite list before publishing " + id);
    }

    PreparedTx publish(Part p, String payer, long end) {
        if (p.verifier == null) return p.kind.equals("referenceScript")
                ? handle.prepareReferenceScript(payer, end, reads.outputs(handle.address()))
                : handle.prepareStakeRegistration(payer, end);
        var component = switch (p.kind) {
            case "referenceScript" -> VerifierHandle.Component.SCRIPT;
            case "stakeRegistration" -> VerifierHandle.Component.REGISTRATION;
            case "verifier" -> VerifierHandle.Component.BOTH;
            default -> throw new IllegalStateException(p.kind);
        };
        return p.verifier.prepare(payer, end, reads.outputs(p.verifier.holder()), component);
    }
}
