package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3b: keyed objects (ADR-0016). The syntax ({@code owned T keyed by f (…)}, {@code retire}), the processor's keyed
 * rules at {@code .jsc} positions (JSC1240–JSC1251) and warnings (JSC9011–JSC9013), the lowering ({@code @Owned(key = …)},
 * {@code @OwnedDestroy(retire = true)}), D11's rule for keys, and the security review's syntax probes at the new positions
 * (A19, A28, K33, K37). Each refusal is asserted at the position marked {@code @@} in its source.
 */
class JscV2M3bCheckerTest {

    /** A keyed contract: one keyed type with an initializer and a retiring exit; {@code %K} is the keyed clause. */
    static final String HEAD = """
            jsc 2
            package t
            contract R(maxDatumBytes = 384) {
              state { admin: Key, isOpen: Bool }
              owned Name %K { name: Bytes, owner: Key, guardians: List<Key>, needed: Int, guard: Credential }
              init() { return { admin: caller, isOpen: true } }
              owned init Name.claim(name: Bytes, guard: Credential) { return { name, owner: caller, guardians: [caller], needed: 1, guard } }
            """;

    static final String RETIRE = """
              owned destroy Name.leave() only owner retire { pay(owner, balance) }
            """;

    static String src(String keyed, String members) {
        return HEAD.replace("%K", keyed) + members + "\n}\n";
    }

    static String src(String members) {
        return src("keyed by name", members);
    }

    static JscV2.Lowered lower(String source) {
        try {
            return JscV2.lower(source.replace("@@", ""), "R.jsc");
        } catch (JscException e) {
            throw new AssertionError("expected to compile, got " + e.line + ":" + e.col + ": " + e.getMessage(), e);
        }
    }

    static void bad(String source, String code, String fragment) {
        int at = source.indexOf("@@");
        assertTrue(at >= 0, "mark the expected position with @@");
        String before = source.substring(0, at);
        int line = (int) before.chars().filter(c -> c == '\n').count() + 1;
        int col = at - before.lastIndexOf('\n');
        var e = assertThrows(JscException.class, () -> JscV2.lower(source.replace("@@", ""), "R.jsc"), fragment);
        assertEquals(code, e.code, e.getMessage());
        assertTrue(e.getMessage().contains(fragment), "message '" + e.getMessage() + "' should mention '" + fragment + "'");
        assertEquals(line + ":" + col, e.line + ":" + e.col, "position of: " + e.getMessage());
    }

    static List<String> warnings(String source) {
        return lower(source).diagnostics().stream().map(w -> w.code() + "@" + w.line()).toList();
    }

    @Test
    void aKeyedType_lowersToTheProcessorsKeyedType() {
        var l = lower(src("keyed by name (signed, maxKeyBytes = 28, linkCooldownMs = 120_000, linkWindowMs = 60000)", RETIRE
                + "  owned action Name.give(to: Key) only owner { return { ...state, owner: to } }\n"));
        String java = l.java();
        assertTrue(java.contains("@Owned(key = \"name\", maxKeyBytes = 28, signedKey = true, linkCooldownMs = 120000L, linkWindowMs = 60000L)\n"
                + "    public record Name("), java);
        assertTrue(java.contains("@OwnedDestroy(retire = true)\n    @SignedBy(\"owner\")\n    public static JulcList<Payout> leave(Name s__, Ctx ctx)"), java);
        // The defaults are the processor's: only the key and maxKeyBytes are written.
        assertTrue(lower(src(RETIRE)).java().contains("@Owned(key = \"name\", maxKeyBytes = 32)\n"), lower(src(RETIRE)).java());
        // The ABI manifest: the list's datums, its redeemers, the keyed section.
        String abi = l.abiJson();
        for (String s : List.of("\"Head_Name\"", "\"Keyed_Name\"", "\"Retired_Name\"", "\"Insert_claim\"", "\"Link\"", "\"keyed\": [",
                "\"linkCooldownMs\": 120000", "\"linkWindowMs\": 60000", "\"typeId\": \"044e616d65\"")) {
            assertTrue(abi.contains(s), s + " in " + abi);
        }
        assertFalse(abi.contains("\"Remove_Name\""), "no removing exit, no Remove_Name");
        assertTrue(lower(src(RETIRE + "  owned destroy Name.drop() only owner { pay(owner, balance) }\n")).abiJson().contains("\"Remove_Name\""));
        // The IR states the keyed type and the retire modifier.
        String ir = l.irJson();
        assertTrue(ir.contains("\"keyed\": [") && ir.contains("\"name\": \"retire\""), ir);
    }

