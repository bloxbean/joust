package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.state.api.Assets;
import org.julclang.state.api.OutputClaim;
import org.julclang.state.api.OutputClaims;
import org.julclang.state.api.Payout;
import org.julclang.state.api.RequireException;
import org.julclang.state.api.Send;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds the value side of a transaction for an asset-aware contract (ADR-0007): the state output's full value, tagged
 * payouts, sends with their claims in the output registry, and the user-token mint. The JVM twin of the generated
 * validator's value equation (I26): {@code out = in + received + accepted + mint - payouts - sends}.
 */
final class AssetTx {

    private static final HexFormat HEX = HexFormat.of();

    private AssetTx() {}

    /** What the built transaction must still contain after balancing. */
    record Applied(TxRunner.ExpectedState expected, List<TxRunner.ExpectedPayout> payouts, List<TxRunner.ExpectedSend> sends) {}

    /** A UTxO's value as a canonical ledger value. */
    static Value valueOf(Utxo u) {
        Value v = Assets.none();
        for (Amount a : u.getAmount()) {
            if (a.getUnit().equals("lovelace")) {
                v = Assets.add(v, Assets.lovelace(a.getQuantity()));
            } else {
                String unit = a.getUnit().startsWith("0x") ? a.getUnit().substring(2) : a.getUnit();
                v = Assets.add(v, Assets.of(HEX.parseHex(unit.substring(0, 56)), HEX.parseHex(unit.substring(56)), a.getQuantity()));
            }
        }
        return v;
    }

    /** A canonical value as CCL amounts (lovelace first). */
    static List<Amount> amounts(Value v) {
        var out = new ArrayList<Amount>();
        var m = Assets.read(v);
        m.forEach((p, ts) -> ts.forEach((t, q) -> {
            if (q.signum() < 0) throw new RequireException("negative quantity in an output value: " + p + "." + t);
            out.add(p.isEmpty() ? Amount.lovelace(q) : new Amount(p + t, q));
        }));
        return out;
    }

    /** The native assets of a UTxO value without lovelace and without the token {@code (policy, id)}: Funds.held. */
    static Value held(Value in, byte[] policy, byte[] id) {
        Value v = Assets.withoutLovelace(in);
        return Assets.subtract(v, Assets.of(policy, id, Assets.quantity(v, policy, id)));
    }

    /**
     * Adds the transition's outputs and mint to {@code tx}.
     *
     * @param ownRef      the spent state or object UTxO, or null when creating
     * @param inValue     its value (creating: the deposit plus the new token)
     * @param continuing  the datum of the continuing output, or null for a destroy
     * @param lifecycle   the MINT redeemer for the lifecycle token (create or burn), or null for an action
     */
    static Applied apply(Tx tx, StateContractHandle<?> contract, Account caller, String what, TxOutRef ownRef,
                         Value inValue, BigInteger receivedLovelace, Value receivedAssets, Transition<?> t, String unit,
                         byte[] id, com.bloxbean.cardano.client.plutus.spec.PlutusData continuing, Object lifecycle, Function<TxOutRef, Object> byAction,
                         boolean creating) {
        return apply(tx, contract, caller, what, ownRef, inValue, receivedLovelace, receivedAssets, t, unit, id, continuing,
                lifecycle, byAction, creating, null);
    }

    /** As above; {@code seed} is the creation's seed input, which may already hold some of the assets paid in. */
    static Applied apply(Tx tx, StateContractHandle<?> contract, Account caller, String what, TxOutRef ownRef,
                         Value inValue, BigInteger receivedLovelace, Value receivedAssets, Transition<?> t, String unit,
                         byte[] id, com.bloxbean.cardano.client.plutus.spec.PlutusData continuing, Object lifecycle,
                         Function<TxOutRef, Object> byAction, boolean creating, Utxo seed) {
        return apply(tx, contract, caller, what, ownRef, inValue, receivedLovelace, receivedAssets, t, unit, id, continuing,
                lifecycle, byAction, creating, seed, org.julclang.core.types.JulcList.empty());
    }

