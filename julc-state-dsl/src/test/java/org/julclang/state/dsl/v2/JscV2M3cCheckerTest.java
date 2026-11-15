package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3c: the script purposes (ADR-0010: withdraw, certify, vote, propose; stakeSelf), their ledger types, and
 * migration (ADR-0011 D4: init migrate from params.f, destroy … migrates to f). The syntax, the lowering
 * ({@code @Withdraw}, {@code @Certify}, {@code @Votes}, {@code @Propose}, {@code @Migrate(from)}, {@code @MigrateTo},
 * {@code stakeSelf = true}), every M3c refusal (JSC1301–JSC1341) at its {@code .jsc} position (marked {@code @@}), the
 * warning JSC9014 (A43) and its lock, and the security review's syntax probes at the new positions (A19: the handler
 * words are contextual; a ledger type never stored, passed or compared; K39: a handler sees no state).
 */
class JscV2M3cCheckerTest {

    static final String Q = "quorum params.council params.quorum";

    /** A params-governed contract; {@code %O} its options, {@code %M} its members. */
    static final String PURPOSES = """
            jsc 1
            package t
            contract S(%O) {
              params { council: List<Key>, quorum: Int, pool: Bytes, payee: Key, cap: Int, gov: Credential }
              state { deposits: Int }
              init() payable { return { deposits: received } }
            %M
            }
            """;

    static String pur(String options, String members) {
        return PURPOSES.replace("%O", options).replace("%M", members);
    }

    static String pur(String members) {
        return pur("maxDatumBytes = 256", members);
    }

    /** The previous version: a successor field its admin sets once, and a migrating destroy. */
    static final String OLD = """
            jsc 1
            package t
            contract Old {
              state { admin: Key, successor: Bytes, count: Int, started: Int }
              init() { return { admin: caller, successor: #, count: 0, started: validFrom } }
              action setSuccessor(next: Bytes) only admin {
                require(successor == #, "the successor is set once")
                return { ...state, successor: next }
              }
              destroy upgrade() only admin migrates to successor { }
            }
            """;

    /** A previous version that keeps BigMap state. */
    static final String BIG = """
            jsc 1
            package t
            contract Big {
              state { admin: Key, successor: Bytes, m: BigMap<Key, Int> }
              init() { return { admin: caller, successor: #, m: BigMap.empty() } }
            }
            """;

    /** The next version: {@code %P} its params, {@code %M} its members. */
    static final String NEW = """
            jsc 1
            package t
            import contract { Old } from "./Old.jsc"
            import contract { Big } from "./Big.jsc"
            contract New {
              params { %P }
              state { admin: Key, count: Int }
              init() { return { admin: caller, count: 0 } }
            %M
            }
            """;

    static String next(String params, String members) {
        return NEW.replace("%P", params).replace("%M", members);
    }

    static String next(String members) {
        return next("previous: Hash, until: Int", members);
    }

    static final String ADOPT = "  init migrate from params.previous(old: Old.State) { return { admin: old.admin, count: old.count } }\n";

    static JscV2.Lowered build(String source, String name) {
        var fs = new LinkedHashMap<String, String>();
        fs.put("Old.jsc", OLD);
        fs.put("Big.jsc", BIG);
        fs.put("Plain.jsc", "jsc 1\npackage t\ncontract Plain {\n  state { admin: Key, count: Int }\n  init() { return { admin: caller, count: 0 } }\n"
                + "  destroy close() only admin { pay(admin, balance) }\n}\n");
        fs.put(name, source);
        return JscV2.lower(source, name, name, JscModules.of(fs));
    }

    static String fileOf(String source) {
        return source.contains("contract New") ? "New.jsc" : source.contains("contract Old") ? "Old.jsc" : "S.jsc";
    }

    static JscV2.Lowered lower(String source) {
        try {
            return build(source.replace("@@", ""), fileOf(source));
        } catch (JscException e) {
            throw new AssertionError("expected to compile, got " + e.code + " " + e.line + ":" + e.col + ": " + e.getMessage(), e);
        }
    }

    /** A refusal: its code, a fragment of its message, its position (@@). Collected, so one run reports every mismatch. */
    record Bad(String code, String fragment, String source) {}

