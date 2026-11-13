# `.jsc` 2, milestone M2b: report (ADR-0018 §8, r18)

Date 2026-11-13. Branch `feat/jsc-2-m2b`, stacked on `feat/jsc-2-m2a` (`25c2a8f`, ADR-0018 r15). JuLC
`v0.1.0-pre21-rc1`, cardano-client-lib `0.8.0-pre7`, JDK 25, Node 22, macOS arm64. Every number below was measured at
this branch's head, by the test named with it.

M2b is ADR-0009 in `.jsc` 2: deploy-time `params`, `invariant`s, `event`/`emit`, and the modifiers `quorum` and
`authorized`, gated by the treasury port. ADR-0018's amendment is r18 (r16 and r17 are M4a's and M1h's).

## What M2b built

| Item | Where | Tests |
|---|---|---|
| **`params { … }`** (ADR-0009 D1): the `@Params` record, named `Config`; read as `params.f` in methods, views and invariants (never in a `fn`, `JSC0433`), never a time value; an `init` takes them whenever the contract declares them (first), any other method when it reads them (after the state) | `Parser.contract`; `Checker.paramsRef`; IR `ParamsRef`, `Program.params`; `Effects.takesParams`; `JavaEmitter` (`Config params__`, captured by helpers) | `JscV2M2bCheckerTest.params_…`; five-way (Treasury, Council) |
| **`event X { … }`, `emit(e)`** (ADR-0009 D5): each event a variant of the contract's `Event`; `emit` in actions and destroys (arms and loops too), threaded like the other effects; `events` last in the result records (`Outcome`, `Received`, `Settled`), and last in the redeemer of every method whose record has them | `Parser`; `Checker` (Emit); IR `Emit`; `Effects` (kind `emit`); `JavaEmitter` (`events__none()`, prepend then reverse) | `JscV2M2bCheckerTest.events_…`; five-way; mutants (events one step away) |
| **`invariant name = e`** / **`{ … }`** (ADR-0009 D4): `@Invariant public static boolean name(State s__[, Config params__])`, checked like a view's body | `Parser`; `Checker.invariant`; IR `Program.invariants`; `JavaEmitter.invariant` | `JscV2M2bCheckerTest.invariants_…`; five-way (`invariant:withinCap`) |
| **`quorum k t`**, **`authorized c`** (ADR-0009 D2, D3): `@Quorum(keys, threshold)`, `@AuthorizedBy`; each field a state field or `params.f` (a quorum's two fields of one place, `JSC0439`); audiences (J16); no `Ctx` | `Parser.mods`; `Checker.method`, `modField`; `JavaEmitter` | `JscV2M2bCheckerTest.quorumAndAuthorized_…`; five-way |
| **ADR-0009's rules at `.jsc` positions**: no quorum, authorized or events in an init (`JSC0405`, `JSC0413`) or a batched action (`JSC0417`, `JSC0425`); no invariants with batched actions (`JSC0436`) or BigMap state (`JSC0435`); no params in a batched action (`JSC0437`); `Config` and `Event` reserved when declared (`JSC0225`); an invariant's name is a method's (`JSC0224`); `emit` needs an event (`JSC0438`) | `Checker` | `JscV2M2bCheckerTest` (5 tests) |
| **Time** (D11, J6): a time field is never a quorum's or an authorized field (`JSC0524`); an invariant never depends on a time value (a comparison refused where it is, `JSC0502`; `JSC0525` behind it); an event may carry one, with a warning (`JSC9008`) | `Checker` | `JscV2M2bCheckerTest`; Council's `stamp` |
| **The ABI manifest** (D12): `params` (the `Config` constructor), each redeemer's `events` (`list<Event>`), the `Event` schema; `AbiCheck` binds the params to the description's `x-joust.params` | `Abi.of`, `AbiCheck.compare` | `checkJscAbi`; `JscV2M2bGateTest.encoding_equalsTheJavaExample`; `JscV2M2bCheckerTest.theAbiStatesTheParamsAndTheEvents` |
| **The IR** (D15): additive in version 1: `params` and `invariants` (present only when declared, so earlier exports are unchanged), `ParamsRef`, `Emit`, the modifiers `quorum` (`keys`, `threshold`) and `authorized` | `Ir`, `IrJson`; `docs/reference/jsc-2-ir.md` | `JscV2IrTest`; the JavaScript reader's validation (recomputes `usesCtx` with the new modifiers, checks each modifier field's type) |
| **Both reference interpreters**: params per deployment (`withParams`; the vectors' top-level `params`); `quorum` (distinct signers among the keys, at least the threshold ≥ 1) and `authorized` (a key signed; a script among the transaction's `approvers`) as modifiers; the validator's **output checks** after the body (params quorums at creation, state quorums' shapes, invariants: class `invariant:…`); events in the outcome | `v2/Interpreter.java`; `js/jsc-interpreter/src/{interpreter,validate,json}.js` | `semantics.test.js` (1 new test, 35 in all); five-way |
| **The written semantics**: §2 (τ.`approvers`, the params, step 8), §2.5 (`invariant:…`), §2.6 (`quorum`, `authorized`), §2.7 (output checks), §5.6 (events), §6 (time) | `docs/reference/jsc-2-semantics.md` | — |
| **The treasury port** and its gates; **Council** (what the port leaves out) | `examples/dsl/.../Treasury.jsc`, `Council.jsc`; `copyPortTests` copies examples/treasury's tests (with an import of its `Guardian`) | below |
| **A processor fix**: a client's destroy that emits without native assets did not compile (`Transition.end(r.payouts()).withEvents(…)`: `end`'s type argument cannot be inferred through the chained call); now `Transition.<S>end(…)` | `ClientGenerator` (three sites) | Council's generated client compiles; its five-way `close` |
| Grammars and docs | `tree-sitter-jsc` (params, event, invariant declarations; quorum/authorized modifiers with `params.f`; `emit`), its corpus case and shared-corpus file, the TextMate grammar; the language reference; the diagnostics catalogue | tree-sitter's tests and the shared corpus; `JscTextMateTest`; `JscV2DiagnosticsTest` |

