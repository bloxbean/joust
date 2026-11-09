package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.state.runtime.Designation.Designated;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** What keyed lists and objects share (ADR-0016 D7): the designated input, link windows, argument sizes, retries. */
final class KeyedObjects {

    private KeyedObjects() {}

    // ------------------------------------------------------------------ the series of attempts (D7)

    /** Thrown by a composition's first pass when a keyed call takes part and nothing is designated yet. */
    static final class NeedsDesignated extends RuntimeException {
        /** The inputs the first pass collected: not to be designated. */
        final Set<String> avoid;

        NeedsDesignated(Set<String> avoid) {
            super(null, null, false, false);
            this.avoid = Set.copyOf(avoid);
        }
    }

    /**
     * Runs a keyed operation (D7) as a {@link #series}; inside a composition, the composition designates and retries.
     */
    static <T> T operation(StateRuntime rt, Account payer, String what, Function<Designated, T> attempt) {
        var cap = TxRunner.capture();
        if (cap != null) {
            cap.keyed = true;
            return attempt.apply(null);
        }
        return series(rt, payer, what, false, attempt);
    }

    /**
     * The attempts of one logical operation (D7). It designates an input (at once, or, when {@code lazy}, when the first
     * pass asks for it with {@link NeedsDesignated}), which every attempt spends. After a definite rejection it rebuilds
     * the whole call (window, JVM results, references, anchor) while the designated input can be spent: always after a
     * rejection or a transient refusal; after a script or build failure only if an input it spent has moved (otherwise
     * the failure is deterministic). An attempt whose fate is unknown is never rebuilt ({@link PendingTransactionException}).
     * <p>The series ends in a failure only once no attempt it handed out can still land: it waits until the provider
     * has seen the end of every attempt's window and then finds the designated input unspent; otherwise the outcome is
     * pending (F51, R-D1). A pending operation keeps its designated input reserved until those windows have passed.
     */
    static <T> T series(StateRuntime rt, Account payer, String what, boolean lazy, Function<Designated, T> attempt) {
        if (!lazy) refuseWallet(payer, what);
        Designated d = lazy ? null : Designation.reserve(rt, payer, what, Set.of());
        // True once the outcome is known for certain (landed, or no handed-out attempt can land): only then is the
        // designated input released at once; otherwise it stays reserved until the attempts' windows have passed.
        boolean definite = false;
        try {
            int retries = 0;
            while (true) {
                Throwable thrown;
                try {
                    if (d != null && retries > 0) requireUnspent(rt, d, what);
                    T result = attempt.apply(d);
                    definite = true;
                    return result;
                } catch (NeedsDesignated n) {
                    if (d != null) throw new IllegalStateException(what + ": designated twice");
                    refuseWallet(payer, what);
                    d = Designation.reserve(rt, payer, what, n.avoid);
                    continue;
                } catch (Throwable t) {
                    thrown = t;
                }
                // Everything after the attempt (classifying, logging, the back-off, settling) runs in this one guard.
                // Its only outcomes: a retry, or an end that is either definite (no attempt handed out, or settle's
                // checks passed) or pending with the operation's record. Anything unexpected after a hand-out ends
                // pending, with the original failure kept (F69, F62).
                Throwable end;
                try {
                    Next next = next(rt, d, what, thrown, retries);
                    if (next.retry) {
                        retries++;
                        continue;
                    }
                    end = next.end;
                } catch (Throwable unexpected) {
                    if (d == null || d.attempts().isEmpty()) {
                        unexpected.addSuppressed(thrown);
                        end = unexpected;
                    } else {
                        var p = new PendingTransactionException(what + ": " + TxRunner.rootMessage(unexpected) + " after an"
                                + " attempt was handed out, so it may have landed", d.attemptHashes(), d.ref());
                        p.initCause(thrown);
                        p.addSuppressed(unexpected);
                        end = p;
                    }
                }
                definite = !(end instanceof PendingTransactionException) && (d == null || d.attempts().isEmpty()
                        || end instanceof Settled);
                throw rethrow(end instanceof Settled settled ? settled.failure : end);
            }
        } finally {
            if (d != null) {
                if (definite || d.attempts().isEmpty()) d.release();
                else d.releaseAt(lingerUntil(rt, d));
            }
        }
    }

