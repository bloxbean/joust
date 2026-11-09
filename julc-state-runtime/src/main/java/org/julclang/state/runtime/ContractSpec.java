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
 * @param scriptCborHex  the compiled script itself, for a contract known only by its description (ADR-0015 D4): then
 *                       {@code validatorClass} is null and {@code stateClass} is {@code PlutusData}
 * @param keyed          its keyed types (ADR-0016), in declaration order; empty for most contracts
 * @param <S>            the state type
 */
public record ContractSpec<S>(String name, Class<?> validatorClass, Class<S> stateClass, long minAda,
                              int maxDatumBytes, long maxValidityMs, boolean stakeSelf, String scriptCborHex,
                              java.util.List<Integer> mapFields, java.util.List<KeyedType<?>> keyed) {

    public ContractSpec(String name, Class<?> validatorClass, Class<S> stateClass, long minAda, int maxDatumBytes,
                        long maxValidityMs, boolean stakeSelf, String scriptCborHex, java.util.List<Integer> mapFields) {
        this(name, validatorClass, stateClass, minAda, maxDatumBytes, maxValidityMs, stakeSelf, scriptCborHex, mapFields,
                java.util.List.of());
    }

    /**
     * A contract with keyed types (ADR-0016): its instances are created with one list head per type. A factory, not a
     * constructor: an overload ending in a {@code List} made {@code new ContractSpec<>(..., stakeSelf, null)} ambiguous
     * (R-C20).
     */
    public static <S> ContractSpec<S> keyed(String name, Class<?> validatorClass, Class<S> stateClass, long minAda,
                                            int maxDatumBytes, long maxValidityMs, boolean stakeSelf,
                                            java.util.List<KeyedType<?>> keyed) {
        return new ContractSpec<>(name, validatorClass, stateClass, minAda, maxDatumBytes, maxValidityMs, stakeSelf, null, null,
                java.util.List.copyOf(keyed));
    }

    /** The contract has keyed types (ADR-0016). */
    public boolean hasKeyed() {
        return keyed != null && !keyed.isEmpty();
    }

    public ContractSpec(String name, Class<?> validatorClass, Class<S> stateClass, long minAda, int maxDatumBytes,
                        long maxValidityMs, boolean stakeSelf, String scriptCborHex) {
        this(name, validatorClass, stateClass, minAda, maxDatumBytes, maxValidityMs, stakeSelf, scriptCborHex, null);
    }

    /** Where the state's MerkleMaps are: record components, or (a described contract) {@code mapFields} of its Data. */
    @SuppressWarnings("unchecked")
    public MerkleSupport.Layout<S> layout() {
        return mapFields != null ? (MerkleSupport.Layout<S>) MerkleSupport.dataLayout(mapFields) : MerkleSupport.layout(stateClass);
    }

    public ContractSpec(String name, Class<?> validatorClass, Class<S> stateClass, long minAda, int maxDatumBytes,
                        long maxValidityMs) {
        this(name, validatorClass, stateClass, minAda, maxDatumBytes, maxValidityMs, false, null);
    }

    public ContractSpec(String name, Class<?> validatorClass, Class<S> stateClass, long minAda, int maxDatumBytes,
                        long maxValidityMs, boolean stakeSelf) {
        this(name, validatorClass, stateClass, minAda, maxDatumBytes, maxValidityMs, stakeSelf, null);
    }

    /** A contract known by its description and compiled script, with its state as Plutus data (ADR-0015 D4). */
    public static ContractSpec<org.julclang.core.PlutusData> described(String name, String scriptCborHex, long minAda,
                                                                       int maxDatumBytes, long maxValidityMs, boolean stakeSelf) {
        return described(name, scriptCborHex, minAda, maxDatumBytes, maxValidityMs, stakeSelf, java.util.List.of());
    }

    /** As {@link #described(String, String, long, int, long, boolean)}, the state's MerkleMaps being {@code mapFields}. */
    public static ContractSpec<org.julclang.core.PlutusData> described(String name, String scriptCborHex, long minAda,
                                                                       int maxDatumBytes, long maxValidityMs, boolean stakeSelf,
                                                                       java.util.List<Integer> mapFields) {
        return new ContractSpec<>(name, null, org.julclang.core.PlutusData.class, minAda, maxDatumBytes, maxValidityMs,
                stakeSelf, scriptCborHex, java.util.List.copyOf(mapFields));
    }
}
