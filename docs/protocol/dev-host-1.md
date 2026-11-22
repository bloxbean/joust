# JOUST dev host protocol 1

A session runs one trusted project's generated clients. The client supplies the chain and slot clock and owns signing, persistence and submission. The JVM host prepares unsigned transactions in memory. This document specifies the M1a wire interface of ADR-0022.

## Launch and framing (D1–D4, D10)

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

Hash the concatenation with SHA-256. The host computes this fingerprint from its actual class path before project discovery. A relative path containing a control character (U+0000 to U+001F or U+007F) fails initialization, so no two directory contents give the same lines. A mismatch, a path outside the snapshot, or a jar manifest with a nonempty Class-Path fails initialization. The runtime must match the host's build version. The response gives the chosen protocol, echoed network, optional capabilities (empty in M1a), read kinds, fingerprint, versions and contract list. Gradle build versions are null. Execution version fields use null where the installed component supplies no version. Each contract names the actual spend/mint and applicable stake script hashes. A parameterized contract reports only its unapplied template hash and `unsupported:"params"`. It has no deployment hash without parameters.

## Forward methods (D5, D7, D16)

| Method | Parameters | Successful result |
| --- | --- | --- |
| describe | contract (fully qualified name) | description, messages (null for Java), calls with method, kind and either served:true or an unsupported code |
| prepare | now, ttlSlots, call | prepared with reservation, cbor, txHash, validFrom, validTo, signers, plan and the instance ID for a creation |
| settled | reservation, txHash, status, tip, inclusion slot for confirmed | state after applying the event and depth thresholds |
| restore | transactions, complete | reservations, an array of IDs in transaction order |
| reset | now, optional clock, optional rewound:{tip} | empty object |
| shutdown | empty or omitted | empty object, then stop accepting calls |
| exit | notification, empty or omitted | process exits |

The nested call accepts contract, kind, method, instance, object, args, pay, address and cosigners. Arguments use the description's JSON encoding. A missing pay means zero. Plain calls allow an empty co-signer list. Obtain wallet inputs through the caller address's reverse read. Caller-supplied walletUtxos or other extra fields are errors. Serve plain init, action and destroy. Refuse owned and quorum calls with `m1b`, keyed calls with `keyed`, map contracts with `maps`, parameterized contracts with `params`, authorization with `authorizedBy`, and unverifiable calls including batched, moves, admissions and deposits with `unverifiable`. Deployment, prerequisites, publish, view, instances, objects, decode and registered answer `unsupported/notYet` in M1a. Predicted effects start in M1b.

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

UTxO fields follow ADR-0015 snapshot encoding (tx_hash, output_index, address, amount, data_hash, inline_datum, reference_script_hash). Quantities are integral. Keep provider ordering across chunks. Report one chain point for the entire address set. The host checks addresses, references, duplicates and script hashes. For an inline datum an omitted redundant data_hash is equivalent to the hash computed from its exact bytes. Conflicting values fail with inconsistentRead. Chain points cannot go backwards within one call. Only the address set establishes liveness. The host memoizes positive and negative outcomes within one execution, filters by asset and pages locally. Local JuLC evaluation uses the session slot configuration and the read protocol parameters. There is no evaluation or submission RPC.

## Reservation journal (D8)

Reserve all spending and collateral inputs of the final body. A successful write of the complete prepared response is the commit point. Cancellation, failure, an oversized response and a failed write reserve nothing. A body with validTo at or below the greatest tip seen during the call is expiredOnArrival. A client declining a result settles it discarded only if it never handed it out for signing/submission or export.

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

Ordinary reset keeps every reservation. Before a rewind the client **must persist** an outstanding reconciliation. Send reset with rewound:{tip}. Discarded stays final. Re-arm expired or removed inclusions with future validTo as prepared. A retained final inclusion returns to shallow and holds collateral through its depth margin. Stop advancing thresholds while fenced. All builds give reconcile until a restore batch completes.

After restart use initialize with restoring:true. Restore in batches of signed or unsigned transaction CBOR. Send state prepared, or shallow with its inclusion slot. Re-import each handed-out body whose validTo is above the restored tip, all retained inclusions within their margin **even if validTo has passed**, and unsigned bodies still awaiting approval (or invalidate those approvals). A missing upper bound gives unbounded. Repeated identical entries retain their IDs. Conflicting states or slots for a live body give conflict. Complete with complete:true, including an empty final batch if needed. This closes restore until a new rewind. Clear the persisted reconciliation only after the successful final response.

Before signing for submission, exporting or submitting, the client must persist per chain the maximum validTo + settlementSlots of every handed-out body. Read validTo from the body itself. Keep this high-water mark independently of the transaction journal. After losing history do not complete restore until an observed tip strictly passes the persisted bound. Smaller limits in a later session cannot lower it.

## Client signing rules (D13, normative)

Before signing **or exporting**, the client must perform all applicable checks below. A host result alone never authorizes a signature.

