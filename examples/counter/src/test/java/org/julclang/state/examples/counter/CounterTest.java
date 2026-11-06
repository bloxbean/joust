package org.julclang.state.examples.counter;

import org.julclang.state.examples.counter.generated.CounterValidator;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.core.PlutusData;
import org.julclang.ledger.*;
import org.julclang.testkit.ContractTest;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two levels of testing the same Counter source:
 *  1. Plain JVM unit tests of Counter (no chain, no compiler) -- what the developer writes.
 *  2. The generated CounterValidator compiled to UPLC and evaluated in the JuLC VM against
 *     transactions shaped like the ones CounterClient builds (and some malicious ones).
 */
class CounterTest extends ContractTest {

    static final byte[] SEED_TX = bytes(32, 7);
    static final BigInteger SEED_IX = BigInteger.ZERO;
    static final byte[] SCRIPT_HASH = bytes(28, 0xA1);   // in tests: stands in for policy id == script hash
    static final byte[] ALICE = bytes(28, 0x11);          // owner
    static final byte[] BOB = bytes(28, 0x22);
    static final byte[] STATE = "STATE".getBytes();

    @BeforeAll
    static void setup() { initCrypto(); }

    // ------------------------------------------------------------------ 1. JVM
    @Nested
    class PlainJava {
        @Test
        void incrementAndReset() {
            var s0 = Counter.init(ALICE);
            var s1 = Counter.increment(s0, BigInteger.valueOf(5), BOB);
            var s2 = Counter.increment(s1, BigInteger.TWO, ALICE);
            assertEquals(BigInteger.valueOf(7), Counter.count(s2));
            assertEquals(BigInteger.ZERO, Counter.reset(s2, ALICE).count());
        }

        @Test
        void rulesFailOnJvmToo() {
            var s = Counter.init(ALICE);
            assertThrows(RuntimeException.class, () -> Counter.increment(s, BigInteger.ZERO, BOB));
            assertThrows(RuntimeException.class, () -> Counter.reset(s, BOB));
        }
    }

    // ------------------------------------------------------------------ 2. on-chain
    @Nested
    class OnChain {

        @Test
        void deploy_ok() throws Exception {
            var r = evalCreate(ALICE, ALICE, Counter.init(ALICE), true, BigInteger.ONE);
            assertSuccess(r);
            log("deploy_ok", r);
        }

        @Test
        void deploy_withoutSeed_fails() throws Exception {
            assertFailure(evalCreate(ALICE, ALICE, Counter.init(ALICE), false, BigInteger.ONE));
        }

        @Test
        void deploy_twoTokens_fails() throws Exception {
            assertFailure(evalCreate(ALICE, ALICE, Counter.init(ALICE), true, BigInteger.TWO));
        }

        @Test
        void deploy_wrongInitialState_fails() throws Exception {
            var bogus = new Counter.State(BigInteger.valueOf(1000), ALICE);
            assertFailure(evalCreate(ALICE, ALICE, bogus, true, BigInteger.ONE));
        }

        @Test
        void increment_byAnyone_ok() throws Exception {
            var cur = new Counter.State(BigInteger.valueOf(3), ALICE);
            var next = Counter.increment(cur, BigInteger.TEN, BOB);           // client computes this on the JVM
            var r = evalSpend(cur, new CounterValidator.Increment(BigInteger.TEN, BOB), BOB, next, true);
            assertSuccess(r);
            log("increment_ok", r);
        }

        @Test
        void increment_wrongResult_fails() throws Exception {
            var cur = new Counter.State(BigInteger.valueOf(3), ALICE);
            var cheat = new Counter.State(BigInteger.valueOf(999), ALICE);   // claims a different new state
            assertFailure(evalSpend(cur, new CounterValidator.Increment(BigInteger.TEN, BOB), BOB, cheat, true));
        }

        @Test
        void increment_nonPositive_fails() throws Exception {
            var cur = new Counter.State(BigInteger.valueOf(3), ALICE);
            assertFailure(evalSpend(cur, new CounterValidator.Increment(BigInteger.ZERO, BOB), BOB, cur, true));
        }

        @Test
        void increment_callerDidNotSign_fails() throws Exception {
            var cur = new Counter.State(BigInteger.valueOf(3), ALICE);
            var next = Counter.increment(cur, BigInteger.ONE, BOB);
            // redeemer claims caller = BOB but only ALICE signed
            assertFailure(evalSpend(cur, new CounterValidator.Increment(BigInteger.ONE, BOB), ALICE, next, true));
        }

