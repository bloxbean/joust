package org.julclang.state.testkit;

import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.Interval;
import org.julclang.ledger.IntervalBound;
import org.julclang.ledger.IntervalBoundType;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptPurpose;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.testkit.ScriptContextTestBuilder;
import org.julclang.testkit.ValidatorTest;
import org.julclang.vm.EvalResult;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A ledger model for whole transactions (ADR-0016 §8). {@link StateHarness#evaluate} runs only the redeemers a test
 * supplies; this applies transactions to a UTxO set the way the ledger does, so a test cannot "accept" a transaction
 * one of whose scripts never ran:
 * <ul>
 *   <li>a slot clock: a transaction is applied only within its validity interval;</li>
 *   <li>protocol parameters: every output must hold the minimum lovelace for its size, so raised
 *   {@code coinsPerUTxOByte} tests are meaningful;</li>
 *   <li>phase 1: inputs exist, are unique and disjoint from reference inputs; value is conserved
 *   ({@code inputs + mint + withdrawals == outputs + fee}); every key-locked input is signed;</li>
 *   <li>every required script purpose (each script-locked input, each minting policy, each script withdrawal) is
 *   derived from the transaction, its script must be known, and the redeemers must be exactly those purposes;</li>
 *   <li>phase 2: every required script runs against the whole transaction, and their total budget must fit the
 *   per-transaction limits.</li>
 * </ul>
 * Out of the model: collateral, the transaction size limit, datum witnesses (inputs carry inline datums), certificates
 * and governance. Values are kept canonical (lovelace first, then policies and names in unsigned byte order).
 */
public final class Ledger {

    /** Milliseconds per slot (Conway mainnet and DevKit). Validity bounds are expected on slot boundaries. */
    public static final long SLOT_MS = 1_000L;

    /**
     * The protocol parameters the model checks.
     *
     * @param coinsPerUtxoByte lovelace per byte of an output (Conway mainnet: 4,310)
     * @param maxTxCpu         per-transaction CPU steps (mainnet: 10,000,000,000)
     * @param maxTxMem         per-transaction memory units (mainnet: 16,500,000; 14,000,000 before the Plomin raise)
     */
    public record Params(long coinsPerUtxoByte, long maxTxCpu, long maxTxMem) {
        public static Params mainnet() {
            return new Params(4_310L, 10_000_000_000L, 16_500_000L);
        }

        public Params withCoinsPerUtxoByte(long c) {
            return new Params(c, maxTxCpu, maxTxMem);
        }
    }

    /** A transaction as a builder writes it. Mutable, so tests can tamper with {@link #copy() copies}. */
    public static final class Tx {
        public final List<TxOutRef> inputs = new ArrayList<>();
        public final List<TxOutRef> referenceInputs = new ArrayList<>();
        public final List<TxOut> outputs = new ArrayList<>();
        /** policy hex -> token name hex -> quantity (non-zero). */
        public final Map<String, Map<String, BigInteger>> mint = new TreeMap<>();
        /** Required signers: what scripts see as the transaction's signatories. Each must also be a witness. */
        public final List<byte[]> signers = new ArrayList<>();
        /** Key witnesses (phase 1): every key-locked input's owner, required signer and key withdrawal needs one. */
        public final List<byte[]> witnesses = new ArrayList<>();
        /** Validity bounds in POSIX ms: {@code [lower, upper)}; null means unbounded. */
        public BigInteger lower;
        public BigInteger upper;
        public final Map<ScriptPurpose, PlutusData> redeemers = new LinkedHashMap<>();
        public final Map<Credential, BigInteger> withdrawals = new LinkedHashMap<>();
        public BigInteger fee = BigInteger.valueOf(300_000L);

        public Tx copy() {
            var c = new Tx();
            c.inputs.addAll(inputs);
            c.referenceInputs.addAll(referenceInputs);
            c.outputs.addAll(outputs);
            mint.forEach((p, m) -> c.mint.put(p, new TreeMap<>(m)));
            c.signers.addAll(signers);
            c.witnesses.addAll(witnesses);
            c.lower = lower;
            c.upper = upper;
            c.redeemers.putAll(redeemers);
            c.withdrawals.putAll(withdrawals);
            c.fee = fee;
            return c;
        }

        public Tx mint(byte[] policy, byte[] name, BigInteger qty) {
            var m = mint.computeIfAbsent(hex(policy), k -> new TreeMap<>());
            BigInteger q = m.getOrDefault(hex(name), BigInteger.ZERO).add(qty);
            if (q.signum() == 0) m.remove(hex(name));
            else m.put(hex(name), q);
            if (m.isEmpty()) mint.remove(hex(policy));
            return this;
        }

        public Tx window(BigInteger lower, BigInteger upper) {
            this.lower = lower;
            this.upper = upper;
            return this;
        }

        /** The mint as a canonical ledger value (no lovelace entry). */
        public Value mintValue() {
            var all = new TreeMap<String, Map<String, BigInteger>>();
            mint.forEach((p, m) -> all.put(p, new TreeMap<>(m)));
            return valueOf(all);
        }

        /** The validity interval as scripts see it: closed lower bound, open upper bound. */
        public Interval interval() {
            return new Interval(
                    new IntervalBound(lower == null ? new IntervalBoundType.NegInf() : new IntervalBoundType.Finite(lower), true),
                    new IntervalBound(upper == null ? new IntervalBoundType.PosInf() : new IntervalBoundType.Finite(upper),
                            upper == null));
        }
    }

    /** What applying a transaction did: accepted (and applied by {@link #submit}), or the first reason it was refused. */
    public record Result(boolean accepted, String reason, Map<String, EvalResult> scripts, long cpu, long mem, TxId txId) {
        @Override
        public String toString() {
            var sb = new StringBuilder(accepted ? "ACCEPTED" : "REJECTED: " + reason);
            sb.append(" (cpu=").append(cpu).append(", mem=").append(mem).append(')');
            scripts.forEach((k, r) -> {
                sb.append("\n  ").append(k).append(": ").append(r.isSuccess() ? "ok" : "FAILED")
                        .append(" cpu=").append(r.budgetConsumed().cpuSteps()).append(" mem=").append(r.budgetConsumed().memoryUnits());
                if (!r.isSuccess()) sb.append(" traces=").append(r.traces());
            });
            return sb.toString();
        }
    }

    private final Map<TxOutRef, TxOut> utxos = new LinkedHashMap<>();
    private final Map<String, Program> scripts = new HashMap<>();
    private final Map<String, Integer> scriptSizes = new HashMap<>();
    private final Map<Credential, BigInteger> rewards = new HashMap<>();
    private Params params;
    private BigInteger now;
    private long counter;

    public Ledger(Params params, BigInteger now) {
        this.params = params;
        this.now = now;
    }

    // ------------------------------------------------------------------ state

    /** Makes {@code program} the script of hash {@code hash} (its policy id, address credential and stake credential). */
    public void register(byte[] hash, Program program) {
        scripts.put(hex(hash), program);
    }

    /**
     * The size in bytes of the script with hash {@code hash}, for outputs that carry it as a reference script. Without it,
     * a reference script counts only its CBOR framing (7 bytes), so such outputs' minimum is underestimated.
     */
    public void setReferenceScriptSize(byte[] hash, int bytes) {
        scriptSizes.put(hex(hash), bytes);
    }

    /** Sets the reward balance of a stake credential (default 0). A withdrawal must take exactly this balance. */
    public void setReward(Credential c, BigInteger balance) {
        rewards.put(c, balance);
    }

    public BigInteger reward(Credential c) {
        return rewards.getOrDefault(c, BigInteger.ZERO);
    }

    /** Adds an output out of thin air (genesis funds); returns its reference. */
    public TxOutRef fund(TxOut out) {
        TxOutRef ref = new TxOutRef(nextTxId(), BigInteger.ZERO);
        utxos.put(ref, canonical(out));
        return ref;
    }

    public Map<TxOutRef, TxOut> utxos() {
        return Collections.unmodifiableMap(utxos);
    }

    public TxOut utxo(TxOutRef ref) {
        return utxos.get(ref);
    }

    public BigInteger now() {
        return now;
    }

    /** Advances the clock by {@code ms} (time only moves forward). */
    public void advance(long ms) {
        if (ms < 0) throw new IllegalArgumentException("time only moves forward");
        now = now.add(BigInteger.valueOf(ms));
    }

    public void setNow(BigInteger t) {
        now = t;
    }

    public Params params() {
        return params;
    }

    public void setParams(Params p) {
        params = p;
    }

    /** The current slot's start, in POSIX ms. */
    public BigInteger slotStart() {
        BigInteger slot = BigInteger.valueOf(SLOT_MS);
        return now.divide(slot).multiply(slot);
    }

    // ------------------------------------------------------------------ the rules

    /** Checks {@code tx} against the current state without applying it. */
    public Result check(Tx tx) {
        var scriptsRun = new LinkedHashMap<String, EvalResult>();
        String phase1 = phase1(tx);
        if (phase1 != null) return new Result(false, phase1, scriptsRun, 0, 0, null);
        TxId id = peekTxId();
        // Phase 2: every required purpose, each with the whole transaction.
        long cpu = 0;
        long mem = 0;
        var failed = new ArrayList<String>();
        for (var e : requiredPurposes(tx).entrySet()) {
            ScriptPurpose purpose = e.getKey();
            Program program = scripts.get(e.getValue());
            PlutusData redeemer = tx.redeemers.get(purpose);
            ScriptContextTestBuilder b = switch (purpose) {
                case ScriptPurpose.Spending s -> ScriptContextTestBuilder.spending(s.txOutRef(),
                        utxos.get(s.txOutRef()).datum() instanceof OutputDatum.OutputDatumInline d ? d.datum() : null);
                case ScriptPurpose.Minting m -> ScriptContextTestBuilder.minting(m.policyId());
                case ScriptPurpose.Rewarding r -> ScriptContextTestBuilder.rewarding(r.credential());
                default -> throw new IllegalStateException("purpose not modelled: " + purpose);
            };
            b.redeemer(redeemer);
            EvalResult r = ValidatorTest.evaluate(program, fill(b, tx, id).buildPlutusData());
            scriptsRun.put(label(purpose), r);
            cpu += r.budgetConsumed().cpuSteps();
            mem += r.budgetConsumed().memoryUnits();
            if (!r.isSuccess()) failed.add(label(purpose));
        }
        // Every script runs, so a refusal names each purpose that failed (the ledger stops at the first).
        if (!failed.isEmpty()) return new Result(false, "script failed: " + String.join(", ", failed), scriptsRun, cpu, mem, null);
        if (cpu > params.maxTxCpu() || mem > params.maxTxMem()) {
            return new Result(false, "execution budget exceeded", scriptsRun, cpu, mem, null);
        }
        return new Result(true, null, scriptsRun, cpu, mem, id);
    }

    /** Checks {@code tx} and, if every rule passes, applies it: its inputs are spent and its outputs created. */
    public Result submit(Tx tx) {
        Result r = check(tx);
        if (!r.accepted()) return r;
        TxId id = nextTxId();
        for (TxOutRef in : tx.inputs) utxos.remove(in);
        tx.withdrawals.forEach((c, amount) -> rewards.put(c, reward(c).subtract(amount)));
        for (int i = 0; i < tx.outputs.size(); i++) {
            utxos.put(new TxOutRef(id, BigInteger.valueOf(i)), canonical(tx.outputs.get(i)));
        }
        return new Result(true, null, r.scripts(), r.cpu(), r.mem(), id);
    }

    private String phase1(Tx tx) {
        if (tx.inputs.isEmpty()) return "no inputs";
        if (new LinkedHashSet<>(tx.inputs).size() != tx.inputs.size()) return "duplicate input";
        for (TxOutRef in : tx.inputs) if (!utxos.containsKey(in)) return "missing or spent input " + show(in);
        for (TxOutRef in : tx.referenceInputs) {
            if (!utxos.containsKey(in)) return "missing reference input " + show(in);
            if (tx.inputs.contains(in)) return "an input is also a reference input";
        }
        BigInteger slot = BigInteger.valueOf(SLOT_MS);
        if ((tx.lower != null && tx.lower.mod(slot).signum() != 0) || (tx.upper != null && tx.upper.mod(slot).signum() != 0)) {
            return "a validity bound not on a slot boundary";
        }
        if (tx.lower != null && now.compareTo(tx.lower) < 0) return "not valid yet (lower bound in the future)";
        if (tx.upper != null && now.compareTo(tx.upper) >= 0) return "expired (upper bound passed)";
        if (tx.fee.signum() < 0) return "negative fee";
        for (var m : tx.mint.entrySet()) {
            if (m.getKey().isEmpty()) return "lovelace cannot be minted";
            for (var q : m.getValue().values()) if (q.signum() == 0) return "zero mint entry";
        }
        for (TxOut o : tx.outputs) {
            for (var p : entries(o.value()).entrySet()) {
                for (var q : p.getValue().values()) if (q.signum() <= 0) return "non-positive quantity in an output";
            }
        }
        // Withdrawals: exactly the reward balance, never negative, a key credential's by its key's witness.
        for (var w : tx.withdrawals.entrySet()) {
            if (w.getValue().signum() < 0) return "negative withdrawal";
            if (!w.getValue().equals(reward(w.getKey()))) {
                return "a withdrawal must take the whole reward balance (" + reward(w.getKey()) + ")";
            }
            if (w.getKey() instanceof Credential.PubKeyCredential pk && !witnessed(tx, pk.hash().hash())) {
                return "a key withdrawal without its key's witness";
            }
        }
        // Value conservation, asset by asset.
        var balance = new TreeMap<String, Map<String, BigInteger>>();
        for (TxOutRef in : tx.inputs) add(balance, entries(utxos.get(in).value()), 1);
        add(balance, tx.mint, 1);
        for (var w : tx.withdrawals.values()) add(balance, Map.of("", Map.of("", w)), 1);
        for (TxOut o : tx.outputs) add(balance, entries(o.value()), -1);
        add(balance, Map.of("", Map.of("", tx.fee)), -1);
        for (var p : balance.entrySet()) {
            for (var q : p.getValue().entrySet()) {
                if (q.getValue().signum() != 0) {
                    return "value not conserved: " + (p.getKey().isEmpty() ? "lovelace" : p.getKey().substring(0, 8) + "." + q.getKey())
                            + " off by " + q.getValue();
                }
            }
        }
        // Minimum lovelace per output (Babbage/Conway: (160 + size) * coinsPerUTxOByte).
        for (int i = 0; i < tx.outputs.size(); i++) {
            TxOut o = tx.outputs.get(i);
            BigInteger min = minLovelace(o);
            if (o.value().lovelaceOf().compareTo(min) < 0) {
                return "output " + i + " below the minimum lovelace (" + o.value().lovelaceOf() + " < " + min + ")";
            }
        }
        // Key witnesses: every key-locked input's owner and every required signer.
        for (TxOutRef in : tx.inputs) {
            if (utxos.get(in).address().credential() instanceof Credential.PubKeyCredential pk && !witnessed(tx, pk.hash().hash())) {
                return "input " + show(in) + " is not signed by its key";
            }
        }
        for (byte[] s : tx.signers) if (!witnessed(tx, s)) return "a required signer without a witness";
        // Exactly the needed redeemers, each for a known script.
        var required = requiredPurposes(tx);
        for (var e : required.entrySet()) {
            if (!scripts.containsKey(e.getValue())) return "unknown script " + e.getValue().substring(0, 8);
            if (!tx.redeemers.containsKey(e.getKey())) return "missing redeemer: " + label(e.getKey());
        }
        for (ScriptPurpose p : tx.redeemers.keySet()) {
            if (!required.containsKey(p)) return "extraneous redeemer: " + label(p);
        }
        for (TxOutRef in : tx.inputs) {
            TxOut o = utxos.get(in);
            if (o.address().credential() instanceof Credential.ScriptCredential && o.datum() instanceof OutputDatum.OutputDatumHash) {
                return "script input with a datum hash (not modelled)";
            }
        }
        return null;
    }

    private static boolean witnessed(Tx tx, byte[] key) {
        return tx.witnesses.stream().anyMatch(w -> Arrays.equals(w, key));
    }

    /** Every purpose the transaction requires, with its script's hash (hex). */
    private Map<ScriptPurpose, String> requiredPurposes(Tx tx) {
        var out = new LinkedHashMap<ScriptPurpose, String>();
        for (TxOutRef in : sorted(tx.inputs)) {
            if (utxos.get(in).address().credential() instanceof Credential.ScriptCredential sc) {
                out.put(new ScriptPurpose.Spending(in), hex(sc.hash().hash()));
            }
        }
        for (String policy : tx.mint.keySet()) {
            out.put(new ScriptPurpose.Minting(new PolicyId(HexFormat.of().parseHex(policy))), policy);
        }
        for (Credential c : tx.withdrawals.keySet()) {
            if (c instanceof Credential.ScriptCredential sc) out.put(new ScriptPurpose.Rewarding(c), hex(sc.hash().hash()));
        }
        return out;
    }

    private ScriptContextTestBuilder fill(ScriptContextTestBuilder b, Tx tx, TxId id) {
        for (TxOutRef in : sorted(tx.inputs)) b.input(new TxInInfo(in, utxos.get(in)));
        for (TxOutRef in : sorted(tx.referenceInputs)) b.referenceInput(new TxInInfo(in, utxos.get(in)));
        for (TxOut o : tx.outputs) b.output(canonical(o));
        b.fee(tx.fee);
        b.mint(tx.mintValue());
        tx.withdrawals.forEach(b::withdrawal);
        var signers = new ArrayList<>(tx.signers);
        signers.sort(Arrays::compareUnsigned);
        byte[] prev = null;
        for (byte[] s : signers) {
            if (prev == null || !Arrays.equals(prev, s)) b.signer(new PubKeyHash(s));
            prev = s;
        }
        b.validRange(tx.interval());
        // The redeemer map in ledger order: Minting, Spending, Rewarding (Plutus V3's constructor order), then by key.
        var redeemers = new ArrayList<>(tx.redeemers.entrySet());
        redeemers.sort(Comparator.comparing((Map.Entry<ScriptPurpose, PlutusData> e) -> purposeOrder(e.getKey()))
                .thenComparing(e -> purposeKey(e.getKey()), Arrays::compareUnsigned));
        redeemers.forEach(e -> b.redeemerEntry(e.getKey(), e.getValue()));
        b.txId(id);
        return b;
    }

    private static int purposeOrder(ScriptPurpose p) {
        return switch (p) {
            case ScriptPurpose.Minting m -> 0;
            case ScriptPurpose.Spending s -> 1;
            case ScriptPurpose.Rewarding r -> 2;
            default -> 3;
        };
    }

    /** The bytes a purpose is ordered by within its kind (Spending: tx id then index; credentials: kind then hash). */
    private static byte[] purposeKey(ScriptPurpose p) {
        return switch (p) {
            case ScriptPurpose.Minting m -> m.policyId().hash();
            case ScriptPurpose.Spending s -> {
                byte[] id = s.txOutRef().txId().hash();
                byte[] k = Arrays.copyOf(id, id.length + 8);
                long ix = s.txOutRef().index().longValueExact();
                for (int i = 0; i < 8; i++) k[id.length + i] = (byte) (ix >>> (56 - 8 * i));
                yield k;
            }
            case ScriptPurpose.Rewarding r -> {
                boolean script = r.credential() instanceof Credential.ScriptCredential;
                byte[] h = script ? ((Credential.ScriptCredential) r.credential()).hash().hash()
                        : ((Credential.PubKeyCredential) r.credential()).hash().hash();
                byte[] k = new byte[h.length + 1];
                k[0] = (byte) (script ? 1 : 0);
                System.arraycopy(h, 0, k, 1, h.length);
                yield k;
            }
            default -> new byte[0];
        };
    }

    // ------------------------------------------------------------------ minimum lovelace

    /** {@code (160 + |output|) * coinsPerUTxOByte}, with the output's CBOR size computed as the ledger encodes it. */
    public BigInteger minLovelace(TxOut o) {
        int script = o.referenceScript().map(h -> scriptSizes.getOrDefault(hex(h.hash()), 0)).orElse(0);
        return BigInteger.valueOf((160L + outputSize(o, script)) * params.coinsPerUtxoByte());
    }

    /**
     * The CBOR size of an output as cardano-client-lib writes it: the post-Babbage map {0: address, 1: value, 2: datum
     * option, 3: script ref} when it has a datum or a reference script, else the legacy array [address, value].
     * {@code scriptBytes} is the reference script's size (0 if unknown).
     */
    public static long outputSize(TxOut o, int scriptBytes) {
        boolean legacy = o.datum() instanceof OutputDatum.NoOutputDatum && o.referenceScript().isEmpty();
        long size = 1;                                    // map or array header
        long key = legacy ? 0 : 1;                        // a map key before each field
        int addressBytes = o.address().stakingCredential().isPresent() ? 57 : 29;
        size += key + bytesSize(addressBytes);
        var e = entries(o.value());
        long coin = o.value().lovelaceOf().longValueExact();
        long assets = 0;
        int policies = 0;
        for (var p : e.entrySet()) {
            if (p.getKey().isEmpty()) continue;
            policies++;
            assets += bytesSize(p.getKey().length() / 2) + headerSize(p.getValue().size());
            for (var t : p.getValue().entrySet()) assets += bytesSize(t.getKey().length() / 2) + uintSize(t.getValue().longValueExact());
        }
        size += key + (policies == 0 ? uintSize(coin) : 1 + uintSize(coin) + headerSize(policies) + assets);
        if (o.datum() instanceof OutputDatum.OutputDatumInline d) {
            int datum = org.julclang.core.cbor.PlutusDataCborEncoder.encode(d.datum()).length;
            size += 1 + 1 + 1 + 2 + bytesSize(datum);    // key, [1, #6.24(bytes)]
        } else if (o.datum() instanceof OutputDatum.OutputDatumHash) {
            size += 1 + 1 + 1 + bytesSize(32);            // key, [0, hash]
        }
        if (o.referenceScript().isPresent()) {
            size += 1 + 2 + bytesSize(1 + 1 + bytesSize(scriptBytes));   // key, #6.24(bytes [type, script])
        }
        return size;
    }

    private static long headerSize(long n) {
        return uintSize(n);
    }

    private static long bytesSize(long n) {
        return uintSize(n) + n;
    }

    private static long uintSize(long n) {
        return n < 24 ? 1 : n < 256 ? 2 : n < 65_536 ? 3 : n < 4_294_967_296L ? 5 : 9;
    }

    // ------------------------------------------------------------------ values

    /** A value's entries: policy hex ("" for lovelace) -> name hex -> quantity. */
    public static Map<String, Map<String, BigInteger>> entries(Value v) {
        var out = new TreeMap<String, Map<String, BigInteger>>();
        PlutusData.MapData m = v.toPlutusData();
        for (var p : m.entries()) {
            String policy = hex(((PlutusData.BytesData) p.key()).value());
            var names = out.computeIfAbsent(policy, k -> new TreeMap<>());
            for (var t : ((PlutusData.MapData) p.value()).entries()) {
                String name = hex(((PlutusData.BytesData) t.key()).value());
                names.merge(name, ((PlutusData.IntData) t.value()).value(), BigInteger::add);
            }
        }
        out.values().removeIf(Map::isEmpty);
        return out;
    }

    /** A canonical value: lovelace first, then policies and names in unsigned byte order; zero entries dropped. */
    public static Value valueOf(Map<String, Map<String, BigInteger>> entries) {
        var pairs = new ArrayList<PlutusData.Pair>();
        var keys = new ArrayList<>(entries.keySet());
        keys.sort(Comparator.comparing(HexFormat.of()::parseHex, Arrays::compareUnsigned));
        for (String p : keys) {
            var names = new ArrayList<>(entries.get(p).keySet());
            names.sort(Comparator.comparing(HexFormat.of()::parseHex, Arrays::compareUnsigned));
            var inner = new ArrayList<PlutusData.Pair>();
            for (String n : names) {
                BigInteger q = entries.get(p).get(n);
                if (q.signum() != 0) inner.add(new PlutusData.Pair(new PlutusData.BytesData(HexFormat.of().parseHex(n)),
                        new PlutusData.IntData(q)));
            }
            if (!inner.isEmpty()) pairs.add(new PlutusData.Pair(new PlutusData.BytesData(HexFormat.of().parseHex(p)),
                    new PlutusData.MapData(inner)));
        }
        return Value.fromPlutusData(new PlutusData.MapData(pairs));
    }

    public static Value canonical(Value v) {
        return valueOf(entries(v));
    }

    public static TxOut canonical(TxOut o) {
        return new TxOut(o.address(), canonical(o.value()), o.datum(), o.referenceScript());
    }

    /** {@code lovelace} plus one unit of each {@code (policy, name)}. */
    public static Value value(BigInteger lovelace, byte[] policy, byte[]... names) {
        var e = new TreeMap<String, Map<String, BigInteger>>();
        e.put("", new TreeMap<>(Map.of("", lovelace)));
        for (byte[] n : names) e.computeIfAbsent(hex(policy), k -> new TreeMap<>()).merge(hex(n), BigInteger.ONE, BigInteger::add);
        return valueOf(e);
    }

    /** The quantity of {@code (policy, name)} held by every UTxO, summed. */
    public BigInteger supply(byte[] policy, byte[] name) {
        BigInteger total = BigInteger.ZERO;
        for (TxOut o : utxos.values()) total = total.add(o.value().assetOf(new PolicyId(policy), new TokenName(name)));
        return total;
    }

    /** Every token name of {@code policy} held anywhere, with its total quantity. */
    public Map<String, BigInteger> tokensOf(byte[] policy) {
        var out = new TreeMap<String, BigInteger>();
        for (TxOut o : utxos.values()) {
            var names = entries(o.value()).get(hex(policy));
            if (names != null) names.forEach((n, q) -> out.merge(n, q, BigInteger::add));
        }
        return out;
    }

    /** The UTxOs holding {@code (policy, name)}. */
    public List<Map.Entry<TxOutRef, TxOut>> holding(byte[] policy, byte[] name) {
        var out = new ArrayList<Map.Entry<TxOutRef, TxOut>>();
        for (var e : utxos.entrySet()) {
            if (e.getValue().value().assetOf(new PolicyId(policy), new TokenName(name)).signum() != 0) out.add(e);
        }
        return out;
    }

    /** The UTxOs at {@code address}. */
    public List<Map.Entry<TxOutRef, TxOut>> at(Address address) {
        var out = new ArrayList<Map.Entry<TxOutRef, TxOut>>();
        for (var e : utxos.entrySet()) if (e.getValue().address().equals(address)) out.add(e);
        return out;
    }

    private static void add(Map<String, Map<String, BigInteger>> acc, Map<String, Map<String, BigInteger>> v, int sign) {
        v.forEach((p, names) -> names.forEach((n, q) -> acc.computeIfAbsent(p, k -> new TreeMap<>())
                .merge(n, sign > 0 ? q : q.negate(), BigInteger::add)));
    }

    // ------------------------------------------------------------------ helpers

    /** Inputs in ledger order: by transaction id (unsigned bytes), then index. */
    public static List<TxOutRef> sorted(java.util.Collection<TxOutRef> refs) {
        var out = new ArrayList<>(refs);
        out.sort(Comparator.<TxOutRef, byte[]>comparing(r -> r.txId().hash(), Arrays::compareUnsigned)
                .thenComparing(TxOutRef::index));
        return out;
    }

    private TxId peekTxId() {
        return txIdFor(counter + 1);
    }

    private TxId nextTxId() {
        counter++;
        return txIdFor(counter);
    }

    private static TxId txIdFor(long n) {
        byte[] seed = ("joust-ledger-model:" + n).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new TxId(com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(seed));
    }

    private static String label(ScriptPurpose p) {
        return switch (p) {
            case ScriptPurpose.Spending s -> "SPEND " + show(s.txOutRef());
            case ScriptPurpose.Minting m -> "MINT " + hex(m.policyId().hash()).substring(0, 8);
            case ScriptPurpose.Rewarding r -> "WITHDRAW " + r.credential();
            default -> p.toString();
        };
    }

    private static String show(TxOutRef r) {
        return hex(r.txId().hash()).substring(0, 8) + "#" + r.index();
    }

    static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }
}