    /** What follows an attempt that threw: another attempt, or the series' end. */
    private record Next(boolean retry, Throwable end) {}

    /** A failure settle confirmed definite (the windows passed, the designated input unspent). */
    private static final class Settled extends RuntimeException {
        final RuntimeException failure;

        Settled(RuntimeException failure) {
            super(null, null, false, false);
            this.failure = failure;
        }
    }

    private static Next next(StateRuntime rt, Designated d, String what, Throwable thrown, int retries) {
        if (thrown instanceof PendingTransactionException p) {
            if (d == null || p.designatedInput() != null) return new Next(false, p);
            var e = new PendingTransactionException(p.getMessage(), d.attemptHashes(), d.ref());
            e.initCause(p);
            return new Next(false, e);
        }
        if (d == null) return new Next(false, thrown);   // no keyed call took part (a plain composition): as before
        RuntimeException failure;
        if (thrown instanceof SubmitException e) {
            boolean again = switch (e.kind) {
                case REJECTED, TRANSIENT -> true;
                case SCRIPT, BUILD -> inputsMoved(rt, d);
            };
            if (again && retries < rt.maxRetries()) {
                log(rt, what + ": attempt " + (retries + 1) + " refused (" + e.getMessage() + "); rebuilding with the same"
                        + " designated input " + d.ref());
                try {
                    Thread.sleep(2000);   // let the provider's index catch up with whatever won (a neighbour's relink)
                    return new Next(true, null);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    var interrupted = new JoustException(what + ": interrupted before rebuilding", ie);
                    interrupted.addSuppressed(e);
                    failure = interrupted;
                }
            } else if (again) {
                failure = new ContentionException(what + ": refused " + (retries + 1) + " times", e, d.attemptHashes(), d.ref());
            } else {
                failure = e;
            }
        } else if (thrown instanceof RuntimeException e) {
            failure = e;
        } else {
            // An Error: after a hand-out it ends pending (it is the cause); before any, it propagates as it is.
            if (d.attempts().isEmpty()) return new Next(false, thrown);
            var p = new PendingTransactionException(what + ": " + thrown + " after an attempt was handed out, so it may have"
                    + " landed", d.attemptHashes(), d.ref());
            p.initCause(thrown);
            return new Next(false, p);
        }
        RuntimeException end = settle(rt, d, what, failure);
        if (end instanceof PendingTransactionException) return new Next(false, end);
        return new Next(false, d.attempts().isEmpty() ? end : new Settled(end));
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
        throw (E) t;
    }

    /** The runtime's log, which must not turn a pending outcome into another failure (F62). */
    private static void log(StateRuntime rt, String message) {
        try {
            rt.log(message);
        } catch (RuntimeException ignored) {
            // a failing log consumer is not the operation's outcome
        }
    }

    /** A WalletAccount cannot sign here; it fails closed, and wallets prepare keyed calls only from M3 (R-C21). */
    private static void refuseWallet(Account payer, String what) {
        if (payer instanceof WalletAccount) {
            throw new JoustException(what + ": a keyed operation needs a key-holding payer here: a WalletAccount fails closed,"
                    + " and wallets prepare keyed calls from ADR-0016 M3");
        }
    }

    /** Before a rebuild: the designated input must still be spendable (D7). */
    private static void requireUnspent(StateRuntime rt, Designated d, String what) {
        if (d.unspent(rt)) return;
        if (d.attempts().isEmpty()) {
            throw new JoustException(what + ": the designated input " + d.ref() + " was spent by another transaction before"
                    + " any attempt was handed out; nothing of this operation can land (ADR-0016 D7)");
        }
        throw new PendingTransactionException(what + ": the designated input is spent, so an earlier attempt may have"
                + " landed; not rebuilding (ADR-0016 D7)", d.attemptHashes(), d.ref());
    }