        @Test
        void reset_byOwner_ok() throws Exception {
            var cur = new Counter.State(BigInteger.valueOf(42), ALICE);
            var r = evalSpend(cur, new CounterValidator.Reset(ALICE), ALICE, Counter.reset(cur, ALICE), true);
            assertSuccess(r);
            log("reset_ok", r);
        }

        @Test
        void reset_byNonOwner_fails() throws Exception {
            var cur = new Counter.State(BigInteger.valueOf(42), ALICE);
            var next = new Counter.State(BigInteger.ZERO, ALICE);
            assertFailure(evalSpend(cur, new CounterValidator.Reset(BOB), BOB, next, true));
        }

        @Test
        void increment_stealThreadToken_fails() throws Exception {
            var cur = new Counter.State(BigInteger.valueOf(3), ALICE);
            var next = Counter.increment(cur, BigInteger.ONE, BOB);
            // continuing output without the thread token
            assertFailure(evalSpend(cur, new CounterValidator.Increment(BigInteger.ONE, BOB), BOB, next, false));
        }
    }

    // ------------------------------------------------------------------ helpers

    EvalResult evalCreate(byte[] caller, byte[] signer, Counter.State outState,
                          boolean includeSeed, BigInteger qty) throws Exception {
        var program = compiledProgram();
        var policy = new PolicyId(SCRIPT_HASH);
        var ctx = mintingContext(policy)
                .redeemer(toData(new CounterValidator.Create(caller)))
                .signer(signer)
                .mint(Value.singleton(policy, new TokenName(STATE), qty))
                .output(stateOut(outState, true));
        if (includeSeed) {
            ctx = ctx.input(new TxInInfo(new TxOutRef(new TxId(SEED_TX), SEED_IX), walletOut()));
        }
        return evaluate(program, ctx.buildPlutusData());
    }

    EvalResult evalSpend(Counter.State cur, CounterValidator.CounterAction action, byte[] signer,
                         Counter.State outState, boolean outHasToken) throws Exception {
        var program = compiledProgram();
        var ref = new TxOutRef(new TxId(bytes(32, 9)), BigInteger.ZERO);
        var curData = toData(cur);
        var ctx = spendingContext(ref, curData)
                .redeemer(toData(action))
                .signer(signer)
                .input(new TxInInfo(ref, stateOut(cur, true)))
                .output(stateOut(outState, outHasToken));
        return evaluate(program, ctx.buildPlutusData());
    }

    org.julclang.core.Program compiledProgram() throws Exception {
        var compiled = compileValidator(CounterValidator.class);
        assertFalse(compiled.hasErrors(), compiled.toString());
        return compiled.program().applyParams(PlutusData.bytes(SEED_TX), PlutusData.integer(SEED_IX));
    }

    static TxOut stateOut(Counter.State s, boolean withToken) {
        var addr = new Address(new Credential.ScriptCredential(new ScriptHash(SCRIPT_HASH)), Optional.empty());
        var v = Value.lovelace(BigInteger.valueOf(2_000_000));
        if (withToken) v = v.merge(Value.singleton(new PolicyId(SCRIPT_HASH), new TokenName(STATE), BigInteger.ONE));
        return new TxOut(addr, v, new OutputDatum.OutputDatumInline(toData(s)), Optional.empty());
    }

    static TxOut walletOut() {
        return new TxOut(new Address(new Credential.PubKeyCredential(new PubKeyHash(ALICE)), Optional.empty()),
                Value.lovelace(BigInteger.valueOf(10_000_000)), new OutputDatum.NoOutputDatum(), Optional.empty());
    }

    /** Same record → Data path the generated client uses. */
    static PlutusData toData(Object record) {
        return PlutusDataAdapter.fromClientLib(PlutusDataAdapter.convert(record));
    }

    static byte[] bytes(int len, int fill) {
        var b = new byte[len];
        java.util.Arrays.fill(b, (byte) fill);
        return b;
    }

    static void log(String name, EvalResult r) {
        var b = r.budgetConsumed();
        System.out.println("[" + name + "] CPU=" + b.cpuSteps() + " MEM=" + b.memoryUnits());
    }
}
