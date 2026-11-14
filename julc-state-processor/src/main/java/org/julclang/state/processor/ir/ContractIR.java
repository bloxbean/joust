package org.julclang.state.processor.ir;

import java.util.List;

/**
 * The State IR of one {@code @StateContract} class: everything the generators need, nothing javac-specific.
 * Generators read storage, execution and transaction-level validation from {@link #strategies()}; Option A only has
 * INLINE / DIRECT / SPEND, later layers add implementations (ADR-0001 D12).
 *
 * @param packageName  package of the contract (generated classes go there too)
 * @param name         simple name of the contract class
 * @param stateType    the state record, as source text relative to the contract package (e.g. {@code Counter.State})
 * @param stateComponents components of the state record, in order
 * @param minAda       min-ADA deposit held by the state UTxO, in lovelace
 * @param maxDatumBytes largest serialised state the validator accepts
 * @param maxValidityMs widest validity interval a Ctx-reading call may use
 * @param init         the {@code @Init} transition
 * @param actions      {@code @Action} transitions, in source order
 * @param destroys     {@code @Destroy} transitions, in source order
 * @param views        {@code @View} methods, in source order
 * @param imports      fully qualified names the generated code must import for the types above
 * @param strategies   how state is stored, executed and validated
 * @param owned        the {@code @Owned} records, in source order (ADR-0003; several since ADR-0012 D1)
 * @param ownedInits   {@code @OwnedInit} methods
 * @param ownedActions {@code @OwnedAction} methods
 * @param ownedDestroys {@code @OwnedDestroy} methods
 * @param paramsType   the {@code @Params} record as source text, or null (ADR-0009 D1)
 * @param invariants   {@code @Invariant} methods (ADR-0009 D4)
 * @param purposes     {@code @Withdraw}/{@code @Certify}/{@code @Vote}/{@code @Propose} handlers (ADR-0010 D1)
 * @param stakeSelf    state UTxOs stake to the contract's own script (ADR-0010 D2)
 * @param migrate      the {@code @Migrate} creation, or null (ADR-0011 D4)
 */