    @Test
    void theKeyedClause_atItsPositions() {
        // JSC1240: the clause's syntax.
        bad(HEAD.replace("%K", "keyed @@name") + RETIRE + "}\n", "JSC1240", "names its key field");
        bad(HEAD.replace("%K", "keyed by name (@@sized = 2)") + RETIRE + "}\n", "JSC1240", "unknown keyed option 'sized'");
        bad(HEAD.replace("%K", "keyed by name (maxKeyBytes = 8, @@maxKeyBytes = 9)") + RETIRE + "}\n", "JSC1240", "written twice");
        bad(HEAD.replace("%K", "keyed by name (maxKeyBytes@@)") + RETIRE + "}\n", "JSC1240", "takes a value");
        bad(HEAD.replace("%K", "keyed by name (@@)") + RETIRE + "}\n", "JSC1240", "empty keyed options");
        // JSC1241: the key names a Bytes (Key, Hash) field of the type.
        bad(src("keyed by @@nom", RETIRE), "JSC1241", "has no field nom");
        bad(src("keyed by @@needed", RETIRE), "JSC1241", "the key field needed is Int");
        bad(src("keyed by @@guardians", RETIRE), "JSC1241", "the key field guardians is List<Key>");
        lower(src("keyed by owner", RETIRE));   // a Key is Bytes
        // JSC1242: maxKeyBytes 1 to 64 (K37: 1 and 64 accepted), at least 28 when signed; a signed key is no Hash.
        bad(src("keyed by name (@@maxKeyBytes = 0)", RETIRE), "JSC1242", "1 to 64");
        bad(src("keyed by name (@@maxKeyBytes = 65)", RETIRE), "JSC1242", "1 to 64");
        lower(src("keyed by name (maxKeyBytes = 1)", RETIRE));
        lower(src("keyed by name (maxKeyBytes = 64)", RETIRE));
        bad(src("keyed by name (@@signed, maxKeyBytes = 27)", RETIRE), "JSC1242", "at least 28");
        lower(src("keyed by name (signed, maxKeyBytes = 28)", RETIRE));
        // JSC1243: C is 0 or 60,000 to 3,600,000; W 20,000 to min(900,000, maxValidityMs).
        bad(src("keyed by name (@@linkCooldownMs = 59999)", RETIRE), "JSC1243", "linkCooldownMs is 0, or 60000 to 3600000");
        bad(src("keyed by name (@@linkCooldownMs = 3600001)", RETIRE), "JSC1243", "linkCooldownMs");
        lower(src("keyed by name (linkCooldownMs = 3600000)", RETIRE));
        bad(src("keyed by name (@@linkWindowMs = 19999)", RETIRE), "JSC1243", "linkWindowMs is 20000 to min(900000, maxValidityMs) = 900000");
        bad(src("keyed by name (@@linkWindowMs = 900001)", RETIRE), "JSC1243", "= 900000");
        bad(src("keyed by name", RETIRE).replace("maxDatumBytes = 384)", "maxDatumBytes = 384, maxValidityMs = 10000)")
                .replace("owned Name keyed", "owned @@Name keyed"), "JSC1243", "the default link window");
        bad(src("keyed by name (@@linkWindowMs = 20000)", RETIRE).replace("maxDatumBytes = 384)", "maxDatumBytes = 384, maxValidityMs = 10000)"),
                "JSC1243", "= 10000 milliseconds");
        // C = 0 is allowed, with the processor's warning (JSC9012); written explicitly only (K37).
        assertTrue(warnings(src("keyed by name (linkCooldownMs = 0)", RETIRE)).contains("JSC9012@5"));
        assertFalse(warnings(src(RETIRE)).stream().anyMatch(w -> w.startsWith("JSC9012")));
    }