    /**
     * The deposits other calls of the composition make into {@code unit} (from the probe pass), as the claims the
     * registry will hold: their output is this call's continuing output, the next output it adds (ADR-0011 D3).
     */
    static org.julclang.core.types.JulcList<org.julclang.state.api.DepositClaim> incoming(String unit) {
        TxRunner.Capture cap = TxRunner.capture();
        org.julclang.core.types.JulcList<org.julclang.state.api.DepositClaim> out = org.julclang.core.types.JulcList.empty();
        if (cap == null) return out;
        cap.receivers.add(unit.toLowerCase());
        // The real pass knows every deposit (from the first); the first pass knows those made so far.
        var source = cap.known.isEmpty() ? cap.deposits : cap.known;
        var mine = source.stream().filter(d -> d.target().equalsIgnoreCase(unit)).toList();
        BigInteger at = BigInteger.valueOf(TxRunner.outputOffset());
        for (int i = mine.size() - 1; i >= 0; i--) {
            var d = mine.get(i);
            out = out.prepend(new org.julclang.state.api.DepositClaim(at, d.by(), d.value(), d.memo()));
        }
        return out;
    }

    /** As above, for a call that takes {@code incoming} deposits ({@code @Receives}; ADR-0011 D3). */
    static Applied apply(Tx tx, StateContractHandle<?> contract, Account caller, String what, TxOutRef ownRef,
                         Value inValue, BigInteger receivedLovelace, Value receivedAssets, Transition<?> t, String unit,
                         byte[] id, com.bloxbean.cardano.client.plutus.spec.PlutusData continuing, Object lifecycle,
                         Function<TxOutRef, Object> byAction, boolean creating, Utxo seed,
                         org.julclang.core.types.JulcList<org.julclang.state.api.DepositClaim> incoming) {
        byte[] policy = HEX.parseHex(contract.policyId());
        Assets.check(t.accepted(), what + ": accepted");
        Assets.check(t.mint(), what + ": mint");
        if (!Assets.isEmpty(t.accepted()) || !Assets.isEmpty(receivedAssets)) {
            if (!Assets.isEmpty(Assets.subtract(t.accepted(), receivedAssets))) {
                throw new RequireException(what + ": the method accepts " + Assets.read(t.accepted())
                        + " but the caller offers " + Assets.read(receivedAssets) + " (ADR-0007 I31)");
            }
        }
        for (var p : Assets.read(t.mint()).entrySet()) {
            if (!p.getKey().equals(contract.policyId())) throw new RequireException(what + ": mints under a foreign policy");
            for (String name : p.getValue().keySet()) {
                if (reserved(HEX.parseHex(name))) throw new RequireException(what + ": mints a reserved token name");
            }
        }

        collectAssets(tx, contract, caller, receivedAssets, what, seed);

        // ---- the value equation (I26)
        Value payoutTotal = Assets.lovelace(Payouts(t.payouts()));
        Value sent = Assets.none();
        for (Send s : t.sends()) {
            Assets.check(s.value(), what + ": send value");
            sent = Assets.add(sent, s.value());
        }
        // Deposits (ADR-0011 D3): made ones leave like sends, incoming ones arrive like received value.
        TxRunner.Capture cap = TxRunner.capture();
        for (var d : t.deposits()) {
            Assets.check(d.value(), what + ": deposit value");
            if (cap == null) {
                throw new JoustException(what + ": a deposit needs its receiver's @Receives call in the same transaction;"
                        + " compose them with Compose (ADR-0011 D3)");
            }
            if (ownRef == null) throw new RequireException(what + ": a creation cannot deposit");
            Datums.checkCanonicalMemo(PlutusDataAdapter.convert(d.memo()), what);
            sent = Assets.add(sent, d.value());
            cap.deposits.add(new TxRunner.PendingDeposit(HEX.formatHex(d.policy()) + HEX.formatHex(d.instance()), ownRef,
                    d.value(), d.memo(), what));
        }
        for (var d : incoming) sent = Assets.subtract(sent, d.value());
        Value next = Assets.add(Assets.add(inValue, Assets.lovelace(receivedLovelace)), Assets.add(t.accepted(), t.mint()));
        next = Assets.subtract(Assets.subtract(next, payoutTotal), sent);
        Value token = Assets.of(policy, id, BigInteger.ONE);

        TxRunner.ExpectedState expected = null;
        if (continuing != null) {
            if (Assets.lovelaceOf(next).compareTo(BigInteger.valueOf(contract.spec.minAda())) < 0) {
                throw new RequireException(what + ": payouts and sends exceed the contract's funds");
            }
            List<Amount> out = amounts(next);
            if (cap != null) cap.stateOutputs.put(unit.toLowerCase(), TxRunner.outputOffset());
            Datums.checkSize(contract.spec, continuing, what);
            Datums.checkCanonical(contract.spec, continuing, what);
            tx.payToContract(contract.address(), out, continuing);
            expected = new TxRunner.ExpectedState(unit, Assets.lovelaceOf(next), continuing, next);
            BigInteger floor = contract.minUtxoFor(contract.address(), continuing, out);
            if (floor.compareTo(Assets.lovelaceOf(next)) > 0) {
                contract.runtime.log("warning: " + what + ": the state output needs " + floor + " lovelace (min-UTxO) but"
                        + " holds " + Assets.lovelaceOf(next) + "; raise @StateContract(assetSlots) or minAda");
            }
        } else if (!Assets.isEmpty(Assets.subtract(next, token))) {
            throw new RequireException(what + ": a destroy must pay out or send everything it holds; left over: "
                    + Assets.read(Assets.subtract(next, token)));
        }

        // ---- mint
        Value mint = t.mint();
        if (creating) mint = Assets.add(mint, token);
        if (continuing == null) mint = Assets.add(mint, Assets.of(policy, id, BigInteger.ONE.negate()));
        boolean userMint = !Assets.isEmpty(t.mint());
        if (!Assets.isEmpty(mint)) {
            Object redeemer = userMint ? byAction.apply(ownRef) : lifecycle;
            if (redeemer == null) throw new JoustException(what + ": no mint redeemer");
            var assets = new ArrayList<Asset>();
            Assets.read(mint).forEach((p, ts) -> ts.forEach((n, q) -> assets.add(new Asset("0x" + n, q))));
            TxRunner.mint(tx, contract.policyId(), assets, PlutusDataAdapter.convert(redeemer));
        }

        // ---- payouts (tagged, I7)
        int index = TxRunner.outputOffset() + (continuing != null ? 1 : 0);
        Map<String, BigInteger> owed = new LinkedHashMap<>();
        for (Payout p : t.payouts()) {
            if (p.lovelace().signum() <= 0) throw new RequireException(what + ": payouts must be positive");
            if (p.to().length != 28) throw new RequireException(what + ": payout recipient must be a 28-byte key hash");
            owed.merge(HEX.formatHex(p.to()), p.lovelace(), BigInteger::add);
        }
        var expectedPayouts = new ArrayList<TxRunner.ExpectedPayout>();
        if (!owed.isEmpty()) {
            var tag = PlutusDataAdapter.convert(ownRef);
            for (var e : owed.entrySet()) {
                String addr = contract.payoutAddress(HEX.parseHex(e.getKey()), caller);
                BigInteger amount = e.getValue().max(contract.minUtxo(addr, tag, e.getValue()));
                tx.payToContract(addr, List.of(Amount.lovelace(amount)), tag);
                expectedPayouts.add(new TxRunner.ExpectedPayout(addr, e.getValue(), tag));
                index++;
            }
        }

        // ---- sends, each claimed in the output registry (I29)
        var expectedSends = new ArrayList<TxRunner.ExpectedSend>();
        var claims = new ArrayList<OutputClaim>();
        for (Send s : t.sends()) {
            String addr = LedgerAddresses.toBech32(s.to(), contract.runtime.network());
            if (addr.equals(caller.baseAddress())) {
                throw new RequireException(what + ": a send to the caller's own base address would pay the fee out of it;"
                        + " send to its enterprise address instead (ADR-0007 r2)");
            }
            List<Amount> amounts = new ArrayList<>(amounts(s.value()));
            com.bloxbean.cardano.client.plutus.spec.PlutusData inline = null;
            String hash = null;
            switch (s.datum()) {
                case OutputDatum.OutputDatumInline i -> inline = PlutusDataAdapter.convert(i.datum());
                case OutputDatum.OutputDatumHash h -> hash = HEX.formatHex(h.hash().hash());
                case OutputDatum.NoOutputDatum n -> {
                    if (s.to().credential() instanceof org.julclang.ledger.Credential.ScriptCredential) {
                        contract.runtime.log("warning: " + what + ": a send to a script address without a datum locks its funds");
                    }
                }
            }
            BigInteger lovelace = Assets.lovelaceOf(s.value());
            BigInteger floor = contract.minUtxoFor(addr, inline, amounts);
            if (floor.compareTo(lovelace) > 0) {
                amounts.removeIf(a -> a.getUnit().equals("lovelace"));
                amounts.add(0, Amount.lovelace(floor));
            }
            if (inline != null) tx.payToContract(addr, amounts, inline);
            else if (hash != null) tx.payToContract(addr, amounts, hash);
            else tx.payToAddress(addr, amounts);
            claims.add(new OutputClaim(BigInteger.valueOf(index), ownRef));
            expectedSends.add(new TxRunner.ExpectedSend(index, addr, inline == null ? null : Datums.hex(inline), hash, s.value()));
            index++;
        }
        if (!claims.isEmpty()) {
            org.julclang.core.types.JulcList<OutputClaim> list = org.julclang.core.types.JulcList.empty();
            for (int i = claims.size() - 1; i >= 0; i--) list = list.prepend(claims.get(i));
            if (cap != null) cap.claims.addAll(claims);
            else contract.runtime.outputVerifier().attachRedeemer(tx, new OutputClaims(list, org.julclang.core.types.JulcList.empty()), caller);
        }
        return new Applied(expected, expectedPayouts, expectedSends);
    }