    /**
     * The exception a series ending in {@code failure} raises: {@code failure} itself if no attempt was handed out, or
     * once the provider has seen the end of every attempt's window and the designated input is still unspent; otherwise
     * pending.
     */
    static RuntimeException settle(StateRuntime rt, Designated d, String what, RuntimeException failure) {
        if (d == null || d.attempts().isEmpty()) return failure;
        try {
            return settleHandedOut(rt, d, what, failure);
        } catch (RuntimeException e) {
            // Whatever went wrong while settling, an attempt may still land.
            var p = new PendingTransactionException(what + ": " + failure.getMessage() + "; could not settle ("
                    + TxRunner.rootMessage(e) + "), so an attempt may have landed", d.attemptHashes(), d.ref());
            p.initCause(failure);
            return p;
        }
    }

    private static RuntimeException settleHandedOut(StateRuntime rt, Designated d, String what, RuntimeException failure) {
        ChainTime time = rt.chainTime();
        // The provider must have passed the end of every attempt's window, by the settlement depth (R-E7).
        long last = Math.addExact(d.lastToSlot(), rt.settlementSlots());   // overflow: settle fails closed (pending; F75)
        log(rt, what + ": " + failure.getMessage() + "; an attempt was handed out, so waiting for the provider to pass slot "
                + last + " (the end of its window and the settlement depth) before reporting a failure (ADR-0016 D7)");
        long deadline = Math.max(System.currentTimeMillis(), TxRunner.safeMs(time, last)) + rt.confirmTimeout().toMillis();
        while (true) {
            long tip;
            try {
                tip = time.tipSlot();
            } catch (RuntimeException e) {
                tip = Long.MIN_VALUE;
            }
            if (tip >= last) break;
            if (System.currentTimeMillis() > deadline) {
                return new PendingTransactionException(what + ": " + failure.getMessage() + "; the provider did not reach the"
                        + " end of the attempts' windows, so one may have landed", d.attemptHashes(), d.ref());
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return new PendingTransactionException(what + ": interrupted while an attempt may still land ("
                        + failure.getMessage() + ")", d.attemptHashes(), d.ref());
            }
        }
        if (d.unspent(rt)) return failure;
        return new PendingTransactionException(what + ": " + failure.getMessage() + "; but the designated input is spent,"
                + " so an earlier attempt may have landed", d.attemptHashes(), d.ref());
    }

    /** When a pending operation's designated input may be released: after its attempts' windows (and a margin). */
    static long lingerUntil(StateRuntime rt, Designated d) {
        long last = d.lastToSlot();
        if (last == Long.MIN_VALUE) return System.currentTimeMillis();
        try {
            long ms = rt.chainTime().slotToPosixMs(Math.addExact(last, rt.settlementSlots()));
            return Math.addExact(Math.max(ms, System.currentTimeMillis()), 10_000);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;   // fails closed: reserved for good rather than released early (F75)
        }
    }

