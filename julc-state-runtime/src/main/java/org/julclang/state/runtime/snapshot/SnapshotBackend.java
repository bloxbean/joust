package org.julclang.state.runtime.snapshot;

import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.*;
import com.bloxbean.cardano.client.backend.model.Block;
import com.bloxbean.cardano.client.backend.model.EpochContent;
import com.bloxbean.cardano.client.backend.model.ScriptDatum;
import com.bloxbean.cardano.client.backend.model.ScriptDatumCbor;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.PlutusV1Script;
import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link BackendService} over a {@link Snapshot} (ADR-0015 D5): reads answer from the snapshot, any other read
 * throws {@link Need}, and nothing is submitted. No I/O, no reflection: the same class runs in the Wasm runtime.
 * <p>
 * Paging is the snapshot's: an address's set is complete, served in pages of the requested size.
 */
public final class SnapshotBackend implements BackendService {

    private final Snapshot s;
    private final Utxos utxos = new Utxos();
    private final Blocks blocks = new Blocks();
    private final Epochs epochs = new Epochs();
    private final Scripts scripts = new Scripts();
    private final Transactions transactions = new Transactions();

    public SnapshotBackend(Snapshot snapshot) {
        this.s = snapshot;
    }

    public Snapshot snapshot() {
        return s;
    }

    @Override
    public UtxoService getUtxoService() {
        return utxos;
    }

    @Override
    public BlockService getBlockService() {
        return blocks;
    }

    @Override
    public EpochService getEpochService() {
        return epochs;
    }

    @Override
    public ScriptService getScriptService() {
        return scripts;
    }

    @Override
    public AssetService getAssetService() {
        throw new Need("service", "asset");
    }

    @Override
    public NetworkInfoService getNetworkInfoService() {
        throw new Need("service", "networkInfo");
    }

    @Override
    public PoolService getPoolService() {
        throw new Need("service", "pool");
    }

    @Override
    public TransactionService getTransactionService() {
        return transactions;   // CCL's builders take it eagerly; using it fails
    }

    @Override
    public AddressService getAddressService() {
        throw new Need("service", "address");
    }

    @Override
    public AccountService getAccountService() {
        throw new Need("service", "account");
    }

    @Override
    public MetadataService getMetadataService() {
        throw new Need("service", "metadata");
    }

    private static <T> Result<T> ok(T value) {
        Result<T> r = Result.success("snapshot");
        r.code(200);
        return r.withValue(value);
    }

    private static List<Utxo> page(List<Utxo> all, int count, int page, OrderEnum order) {
        List<Utxo> ordered = all;
        if (order == OrderEnum.desc) {
            ordered = new ArrayList<>(all);
            java.util.Collections.reverse(ordered);
        }
        int from = Math.max(0, (page - 1) * count);
        if (count <= 0 || from >= ordered.size()) return new ArrayList<>();
        return new ArrayList<>(ordered.subList(from, Math.min(ordered.size(), from + count)));
    }

    final class Utxos implements UtxoService {
        @Override
        public Result<List<Utxo>> getUtxos(String address, int count, int page) {
            return getUtxos(address, count, page, OrderEnum.asc);
        }

        @Override
        public Result<List<Utxo>> getUtxos(String address, int count, int page, OrderEnum order) {
            List<Utxo> all = s.utxos.get(address);
            if (all == null) throw new Need("utxos", address);
            return ok(page(all, count, page, order));
        }

        @Override
        public Result<List<Utxo>> getUtxos(String address, String unit, int count, int page) {
            return getUtxos(address, unit, count, page, OrderEnum.asc);
        }

        @Override
        public Result<List<Utxo>> getUtxos(String address, String unit, int count, int page, OrderEnum order) {
            // The set by unit first: a recorded address listing can be a partial one (its first page).
            List<Utxo> some = s.assetUtxos.get(address + "|" + unit);
            if (some != null) return ok(page(some, count, page, order));
            List<Utxo> all = s.utxos.get(address);
            if (all != null) {
                List<Utxo> holding = new ArrayList<>();
                for (Utxo u : all) {
                    if (u.getAmount().stream().anyMatch(a -> a.getUnit().equals(unit))) holding.add(u);
                }
                return ok(page(holding, count, page, order));
            }
            throw new Need("assetUtxos", address + "|" + unit);
        }