    private static BigInteger Payouts(org.julclang.core.types.JulcList<Payout> payouts) {
        BigInteger sum = BigInteger.ZERO;
        for (Payout p : payouts) sum = sum.add(p.lovelace());
        return sum;
    }

    /**
     * Spends caller UTxOs (from its base address) that hold {@code needed}'s native assets. QuickTx balances only
     * lovelace from the fee payer, so the assets a caller pays in must be collected explicitly, by key accounts and
     * wallets alike: with a script input fixed, QuickTx builds the change from the fixed inputs and its fee selection
     * asks only for lovelace, so an uncollected token payment ends as a negative quantity in the change (#152). The
     * leftovers return as change: the payer's; in a composition whose caller is not the payer, to the caller's own
     * address ({@link #returnSurplus}, T1). Throws if the caller does not hold them.
     * <p>
     * Never collects a UTxO twice (ADR-0015 §13.8, #152): what the transaction already spends at the caller's address
     * (the creation's {@code alreadyIn} seed; in a composition, another call's seed, a designated input, or what an
     * earlier call collected) counts first, less what earlier calls took from it.
     * <p>
     * Selection: never a UTxO with a reference script (spending it would consume a deployment's script; T2) or a datum
     * hash (a conservative choice, as cardano-client-lib's selection makes: at a key address the ledger needs no datum,
     * but whatever the hash commits to may matter to someone; F-W5); an inline datum at a key address is fine (R9). Nor
     * one another keyed operation designates or holds ({@link Designation#pickable}; R2). Like every plain
     * transaction's selection (Designation), it does not skip inputs an earlier, still-unexpired prepared transaction
     * of this JVM claims (T4): the lovelace selection of the same body would reuse them anyway; two such bodies
     * conflict and at most one lands, so nothing is lost, and a page prepares again after the first settles.
     */
    static void collectAssets(Tx tx, StateContractHandle<?> contract, Account caller, Value needed, String what,
                              Utxo alreadyIn) {
        var want = Assets.read(Assets.withoutLovelace(needed));
        if (want.isEmpty()) return;
        var missing = new java.util.HashMap<String, BigInteger>();
        want.forEach((p, ts) -> ts.forEach((n, q) -> missing.put(p + n, q)));
        TxRunner.Capture cap = TxRunner.capture();
        // What the transaction's inputs at the caller's address hold beyond what earlier calls took, by unit; and the
        // inputs counted in it (by lower-case reference: a provider may spell a transaction id in either case; T3).
        // Per composition (its capture), else per call.
        var spare = cap == null ? new java.util.HashMap<String, BigInteger>()
                : cap.spareAssets.computeIfAbsent(caller.baseAddress(), k -> new java.util.HashMap<>());
        var counted = cap == null ? new java.util.HashSet<String>() : cap.countedInputs;
        if (alreadyIn != null && counted.add(key(alreadyIn))) addAssets(spare, alreadyIn);
        // #165 reviewer3 R3-2: inputs earlier calls of this composition already spend from the caller's address (a
        // seed, a designated input) count before any page is read, whatever their place in the listing and even when
        // they carry a datum hash (the transaction spends them anyway): no token UTxO is pulled in for what they hold.
        if (cap != null) {
            for (var e : cap.regularInputs.entrySet()) {
                if (caller.baseAddress().equals(cap.regularOwners.get(e.getKey())) && counted.add(key(e.getValue()))) {
                    addAssets(spare, e.getValue());
                }
            }
        }
        draw(missing, spare);
        var picked = new ArrayList<Utxo>();
        int passedOver = 0;
        try {
            for (int page = 1; page < 20 && !missing.isEmpty(); page++) {
                var r = contract.runtime.backend().getUtxoService().getUtxos(caller.baseAddress(), 100, page);
                if (!r.isSuccessful() || r.getValue() == null || r.getValue().isEmpty()) break;
                for (Utxo u : r.getValue()) {
                    if (counted.contains(key(u)) || !holdsAny(u, missing)) continue;
                    String ref = Designation.ref(u);
                    boolean spent = cap != null && cap.inputs.contains(ref);
                    // An inline datum at a key address is spendable as is (R9): tokens a contract sends a wallet with one
                    // can be paid in. Not a datum hash (conservative, as cardano-client-lib; F-W5), nor a reference
                    // script (it would be consumed). A provider may give an inline datum's hash too (Yaci Store does):
                    // only a hash without the inline datum is a datum hash (#165 Codex).
                    // Spent already (R3-2): it counts whatever it carries, since the transaction spends it anyway.
                    if (!spent && (u.getReferenceScriptHash() != null || u.getDataHash() != null && u.getInlineDatum() == null
                            || !Designation.pickable(u, TxRunner.currentOperation()))) {
                        passedOver++;
                        continue;
                    }
                    counted.add(key(u));
                    addAssets(spare, u);
                    // Spent already (another call's seed, a designated input): counted, not collected again.
                    if (!spent) {
                        picked.add(u);
                        if (cap != null) {
                            cap.inputs.add(ref);
                            cap.assetInputs.add(ref);
                            cap.regularInputs.putIfAbsent(ref, u);
                            cap.regularOwners.put(ref, caller.baseAddress());   // listed there (R7)
                        }
                    }
                    draw(missing, spare);
                    if (missing.isEmpty()) break;
                }
            }
        } catch (Exception e) {
            throw new JoustException(what + ": cannot read the caller's UTxOs", e);
        }
        if (!missing.isEmpty()) {
            throw new RequireException(what + ": the caller's base address does not hold " + missing
                    + (passedOver == 0 ? "" : " in UTxOs it may spend (" + passedOver + " holding it carry a reference"
                    + " script or a datum hash, or are held by a keyed operation)"));
        }
        if (cap != null) {
            var paid = cap.paidAssets.computeIfAbsent(caller.baseAddress(), k -> new java.util.HashMap<>());
            want.forEach((p, ts) -> ts.forEach((n, q) -> paid.merge(p + n, q, BigInteger::add)));
        }
        if (!picked.isEmpty()) tx.collectFrom(picked);
    }

