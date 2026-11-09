package org.julclang.state.examples.rollup;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import org.julclang.core.types.JulcList;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The off-chain side of the spike (ADR-0006 D3): a sequencer that applies signed orders with {@link Ledger#step} (the
 * same code a fraud proof runs on-chain) and cuts checkpoints, and a watcher that re-executes a checkpoint and builds
 * the fraud proof for its first wrong step.
 */
public final class Sequencer {

    /** An L2 account: an ed25519 key pair. */
    public record Signer(byte[] publicKey, byte[] privateKey) {
        public static Signer generate() {
            try {
                var keys = KeyGenUtil.generateKey();
                return new Signer(keys.getVkey().getBytes(), keys.getSkey().getBytes());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        public byte[] sign(byte[] domain, Ledger.Order o) {
            return CryptoConfiguration.INSTANCE.getSigningProvider().sign(Ledger.message(domain, o), privateKey);
        }
    }

    /** A checkpoint: its steps (committed on-chain in the redeemer), and the log hash and new state they imply. */
    public record Checkpoint(byte[] log, BigInteger count, byte[] newHash, JulcList<Ledger.Step> steps) {}

    /** A fraud proof: which step, and the state before it. */
    public record Proof(BigInteger index, Ledger.Book pre) {}

    private Ledger.Book book;
    private final List<Ledger.Step> pending = new ArrayList<>();

    public Sequencer(Ledger.Book genesis) {
        this.book = genesis;
    }

    public Ledger.Book book() {
        return book;
    }

    /** Applies an order honestly: an invalid one is a recorded no-op. */
    public void submit(Ledger.Order o, byte[] sig) {
        Ledger.Book next = Ledger.step(book, o, sig);
        pending.add(new Ledger.Step(o, sig, Ledger.hash(next)));
        book = next;
    }

    /** A dishonest sequencer: records the order but claims {@code claimed} as the result, and continues from it. */
    public void submitForged(Ledger.Order o, byte[] sig, Ledger.Book claimed) {
        pending.add(new Ledger.Step(o, sig, Ledger.hash(claimed)));
        book = claimed;
    }

    /** Cuts a checkpoint of the pending steps. */
    public Checkpoint checkpoint() {
        JulcList<Ledger.Step> steps = JulcList.empty();
        for (int i = pending.size() - 1; i >= 0; i--) steps = steps.prepend(pending.get(i));
        pending.clear();
        return new Checkpoint(Ledger.logHash(steps), Ledger.count(steps), Ledger.lastPost(steps, Ledger.hash(book)), steps);
    }

    /** Forgets the steps of a slashed checkpoint: the state goes back to {@code finalBook}. */
    public void revertTo(Ledger.Book finalBook) {
        pending.clear();
        book = finalBook;
    }

    /**
     * Watcher: re-executes the committed steps from {@code finalBook}. Returns the proof for the first step whose
     * post-hash is wrong, or empty if the checkpoint is honest.
     */
    public static Optional<Proof> audit(Ledger.Book finalBook, JulcList<Ledger.Step> steps) {
        Ledger.Book pre = finalBook;
        BigInteger i = BigInteger.ZERO;
        for (Ledger.Step s : steps) {
            Ledger.Book post = Ledger.step(pre, s.order(), s.sig());
            if (!Arrays.equals(Ledger.hash(post), s.post())) return Optional.of(new Proof(i, pre));
            pre = post;
            i = i.add(BigInteger.ONE);
        }
        return Optional.empty();
    }

    /** The state after the committed steps, as a watcher reconstructs it (to follow finalised checkpoints). */
    public static Ledger.Book replay(Ledger.Book from, JulcList<Ledger.Step> steps) {
        Ledger.Book b = from;
        for (Ledger.Step s : steps) b = Ledger.step(b, s.order(), s.sig());
        return b;
    }
}
