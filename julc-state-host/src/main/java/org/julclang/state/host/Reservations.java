package org.julclang.state.host;

import com.bloxbean.cardano.client.api.util.ReferenceScriptUtil;
import com.bloxbean.cardano.client.transaction.spec.*;
import com.bloxbean.cardano.client.transaction.spec.cert.RegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.julclang.state.runtime.ClaimStore;
import org.julclang.state.runtime.snapshot.Unsupported;
import java.util.*;
import static org.julclang.state.host.Wire.*;

/** D8. Final entries are retained with their original body evidence for a subsequent rewind. */
final class Reservations implements ClaimStore {
    enum State { prepared, shallow, confirmed, expired, discarded }
    /**
     * {@code keyedTokens}: the keyed object units the body holds or mints (M3a). {@code publishes}: the prerequisite
     * components the body would put on chain (D14 ids {@code referenceScript:h} for an output carrying script h as its
     * reference script, {@code stakeRegistration:h} for a registration of credential h). Both derived from the body, so
     * a restored body counts as well as one this host built.
     */
    record Body(String hash, Set<String> inputs, Set<String> collateral, long from, long to, Set<String> keyedTokens,
                Set<String> publishes) {
        Body(String hash, Set<String> inputs, Set<String> collateral, long from, long to) {
            this(hash, inputs, collateral, from, to, Set.of(), Set.of());
        }
        // Include relinked predecessors and heads. Their previous outrefs may already have changed at shallow inclusion.
        private static Set<String> keyedTokens(TransactionBody body) {
            var units = new HashSet<String>();
            for (var out : body.getOutputs()) {
                if (out.getInlineDatum() == null || out.getValue().getMultiAssets().isEmpty()) continue;
                var payment = new com.bloxbean.cardano.client.address.Address(out.getAddress()).getPaymentCredential();
                if (payment.isPresent() && payment.get().getType() == com.bloxbean.cardano.client.address.CredentialType.Script)
                    addKeyed(units,out.getValue(),HexFormat.of().formatHex(payment.get().getBytes()));
            }
            if (body.getMint()!=null) addKeyed(units,new Value(java.math.BigInteger.ZERO,body.getMint()),null);
            return Set.copyOf(units);
        }
        private static void addKeyed(Set<String> units, Value value, String policy) {
            for (var amount : com.bloxbean.cardano.client.api.util.ValueUtil.toAmountList(value)) {
                String unit = amount.getUnit();
                if (unit.length()==114 && unit.startsWith("4b",56) && (policy == null || unit.startsWith(policy))) units.add(unit);
            }
        }
        static Body of(Transaction tx, String hash, int network) {
            var b = tx.getBody();
            if (b.getTtl() <= 0) throw HostError.bad("unbounded", "transaction has no upper bound");
            // D4 also applies to bodies re-imported from the client's journal.
            for (var output : b.getOutputs()) Reads.checkAddress(output.getAddress(), network);
            if (b.getCollateralReturn() != null) Reads.checkAddress(b.getCollateralReturn().getAddress(), network);
            if (b.getWithdrawals() != null) {
                for (var withdrawal : b.getWithdrawals()) Reads.checkAddress(withdrawal.getRewardAddress(), network);
            }
            return new Body(hash, refs(b.getInputs()), refs(b.getCollateral()), b.getValidityStartInterval(), b.getTtl(),
                    keyedTokens(b), publishes(b));
        }
        private static Set<String> publishes(TransactionBody b) {
            var ids = new HashSet<String>();
            for (var output : b.getOutputs()) {
                if (output.getScriptRef() == null) continue;
                try {
                    ids.add("referenceScript:" + HexFormat.of().formatHex(
                            ReferenceScriptUtil.deserializeScriptRef(output.getScriptRef()).getScriptHash()));
                } catch (Exception e) {
                    throw bad("invalid reference script in transaction output");
                }
            }
            if (b.getCerts() != null) {
                for (var cert : b.getCerts()) {
                    var credential = cert instanceof StakeRegistration r ? r.getStakeCredential()
                            : cert instanceof RegCert r ? r.getStakeCredential() : null;
                    if (credential != null) ids.add("stakeRegistration:" + HexFormat.of().formatHex(credential.getHash()));
                }
            }
            return Set.copyOf(ids);
        }
        private static Set<String> refs(List<TransactionInput> ins) {
            var refs = new HashSet<String>();
            // One spelling per input, as the runtime's claim keys (Designation.ref): lowercase hex.
            if (ins != null) for (var in : ins) refs.add(in.getTransactionId().toLowerCase(Locale.ROOT) + "#" + in.getIndex());
            return Set.copyOf(refs);
        }
        static Body decode(String cbor, int network) {
            try {
                byte[] bytes = HexFormat.of().parseHex(cbor);
                return of(Transaction.deserialize(bytes), TransactionUtil.getTxHash(bytes), network);
            } catch (Exception e) { throw bad("invalid transaction CBOR"); }
        }
    }
    record Entry(String id, Body body, State state, Long slot, KeyedOperation operation) {
        Entry as(State state, Long slot) {
            var op = operation != null && state == State.shallow ? operation.handedOut(body.hash, body.to) : operation;
            return new Entry(id, body, state, slot, op);
        }
        boolean live() { return state == State.prepared || state == State.shallow; }
    }
    private Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, String> publicationCredentials = new HashMap<>();
    private final Map<String, Set<String>> assertions = new HashMap<>();