    /** A canonical value as a cardano-client-lib output value. */
    static com.bloxbean.cardano.client.transaction.spec.Value specValue(Value v) {
        var multi = new ArrayList<com.bloxbean.cardano.client.transaction.spec.MultiAsset>();
        Assets.read(v).forEach((p, ts) -> {
            if (p.isEmpty()) return;
            var assets = new ArrayList<Asset>();
            ts.forEach((n, q) -> assets.add(new Asset("0x" + n, q)));
            multi.add(com.bloxbean.cardano.client.transaction.spec.MultiAsset.builder().policyId(p).assets(assets).build());
        });
        return com.bloxbean.cardano.client.transaction.spec.Value.builder().coin(Assets.lovelaceOf(v)).multiAssets(multi).build();
    }

    private static String key(Utxo u) {
        return Designation.ref(u);   // lower case (T3, R3-2)
    }

    /**
     * Returns to each caller other than the payer what the composition's inputs at its address hold beyond the native
     * assets its calls pay in (T1): one output to that address, appended after the calls' outputs, holding those
     * inputs' lovelace and their other assets. Without it QuickTx would make it the payer's change (the payer is the
     * change address). The payer funds the calls' lovelace and the fee, as for any composition (ADR-0011, ADR-0013).
     * Refuses when that output would hold less than its ledger minimum, or a value above the ledger's maximum value
     * size. Returns what each such caller pays in, by its
     * address, for the prepared transaction's check ({@code TxRunner.checkCoCallers}).
     */
    static Map<String, Value> returnSurplus(org.julclang.state.runtime.StateRuntime rt, TxRunner.Capture cap, String payer) {
        var held = new LinkedHashMap<String, Value>();
        for (var e : cap.regularInputs.entrySet()) {
            String owner = cap.regularOwners.get(e.getKey());
            if (owner == null) {
                // Fail closed (R7): its surplus would go to the payer's change unnoticed.
                throw new JoustException("composition: cannot tell whose input " + e.getKey() + " is (the provider gave no"
                        + " address); refused");
            }
            if (owner.equals(payer)) continue;
            held.merge(owner, valueOf(e.getValue()), Assets::add);
        }
        var paid = new LinkedHashMap<String, Value>();
        if (held.isEmpty()) return paid;
        com.bloxbean.cardano.client.api.model.ProtocolParams pp = null;
        for (var e : held.entrySet()) {
            Value in = Assets.none();
            var units = cap.paidAssets.getOrDefault(e.getKey(), Map.of());
            for (var u : units.entrySet()) {
                in = Assets.add(in, Assets.of(HEX.parseHex(u.getKey().substring(0, 56)), HEX.parseHex(u.getKey().substring(56)), u.getValue()));
            }
            paid.put(e.getKey(), in);
            Value back = Assets.subtract(e.getValue(), in);
            var amounts = new ArrayList<Amount>();
            Assets.read(back).forEach((p, ts) -> ts.forEach((n, q) -> {
                if (q.signum() < 0) {
                    throw new JoustException("composition: " + e.getKey() + " pays in more " + p + n + " than its inputs hold");
                }
                if (q.signum() > 0) amounts.add(p.isEmpty() ? Amount.lovelace(q) : new Amount(p + n, q));
            }));
            if (amounts.isEmpty()) continue;
            if (pp == null) pp = TxRunner.protocolParams(rt, "composition");
            var out = TransactionOutput.builder().address(e.getKey()).value(specValue(back)).build();
            BigInteger min = new MinAdaCalculator(pp).calculateMinAda(out);
            if (Assets.lovelaceOf(back).compareTo(min) < 0) {
                throw new JoustException("composition: what " + e.getKey() + "'s inputs hold beyond its payment ("
                        + Assets.read(back) + ") is below the ledger minimum of an output (" + min + " lovelace) and cannot"
                        + " go back to it; compose with it as the payer, or pay from a UTxO with more ADA (T1)");
            }
            // One output holds what several inputs held: it may exceed the ledger's maximum value size (#165 Codex).
            // Refused rather than split: a split changes the outputs a page sees and needs min ADA for each.
            int size = out.getValue().getMultiAssets() == null || out.getValue().getMultiAssets().isEmpty() ? 0
                    : BodyBalance.valueSize(out.getValue(), "composition");
            int max = size == 0 ? 0 : BodyBalance.maxValueSize(pp, "composition");
            if (size > max) {
                throw new JoustException("composition: what " + e.getKey() + "'s inputs hold beyond its payment needs a "
                        + size + "-byte value, above the ledger's maximum of " + pp.getMaxValSize() + " bytes, and cannot"
                        + " go back to it in one output; compose with it as the payer, or pay from fewer UTxOs (T1)");
            }
            cap.tx.payToAddress(e.getKey(), amounts);
            // Checked by its place and value in the built body (TxRunner.checkCoCallers); counted, as a call's outputs are.
            cap.returns.put(e.getKey(), new TxRunner.Return(cap.outputs, back));
            cap.outputs++;
        }
        return paid;
    }

