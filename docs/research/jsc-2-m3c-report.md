# `.jsc` 2, milestone M3c: report (ADR-0018 §8, r28)

Date 2026-11-15. Branch `feat/jsc-2-m3c`, stacked on M3b (`feat/jsc-2-m3b`), M3a3, M3a2 and M3a1, which hold the
`.jsc` 2 integration (#98). JuLC `v0.1.0-pre21-rc1`, cardano-client-lib `0.8.0-pre7`, JDK 25, Node 22, macOS arm64,
Yaci DevKit. Every number below was measured at this branch's head, by the test named with it.

M3c, the last part of M3, builds the script purposes (ADR-0010: `withdraw`, `certify`, `vote`, `propose`, the option
`stakeSelf`) with their ledger types, and migration (ADR-0011 D4: `destroy … migrates to f`, `init migrate from
params.p(old: V.State)`). It is gated by examples/staking's StakedVault and examples/upgrade's CounterV1 and CounterV2.
ADR-0018 amendment **r28**.

## What M3c built

| Item | Where | Tests |
|---|---|---|
| **The handlers** `withdraw m(amount: Int, args…) aud { … }`, `certify m(cert: TxCert) aud = …`, `vote m(voter: Voter, action: GovernanceActionId, vote: Vote) aud = …`, `propose m(p: ProposalProcedure) = …`, lowered to `@Withdraw` (`JulcList<Send>`), `@Certify`, `@Votes`, `@Propose` (`boolean`); the words are contextual (only where a member starts) | `Syntax`, `Parser`; `Checker.purposeShape`, `purposeParams`, `purposeRules`; `JavaEmitter` | `JscV2M3cCheckerTest`, five-way |
| **`stakeSelf`**, a flag option (`@StateContract(stakeSelf = true)`), needing a withdraw and a certify handler (JSC1301, JSC1302) | `Parser`, `Checker` | the same |
| **The ledger types** (D5): JuLC's `TxCert`, `Delegatee`, `DRep`, `Voter`, `Vote`, `GovernanceAction`, `GovernanceActionId`, `ProposalProcedure`, `ProtocolVersion`, `Rational`, `Constitution`, with JuLC's constructors in tag order and fields; a read-only `LedgerMap<K, V>`; never stored, passed by a caller, encoded or compared; `ParameterChange.parameters` carried, never read (JSC1320–JSC1325) | `Gov` (one table), `Ty.Gov`, `Ty.LedgerMapT`; `js/jsc-interpreter/src/gov.js` | `JscV2M3cGovTableTest` (against JuLC's records by reflection, every constructor's tag by encoding, and against gov.js) |
| **`std/certs`**: `isRegistration`, `delegatesOnlyTo`, `delegatesVoteTo`, `isDRepLifecycle` (the API's `Certs`) | `Std`, `std.js` | `JscV2BindingTest`, five-way |
| **Migration**: `destroy m() aud migrates to f { }` (`@MigrateTo("f")`) and `init migrate from params.p(old: V.State, args…) { … }` (`@Migrate(from = "params.p")`, Java name `migrate`); the adopted state a checked input (`#old:`); no modifiers, effects or receipts on either side; not beside owned or keyed objects, BigMaps or native assets (JSC1330–JSC1339); a successor writer warned without its lock (JSC9014) | `Parser`, `Checker` (`migrates`, `migrateOld`, `purposeRules`), `JavaEmitter` | `JscV2M3cCheckerTest`, five-way |
| **Both reference interpreters**: the purposes' filters (`purpose:certificate`, `purpose:voter`, `purpose:votes`) before the audience, the decisions after the body (`purpose:withdraw`, `purpose:certify`, `purpose:vote`, `purpose:propose`), a vote once per vote of its voter; the migration's halves (`migrate:to`, `migrate:from`) after the modifiers, τ carrying the successor (`Tx.withSuccessor`) and the prior instance (`Tx.withPrior`); the old state's check (`input:<old>`) | `v2/Interpreter`; `interpreter.js`, `values.js`, `json.js` | five-way; `purpose-strict.test.js` |
| **The IR** (additive within format 2): kinds `withdraw`, `certify`, `vote`, `propose`, `migrate` (with `from`, `old`), the modifier `migrates`, the option `stakeSelf`, the ledger types and `ledgerMap`; **both readers strict** for purposes and migration | `IrJson.purposesChecked`; `validate.js` `purposeRules`; `docs/reference/jsc-2-ir.md` | `JscV2M3cIrStrictTest`, `purpose-strict.test.js` (35 shared mutants, `test/mutants/purpose-*.json`) |
| **The ABI manifest**: `stakeSelf`, `purposes` (the withdraw handler's `Rewards(marker 74, amount, args…[, ctx])`), `migrate` (`from`, the previous contract), `migratesTo`, `Adopt` among the mint redeemers; checked against the description | `Abi`, `AbiCheck` | `checkJscAbi`; `JscV2M3cGateTest.encoding_…`, `modifiers_…` |
| **The test harness**: `PurposeLegs` (each purpose on every leg, the validator run for its purpose, withdraw and approval mutants); FourWay's migration halves (a migrating destroy with its successor minted; an adoption as the new mint sees the whole transaction); `StateHarness.evaluateVotes` (other voters' votes), `certifyContext`/`evaluateContext` (a context no ledger type builds); `TxModel.copyAs` (the new half attacked as a creation) | `julc-state-toolchain` tests, `julc-state-testkit` | the five-way, the attack test |
| **Grammars and the language server**: tree-sitter (the handlers, `init migrate`, `migrates to`, the `stakeSelf` flag), TextMate, the shared corpus (`purposes.jsc`, `migration.jsc`, three refusals) and shapes, the outline | `tree-sitter-jsc`, `js/vscode-joust`, `CorpusParse`, `JscLang`, `js/joust/src/lsp/model.js` | `npm test` (tree-sitter), `JscCorpusTest`, `JscLangTest` |
| Docs | ADR-0018 r28; the semantics (§1.5, §2.10, §2.11, §2.5's `purpose:` and `migrate:`, §4's adopted state); the IR; the language reference; the diagnostics catalogue | `JscV2DiagnosticsTest` |

**A defect found and fixed on the way** (K39): a handler's body could name a state field (`certify c(…) = deposits >
0` compiled: the checker gave purpose handlers the state scope). A handler now has no state, and JSC1318 says so.

### What earlier contracts keep

The lowering of a contract without purposes or migration is unchanged: the new code runs only for the new members, the
option and the ledger types. See "Builds" for the `diff -r` of the generated output against the base. examples/dsl
compiles with `-Ajulc.blueprint=false`, as examples/staking does (JuLC's CIP-57 writer has no vocabulary for the VOTE
and PROPOSE purposes).

## The ports (J11)

**StakedVault** (`StakedVault.jsc`; Java `StakedVault.java`): no stated difference but the creation's name (`init`,
the example's `open`), which is no part of the encoding. **CounterV1** and **CounterV2** (`CounterV1.jsc`,
`CounterV2.jsc`): a parameter never takes a state field's name (D3: `setSuccessor(newSuccessor)`, `setStep(newStep)`);
the adoption's Java name is `migrate` (the example's `adopt`); and one stated behaviour (A44): the port's adopted state
is a checked input, so an admin of 27 bytes is refused, where examples/upgrade's adopt takes the old state as it is.

**The examples' own tests** run against the ports (`copyPortTests`: `staking: StakedVault`, `upgrade: CounterV1`, with
`CounterV2.adopt(` rewritten to `migrate(`): `StakingPortTest` (5) and its description test, `UpgradePortTest` (2) and
its description test; their DevKit tests too (below).

**Size** (`JscV2M3cGateTest.size_within15PercentOfJava_andBelow14KB`):

| Contract | Java | `.jsc` 2 | Δ |
|---|---|---|---|
| StakedVault | 12,121 | 12,312 | +1.6% |
| CounterV1 | 5,738 | 5,785 | +0.8% |
| CounterV2 | 6,581 | 6,784 | +3.1% |

All below 14 KB. The coverage contracts: Steward 13,783 B, Guardrail 6,556 B, TallyV1 7,990 B, TallyV2 8,049 B (under the
runtime's 15,000-byte limit; a first Steward with the guardrail in it was 15,557 B, so the guardrail is its own contract).

**Encoding** (`encoding_equalsTheJavaExamples`): the manifests match the descriptions but for exactly the stated
renamings (`SetSuccessor.newSuccessor`, `SetStep.newStep`), asserted as a list; StakedVault's with none. **Modifiers**
(A9, `modifiers_perMethod_equalTheJavaExamples`): all 16 calls' kind, payable, assets, policies, returns, redeemer
(the purposes' included: the marker 74, `Unit` for certify, vote and propose, Adopt's index) and ctx as the Java
examples'.

**Budgets** (`budgets_saturated`; each transaction as the runtime builds it, every script it runs summed; the Java
example's against the port's):

| Call | Java CPU | `.jsc` 2 CPU | Δ CPU | Java memory | `.jsc` 2 memory (Δ) | % of max (CPU / mem) |
|---|---|---|---|---|---|---|
| StakedVault init | 131,486,340 | 140,159,946 | +6.6% | 443,131 | 478,736 (+8.0%) | 1.40% / 3.42% |
| StakedVault deposit | 117,864,657 | 117,614,277 | -0.2% | 377,541 | 376,571 (-0.3%) | 1.18% / 2.69% |
| StakedVault close (7 of 7) | 188,048,631 | 189,228,850 | +0.6% | 719,992 | 726,295 (+0.9%) | 1.89% / 5.19% |
| StakedVault collect (7 of 7) | 132,762,878 | 132,954,969 | +0.1% | 523,964 | 525,830 (+0.4%) | 1.33% / 3.76% |
| StakedVault certificate (7 of 7, its last test) | 54,786,528 | 54,786,528 | +0.0% | 266,841 | 266,841 (+0.0%) | 0.55% / 1.91% |
| StakedVault certificate (a delegation to the pool) | 55,872,501 | 55,872,501 | +0.0% | 270,934 | 270,934 (+0.0%) | 0.56% / 1.94% |
| StakedVault vote (7 of 7, 8 votes) | 158,680,355 | 158,680,355 | +0.0% | 480,280 | 480,280 (+0.0%) | 1.59% / 3.43% |
| StakedVault proposal (20 withdrawals) | 503,097,480 | 43,872,977 | -91.3% | 956,258 | 157,520 (-83.5%) | 0.44% / 1.13% |
| Counter v1 init | 88,098,684 | 88,098,684 | +0.0% | 273,369 | 273,369 (+0.0%) | 0.88% / 1.95% |
| Counter v1 increment | 73,940,932 | 73,940,932 | +0.0% | 207,108 | 207,108 (+0.0%) | 0.74% / 1.48% |
| Counter v1 setSuccessor | 78,697,313 | 78,697,313 | +0.0% | 226,548 | 226,548 (+0.0%) | 0.79% / 1.62% |
| Counter the migration (v1 upgrade + v2 adopt) | 242,504,672 | 245,443,056 | +1.2% | 769,558 | 782,502 (+1.7%) | 2.45% / 5.59% |
| Counter v2 setStep | 77,739,834 | 77,875,077 | +0.2% | 225,378 | 226,210 (+0.4%) | 0.78% / 1.62% |
| Counter v2 close | 112,389,250 | 113,473,469 | +1.0% | 384,686 | 390,389 (+1.5%) | 1.13% / 2.79% |

The proposal's -91.3% is the guardrail's sum: the port's `withdrawals.values.sum()` is entry-wise, the example's
`totalOf` looks each key up again (quadratic). The init's +6.6% is the port's check of the params at the creation (M2c's
rule), which the example does not make.

## Five-way agreement, mutation rejection, attacks (J3, J15)

`JscV2M3cFourWayTest`: every call on the JavaScript interpreter, the Java interpreter, the generated Java on the JVM
(and the Java example's, for the ports), the transition program (a withdraw handler without Ctx, the creations, the
destroys) and the validator run for its purpose, each refusal asserted by its class.

| Contract | Calls | Refused | Validator runs | Purpose | Migration | Modifier | Invariant | Tx-level | Mutants | Attacks | What they cover |
|---|---|---|---|---|---|---|---|---|---|---|---|
| StakedVault (port) | 41 | 21 | 52 | 16 | 0 | 4 | 0 | 0 | 16 | 27 | the state at the staked address; deposits; a close by the quorum; collect at 0 and 5 ADA; registration and stake-only delegation to the pool, refused for another pool, a StakeVote with an attacker DRep, unregistration, committee and pool certificates and a key credential (the filter before the handler); votes as the script DRep, a stake pool voter, no votes; the guardrail at, under and over the cap; every vector also on examples/staking, no difference |
| CounterV1 (port) | 10 | 4 | 32 | 0 | 2 | 2 | 0 | 1 | 11 | 23 | the successor set by the admin only (and changeable, as the example allows); the upgrade into the policy its field names; another policy and an unset field (`migrate:to`); one lovelace skimmed (the old side's value rule); six old-side mutants (no mint, a key address, another script, another token name, the token not burned) |
| CounterV2 (port) | 10 | 3 | 30 | 0 | 2 | 1 | 0 | 2 | 10 | 28 | the adoption as the new mint sees the whole migration; another previous policy (`migrate:from`); a 27-byte admin (`input:old`, the stated difference); ±1 lovelace (the new side's value rule); four new-side mutants (no burn, one into two, the seed not spent, a wallet seed); the attack suite on the whole migration, each attack refused by one half or both |
| Steward (coverage) | 38 + 4 | 23 + 2 | 51 + 15 | 17 | 0 | 5 | 0 | 0 | 23 | 0 | a withdraw handler with arguments and Ctx (deadline, tip, a 27-byte `to`, a one-lovelace tip: `purpose:withdraw`), authorized by a governor; a total match over every TxCert constructor behind the filter; a vote by quorum deciding on the action's id, index and vote (the second vote refused; another voter's votes beside); K46's approval mutants (another policy, purpose, certificate, index, voter) |
| Guardrail (coverage) | 24 | 16 | 24 | 16 | 0 | 0 | 0 | 0 | 3 | 16 | a propose handler over every GovernanceAction, its records' fields and LedgerMaps (`fold`, `all`, `has`, `size`, `keys`, `any`), a duplicate key counting twice |
| TallyV1 (coverage) | 13 | 8 | 35 | 0 | 2 | 3 | 2 | 1 | 20 | 34 | a list, a map and an option in the migrating state; the successor set once (the lock); a migrating destroy by a quorum of the voters; its quorum's shape at the creation |
| TallyV2 (coverage) | 13 | 8 | 33 | 0 | 3 | 2 | 1 | 1 | 10 | 28 | an adoption with an argument and Ctx (the old admin adopts, before a deadline); its invariant on the adopted state; a 27-byte voter and admin (`input:old`); a map out of order or with a key twice refused by both interpreters; the params' quorum checked at the adoption (a council out of order, its own build) |

**Each defence fails a test without it.** Removing any one of the nine M3c call rules from either interpreter (the
certificate filter, the voter, the votes, the withdrawal's allocation, the certify and propose decision, the vote's
decision, the successor, the previous policy, the old state's check) makes `JscV2M3cFourWayTest` fail: 18 of 18 (the
Java removals as "validator vs interpreter" or "JVM vs interpreter", the JavaScript ones as "the JavaScript interpreter
disagrees"). The allocation rule was at first reached by no vector (its removal passed): Steward's one-lovelace tip,
which makes no output, now reaches it on every leg. Each reader rule has its mutant (35), refused by both readers with a
shared fragment (the checker's code), and each checker rule its refusal at its position (67).

**Raw cases** (`JscV2M3cAttackTest`, 5 tests): A35 (7 purposes no handler takes, refused); A41 (an Approvals list, no
marker, marker 0 and constructor 1 under the withdraw purpose); A37 (certificates of tags 11, 12 and 255 under
StakedVault, and 11 and 12 approved by Steward's governor for that very purpose, refused); A44/K48 (12 malformed old
states refused by the adopting mint; and, stated, a tag value of the wrong kind that the adoption never reads,
accepted); A43 (a migration into the old version's own policy refused, the honest one accepted).

## The security review's items

The lists (`m3-attacks.md` A35–A49; `codex-m3-invariants.md` K1–K3, K39–K50): each M3c item and the test that covers
it.

| Item | Test |
|---|---|
| A35 purposes without handlers fail closed (I38) | checker JSC1302 (stakeSelf without withdraw or certify); `JscV2M3cAttackTest.aPurposeTheContractDoesNotHandle_failsClosed` (Guardrail: certify, vote, withdraw; CounterV1: those and propose: 7 refused) |
| A36 withdraw fully allocated (I37) | five-way `purpose:withdraw` (Steward's one-lovelace tip: the allocation short, on every leg); withdraw mutants (a send one short, to a thief, a thief beside, claims dropped, another contract's reward ref, a real input as the claim, the redeemer's amount +1, the ledger withdrawing +1); copied `withdraw_rewardsToThePayeeOnly_byQuorum` (StakingPortTest). Not built: two credentials withdrawing in one transaction (the harness gives other credentials no amount) |
| A37 certificate filter, exact delegation | five-way `purpose:certificate` (unregistration, pool and committee certificates, a key credential), `purpose:certify` (another pool, StakeVote with an attacker DRep); Steward's total match over all 11 TxCert constructors; `JscV2M3cAttackTest.aCertificatePastConway_isRefused_notTakenByTheLastArm` (tags 11, 12, 255; Steward approved for that purpose and refused); copied `certificates_onlyRegistrationAndExactDelegation` |
| A38 votes and proposals | five-way: Steward's vote by quorum (an action's id, index and vote decide; another voter's entries; a missing own entry `purpose:votes`; a stake pool voter `purpose:voter`); Guardrail's rail over every GovernanceAction (a payee in the withdrawals, a return address, a deposit of 0, the cap); checker JSC1320 (`ParameterChange.parameters`) |
| A39 ledger types as checked data | checker JSC1321 (in the state, a record, a parameter, a view's result, a LedgerMap arity), JSC1322 (comparison), JSC1324 (`set`/`delete`/unknown members), JSC1325 (built by name); both readers (mutants `purpose-state-txcert`, `-params-ledgermap`, `-action-vote-param`, `-withdraw-txcert-arg`) |
| A40 stakeSelf and the state address (I36) | five-way StakedVault: the state at (own script, own stake); the attack suite's "stake credential on the state address" on every instance transaction; DevKit `StakingDevnetPortTest`. Not built: an owned type beside stakeSelf (no port needs it) |
| A41 a Rewarding redeemer read as Approvals | `JscV2M3cAttackTest.anApprovalsListUnderTheWithdrawPurpose_isNoRewards` (an Approvals list, no marker, marker 0, constructor 1); withdraw mutant "the marker of an Approvals list (0)"; `JscV2M3cGateTest.modifiers_perMethod_equalTheJavaExamples` (the redeemer with marker 74 as the description's) |
| A42 the two-sided handshake (I41) | five-way `migrate:to` (an unset successor, another policy), `migrate:from` (an instance of another policy); old-side mutants (no successor minted, output without mint, at a key address, at another script, a token not named for the spent input, the old token not burned); new-side mutants (not burned, one into two, the seed not spent, a wallet input as the seed); value skimmed (transaction-level, both sides, ±1 lovelace); DevKit `UpgradeDevnetPortTest` |
| A43 the successor pinned by a state field | five-way: a stranger's setSuccessor (`modifier:only admin`); the admin may change it in CounterV1 (examples/upgrade's rule, kept); TallyV1 locks it (`require:…the successor is set once`); checker JSC1331 (a params successor), JSC9014 (warned, and silent with the lock); `JscV2M3cAttackTest.aSuccessorThatIsTheOldPolicy_isNoMigration` |
| A44 the old state as a checked input | five-way `input:old` (a 27-byte admin; a 27-byte voter); interpreters: a map out of order, a key twice; `JscV2M3cAttackTest.theAdoptedState_isCheckedAsData` (12 raw datums: an Option of tag 2, Some without its field, a map out of order, a key twice, a 65-byte key, a 27-byte voter, a voter that is an integer, a 29-byte admin, an admin that is an integer, a field short, a field more, constructor 1); stated: an unread field of the wrong kind is not looked at |
| A45 migration excluded where unsound | checker JSC1334 (owned objects on the adopting side, native assets on the migrating side, a previous version with BigMap state); readers (mutants `purpose-migrate-big-maps`, `-migrate-with-send`, `-migrates-with-send`); an instance destroy beside owned objects is JSC1210 already |
| A46 refusal-class parity | every M3c refusal asserted by class on all five legs (`purpose:*`, `migrate:*`, `input:old`, `modifier:*`, `invariant:*`); the JavaScript leg compares classes |
| A47 mutants per transaction shape | the five-way's mutant counts (see the table) |
| A48 byte-identical earlier contracts | `diff -r` of `generateJsc`'s output against the base (see Builds) |
| A49 size and budget at saturation | `JscV2M3cGateTest` |
| K1 five-way agreement | `JscV2M3cFourWayTest` (5 tests) |
| K2 IR | `JscV2M3cIrStrictTest`, `purpose-strict.test.js` (35 shared mutants) |
| K3 ABI | `checkJscAbi`; `JscV2M3cGateTest.encoding_equalsTheJavaExamples` and `modifiers_…` (the purposes' redeemers, the marker, Adopt's index); checker `theManifest_namesThePurposes_andTheMigration` |
| K39 purpose shapes, no hidden state | `JscV2M3cCheckerTest` (JSC1312–JSC1319, JSC1340, JSC1341; a fixed defect: a state field read in a handler compiled, now JSC1318); mutants `purpose-certify-state`, `-withdraw-state`; A35 |
| K40 ledger variants | `JscV2M3cGovTableTest` (the table against JuLC's records by reflection, each constructor's tag by encoding; against gov.js); A37's raw tags; checker JSC1320 |
| K41 withdrawal binding | five-way take/collect at zero and positive amounts, with Ctx (Steward: caller, deadline); A41; withdraw mutants (amount, marker, ledger amount, signer) |
| K42 reward sends' claims | withdraw mutants (claims dropped, another reward ref, a real input). Not built: a withdrawal composed with an owned payout |
| K43 certificates | A37; five-way every certificate kind through Steward's total match (a handler true for each) and the filter before it |
| K44 voting | five-way Steward: two votes, the second refused; another voter's entries; no own entry; all three Vote constructors; `StateHarness.evaluateVotes` |
| K45 proposals and LedgerMap | five-way Guardrail and StakedVault: amounts under the cap summing over it, exactly the cap, several credentials; checker JSC1324 |
| K46 approvals bind the purpose | five-way `approvalMutants` (Steward: another certificate, index, voter, purpose tag, policy) |
| K47 stakeSelf | A40; DevKit `StakingDevnetPortTest` (register, delegate, collect zero rewards; "already registered" tolerated). Not built: an owned stakeSelf coverage contract |
| K48 the handshake | A42; DevKit `UpgradeDevnetPortTest` |
| K49 exact adoption value | five-way transaction-level ±1 lovelace on both halves; checker JSC1338, JSC1339, JSC1332 |
| K50 separate gates | `JscV2M3cGateTest` |

## Builds and DevKit

The branch is `origin/feat/jsc-2-m3b` (`17779afd`) plus M3c's commits; the base had no newer commits to merge.

- `./gradlew clean build -PnoDevnet --continue --no-watch-fs`, then the regenerated fixtures (the coverage contracts'
  IR fixtures, shapes and language models) and `./gradlew build -PnoDevnet --continue`: **BUILD SUCCESSFUL**, 1,167 JVM
  tests, 0 failures (2 skipped). Among them: julc-state-dsl 242, julc-state-toolchain 240, examples/dsl 204 (the
  examples' copied tests among them), examples/staking 6, examples/upgrade 3. `checkJscAbi`: 54 manifests agree with
  the processor's descriptions.
- **Earlier contracts unchanged** (A48): `generateJsc`'s and the annotation processor's output at the head diffed
  against the base's (`17779afd`, `diff -r`): only the seven M3c contracts' files are new; every earlier file is
  byte-identical (Java, IR, source maps, validators, clients); every earlier ABI manifest is unchanged in the tree.
- **Wasm** (GraalVM 25.3.4.1): `toolchainWasm runtimeWasm`, the engines copied into `js/joust/engine` and
  `js/joust-runtime/engine`, then `toolchainWasmTest`: 80 contracts the same as the JVM's (the seven M3c ones among
  them, by size and hash), 141 language-server models byte-identical (58 in their project), 3,831/3,831 S2 cases, 21
  depth cases, 43/43 S2b token calls.
- **npm**: `js/jsc-interpreter` 60/60 (the purpose mutants among them); `tree-sitter-jsc` 22/22 corpus parses, and the
  shared corpus (99 corpus, 62 repository and 70 research files) agrees with the hand-written parsers; `js/state-client`
  48/48; `js/joust-runtime` 33/37 at first (1 skipped): two DevKit flows failed on a dry faucet ("no genesis account can
  pay"), which passed after the DevKit reset (2/2), and the Ogmios provider test that M3a's and M3b's reports also have
  (it probes the M0 fixtures' transactions, which are not on this devnet); its StakedVault and Upgrade flows pass;
  `js/joust` 169/170 (1 skipped), the language-server tests and the page test among them.
- **DevKit** (Yaci DevKit): `./gradlew :examples:dsl:test` gave **231 tests, 0 failures** (1 skipped), the earlier
  ports' DevKit tests among them. M3c's: `StakingDevnetPortTest.stakedFundsDelegateAndWithdraw` (examples/staking's,
  on the port: register, delegate, collect zero rewards; DevKit pays none within a test, and an "already registered"
  answer counts as registered) and `UpgradeDevnetPortTest.v1MigratesToV2` (the migration on chain), both pass.

## Not in M3c

Migration beside owned or keyed objects, BigMap state or native assets (JSC1334); a handler's audience over the state
(none is spent); a typed view of `ParameterChange.parameters` (JSC1320). Not built: an owned type beside `stakeSelf`
(no port has one: A40, K47), two credentials withdrawing in one transaction (A36), a withdrawal composed with an owned
payout (K42).

## Decisions for the maintainers

1. **The syntax**: the handler words `withdraw`, `certify`, `vote`, `propose` with a name, contextual where a member
   starts; `stakeSelf` a flag; `init migrate from params.p(old: V.State, …)` and `destroy … migrates to f`.
2. **The successor can change until the migration** (A43), as examples/upgrade allows: JSC9014 warns, and
   `require(f == #, …)` fixes it once (TallyV1). Refusing instead would break the port's parity with the example.
3. **No migration beside objects, BigMaps or assets** (JSC1334, both sides), which also keeps M3a3's premise that an
   arriving object is the contract's own output (the design review's note).
4. **The adopted state is checked as a read state is**: lengths, map order and keys, addresses; nested tags as written,
   and a field's kind as its type, decoded when read (an unread field of the wrong kind is not looked at:
   `JscV2M3cAttackTest`). A full decode would cost every adoption the whole state's walk.
5. **The order of a handler's checks** (semantics §2.10): the purpose's filter, then the audience, the checked
   arguments, the body, the decision. A vote handler runs once per vote and refuses at the first it does not allow.
6. **The examples' blueprint**: examples/dsl compiles without a CIP-57 blueprint, as examples/staking does, until JuLC's
   blueprint writer has the VOTE and PROPOSE purposes.