### What earlier contracts keep

The lowering of every contract without params, events, invariants, quorum or authorized is unchanged: all 22 earlier
`.jsc` files in `examples/dsl` lower to byte-identical Java, IR, source maps and ABI manifests (`generateJsc`'s output
diffed against `25c2a8f`'s), so no M1 or M2a script changed; the committed IR fixtures and manifests did not change.

## The treasury port (J11)

`examples/dsl/src/main/jsc/org/julclang/state/examples/dsl/Treasury.jsc`: 45 non-blank lines (Java: 76). Differences
from examples/treasury:

- **Three field names**: a `.jsc` 2 parameter never takes a state field's name (D3), so rotate's `council` and `quorum`
  are `newCouncil` and `newQuorum`, and freeze's `frozen` is `on`. Names are not encoded; the data is the same.
- **One result record**: the Java example returns `Spent(state, payouts, events)` from pay and `Changed(state, events)`
  from rotate and freeze; the port's actions all return `Outcome(state, payouts, events)` (the payouts empty for rotate
  and freeze). The redeemers are the same.
- **Checked inputs**: the port checks that every council key is 28 bytes (`#input:`), in the method; the Java method
  leaves it to its validator's quorum check (the outcome on-chain is the same).
- The init is `init`; it takes the params, as the Java `open` does.

**Size** (`JscV2M2bGateTest.size_within15PercentOfJava_andBelow14KB`, both built by the toolchain): examples/treasury
**9,297 B**, the port **9,801 B (+5.4%)**, below 14,000.

**Encoding** (`JscV2M2bGateTest.encoding_equalsTheJavaExample`): with the three stated names, the port's manifest
matches examples/treasury's processor description structurally: the datum, the params (`x-joust.params`), every spend
redeemer with its `ctx` and `events`, `CreateInstance` and `BurnInstance`. Without them the comparison finds exactly the
three names.

**Behaviour**: the copied examples/treasury tests pass against the port (`TreasuryPortTest` 7, `TreasuryDescriptionPortTest`
1, and `TreasuryDevnetPortTest` 1 on DevKit: a deploy pinned by `policyIdFor`, a 2-of-3 payment with its `Paid` event
read back, a single signature refused by the node, the guardian script freezing and unfreezing through a zero
withdrawal, a rotation and a quorum close); and every five-way vector also ran on the Java example's JVM method (below).

**Execution units** (`JscV2M2bGateTest.budgets`; each transaction signed by the quorum and with the guardian's
withdrawal, as the runtime builds it; "@ max" with an 18-key council, the most the 640-byte datum bound holds, and nine
signers):

