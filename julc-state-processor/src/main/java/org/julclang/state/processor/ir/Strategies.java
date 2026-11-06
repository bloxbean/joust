package org.julclang.state.processor.ir;

/**
 * The pluggable parts of code generation (ADR-0001 D12). Option A ships one value of each.
 *
 * @param storage   where the state lives
 * @param execution who submits transitions
 * @param txLevel   where checks that span inputs run
 */
public record Strategies(Storage storage, Execution execution, TxLevel txLevel) {

    public static final Strategies OPTION_A = new Strategies(Storage.INLINE, Execution.DIRECT, TxLevel.SPEND);

    /** INLINE: the state record is the inline datum of the state UTxO. Later: MERKLE, SHARDED, OWNED. */
    public enum Storage { INLINE }

    /** DIRECT: the caller's transaction spends the state UTxO. Later: BATCHED (intents + batcher). */
    public enum Execution { DIRECT }

    /** SPEND: each spend validates its own instance. Later: WITHDRAW_ZERO, then CIP-112 guards. */
    public enum TxLevel { SPEND }
}
