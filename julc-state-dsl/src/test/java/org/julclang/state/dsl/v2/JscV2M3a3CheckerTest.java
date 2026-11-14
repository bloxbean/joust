package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3a3: moves between instances (ADR-0012 D3: {@code name: into}, {@code admits T}) and invariants by type
 * (ADR-0012 D1). The processor's rules at {@code .jsc} positions (JSC1212–JSC1214), the lowering's shape, and the
 * security review's syntax probes (A19, A21) at the new positions. Each refusal is asserted at the position marked
 * {@code @@} in its source.
 */
class JscV2M3a3CheckerTest {

    static final String HEAD = """
            jsc 2
            package t
            contract G {
              state { master: Key, isOpen: Bool, arrivals: Int }
              owned Hero { owner: Key, level: Int }
              owned Item { owner: Key, power: Int }
              init() { return { master: caller, isOpen: true, arrivals: 0 } }
              owned init Hero.recruit() { return { owner: caller, level: 1 } }
              owned destroy Hero.retire() only owner { pay(owner, balance) }
              owned init Item.forge() { return { owner: caller, power: 1 } }
              owned destroy Item.scrap() only owner { pay(owner, balance) }
            """;

    static final String MOVE = """
              owned action Hero.transfer(source: shared, destination: into) only owner { require(source.isOpen, "closed"); return state }
              action admit(heroId: Bytes, hero: Hero) admits Hero { require(hero.level > 1, "low"); return { ...state, arrivals: arrivals + 1 } }
            """;

    static String src(String members) {
        return HEAD + members + "\n}\n";
    }

    static JscV2.Lowered lower(String source) {
        try {
            return JscV2.lower(source.replace("@@", ""), "G.jsc");
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
        var e = assertThrows(JscException.class, () -> JscV2.lower(source.replace("@@", ""), "G.jsc"), fragment);
        assertEquals(code, e.code, e.getMessage());
        assertTrue(e.getMessage().contains(fragment), "message '" + e.getMessage() + "' should mention '" + fragment + "'");
        assertEquals(line + ":" + col, e.line + ":" + e.col, "position of: " + e.getMessage());
    }

    @Test
    void aMove_andItsAdmit_lowerToTheProcessorsMove() {
        String java = lower(src(MOVE)).java();
        assertTrue(java.contains("@OwnedAction\n    @SignedBy(\"owner\")\n    public static Hero transfer(Hero s__, @Shared State source, @Into State destination, Ctx ctx)"), java);
        assertTrue(java.contains("@Action\n    @Admits(\"Hero\")\n    public static State admit(State s__, byte[] heroId, Hero hero, Ctx ctx)"), java);
        // The ABI: a move's redeemer names its destination first (into), then its arguments (ADR-0012 D3).
        String abi = lower(src(MOVE)).abiJson();
        assertTrue(abi.contains("\"name\": \"into\""), abi);
    }

    @Test
    void theMoveRules_atTheirPositions() {
        // A move needs an admits action for its type, and an admits action needs a move of its type (JSC1214).
        bad(src("  @@owned action Hero.transfer(destination: into) only owner { return state }"), "JSC1214", "which must consent");
        bad(src("  @@action admit(heroId: Bytes) admits Hero { return state }"), "JSC1214", "but no owned action moves");
        // admits goes on an instance action, never batched; its first parameter is the object's id (JSC1212).
        bad(src(MOVE + "  owned action Item.x() only owner @@admits Hero { return state }"), "JSC1212", "admits goes on an instance action");
        bad(src(MOVE.replace("action admit(heroId: Bytes, hero: Hero) admits Hero", "@@action admit(heroId: Int, hero: Hero) admits Hero")),
                "JSC1212", "the arriving object's id, a Bytes");
        bad(src(MOVE + "  action y(id: Bytes) @@admits Wand { return state }"), "JSC1212", "Wand is not an owned type");
    }

    @Test
    void admitsAreBoundToTheirType_atTheChecker() {
        // A21: the arriving object of admits Hero is a Hero; typed Item it is refused.
        var e = assertThrows(JscException.class, () -> JscV2.lower(src(MOVE.replace("hero: Hero) admits Hero", "hero: Item) admits Hero")
                .replace("hero.level > 1", "hero.power > 1")), "G.jsc"));
        assertTrue(e.code.startsWith("JSC"), e.getMessage());
        System.out.println("[M3a3 A21] an arriving parameter typed Item on admits Hero: " + e.code + " " + e.getMessage());
        // A19: into in an owned init or destroy, or on an instance action.
        var init = assertThrows(JscException.class, () -> JscV2.lower(src(MOVE + "  owned init Item.make(d: into) { return { owner: caller, power: 1 } }"), "G.jsc"));
        System.out.println("[M3a3 A19] into in an owned init: " + init.code + " " + init.getMessage());
        var destroy = assertThrows(JscException.class, () -> JscV2.lower(src(MOVE + "  owned destroy Item.gone(d: into) only owner { pay(owner, balance) }"), "G.jsc"));
        System.out.println("[M3a3 A19] into in an owned destroy: " + destroy.code + " " + destroy.getMessage());
        bad(src(MOVE + "  action z(@@d: into) only master { return state }"), "JSC1205", "is for an owned action");
    }

    @Test
    void invariantsByType() {
        String java = lower(src(MOVE + "  invariant Hero.heroLevel = level > 0\n  invariant Item.itemPower = power <= 100")).java();
        assertTrue(java.contains("heroLevel(Hero s__)") && java.contains("itemPower(Item s__)"), java);
        bad(src(MOVE + "  @@invariant Wand.x = true"), "JSC1203", "Wand is not an owned type");
        // An owned type that keeps a BigMap has no invariants (on chain only its root is there).
        bad("""
                jsc 2
                package t
                contract V {
                  state { keeper: Key }
                  owned Vault { owner: Key, inv: BigMap<Bytes, Int> }
                  init() { return { keeper: caller } }
                  owned init Vault.make() { return { owner: caller, inv: BigMap.empty() } }
                  owned destroy Vault.end() only owner { pay(owner, balance) }
                  @@invariant Vault.small = true
                }
                """, "JSC1213", "holds a BigMap");
    }

    /** #107 review C3: an arriving object that keeps a BigMap is admitted by its id alone (JSC1212 at the parameter). */
    @Test
    void anArrivingObjectWithABigMap_isAdmittedByIdAlone() {
        String vault = """
                jsc 2
                package t
                contract A {
                  state { keeper: Key }
                  owned Vault { owner: Key, inventory: BigMap<Bytes, Int> }
                  init() { return { keeper: caller } }
                  owned init Vault.open() { return { owner: caller, inventory: BigMap.empty() } }
                  owned action Vault.move(to: into) only owner { return state }
                  owned destroy Vault.close() only owner { pay(owner, balance) }
                """;
        lower(vault + "  action admit(id: Bytes) admits Vault { return state }\n}\n");
        bad(vault + "  action admit(id: Bytes, @@v: Vault, k: Bytes) admits Vault { require(v.inventory[k] > 0, \"x\"); return state }\n}\n",
                "JSC1212", "keeps a BigMap");
    }
}
