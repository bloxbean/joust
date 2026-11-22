# Protocol 1 client conformance kit

Use the `conformance` classifier of `org.julclang:julc-state-host` at the host version you support. It contains a Java 25 reference checker, a scripted stdio host, and the JSON inputs for testing another implementation. A client test project can resolve it with the same Maven repositories it uses for JOUST. For example, with Gradle Groovy DSL and a `joustVersion` project property:

```groovy
configurations { clientKit }
dependencies { clientKit "org.julclang:julc-state-host:${joustVersion}:conformance" }

tasks.register('copyClientKit', Sync) {
    from configurations.clientKit
    into layout.buildDirectory.dir('client-kit')
}
```

Run `./gradlew copyClientKit` in the client project. From an empty test directory, with `lib` set to the absolute path of that project's `build/client-kit`, run:

```sh
jar xf "$lib"/julc-state-host-*-conformance.jar verifier sessions
java -cp "$lib/*" org.julclang.state.host.conformance.ReferenceChecker verifier/dapp-0015.json
java -cp "$lib/*" org.julclang.state.host.conformance.ReferenceChecker verifier/reject-shifted-pointer.json
```

The first command invoking the checker prints `[]` and exits 0. The second prints `["pointer"]` and exits 1. In Java, `ReferenceChecker.check(JsonNode)` returns the list directly. The kit's dedicated source set supplies a classified jar on the existing Maven publication. Only clients requesting that classifier resolve the kit. HostSpec continues to select the ordinary host jar.

## Test a protocol peer

Point the client's host launcher at this command, with the normal JVM output options:

```sh
java -XX:+DisplayVMOutputToStderr -Xlog:disable -Xlog:all=warning:stderr --enable-native-access=ALL-UNNAMED \
  -cp "$lib/*" org.julclang.state.host.conformance.FakeHost --stdio sessions/bank.json
```

Omitting the session path selects the bundled Bank session. Start another process with `sessions/keyed.json` for the keyed refusal. Each `frames` entry contains a single `client` or `host` message, in wire order. Feed the client its recorded chain answers when it receives each reverse request. The Bank recording includes a failed digest check, successful handshake, description and deployment, missing prerequisite, publication and settlement, prepare, view, listing, cancellation, late reverse answer, shutdown and exit. Both recordings end with the client's `exit` notification, after which the client must close its end (or the process). A further frame fails the run (exit 3), and so does an end still open after five seconds (exit 4).

The recorded sessions prove transport behaviour only. Passing them says nothing about D13: a client that never inspects `prepared` passes both. Feed each `prepared` result you receive to your verifier and compare it with its vector: the session's publication result is `verifier/Bank-publish-1.json` and its prepare result is `verifier/Bank-init.json`.

Object key order and JSON whitespace are immaterial. Array order, params, error or result envelopes, and reverse IDs must match. A client may choose a string or integer forward ID. The fake host correlates its response and the cancel notification with that actual ID. Snapshot paths and fingerprints in the transcripts are fixed test identities. Use the recorded values for this scripted launch. The recorder normalizes these two machine-dependent fields after capturing real frames, once it has checked that the host's fingerprint equals the digest computed for that snapshot. The fake host takes only end of input as a close: an empty or non-object frame after `exit` fails. The live regression also allows installed engine version strings to vary across releases.

Both peers use the host's D2 frame codec. The header limit is 1,024 bytes, the UTF-8 body limit is 4,194,304 bytes in either direction, and a partial frame has ten seconds from its first byte. A mismatch, partial EOF, invalid UTF-8 or malformed frame produces a single-line diagnostic on stderr and a nonzero exit. An idle peer may wait indefinitely between frames.

### Host faults

Each `sessions/violation-*.json` replays a prefix of the Bank recording and ends with one `violation` entry, a transport fault of the host's that the client must treat as a protocol violation (protocol 1 "Launch and framing"). The client must discard any in-flight result, report the violation and stop: close its end (or kill the process) without sending another frame. The fake host then exits 0. Another frame exits 3, and a client that is still connected after five seconds exits 4. Check the client's reported violation in your harness.

The sessions cover immediate faults only. Two client obligations of "Launch and framing" have no session: a frame that starts and does not finish within ten seconds of its first byte, and a host that never answers (the independent hard client timeout). The fake host's five-second stop bound is shorter than the ten-second frame timer, so a client with neither timeout passes every session. Test both in the client's own suite.