    void commit(Entry entry, String credential) {
        commit(entry);
        if (credential != null) publicationCredentials.put(entry.id, credential);
    }

    boolean registered(String credential) {
        if (assertions.values().stream().anyMatch(keys -> keys.contains(credential))) return true;
        return publicationCredentials.entrySet().stream().anyMatch(p ->
                p.getValue().equals(credential) && entries.get(p.getKey()).state == State.confirmed);
    }

    Transport.Reply registered(JsonNode p) {
        fields(p, "credential", "evidence");
        String credential = str(p, "credential");
        if (!credential.matches("[0-9a-f]{56}")) throw bad("invalid stake credential hash");
        var evidence = fields(p.path("evidence"), "txHash", "slot", "message");
        String hash = str(evidence, "txHash");
        if (!hash.matches("[0-9a-f]{64}") || evidence.has("slot") == evidence.has("message"))
            throw bad("expected transaction hash and either inclusion slot or node message");
        if (evidence.has("slot")) number(evidence, "slot", 0);
        else str(evidence, "message");
        return new Transport.Reply(obj(), () -> assertions.computeIfAbsent(hash, ignored -> new HashSet<>()).add(credential));
    }

    private final long margin;
    private final int network;
    private boolean reconcile;
    private boolean deepRollback;
    private long lastId;
    Reservations(long margin, boolean restoring, int network) {
        this.network = network;
        this.margin = margin;
        this.reconcile = restoring;
    }

    void allowBuild() {
        if (deepRollback) throw HostError.refusal("deepRollback", "restart after a deep rollback");
        if (reconcile) throw HostError.refusal("reconcile", "restore is incomplete");
    }
    Entry prepare(Body body, long tip) {
        return prepare(body, tip, null);
    }
    Entry prepare(Body body, long tip, KeyedOperation operation) {
        if (operation != null && !body.inputs.contains(operation.identity().designated())) throw KeyedOperation.mismatch();
        if (body.inputs.stream().anyMatch(this::avoids) || body.collateral.stream().anyMatch(this::avoids))
            throw HostError.refusal("pending", "transaction touches a reserved input");
        if (operation != null && entries.values().stream().anyMatch(e -> e.live() &&
                !Collections.disjoint(body.keyedTokens,e.body.keyedTokens)))
            throw HostError.refusal("pending", "a keyed object is still reserved");
        if (body.to <= tip) throw HostError.refusal("expiredOnArrival", "transaction is already past its upper bound");
        return new Entry(Long.toString(++lastId), body, State.prepared, null, operation);
    }
    void commit(Entry e) { entries.put(e.id, e); }

