# JOUST dev host protocol 1

A session runs one trusted project's generated clients. The client supplies the chain and slot clock and owns signing, persistence and submission. Both the JVM and npm hosts prepare unsigned transactions in memory using the ADR-0022 wire interface.

## Launch and framing (D1–D4, D10)

For Gradle projects run `joustHostSpec` in the contract project and read its declared output
`build/joust/host-spec.json` after success. Its fields are `protocol` (1), `java` (an absolute toolchain executable),
`mainClass` (`org.julclang.state.host.DevHost`), `launch` and `digest`. Each launch entry has its zero-based ordinal,
absolute path and kind. A `dir` has a `files` object mapping relative POSIX paths to lowercase SHA-256 hashes.
A `jar` has one `sha256`. The digest uses the fingerprint format below. Missing optional source-set outputs take
no ordinal. Missing artifacts and jar manifest Class-Path additions fail the build.

The client must obtain user trust and an explicit launch action. Validate the ordered launch spec, copy every entry into a private immutable snapshot, verify the digest, and retain the snapshot until the process exits. Launch the project's JDK 25+ toolchain from the project root with these arguments, each passed directly to the executable.

```text
-XX:+DisplayVMOutputToStderr -Xlog:disable -Xlog:all=warning:stderr --enable-native-access=ALL-UNNAMED
-cp <ordered snapshot class path> org.julclang.state.host.DevHost --stdio
```

Use the D3 environment allow-list (PATH, HOME, LANG, LC_*, and Windows USERPROFILE, SystemRoot and TEMP/TMP). Never pass project JVM options, send SIGQUIT, or start the host to select a project or network. Drain stderr continuously into a bounded diagnostic buffer. Linux and macOS divert descriptor 1 to stderr before loading project classes. Windows redirects Java standard output and relies on the client's strict frame parser.

In each direction send one JSON-RPC 2.0 object per frame. Encode UTF-8, prefix with ASCII `Content-Length: <decimal byte count>\r\n\r\n`. Header names are case-insensitive. Content-Length occurs exactly once. Content-Type is optional. The entire header including its delimiter must fit in 1,024 bytes and the body in 4,194,304 bytes. Both must arrive within ten seconds of the first byte. Idle time between frames has no limit. Keep reading the host's output: a host may end the session when one of its frames does not finish writing within ten seconds of its first byte. A malformed header, encoding, JSON or envelope, partial EOF, oversized body or stalled frame terminates the session. JSON nested more than 1,000 levels deep is malformed. The host gives a single-line reason on stderr. A legitimate forward result too large to send gives a small `bad/tooLarge` response instead.

Use distinct outstanding forward IDs (string or integer). Exactly one call runs at once. During execution and while a cancelled execution unwinds, another call gets `bad/busy`. A reverse request uses `chain/read` and a session-unique string ID `"<generation>.<sequence>"`. Only the client answers it. The host ignores late or unknown reverse responses. The client must reject an unsolicited or second forward response, a reverse request outside a call, stray bytes and EOF during a call. Discard the in-flight result and stop the process on a violation.

Send the notification `$/cancelRequest {id}` at any time. The first latched result, failure, cancellation or monotonic deadline wins. A cancelled or timed-out read is abandoned. A terminal response commits at most once. The execution thread is interrupted and gets two seconds to stop before the host exits. Enforce an independent hard client timeout. Treat process exit as loss of session state regardless of exit status.

## Initialize (D3, D4)

The first request must be `initialize`. For example, the following parameters configure a local testnet. Replace the snapshot path and digest with verified values.

```json
{
  "protocol": [1],
  "network": {"networkId": 0, "protocolMagic": 42, "mainnet": false},
  "clock": {"zeroSlot": 0, "zeroTimeMs": 1700000000000, "slotLengthMs": 1000},
  "time": {"maxTtlSlots": 900, "settlementSlots": 60, "maxNowAheadSlots": 900},
  "limits": {"callDeadlineMs": 60000, "readsPerCall": 256, "readBytesPerCall": 33554432},
  "build": {"snapshot": "/private/snapshots/example", "digest": "<sha256>"},
  "restoring": false
}
```

The highest common integer protocol is selected. Network ID 1 requires explicit `mainnet:true` after the client's mainnet gate. The client must check the protocol magic against its node. The host checks caller and supplied addresses against the network, including output addresses in restored transactions. Time quantities are nonnegative integer slots or milliseconds as named. A call's `now` must not be below the `now` of any earlier call the host accepted for work, even one that then failed (including `nowImplausible`); `reset` sets the floor again. Slot length, TTL and RPC limits are positive. Set tight TTL and ahead limits on public networks. Set restoring when journaled transactions **or a persisted rewind fence** need reconciliation.

For each class-path entry in zero-based ordinal order, hash every file's bytes with SHA-256. A jar contributes one file with an empty relative path. For a directory sort regular files by the unsigned UTF-8 bytes of their relative POSIX paths. Append one UTF-8 line per file, including the space before the path and a final LF.

```text
<lowercase hex sha256> <decimal ordinal> <dir or jar> <relative path>
```

Hash the concatenation with SHA-256. The host computes this fingerprint from its actual class path before project discovery. A relative path containing a control character (U+0000 to U+001F or U+007F) fails initialization, so no two directory contents give the same lines. A mismatch, a path outside the snapshot, or a jar manifest with a nonempty Class-Path fails initialization. The runtime must match the host's build version. The response gives the chosen protocol, echoed network, capabilities (`views`, `objects`, `quorum` on the JVM host, plus `keyed` on either host when the snapshot contains a served keyed contract), read kinds, fingerprint, versions and contract list. Gradle build versions are null. Execution version fields use null where the installed component supplies no version. Each contract names the actual spend/mint and applicable stake script hashes. A parameterized contract reports only its unapplied template hash and `unsupported:"params"`. It has no deployment hash without parameters.