    static String check(Bad b) {
        int at = b.source.indexOf("@@");
        if (at < 0) return b.code + ": mark the expected position with @@";
        String before = b.source.substring(0, at);
        int line = (int) before.chars().filter(c -> c == '\n').count() + 1;
        int col = at - before.lastIndexOf('\n');
        try {
            build(b.source.replace("@@", ""), fileOf(b.source));
            return b.code + " (" + b.fragment + "): compiled";
        } catch (JscException e) {
            var why = new ArrayList<String>();
            if (!b.code.equals(e.code)) why.add("code " + e.code + " (" + e.getMessage() + ")");
            if (!e.getMessage().contains(b.fragment)) why.add("message '" + e.getMessage() + "'");
            if (!(line + ":" + col).equals(e.line + ":" + e.col)) why.add("at " + e.line + ":" + e.col + " not " + line + ":" + col);
            return why.isEmpty() ? null : b.code + " (" + b.fragment + "): " + String.join("; ", why);
        }
    }

    static void allRefused(List<Bad> cases) {
        var out = new ArrayList<String>();
        for (Bad b : cases) {
            String r = check(b);
            if (r != null) out.add(r);
        }
        assertTrue(out.isEmpty(), out.size() + " of " + cases.size() + " cases differ:\n" + String.join("\n", out));
    }

    static Bad bad(String code, String fragment, String source) {
        return new Bad(code, fragment, source);
    }

    @Test
    void thePurposes_lowerToTheProcessorsHandlers() {
        String java = lower(pur("stakeSelf, maxDatumBytes = 256", """
                  withdraw collect(amount: Int, to: Key) %Q {
                    send(wallet(to), Value.lovelace(amount))
                  }
                  certify certificate(cert: TxCert) %Q = match cert { RegStaking { credential } => credential == params.gov, _ => false }
                  vote judge(voter: Voter, action: GovernanceActionId, choice: Vote) %Q = action.govActionIx >= 0
                  propose rail(p: ProposalProcedure) = p.deposit <= params.cap
                """.replace("%Q", Q))).java();
        for (String s : List.of("@StateContract(stakeSelf = true, maxDatumBytes = 256)", "@Withdraw", "@Certify", "@Votes", "@Propose",
                "public static JulcList<Send> collect(Config params__, BigInteger amount, byte[] to",
                "public static boolean certificate(Config params__, TxCert cert)",
                "public static boolean judge(Config params__, Voter voter, GovernanceActionId action, Vote choice)",
                "public static boolean rail(Config params__, ProposalProcedure p)")) {
            assertTrue(java.contains(s), s + " in\n" + java);
        }
        String m = lower(next(ADOPT)).java();
        assertTrue(m.contains("@Migrate(from = \"params.previous\")\n    public static State migrate(Config params__, Old.State old)"), m);
        assertTrue(m.contains("\"#old:mint"), m);
        String o = lower(OLD).java();
        assertTrue(o.contains("@MigrateTo(\"successor\")\n    public static JulcList<Payout> upgrade("), o);
    }

    @Test
    void theHandlerWords_areWordsOnlyWhereAMemberStarts() {
        // A19: an action, a field, a parameter, a local may be called withdraw, certify, vote, propose, migrate, migrates.
        lower(pur("""
                  action withdraw(certify: Int) payable {
                    const vote = certify + 1
                    const propose = vote
                    return { deposits: deposits + propose }
                  }
                  action migrates(migrate: Int) payable { return { deposits: migrate } }
                """));
        lower("jsc 1\npackage t\ncontract S {\n  state { withdraw: Int, vote: Bool }\n  init() { return { withdraw: 0, vote: false } }\n}\n");
    }