| Call | Java CPU | `.jsc` 2 CPU | Δ CPU | Java memory | `.jsc` 2 memory (Δ) | % of max (CPU / mem) |
|---|---|---|---|---|---|---|
| init | 128,965,377 | 132,426,942 | +2.7% | 417,102 | 432,582 (+3.7%) | 1.32% / 3.09% |
| fund | 127,790,573 | 127,577,813 | −0.2% | 398,298 | 398,158 (−0.0%) | 1.28% / 2.84% |
| pay | 190,812,376 | 194,510,399 | +1.9% | 629,339 | 647,134 (+2.8%) | 1.95% / 4.62% |
| rotate | 131,350,738 | 135,771,146 | +3.4% | 432,848 | 451,841 (+4.4%) | 1.36% / 3.23% |
| freeze | 136,885,785 | 138,888,069 | +1.5% | 421,790 | 430,472 (+2.1%) | 1.39% / 3.07% |
| close | 123,844,730 | 127,391,201 | +2.9% | 438,435 | 456,306 (+4.1%) | 1.27% / 3.26% |
| init @ max | 279,267,132 | 296,376,012 | +6.1% | 898,287 | 969,852 (+8.0%) | 2.96% / 6.93% |
| fund @ max | 255,330,698 | 255,117,938 | −0.1% | 777,003 | 776,863 (−0.0%) | 2.55% / 5.55% |
| pay @ max | 440,160,046 | 443,858,069 | +0.8% | 1,639,532 | 1,657,327 (+1.1%) | 4.44% / 11.84% |
| rotate @ max | 411,701,438 | 429,769,161 | +4.4% | 1,571,536 | 1,646,614 (+4.8%) | 4.30% / 11.76% |
| freeze @ max | 264,425,910 | 266,428,194 | +0.8% | 800,495 | 809,177 (+1.1%) | 2.66% / 5.78% |
| close @ max | 245,652,275 | 249,198,746 | +1.4% | 1,069,923 | 1,087,794 (+1.7%) | 2.49% / 7.77% |

All within +15% and far below the per-transaction maximum. What the port adds: the checked inputs of a council (init,
rotate: a walk over the keys), the empty payouts of rotate and freeze, and `events__none()`.

## Five-way agreement, mutation rejection, attacks (J3, J15)

