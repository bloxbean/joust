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
    final Function<Ctx, Object> redeemer;
    final Object burnRedeemer;

    private Call(Builder<S> b) {
        this.name = Objects.requireNonNull(b.name);
        this.kind = Objects.requireNonNull(b.kind);
        this.caller = Objects.requireNonNull(b.caller, "caller");
        this.received = b.received;
        this.usesCtx = b.usesCtx;
        this.signers = List.copyOf(b.signers);
        this.notBefore = List.copyOf(b.notBefore);
        this.notAfter = List.copyOf(b.notAfter);
        this.run = Objects.requireNonNull(b.run);
        this.redeemer = Objects.requireNonNull(b.redeemer);
        this.burnRedeemer = b.burnRedeemer;
        if (received.signum() < 0) throw new IllegalArgumentException(name + ": received must not be negative");
        if (kind == Kind.DESTROY && burnRedeemer == null) throw new IllegalArgumentException("destroy needs a burn redeemer");
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
        private Function<Ctx, Object> redeemer;
        private Object burnRedeemer;

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
        public Builder<S> redeemer(Function<Ctx, Object> redeemer) { this.redeemer = redeemer; return this; }

        public Call<S> build() {
            return new Call<>(this);
        }
    }
}