    @Test
    void everyM3cRefusal_isAtItsPosition() {
        var cases = new ArrayList<Bad>();
        // ---- stakeSelf (ADR-0010 D2)
        cases.add(bad("JSC1301", "stakeSelf is a flag", pur("@@stakeSelf = 1", "")));
        cases.add(bad("JSC1301", "written twice", pur("stakeSelf, @@stakeSelf", "")));
        cases.add(bad("JSC1302", "needs a withdraw handler", pur("@@stakeSelf", "  certify c(cert: TxCert) " + Q + " = true\n")));
        cases.add(bad("JSC1302", "needs a withdraw handler", pur("@@stakeSelf", "  withdraw w(amount: Int) " + Q + " { }\n")));
        // ---- the handlers' shapes (ADR-0010 D1)
        cases.add(bad("JSC1312", "needs quorum or authorized", pur("  @@withdraw w(amount: Int) { }\n")));
        cases.add(bad("JSC1312", "needs quorum or authorized", pur("  @@certify c(cert: TxCert) = true\n")));
        cases.add(bad("JSC1312", "needs quorum or authorized", pur("  @@vote v(voter: Voter, a: GovernanceActionId, x: Vote) = true\n")));
        cases.add(bad("JSC1312", "names params fields", pur("  certify c(cert: TxCert) @@quorum council quorum = true\n")));
        cases.add(bad("JSC1312", "names params fields", pur("  withdraw w(amount: Int) @@authorized gov { }\n")));
        cases.add(bad("JSC1313", "takes only quorum or authorized", pur("  certify c(cert: TxCert) @@only payee = true\n")));
        cases.add(bad("JSC1313", "takes only quorum or authorized", pur("  withdraw w(amount: Int) " + Q + " @@before cap { }\n")));
        cases.add(bad("JSC1313", "a propose handler is a guardrail", pur("  propose p(x: ProposalProcedure) @@" + Q + " = true\n")));
        cases.add(bad("JSC1314", "certify c(cert: TxCert)", pur("  @@certify c(cert: TxCert, n: Int) " + Q + " = true\n")));
        cases.add(bad("JSC1314", "its parameter 1 is a TxCert", pur("  certify c(cert: @@Voter) " + Q + " = true\n")));
        cases.add(bad("JSC1314", "its parameter 3 is a Vote", pur("  vote v(voter: Voter, a: GovernanceActionId, x: @@Int) " + Q + " = true\n")));
        cases.add(bad("JSC1314", "propose p(p: ProposalProcedure)", pur("  @@propose p() = true\n")));
        cases.add(bad("JSC1314", "withdraw w(amount: Int", pur("  @@withdraw w() " + Q + " { }\n")));
        cases.add(bad("JSC1314", "the amount withdrawn, an Int", pur("  withdraw w(amount: @@Bytes) " + Q + " { }\n")));
        cases.add(bad("JSC0216", "Rewards(marker, amount", pur("  withdraw w(amount: Int, @@marker: Int) " + Q + " { }\n")));
        cases.add(bad("JSC1321", "a ledger type of the script purposes", pur("  withdraw w(amount: Int, c: @@TxCert) " + Q + " { }\n")));
        cases.add(bad("JSC1315", "received is 0", pur("  withdraw w(amount: Int) " + Q + " { send(wallet(params.payee), Value.lovelace(@@received)) }\n")));
        cases.add(bad("JSC1315", "is not available in", pur("  certify c(cert: TxCert) " + Q + " = @@caller == params.payee\n")));
        cases.add(bad("JSC1315", "is not available in", pur("  propose p(x: ProposalProcedure) = @@validTo > 0\n")));
        cases.add(bad("JSC1316", "at most one certify handler", pur("  certify c(cert: TxCert) " + Q + " = true\n  @@certify d(cert: TxCert) " + Q + " = false\n")));
        cases.add(bad("JSC1316", "at most one withdraw handler",
                pur("  withdraw w(amount: Int) " + Q + " { }\n  @@withdraw x(amount: Int) " + Q + " { }\n")));
        cases.add(bad("JSC1317", "is governed by the params",
                "jsc 1\npackage t\ncontract S {\n  state { n: Int }\n  init() { return { n: 0 } }\n  @@propose p(x: ProposalProcedure) = true\n}\n"));
        cases.add(bad("JSC1318", "sees no instance", pur("  certify c(cert: TxCert) " + Q + " = @@deposits > 0\n")));
        cases.add(bad("JSC1318", "sees no instance", pur("  vote v(voter: Voter, a: GovernanceActionId, x: Vote) " + Q + " { const s = @@state\n true }\n")));
        cases.add(bad("JSC1319", "only effect is send", pur("  withdraw w(amount: Int) " + Q + " { @@pay(params.payee, amount) }\n")));
        cases.add(bad("JSC1319", "it has no effects", pur("  certify c(cert: TxCert) " + Q + " { @@send(wallet(params.payee), Value.lovelace(1))\n true }\n")));
        cases.add(bad("JSC1340", "its sends", pur("  withdraw w(amount: Int) " + Q + " { send(wallet(params.payee), Value.lovelace(amount))\n @@true }\n")));
        // Review S4: a withdraw handler's send is Value.lovelace(n), as written (an asset, a sum, a const value refused).
        cases.add(bad("JSC1340", "sends lovelace only", pur("  withdraw w(amount: Int, p: Hash) " + Q
                + " { send(wallet(params.payee), @@Value.lovelace(amount) + Value.asset(p, #01, 1)) }\n")));
        cases.add(bad("JSC1340", "sends lovelace only", pur("  withdraw w(amount: Int) " + Q
                + " { const v = Value.lovelace(amount)\n send(wallet(params.payee), @@v) }\n")));
        // A withdraw handler is a block (its sends); the parser says so where the = is.
        cases.add(bad("JSC0109", "expected '{'", pur("  withdraw w(amount: Int) " + Q + " @@= true\n")));
        cases.add(bad("JSC1341", "evaluates to a Bool", pur("  certify c(cert: TxCert) " + Q + " @@{ const x = 1 }\n")));
        // ---- the ledger types (D5): read-only, never stored, passed, compared or built
        cases.add(bad("JSC1320", "is PlutusData in the ledger", pur(
                "  propose p(x: ProposalProcedure) = match x.governanceAction { ParameterChange { @@parameters } => true, _ => true }\n")));
        cases.add(bad("JSC1321", "a ledger type of the script purposes", "jsc 1\npackage t\ncontract S {\n  state { c: @@TxCert }\n  init() { return { c: 0 } }\n}\n"));
        cases.add(bad("JSC1321", "a ledger type of the script purposes", pur("  action a(v: @@Vote) payable { return state }\n")));
        cases.add(bad("JSC1321", "a ledger type of the script purposes", pur("  view f(): @@Voter = 1\n")));
        cases.add(bad("JSC1321", "LedgerMap<Credential, Int>", pur("  fn f(m: @@LedgerMap<Credential>): Int = 0\n")));
        cases.add(bad("JSC1321", "a ledger type of the script purposes",
                "jsc 1\npackage t\ntype R = { v: @@Vote }\ncontract S {\n  state { n: Int }\n  init() { return { n: 0 } }\n}\n"));
        cases.add(bad("JSC1322", "is not comparable", pur("  vote v(voter: Voter, a: GovernanceActionId, x: Vote) " + Q + " = @@x == x\n")));
        cases.add(bad("JSC1323", "is a sealed ledger type", pur("  certify c(cert: TxCert) " + Q + " = @@cert.credential == cert.credential\n")));
        cases.add(bad("JSC1323", "has no methods", pur("  vote v(voter: Voter, a: GovernanceActionId, x: Vote) " + Q + " = @@a.size() > 0\n")));
        cases.add(bad("JSC1324", "is read-only", pur("  fn f(m: LedgerMap<Credential, Int>, k: Credential): Int = @@m.delete(k).size\n")));
        cases.add(bad("JSC1324", "has no property", pur("  fn f(m: LedgerMap<Credential, Int>): Int = @@m.length\n")));
        cases.add(bad("JSC1325", "its values come from", pur("  certify c(cert: TxCert) " + Q + " = @@VoteYes == VoteYes\n")));
        // Review C3: funds in a handler (no Funds in the purposes' signatures) and in init migrate.
        cases.add(bad("JSC1315", "funds is not available in", pur("  certify c(cert: TxCert) " + Q + " = @@funds.policy == #\n")));
        cases.add(bad("JSC1315", "funds is not available in", pur("  withdraw w(amount: Int) " + Q + " { const p = @@funds.policy\n }\n")));
        cases.add(bad("JSC1315", "funds is not available in", pur("  vote v(voter: Voter, a: GovernanceActionId, x: Vote) " + Q + " = @@funds.self == #\n")));
        cases.add(bad("JSC1315", "funds is not available in", pur("  propose p(x: ProposalProcedure) = @@funds.policy == #\n")));
        // Review C5: indexOf over ledger values, refused at the call (direct, and nested in an Option).
        cases.add(bad("JSC1322", "is not comparable",
                pur("  certify c(cert: TxCert) " + Q + " = @@indexOf([cert], cert) != None\n").replace("package t\n", "package t\nimport { indexOf } from \"std/list\"\n")));
        cases.add(bad("JSC1322", "is not comparable",
                pur("  vote v(voter: Voter, a: GovernanceActionId, x: Vote) " + Q + " = @@indexOf([Some(x)], Some(x)) != None\n")
                        .replace("package t\n", "package t\nimport { indexOf } from \"std/list\"\n")));
        // Review F1, S3: stakeSelf, or a withdraw handler, beside owned objects (not covered yet).
        String owned = "  owned Box { owner: Key, v: Int }\n  owned init Box.make() { return { owner: caller, v: 0 } }\n"
                + "  owned destroy Box.close() only owner { pay(owner, balance) }\n";
        cases.add(bad("JSC1305", "stakeSelf is not supported beside owned objects", pur("@@stakeSelf",
                owned + "  withdraw w(amount: Int) " + Q + " { }\n  certify c(cert: TxCert) " + Q + " = true\n")));
        cases.add(bad("JSC1305", "a withdraw handler is not supported beside owned objects", pur(owned + "  @@withdraw w(amount: Int) " + Q + " { }\n")));
        // M3 final pass MD1: a method named after a ledger type the handlers read (its redeemer record would shadow it).
        cases.add(bad("JSC0203", "the generated name Vote", pur("  @@action vote(k: Int) payable { return { deposits: deposits + k } }\n"
                + "  vote v(voter: Voter, a: GovernanceActionId, x: Vote) " + Q + " = true\n")));
        cases.add(bad("JSC0203", "the generated name TxCert", pur("  @@action txCert() payable { return state }\n"
                + "  certify c(cert: TxCert) " + Q + " = true\n")));
        // ---- migration (ADR-0011 D4)
        cases.add(bad("JSC1303", "migrates to successor",
                OLD.replace("only admin migrates to successor", "only admin migrates @@{").replace("{ }\n}", "\n}")));
        cases.add(bad("JSC1304", "by a params field", next("  init migrate from @@previous(old: Old.State) { return { admin: old.admin, count: 0 } }\n")));
        cases.add(bad("JSC1330", "goes on an instance destroy", OLD.replace("action setSuccessor(next: Bytes) only admin {",
                "action setSuccessor(next: Bytes) only admin @@migrates to successor {")));
        cases.add(bad("JSC1330", "no state field nope", OLD.replace("migrates to successor", "@@migrates to nope")));
        cases.add(bad("JSC1330", "count is Int", OLD.replace("migrates to successor", "@@migrates to count")));
        cases.add(bad("JSC1331", "names a state field, not params.next", OLD.replace("migrates to successor", "@@migrates to params.next")));
        cases.add(bad("JSC1332", "moves the instance's whole value", OLD.replace("migrates to successor { }",
                "migrates to successor { @@pay(admin, 1) }")));
        cases.add(bad("JSC1333", "say who may", OLD.replace("destroy upgrade() only admin migrates", "@@destroy upgrade() open migrates")));
        cases.add(bad("JSC1334", "keeps BigMap state", next("  init migrate from params.previous(old: @@Big.State) { return { admin: old.admin, count: 0 } }\n")));
        // The adopting side with owned objects (an instance destroy with them is JSC1210's already).
        cases.add(bad("JSC1334", "owned objects", next("  owned Item { owner: Key }\n  owned init Item.make() { return { owner: caller } }\n"
                + "  owned destroy Item.drop() only owner { pay(owner, balance) }\n"
                + "  @@init migrate from params.previous(old: Old.State) { return { admin: old.admin, count: old.count } }\n")));
        cases.add(bad("JSC1334", "native assets", "jsc 1\npackage t\ncontract O {\n  state { admin: Key, successor: Bytes }\n"
                + "  init() { return { admin: caller, successor: # } }\n"
                + "  action give(to: Key) only admin { send(wallet(to), Value.lovelace(1))\n return state }\n"
                + "  destroy up() only admin @@migrates to successor { }\n}\n"));
        cases.add(bad("JSC1335", "the params have no field prior", next("  init migrate from @@params.prior(old: Old.State) { return { admin: old.admin, count: 0 } }\n")));
        cases.add(bad("JSC1335", "params.until is Int", next("  init migrate from @@params.until(old: Old.State) { return { admin: old.admin, count: 0 } }\n")));
        cases.add(bad("JSC1335", "the contract declares no params", NEW.replace("  params { %P }\n", "").replace("%M",
                "  init migrate from @@params.previous(old: Old.State) { return { admin: old.admin, count: 0 } }\n")));
        // Review S2, F4: the previous version must declare a migrating destroy (Plain has an ordinary destroy only).
        cases.add(bad("JSC1336", "Plain declares no migrating destroy", next("  init migrate from params.previous(old: @@Plain.State) { return { admin: old.admin, count: 0 } }\n")
                .replace("import contract { Big }", "import contract { Plain } from \"./Plain.jsc\"\nimport contract { Big }")));
        cases.add(bad("JSC1336", "the previous version's state", next("  @@init migrate from params.previous() { return { admin: caller, count: 0 } }\n")));
        cases.add(bad("JSC1336", "the previous version's state", next("  init migrate from params.previous(@@n: Int) { return { admin: caller, count: n } }\n")));
        // Review F6: a second init migrate has its own message (it is also a second Java method migrate).
        cases.add(bad("JSC1337", "at most one init migrate", next(ADOPT + "  @@init migrate from params.previous(old: Old.State) { return { admin: old.admin, count: 1 } }\n")));
        cases.add(bad("JSC1337", "rename this method", next(ADOPT + "  @@action migrate() only admin { return state }\n")));
        cases.add(bad("JSC1338", "pays,", next("  init migrate from params.previous(old: Old.State) { @@pay(old.admin, 1)\n return { admin: old.admin, count: 0 } }\n")));
        cases.add(bad("JSC1338", "funds is not available in init migrate",
                next("  init migrate from params.previous(old: Old.State) { return { admin: old.admin, count: @@funds.held.lovelace } }\n")));
        cases.add(bad("JSC1338", "nothing is received", next("  init migrate from params.previous(old: Old.State) { return { admin: old.admin, count: @@received } }\n")));
        cases.add(bad("JSC1339", "takes no modifiers", next("  init migrate from params.previous(old: Old.State) @@payable { return { admin: old.admin, count: 0 } }\n")));
        cases.add(bad("JSC1339", "takes no modifiers", next("  init migrate from params.previous(old: Old.State) @@open { return { admin: old.admin, count: 0 } }\n")));
        allRefused(cases);
        System.out.println("[M3c checker] " + cases.size() + " refusals at their positions");
    }

