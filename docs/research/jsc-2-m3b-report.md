# `.jsc` 2, milestone M3b: report (ADR-0018 §8, r27)

Date 2026-11-14 (round r2: 2026-11-15). Branch `feat/jsc-2-m3b`, stacked on M3a3 (`feat/jsc-2-m3a3`, #107), M3a2 (#104) and M3a1 (#101), which
hold the `.jsc` 2 integration (#98). JuLC `v0.1.0-pre21-rc1`, cardano-client-lib `0.8.0-pre7`, JDK 25, Node 22, macOS
arm64, Yaci DevKit. Every number below was measured at this branch's head, by the test named with it.

M3b builds keyed objects (ADR-0016): an owned type whose objects are unique by a key within an instance, each a node of
a sorted on-chain list. It is gated by examples/registry's Registry (keyed types Ballot and Entry). ADR-0018 amendment
**r27** (r26 is the `.jsc` v1 removal's, #111; M3c takes r28).

## What M3b built

| Item | Where | Tests |
|---|---|---|
| **`owned T keyed by f (signed, maxKeyBytes = n, linkCooldownMs = c, linkWindowMs = w) { … }`** (ADR-0016 D1): a keyed type, lowered to `@Owned(key = "f", maxKeyBytes = n[, signedKey = true][, linkCooldownMs = cL][, linkWindowMs = wL])` (the defaults left unwritten) | `Syntax.KeyedDecl`, `Parser.keyedDecl`; `Checker.keyedOptions`; `JavaEmitter.keyedAttributes` | `JscV2M3bCheckerTest`, five-way |
| **`owned destroy T.m() only f retire`** (ADR-0016 D10): a retiring exit, `@OwnedDestroy(retire = true)`; `retire` joins the modifier words (JSC0211) | `Parser.mods`; `Checker` (`case "retire"`); `JavaEmitter.method` | the same |
| **The processor's keyed rules at `.jsc` positions** (JSC1240–JSC1251): the clause's syntax; the key field (a `Bytes`, `Key` or `Hash` field); the options' ranges; the contract's limits (no native assets, batched actions or `BigMap`s beside keyed types, at most 16 keyed types, maxDatumBytes at most 2048); the retiring escape hatch, signed by its owner and needing nobody else; every keyed action with an audience (`open` is not one); no moves of keyed objects; **the fixed key** (every keyed action's result keeps the key, checked syntactically and conservatively); the generated names (`Link`, `Insert_m`, `Remove_T`, `Head_T`, `Keyed_T`, `Retired_T`, the client's and the handle's members, the redeemer fields `anchor`, `removed`, `instance`); a retired node's worst case within maxDatumBytes; **no key that is a time value** (D11) | `Checker.keyedOptions`, `keyedNames`, `keyedMethod`, `fixedKey`, `keyedRules` | `JscV2M3bCheckerTest` (7 tests: every code at its position) |
| **The processor's keyed warnings**: squatting (JSC9011), `linkCooldownMs = 0` (JSC9012), a keyed action authorized by someone other than the retire's signer (JSC9013) | `Checker.squatting`, `keyedRules` | the same |
| **Both reference interpreters** model the list's rules as transaction preconditions (semantics §2.9): τ carries an insert's or removal's instance and anchor (`Tx.withAnchor`; JSON `instance`, `anchor`); classes `keyed:anchor`, `keyed:window`, `keyed:cooldown`, `keyed:length`, `keyed:signed`, `keyed:order` (in this order, after an insert's or removal's body, as the validator's policy checks them: round r2, #112 C3), `keyed:fixed` (after an action's); before the body only the caller's errors (a retire's τ receives nothing; an insert or a removal names its instance and anchor) | `v2/Interpreter.keyedBefore`, `keyedAfter`; `js/jsc-interpreter/src/interpreter.js` `#keyedBefore`, `#keyedAfter`; `json.js` | five-way; `keyed-strict.test.js` |
| **The IR** (additive within format 2): `keyed` (each keyed type's resolved options), the modifier `retire`; **both readers strict for keyed programs** (`IrJson.keyedChecked`; `validate.js` `keyedRules`) | `IrJson`, `validate.js`; `docs/reference/jsc-2-ir.md` | `JscV2M3bIrStrictTest`, `keyed-strict.test.js` (29 shared mutants, `test/mutants/keyed-*.json`) |
| **The ABI manifest**: the list's datums (`Head_T`, `Keyed_T`, `Retired_T`, in their tags), the inserts (`Insert_m(instance, anchor, …)`), `Remove_T(anchor, removed)`, `Link`, and a `keyed` section (key, index, options, typeId, tags, the Link and Remove_T indexes, the methods by role), checked against the description's `x-joust.keyed` | `Abi.of`, `Abi.keyed`, `Abi.removable`; `AbiCheck.compare` | `checkJscAbi` (43 manifests); `JscV2M3bGateTest.encoding_equalsTheJavaExample` |
| **FourWay** (the five-way harness): a keyed contract's creation with its heads; an insert spending its anchor with Link and minting the key's token; a keyed action continuing its node; a retire continuing it as Retired_T; a removal spending the node and its predecessor and burning the token; keyed mutants (the next object, payouts, and the list's structure one step away) and the attack suite (a retire's "one lovelace added" left out: a builder may top a retired node up); an approval names the object's token; a keyed call is signed only where it needs a signer | `FourWay` (keyed section) | `JscV2M3bFourWayTest` |
| **Grammars and the language server**: tree-sitter (`keyed_clause`, `keyed_options`, `retire`), TextMate (`keyed`, `by`, `retire`), the shared corpus (`keyed.jsc`, two refusals) and its shapes (a keyed type with its key and options), the language server's model (keyed types in the outline as owned types) | `tree-sitter-jsc`, `js/vscode-joust`, `CorpusParse`, `JscLang` | `npm test` (tree-sitter), `JscCorpusTest`, `JscTextMateTest`, `JscLangTest` |
| Docs | ADR-0018 r27, the semantics (§2.9, §2.5's `keyed:<rule>`), the IR format, the language reference ("Keyed objects"), the diagnostics catalogue ("Keyed objects", "Names M3b reserves") | `JscV2DiagnosticsTest` |

### What earlier contracts keep

The lowering of a contract without keyed types is unchanged: the new code runs only when a contract declares one (the
keyed options, names and rules; the manifest's `keyed` section, Link and Insert_m; the IR's `keyed`). `retire` becomes a
modifier word, which no earlier `.jsc` file uses as a field or binding name (Guild's `Hero.retire` is a method, which
may take one). See "Builds" for the `diff -r` of the generated output against the base.

## The port (J11)

**Registry** (`Registry.jsc`; Java `Registry.java`). Stated differences: the state's `open` is `isOpen` (`open` is a
modifier word); a parameter never takes an object's field name (D3: `setOpen(nowOpen)`, `change(pick)`,
`upgrade(newTier)`); `withdraw` reads nothing of the transaction, so neither the method nor its redeemer takes a `Ctx`
(examples/registry's takes an unused one); its result record `Withdrawal` is the port's `EntryOutcome`; an Entry's
`owner` and `guardians` are `Key`s (checked inputs, D5), where examples/registry's are `byte[]`.

**examples/registry's own tests** run against the port (`copyPortTests`' registry list: every file of
examples/registry/src/test is copied or left out with its reason, and any other file stops the build): the attack suite
(`RegistryAttacksPortTest`, 34 tests), the list-integrity property test (`ListIntegrityPortTest`), the ledger model, the
vectors, the description (`RegistryDescriptionPortTest`; its check of js/state-client's copy, which is the example's
description, is disabled) and the runtime checks: 47 tests, 0 failures, 1 skipped. Left out, with the reason: the
processor's and runtime's own test contracts (`m2/`, `widest/`) and the tests that drive only them, the example's size
gate (`JscV2M3bGateTest` measures the port) and its portable steps (`-Ajoust.portable`, which examples/dsl does not
generate). One case is skipped inside `keyedActionsKeepTheList`: its size-rule half grows the datum through `recover`
with an owner of 100+ bytes, which the port's `Key` refuses as a checked input; `JscV2M3bAttackTest` makes the size rule's
case on the port instead. The DevKit tests that drive Registry are copied too (below).

**Size** (`JscV2M3bGateTest.size_within15PercentOfJava_andUnderTheRuntimesLimit`): 14,711 → 14,789 B (+0.5%).
**J11's 14 KB is exceeded by the Java example and the port alike**, as for the locker ports (M3a2): an exception with the
numbers. The port is 211 B under the runtime's 15,000-byte reference-script limit. Its extra bytes are the checked inputs
examples/registry does not have: `cast`'s `voter`, `grant`'s `owner` and `recover`'s `newOwner` (each a `Key` of 28
bytes) and `claim`'s `guardians` (each a `Key`). Keys, the coverage contract: 13,655 B.

**Encoding** (`encoding_equalsTheJavaExample`): the manifest matches examples/registry's description structurally (the
datum, the list's six datums, every redeemer, the keyed section) but for exactly the stated differences, asserted as a
list: `isOpen`, `nowOpen`, `pick`, `newTier`, and `Withdraw` with one field. **Modifiers** (A9,
`modifiers_perMethod_equalTheJavaExample`): all 12 calls' kind, payable, assets, policies, returns, shared, signers,
windows, params, reads and events as the Java example's (withdraw's `ctx` the stated difference), and the description's
keyed section field by field.

**Execution units** (`JscV2M3bGateTest.budgets_saturatedAtTheKeyBounds`; each transaction as the runtime builds it: an
insert spending its anchor with Link, a removal its predecessor; "saturated": a 64-byte name after a head whose next is a
64-byte key, with 5 guardians, the most whose envelope fits maxDatumBytes = 384):

| Call | Java CPU | `.jsc` 2 CPU | Δ CPU | Java memory | `.jsc` 2 memory (Δ) | % of max (CPU / mem) |
|---|---|---|---|---|---|---|
| init (with two heads) | 355,022,696 | 355,310,696 | +0.1% | 1,138,746 | 1,140,546 (+0.2%) | 3.55% / 8.15% |
| cast (insert, the instance by reference) | 322,323,762 | 322,984,621 | +0.2% | 1,026,590 | 1,030,400 (+0.4%) | 3.23% / 7.36% |
| claim (insert) | 316,261,497 | 317,768,177 | +0.5% | 988,382 | 995,552 (+0.7%) | 3.18% / 7.11% |
| claim (saturated) | 355,088,900 | 360,234,864 | +1.4% | 1,103,535 | 1,125,661 (+2.0%) | 3.60% / 8.04% |
| grant (insert, the instance by reference) | 340,350,092 | 341,010,951 | +0.2% | 1,066,327 | 1,070,137 (+0.4%) | 3.41% / 7.64% |
| change | 138,730,593 | 138,488,970 | -0.2% | 446,075 | 445,173 (-0.2%) | 1.38% / 3.18% |
| upgrade | 195,741,766 | 195,452,143 | -0.1% | 617,382 | 616,180 (-0.2%) | 1.95% / 4.40% |
| withdraw | 230,581,431 | 205,023,746 | -11.1% | 735,157 | 642,519 (-12.6%) | 2.05% / 4.59% |
| recover (a guardian's quorum) | 174,024,767 | 174,349,626 | +0.2% | 540,832 | 542,542 (+0.3%) | 1.74% / 3.88% |
| leave (retire) | 178,160,583 | 178,390,646 | +0.1% | 611,123 | 612,391 (+0.2%) | 1.78% / 4.37% |
| retire | 178,672,770 | 178,902,833 | +0.1% | 613,383 | 614,651 (+0.2%) | 1.79% / 4.39% |
| release (remove, the predecessor relinked) | 399,861,268 | 400,954,244 | +0.3% | 1,300,448 | 1,306,219 (+0.4%) | 4.01% / 9.33% |

The port's withdraw is cheaper because it checks no `Ctx` (it takes none).

## Five-way agreement, mutation rejection, attacks (J3, J15)

`JscV2M3bFourWayTest`: every call on the JavaScript interpreter, the Java interpreter, the generated Java on the JVM, the
transition program and the validator, each refusal asserted by its class. Since round r2 (#112 C2, C3) a keyed refusal's
transaction proposes what the call would make without the rule (its next object, events and payouts; `FourWay.unguarded`),
so the validator's refusal is the rule's and not a malformed transaction's, and where the validator tells a refusal apart
it is compared with the interpreters' class (`FourWay.keyedParity`, the "validator refusals compared" column).

| Contract | Calls | Refused | Validator runs | Keyed refusals | Modifier refusals | Invariant refusals | Validator refusals compared | Mutants rejected | Attacks rejected | What they cover |
|---|---|---|---|---|---|---|---|---|---|---|
| Registry (port) | 67 | 43 | 67 | 20 | 9 | 4 | 30 | 118 | 181 | inserts after a head, a live node and a retired node; a signed key (EVE casting ANN's ballot: `keyed:signed`; ANN co-signing: accepted); uniqueness (a live, a retired and the anchor's next key: `keyed:order`); an anchor of another instance and of the other type (`keyed:anchor`); a removal's predecessor of another instance and the other type's head, each pointing at the removed key (`keyed:anchor`, round r2, design F5); a `require` beside a cooling anchor (the validator's `require:`, round r2, C3); the cooldown at ±1 ms, the window W and W + 1, an upper bound at 2^32 − 1 and 2^32; key lengths 0, 64, 65 (`keyed:length`); the unsigned order (`7f`/`80`, `ab`/`ab00`); quorum shapes and the tier invariant at an insert; a guardian's recovery and the new owner's withdrawal (K32); a removal with a wrong or cooling predecessor; retires with and without funds (K31); every vector also on examples/registry (one stated difference: a 27-byte guardian, the port's checked input) |
| Keys (coverage) | 25 | 12 | 25 | 6 | 4 | 1 | 6 | 64 | 134 | a plain owned type beside keyed ones (`Holding_Badge`); a key at index 1 with C = 0 (an anchor just relinked is no obstacle, the window still is); the same key in another instance's list; an action authorized by the object's credential, a key's and a script's (`Approvals` naming the node's token); a removal after a live predecessor; a signed key in a `Bytes` field (27 and 65 bytes refused); a 60,500 ms cooldown at its edge; an invariant of a keyed type; events from a keyed action; maxDatumBytes 2048 |
| **Total** | **92** | 55 | 92 | 26 | 13 | 5 | 36 | **182** | **315** | |

**Each defence fails a test without it.** Removing any one of the seven list rules from either interpreter (the anchor,
the predecessor, the window, the cooldown, the length, the signer, the place) makes `JscV2M3bFourWayTest` fail: 14 of 14
(the Java removals as "validator vs interpreter", the JavaScript ones as "the JavaScript interpreter disagrees"; rerun
after round r2's reordering, 14 of 14 again). The validator's own defences are tested the same way since round r2 (C2):
`JscV2M3bGuardMutationTest` builds Registry without one `require` (`choose 0 to 9`, the stake bound, the tier bound) and
shows the validator then accepts the refused call (cast(10), a withdrawal over the stake, change(10)): 3 of 3.
`keyed:fixed` cannot be reached from a checked program (JSC1248): a hand-made IR that changes a ballot's key
(`keyed-key-changed.json`) loads in both readers and both interpreters refuse its call with `keyed:fixed`, while the
transition computes it (`JscV2M3bIrStrictTest`, `keyed-strict.test.js`); the validator refuses the transaction (the
five-way's key mutants). Each reader rule has its mutant (32; round r2 adds a Key or Hash key under 28 bytes, an
empty and a null `reads` on a retire, C5 and security S1), refused by both readers with a shared fragment, and each
checker rule its refusal at its position.

**Raw cases** (`JscV2M3bAttackTest`, 5 tests): the size rule counts the envelope at an insert (a claim with a 47-byte
name and 6 guardians refused, its envelope 385 B, its actual datum fitting; 46 bytes accepted) and at a keyed action (a
seat's note of 1,837 bytes accepted, 1,838 refused at maxDatumBytes 2048); token kinds never cross arms (a keyed node
with a plain owned arm and with the instance's, a plain object with a keyed arm, a keyed token on a plain datum: 4
crossings refused); round r2 adds the client's names across owned types (Name beside a keyed Name's NameExists refused
by JSC1249, Named built, C1) and the parity check's proof (C3): for cast(10) after a cooling head, `keyed:cooldown`
(round r1's class) fails `keyedParity` and `require:` passes; for cast(3) after it, the reverse.

**Descriptors** (`JscV2M3bAbiDescriptorTest`, round r2, C4): AbiCheck compares the whole `x-joust.keyed` entry (the
lookup, the head, node and retired schemas, the Link and Remove_T redeemers with their purposes and indices); 13
descriptor mutants, each refused.

## The security review's items

The lists (`m3-attacks.md` A26–A34 and A46–A49; `codex-m3-invariants.md` K1–K14, K26–K38, K50): each keyed item and the
test that covers it.

| Item | Test |
|---|---|
| A26 uniqueness and list integrity | five-way `keyed:order` (the anchor's own key, live and retired; its next), `keyed:anchor` (another instance, the other type); copied `forgedAnchors`, `aRetiredKeyInsertedAgain`, `anAnchorOfAnotherInstanceOrType`, `ListIntegrityPortTest` (random sequences against a reference model) |
| A27 key bounds and checked keys | five-way `keyed:length` (0, 65; 64 accepted), `keyed:signed` (signed by another; a 27-byte key in a `Bytes` field); checker JSC1242 (maxKeyBytes 0/65, signed under 28), JSC1244 (maxDatumBytes 2049); copied `signedKeyWithoutTheSignature` |
| A28 the fixed key | checker JSC1248 (every result shape: a spread, a full literal, a const, a conditional, an `if`, a `match`); validator: the five-way's key mutants and copied `keyedActionsKeepTheList` (the key, next, linkedAt, instance changed); the interpreters' `keyed:fixed` on a hand-made IR |
| A29 token-kind arms | copied `aHeadTakesOnlyLink`, `aRetiredNodeTakesNoActions`, `aStateRedeemerOnANodeAndAKeyedOneOnTheInstance`, `linkUnderOtherMintRedeemers`, `linkOnOtherKinds`; `tokenKinds_neverCrossArms` (plain 0x4F against keyed 0x4B, both ways, in Keys) |
| A30 remove and retire | five-way removals (wrong and cooling predecessors, `keyed:anchor`, `keyed:cooldown`) and retires (with and without funds); copied `aRetiredNodeIsNeverRemoved`, `burning`, `theRelink`, `theRoles`, `theValueRule`, `topUpsAndZeroBalances`, `mintingOrBurningTheNodesUnit`; `retire` lowers to `@OwnedDestroy(retire = true)` (`aKeyedType_lowersToTheProcessorsKeyedType`) |
| A31 cooldown and window as preconditions | five-way at the edges (linkedAt + C − 1 and + C; W and W + 1; an upper bound of 2^32 − 1 and 2^32) both interpreters reporting `keyed:<rule>`, the JVM and the transition program agreeing on the transition, the validator refusing where it checks the list (`FourWay.keyedParity`); DevKit `KeyedCooldownDevnetPortTest` (Registry's 60 s exactly at the boundary; Keys' 60.5 s rounded up to 61 slots; C = 0 not waiting) |
| A32 keyed audience and quorum over object fields | five-way: recover by a guardian's quorum over the entry's fields, a stranger refused; the guardians' shape at an insert (`invariant:quorum guardians needed`: unsorted, a threshold over the count); Keys' `authorized` over the object's credential; checker JSC1246 (`open` is no audience) |
| A33 keyed calls in one transaction | copied `twoStructuralOperations`, `anAnchorSpentWithAnOwnedAction`; DevKit `ContestedGapDevnetPortTest`, `ConcurrencyDevnetPortTest` |
| A34 the datum within maxDatumBytes with placeholders | `theSizeRule_countsTheEnvelope_atAnInsert`, `…_atAKeyedAction`; copied `aNodeAtTheSizeLimitThenTheLongestKeyNearTheEndOfTime`, `aRemoveWhereNextIsLongerThanTheKey`; checker JSC1250 |
| A46 refusal-class parity | both interpreters report each keyed refusal's class (`keyed:anchor`, `keyed:window`, `keyed:cooldown`, `keyed:length`, `keyed:signed`, `keyed:order`; the JavaScript leg compares classes); the JVM method and the transition program agree on the transition; the validator's refusal is compared where it tells it (`FourWay.keyedParity`: the failing purpose, a require's message or a checked input's key traced, round r2, #112 C3) |
| A47 mutants per transaction shape | 182 (table above): next and linkedAt of the new or continuing node, its instance, a retired node's key, the anchor's relink (next, linkedAt, a lovelace skimmed), the minted quantity, the anchor the mint redeemer names, a removal keeping its token |
| A48 byte-identical earlier contracts | see "Builds" |
| A49 size and budget at saturation | `JscV2M3bGateTest` (saturated at a 64-byte key after a 64-byte next, the datum at its envelope); the 16-keyed-type limit and the 2,048-byte cap at the checker (JSC1244) |
| K1 five-way agreement | `JscV2M3bFourWayTest` (92 calls) |
| K2 IR | `JscV2M3bIrStrictTest`, `keyed-strict.test.js` (29 shared mutants: options, entries, the hatch, audiences, moves, assets, batched, maxDatumBytes, a time key, `retire` elsewhere) |
| K3 ABI tags and positions | `checkJscAbi` (Registry, Keys: a plain type before keyed ones, a key at index 1, two initializers); `encoding_equalsTheJavaExample`; checker JSC1249 (a method `entryExists`, `nameKeys`, `link`, `insert_claim`, `remove_Name`, a handle member) |
| K5 bytes and integers | five-way `7f`/`80`, `ab`/`ab00`; copied `KeyedVectorsPortTest` (the order and keyIds against the runtime and on chain) |
| K6, K7, K8 inputs, writers, refinements | the port's `Key` fields are checked inputs (a 27-byte guardian `input:guardians`); a signed key in `Bytes` refused at 27 bytes (`keyed:signed`) |
| K9 datum bounds | A34's tests |
| K10 output invariants by type | five-way `invariant:tierInRange` at an insert and an upgrade; `invariant:fewUses` (Keys) |
| K11 authorization and time | five-way K32 (the new owner withdraws, the old refused); checker JSC1251 (a key from a time value), JSC1245 (no `after`/`before` on a retire) |
| K12 token kind and datum type | A29 |
| K14 shared reads | the inserts that read the instance (`cast`, `grant`) by reference; copied `sharedStateFromAnotherInstance` |
| K26 identity scoped by instance and type | five-way (an anchor of another instance and of another type; the same key in another instance's list, Keys); `KeyedVectorsPortTest` |
| K27 heads at creation | five-way `init` (heads built from the ADR's definition) and its mutants; copied `theHeads`, `aBurnDuringCreation` |
| K28 insertion | A26, A27, A31 |
| K29 Link | copied `aSecondNodeWithLink`, `linkUnderOtherMintRedeemers`; the five-way's relink mutants (a top-up of the anchor is allowed: no "lovelace added" attack on it) |
| K30 removal | A30 |
| K31 retire | A30; five-way retires with funds and with only the deposit; the attack suite on a retire without "one lovelace added" (a builder's top-up is allowed, D10) |
| K32 keyed actions keep the structure | A28; five-way recover then withdraw |
| K33 signed keys | five-way (signed by another refused, co-signed accepted); checker JSC9011 (a caller-valued field that is not the key, a discarded comparison: still warned) |
| K34 cooldown as a precondition | A31; five-way C = 0 with a just-relinked anchor (Keys) and a Ctx-less initializer (`take`) |
| K35 writers reserve space | A34 |
| K36 world integrity | copied `ListIntegrityPortTest` |
| K37 the checker mirrors the keyed limits | `JscV2M3bCheckerTest` (maxKeyBytes 1/64, 16/17 keyed types, maxDatumBytes 2048/2049, a BigMap in a plain owned type beside keyed ones, signed under 28, the retire's signer, C = 0's warning) |
| K38 retries through the runtime | DevKit: copied `DuplicateKeyDevnetPortTest`, `UntimedKeyedPendingDevnetPortTest`, `ContestedGapDevnetPortTest`, `HostilityDevnetPortTest` |
| K50 separate gates | `JscV2M3bGateTest` (size, encoding, modifiers, budgets) |
| A1–A25, A35–A45, K4, K13, K15–K25, K39–K49 | owned objects and moves (M3a), purposes and migration (M3c) |

## Builds and DevKit

**Round r2** (#112's review at `4c694d0c`). The branch merges `origin/feat/jsc-2-m3a3` at `ee8650f1`,
`origin/feat/jsc-v1-removal` at `baa2d6a3` (#111: the header is `jsc 1`; this branch's `.jsc` files are rewritten to it)
and `origin/feat/jsc-2-m3a2` at `636e2472` (with #101 at `04b5e5e4`; already contained). `fixtures/m0/dsl` is recorded
again on DevKit (Points' and Jar's scripts under this head's lowering). At the round's head:

- `./gradlew clean build -PnoDevnet --continue --no-watch-fs`: **BUILD SUCCESSFUL**, 1,099 JVM tests, 0 failures (2
  skipped; fewer than round r1's 1,136 as #111 removes v1's). julc-state-dsl 195, julc-state-toolchain 231, examples/dsl
  193, examples/registry 58. The sizes and budgets below are unchanged (Registry 14,789 B, Keys 13,655 B).
- **Wasm** (GraalVM 25.3.4.1): toolchainWasmTest 157 outputs the same as the JVM's (Registry's and Keys' among them), 129
  language-server models byte-identical (49 in their project), 21 depth cases; the engines copied into `js/joust/engine`
  and `js/joust-runtime/engine`.
- **npm**: `js/jsc-interpreter` 62/62 (32 shared keyed reader mutants); `tree-sitter-jsc` 21/21 corpus parses, the shared
  corpus agreeing with the hand-written parsers; `js/vscode-joust` 14/14; `js/state-client` 48/48; `js/joust` 151/158 with
  its language-server tests, 0 failures (7 skipped: 6 need DevKit, 1 is Linux-only); `js/joust-runtime` 18/37, 0 failures
  (19 skipped, DevKit not reachable).
- **DevKit**: not rerun for round r2. The round changes the checker, the interpreters, the readers, AbiCheck and tests,
  not a generated script; the dsl fixtures above were recorded on DevKit in this round.

**Round r1**: The branch merges `origin/feat/jsc-2-m3a3` at `66586603` (#101, #104 and #107 round r1); the numbers below are at the
merged head unless stated.

- `./gradlew clean build -PnoDevnet --continue --no-watch-fs`: **BUILD SUCCESSFUL**, 1,136 JVM tests, 0 failures (2
  skipped). Among them: julc-state-dsl 236, julc-state-toolchain 224, examples/dsl 195 (examples/registry's copied tests
  among them), examples/registry 58. `checkJscAbi`: 47 manifests agree with the processor's descriptions.
- **Earlier contracts unchanged** (A48): `generateJsc`'s output at the head diffed against the base's (`66586603`, `diff
  -r`): only Registry's and Keys' files are new; every earlier file is byte-identical (Java, IR, source maps).
- **Wasm**: `toolchainWasm toolchainWasmTest runtimeWasm` (GraalVM 25.3.4.1): toolchainWasmTest 158 outputs the same as the
  JVM's, 129 language-server models byte-identical (51 in their project), 3,769/3,769 S2 cases and 21 depth cases; the
  engines were copied into `js/joust/engine` and `js/joust-runtime/engine`.
- **npm**: `js/jsc-interpreter` 59/59; `tree-sitter-jsc` 21/21 corpus parses, and the shared corpus (94 corpus, 55
  repository and 70 research files) agrees with the hand-written parsers; `js/state-client` 48/48; `js/joust` 168/170 (1
  skipped), the language-server tests among them: the one failure was the MerkleMap page's DevKit flow ("the caller's
  address has no UTxO to use as seed", a dry wallet on a DevKit shared with other runs), which passed when run again;
  `js/joust-runtime` 34/37 (2 skipped), its one failure the Ogmios provider test that M3a's reports also have (it probes
  the M0 fixtures' transactions, which are not on this devnet).
- **DevKit** (Yaci DevKit): `./gradlew :examples:dsl:test` at the merged head (`672665bf`) gave **220 tests, 0 failures**
  (1 skipped), the earlier ports' DevKit tests and the 9 below among them. Run first at `295723e0`, before the merge, the
  port's DevKit tests, 9 of 9 pass: examples/registry's `RegistrySmokeDevnetTest` (every operation), `ConcurrencyDevnetTest`, `ContestedGapDevnetTest`,
  `HostilityDevnetTest` (a hostile predecessor updating every block cannot block the retire; a removal retries against an
  updated predecessor), `DuplicateKeyDevnetTest` (a duplicate forced past the JVM, refused on chain), `UntimedKeyedPending
  DevnetTest`, and `KeyedCooldownDevnetPortTest` (Registry's 60 s cooldown exactly at the boundary; Keys' C = 0 relinking
  before the previous upper bound and its 60.5 s cooldown rounded up to 61 one-second slots).

## Not in M3b

Keyed types with native assets, `BigMap`s or batched actions (the processor's limits, JSC1244); moves of keyed objects
(JSC1247); a time-derived key (JSC1251). Migration and withdraw or purpose handlers beside keyed types are refused by the
processor (as migrating or asset-aware); `.jsc` has neither until M3c, which extends JSC1244 and both readers' keyed rules
to them (#112 design F4). Purposes, staking and migration are M3c's.

## Decisions for the maintainers

1. **The syntax**: `owned T keyed by f (signed, maxKeyBytes = n, linkCooldownMs = c, linkWindowMs = w)`, options in any
   order, each once, the durations in milliseconds as `maxValidityMs` is; `retire` a modifier word of an owned destroy.
2. **The order of a keyed call's checks** (semantics §2.9, revised in round r2, #112 C3): every list rule after the
   body (its checked inputs, statements and result) and before the output checks, in the order anchor, window,
   cooldown, length, signer, place, then the fixed key after an action's body. This is the validator's order: its
   policy runs the initializer (a removal's spend runs the destroy) before it checks the list, so a call that fails both
   a `require` and the cooldown is the validator's `require:`. Round r1 checked the anchor, window and cooldown after the
   modifiers, before the body, and disagreed with the validator on such a call.
3. **No time-derived key** (JSC1251), stricter than the processor.
4. **`open` is no audience of a keyed action** (JSC1246), as the processor requires; the client's keyed members
   (`tList`, `tExists`, `tKeys`) are refused as method names too, where the processor refuses only views.
5. **The fixed key is checked syntactically and conservatively** (JSC1248): a result built in a `const` or by a `fn` is
   refused even when it keeps the key; write it where it is returned.
6. **Registry's J11 exception**: both over 14 KB, the port +0.5% and 211 B under the runtime's limit.
7. **withdraw without a Ctx**: the port's reads none, so its redeemer has one field fewer than examples/registry's.
