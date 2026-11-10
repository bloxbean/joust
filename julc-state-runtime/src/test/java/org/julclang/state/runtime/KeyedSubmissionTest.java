package org.julclang.state.runtime;

import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0016 D7's submission rules on the JVM (the review of M2): how relay answers combine (F50, R-C3, R-C10), the check
 * that every attempt spends its designated input (R-C9), the top-up allowance (R-C8).
 */
class KeyedSubmissionTest {

    static final byte[] CBOR = {(byte) 0x80};

    static Relay answer(Relay.Outcome o) {
        return cbor -> o;
    }

    static Relay throwing() {
        return cbor -> {
            throw new IllegalStateException("the relay's own bug");
        };
    }

    // ------------------------------------------------------------------ relays

    @Test
    void anUnknownAnswerWithoutAMessage_stillDominatesARejection() {
        // F50: UNKNOWN is tracked by answer, not by its (nullable) message.
        var e = assertThrows(PendingTransactionException.class, () -> TxRunner.deliver(
                List.of(answer(Relay.Outcome.unknown(null)), answer(Relay.Outcome.rejected("no"))), CBOR, "h1", "test"));
        assertEquals("h1", e.txHash());
        assertThrows(PendingTransactionException.class, () -> TxRunner.deliver(
                List.of(answer(Relay.Outcome.rejected("no")), answer(Relay.Outcome.unknown(null))), CBOR, "h1", "test"),
                "in either order");
    }

    @Test
    void aRelayThatThrowsOrAnswersNothing_isAnUnknownDelivery() {
        // R-C3: a throwing relay neither overrides an acceptance nor forces a rebuild.
        assertDoesNotThrow(() -> TxRunner.deliver(List.of(answer(Relay.Outcome.accepted()), throwing()), CBOR, "h", "test"));
        assertDoesNotThrow(() -> TxRunner.deliver(List.of(throwing(), answer(Relay.Outcome.accepted())), CBOR, "h", "test"));
        assertThrows(PendingTransactionException.class,
                () -> TxRunner.deliver(List.of(throwing(), answer(Relay.Outcome.rejected("no"))), CBOR, "h", "test"));
        assertThrows(PendingTransactionException.class,
                () -> TxRunner.deliver(List.of(cbor -> null, answer(Relay.Outcome.rejected("no"))), CBOR, "h", "test"));
        var calls = new ArrayList<String>();
        assertThrows(PendingTransactionException.class, () -> TxRunner.deliver(List.of(throwing(), cbor -> {
            calls.add("second");
            return Relay.Outcome.rejected("no");
        }), CBOR, "h", "test"));
        assertEquals(List.of("second"), calls, "every relay still gets the bytes");
    }

    @Test
    void onlyRejectionsByEveryRelay_areARejection_ofKindScriptWhenEveryReasonIsAScriptFailure() {
        var e = assertThrows(SubmitException.class, () -> TxRunner.deliver(List.of(
                answer(Relay.Outcome.rejected("HTTP 400: ... UtxosFailure (ValidationTagMismatch (IsValid True) (FailedUnexpectedly (PlutusFailure ...")),
                answer(Relay.Outcome.rejected("HTTP 400: ... PlutusFailure ..."))), CBOR, "h", "test"));
        assertEquals(SubmitException.Kind.SCRIPT, e.kind);
        e = assertThrows(SubmitException.class, () -> TxRunner.deliver(List.of(
                answer(Relay.Outcome.rejected("HTTP 400: ... PlutusFailure ...")),
                answer(Relay.Outcome.rejected("HTTP 400: ... BadInputsUTxO ..."))), CBOR, "h", "test"));
        assertEquals(SubmitException.Kind.REJECTED, e.kind, "an input that moved: contention");
    }

    @Test
    void aRejectionWithoutAReason_isStillARejection() {
        // F67: rejection presence is tracked apart from its (nullable) reason, in either order.
        var e = assertThrows(SubmitException.class, () -> TxRunner.deliver(
                List.of(answer(Relay.Outcome.rejected(null)), answer(Relay.Outcome.rejected(null))), CBOR, "h", "test"));
        assertEquals(SubmitException.Kind.REJECTED, e.kind);
        assertTrue(e.getMessage().contains("no reason given"), e.getMessage());
        for (var order : List.of(List.of(Relay.Outcome.rejected(null), Relay.Outcome.rejected("BadInputsUTxO")),
                List.of(Relay.Outcome.rejected("BadInputsUTxO"), Relay.Outcome.rejected(null)))) {
            var m = assertThrows(SubmitException.class, () -> TxRunner.deliver(
                    List.of(answer(order.get(0)), answer(order.get(1))), CBOR, "h", "test"));
            assertEquals(SubmitException.Kind.REJECTED, m.kind);
        }
    }