public record ContractIR(String packageName,
                         String name,
                         String stateType,
                         List<ParamIR> stateComponents,
                         long minAda,
                         int maxDatumBytes,
                         long maxValidityMs,
                         TransitionIR init,
                         List<TransitionIR> actions,
                         List<TransitionIR> destroys,
                         List<ViewIR> views,
                         List<String> imports,
                         Strategies strategies,
                         List<OwnedIR> owned,
                         List<TransitionIR> ownedInits,
                         List<TransitionIR> ownedActions,
                         List<TransitionIR> ownedDestroys,
                         String paramsType,
                         List<InvariantIR> invariants,
                         List<PurposeIR> purposes,
                         boolean stakeSelf,
                         TransitionIR migrate,
                         List<String> javaLibraries) {

    /**
     * {@code javaLibraries}: the {@code java:} libraries a contract lowered from {@code .jsc} is built with (ADR-0018 D9,
     * review r2), by class name; its client checks their classes when it loads. Empty for every other contract.
     */
    public ContractIR {
        javaLibraries = javaLibraries == null ? List.of() : List.copyOf(javaLibraries);
    }

    /** A handler for a non-spend purpose (ADR-0010). */
    public record PurposeIR(String kind, String method, List<ParamIR> args, boolean hasCtx, String quorumKeys,
                            String quorumThreshold, List<String> authorizedBy) {}

    public java.util.Optional<PurposeIR> purpose(String kind) {
        return purposes.stream().filter(p -> p.kind().equals(kind)).findFirst();
    }

    /**
     * An {@code @Invariant} method: on an owned record ({@code ownedType}, its source text) or on the state (null);
     * optionally taking params.
     */
    public record InvariantIR(String method, String ownedType, boolean takesParams) {
        public boolean owned() {
            return ownedType != null;
        }
    }

    /**
     * One {@code @Owned} record (ADR-0012 D1).
     *
     * @param type       the record as source text ({@code Guild.Hero})
     * @param simple     its simple name ({@code Hero})
     * @param components its components, in order
     * @param holding    the datum record wrapping it: {@code Holding} with one owned type, {@code Holding_Hero} with several
     * @param tag        the datum's constructor tag: its index among the owned records; for a keyed type, its node's
     *                   ({@code Keyed_T}) tag
     * @param keyed      how the type is keyed (ADR-0016), or null for a plain owned type
     */
    public record OwnedIR(String type, String simple, List<ParamIR> components, String holding, int tag, KeyedIR keyed) {
        /** Indexes of its MerkleMap components (ADR-0012 D2). */
        public List<Integer> mapComponents() {
            var out = new java.util.ArrayList<Integer>();
            for (int i = 0; i < components.size(); i++) {
                if (components.get(i).kind() == ParamIR.TypeKind.MERKLE_MAP) out.add(i);
            }
            return out;
        }

        public boolean hasMaps() {
            return !mapComponents().isEmpty();
        }

        /** A keyed type (ADR-0016). */
        public boolean isKeyed() {
            return keyed != null;
        }
    }

    /**
     * How an owned type is keyed (ADR-0016 D1, D2).
     *
     * @param keyField    the record component that is the key
     * @param keyIndex    its index among the record's components
     * @param maxKeyBytes longest key
     * @param signedKey   the key is a key hash that signs the insert (I53)
     * @param cooldownMs  {@code C}, least time between relinks (0: none)
     * @param windowMs    {@code W}, widest validity window of a relink
     * @param typeId      {@code I2OSP(len(name), 1) ++ utf8(name)}, hashed into every key token of the type
     * @param headTag     constructor tag of {@code Head_T}
     * @param nodeTag     constructor tag of {@code Keyed_T}
     * @param retiredTag  constructor tag of {@code Retired_T}
     */
    public record KeyedIR(String keyField, int keyIndex, int maxKeyBytes, boolean signedKey, long cooldownMs,
                          long windowMs, byte[] typeId, int headTag, int nodeTag, int retiredTag) {}

    /** The state or some owned record has MerkleMaps: the validator reads the map verifier's claims. */
    public boolean anyMaps() {
        return hasMaps() || owned.stream().anyMatch(OwnedIR::hasMaps);
    }

    public boolean hasParams() {
        return paramsType != null;
    }

    /** The contract has an {@code @Owned} record (ADR-0003). */
    public boolean hasOwned() {
        return !owned.isEmpty();
    }

    /** Several owned types: the datum is a sealed {@code Holding} with one variant per type (ADR-0012 D1). */
    public boolean multiOwned() {
        return owned.size() > 1;
    }

    /** Some owned type is keyed (ADR-0016). */
    public boolean hasKeyed() {
        return owned.stream().anyMatch(OwnedIR::isKeyed);
    }

    /** The keyed owned types, in source order. */
    public List<OwnedIR> keyedTypes() {
        return owned.stream().filter(OwnedIR::isKeyed).toList();
    }

    /**
     * The objects' datum is a sealed {@code Holding} told apart by constructor tag: with several owned types
     * (ADR-0012 D1), and whenever a type is keyed (ADR-0016 D2, I42 amended).
     */
    public boolean sealedHolding() {
        return multiOwned() || hasKeyed();
    }

    /** A keyed type's removing destroys ({@code @OwnedDestroy} without {@code retire}; ADR-0016 D5). */
    public List<TransitionIR> removals(OwnedIR o) {
        return ownedDestroys.stream().filter(t -> t.ownedType().equals(o.type()) && !t.retire()).toList();
    }

    /** The owned record named by source text {@code type}. */
    public OwnedIR ownedOf(String type) {
        for (var o : owned) if (o.type().equals(type)) return o;
        throw new IllegalArgumentException("not an owned type: " + type);
    }

    /** The owned record an owned method acts on. */
    public OwnedIR ownedOf(TransitionIR t) {
        return ownedOf(t.ownedType());
    }

    /** Owned moves ({@code @Into}, ADR-0012 D3). */
    public List<TransitionIR> moves() {
        return ownedActions.stream().filter(TransitionIR::moves).toList();
    }

    /** {@code @Admits} actions (ADR-0012 D3). */
    public List<TransitionIR> admits() {
        return actions.stream().filter(TransitionIR::admits).toList();
    }

    /** Owned spend-side transitions: owned actions then owned destroys. */
    public List<TransitionIR> ownedSpendTransitions() {
        var all = new java.util.ArrayList<TransitionIR>(ownedActions);
        all.addAll(ownedDestroys);
        return List.copyOf(all);
    }

    public String validatorName() {
        return name + "Validator";
    }

    public String clientName() {
        return name + "Client";
    }

    /** Indexes of the state's MerkleMap components (ADR-0002). */
    public List<Integer> mapComponents() {
        var out = new java.util.ArrayList<Integer>();
        for (int i = 0; i < stateComponents.size(); i++) {
            if (stateComponents.get(i).kind() == ParamIR.TypeKind.MERKLE_MAP) out.add(i);
        }
        return out;
    }

    public boolean hasMaps() {
        return !mapComponents().isEmpty();
    }

    /** {@code @Batched} actions (ADR-0004), in source order. */
    public List<TransitionIR> batched() {
        return actions.stream().filter(TransitionIR::batched).toList();
    }

    /** Every transition: init, actions, destroys, owned inits, owned actions, owned destroys. */
    public List<TransitionIR> allTransitions() {
        var all = new java.util.ArrayList<TransitionIR>();
        all.add(init);
        all.addAll(actions);
        all.addAll(destroys);
        all.addAll(ownedInits);
        all.addAll(ownedActions);
        all.addAll(ownedDestroys);
        return List.copyOf(all);
    }

    /**
     * The contract handles native assets (ADR-0007 D2): some method takes Funds, sends or mints. Then every transition
     * checks the full multi-asset value; otherwise the cheaper lovelace-only check of ADR-0001 I3 applies.
     */
    public boolean assetAware() {
        return allTransitions().stream().anyMatch(TransitionIR::assetAware) || purpose("WITHDRAW").isPresent();
    }

    /** Some method sends outputs or uses deposits (ADR-0007 D5, ADR-0011 D3): validators read the output registry. */
    public boolean hasSends() {
        return allTransitions().stream().anyMatch(TransitionIR::resultSends) || purpose("WITHDRAW").isPresent()
                || hasDeposits() || hasReceives();
    }

    /** Some method deposits into another instance (ADR-0011 D3). */
    public boolean hasDeposits() {
        return allTransitions().stream().anyMatch(TransitionIR::resultDeposits);
    }

    /** Some method takes deposits ({@code @Receives}, ADR-0011 D3). */
    public boolean hasReceives() {
        return allTransitions().stream().anyMatch(TransitionIR::receives);
    }

    /** Spend-side methods that may mint user tokens (ADR-0007 D4). */
    public boolean hasMints() {
        return allTransitions().stream().anyMatch(TransitionIR::resultMint);
    }

    public boolean hasBatched() {
        return actions.stream().anyMatch(TransitionIR::batched);
    }

    /**
     * Spend-side transitions with a direct-call redeemer: actions (except {@code @Batched(only = true)}) then
     * destroys, in that order (the redeemer's constructor order).
     */
    public List<TransitionIR> spendTransitions() {
        var all = new java.util.ArrayList<TransitionIR>(actions.stream().filter(t -> !t.batchOnly()).toList());
        all.addAll(destroys);
        return List.copyOf(all);
    }
}