| Session | Fault | Client state |
| --- | --- | --- |
| `violation-unsolicited-response` | A response with an ID the client never sent. | describe outstanding |
| `violation-duplicate-response` | A second response to the already answered initialize. | describe outstanding |
| `violation-stray-bytes` | Unframed bytes on stdout. | describe outstanding |
| `violation-malformed-frame` | A well-framed body that is not JSON. | describe outstanding |
| `violation-oversized-frame` | A header announcing a body above 4,194,304 bytes. | describe outstanding |
| `violation-eof-during-call` | The host's output ends. | describe outstanding |
| `violation-reverse-outside-call` | A `chain/read` after shutdown's response, where no call can run. The client may still send `exit` (the entry's `tolerate`), but must not answer the read. | after shutdown |

## Check a signing decision

Read each `verifier/*.json` and run the client's verifier on it. The pass criterion is normative:

- A vector with an empty `violations` list (a positive) must be accepted.
- A vector with a nonempty list (a negative) must be refused, and the reasons the client reports must include one of its codes, or the client's own check id that a documented mapping assigns to that code. `violations` has a single entry except in `reject-action-burns-thread` (below), which two checks refuse. Extra codes are allowed: a stricter client may refuse a negative for more than one reason.

The recorded positives are named after their recording. A derived positive (`accept-*`) and every negative (`reject-*`) name their base in `source` and their edit in `mutation`. A negative also names, in `check`, the one D13 check that alone refuses it (`<code>/<check>`, below); with that check disabled the reference accepts it. `malformed` negatives have no `check`. `reject-action-burns-thread` is the exception: an action that burns the thread token, leaves no continuation and pays the pool's lovelace (less the claimed send) to a stranger. `mint/action` (the thread token is not an action's to mint or burn) and `continuingOutput/thread` (a non-destroying spend has one continuation) each refuse it alone, so its `check` names the second and `also` lists the first. With both disabled the reference accepts it. All examples carry complete inputs. Only `result` comes from the host. Assemble the rest in the client:

| Field | Origin and use |
| --- | --- |
| `method`, `request` | Exact forward method and sent params. The caller address, named payment keys, method, arguments, payment, window and requested publication ID come from here. |
| `anchors` | Snapshot description, contract address, policy and script language. The shared output verifier has its own hash, language and enterprise address. The recorder reads the actual validator resources beside the compiled classes or in their jars and checks their hashes. |
| `resolved` | Client node UTxOs keyed by `txHash#index`, using D6 amount and datum fields. The positive generator obtains these from the recorded node answers. |
| `node` | Complete script hashes present at queried holder addresses and registration observations by credential. An empty holder list means the address was queried. Registration may be `true`, `false` or `"unknown"`. |
| `protocolParams`, `networkId`, `maxFee` | Client node parameters, selected ledger network and user fee ceiling in lovelace. Fee and execution limits follow the protocol-1 wallet rules. |
| `result` | The complete forward result, including `prepared` and any `predicted` projection. Signing uses the independently decoded body, witnesses and resolved UTxOs. |
| `orderedReads` | Included on the two Bank order cases. Compare the provider arrays in `Bank-publish-1` and `permuted`. Selection can change the body hash even though the UTxO set is the same. |

The checker slices the received CBOR, hashes the body and reads the ledger fields independently of the host builder. It binds the checked redeemers and datums to the body's script-data hash using the client's V3 cost model. Input order for the plan follows the wire. Spend pointers use transaction ID and numeric output index in ledger order. Compare `dapp-0003` and `dapp-0015` (the same method, both at pointer 0) with `owned-action`, whose spend is at pointer 1. The method constructor comes from the anchored description in both cases.

Rule 1 says to hash the received body bytes and never a re-encoding: `accept-indefinite-body`, `accept-nonminimal-integers` and `accept-untagged-sets` carry valid non-canonical bodies (an indefinite-length body map and outputs array, non-minimal integer heads, sets as plain arrays) with their correct hash, and `reject-reencoded-hash` claims the hash of the canonical re-encoding for non-canonical bytes. A client that decodes and re-encodes the body fails all four. The `malformed` negatives are bodies two decoders can read differently: duplicate body keys, a duplicate input or redeemer, trailing bytes, an indefinite byte string and an unknown witness key. Refuse them rather than keep the first or last duplicate.