    @Test
    void theEscapeHatch_andTheKeyedMethods_atTheirPositions() {
        // JSC1245: a keyed type's escape hatch is its retiring exit; it is signed and needs nobody else.
        bad(src("keyed by name", "").replace("owned Name keyed", "owned @@Name keyed"), "JSC1245", "with 'retire'");
        bad(src("  owned destroy Name.drop() only owner { pay(owner, balance) }\n").replace("owned Name keyed", "owned @@Name keyed"), "JSC1245",
                "a removal needs its predecessor");
        bad(src("  @@owned destroy Name.leave() open retire { pay(owner, balance) }\n"), "JSC1245", "needs 'only <field>'");
        bad(src("  owned destroy Name.leave() only owner @@payable retire { pay(owner, balance + received) }\n"), "JSC1245", "takes no 'payable'");
        bad(src("  owned destroy Name.leave() only owner retire @@quorum guardians needed { pay(owner, balance) }\n"), "JSC1245", "takes no 'quorum'");
        bad(src("  owned destroy Name.leave() only owner retire @@authorized guard { pay(owner, balance) }\n"), "JSC1245", "takes no 'authorized'");
        bad(src("  owned destroy Name.leave(@@s: shared) only owner retire { pay(owner, balance) }\n"), "JSC1245", "reads no shared state");
        // retire only on a keyed type's owned destroy.
        bad(src(RETIRE + "  action close() only admin @@retire { return state }\n"), "JSC1245", "retire goes on an owned destroy of a keyed type");
        bad("""
                jsc 2
                package t
                contract P {
                  state { a: Int }
                  owned Hero { owner: Key }
                  init() { return { a: 0 } }
                  owned init Hero.make() { return { owner: caller } }
                  owned destroy Hero.end() only owner @@retire { pay(owner, balance) }
                }
                """, "JSC1245", "Hero is not keyed");
        // JSC1246: a keyed action names its audience; open is not one.
        bad(src(RETIRE + "  @@owned action Name.touch() open { return state }\n"), "JSC1246", "must name who may call it");
        lower(src(RETIRE + "  owned action Name.give(to: Key) quorum guardians needed { return { ...state, owner: to } }\n"));
        lower(src(RETIRE + "  owned action Name.give(to: Key) authorized guard { return { ...state, owner: to } }\n"));
        // JSC9013: authorized by someone other than the retire's signer (a quorum, a credential, another field).
        assertTrue(warnings(src(RETIRE + "  owned action Name.give(to: Key) quorum guardians needed { return { ...state, owner: to } }\n"))
                .contains("JSC9013@9"));
        assertFalse(warnings(src(RETIRE + "  owned action Name.give(to: Key) only owner { return { ...state, owner: to } }\n"))
                .stream().anyMatch(w -> w.startsWith("JSC9013")));
        // JSC1247: keyed objects do not move (into, admits).
        bad(src(RETIRE + "  owned action Name.go(@@d: into) only owner { return state }\n"
                + "  action take(id: Bytes) admits Name { return state }\n"), "JSC1247", "do not move");
        bad(src(RETIRE + "  action take(id: Bytes) @@admits Name { return state }\n"), "JSC1247", "Name is a keyed type");
    }