## Forward methods (D5, D7, D16)

| Method | Parameters | Successful result |
| --- | --- | --- |
| describe | contract (fully qualified name) | description, messages (null for Java), calls with method, kind and either served:true or an unsupported code |
| deployment | now, contract | addresses, policies, scriptHashes, referenceScripts, governors |
| prerequisites | now, contract | ordered array of missing components |
| publish | now, ttlSlots, contract, prerequisite (ID), address | {prepared} or null if satisfied; refusal pending while a live transaction already publishes it or a component of it |
| registered | credential, evidence | empty object |
| prepare | now, ttlSlots, call | prepared and predicted |
| view | now, contract, instance, view, args | {value} |
| instances | now, contract, limit | listing, items, next |
| objects | now, contract, instance, limit | listing, items, next |
| instances or objects, subsequent page | listing, after, limit | listing, items, next |
| decode | contract, datum (hex CBOR) | type and value, or ambiguous (type names) |
| settled | reservation, txHash, status, tip, inclusion slot for confirmed | state after applying the event and depth thresholds |
| restore | transactions, complete | reservations, an array of IDs in transaction order |
| reset | now, optional clock, optional rewound:{tip} | empty object |
| shutdown | empty or omitted | empty object, then stop accepting calls |
| exit | notification, empty or omitted | process exits |

The nested call accepts contract, kind, method, instance, object, args, pay, address and cosigners. Keyed calls also accept designated, pageLimits and operation. Arguments use the description's JSON encoding. A missing pay means zero. Plain calls allow an empty co-signer list. Obtain wallet inputs through the caller address's reverse read. Caller-supplied walletUtxos or other extra fields are errors.

