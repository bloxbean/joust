package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import org.julclang.state.api.Ctx;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * One {@code @Action} or {@code @Destroy} call: filled in by a generated client method.
 */
public final class Call<S> {

    public enum Kind { ACTION, DESTROY }

    /** An owned method run with the instance's state (read through a reference input). */
    @FunctionalInterface
    public interface SharedRun<S> {
        Transition<S> apply(S current, Object shared, Ctx ctx);
    }

    /** Builds the spend redeemer from the call's Ctx. */
    @FunctionalInterface
    public interface ActRedeemer {
        Object make(Ctx ctx);
    }

    /**
     * A method of an asset-aware contract (ADR-0007): the current state or object, the instance state for
     * {@code @Shared} (else null), the Ctx and the Funds.
     */
    @FunctionalInterface
    public interface AssetRun<S> {
        Transition<S> apply(S current, Object shared, Ctx ctx, org.julclang.state.api.Funds funds);
    }

    /** {@code @SignedBy(field)}: the state field holding the key hash that must sign. */
    public record SignerRule<S>(String field, Function<S, byte[]> key) {}

    /** {@code @ValidAfter(field)} / {@code @ValidBefore(field)}: the state field holding a POSIX-ms deadline. */
    public record TimeRule<S>(String field, Function<S, BigInteger> time) {}

    final String name;
    final Kind kind;
    final Account caller;
    final BigInteger received;
    final boolean usesCtx;
    final List<SignerRule<S>> signers;
    final List<TimeRule<S>> notBefore;
    final List<TimeRule<S>> notAfter;
    final BiFunction<S, Ctx, Transition<S>> run;
    final SharedRun<S> sharedRun;
    final ActRedeemer redeemer;
    final Object burnRedeemer;
    final org.julclang.ledger.Value receivedAssets;
    final AssetRun<S> assetRun;
    final boolean needsShared;
    final Function<org.julclang.ledger.TxOutRef, Object> byAction;
    final List<Account> cosigners;
    final Authorization authorization;
    final BiFunction<Ctx, Transition<S>, Object> redeemerWith;

    private Call(Builder<S> b) {
        this.name = Objects.requireNonNull(b.name);
        this.kind = Objects.requireNonNull(b.kind);
        this.caller = Objects.requireNonNull(b.caller, "caller");
        this.received = b.received;
        this.usesCtx = b.usesCtx;
        this.signers = List.copyOf(b.signers);
        this.notBefore = List.copyOf(b.notBefore);
        this.notAfter = List.copyOf(b.notAfter);
        this.run = b.run;
        this.sharedRun = b.sharedRun;
        this.assetRun = b.assetRun;
        this.needsShared = b.needsShared || b.sharedRun != null;
        this.receivedAssets = b.receivedAssets;
        this.byAction = b.byAction;
        this.cosigners = List.copyOf(b.cosigners);
        this.authorization = b.authorization;
        this.redeemerWith = b.redeemerWith;
        if (run == null && sharedRun == null && assetRun == null) throw new IllegalArgumentException(name + ": no run");
        this.redeemer = b.redeemer != null ? b.redeemer : (ctx -> { throw new IllegalStateException("redeemerWith only"); });
        if (b.redeemer == null && b.redeemerWith == null) throw new NullPointerException(name + ": no redeemer");
        this.burnRedeemer = b.burnRedeemer;
        if (received.signum() < 0) throw new IllegalArgumentException(name + ": received must not be negative");
        if (kind == Kind.DESTROY && burnRedeemer == null) throw new IllegalArgumentException("destroy needs a burn redeemer");
    }

    /** The spend redeemer of this call, given the method's result. */
    Object redeemerFor(Ctx ctx, Transition<S> t) {
        return redeemerWith != null ? redeemerWith.apply(ctx, t) : redeemer.make(ctx);
    }

    /** The extra signing accounts: co-signers and an authorization key. */
    List<Account> extraSigners() {
        var all = new ArrayList<Account>(cosigners);
        if (authorization != null) all.addAll(authorization.signers());
        return all;
    }