        @Override
        public Result<Utxo> getTxOutput(String txHash, int outputIndex) {
            Utxo u = s.output(txHash, outputIndex);
            if (u != null) return ok(u);
            if (s.absent.contains(Snapshot.ref(txHash, outputIndex))) {
                Result<Utxo> r = Result.error("not found");
                r.code(404);
                return r;
            }
            throw new Need("output", Snapshot.ref(txHash, outputIndex));
        }
    }

    /** Submission and confirmation are the JS layer's (ADR-0015 D5). */
    static final class Transactions implements TransactionService {
        private static IllegalStateException none() {
            return new IllegalStateException("a snapshot backend submits and looks up no transactions (ADR-0015 D5)");
        }

        @Override
        public Result<String> submitTransaction(byte[] cbor) {
            throw none();
        }

        @Override
        public Result<com.bloxbean.cardano.client.backend.model.TransactionContent> getTransaction(String txnHash) {
            throw none();
        }

        @Override
        public Result<List<com.bloxbean.cardano.client.backend.model.TransactionContent>> getTransactions(List<String> txnHashCollection) {
            throw none();
        }

        @Override
        public Result<com.bloxbean.cardano.client.backend.model.TxContentUtxo> getTransactionUtxos(String txnHash) {
            throw none();
        }

        @Override
        public Result<List<com.bloxbean.cardano.client.backend.model.TxContentRedeemers>> getTransactionRedeemers(String txnHash) {
            throw none();
        }
    }

    final class Blocks implements BlockService {
        @Override
        public Result<Block> getLatestBlock() {
            if (s.tip == null) throw new Need("tip", "");
            return ok(s.tip);
        }

        @Override
        public Result<Block> getBlockByHash(String hash) {
            throw new Need("block", hash);
        }

        @Override
        public Result<Block> getBlockByNumber(BigInteger number) {
            throw new Need("block", String.valueOf(number));
        }
    }

    final class Epochs implements EpochService {
        @Override
        public Result<EpochContent> getLatestEpoch() {
            throw new Need("epoch", "");
        }

        @Override
        public Result<EpochContent> getEpoch(Integer epoch) {
            throw new Need("epoch", String.valueOf(epoch));
        }

        @Override
        public Result<ProtocolParams> getProtocolParameters(Integer epoch) {
            return getProtocolParameters();
        }

        @Override
        public Result<ProtocolParams> getProtocolParameters() {
            if (s.protocolParams == null) throw new Need("protocolParams", "");
            return ok(s.protocolParams);
        }
    }

    final class Scripts implements ScriptService {
        @Override
        public Result<PlutusScript> getPlutusScript(String scriptHash) {
            Snapshot.Script sc = s.scripts.get(scriptHash);
            if (sc == null) throw new Need("script", scriptHash);
            PlutusScript p = switch (sc.type()) {
                case "PlutusScriptV1" -> PlutusV1Script.builder().cborHex(sc.cborHex()).build();
                case "PlutusScriptV2" -> PlutusV2Script.builder().cborHex(sc.cborHex()).build();
                default -> PlutusV3Script.builder().cborHex(sc.cborHex()).build();
            };
            return ok(p);
        }

        @Override
        public Result<String> getPlutusScriptCbor(String scriptHash) {
            Snapshot.Script sc = s.scripts.get(scriptHash);
            if (sc == null) throw new Need("script", scriptHash);
            return ok(sc.cborHex());
        }

        @Override
        public Result<ScriptDatum> getScriptDatum(String datumHash) {
            throw new Need("datum", datumHash);
        }

        @Override
        public Result<ScriptDatumCbor> getScriptDatumCbor(String datumHash) {
            throw new Need("datum", datumHash);
        }

        @Override
        public Result<JsonNode> getNativeScriptJson(String scriptHash) {
            throw new Need("nativeScript", scriptHash);
        }
    }
}
