package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxOutRef;
import org.julclang.state.api.RequireException;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * One intent (ADR-0004): a request to run a {@code @Batched} action, locked in its own UTxO at the contract address
 * with a 29-byte {@code 0x49} token. A batcher applies it (and burns the token); until then its owner can cancel it.
 *
 * @param <S> the state type
 */
public final class IntentHandle<S> {

    /** Lovelace an intent carries beyond its payment by default: returned to the owner as the refund. */
    public static final BigInteger DEFAULT_DEPOSIT = BigInteger.valueOf(2_000_000);

    private final StateInstance<S> parent;
    private final IntentCodec<S> codec;
    private final String intentId;

    IntentHandle(StateInstance<S> parent, IntentCodec<S> codec, String intentId) {
        this.parent = parent;
        this.codec = codec;
        this.intentId = intentId.toLowerCase();
    }

    /** An existing intent of {@code parent}, by its id (hex token name). */
    public static <S> IntentHandle<S> at(StateInstance<S> parent, IntentCodec<S> codec, String intentId) {
        return new IntentHandle<>(parent, codec, intentId);
    }

    public String intentId() { return intentId; }
    public String unit() { return parent.contract().policyId() + intentId; }

    /** The intent id a seed UTxO produces: {@code 0x49 ++ blake2b_224(txId ++ I2OSP(index, 2))}, hex. */
    public static String intentIdOf(String seedTxHash, int seedIndex) {
        byte[] tx = HexUtil.decodeHexString(seedTxHash);
        byte[] buf = Arrays.copyOf(tx, tx.length + 2);
        buf[tx.length] = (byte) (seedIndex >> 8);
        buf[tx.length + 1] = (byte) seedIndex;
        return "49" + HexUtil.encodeHexString(Blake2bUtil.blake2bHash224(buf));
    }

    /** True while the intent is waiting: neither batched nor cancelled. */
    public boolean isPending() {
        return find() != null;
    }

    /** The intent's fields, while it is pending. */
    public IntentCodec.IntentData data() {
        Utxo u = find();
        if (u == null) throw new InstanceNotFoundException("intent " + intentId + " is not pending");
        return Batcher.decode(codec, u);
    }

    Utxo find() {
        var c = parent.contract();
        try {
            var r = c.runtime.backend().getUtxoService().getUtxos(c.address, unit(), 10, 1);
            if (r.isSuccessful() && r.getValue() != null) {
                for (Utxo u : r.getValue()) {
                    if (u.getAmount().stream().anyMatch(a -> a.getUnit().equalsIgnoreCase(unit()))) return u;
                }
            }
            return null;
        } catch (Exception e) {
            throw new JoustException("cannot query the intent UTxO", e);
        }
    }

    // ------------------------------------------------------------------ create

    /**
     * Locks an intent for {@code call} (the generated {@code <Action>Intent} record): {@code received} goes to the
     * contract when it is applied; {@code deposit} comes back to the owner's address, tagged, at batch or cancel time.
     */
    public static <S> IntentHandle<S> create(StateInstance<S> parent, IntentCodec<S> codec, Account owner, Object call,
                                             BigInteger received, BigInteger deposit, Instant deadline) {
        var contract = parent.contract();
        var rt = contract.runtime;
        String what = contract.spec.name() + " intent";
        if (received.signum() < 0) throw new RequireException(what + ": received must not be negative");
        if (deposit.compareTo(DEFAULT_DEPOSIT) < 0) throw new RequireException(what + ": the deposit must be at least 2 ADA");
        Utxo ref = contract.ensureReferenceScript(owner);
        Utxo seed = contract.pickSeedFor(owner);
        String intentId = intentIdOf(seed.getTxHash(), seed.getOutputIndex());
        var handle = new IntentHandle<>(parent, codec, intentId);
        byte[] ownerPkh = Keys.pkh(owner);
        Object intent = codec.intent(HexUtil.decodeHexString(parent.instanceId()), ownerPkh,
                LedgerAddresses.toLedger(owner.baseAddress()), BigInteger.valueOf(deadline.toEpochMilli()), received, call);
        PlutusData datum = PlutusDataAdapter.convert(intent);
        Datums.checkSize(contract.spec, datum, what);
        var seedRef = new TxOutRef(new TxId(HexUtil.decodeHexString(seed.getTxHash())), BigInteger.valueOf(seed.getOutputIndex()));
        PlutusData redeemer = PlutusDataAdapter.convert(codec.create(seedRef, intent));
        BigInteger lovelace = received.add(deposit);
        var tx = new ScriptTx()
                .collectFrom(seed)
                .readFrom(ref)
                .mintAsset(contract.policyId, List.of(new Asset("0x" + intentId, BigInteger.ONE)), redeemer)
                .payToContract(contract.address, List.of(Amount.lovelace(lovelace), new Amount(handle.unit(), BigInteger.ONE)), datum);
        String txHash = new TxRunner(rt).submit(tx, owner, null, new byte[][]{ownerPkh},
                new TxRunner.ExpectedState(handle.unit(), lovelace, datum), List.of(), new PlutusScript[]{contract.script}, what);
        for (int i = 0; i < 60 && !handle.isPending(); i++) TxRunner.sleep(500);
        if (!handle.isPending()) throw new PendingTransactionException(txHash, what + ": included, but not visible yet");
        rt.log(what + " " + intentId.substring(0, 10) + "… locked in " + txHash);
        return handle;
    }

    // ------------------------------------------------------------------ cancel

    /** Withdraws the intent: burns its token and returns everything it holds to the owner (who must sign). */
    public String cancel(Account owner) {
        var contract = parent.contract();
        String what = contract.spec.name() + " cancel intent";
        Utxo u = find();
        if (u == null) throw new InstanceNotFoundException(what + ": intent " + intentId + " is not pending (batched or cancelled)");
        byte[] ownerPkh = Keys.pkh(owner);
        if (!Arrays.equals(Batcher.decode(codec, u).owner(), ownerPkh)) throw new RequireException(what + ": only the owner can cancel");
        BigInteger lovelace = u.getAmount().stream().filter(a -> a.getUnit().equals("lovelace"))
                .map(Amount::getQuantity).findFirst().orElse(BigInteger.ZERO);
        var tx = new ScriptTx()
                .collectFrom(u, PlutusDataAdapter.convert(codec.cancel()))
                .readFrom(contract.findReferenceScript())
                .mintAsset(contract.policyId, List.of(new Asset("0x" + intentId, BigInteger.ONE.negate())),
                        PlutusDataAdapter.convert(codec.burn()))
                .payToAddress(owner.enterpriseAddress(), Amount.lovelace(lovelace));
        return new TxRunner(contract.runtime).submit(tx, owner, null, new byte[][]{ownerPkh}, null, List.of(),
                new PlutusScript[]{contract.script}, what);
    }
}