    /**
     * A live (prepared or shallow) transaction already publishes prerequisite {@code id} or a component of it
     * ({@code verifier:h} is {@code referenceScript:h} with {@code stakeRegistration:h}): a second publication would
     * lock another holder's minimum value, or register twice, before the first settles (#151 review DB1).
     */
    boolean publishing(String id) {
        var parts = id.startsWith("verifier:")
                ? Set.of("referenceScript:" + id.substring(9), "stakeRegistration:" + id.substring(9)) : Set.of(id);
        for (var e : entries.values()) {
            if (e.live() && e.body.publishes.stream().anyMatch(parts::contains)) return true;
        }
        return false;
    }

    @Override public boolean avoids(String ref) {
        for (var e : entries.values()) {
            if (e.live() && (e.body.inputs.contains(ref) || e.body.collateral.contains(ref))) return true;
        }
        return false;
    }

    // Only the final body reserves anything. Runtime picks/claims have no lifetime outside this
    // serial plain build (D8). Neither Expiry.untilMs nor a runtime margin advances the table.
    @Override public Collection<Claim> claims() { return List.of(); }
    @Override public void add(Claim claim, Expiry when) {}
    @Override public void remove(Claim claim) {}
    @Override public void expire(Claim claim, Expiry when) {}
    @Override public boolean isDesignated(String ref) { return false; }
    @Override public boolean designate(String ref) { throw new Unsupported("designate"); }
    @Override public void undesignate(String ref, Expiry when) { throw new Unsupported("undesignate"); }

    // Use the chain point of the complete wallet listing, never now or an output-by-ref lookup.
    void checkOperation(KeyedOperation op, boolean unspent, long tip, String objectUnit) {
        var id = op.identity();
        boolean pending = false;
        boolean landed = false;
        boolean known = false;
        var expected = new HashMap<String,Long>();
        for (var e : entries.values()) {
            Long supplied = op.attempts().get(e.body.hash);
            if (e.operation == null || !id.equals(e.operation.identity())) {
                if (supplied != null && e.state != State.discarded) throw KeyedOperation.mismatch();
                if (e.live() && objectUnit != null && e.body.keyedTokens.contains(objectUnit)) pending = true;
                continue;
            }
            known = true;
            expected.putAll(e.operation.attempts());
            if (e.state == State.discarded) continue; // An identical re-prepare can have the same body hash.
            // An attempt never acknowledged as handed out (neither its own record nor this one lists it): expired, it
            // cannot land and was never signed, so it does not hold the operation (#156 review K2); still prepared
            // (awaiting approval, or revived by a rewind) it holds it as a live attempt: pending (final review F-K3).
            if (supplied == null && !e.operation.attempts().containsKey(e.body.hash)) {
                if (e.state == State.expired) continue;
                if (e.state == State.prepared) { pending = true; continue; }
            }
            expected.put(e.body.hash,e.body.to);
            if (supplied == null || supplied != e.body.to) throw KeyedOperation.mismatch();
            pending |= e.live();
            landed |= e.state == State.confirmed;
        }
        if (known && !expected.equals(op.attempts())) throw KeyedOperation.mismatch();
        // A matching retry acknowledges handoff even when this prepare must return pending.
        // Retain it across rollback so a later discarded notification cannot release signed inputs.
        if (known) entries.replaceAll((rid,e) -> e.live() && e.operation != null && id.equals(e.operation.identity())
                && op.attempts().containsKey(e.body.hash) && !e.operation.attempts().containsKey(e.body.hash)
                ? new Entry(rid,e.body,e.state,e.slot,e.operation.handedOut(e.body.hash,e.body.to)) : e);
        if (!unspent || landed) throw HostError.refusal("mayHaveLanded", "designated input spent or an attempt confirmed");
        if (pending || avoids(id.designated()) || op.attempts().values().stream().anyMatch(to -> tip - to <= margin))
            throw HostError.refusal("pending", "a previous attempt or an input is still reserved");
    }