    @Test
    void relaysAreCalledInParallel_andOneThatDoesNotAnswerInTimeIsUnknown() {
        // R-E8.
        Relay slow = cbor -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Relay.Outcome.rejected("too late");
        };
        long t0 = System.currentTimeMillis();
        assertThrows(PendingTransactionException.class, () -> TxRunner.deliver(List.of(slow, answer(Relay.Outcome.rejected("no"))),
                CBOR, "h", "test", java.time.Duration.ofMillis(300)));
        assertDoesNotThrow(() -> TxRunner.deliver(List.of(slow, slow, answer(Relay.Outcome.accepted())), CBOR, "h", "test",
                java.time.Duration.ofMillis(300)));
        assertTrue(System.currentTimeMillis() - t0 < 3_000, "the slow relays ran side by side, bounded by the timeout");
    }

    @Test
    void aRelayStillRunningWhenItsAttemptStopsWaiting_isCancelled() throws Exception {
        // F70: unanswered relay calls are cancelled (interrupted); the outcome stays unknown.
        var interrupted = new java.util.concurrent.CountDownLatch(1);
        Relay hanging = cbor -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return Relay.Outcome.accepted();
        };
        assertThrows(PendingTransactionException.class, () -> TxRunner.deliver(List.of(hanging), CBOR, "h", "test",
                java.time.Duration.ofMillis(200)));
        assertTrue(interrupted.await(5, java.util.concurrent.TimeUnit.SECONDS), "the hanging relay was cancelled");
    }

    @Test
    void theBackendRelayClassifiesHttpAnswers() {
        // R-C10: a 4xx with the node's reason is a rejection; a gateway's 5xx, 408 and 429 are unknown.
        assertEquals(Relay.Answer.ACCEPTED, Relay.classify(Result.success("ok")).answer());
        assertEquals(Relay.Answer.REJECTED, Relay.classify(Result.error("{\"error\":\"BadInputsUTxO\"}").code(400)).answer());
        for (int code : new int[]{500, 502, 503, 504, 408, 429, 0}) {
            assertEquals(Relay.Answer.UNKNOWN, Relay.classify(Result.error("{\"error\":\"x\"}").code(code)).answer(), "HTTP " + code);
        }
        assertEquals(Relay.Answer.UNKNOWN, Relay.classify(Result.error("").code(400)).answer(), "no reason given");
        assertEquals(Relay.Answer.UNKNOWN, Relay.classify(null).answer());
    }

    // ------------------------------------------------------------------ the designated input, in the signed body

    static Transaction spending(String... refs) {
        var ins = new ArrayList<TransactionInput>();
        for (String r : refs) {
            String[] p = r.split("#");
            ins.add(new TransactionInput(p[0], Integer.parseInt(p[1])));
        }
        return Transaction.builder().body(TransactionBody.builder().inputs(ins).outputs(new ArrayList<>()).fee(BigInteger.ONE).build()).build();
    }

    @Test
    void anAttemptThatDoesNotSpendItsDesignatedInput_isNeverHandedOut() {
        String d = "aa".repeat(32) + "#1";
        assertDoesNotThrow(() -> TxRunner.requireSpends(spending("bb".repeat(32) + "#0", d), d, "test"));
        var e = assertThrows(JoustException.class,
                () -> TxRunner.requireSpends(spending("bb".repeat(32) + "#0", "aa".repeat(32) + "#0"), d, "test"));
        assertFalse(e instanceof SubmitException, "a defect, not contention: never retried");
        assertTrue(e.getMessage().contains("designated input"), e.getMessage());
    }

    // ------------------------------------------------------------------ the top-up allowance

    @Test
    void topUpsFromTheProvidersParametersAreBounded() {
        var backend = (com.bloxbean.cardano.client.backend.api.BackendService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.bloxbean.cardano.client.backend.api.BackendService.class},
                (p, m, a) -> { throw new UnsupportedOperationException(m.getName()); });
        var rt = StateRuntime.builder(backend)
                .chainTime(new SlotChainTime(0, 0, 1000, null)).topUpAllowance(2_000_000).build();
        assertEquals(BigInteger.valueOf(3_000_000), KeyedObjects.topUp(rt, BigInteger.valueOf(3_000_000), BigInteger.valueOf(1_000_000), "t", "o"));
        assertEquals(BigInteger.valueOf(5_000_000), KeyedObjects.topUp(rt, BigInteger.valueOf(3_000_000), BigInteger.valueOf(5_000_000), "t", "o"));
        var e = assertThrows(JoustException.class,
                () -> KeyedObjects.topUp(rt, BigInteger.valueOf(3_000_000), BigInteger.valueOf(5_000_001), "t", "the relinked anchor"));
        assertTrue(e.getMessage().contains("allowance"), e.getMessage());
        assertEquals(10_000_000L, StateRuntime.builder(rt.backend()).chainTime(rt.chainTime()).build().topUpAllowance(), "the default");
    }
}