Serve init, action, destroy, ownedInit, ownedAction and ownedDestroy. Only ownedAction and ownedDestroy name an object id (a string), and a keyed call its key (below); any other kind with a non-null `object` is `bad` before any read. Owned spends name the current instance in the object's holding datum. An address answer holding a catalogued contract's unique token (an instance, object, intent or keyed name, ADR-0007 D3) more than once, or in a quantity other than one, is `internal/inconsistentRead`, and its tip does not count. A quorum call takes optional `cosigners`, an array of at most 64 lowercase 28-byte payment key hashes (more is `bad`). The host uses enterprise wallet addresses on the session network and includes the caller once in the body's required signers, even when the method has no Ctx. A SignedBy field must equal the caller. Other methods accept only absent or empty cosigners. Refuse parameterized contracts with `params`, governor-authorized calls with `authorizedBy` and calls outside the wallet's verifiable set with `unverifiable`. This includes batched calls, moves, admissions, migrations and deposits. A served send pins the output verifier in deployment. A served map contract pins the map verifier. A map history's answers count (and advance reservations) only once the rebuild reaches the root the call needs; one history transaction withdraws from the map verifier at most once. A restored body fences a map contract only by that contract's tokens at its own script address or by its mint (#160 Codex review).

### Keyed calls

Use the anchored `x-joust.keyed` description for an instance creation (`init` with list heads) and for `keyedInit`, `keyedAction`, `keyedRemove` and `keyedRetire`. Prepare one call per transaction. For the latter three, `object` is the logical key in lowercase hex. An insert computes its key from the transition's result and returns it in `prepared.object`. Predicted effects and listings identify objects by their 29-byte token names.

Before the first request the client chooses and persists an ordinary lovelace-only input of at least 5 ADA at the payer address. Send its canonical `txHash#index` in `call.designated` and persist the following `call.operation` record. The full contract name, method, instance, key and designated reference identify the operation. Use JSON null for the instance of a deployment and the key of a deployment or insert.

```json
{
  "designated": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa#0",
  "contract": "org.julclang.state.examples.registry.Registry",
  "method": "release",
  "instance": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "key": "61",
  "attempts": []
}
```

Each attempt signed for submission, exported or submitted is journaled as `{txHash, validTo}`, using the body's exclusive upper slot. Persist the per-chain recovery high-water mark before handoff. A declined unsigned body is settled `discarded` and omitted from the attempts array. One that expired before it was handed out needs no settlement: leave it out of the attempts; it no longer holds the operation. Keep its reservation id until the chain is past its `validTo + settlementSlots` at settlement depth: a devnet rewind below its `validTo` makes it live again (`refusal/pending` on the next retry, as for a body still awaiting approval), and settling that revived body `discarded` releases it (#156 K7). Restore accepts the client's record without such a body, expired or revived; a revived one stays reserved until it is discarded or expires (final review F-K1). On restore, each keyed transaction carries the persisted record, which may list attempts made after it. Every successful attempt spends the same designation. A missing designation gives `refusal/needsDesignated`. Inconsistent identity or attempt history gives `bad/operation`. The host keeps an operation's identity for the session: reuse a designation for the same contract, method, instance and key only with that operation's full attempt history, or choose another designation (final review F-K2). On restore the host binds a keyed body to its record's contract only; the record's instance, method and key are the client's own (final review F-K4). A retry's record acknowledges every attempt it lists, with that attempt's own `validTo`, before the host reads the chain: a failed read or an attempt that already expired does not lose the acknowledgement, and an acknowledged attempt is never released by `discarded`. Within one restore batch every transaction of an operation carries the same record, else `bad/operation` in either order; a later batch's fuller record replaces the record of the operation's earlier entries. An insert whose key a reserved, not yet final transaction already holds answers `refusal/pending`, not a duplicate key (#156 Codex review).

The complete result frame commits its reservation. A refused first attempt needs no settlement. A live attempt, including an unsigned one still awaiting approval and not yet listed, or another operation touching a reserved object gives `refusal/pending` (final review F-K3). Read liveness from the payer's complete address listing. A spent designation gives `refusal/mayHaveLanded`. The client checks its node for inclusion; when the operation never handed an attempt out (a first attempt), the designation was spent by something else: choose and persist a new designation and start a new operation (#156 K3). A retry that lists an attempt acknowledges its handoff even when the call then fails, the one state change a failed call keeps, so a later `discarded` cannot release signed inputs (#156 K4). Retry only with the designation still unspent at a tip strictly past every handed-out attempt's `validTo + settlementSlots` and after release of all reservations. Include the persisted `operation` alongside each keyed transaction in `restore.transactions`, including unsigned bodies still awaiting approval.

Send optional `call.pageLimits: [maxStartSlots, maxValiditySlots]` (default `[60, 900]`). The start limit is nonnegative and the validity limit is positive. The final keyed body, transition context and link redeemers share one nonempty window. Its start is in `[now, now + maxStartSlots]`. Its exclusive end is at most both `start + ttlSlots` and `now + maxValiditySlots`. Submit a future-start body at its start. A cooldown beyond the available window returns `refusal/cooldown` with the slot in `retryAtSlot`.

A keyed method without a page-verifiable recipe or described list type, or a removal without a balance-binding Ctx, is `unsupported/keyed`. Yaci Store supplies no producing transaction CBOR. On removal the target's Ctx binds its entire input balance, leaving just the predecessor to be solved by value conservation. On an action or retirement the target's Ctx balance excludes the contract minimum ADA. A keyed method with governor authorization remains `unsupported/authorizedBy`.

Every amount in a chain-read answer has a `unit` of `lovelace` or a lowercase policy id (56 hex digits) followed by a token name of at most 32 bytes in lowercase hex, at most once per output; anything else is `internal/inconsistentRead`. Before reserving or returning a prepared transaction (including a publication), the host checks output quantities and per-unit value conservation against the call's chain reads. A negative output or a mismatch returns `internal/unbalanced`, as does a call body with a certificate, a withdrawal that is not a zero one from the contract's or a shared verifier's reward account, or a mint under another policy. The client independently verifies the body before signing (D13).

`prepared` contains reservation, cbor, txHash, validFrom, validTo, signers and plan. A creation includes its new `instance` or `object`. The `predicted` object contains the following projections. Each item has `predicted: true`.

| Field | Projection |
| --- | --- |
| state | null or {index, instance, type, value} from the continuing thread-token output at the contract address |
| objects | Array of {index, object, type, value, effect} from an owned holding or keyed head, node or retired output, with effect created, updated or retired. A relink reports the updated predecessor at its actual output index. A burn produces {object, effect: "removed"}. |
| events | Array of {value} decoded from the method's mint or spend redeemer at its ledger pointer |
| payouts, sends | Arrays of {index, address, value, owed}. Each value is a map of unit to decimal quantity. The full output value includes any minimum-UTxO top-up. Payouts match the input tag and payee. Sends use the output verifier's claim index. Change has no attribution. |

### Deployment and publication

Deployment takes no wallet. The contract address and policy use the key `contract` in their respective maps. Script hashes have `spend`, `mint` and, where applicable, `stake`. A staking contract also supplies the `reward` address. Contracts using the output verifier add its script hash and holder address under `outputVerifier` and its reward address under `outputVerifierReward`. Map contracts use the same deployment fields under `mapVerifier` and `mapVerifierReward`. Publish the map verifier through the same script and stake-registration prerequisite rules. The `referenceScripts` map gives the first matching out-ref in the complete provider-ordered holder set for each published script. The M1 governor list is empty.

A missing prerequisite is `{id, kind, target, present}`. Its ID is `kind:scriptHash`; target is the holder address for a publication with a script, or the reward address for registration alone. The host lists only outstanding work, in publication order. Registration comes from a stakeRegistration read. A definite provider answer controls the decision. An unsupported read uses session knowledge or gives `present: "unknown"`.

| Verifier script | Registered | Outstanding work, in order |
| --- | --- | --- |
| absent | false | verifier (publish and register together) |
| absent | true | referenceScript |
| absent | unknown | referenceScript, stakeRegistration |
| present | false or unknown | stakeRegistration |
| present | true | none |

Publish the first outstanding ID and settle its transaction before continuing. The host checks the components again on each request. An already satisfied ID returns null. A changed shape or out-of-order ID gives `refusal/prerequisite`. Every M1 publication with a registration uses a pure kind-0 certificate and the protocol key deposit. A prepare with any outstanding component gives `refusal/prerequisite` naming its ID.

The host learns a registration when its publication reservation reaches final confirmation. A client can also send `registered` with the 28-byte credential hash and evidence containing a 32-byte `txHash` and exactly one of a confirmed inclusion `slot` or a duplicate-registration node `message`. A rollback invalidates the associated knowledge. A rewound reset clears learned status, and a new session starts without it. Registration evidence applies only to the credential.

### Views and inspection

A null view returns decoded instance state with empty args. A named view runs its generated JVM method. Values use the description's JSON encoding. Decode tries the contract's declared datum schemas by constructor tag, arity and field shapes. Multiple matches return `ambiguous`, and no match gives `bad/request`.

Start a listing with a limit from 1 through 500. The session reads the full address once, binds the query and tip hash to a snapshot, and sorts by transaction hash then numeric output index. Recognized items include ref, type, decoded value and instance. Objects add the object token name and belong to the instance in the holding, head, node or retired datum. Other recognized kinds and other owners are omitted. Foreign or malformed outputs appear as `{unrecognized: ref}`. The index in `next` is the next page's `after`; null marks the end. Later pages use the same method and listing ID without reading the chain. At most eight snapshots survive, with least-recently-used eviction. Reset clears all snapshots. Restart a listing after `bad/listingGone`.

Both validity fields come from the final body. validFrom is null for an absent lower bound. validTo is the exclusive upper bound. The scoped runtime uses now as its current slot and tip slot during construction, with the supplied genesis conversion. A non-keyed build must fit under now + ttlSlots and ttlSlots must fit maxTtlSlots. A tighter contract deadline shortens the window. Reject an empty window. A lower now is invalid until reset. A chain point may be ahead of now, but now may exceed a point by at most maxNowAheadSlots. Reset changes the clock origin when supplied, drops caches and establishes a new now baseline.

## Reverse reads (D6, D6a)

Each request has a kind and the fields below. Every result includes `tip:{slot,hash,height}` and exactly one of value, absent:true, unsupported:true or failed:{message}. Slots and heights are integers. Block hashes are 32-byte lowercase hex. Empty answers are legal only as listed. A client reporting a provider failure must send failed with its diagnostic. Each reverse exchange counts toward readsPerCall, and the entire UTF-8 response body counts toward readBytesPerCall.

| Kind | Request fields | Value |
| --- | --- | --- |
| utxos | address, optional cursor | Ordered array of unspent CCL UTxOs. Absent means empty. Add more (a nonempty opaque cursor) to each nonfinal chunk. |
| output | ref as txHash#index | A CCL UTxO, possibly spent. Absent means unknown. |
| tip | none | The same chain point as tip. |
| protocolParams | none | Latest CCL ProtocolParams JSON, including the cost models for scripts used. |
| script | hash | type (PlutusScriptV1, PlutusScriptV2 or PlutusScriptV3) and cborHex. Absent means unknown. |
| stakeRegistration | credential (28-byte hash) | registered (boolean). An unsupported read means unknown. |
| assetHistory | unit (policy and token name concatenated), optional cursor | Ordered array of `{txHash, point:{slot,hash,height}}` from oldest to newest. Absent on the first page means no history. Use the same snapshot tip and opaque `more` cursor convention as utxos. |
| redeemers | txHash (32-byte hash) | Array of `{purpose,index,scriptHash,dataHash}` for that transaction. The purpose is spend, mint, cert, reward, voting or proposing. An empty array means a known transaction with no redeemers. Absent means an unknown transaction. |
| datum | hash (32-byte data hash) | Exact ledger CBOR bytes in lowercase hex. Absent means unknown. |

**Map history decision (ADR-0022 Q3, option a, 2026-11-21).** A protocol-1 host with a served map contract advertises capability `maps` and the three read kinds above in `initialize.reads`. These shapes are part of version 1. An incompatible shape requires a new kind or protocol version. A client unable to answer sends `unsupported:true` and the forward call ends with `unsupported/read:<kind>`. A provider failure gives `internal/readFailed`. Each page and every witness or datum lookup uses the call's normal read count, byte budget and deadline.

The host owns the in-memory trie and generates every proof. On a missing input root it replays the unit's history from the empty root, selecting reward redeemers for the anchored MapVerifier. It verifies the datum hash, MPF claims and resulting root before use. Duplicate transactions, out-of-order blocks, inconsistent pages, missing witness data, a redeemer datum over 64 KiB (larger than any transaction) or a history that cannot reach the input root yield `internal/inconsistentRead`. A point may repeat for distinct transactions in the same block; the order of transactions within a block does not matter, as the host applies the history's claims together. The only anchor is the input datum's root, read from the client's own UTxO answer: the transaction ids and the redeemer-to-datum pairing are client-supplied and not authenticated, and they need not be, because any history that reaches that root reproduces exactly the trie it commits to (short of a Blake2b collision). The datum-hash check guards against a provider serving other data for a hash, not against a forged history. A rebuild costs about two reads per history transaction and counts toward `readsPerCall`; a unit whose history needs more is refused `bad/readLimit`. These reads need no transaction CBOR: a Yaci Store client answers `redeemers` from `/txs/{hash}/redeemers` and `datum` from `/scripts/datum/{hash}/cbor` (the script store, on by default), so a stock Yaci DevKit serves them with `store.transaction.save-cbor` off; a Blockfrost client uses the same two endpoints. A client whose provider cannot answer a kind replies `unsupported`, and only map calls that need a history rebuild are refused (`unsupported/read:<kind>`).

A result carries only `tip`, its one outcome and, for a `utxos` value chunk, `more`; any other field (a misspelled `cursor`, an echoed `kind`) is refused with inconsistentRead. Omit `more` on the last chunk (`more:null` is refused). UTxO fields follow ADR-0015 snapshot encoding (tx_hash, output_index, address, amount, data_hash, inline_datum, reference_script_hash). `data_hash` (64 lowercase hex), `reference_script_hash` (56 lowercase hex) and `inline_datum` (nonempty hex, either case) are each absent, null or a string in that format; an empty string for "none" is refused. Quantities are integral. Keep provider ordering across chunks. Report one chain point for the entire address set. The host checks addresses, references, duplicates and script hashes. For an inline datum an omitted redundant data_hash is equivalent to the hash computed from its exact bytes. Conflicting values fail with inconsistentRead. Chain points cannot go backwards within one call. Only the address set establishes liveness. The host memoizes positive and negative outcomes within one execution, filters by asset and pages locally. Local JuLC evaluation uses the session slot configuration and the read protocol parameters. There is no evaluation or submission RPC.

## Reservation journal (D8)

Reserve all spending and collateral inputs of the final body. A body whose reference inputs include an input a live reservation spends is `refusal/pending`: it would read a version that the pending transaction replaces. A reference reserves nothing, so bodies that only read the same input stay independent. A successful write of the complete prepared response is the commit point. Cancellation, failure, an oversized response and a failed write reserve nothing. A body with validTo at or below the greatest tip seen during the call is expiredOnArrival. A client declining a result settles it discarded only if it never handed it out for signing/submission or export.

| Current state | Event or observed chain evidence | Result |
| --- | --- | --- |
| prepared | confirmed with inclusion slot inside the body's validity interval and at most tip | shallow, keeping inputs and collateral |
| shallow | confirmed again at the same slot | shallow |
| shallow | tip strictly greater than inclusion slot + settlementSlots | confirmed, release |
| prepared or shallow | rolledBack | prepared, keep reservations |
| prepared | tip strictly greater than validTo + settlementSlots | expired, release |
| prepared | discarded | discarded, release immediately |
| confirmed | rolledBack | deepRollback, restart required before building |

Apply the event first, then every depth/expiry threshold reached by that tip. The result may already be final. An explicit premature expired is notExpired. Neither the machine clock nor now releases an input. Match both reservation and txHash. Unknown IDs give unknownReservation. Accept repeat final status, reject competing evidence with conflict (including different inclusion slots). A tombstone retains the body's inputs, collateral, validity and inclusion slot. A new preparation of identical bytes gets a new ID.

Ordinary reset keeps every reservation. Before a rewind the client **must persist** an outstanding reconciliation. Send reset with rewound:{tip}. Discarded stays final. Re-arm expired or removed inclusions with future validTo as prepared. After a rewind, settle by the IDs restore returns: identical bodies share one re-armed reservation, and a sibling tombstone answers conflict. A retained final inclusion returns to shallow and holds collateral through its depth margin. Stop advancing thresholds while fenced. All builds give reconcile until a restore batch completes.

After restart use initialize with restoring:true. Restore in batches of signed or unsigned transaction CBOR. Send state prepared, or shallow with its inclusion slot. Re-import each handed-out body whose validTo is above the restored tip, all retained inclusions within their margin **even if validTo has passed**, and unsigned bodies still awaiting approval (or invalidate those approvals). A missing upper bound gives unbounded. Repeated identical entries retain their IDs. Conflicting states or slots for a live body give conflict. Complete with complete:true, including an empty final batch if needed. This closes restore until a new rewind. Clear the persisted reconciliation only after the successful final response.

A map write is private to its prepare until the complete response commits the reservation. Its trie nodes become readable on shallow inclusion. A shallow rollback removes that promotion. Discard or expiry releases the staged nodes, while a final confirmation retains the verified nodes for later calls. Conflicting settlement evidence for a map reservation makes all map calls fail with `refusal/mapsStale`. Reset clears all mirror nodes and overlays. When an affected contract still has pending work, settle it and reset again. Rewinding also marks all mirrors stale and fences building through reconciliation. Once restore completes, unaffected contracts can rebuild immediately.

Restore decodes the map claims of the journal transaction at the map verifier's reward pointer and keeps them to compare with later retries; it does not apply them to a mirror. The affected contract is fenced until settlement and reset. The next call needing its trie reads the chain to rebuild it. Journal evidence alone cannot promote a root into a fresh mirror.

Before signing for submission, exporting or submitting, the client must persist per chain the maximum validTo + settlementSlots of every handed-out body. Read validTo from the body itself. Keep this high-water mark independently of the transaction journal. After losing history do not complete restore until an observed tip strictly passes the persisted bound. Smaller limits in a later session cannot lower it.

## Client signing rules (D13, normative)

Before signing **or exporting**, the client must perform all applicable checks below. A host result alone never authorizes a signature.

1. Extract the exact encoded transaction-body slice from cbor and hash it. Refuse a txHash mismatch. Sign that hash and preserve those body bytes when adding witnesses.
2. Independently read the descriptions and compiled validator resources, including sibling jars, from the immutable snapshot whose digest the client verified. Derive the contract hashes, addresses, network and policies. Anchor shared verifiers in the snapshot's julc-state-verifier jar. Refuse a conflicting fingerprint, contract entry or deployment. Determine the method and its verifiability independently from the description. Refuse any call outside the verifiable set.
3. Resolve every spending, reference and collateral input against the bound node. Check the network ID and every output address. Permit wallet inputs and exactly the requested script target for an ordinary spend. A keyed insert spends its predecessor with Link. A removal spends that predecessor with Link and the target with the requested method, and burns exactly its token with Remove_T bound to both outrefs. Derive each keyed token as `0x4B ‖ blake2b_224(instance ‖ typeId ‖ key)`, with an empty key for a head. Require the designated wallet input and verify the relink, node or tombstone, minimum deposits, bounded top-ups and wallet loss under ADR-0016 D11. A wallet address is the exact payer address of the call or publication, or the enterprise address of the caller's payment key on the selected network. No other address of that key belongs to the wallet. Check the target's address, policy and one instance/object token. For every owned spend, a destroy included (it has no continuation to check), decode the resolved spending input's inline datum with the method's anchored holding schema and require its instance to equal call.instance. Restrict references to pinned script holders (the contract's script at the contract address, the anchored output verifier at its enterprise address), the call's instance UTxO (the output at the contract address holding exactly one of the call's instance token; for an owned call this is the instance, not the spent object) and explicitly anchored foreign reads (none in M1: a method with reads is outside the verifiable set). Collateral inputs must all be at wallet addresses. The collateral return must go to a wallet address with no datum, datum hash or script. The collateral inputs must equal the return plus the total collateral for every unit, and the total collateral must be from 0 through 5,000,000 lovelace inclusive. Verify is_valid is true, execution budgets are nonnegative and within chain limits, and the fee is nonnegative, at most the user's maximum and at most the protocol-parameter-derived cap ⌊1.2 × (min_fee_a × (S + 200) + min_fee_b + E)⌋ + 500,000 lovelace, where S is the byte length of the received transaction and E = ⌈Σ mem × price_mem + Σ steps × price_step⌉ over its redeemers, computed exactly. The cap has no reference-script term. The 20% factor and the 500,000-lovelace margin cover Conway's fee for the scripts in spending and reference inputs (15 lovelace per byte, rising by a factor of 1.2 for each 25,600 bytes). M1 pins at most two such scripts, the contract's and the output verifier. With a contract script near the 16 KB transaction limit their fee is about 270,000 lovelace. In Dex-addLiquidity it is about 207,000 of the 513,750 fee, against a cap of 867,532. A change to the factor, the margin, the reference-script price or the number of pinned scripts must preserve that headroom.
4. Reject extra certificates, withdrawals, votes, proposals, donations, treasury values and auxiliary data. Permit only pinned verifier zero-withdrawals needed by the requested call, and registrations of the precise publication shapes below. Restrict mint policies and payment-key signers to those requested. Check that named co-signers are required by the body.
5. Select the redeemer pointer from the body's ledger order. For a spend, use the target input's index. Independently select the constructor alternative from the anchored method schema. Check the redeemer at that pointer against the constructor, typed arguments, caller, payment and offered assets. Reject extra redeemers. A creation spends its wallet seed, mints exactly one derived thread token, and spends no script input. A destroy burns exactly its target token with the expected burn redeemer. Ordinary actions cannot mint unless the anchored method permits it. For a permitted method mint, check ByAction names the target.
6. Check the one continuing output for a creation or non-destroying spend, its pinned script address, unique token, decodable inline state and no script reference. A destroy has no continuation. Check tagged wallet payouts against the target out-ref. For a method with sends, decode the pinned output verifier's OutputClaims redeemer (at the Reward pointer of its zero withdrawal). Refuse another shape, a malformed claim, any deposit claim, claims that do not name distinct outputs of the body in strictly increasing order, a claim whose `by` is not the target input the body spends, and a claimed output that also carries a payout tag. Reject unexpected wallet datums, script references and outputs using the wallet's payment key at addresses that are not wallet addresses (rule 3). The continuation and every output to a third party may carry a token of the contract's policy other than the thread token only when the method mints and the body mints that unit with a positive quantity. Wallet outputs are exempt: the bound on the caller's net loss already covers every unit, so a contract token the wallet holds may return in its change, such as the LP left after a partial removeLiquidity. Keep the wallet's change (wallet outputs neither tagged nor claimed) at the exact payer address. A tagged payout or claimed send at another address of the caller's key may also hold change the host merged into it: no value is lost, but that change loses the payer address's stake delegation. Bounding such outputs by the release would refuse honest sends that carry the wallet's minimum ADA, so protocol 1 accepts this; a client may show it (#153 SC5). Balance inputs, withdrawals and mint against outputs, fee and deposits for every unit. On chain a send to a wallet address is met by any output there that covers it, so every claimed wallet output counts as the script's release. Bound the caller's net loss (its inputs less its wallet outputs) by the agreed payment and fee and by the offered assets. For a creation, the continuation's lovelace (the creation deposit) is at most the call's pay.lovelace plus the description's x-joust.minAda, and that continuation lovelace takes the payment's place in the allowance. Third parties can receive only what the script releases (the target's value less the continuation's, plus mint) less tagged payouts and less the claimed wallet outputs: the release cannot go elsewhere while the wallet's own change stands in for a send. A call that sends tokens to the caller with the wallet's minimum ADA and pays released lovelace to others is therefore refused; such a send must carry its own minimum ADA (ADR-0013 §9.2).
7. For ordinary calls require an exclusive upper bound at most the **sent** now + ttlSlots. Require a lower bound at or after that now for methods using Ctx or time rules. For a keyed call instead check start within [now, now + maxStartSlots], a nonempty window, and end at most both start + ttlSlots and now + maxValiditySlots. Determine whether the keyed rule applies from the anchored contract and method.
8. Recompute the bounds, signers, plan and any predicted effects used in the signing decision from the received bytes. Compare a shown plan against the client's own. Classify calls locally regardless of describe's served flag.

For each top-level MerkleMap in the target state or owned value, require one zero withdrawal from the snapshot-anchored MapVerifier reward account (one withdrawal serves all fields). Resolve its redeemer by ledger reward-account order, including calls that also use the output verifier. Check the typed MapClaims, their proofs and the exact (contract policy, owner out-ref, map ordinal) keys. A creation uses its wallet seed, negative ordinals starting at −1 and the empty root. A spend uses its target input and ordinals starting at zero. Check that every stored map is in datum form with empty reads and writes. Its input root equals the old claim root and the continuation root equals the new claim root. A destroy only reads its maps. Apply the ordinary token, datum, value and spend rules to the same inputs and outputs.

Before signing a publication, the client must positively recognize exactly one of these shapes. Every shape allows only payer change besides the named output, and no mint, withdrawals, metadata, other certificates, reference inputs, collateral or redeemers (#153 F-C5). Account for the exact registration deposit in the balance.

- A referenceScript publication has one holder output at the anchored target, the anchored script language and hash, no datum or other assets, and lovelace from the client-computed minimum through that minimum plus 1,000,000 inclusive. The minimum is (160 + B) × coins_per_utxo_size, where B is the byte length of the holder output as encoded in the received body, never a re-encoding. It has no certificate.
- A stakeRegistration has no holder output and exactly one pure legacy registration (kind 0, deposit from the client's protocol parameters) or pure Conway registration (kind 7, stated deposit equal to that parameter) of the anchored credential. Reject combined registration/delegation certificates.
- A verifier publication has one referenceScript-shaped output at the anchored verifier enterprise address and exactly one pure registration for that same verifier.

Check absence independently for each component being added. Check script presence at the target and registration status where the node can answer. A duplicate registration rejection can establish credential status only. It cannot establish script publication.

## Errors and schemas (D9)

Each terminal error has a numeric JSON-RPC code, a diagnostic message, and data with kind, stable string code, message, sites and retryAtSlot. Sites are structured file, line, col, kind, in and what values from the message map. Empty sites is an array and an absent retry slot is null. Clients must branch on kind/code, never on diagnostic text.

| Kind | JSON-RPC code | Examples |
| --- | --- | --- |
| bad | −32602 | request, method, contract, busy, network, nowDecreased, nowImplausible, tooLarge, readLimit, stageMismatch, unknownReservation, conflict, notExpired, unbounded, restoreClosed, listingGone, operation |
| refusal | −32001 | require, runtime, pending, mapsStale, window, prerequisite, reconcile, deepRollback, expiredOnArrival, cooldown, needsDesignated, mayHaveLanded |
| unsupported | −32003 | protocol, runtimeVersion, outsideProject, views, params, keyed, maps, authorizedBy, unverifiable, read:&lt;kind or service.method&gt; |
| internal | −32603 (−32800 for cancelled) | readFailed, inconsistentRead, chainMoved, unbalanced, evaluator, tooDeep, cancelled, deadline, transport, internal, need |

The draft 2020-12 schemas describe message envelopes and method payloads. For successful forward results and reverse answers select the matching definition using the outstanding request. The read schema's per-kind definitions constrain the result. Ordering, cross-message checks, byte limits, hashes and ledger semantics are normative above. [Methods](dev-host-1/methods.schema.json), [results](dev-host-1/results.schema.json), [reads](dev-host-1/reads.schema.json), [errors](dev-host-1/error.schema.json).

## npm host

Build the project with its installed `joust build`. Set the working directory to the trusted project root and launch the project-local entry with the protocol environment allow-list.

```sh
node node_modules/@julclang/joust/bin/joust-host.js --stdio
```

The client checks the real paths of the package, the entry and the installed runtime before launching. At launch the entry reads its JavaScript closure (the files listed below under the two packages) into memory once, before any of it runs. Node module hooks then evaluate exactly those bytes in the main thread and in the engine worker, and refuse every other module. The host eagerly loads the verified engine and contracts at initialize. A module outside the project, or outside the closure, gives `unsupported/outsideProject`. A missing package or closure file gives `bad/stageMismatch`. The host refuses to start (exit status 2) under `NODE_OPTIONS` or a preload option (`--require`, `--import`, a loader), since such code would run outside the hashed closure under the same fingerprint. Editing, rebuilding or cleaning the project after launch leaves the session pinned to the bytes it loaded: the closure from launch, and the build and engine from initialize. Start a new host to use new files. Node reads the entry file itself before any hook exists, and the entry reads it again immediately, so a change to the entry in that window alone is not detected.

Set `build.snapshot` to the absolute project root. For the npm fingerprint there is one directory entry at ordinal 0. Hash the following files with SHA-256. Use each file's real path relative to the project root, which differs from these when a package is installed nested or linked.

- `build/joust/contracts.json`
- `node_modules/@julclang/joust-runtime/engine/joust-runtime.js`
- `node_modules/@julclang/joust-runtime/engine/joust-runtime.js.wasm`
- `node_modules/@julclang/joust-runtime/package.json`, `src/service.js`, `src/host-service.js` and `src/exnref.js` (the runtime's closure)
- `node_modules/@julclang/joust/package.json`, `bin/joust-host.js`, and `src/host/frames.js`, `guard.js`, `launch.js`, `resolve.js`, `transport.js` and `worker.js` (the host's closure)

Sort by UTF-8 relative path bytes using forward slashes. For each file append `<lowercase hex hash> 0 dir <path>\n` (one LF), then SHA-256 the concatenated UTF-8 lines. Send the lowercase hex digest in `build.digest`. The host returns it as `fingerprint`. A mismatch gives `bad/stageMismatch`. The host hashes the closure's bytes as read at launch, so a client that hashes the files later detects an edit made after launch as a mismatch.

`versions.build` comes from the contracts' common `built` field. `versions.engine.joust` identifies the installed runtime package, `image` identifies its Web Image, and `julc` identifies the engine's evaluator. Unknown versions are null. Incompatible packages or engine versions give `unsupported/runtimeVersion`. This includes an image that names a JOUST version other than the runtime package's. An image built without a release version names `dev`.

The npm host serves state lookup through `view` with `view: null`, plus `instances`, `objects` and `decode`. It omits the `views` capability and returns `unsupported/views` for a named view. Other capabilities and the signing, restore and settlement rules follow the JVM host. A worker evaluates locally and asks the client for call-scoped chain reads. The host has no wallet, persistent journal or provider credentials. The client persists the D8 high-water mark before handing out a body.

Some failures differ from the JVM host's:
- A trap inside the engine, such as CBOR nested past the Wasm stack, is answered as `internal/tooDeep` (or `internal/internal` for another trap). The host then exits, because the engine's in-memory session may be inconsistent. The JVM host answers `internal/tooDeep` and keeps serving.
- A transition that exhausts its execution budget gives `internal/evaluator`. The JVM host runs the contract's Java method, which has no budget.

## JVM conformance in M1

Run `./gradlew :julc-state-gradle-plugin:check :julc-state-host:check`. The plugin tests inject the plugin with
Gradle TestKit. Its `hostSpecTestRepository` task stages this build's jars, on-chain classifier, generated POMs and
Gradle module metadata under the plugin's build directory. Third-party dependencies resolve from the consumer's
repositories. The host's normal JUnit suite replays every plain and owned recording over the wire; the gate requires them to cover Counter's init, action and destroy, Bank's init and action, and Bank's owned init, action and destroy (it checks coverage, not a count, so a re-recorded flow of another length still passes). The plugin check also depends on it.

| ADR-0022 invariant | Executable coverage |
| --- | --- |
| I1 | `BootstrapTest` exercises a static initializer, saved Java stream, native fd 1, inherited child stdout and close-on-exec on Unix. The application probe prints and exits or halts during a request. |
| I2 | `TransportTest` checks frame caps, stalls, UTF-8, malformed envelopes and oversized results. `ReadsTest` checks chunked answers. `InspectionTest` checks sorted pages, forks, LRU and the listing limit. |
| I3 | `ReadsTest` exercises the closed backend and local evaluator. `ReplayTest` accepts only recorded read kinds. |
| I4 | `IntegrationTest.clockLimitsAndReset` exercises implausible and decreasing slots, reset and two TTLs. Its timed Escrow case varies the genesis across a contract deadline while holding the request and tip fixed. `ReservationsTest` drives release by tip. |
| I5 | `M1bTest.quorumCountsCallerAndEventsSurviveDestroy` checks the final required signers with no Ctx, with the caller alone and with duplicate co-signers. `M1bTest.signedByCannotBeSatisfiedByNamingTheOwnerAsACosigner` checks the caller requirement. The runtime's `WalletAccountTest` covers address-only accounts. |
| I6 | `ReplayTest.plainAndOwnedPrepares` reproduces every plain and owned M0 call body and hash from ordered answers in fresh sessions; together they cover every plain and owned call kind of Counter and Bank. |
| I7 | `LaunchTest` and `ForkedSessionTest` check order, overlapping resources, runtime identity and tampering. `HostSpecConsumerTest` independently copies and verifies the plugin manifest and reads a lazy contract resource and a sibling anchor after rebuilds and cleanup. |
| I8 | `TransportTest` checks generation IDs and late answers. `ReadsTest` counts chunks and bytes against the per-call caps. |
| I9 | `ReplayTest` decodes every prepared body and checks its bounds against the response and the sent ceiling. `M1bTest` checks the publication bounds, script, certificate, deposit and balance for every verifier-table row. `HostVectorsTest` checks the bounded counterpart of M0 Dex 0003. |
| I10 | `ForkedSessionTest` prepares with an empty working directory, home and temp. Linux CI adds `-PjoustHostTrace` for network-namespace isolation and syscall tracing. `IntegrationTest` records worker I/O with JFR. The traced forked session also replays every recorded `fixtures/host` vector, including the Token map calls with their cold history rebuilds (`Token-mint`, `Token-transfer`, `Token-mint-again`). `DevHostSeamsTest` and `ReadsTest` exercise denial of submission, verifier publication, waits and unlisted backend methods. The traced session serves every plain recording (the plain gate's coverage), every `fixtures/host` vector above (publication, owned, quorum, keyed and map calls; only the superseded M1b maps refusal is skipped), a refusal, a failed read and a cancellation, and refuses any socket, process, or file created, opened for writing, removed or renamed after the first stdin read. Two JVM behaviours are outside the host's code and recorded as exceptions: before serving, the host loads the JDK's network library, whose initialization opens and closes an IPv4 and an IPv6 probe socket (so a client's sandbox sees them at startup); and the JVM keeps its perf-data file (`/tmp/hsperfdata_<user>/<pid>`, created before serving and removed at exit), which D2's fixed options leave enabled. |
| I11 | `IntegrationTest.missingReferenceAndProviderFailureStopBeforePublication` checks that failed and absent answers take distinct paths. |
| I12 | `LaunchTest` checks the mainnet opt-in. `IntegrationTest` and `ReservationsTest` reject cross-network prepares and restored bodies. |
| I13 | `ReservationsTest` and `IntegrationTest` cover spending and collateral, settlement margins, tombstones, deep rollback, rewind fences and atomic restart batches. `M1bTest` checks shallow and final registration knowledge and rewound reset. The client journal and its high-water mark are client responsibilities. |
| I14 | `TransportTest` races cancellation and deadlines with reverse reads and a CPU-bound result. It checks busy while draining, failure to deliver, one terminal reply and no late commit. |
| I15 | `ClientConformanceTest` rebuilds every positive through the host, including both spend pointers and the D6 permutation. `VerifierVectorsTest` checks every rejection, its body hash, and acceptance with its target rule disabled (both rules for the one body that two rules refuse). `RecordedSessionsTest` and `FakeHostTest` exercise both peers. The JS wallet runs the shared signing cases. [Client kit, rule codes and adversarial coverage](dev-host-1-conformance.md). |