    void observe(long tip) {
        if (!reconcile) entries.replaceAll((id, e) -> advance(e, tip));
    }
    private Entry advance(Entry e, long tip) {
        if (e.state == State.prepared && tip - e.body.to > margin) return e.as(State.expired, null);
        if (e.state == State.shallow && tip - e.slot > margin) return e.as(State.confirmed, e.slot);
        return e;
    }
    private static HostError conflict() { return HostError.bad("conflict", "settlement conflicts with the reservation"); }
    private static long inclusion(Body b, JsonNode p) {
        long slot = number(p, "slot", 0);
        if (slot < b.from || slot >= b.to) throw conflict();
        return slot;
    }
    Transport.Reply settled(JsonNode p, java.util.function.Consumer<Runnable> whileLive) {
        fields(p, "reservation", "txHash", "status", "slot", "tip");
        var e = entries.get(str(p, "reservation"));
        if (e == null) throw HostError.bad("unknownReservation", "unknown reservation");
        if (!e.body.hash.equals(str(p, "txHash"))) throw conflict();
        long tip = number(p, "tip", 0);
        String status = str(p, "status");
        if (!status.equals("confirmed") && p.has("slot")) throw bad("only a confirmation takes slot");
        Entry next = switch (status) {
            case "confirmed" -> {
                long slot = inclusion(e.body, p);
                if (slot > tip || e.slot != null && e.slot != slot) throw conflict();
                if (e.state == State.confirmed) yield e;
                if (!e.live()) throw conflict();
                yield e.as(State.shallow, slot);
            }
            case "rolledBack" -> {
                if (e.state == State.confirmed) {
                    whileLive.accept(() -> {
                        deepRollback = true;
                        publicationCredentials.remove(e.id);
                        assertions.remove(e.body.hash);
                    });
                    throw HostError.refusal("deepRollback", "a final inclusion was rolled back");
                }
                if (!e.live()) throw conflict();
                yield e.as(State.prepared, null);
            }
            case "expired" -> {
                if (e.state == State.expired) yield e;
                if (e.state != State.prepared) throw conflict();
                if (tip - e.body.to <= margin || reconcile) throw HostError.bad("notExpired", "expiry has not been established");
                yield e.as(State.expired, null);
            }
            case "discarded" -> {
                if (e.operation != null && e.operation.attempts().containsKey(e.body.hash)) throw conflict();
                if (e.state != State.prepared && e.state != State.discarded) throw conflict();
                yield e.as(State.discarded, null);
            }
            default -> throw bad("unknown settlement status");
        };
        Entry finalState = reconcile ? next : advance(next, tip);
        return new Transport.Reply(obj().put("state", finalState.state.name()), () -> {
            if (status.equals("rolledBack")) assertions.remove(e.body.hash);
            commit(finalState);
            observe(tip);
        });
    }

    void rewind(long tip) {
        assertions.clear();
        // Keep publication provenance: rewind downgrades final entries below, and re-confirmation can teach again.
        entries.replaceAll((id, e) -> switch (e.state) {
            case expired -> e.body.to > tip ? e.as(State.prepared, null) : e;
            case confirmed, shallow -> e.slot > tip ? e.as(State.prepared, null) : e.as(State.shallow, e.slot);
            default -> e;
        });
        reconcile = true;
    }

    Transport.Reply restore(JsonNode p) {
        return restore(p,null);
    }