    /**
     * Whether an input the latest attempt spent, or a reference input it read (F68), has moved (is no longer at its
     * address), so that a script or build failure may have come from contention. Each script input holds a unique token, looked up by unit; an unreadable
     * answer counts as moved (a rebuild is still guarded by the designated input).
     */
    static boolean inputsMoved(StateRuntime rt, Designated d) {
        for (Utxo u : d.dependencies()) {
            try {
                if (u == null || u.getTxHash() == null || u.getAddress() == null || u.getAmount() == null) return true;
                String unit = null;
                for (var a : u.getAmount()) {
                    if (a != null && a.getUnit() != null && !a.getUnit().equals("lovelace") && a.getQuantity() != null
                            && a.getQuantity().compareTo(BigInteger.ONE) == 0) {
                        unit = a.getUnit();
                        break;
                    }
                }
                boolean found = false;
                if (unit != null) {
                    var r = rt.backend().getUtxoService().getUtxos(u.getAddress(), unit, 10, 1);
                    if (!r.isSuccessful() || r.getValue() == null) return true;
                    for (Utxo x : r.getValue()) {
                        if (Designation.ref(x).equals(Designation.ref(u))) found = true;
                    }
                } else {
                    for (int page = 1; page <= 20 && !found; page++) {
                        var r = rt.backend().getUtxoService().getUtxos(u.getAddress(), 100, page);
                        if (!r.isSuccessful() || r.getValue() == null || r.getValue().isEmpty()) break;
                        for (Utxo x : r.getValue()) {
                            if (Designation.ref(x).equals(Designation.ref(u))) found = true;
                        }
                        if (r.getValue().size() < 100) break;
                    }
                }
                if (!found) return true;
            } catch (Exception e) {
                return true;   // unreadable: counts as moved (a rebuild is still guarded by the designated input)
            }
        }
        return false;
    }

    /**
     * {@code need} lovelace for an output that holds {@code have}: the ledger minimum computed from the provider's
     * protocol parameters. The top-up the payer adds is bounded by the runtime's allowance (R-C8): a provider that
     * inflates {@code coinsPerUTxOByte} cannot make the wallet pay a neighbour's node more than that.
     */
    static BigInteger topUp(StateRuntime rt, BigInteger have, BigInteger need, String what, String output) {
        BigInteger extra = need.subtract(have);
        if (extra.compareTo(BigInteger.valueOf(rt.topUpAllowance())) > 0) {
            throw new JoustException(what + ": " + output + " needs a top-up of " + extra + " lovelace to the ledger minimum the"
                    + " provider's protocol parameters give, above the allowance of " + rt.topUpAllowance() + " lovelace"
                    + " (StateRuntime.Builder.topUpAllowance); check the provider (ADR-0016 §2)");
        }
        return have.max(need);
    }

    // ------------------------------------------------------------------ link windows (D7, D9)

    /**
     * A relink's window, in slots: it starts at {@code minStart} (at least the tip), and no earlier than the first slot at
     * or after {@code linkedAt + C} when {@code C > 0} (rounded up, never down); it is at most {@code W}, the contract's
     * {@code maxValidityMs} and the runtime's TTL long. With page limits, its start is at most {@code maxStartSlots} and
     * its end at most {@code maxValiditySlots} after the tip: {@code prepareAt} is the first tip at which it may be
     * prepared (the start being then near enough), and the window is shortened to the end limit.
     */
    record LinkPlan(long fromSlot, long toSlot, long prepareAt) {}

    static LinkPlan plan(ChainTime time, long tip, long minStart, BigInteger linkedAt, long cooldownMs, long windowMs,
                         long maxValidityMs, long ttlMs, long[] pageLimits) {
        long slot = time.slotLengthMs();
        long from = Math.max(tip, minStart);
        if (cooldownMs > 0 && linkedAt != null) from = Math.max(from, firstSlotAtOrAfter(time, linkedAt.add(BigInteger.valueOf(cooldownMs))));
        long lengthMs = Math.min(windowMs, Math.min(maxValidityMs, ttlMs));
        long slots = lengthMs / slot;
        if (slots < 1) throw new JoustException("a link window of " + lengthMs + " ms is shorter than one " + slot + " ms slot");
        long to = from + slots;
        long prepareAt = tip;
        if (pageLimits != null) {
            prepareAt = Math.max(tip, from - pageLimits[0]);
            to = Math.min(to, prepareAt + pageLimits[1]);
            if (to <= from) throw new JoustException("the page's window limits leave no room for a link window");
        }
        return new LinkPlan(from, to, prepareAt);
    }

