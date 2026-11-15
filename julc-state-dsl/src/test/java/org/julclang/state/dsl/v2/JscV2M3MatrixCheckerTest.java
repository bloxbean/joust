package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3 final pass (design MD2): the combination matrix of the semantics (§2.12), cell by cell at the checker. Each
 * part is a small piece of a contract; each pair of parts is put in one contract and lowered. A refused cell is refused with
 * its code; an accepted cell lowers, and the coverage contract or port that runs it on all five legs is named in §2.12
 * (JscV2M3MatrixTest, the M3 ports' five-way tests).
 */
class JscV2M3MatrixCheckerTest {

    record Part(String opts, String imports, String params, String state, String init, List<String> members) {}

    static final String Q = "quorum params.council params.quorum";
    static final String WITHDRAW = "withdraw collect(amount: Int) " + Q + " {\n    if amount > 0 {\n      send(wallet(params.payee), Value.lovelace(amount))\n    }\n  }";
    static final String CERTIFY = "certify reg(cert: TxCert) " + Q + " = true";

    /** The parts, in the matrix's order. */
    static final Map<String, Part> PARTS = new LinkedHashMap<>();
    static {
        PARTS.put("owned", new Part("", "", "", "", "", List.of("owned Box { owner: Key, n: Int }",
                "owned init Box.make() { return { owner: caller, n: 0 } }",
                "owned action Box.bump() only owner { return { ...state, n: n + 1 } }",
                "owned destroy Box.close() only owner { pay(owner, balance) }")));
        PARTS.put("keyed", new Part("", "", "", "", "", List.of("owned Slot keyed by key { key: Key, c: Int }",
                "owned init Slot.claim() { return { key: caller, c: 0 } }",
                "owned action Slot.raise() only key { return { ...state, c: c + 1 } }",
                "owned destroy Slot.leave() only key retire { pay(key, balance) }")));
        PARTS.put("bigState", new Part("", "", "", ", book: BigMap<Key, Int>", ", book: BigMap.empty()",
                List.of("action note(v: Int) { return { ...state, book: book.set(caller, v) } }")));
        PARTS.put("bigObj", new Part("", "", "", "", "", List.of("owned Vault { owner: Key, notes: BigMap<Bytes, Int> }",
                "owned init Vault.open() { return { owner: caller, notes: BigMap.empty() } }",
                "owned action Vault.write(k: Bytes, v: Int) only owner { return { ...state, notes: notes.set(k, v) } }",
                "owned destroy Vault.shut() only owner { pay(owner, balance) }")));
        PARTS.put("assets", new Part(", assetSlots = 2", "", "", "", "", List.of(
                "action lodge(policy: Hash, name: Bytes, q: Int) payable {\n    accept(Value.asset(policy, name, q))\n    return state\n  }")));
        PARTS.put("batched", new Part("", "", "", "", "", List.of("action tick() batched { return { ...state, count: count + 1 } }")));
        PARTS.put("moves", new Part("", "", "", "", "", List.of("owned Hero { owner: Key, level: Int }",
                "owned init Hero.recruit() { return { owner: caller, level: 1 } }",
                "owned action Hero.go(to: into) only owner { return state }",
                "owned destroy Hero.retire() only owner { pay(owner, balance) }",
                "action admit(id: Bytes, h: Hero) only admin admits Hero { return { ...state, count: count + 1 } }")));
        PARTS.put("invariants", new Part("", "", "", "", "", List.of("owned Tag { owner: Key, k: Int }",
                "owned init Tag.stamp() { return { owner: caller, k: 1 } }",
                "owned destroy Tag.peel() only owner { pay(owner, balance) }",
                "invariant Tag.positive = k > 0")));
        PARTS.put("receives", new Part("", "", "", "", "", List.of(
                "action credit() receives Key {\n    require(!funds.deposits.isEmpty, \"nothing\")\n    return { ...state, count: count + 1 }\n  }")));
        PARTS.put("reads", new Part("", "import contract { Other } from \"./Other.jsc\"\n", ", other: Instance<Other>", "", "", List.of(
                "action peek(got: reads params.other) { return { ...state, count: got.n } }")));
        PARTS.put("withdraw", new Part("", "", "", "", "", List.of(WITHDRAW)));
        PARTS.put("certify", new Part("", "", "", "", "", List.of(CERTIFY)));
        PARTS.put("vote", new Part("", "", "", "", "", List.of("vote choose(voter: Voter, action: GovernanceActionId, choice: Vote) " + Q + " = true")));
        PARTS.put("propose", new Part("", "", "", "", "", List.of("propose guard(p: ProposalProcedure) = true")));
        PARTS.put("stakeSelf", new Part(", stakeSelf", "", "", "", "", List.of(WITHDRAW, CERTIFY)));
        PARTS.put("migrate", new Part("", "", "", "", "", List.of(
                "action setSuccessor(next: Bytes) only admin {\n    require(successor == #, \"once\")\n    return { ...state, successor: next }\n  }",
                "destroy upgrade() only admin migrates to successor { }")));
        PARTS.put("adopt", new Part("", "import contract { Old } from \"./Old.jsc\"\n", ", previous: Hash", "", "", List.of(
                "init migrate from params.previous(old: Old.State) { return { admin: old.admin, successor: #, count: old.count%INIT } }")));
    }

    static final String OLD = "jsc 1\npackage t\ncontract Old {\n  state { admin: Key, successor: Bytes, count: Int }\n"
            + "  init() { return { admin: caller, successor: #, count: 0 } }\n"
            + "  action setSuccessor(next: Bytes) only admin {\n    require(successor == #, \"once\")\n    return { ...state, successor: next }\n  }\n"
            + "  destroy upgrade() only admin migrates to successor { }\n}\n";
    static final String OTHER = "jsc 1\npackage t\ncontract Other {\n  state { n: Int }\n  init() { return { n: 0 } }\n}\n";

    /** The refused cells and their codes; every other pair lowers (§2.12). */
    static final Map<String, String> REFUSED = new TreeMap<>();
    static {
        String[][] cells = {
                {"owned", "bigState", "JSC1211"}, {"owned", "withdraw", "JSC1305"}, {"owned", "stakeSelf", "JSC1305"},
                {"owned", "migrate", "JSC1210"}, {"owned", "adopt", "JSC1334"},
                {"keyed", "bigState", "JSC1211"}, {"keyed", "bigObj", "JSC1244"}, {"keyed", "assets", "JSC1244"},
                {"keyed", "batched", "JSC1244"}, {"keyed", "receives", "JSC1244"}, {"keyed", "withdraw", "JSC1244"},
                {"keyed", "stakeSelf", "JSC1244"}, {"keyed", "migrate", "JSC1210"}, {"keyed", "adopt", "JSC1334"},
                {"bigState", "bigObj", "JSC1211"}, {"bigState", "batched", "JSC0419"}, {"bigState", "moves", "JSC1211"},
                {"bigState", "invariants", "JSC1211"}, {"bigState", "withdraw", "JSC1305"}, {"bigState", "stakeSelf", "JSC1305"},
                {"bigState", "migrate", "JSC1334"}, {"bigState", "adopt", "JSC1334"},
                {"bigObj", "batched", "JSC1217"}, {"bigObj", "moves", "JSC1217"}, {"bigObj", "invariants", "JSC1217"},
                {"bigObj", "receives", "JSC1217"}, {"bigObj", "reads", "JSC1217"}, {"bigObj", "withdraw", "JSC1305"},
                {"bigObj", "certify", "JSC1217"}, {"bigObj", "vote", "JSC1217"}, {"bigObj", "propose", "JSC1217"},
                {"bigObj", "stakeSelf", "JSC1217"}, {"bigObj", "migrate", "JSC1210"}, {"bigObj", "adopt", "JSC1334"},
                {"assets", "batched", "JSC0424"}, {"assets", "migrate", "JSC1334"}, {"assets", "adopt", "JSC1334"},
                {"batched", "invariants", "JSC0436"}, {"batched", "receives", "JSC0424"},
                {"moves", "withdraw", "JSC1305"}, {"moves", "stakeSelf", "JSC1305"}, {"moves", "migrate", "JSC1210"}, {"moves", "adopt", "JSC1334"},
                {"invariants", "withdraw", "JSC1305"}, {"invariants", "stakeSelf", "JSC1305"}, {"invariants", "migrate", "JSC1210"},
                {"invariants", "adopt", "JSC1334"},
                {"receives", "migrate", "JSC1334"}, {"receives", "adopt", "JSC1334"},
        };
        for (String[] c : cells) REFUSED.put(c[0] + " x " + c[1], c[2]);
    }

    static String source(List<String> keys) {
        var opts = new StringBuilder();
        var imports = new LinkedHashSet<String>();
        var params = new StringBuilder();
        var state = new StringBuilder();
        var init = new StringBuilder();
        var members = new LinkedHashSet<String>();
        for (String k : keys) {
            Part f = PARTS.get(k);
            if (!opts.toString().contains(f.opts())) opts.append(f.opts());
            if (!f.imports().isEmpty()) imports.add(f.imports());
            params.append(f.params());
            state.append(f.state());
            init.append(f.init());
            members.addAll(f.members());
        }
        var sb = new StringBuilder("jsc 1\npackage t\n");
        imports.forEach(sb::append);
        sb.append("contract P(maxDatumBytes = 1024").append(opts).append(") {\n");
        sb.append("  params { council: List<Key>, quorum: Int, payee: Key").append(params).append(" }\n");
        sb.append("  state { admin: Key, successor: Bytes, count: Int").append(state).append(" }\n");
        sb.append("  init() { return { admin: caller, successor: #, count: 0").append(init).append(" } }\n");
        for (String m : members) sb.append("  ").append(m.replace("%INIT", init)).append("\n");
        return sb.append("}\n").toString();
    }

    /** "ok", or the code the contract made of {@code keys} is refused with. */
    static String outcome(List<String> keys) {
        String src = source(keys);
        var fs = new LinkedHashMap<String, String>();
        fs.put("Old.jsc", OLD);
        fs.put("Other.jsc", OTHER);
        fs.put("P.jsc", src);
        try {
            JscV2.lower(src, "P.jsc", "P.jsc", JscModules.of(fs));
            return "ok";
        } catch (JscException e) {
            return e.code;
        }
    }

    @Test
    void everyPart_alone_lowers() {
        var out = new ArrayList<String>();
        for (String k : PARTS.keySet()) {
            String o = outcome(List.of(k));
            if (!o.equals("ok")) out.add(k + ": " + o);
        }
        assertEquals(List.of(), out);
    }

    @Test
    void everyCell_isRefusedWithItsCode_orLowers() {
        var keys = new ArrayList<>(PARTS.keySet());
        var out = new ArrayList<String>();
        int refused = 0, accepted = 0;
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                String cell = keys.get(i) + " x " + keys.get(j);
                String want = REFUSED.getOrDefault(cell, "ok");
                String got = outcome(List.of(keys.get(i), keys.get(j)));
                if (!want.equals(got)) out.add(cell + ": expected " + want + ", got " + got);
                if (want.equals("ok")) accepted++;
                else refused++;
            }
        }
        System.out.println("[M3 matrix] " + (refused + accepted) + " cells: " + refused + " refused with their codes, " + accepted + " accepted");
        assertEquals(List.of(), out, out.size() + " cells differ from the matrix");
    }
}
