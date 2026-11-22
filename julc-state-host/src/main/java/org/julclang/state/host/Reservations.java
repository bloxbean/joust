package org.julclang.state.host;

import com.bloxbean.cardano.client.transaction.spec.*;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.julclang.state.runtime.ClaimStore;
import org.julclang.state.runtime.snapshot.Unsupported;
import java.util.*;
import static org.julclang.state.host.Wire.*;

/** D8. Final entries are retained with their original body evidence for a subsequent rewind. */
final class Reservations implements ClaimStore {
    enum State { prepared, shallow, confirmed, expired, discarded }
    record Body(String hash, Set<String> inputs, Set<String> collateral, long from, long to) {
        static Body of(Transaction tx, String hash, int network) {
            var b = tx.getBody();
            if (b.getTtl() <= 0) throw HostError.bad("unbounded", "transaction has no upper bound");
            // D4 also applies to bodies re-imported from the client's journal.
            for (var output : b.getOutputs()) Reads.checkAddress(output.getAddress(), network);
            if (b.getCollateralReturn() != null) Reads.checkAddress(b.getCollateralReturn().getAddress(), network);
            if (b.getWithdrawals() != null) {
                for (var withdrawal : b.getWithdrawals()) Reads.checkAddress(withdrawal.getRewardAddress(), network);
            }
            return new Body(hash, refs(b.getInputs()), refs(b.getCollateral()), b.getValidityStartInterval(), b.getTtl());
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
    record Entry(String id, Body body, State state, Long slot) {
        Entry as(State state, Long slot) { return new Entry(id, body, state, slot); }
        boolean live() { return state == State.prepared || state == State.shallow; }
    }
    private Map<String, Entry> entries = new LinkedHashMap<>();
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
        if (body.to <= tip) throw HostError.refusal("expiredOnArrival", "transaction is already past its upper bound");
        return new Entry(Long.toString(++lastId), body, State.prepared, null);
    }
    void commit(Entry e) { entries.put(e.id, e); }

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
                    whileLive.accept(() -> deepRollback = true);
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
                if (e.state != State.prepared && e.state != State.discarded) throw conflict();
                yield e.as(State.discarded, null);
            }
            default -> throw bad("unknown settlement status");
        };
        Entry finalState = reconcile ? next : advance(next, tip);
        return new Transport.Reply(obj().put("state", finalState.state.name()), () -> {
            commit(finalState);
            observe(tip);
        });
    }

    void rewind(long tip) {
        entries.replaceAll((id, e) -> switch (e.state) {
            case expired -> e.body.to > tip ? e.as(State.prepared, null) : e;
            case confirmed, shallow -> e.slot > tip ? e.as(State.prepared, null) : e.as(State.shallow, e.slot);
            default -> e;
        });
        reconcile = true;
    }

    Transport.Reply restore(JsonNode p) {
        if (!reconcile) throw HostError.bad("restoreClosed", "restore is closed");
        fields(p, "transactions", "complete");
        boolean complete = bool(p, "complete");
        if (!p.path("transactions").isArray()) throw bad("transactions must be an array");
        // One pass indexes the live bodies by hash, so a batch costs linear time in the table and the batch, and only the
        // batch's additions are staged: restore never changes an existing entry (#149 Codex).
        var live = new HashMap<String, Entry>();
        for (var e : entries.values()) if (e.live()) live.putIfAbsent(e.body.hash, e);
        var added = new LinkedHashMap<String, Entry>();
        var out = obj();
        var ids = out.putArray("reservations");
        for (var t : p.get("transactions")) {
            fields(t, "cbor", "state", "slot");
            Body b = Body.decode(str(t, "cbor"), network);
            State state = switch (str(t, "state")) {
                case "prepared" -> State.prepared;
                case "shallow" -> State.shallow;
                default -> throw bad("invalid restore state");
            };
            Long slot = state == State.shallow ? inclusion(b, t) : null;
            if (slot == null && t.has("slot")) throw bad("prepared transaction has no inclusion slot");
            Entry e = live.get(b.hash);
            if (e != null) {
                if (e.state != state || !Objects.equals(e.slot, slot)) throw conflict();
            } else {
                e = new Entry(Long.toString(++lastId), b, state, slot);
                added.put(e.id, e);
                live.put(b.hash, e);
            }
            ids.add(e.id);
        }
        return new Transport.Reply(out, () -> {
            entries.putAll(added);
            reconcile = !complete;
        });
    }
}