The signing verdict authenticates the compared plan, bounds and signer set. The latter also includes the payment key needed to spend the payer's inputs, even on a body with no explicit required signers. The checker uses no host prediction in its decision. A UI using predicted state, events, sends or payouts to authorize a signature must derive its displayed effects independently. In particular, a tagged output reveals what was paid, including minimum-ADA padding. The pre-padding amount owed can require running the contract's transition.

For sends the checker decodes the output verifier's OutputClaims redeemer and counts every claimed wallet output as the script's release, never the wallet's change (rule 6). `accept-send-releases-lovelace` restates Dex's addLiquidity so that its claimed send to the caller also carries 1.5 ADA the instance releases. `reject-send-claim-on-change` re-points the claim at the payer's change, which covers the send with the wallet's own LP tokens, and pays the release to a stranger. Without the claims rule (`netOutflow/claims`) the reference accepts it, and so would every validator. `reject-send-claim-wallet-funded` is the variant that keeps the claimed output but funds it from the wallet.

Rule 6 limits other tokens of the contract's policy to the continuation and third parties: there they need the call's own positive mint. `reject-contract-token-to-stranger` pays another instance's token, held by the pool, to a stranger with lovelace the pool releases; the third-party bound allows the release, so only `continuingOutput/otherToken` refuses it. Wallet outputs are exempt. `accept-partial-remove-liquidity` restates Dex-addLiquidity as the removeLiquidity that follows it: the wallet input holds the 100M LP minted there, the call burns 40M, the caller receives a tagged 40 ADA payout and a claimed send of 40% of the pool's tokens, and the remaining 60M LP return in the payer's change.

The other thresholds the vectors pin are normative too. Rule 3 fixes the wallet addresses (the exact payer, or the caller key's enterprise address on the network: `reject-collateral-elsewhere`, `reject-change-at-enterprise`, `reject-payout-foreign-stake-key`), the instance UTxO as a reference input (the `owned-*` positives) and the 5,000,000-lovelace collateral limit (`reject-collateral-risk` at 5,000,001). Rule 6 fixes the creation deposit bound pay.lovelace + `x-joust.minAda` (`Bank-init` exactly at it, `reject-creation-deposit` one lovelace above). The referenceScript shape fixes the holder minimum (160 + the received holder output's byte length) × coins_per_utxo_size (`Dex-publish-2` at minimum + 1,000,000, `reject-reference-above-maximum` one above, `reject-reference-below-minimum` one below the minimum).

The fee cap is normative (rule 3): ⌊1.2 × (min_fee_a × (S + 200) + min_fee_b + E)⌋ + 500,000, with S the received transaction's byte length and E the exact execution fee rounded up. `accept-fee-at-derived-cap` pays exactly the cap, with the total collateral the ledger's `collateral_percent` then requires, and `reject-fee-above-derived-cap` one lovelace more, both under a raised `maxFee`. Every positive must also pass the ledger's phase-1 rules. `accept-pointer-at-one` is the same plain method as `dapp-0003` with its target at ledger index 1, so a client cannot pick the spend pointer from the call kind. `reject-fee-above-maximum` exceeds only `maxFee`.

### Stable violation codes and check ids

| Code | Failed check |
| --- | --- |
| `malformed` | Unreadable, ambiguous, unsupported or ill-typed transaction encoding, or missing checker input. |
| `bodyHash` | Blake2b-256 of the received body differs from the claimed hash. |
| `scriptIntegrity` | Witness redeemers or datums and the client's language view do not match the body commitment. |
| `planMismatch` | Shown plan, bound, signer claims or creation ID differ from the independent projection. |
| `notVerifiable` | Contract or method outside the locally verifiable M1 set. |
| `network`, `isValid` | Wrong body or address network, or phase-2 validity flag differs from true. |
| `unresolvedInput`, `scriptInput`, `referenceInput` | Missing node resolution, unexpected spending input or holding, or unpinned reference/script. |
| `collateral` | Ownership, return, per-unit conservation or the 5,000,000-lovelace exposure limit fails. |
| `certificate`, `deposit` | Wrong count, form or credential. Deposit covers an incorrect explicit registration deposit or excessive creation deposit. |
| `withdrawal`, `governance`, `auxiliaryData` | Unauthorized reward account or amount, governance fields, or auxiliary data/hash present. |
| `mint`, `signer` | Wrong policy, token or quantity, or payment-key requirements disagree with the call. |
| `upperBound`, `lowerBound` | Missing, expired, empty or overlong upper window, or missing/backdated context or time-rule lower bound. |
| `budget`, `fee` | Invalid execution budget or fee above the protocol-derived or user cap. |
| `redeemer`, `pointer` | Wrong purpose/count, schema, constructor, arguments, context, seed or burn/mint authorization, or wrong ledger index. |
| `continuingOutput`, `walletOutput` | Wrong continuation, token, inline datum or owned instance, or an unexpected wallet address, script or payout tag. |
| `balance`, `netOutflow` | Per-unit conservation or nonnegativity fails, or wallet loss/third-party receipts exceed the authorized amounts. |
| `publicationShape`, `publicationPresence`, `holderLovelace` | Publication target, script, holder structure or payer change fails, independent absence evidence fails, or holder value is outside the inclusive minimum through minimum + 1,000,000. |

