package org.julclang.state.runtime;

import org.julclang.ledger.Address;
import org.julclang.ledger.TxOutRef;
import org.julclang.state.api.Ctx;

import java.math.BigInteger;

/**
 * How a generated client builds and reads its validator's intent types (ADR-0004). Implemented by the generated
 * {@code <Name>Client.INTENTS}; the runtime and the testkit use it to create intents and fold batches exactly as the
 * validator does.
 *
 * @param <S> the state type
 */
public interface IntentCodec<S> {

    /** An intent's fields, read from the generated {@code Intent} record. */
    record IntentData(byte[] instance, byte[] owner, Address refund, BigInteger deadline, BigInteger received, Object call) {}

    /** The generated {@code Intent} record class (for decoding datums). */
    Class<?> intentClass();

    IntentData read(Object intent);

    Object intent(byte[] instance, byte[] owner, Address refund, BigInteger deadline, BigInteger received, Object call);

    /** MINT {@code CreateIntent(seed, intent)}. */
    Object create(TxOutRef seed, Object intent);

    /** SPEND {@code Batch(validFrom, validTo)} on the instance. */
    Object batch(BigInteger validFrom, BigInteger validTo);

    /** SPEND {@code InBatch} on each intent of a batch. */
    Object inBatch();

    /** SPEND {@code CancelIntent} by the owner. */
    Object cancel();

    /** MINT {@code BurnInstance}: the burns of a batch or a cancel. */
    Object burn();

    /** The datum of an intent's refund output, {@code IntentRefund(intentRef)}. */
    Object refundTag(TxOutRef intent);

    /** One step of the fold: the batched action the call names, on the JVM. */
    Transition<S> step(S state, Object call, Ctx ctx);
}