    @Test
    void theKey_isFixed_andNeverATimeValue() {
        // JSC1248 (A28, I50): a keyed action keeps its key, however the result is written.
        lower(src(RETIRE + "  owned action Name.give(to: Key) only owner { return { ...state, owner: to } }\n"));
        lower(src(RETIRE + "  owned action Name.same() only owner { return state }\n"));
        lower(src(RETIRE + "  owned action Name.re(to: Key) only owner { return { ...state, name: name, owner: to } }\n"));
        lower(src(RETIRE + "  owned action Name.re(to: Key) only owner { return { ...state, name: state.name, owner: to } }\n"));
        lower(src(RETIRE + "  owned action Name.pick(to: Key, b: Bool) only owner { return b ? { ...state, owner: to } : state }\n"));
        bad(src(RETIRE + "  owned action Name.rename(to: Bytes) only owner { return { ...state, @@name: to } }\n"), "JSC1248", "is fixed for its life");
        bad(src(RETIRE + "  owned action Name.rename(to: Bytes, b: Bool) only owner { return b ? state : { ...state, @@name: to } }\n"), "JSC1248", "fixed");
        bad(src(RETIRE + "  owned action Name.rename(to: Bytes) only owner { const n = to; return { ...state, @@name: n } }\n"), "JSC1248", "fixed");
        // a const that shadows the field's name is not the input's key
        bad(src(RETIRE + "  owned action Name.rename(to: Bytes) only owner { const s: Name = { ...state, name: to }; return @@s }\n"), "JSC1248", "fixed");
        bad(src(RETIRE + "  owned action Name.rename() only owner { return Name { @@name: utf8(\"x\"), owner, guardians, needed, guard } }\n"),
                "JSC1248", "fixed");
        bad(src(RETIRE + "  owned action Name.rename(to: Option<Bytes>) only owner { return match to { None => state, "
                + "Some(n) => ({ ...state, @@name: n }) } }\n"), "JSC1248", "fixed");
        bad(src(RETIRE + "  owned action Name.rename(to: Bytes, b: Bool) only owner { return if b { state } else { { ...state, @@name: to } } }\n"),
                "JSC1248", "fixed");
        // JSC1251 (D11): a key that is a time field would let the submitter's window choose its place. No std row turns a
        // time value into Bytes, so a key becomes one only as part of a value the checker follows whole (JSC9002): a fn's
        // result is a time value when an argument is, so a result made by a fn from a time value makes every field of the
        // type a time field, the key with it.
        bad("""
                jsc 2
                package t
                contract S {
                  state { admin: Key }
                  owned Stamp keyed by @@tag { tag: Bytes, owner: Key, since: Int }
                  init() { return { admin: caller } }
                  owned init Stamp.make(tag: Bytes) {
                    return stamp(tag, caller, validFrom)
                  }
                  owned destroy Stamp.drop() only owner retire { pay(owner, balance) }
                  fn stamp(t: Bytes, o: Key, at0: Int): Stamp = Stamp { tag: t, owner: o, since: at0 }
                }
                """, "JSC1251", "set from a time value");
        // Field by field, only the time field is one: the key stays ordinary.
        lower("""
                jsc 2
                package t
                contract S {
                  state { admin: Key }
                  owned Stamp keyed by tag { tag: Bytes, owner: Key, since: Int }
                  init() { return { admin: caller } }
                  owned init Stamp.make(tag: Bytes) { return { tag, owner: caller, since: validFrom } }
                  owned destroy Stamp.drop() only owner retire { pay(owner, balance) }
                }
                """);
    }

    @Test
    void theContractsLimits_atTheirPositions() {
        // JSC1244: no native assets, no batched actions, no BigMap in any owned type, at most 16 keyed types, maxDatumBytes <= 2048.
        bad(src(RETIRE + "  owned action Name.give(to: Key) only owner { send(wallet(to), funds.held); return state }\n")
                .replace("owned Name keyed", "owned @@Name keyed"), "JSC1244", "native assets");
        bad(src(RETIRE + "  @@action tick() batched { return state }\n"), "JSC1244", "batched");
        bad(src(RETIRE + "  owned Vault { owner: Key, items: BigMap<Bytes, Int> }\n  owned init Vault.open() { return { owner: caller, items: BigMap.empty() } }\n"
                + "  owned destroy Vault.close() only owner { pay(owner, balance) }\n").replace("owned Vault {", "owned @@Vault {"), "JSC1244", "BigMaps");
        var many = new StringBuilder();
        for (int i = 0; i < 16; i++) {   // with Name, 17 keyed types: the 17th (Kp) is refused
            many.append("  owned ").append(i == 15 ? "@@" : "").append("K").append((char) ('a' + i)).append(" keyed by k { k: Bytes, owner: Key }\n");
            many.append("  owned init K").append((char) ('a' + i)).append(".m").append(i).append("(k: Bytes) { return { k, owner: caller } }\n");
            many.append("  owned destroy K").append((char) ('a' + i)).append(".x").append(i).append("() only owner retire { pay(owner, balance) }\n");
        }
        bad(src(RETIRE + many), "JSC1244", "at most 16 keyed types");
        lower(src(RETIRE + many.toString().replaceAll("(?m)^.*Kp.*\\n", "")));   // 16 keyed types (Name and Ka..Ko): accepted
        bad(src(RETIRE).replace("(@@maxDatumBytes = 384)", "").replace("contract R(maxDatumBytes = 384)", "contract R(@@maxDatumBytes = 2049)"),
                "JSC1244", "at most 2048");
        lower(src(RETIRE).replace("contract R(maxDatumBytes = 384)", "contract R(maxDatumBytes = 2048)"));
        // JSC1250: a retired node's worst case fits maxDatumBytes (3 + 2 + 34 + 2·(maxKeyBytes + 2) + 9 bytes: 180 at 64).
        bad(src("keyed by name (maxKeyBytes = 64)", RETIRE).replace("contract R(maxDatumBytes = 384)", "contract R(maxDatumBytes = 179)")
                .replace("owned Name keyed", "owned @@Name keyed"), "JSC1250", "may need 180 datum bytes");
        lower(src("keyed by name (maxKeyBytes = 64)", RETIRE).replace("contract R(maxDatumBytes = 384)", "contract R(maxDatumBytes = 180)"));
    }