Each check of the reference has a stable id `<code>/<check>`. The vectors' `check` fields use them, and a client may map its own check ids onto them.

| Code | Checks |
| --- | --- |
| `bodyHash`, `scriptIntegrity`, `isValid`, `auxiliaryData` | `digest`; `hash`; `flag`; `present` |
| `planMismatch` | `plan`, `validFrom`, `validTo`, `signers`, `creationId` |
| `notVerifiable` | `classify` (method outside the M1 set), `field` (a constructor field the checker does not know) |
| `network` | `client` (selected network and payer), `body`, `output` |
| `unresolvedInput`, `scriptInput`, `referenceInput` | `spending`, `reference`, `collateral`; `foreign`, `target`, `datum`, `ownedInstance`; `allowed`, `scripts`, `witnessScript` |
| `collateral` | `none`, `present`, `owner`, `returnAddress`, `returnClean`, `returnNonNegative`, `limit`, `conserved` |
| `certificate`, `deposit`, `withdrawal`, `governance` | `count`, `shape`; `registration`, `creation`; `allowed`, `account`, `amount`; `votes`, `proposals`, `treasury`, `donation` |
| `mint`, `signer` | `instance`, `action`, `publication`; `allowed`, `named`, `cosigners`, `caller`, `form`, `signedBy` |
| `upperBound`, `lowerBound`, `budget`, `fee` | `window`; `context`; `units`, `total`; `sign`, `derived`, `max` |
| `redeemer`, `pointer` | `decode`, `constructor`, `argument`, `args`, `ctxCaller`, `ctxReceived`, `assetsIn`, `instance`, `seed`, `mintRedeemer`, `purpose`, `count`, `publication`, `claimsShape`, `claimsOrder`, `claimsBy`; `method`, `mint`, `reward` |
| `continuingOutput`, `walletOutput` | `thread`, `datum`, `script`, `instance`, `otherToken`; `script`, `tag`, `claimedTag`, `change`, `foreignKey` |
| `balance`, `netOutflow` | `nonNegative`, `conserved`; `wallet`, `third`, `claims` (the claims accounting of rule 6) |
| `publicationShape`, `publicationPresence`, `holderLovelace` | `kind`, `change`, `holder`, `count`; `holder`, `registration`; `range` |