    /** Adds the authorization's zero withdrawal, if it is a script (ADR-0009 D3). */
    void applyAuthorization(com.bloxbean.cardano.client.quicktx.ScriptTx tx) {
        if (authorization == null || authorization.rewardAddress() == null) return;
        tx.withdraw(authorization.rewardAddress(), java.math.BigInteger.ZERO,
                org.julclang.clientlib.PlutusDataAdapter.convert(authorization.redeemer()));
        tx.attachRewardValidator(authorization.script());
    }

    boolean usesTime() {
        return usesCtx || !notBefore.isEmpty() || !notAfter.isEmpty();
    }

    public static <S> Builder<S> action(String name, Account caller) {
        return new Builder<S>(name, Kind.ACTION, caller);
    }

    public static <S> Builder<S> destroy(String name, Account caller, Object burnRedeemer) {
        var b = new Builder<S>(name, Kind.DESTROY, caller);
        b.burnRedeemer = burnRedeemer;
        return b;
    }

    public static final class Builder<S> {
        private final String name;
        private final Kind kind;
        private final Account caller;
        private BigInteger received = BigInteger.ZERO;
        private boolean usesCtx;
        private final List<SignerRule<S>> signers = new ArrayList<>();
        private final List<TimeRule<S>> notBefore = new ArrayList<>();
        private final List<TimeRule<S>> notAfter = new ArrayList<>();
        private BiFunction<S, Ctx, Transition<S>> run;
        private SharedRun<S> sharedRun;
        private ActRedeemer redeemer;
        private Object burnRedeemer;
        private org.julclang.ledger.Value receivedAssets = org.julclang.state.api.Assets.none();
        private AssetRun<S> assetRun;
        private boolean needsShared;
        private Function<org.julclang.ledger.TxOutRef, Object> byAction;
        private final List<Account> cosigners = new ArrayList<>();
        private Authorization authorization;
        private BiFunction<Ctx, Transition<S>, Object> redeemerWith;

        private Builder(String name, Kind kind, Account caller) {
            this.name = name;
            this.kind = kind;
            this.caller = caller;
        }

        public Builder<S> received(BigInteger lovelace) { this.received = lovelace; return this; }
        public Builder<S> usesCtx(boolean usesCtx) { this.usesCtx = usesCtx; return this; }
        public Builder<S> signedBy(String field, Function<S, byte[]> key) { signers.add(new SignerRule<>(field, key)); return this; }
        public Builder<S> validAfter(String field, Function<S, BigInteger> t) { notBefore.add(new TimeRule<>(field, t)); return this; }
        public Builder<S> validBefore(String field, Function<S, BigInteger> t) { notAfter.add(new TimeRule<>(field, t)); return this; }
        public Builder<S> run(BiFunction<S, Ctx, Transition<S>> run) { this.run = run; return this; }
        /** An owned method that also reads the instance state ({@code @Shared}; ADR-0003). */
        public Builder<S> runShared(SharedRun<S> run) { this.sharedRun = run; return this; }
        public Builder<S> redeemer(ActRedeemer redeemer) { this.redeemer = redeemer; return this; }
        /** Native assets the caller offers ({@code Funds.received}; ADR-0007). */
        public Builder<S> receivedAssets(org.julclang.ledger.Value assets) { this.receivedAssets = assets; return this; }
        /** A method of an asset-aware contract; {@code shared}: it reads the instance state (@Shared). */
        public Builder<S> runAssets(boolean shared, AssetRun<S> run) { this.assetRun = run; this.needsShared = shared; return this; }
        /** The {@code ByAction} mint redeemer for methods that mint user tokens (ADR-0007 D4). */
        public Builder<S> byAction(Function<org.julclang.ledger.TxOutRef, Object> make) { this.byAction = make; return this; }
        /** Further keys that sign the transaction ({@code @Quorum}; ADR-0009 D2). */
        public Builder<S> cosigners(Account... accounts) { if (accounts != null) cosigners.addAll(List.of(accounts)); return this; }
        /** How {@code @AuthorizedBy} is proven (ADR-0009 D3). */
        public Builder<S> authorization(Authorization auth) { this.authorization = auth; return this; }
        /** A redeemer built from the Ctx and the method's result (it carries the events; ADR-0009 D5). */
        public Builder<S> redeemerWith(BiFunction<Ctx, Transition<S>, Object> make) { this.redeemerWith = make; return this; }

        public Call<S> build() {
            return new Call<>(this);
        }
    }
}