    @Test
    void aSuccessorTheAdminCanChange_isWarned_andItsLockSilencesIt() {
        // A43: setSuccessor writes the successor field a migrating destroy names: warned unless it requires the field unset.
        String loose = OLD.replace("    require(successor == #, \"the successor is set once\")\n", "");
        var w = lower(loose).diagnostics().stream().filter(d -> d.code().equals("JSC9014")).map(d -> d.code() + "@" + d.line()).toList();
        assertEquals(List.of("JSC9014@6"), w, w.toString());
        assertEquals(List.of(), lower(OLD).diagnostics().stream().map(d -> d.code()).filter("JSC9014"::equals).toList());
    }

    @Test
    void theManifest_namesThePurposes_andTheMigration() {
        String abi = lower(pur("stakeSelf, maxDatumBytes = 256", "  withdraw w(amount: Int) " + Q + " { }\n  certify c(cert: TxCert) " + Q + " = true\n"))
                .abiJson();
        for (String s : List.of("\"stakeSelf\": true", "\"purposes\": [", "\"Rewards\"")) assertTrue(abi.contains(s), s + " in " + abi);
        String n = lower(next(ADOPT)).abiJson();
        assertTrue(n.contains("\"migrate\": {") && n.contains("\"from\": \"previous\""), n);
        String o = lower(OLD).abiJson();
        assertTrue(o.contains("\"migratesTo\": {") && o.contains("\"upgrade\": \"successor\""), o);
    }
}