Every check but three has a vector of its own. `network/client`, `scriptInput/target` and `redeemer/publication` refuse only bodies that another check refuses too (the payer's change or collateral return on the wrong network; the spend pointer and redeemer count; the collateral a redeemer needs).

Within a check, each conjunct and each accounting term has a part id `<code>/<check>#<part>` in the reference's sources. The terms are those of rule 6 (what the script releases: the target's value, less the continuation, plus mint, less tagged payouts and claimed sends; the wallet's allowance: fee, payment or creation deposit, offered assets) and of the balance, collateral and fee-cap rules. A client that drops one of them fails some vector: for example `reject-payment-diverted` lowers `owned-action`'s continuation by the payment and pays it to a stranger, which only the continuation term of the third-party bound refuses. The two release terms (`netOutflow/third#target`, `#mint`) have no positive yet: deciding them needs a recorded call in which a script releases value to another party, and the M1 sources pin every payout and send to the owner or the caller (`VerifierVectorsTest.NEEDS_POSITIVE`). A client dropping one of them is only stricter. Seventeen parts are equivalent mutants that no body can decide, each refused through another check; `VerifierVectorsTest.EQUIVALENT` gives the reason for each. Where two of them cover each other, `COVERING` requires the pair, dropped together, to decide some vector (`reject-action-burns-thread` for `continuingOutput/thread#count` and `mint/action#notThread`).

### Reproduce the recordings

From the JOUST checkout run:

```sh
./gradlew :julc-state-host:recordClientConformance
./gradlew :julc-state-host:check :julc-state-gradle-plugin:check -PnoDevnet
(cd js/state-client && npm ci && npm test)
```

`ClientVectors` drives the actual framed host with M1b's recorded answers and the two historical M0 prepares. It also reverses the payer set and resolves the inputs selected by that build. `MutationVectors` edits individual ledger items or client evidence. When isolating a check it updates change, deposits, witness commitments, hash and matching host claims as appropriate, and keeps a body's own encoding unless the edit changes it. `VerifierVectorsTest` recreates every derived vector, checks the hash with CCL (which also hashes the received body bytes), and reruns the oracle with just the target check disabled. Its `everyCheckDecidesSomeVector` reads the check ids from the checker's sources, requires a vector for each (except the three above), and disables each check in turn: one of its vectors must then change verdict. `everyPartDecidesSomeVector` does the same for every part over all vectors, and requires the listed equivalent parts to change none. The public checker always enables every check. `RecordedSessionsTest` regenerates and compares both sessions and derives the `violation-*` sessions from the Bank recording; `FakeHostTest` drives each with a client that stops and one that continues. The Node wallet differential exercises the intersection of the two verifiers' rules, using the same fee ceiling and sent window. Its gap list names the vectors whose check the page verifier does not make yet; each must still be accepted by the page, so a gap that closes fails until its entry is removed. Its false-refusal list names positives the page still refuses, each with the one problem it reports: `accept-partial-remove-liquidity`, because verify.js applies rule 6's other-token check to wallet outputs too (bloxbean/joust#164).

The checker shares some code with the host, so "independent" is partial: the script-data hash takes the language view from CCL's `CostModelUtil` and `CostMdls` (the functions the host's builder uses), and argument and datum checks use the runtime's `SchemaCodec` and julc-core's Plutus data codec. The body walker, ledger projection, hashing and every D13 rule are the checker's own. The classifier resolves the host's runtime graph, although the checker needs only Jackson, Bouncy Castle, CCL, julc-core and `SchemaCodec`.

### Exercise the npm host

Package both Web Images before running the npm differential. Use a GraalVM 25.3 installation, Binaryen on PATH, and the package dependencies used by the npm toolchain.

```sh
./gradlew :julc-state-runtime-wasm:runtimeWasm :julc-state-toolchain:toolchainWasm -PgraalvmHome="$GRAALVM_HOME"
mkdir -p js/joust-runtime/engine js/joust/engine
cp julc-state-runtime-wasm/build/wasm/{joust-runtime.js,joust-runtime.js.wasm,package.json} js/joust-runtime/engine/
cp julc-state-toolchain/build/wasm/{joust-toolchain.js,joust-toolchain.js.wasm,package.json} js/joust/engine/
./gradlew :julc-state-host:nodeHostConformance
```

The task builds the conformance classifier and runs its reference checker on the npm host's actual results. The client builds the fixture contracts through the npm compiler, replays the Bank and keyed sessions in wire order, exercises every host vector and reconstructs every eligible M0 call, checking each that has a signing vector (`dapp-0003`, `dapp-0015`) against it. It checks complete unsigned bytes and body hashes. The machine-dependent snapshot identity, engine versions and named-view capability are normalized. The harness checks that each negative uses its positive's request and applies its changes to the live result or the independent client evidence. The reference checker must report the recorded violations.

### ADR-0022 §9 adversarial coverage