    Transport.Reply restore(JsonNode p, Contracts contracts) {
        if (!reconcile) throw HostError.bad("restoreClosed", "restore is closed");
        fields(p, "transactions", "complete");
        boolean complete = bool(p, "complete");
        if (!p.path("transactions").isArray()) throw bad("transactions must be an array");
        var staged = new LinkedHashMap<>(entries);
        // The live bodies, indexed by hash: finding a restored body's entry takes no scan of the table (#149 Codex).
        var live = new HashMap<String, Entry>();
        for (var e : staged.values()) if (e.live()) live.putIfAbsent(e.body.hash, e);
        var out = obj();
        var ids = out.putArray("reservations");
        for (var t : p.get("transactions")) {
            fields(t, "cbor", "state", "slot", "operation");
            Body b = Body.decode(str(t, "cbor"), network);
            var op = t.has("operation") ? KeyedOperation.parse(t.get("operation")) : null;
            if (op != null && (!b.inputs.contains(op.identity().designated()) ||
                    op.attempts().getOrDefault(b.hash, b.to) != b.to)) throw KeyedOperation.mismatch();
            if (contracts != null) {
                for (var c : contracts.all().values()) {
                    if (c.keyed() && b.keyedTokens.stream().anyMatch(u -> u.startsWith(c.hash())) &&
                            (op == null || !op.identity().contract().equals(c.name()))) throw KeyedOperation.mismatch();
                }
            }
            for (var held : staged.values()) {
                if (held.state == State.discarded) continue;
                var previous = held.operation;
                boolean same = op != null && previous != null && op.identity().equals(previous.identity());
                Long deadline = op == null ? null : op.attempts().get(held.body.hash);
                Long priorDeadline = previous == null ? null : previous.attempts().get(b.hash);
                // As in checkOperation: another attempt of this operation that was never acknowledged and that the
                // restored record does not list, expired (K2) or prepared (awaiting approval, or revived by a rewind
                // below its validTo), does not refuse the client's record; a prepared one stays reserved, and the
                // next prepare answers pending until it is discarded or expires (final review F-K1).
                if (same && !held.body.hash.equals(b.hash) && deadline == null && !previous.attempts().containsKey(held.body.hash)
                        && (held.state == State.expired || held.state == State.prepared)) continue;
                if (deadline != null && (!same || deadline != held.body.to) ||
                        priorDeadline != null && (!same || priorDeadline != b.to)) throw KeyedOperation.mismatch();
                if (same && (!op.attempts().entrySet().containsAll(previous.attempts().entrySet()) ||
                        !held.body.hash.equals(b.hash) && deadline == null)) throw KeyedOperation.mismatch();
            }
            State state = switch (str(t, "state")) {
                case "prepared" -> State.prepared;
                case "shallow" -> State.shallow;
                default -> throw bad("invalid restore state");
            };
            if (state == State.shallow && op != null) op = op.handedOut(b.hash,b.to);
            Long slot = state == State.shallow ? inclusion(b, t) : null;
            if (slot == null && t.has("slot")) throw bad("prepared transaction has no inclusion slot");
            Entry e = live.get(b.hash);
            if (e != null) {
                if (e.state != state || !Objects.equals(e.slot, slot)) throw conflict();
                if (!Objects.equals(e.operation, op)) {
                    // A rewind can re-import our prepared result after the client handed it out.
                    // The client's record may know later attempts (#156 review K1): same identity, and every attempt
                    // the entry knew (now including this one) with the same deadline; the fuller record is kept.
                    var known = e.operation == null ? null : e.operation.handedOut(b.hash,b.to);
                    if (known == null || op == null || !known.identity().equals(op.identity())
                            || !op.attempts().entrySet().containsAll(known.attempts().entrySet())) throw KeyedOperation.mismatch();
                    e = new Entry(e.id,b,state,slot,op);
                    staged.put(e.id,e);
                    live.put(b.hash,e);
                }
            } else {
                e = new Entry(Long.toString(++lastId), b, state, slot, op);
                staged.put(e.id, e);
                live.put(b.hash, e);
            }
            ids.add(e.id);
        }
        return new Transport.Reply(out, () -> {
            entries = staged;
            reconcile = !complete;
        });
    }
}