1. Extract the exact encoded transaction-body slice from cbor and hash it. Refuse a txHash mismatch. Sign that hash and preserve those body bytes when adding witnesses.
2. Independently read the descriptions and compiled validator resources, including sibling jars, from the immutable snapshot whose digest the client verified. Derive the contract hashes, addresses, network and policies. Anchor shared verifiers in the snapshot's julc-state-verifier jar. Refuse a conflicting fingerprint, contract entry or deployment. Determine the method and its verifiability independently from the description. Refuse any call outside the verifiable set.
3. Resolve every spending, reference and collateral input against the bound node. Check the network ID and every output address. Permit wallet inputs and exactly the requested script target for a spend. Check its address, policy and one instance/object token. Restrict references to pinned script holders, the target and explicitly anchored foreign reads. Verify collateral ownership, return address and total at risk. Verify is_valid is true, execution budgets are within chain limits, and the fee satisfies the protocol-parameter-derived cap and the user's maximum.
4. Reject extra certificates, withdrawals, votes, proposals, donations, treasury values and auxiliary data. Permit only pinned verifier zero-withdrawals needed by the requested call, and registrations of the precise publication shapes below. Restrict mint policies and payment-key signers to those requested. Check that named co-signers are required by the body.
5. Select the redeemer pointer from the body's ledger order. For a spend, use the target input's index. Independently select the constructor alternative from the anchored method schema. Check the redeemer at that pointer against the constructor, typed arguments, caller, payment and offered assets. Reject extra redeemers. A creation spends its wallet seed, mints exactly one derived thread token, and spends no script input. A destroy burns exactly its target token with the expected burn redeemer. Ordinary actions cannot mint unless the anchored method permits it. For a permitted method mint, check ByAction names the target.
6. Check the one continuing output for a creation or non-destroying spend, its pinned script address, unique token and decodable inline state. A destroy has no continuation. Check tagged wallet payouts against the target out-ref. Reject unexpected wallet datums, script references and outputs using the wallet's key at unrelated addresses. Balance inputs, withdrawals and mint against outputs, fee and deposits for every unit. Bound the caller's net loss by the agreed payment and fee, including the bounded creation deposit. Third parties can receive only what the script releases after accounting for tagged wallet payouts.
7. Require an exclusive upper bound at most the **sent** now + ttlSlots. Require a lower bound at or after that now for methods using Ctx or time rules. A keyed client in a later milestone must check start within [now, now + maxStartSlots], a nonempty window, and end at most both start + ttlSlots and now + maxValiditySlots. Determine whether the keyed rule applies from the anchored contract and method.
8. Recompute the bounds, signers, plan and any predicted effects used in the signing decision from the received bytes. Compare a shown plan against the client's own. Classify calls locally regardless of describe's served flag.

Publication is reserved for a later milestone. Before signing one, the client must positively recognize exactly one of these shapes. Every shape allows only payer change besides the named output, no mint, withdrawals, metadata or other certificates. Account for the exact registration deposit in the balance.

- A referenceScript publication has one holder output at the anchored target, the anchored script language and hash, no datum or other assets, and lovelace from the client-computed minimum through that minimum plus 1,000,000 inclusive. It has no certificate.
- A stakeRegistration has no holder output and exactly one pure legacy registration (kind 0, deposit from the client's protocol parameters) or pure Conway registration (kind 7, stated deposit equal to that parameter) of the anchored credential. Reject combined registration/delegation certificates.
- A verifier publication has one referenceScript-shaped output at the anchored verifier enterprise address and exactly one pure registration for that same verifier.

Check absence independently for each component being added. Check script presence at the target and registration status where the node can answer. A duplicate registration rejection can establish credential status only. It cannot establish script publication.

## Errors and schemas (D9)

Each terminal error has a numeric JSON-RPC code, a diagnostic message, and data with kind, stable string code, message, sites and retryAtSlot. Sites are structured file, line, col, kind, in and what values from the message map. Empty sites is an array and an absent retry slot is null. Clients must branch on kind/code, never on diagnostic text.

| Kind | JSON-RPC code | Examples |
| --- | --- | --- |
| bad | −32602 | request, method, contract, busy, network, nowDecreased, nowImplausible, tooLarge, readLimit, stageMismatch, unknownReservation, conflict, notExpired, unbounded, restoreClosed |
| refusal | −32001 | require, runtime, pending, window, prerequisite, reconcile, deepRollback, expiredOnArrival, cooldown |
| unsupported | −32003 | protocol, runtimeVersion, params, keyed, maps, m1b, authorizedBy, unverifiable, notYet, read:&lt;kind or service.method&gt; |
| internal | −32603 (−32800 for cancelled) | readFailed, inconsistentRead, chainMoved, evaluator, tooDeep, cancelled, deadline, transport, internal, need |

The draft 2020-12 schemas describe message envelopes and method payloads. For successful forward results and reverse answers select the matching definition using the outstanding request. The read schema's per-kind definitions constrain the result. Ordering, cross-message checks, byte limits, hashes and ledger semantics are normative above. [Methods](dev-host-1/methods.schema.json), [results](dev-host-1/results.schema.json), [reads](dev-host-1/reads.schema.json), [errors](dev-host-1/error.schema.json).