| Scenario | Executable coverage |
| --- | --- |
| I1 stdout pollution, native and inherited descriptors, termination | `BootstrapTest.applicationOutputAndTermination` and `bootstrappedFdOneIsStderrEvenForEarlierReferencesAndNativeCode`. |
| I2 frames, sizes, failed writes and stalls | `TransportTest.cappedHeaderAndMultibyteBody`, `invalidUtf8Terminates`, `incompleteFrameTimesOutFromFirstByte`, `oversizedResultAndFailedWriteNeverCommit`. `FakeHostTest` checks the client-facing replay process, a frame after the recording, and each scripted host fault (`scriptedViolationsRequireTheClientToStop`). |
| Client transport obligations (unsolicited or second response, reverse request outside a call, stray bytes, malformed or oversized frame, EOF during a call) | The `violation-*` sessions, for a client under test. |
| Unknown response ID | `ClientConformanceTest.unsolicitedReverseIdCannotAnswerAnOutstandingPrepareRead`. The fake additionally rejects a mismatched ID in a recorded reverse answer. |
| Late reverse answer after cancellation and forward ID reuse | `TransportTest.controlWinsDuringReadAndExecutionDrains`, including a stale answer during the following generation. The Bank transcript includes a late answer. |
| Read count/byte cap, inconsistent answers, decreasing point | `ReadsTest.everyChunkCountsAndMustKeepItsChainPoint`, `addressReferenceDuplicatesAndScriptHashAreChecked` and `separateLookupsAgreeAndTipsCannotMoveBackwards`. |
| Failed read during publication, script absent | `M1bTest.publicationChecksTheLastChunkAndPropagatesReadFailure`, `ReadsTest.failedUnsupportedUnknownAndNegativeAnswersAreDistinct`, `IntegrationTest.missingReferenceAndProviderFailureStopBeforePublication`. |
| Unanswered read/deadline, cancellation during CPU execution, immediate next call, concurrent prepare | `TransportTest.controlWinsDuringReadAndExecutionDrains`, `cancelledCpuResultCannotCommitOrReplyAgain` and `uncooperativeExecutionStopsHost`. |
| Decreasing/implausible now, machine clock one day away | `IntegrationTest.clockLimitsAndReset`, `timedEscrowUsesSessionGenesisAndSentNow`, `ClientConformanceTest.contextSeesTheClientClockOneDayAheadOfTheMachine`. |
| Foreign address network, mainnet with and without opt-in | `IntegrationTest.wrongNetworkAndCallerSuppliedInputs`, `LaunchTest.negotiationOptInDigestAndIdentity`, the network signing vectors. |
| D8 transitions, rollback, rewind, restart, expired-on-arrival | `ReservationsTest`, `IntegrationTest.pendingInstanceAndRewindFenceAcrossPrepares`, `expiredOnArrivalNeverReserves`, `M1bTest.finalRegistrationRollbackDropsKnowledgeAndFencesBuilds`. The persistent client journal and recovery high-water mark are client-side obligations. |
| Rebuild after initialize, clean and evict original artifacts | `HostSpecConsumerTest.launchesExactlyTheApplicationGraphAndSurvivesSourceCleanup`. |
| Script after first page, publication rollback | `M1bTest.publicationChecksTheLastChunkAndPropagatesReadFailure` and `finalRegistrationRollbackDropsKnowledgeAndFencesBuilds`. |
| Every D14 verifier-table row, unknown registration after assertion and after confirmed publication | `M1bTest.verifierTableBuildsOnlyMissingComponentsBeforeBalancing` and `providerUnknownRequiresIndependentRegistrationEvidence`, including the subsequent prepare. |
| Sub-minimum payout to payer, destroy with events | `M1bTest.quorumCountsCallerAndEventsSurviveDestroy`. |
| Interleaved listings across a same-height fork | `InspectionTest.interleavedForkSnapshotsRespectSortOrderResetAndLru`. |
| Cold engine state lookup | The npm conformance test starts a fresh worker, checks the complete-address read and cursor, compares Bank state against the transcript, then lists and decodes a holding. |
| Node frame writer and D12 I/O | `js/joust/test/host.test.js` probes console and raw stdout, fs writes, child processes, sockets and fetch. It exercises fragmented and invalid frames, stalls and exact size limits. |
| Node cancellation and commit | The controlled worker test races cancellation with CPU execution, sends an old read answer after ID reuse, waits for a deadline and exceeds the result cap. It counts committed calls. |
| Node snapshot, restore and network | The Bank recording exercises stage mismatch and late answers. The real worker tests restore twice, rewind and rearm a tombstone, check the reconciliation fence, and exercise mainnet opt-in and wallet network validation. |
| Honest future-start keyed window, widened and late-start windows, refused first designation, two instances with the same method and key | M3 keyed preparation. M1 exercises the typed keyed refusal on both peers. |
