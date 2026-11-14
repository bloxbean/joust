package org.julclang.state.dsl.v2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M3c (ADR-0010, ADR-0018 D5): the ledger types of the script purposes, as {@code .jsc} 2 reads them. Each is JuLC's
 * ledger type ({@code org.julclang.ledger}), with its constructors in the ledger's tag order and its fields under JuLC's
 * names, typed in {@code .jsc}: a {@code PubKeyHash} is a {@code Key}, a {@code ScriptHash} a {@code Hash}, a
 * {@code TxId} {@code Bytes}, a {@code JulcMap} a read-only {@code LedgerMap}. A field typed {@code PlutusData}
 * ({@code ParameterChange.parameters}) is {@link #DATA}: it exists, so its constructor's arity and tag are the ledger's,
 * but it is inaccessible (JSC1320). The table is the one definition the checker, the emitter, both reference interpreters
 * (the JavaScript one through the IR's types and this table's twin, {@code js/jsc-interpreter/src/gov.js}) and the ABI
 * read; {@code JscV2M3cCheckerTest} checks it against JuLC's records by reflection.
 */
final class Gov {
    private Gov() {}

    /** How JuLC's record holds a field: as the {@code .jsc} type, a hash newtype ({@code .hash()}), an Optional of one. */
    enum Wire { DIRECT, HASH, OPT_HASH }

    /** A field: its name (JuLC's component), its {@code .jsc} type, how JuLC holds it. */
    record Field(String name, Ty type, Wire wire) {
        boolean opaque() {
            return type.equals(DATA);
        }
    }

    /**
     * A constructor of a sealed ledger type ({@code type} its type, {@code tag} its constructor index) or a ledger record
     * ({@code name} equal to {@code type}, tag 0).
     */
    record Ctor(String type, String name, int tag, List<Field> fields) {
        boolean record() {
            return type.equals(name);
        }

        /** The JuLC class: {@code TxCert.RegStaking}, or the record's own {@code GovernanceActionId}. */
        String javaClass() {
            return record() ? type : type + "." + name;
        }

        Field field(String f) {
            for (Field x : fields) if (x.name().equals(f)) return x;
            return null;
        }

        LinkedHashMap<String, Ty> types() {
            var out = new LinkedHashMap<String, Ty>();
            for (Field f : fields) out.put(f.name(), f.type());
            return out;
        }
    }

    /** A field typed {@code PlutusData}: carried, never read (JSC1320). */
    static final Ty DATA = new Ty.Gov("Data");

    static final Ty TX_CERT = new Ty.Gov("TxCert"), VOTER = new Ty.Gov("Voter"), VOTE = new Ty.Gov("Vote"),
            GOV_ACTION_ID = new Ty.Gov("GovernanceActionId"), PROPOSAL = new Ty.Gov("ProposalProcedure"),
            GOV_ACTION = new Ty.Gov("GovernanceAction"), DELEGATEE = new Ty.Gov("Delegatee"), DREP = new Ty.Gov("DRep"),
            PROTOCOL_VERSION = new Ty.Gov("ProtocolVersion"), RATIONAL = new Ty.Gov("Rational"), CONSTITUTION = new Ty.Gov("Constitution");

    /** The sealed types, each with its constructors in tag order. */
    static final Map<String, List<Ctor>> SUMS = new LinkedHashMap<>();
    /** The records, read by projection. */
    static final Map<String, Ctor> RECORDS = new LinkedHashMap<>();

    private static final Ty CRED = Ty.CREDENTIAL, INT = Ty.INT;
    private static final Ty OPT_ID = new Ty.OptT(GOV_ACTION_ID), OPT_HASH = new Ty.OptT(Ty.HASH);
    private static final Ty CRED_MAP = new Ty.LedgerMapT(Ty.CREDENTIAL, Ty.INT);

    private static Field f(String n, Ty t) {
        return new Field(n, t, Wire.DIRECT);
    }

    private static Field h(String n, Ty t) {
        return new Field(n, t, t instanceof Ty.OptT ? Wire.OPT_HASH : Wire.HASH);
    }

    private static void sum(String type, Object[]... ctors) {
        var out = new ArrayList<Ctor>();
        for (Object[] c : ctors) {
            var fs = new ArrayList<Field>();
            for (int i = 1; i < c.length; i++) fs.add((Field) c[i]);
            out.add(new Ctor(type, (String) c[0], out.size(), List.copyOf(fs)));
        }
        SUMS.put(type, List.copyOf(out));
    }

    private static Object[] c(String name, Field... fs) {
        Object[] o = new Object[fs.length + 1];
        o[0] = name;
        System.arraycopy(fs, 0, o, 1, fs.length);
        return o;
    }

    private static void rec(String type, Field... fs) {
        RECORDS.put(type, new Ctor(type, type, 0, List.of(fs)));
    }

    static {
        // org.julclang.ledger.TxCert: Conway's certificates, tags 0-10.
        sum("TxCert",
                c("RegStaking", f("credential", CRED), f("deposit", new Ty.OptT(INT))),
                c("UnRegStaking", f("credential", CRED), f("refund", new Ty.OptT(INT))),
                c("DelegStaking", f("credential", CRED), f("delegatee", DELEGATEE)),
                c("RegDeleg", f("credential", CRED), f("delegatee", DELEGATEE), f("deposit", INT)),
                c("RegDRep", f("credential", CRED), f("deposit", INT)),
                c("UpdateDRep", f("credential", CRED)),
                c("UnRegDRep", f("credential", CRED), f("refund", INT)),
                c("PoolRegister", h("poolId", Ty.KEY), h("poolVfr", Ty.KEY)),
                c("PoolRetire", h("pubKeyHash", Ty.KEY), f("epoch", INT)),
                c("AuthHotCommittee", f("cold", CRED), f("hot", CRED)),
                c("ResignColdCommittee", f("cold", CRED)));
        sum("Delegatee",
                c("Stake", h("poolId", Ty.KEY)),
                c("Vote", f("dRep", DREP)),
                c("StakeVote", h("poolId", Ty.KEY), f("dRep", DREP)));
        sum("DRep",
                c("DRepCredential", f("credential", CRED)),
                c("AlwaysAbstain"),
                c("AlwaysNoConfidence"));
        sum("Voter",
                c("CommitteeVoter", f("credential", CRED)),
                c("DRepVoter", f("credential", CRED)),
                c("StakePoolVoter", h("pubKeyHash", Ty.KEY)));
        sum("Vote", c("VoteNo"), c("VoteYes"), c("Abstain"));
        sum("GovernanceAction",
                c("ParameterChange", f("id", OPT_ID), f("parameters", DATA), h("constitutionScript", OPT_HASH)),
                c("HardForkInitiation", f("id", OPT_ID), f("protocolVersion", PROTOCOL_VERSION)),
                c("TreasuryWithdrawals", f("withdrawals", CRED_MAP), h("constitutionScript", OPT_HASH)),
                c("NoConfidence", f("id", OPT_ID)),
                c("UpdateCommittee", f("id", OPT_ID), f("removedMembers", new Ty.ListT(CRED)), f("addedMembers", CRED_MAP),
                        f("newQuorum", RATIONAL)),
                c("NewConstitution", f("id", OPT_ID), f("constitution", CONSTITUTION)),
                c("InfoAction"));
        rec("GovernanceActionId", h("txId", Ty.BYTES), f("govActionIx", INT));
        rec("ProposalProcedure", f("deposit", INT), f("returnAddress", CRED), f("governanceAction", GOV_ACTION));
        rec("ProtocolVersion", f("major", INT), f("minor", INT));
        rec("Rational", f("numerator", INT), f("denominator", INT));
        rec("Constitution", h("script", OPT_HASH));
    }

    /** The type names a contract may write (the purposes' parameters, a fn's, a const's): every type but {@link #DATA}. */
    static final Set<String> NAMES;

    static {
        var n = new java.util.LinkedHashSet<String>();
        n.addAll(SUMS.keySet());
        n.addAll(RECORDS.keySet());
        NAMES = java.util.Collections.unmodifiableSet(n);
    }

    static boolean isSum(Ty t) {
        return t instanceof Ty.Gov g && SUMS.containsKey(g.name());
    }

    static boolean isRecord(Ty t) {
        return t instanceof Ty.Gov g && RECORDS.containsKey(g.name());
    }

    /** The constructor {@code name} of the sealed type {@code type}, or null. */
    static Ctor ctor(String type, String name) {
        for (Ctor c : SUMS.getOrDefault(type, List.of())) if (c.name().equals(name)) return c;
        return null;
    }

    /** The record or constructor a value's shape names: a record's type, or {@code Type.Ctor}. */
    static Ctor shape(String qualified) {
        int dot = qualified.indexOf('.');
        if (dot < 0) return RECORDS.get(qualified);
        return ctor(qualified.substring(0, dot), qualified.substring(dot + 1));
    }

    /** The constructor names of a sealed type, in tag order. */
    static List<String> ctorNames(String type) {
        var out = new ArrayList<String>();
        for (Ctor c : SUMS.get(type)) out.add(c.name());
        return out;
    }

    /** Whether a type holds a purposes' ledger type or a LedgerMap anywhere (never stored, compared or passed: JSC1321). */
    static boolean holds(Ty t) {
        return switch (t) {
            case Ty.Gov g -> true;
            case Ty.LedgerMapT m -> true;
            case Ty.ListT l -> holds(l.elem());
            case Ty.OptT o -> holds(o.elem());
            case Ty.MapT m -> holds(m.val());
            default -> false;
        };
    }
}
