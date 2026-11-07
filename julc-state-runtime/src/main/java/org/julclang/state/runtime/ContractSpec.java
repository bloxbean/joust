package org.julclang.state.runtime;

/**
 * What the runtime needs to know about a generated contract. Each generated client holds one.
 *
 * @param name           contract name, for messages
 * @param validatorClass the generated {@code <Name>Validator}; its compiled script is loaded from META-INF/plutus
 * @param stateClass     the state record
 * @param minAda         min-ADA deposit held by the state UTxO
 * @param maxDatumBytes  largest serialised state the validator accepts
 * @param maxValidityMs  widest validity interval a Ctx-reading call may use
 * @param stakeSelf      state UTxOs sit at base addresses staked to the contract's own script (ADR-0010 D2)
 * @param <S>            the state type
 */
public record ContractSpec<S>(String name, Class<?> validatorClass, Class<S> stateClass, long minAda,
                              int maxDatumBytes, long maxValidityMs, boolean stakeSelf) {

    public ContractSpec(String name, Class<?> validatorClass, Class<S> stateClass, long minAda, int maxDatumBytes,
                        long maxValidityMs) {
        this(name, validatorClass, stateClass, minAda, maxDatumBytes, maxValidityMs, false);
    }
}