    @Test
    void theGeneratedNames_atTheirPositions() {
        // JSC1249: the validator's keyed names (Link, Insert_m, Remove_T, …), the client's members, the handle's, the redeemers' fields.
        bad(src(RETIRE + "  @@action link() only admin { return state }\n"), "JSC1249", "would be the redeemer Link");
        bad(src(RETIRE + "  @@owned action Name.remove_Name() only owner { return state }\n"), "JSC1249", "Remove_Name");
        bad(src(RETIRE + "  @@owned action Name.insert_claim() only owner { return state }\n"), "JSC1249", "Insert_claim");
        bad(src(RETIRE + "  @@view nameKeys(): Int = 1\n"), "JSC1249", "the view nameKeys");
        bad(src(RETIRE + "  @@action nameExists() only admin { return state }\n"), "JSC1249", "keyed type Name");
        bad(src(RETIRE + "  @@owned action Name.read() only owner { return state }\n"), "JSC1249", "NameObject handle");
        bad(src(RETIRE + "  owned action Name.give(@@anchor: Key) only owner { return { ...state, owner: anchor } }\n"), "JSC1249", "'anchor'");
        bad(src(RETIRE + "  owned init Name.make(@@removed: Bytes, guard: Credential) { return { name: removed, owner: caller, guardians: [caller], needed: 1, guard } }\n"),
                "JSC1249", "'removed'");
        // the lookup itself is JSC1204 (M3a), as for every owned type
        bad(src(RETIRE + "  @@action name() only admin { return state }\n"), "JSC1204", "lookup");
        // retire is a modifier word: no field or binding takes it (JSC0211); method names may (Guild's Hero.retire).
        bad(src(RETIRE + "  action set(@@retire: Int) only admin { return state }\n"), "JSC0211", "modifier");
        lower(src(RETIRE + "  owned destroy Name.retire() only owner retire { pay(owner, balance) }\n"));
    }

    @Test
    void squatting_isTheProcessorsWarning() {
        // JSC9011 (K33): an initializer that neither signs the key nor takes it from the caller in every result.
        assertTrue(warnings(src(RETIRE)).contains("JSC9011@7"), warnings(src(RETIRE)).toString());
        assertFalse(warnings(src("keyed by name (signed, maxKeyBytes = 28)", RETIRE)).stream().anyMatch(w -> w.startsWith("JSC9011")));
        String fromCaller = src("keyed by owner", RETIRE);
        assertFalse(warnings(fromCaller).stream().anyMatch(w -> w.startsWith("JSC9011")), warnings(fromCaller).toString());
        // a caller-valued field that is not the key, or a discarded comparison, is no binding
        String compared = src("keyed by name", RETIRE + "  owned init Name.mine(name: Bytes, guard: Credential) { require(name == caller || true, \"x\"); "
                + "return { name, owner: caller, guardians: [caller], needed: 1, guard } }\n");
        assertTrue(warnings(compared).contains("JSC9011@9"), warnings(compared).toString());
    }
}