`JscV2M2bFourWayTest`: every call runs on the JavaScript interpreter, the Java interpreter, the generated Java on the
JVM, the transition program and the validator. `FourWay` now deploys a contract with its params on every leg (the
harness's script applied to them; the JVM method and the transition program given them), signs a call with the keys a
quorum counts and approves it with each approving script's zero withdrawal (an `Approvals` redeemer naming the contract,
instance and action, as examples/treasury's `Guardian` approves), and encodes the events in every step and redeemer.

| Contract | Calls | Refused | Validator runs | Modifier refusals | Invariant refusals | Mutants rejected | Attacks rejected | What they cover |
|---|---|---|---|---|---|---|---|---|
| Treasury (the port) | 27 | 19 | 27 | 7 | 6 | 36 | 96 | quorums of two of three, of one, with a stranger; the params' payment bound; frozen; councils unsorted, repeated, above the quorum, a quorum of 0, a 27-byte key; the guardian script approving, a key of its hash signing, another script approving; close by quorum |
| Council (the rest) | 19 | 10 | 19 | 6 | 3 | 63 | 115 | a quorum of the params' board (and of one); an invariant over the params (`withinCap`); events in a loop, in an `if` in a loop, none at all, from a destroy (`Settled`); authorized by a state key credential, a state script credential (approved, and a key of its hash refused), and a params key credential; a time value in an event |
| **Total** | **46** | 29 | 46 | 13 | 9 | **99** | **211** | |

**Refusals by the output checks** (semantics §2.7): both interpreters refuse with `invariant:quorum council quorum`,
`invariant:quorum members need` or `invariant:withinCap`; the JVM method and the transition program compute the
transition (they agree with the interpreters' `transition`); the validator refuses. The modifier refusals work the same
way with `modifier:quorum …` and `modifier:authorized …`.

**Mutation rejection** (`FourWay.mutants`): for an accepted call of every method, the validator rejects every
transaction proposing a next state or payouts one step away, and (M2b) every redeemer whose events are one step away
from the method's: each dropped, the first repeated, the first one's first field changed (I35).

**Against the Java example**: every Treasury vector also ran on examples/treasury's JVM method. They differ in exactly
one call, where the Java method accepts and the port refuses: a council key of 27 bytes at init (the port's checked
input; the Java example's validator refuses the creation through its quorum check, so it never reaches the chain).

## Found on the way

- **The processor's client did not compile a destroy that emits without native assets** (above): no Java example had
  one. Fixed in `ClientGenerator`; Council's `close` covers it.
- **A modifier's field and a parameter's name**: `params` is a built-in name, so `quorum params.board …` cannot be read
  otherwise; `quorum` and `authorized` are modifier words only in a header, so the treasury's state field `quorum` keeps
  its name (`quorum council quorum`).
- **The message-key test** asserted that a key does not contain the method's name; a method named `pay` (treasury) or
  `spend` (Council) shares the key's fixed words (`#pay:`, `spend<i>`). The test now checks what follows those words.
- **The attack suite and a script-authorized action**: freeze reads no `Ctx`, so no key need sign it; signing it anyway
  makes the suite's "required signer missing" attack a valid transaction. The five-way test signs it with no key.

## Not in M2b

- M2c: `deposit`/`receives` and `funds.deposits`, `reads`, `Instance<C>` (in params), `import contract`,
  `send(to, v, datum)` with generated encoders, stored `Value`s; the oracle and fees ports.
- Events in an init (ADR-0009 r2 refuses them; so does the processor) and in batched actions.
- `only params.f`, `after params.f`, `before params.f`: the processor's `@SignedBy`, `@ValidAfter` and `@ValidBefore`
  read state fields only.
- Invariants that read the transaction: ADR-0009 D4 gives them the state and the params only.

## Decisions for the maintainers

1. **The names `Config` and `Event`**: fixed by the lowering (examples/treasury's own names), reserved only in a contract
   that declares params or events.
2. **An init always takes the params**: the creation is made under the script applied to them, and the Java examples'
   inits take them; other methods take them only when they read them (each `params.f` read is free on-chain).
3. **One result record per kind of method**: an action with effects returns `Outcome`, whose components are every kind
   any of its actions produces; so rotate and freeze carry empty payouts where the Java example has a separate
   `Changed`. The redeemers are the same; it costs rotate and freeze about +1.5–3.4% CPU.
4. **The output checks in the interpreters** (§2.7): the validator's invariants and quorum shapes are part of what a call
   means, as the modifiers are (r14), so the interpreters refuse where the validator does and the differential tests can
   hold all five legs to it.
5. **`JSC0525`** is defence in depth: today a comparison of a time value is refused where it is (JSC0502) before an
   invariant's value could depend on one.

## Tests run at this head

| Command | Result |
|---|---|
| `./gradlew build -PnoDevnet --continue` | BUILD SUCCESSFUL (227 tasks), none failed: julc-state-dsl 144 (`JscV2M2bCheckerTest` 5), julc-state-toolchain 113 (`JscV2M2bFourWayTest` 3, `JscV2M2bGateTest` 3), julc-state-processor 71, julc-state-runtime 76, julc-state-api 10, examples/dsl 82 (with `TreasuryPortTest` 7, `TreasuryDescriptionPortTest` 1), examples/treasury 8. `checkJscAbi`: `21 ABI manifest(s) agree with the processor's descriptions` |
| `./gradlew :julc-state-toolchain:toolchainWasmTest :julc-state-runtime-wasm:runtimeWasm -PgraalvmHome=…/graalvm-25.3.4.1+1.1/Contents/Home` (images rebuilt) | BUILD SUCCESSFUL: `ALL SAME` (every `.jsc` file's Java, source map, manifest and IR equal to Gradle's, Treasury and Council included); S2 in Wasm 1951/1951; 21 depth cases on a cold engine; S2b 43/43 |
| `npm test` in `js/jsc-interpreter` | 35/35 |
| `npm test` in `js/joust` (with the engines just built) | 39/39 |
| `npm test` in `tree-sitter-jsc` | 15/15 parses; the shared corpus agrees on every file (83 corpus, 26 repository, 70 research files) |
| DevKit port tests (`:examples:dsl:test`, Yaci DevKit up) | BUILD SUCCESSFUL: 91 tests, none failed; every DevKit port test passed on Yaci DevKit: `TreasuryDevnetPortTest` (deploy pinned by `policyIdFor`, a 2-of-3 payment and its `Paid` event, one signature refused by the node, the guardian script freezing and unfreezing, a rotation, a quorum close), and Vault, Escrow, Crowdfund, Token, Pool, Dex and `DslDevnetTest` (2) |

Two existing toolchain tests needed updating for the new port: `ValidatorScriptCollisionTest` used examples/treasury's
validator as one "without a twin" (its .jsc 2 port is now its twin: JuLC's own loader, which reads a script by its
simple resource name, can read the port's; the comparison with JuLC's loader now uses `Guardian`, and the treasury's
scripts are compared beside their classes), and `JscV2HashStabilityTest` takes a parameterised contract's unapplied
script digest as its hash (the toolchain gives no hash before the params are applied) and keeps M2b's words out of its
renaming.