    /** A call's time rule, resolved for the current state: the POSIX ms in {@code field}. */
    record TimeBound(String field, BigInteger ms) {}

    /**
     * A link window within a removing destroy's own time rules (D5 keeps a plain destroy's {@code @ValidAfter} and
     * {@code @ValidBefore}; F55, R-C6): it ends at or before every {@code notAfter} and starts at or after every
     * {@code notBefore}. A rule that leaves no window is refused before anything is built, as {@link Window#open} does.
     */
    static LinkPlan withTimeRules(ChainTime time, LinkPlan p, List<TimeBound> notBefore, List<TimeBound> notAfter, String what) {
        long to = p.toSlot();
        for (var b : notAfter) {
            long last = time.posixMsToSlotFloor(b.ms().longValueExact());   // slotToPosix(last) <= the deadline
            if (last < to) to = last;
            if (to <= p.fromSlot()) {
                throw new org.julclang.state.api.RequireException(what + ": too late, the deadline in '" + b.field()
                        + "' leaves no link window (it would start at slot " + p.fromSlot() + ")");
            }
        }
        BigInteger fromMs = BigInteger.valueOf(time.slotToPosixMs(p.fromSlot()));
        for (var b : notBefore) {
            if (fromMs.compareTo(b.ms()) < 0) {
                throw new org.julclang.state.api.RequireException(what + ": too early, the time in '" + b.field()
                        + "' has not been reached by the link window's start");
            }
        }
        return new LinkPlan(p.fromSlot(), to, p.prepareAt());
    }

    /** A composition's one window must satisfy the removing destroy's time rules too (as {@link Window#open} checks). */
    static void checkTimeRules(Window w, List<TimeBound> notBefore, List<TimeBound> notAfter, String what) {
        for (var b : notAfter) {
            if (w.toMs().compareTo(b.ms()) > 0) {
                throw new org.julclang.state.api.RequireException(what + ": the composed window ends after the deadline in '"
                        + b.field() + "'");
            }
        }
        for (var b : notBefore) {
            if (w.fromMs().compareTo(b.ms()) < 0) {
                throw new org.julclang.state.api.RequireException(what + ": the composed window starts before '" + b.field() + "'");
            }
        }
    }

    /** The first slot whose time is at least {@code ms}. */
    static long firstSlotAtOrAfter(ChainTime time, BigInteger ms) {
        long s = time.posixMsToSlotFloor(ms.longValueExact());
        return time.slotToPosixMs(s) < ms.longValueExact() ? s + 1 : s;
    }

    static Window window(ChainTime time, long from, long to) {
        return new Window(from, to, BigInteger.valueOf(time.slotToPosixMs(from)), BigInteger.valueOf(time.slotToPosixMs(to)));
    }

    // ------------------------------------------------------------------ sizes (D2, D4c)

    /** The serialised size of {@code args} as one Plutus list. */
    static int argumentBytes(List<PlutusData> args) {
        try {
            var list = ListPlutusData.builder().plutusDataList(new java.util.ArrayList<>(args)).build();
            return com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.serialize(list.serialize()).length;
        } catch (Exception e) {
            throw new JoustException("cannot serialise arguments", e);
        }
    }

    /** The datum's serialised size, as {@code datumFits} measures it on chain (Plutus {@code serialiseData}). */
    static int datumBytes(PlutusData d) {
        try {
            return org.julclang.core.cbor.PlutusDataCborEncoder.encode(PlutusDataAdapter.fromClientLib(d)).length;
        } catch (Exception e) {
            throw new JoustException("cannot serialise a datum", e);
        }
    }

    static String hex(byte[] b) {
        return HexUtil.encodeHexString(b);
    }

    private static final class PlutusDataAdapter {
        static org.julclang.core.PlutusData fromClientLib(PlutusData d) {
            return org.julclang.clientlib.PlutusDataAdapter.fromClientLib(d);
        }
    }
}