    private static String unitOf(Amount a) {
        return (a.getUnit().startsWith("0x") ? a.getUnit().substring(2) : a.getUnit()).toLowerCase();
    }

    private static boolean holdsAny(Utxo u, Map<String, BigInteger> missing) {
        for (Amount a : u.getAmount()) if (missing.containsKey(unitOf(a))) return true;
        return false;
    }

    private static void addAssets(Map<String, BigInteger> spare, Utxo u) {
        for (Amount a : u.getAmount()) {
            if (!a.getUnit().equals("lovelace")) spare.merge(unitOf(a), a.getQuantity(), BigInteger::add);
        }
    }

    /** Takes what {@code spare} holds of each missing unit. */
    private static void draw(Map<String, BigInteger> missing, Map<String, BigInteger> spare) {
        for (var it = missing.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            BigInteger have = spare.getOrDefault(e.getKey(), BigInteger.ZERO);
            if (have.signum() <= 0) continue;
            BigInteger take = have.min(e.getValue());
            spare.put(e.getKey(), have.subtract(take));
            if (take.equals(e.getValue())) it.remove();
            else e.setValue(e.getValue().subtract(take));
        }
    }

    /** Reserved token kinds (ADR-0007 D3): instance, object, intent. */
    static boolean reserved(byte[] n) {
        return n.length == 32 || (n.length == 29 && (n[0] == 0x4F || n[0] == 0x49));
    }
}
