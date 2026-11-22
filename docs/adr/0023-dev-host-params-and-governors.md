# ADR-0023: The dev host serves `@Params` contracts and `@AuthorizedBy` calls (ADR-0022 Q7)

**Status:** Proposed (r15). Design only.
**Date:** 2026-11-21
**Amends:** ADR-0022 (a dev host): its Q7 ("which milestone serves governors, and how the governor's applied script,
reward address, redeemer and signers are pinned per deployment"), its D16 rows for `@AuthorizedBy` and `@Params`, its
R3, its D3/D4 (npm fingerprint and the `initialize` protocol list: this ADR is protocol 2), D8 (reservation,
operation identity and restore), D11 (operation record), D13 (including rule 3 for every `@Quorum` call) and D14
where stated. Everything else in ADR-0022 holds.
**Builds on:** ADR-0009 D3 (`@AuthorizedBy`, the `Approvals` binding, the governor's obligations), ADR-0013 phase 3
(`@Params` in the prepare service, R3), ADR-0015 M3 (governors from a page, `validators.json`).
**Decided by the maintainer (2026-11-21):** Q7 gets an ADR before any implementation.
**Facts** are pinned per row: `main` `d529bfd0` for the runtime, JS, processor and examples; PR #156's head `4335773d`
(ADR-0022 M3a) for the hosts. `DescribedContract.java` and `StateContractHandle.java` differ between the two pins; the
rows cite the pin they were read at.
**Risk:** high. Nothing here changes a validator or what a page verifier accepts; descriptions gain only an additive
params `format` marker (D2a, from r10). But it lets the host
build transactions that **spend a governor's approval** and that **name a script by parameters the client supplies**,
so a client that anchors either wrongly signs a transaction for another script or another approval. D3 and D5 make
the client's checks normative, down to the bytes.

## 1. Context

ADR-0022's protocol 1 refuses two families of calls:
- **`@Params` contracts** (`unsupported/params`): inherited from the prepare service (ADR-0022 R3, F2), not a reason
  of its own.
- **`@AuthorizedBy` calls** (`unsupported/authorizedBy`): ADR-0022 Q7 left open how the governor is pinned.

The two are one problem for the dev host. Both hosts must serve the same calls (ADR-0022 D1), and the engine's
described dispatcher accepts only **`@Params` credentials** (F1), so the dev host serves `@AuthorizedBy` only where the
credential is a `@Params` field, and no governor call can be served before parameterized contracts are. A
parameterized contract's script hash, address and policy are not in the build: they come from applying the params
(F4). The question is the same for both: **what exact bytes does the client anchor on?**

### 1.1 Facts

| # | Fact | Where |
|---|---|---|
| F1 | The **described dispatcher** (the engine's path, npm host) supports `@AuthorizedBy` only for `@Params` credentials (`"params.<field>"`); ADR-0009 D3 and the annotation also allow state and owned fields, which generated clients may serve. A `PubKeyCredential` must be the caller or a co-signer; if it is the caller, the caller becomes a required signer. A `ScriptCredential` needs a `Governor(script, signers)`: the applied governor script must hash to the credential, one governor approves one credential, and the call withdraws zero from the governor's reward address with `Approvals[Approval(policy, target, action)]`, `action` being the method's redeemer constructor index | `DescribedContract.java:600-641` (`:606`, `:169`, `:630`) at `d529bfd0` |
| F2 | The governor script is **attached to the transaction** (a witness), and its `signers` become required signers; `Call.applyAuthorization` adds the zero withdrawal and the script | `Authorization.java:8-27`; `Call.java:129-134` at `d529bfd0` |
| F3 | The described dispatcher refuses `@AuthorizedBy` on a keyed call | `DescribedContract.java:185` at `d529bfd0` |
| F4 | A contract's `@Params` record is encoded from `x-joust.params` (with a fidelity check) and applied as **one** Data argument; a plain JuLC validator's `@Param` fields are applied **one Data argument each, in order**. Application is JuLC's `Program.applyParams`. An applied contract script over 15,000 bytes is refused. The contract's address is a script base address (payment and stake the script) when `stakeSelf`, else an enterprise address | `DescribedContract.java:103-106`; `StateContractHandle.java:149-153`, `:177-191`; `ValidatorScripts.java:91-104` at `d529bfd0` |
| F5 | JuLC encodes Data as plutus-core does: non-empty lists and constructor fields are **indefinite** arrays (`9f … ff`), empty ones `80`; byte strings over 64 bytes are indefinite strings of 64-byte chunks. The same decoded value in another CBOR form gives another applied hash (the Codex review on #157 reproduced four policies from two Treasury encodings) | JuLC `julc-core/src/main/java/org/julclang/core/cbor/PlutusDataCborEncoder.java:31-35`, `:102-112`, `:161-175` at tag `v0.1.0-pre21-rc1` (re-verified at the JuLC release JOUST then pins, an M4a entry gate) |
| F6 | A script's `cborHex` in JOUST's artifacts is a CBOR byte string **of** a CBOR byte string of the flat program. The ledger's hash is `blake2b_224(0x03 ‖ S)`, where `S` is the inner CBOR byte string (the outer wrapper removed); hashing the outer or the bare flat bytes gives other hashes (reproduced on `fixtures/m3/treasury-governor/017-freeze.json`'s Guardian) | #157 review X1; `fixtures/m3/treasury-governor/017-freeze.json` (fixtures at `d529bfd0`; the JuLC 0.1.0-pre22 / CCL 0.8.0-pre8 re-recording in #148 moves Guardian's hash and the spend redeemer's index, so the vectors are regenerated at M4a's entry) |
| F7 | Governors are plain JuLC validators. Gradle writes them as **generated** resources `META-INF/plutus/<SimpleName>.plutus.json` (`cborHex`, empty `hash` when parameterized, `params` such as `"owner:byte[]"`; reproduce with `./gradlew :examples:treasury:classes`), resolved by `ValidatorScripts` beside the defining class, detecting ambiguity; the npm toolchain writes `build/joust/validators.json` (`[{name, purpose, script, scriptHash, built, params}]`, with the package) | `ValidatorScripts.java:27-54`, `:138-155`; `js/joust/src/build.js:7`, `:165-176` at `d529bfd0` |
| F8 | The example: `Treasury`'s `@Params record Config(Credential guardian, BigInteger maxPayout)`; `freeze` is `@AuthorizedBy("params.guardian")` with no `Ctx`, `@SignedBy` or `@Quorum`. `Guardian` is a `@WithdrawValidator` with `@Param byte[] owner`, approving when the purpose is Rewarding and `owner` signed. The recorded `freeze` has a V3 governor witness, a zero withdrawal from `f0‖hash`, and redeemers at `(spend,1)` and `(reward,0)` | `Treasury.java:31`, `:73`; `Guardian.java:17-26`; `fixtures/m3/treasury-governor/017-freeze.json` (fixtures at `d529bfd0`; the JuLC 0.1.0-pre22 / CCL 0.8.0-pre8 re-recording in #148 moves Guardian's hash and the spend redeemer's index, so the vectors are regenerated at M4a's entry) |
| F9 | A body's required signers: the caller's key **only when** the method uses `Ctx` or `@SignedBy`; then every extra signer (co-signers, governor signers), deduplicated. The payer still witnesses its own inputs | `StateInstance.java:199-207`; `DescribedContract.java:310-313` at `d529bfd0` |
| F10 | JOUST's page verifier, for a governor call: verifiable only when the credential is in the deployment's params; exactly one script credential; instance actions only; the governor's hash equals the params' credential; exactly one Rewarding redeemer **at the governor's withdrawal index**, equal to `Approvals[Approval(policy, instance, tag)]`. It does **not** examine a Rewarding redeemer at another reward index | `verify.js:124-192`, `:485-509`, `:515-536` at `d529bfd0` |
| F11 | The page's governor registration check: one pure registration of exactly the governor's script credential, the protocol deposit, wallet-only inputs and outputs, no withdrawals, mint or redeemers; it counts Plutus witness scripts and **allows an absent TTL** | `governors.js:56-114`; `ledger.js:101` at `d529bfd0` |
| F12 | Generated clients take the params record (`deploy(runtime, Config, …)`, `at(runtime, Config, instanceId)`) and an `Authorization` argument for `@AuthorizedBy` methods | `ClientGenerator.java:99-106` (`contract(runtime, P params)`, `policyIdFor`), `:120-121` (`deploy`), `:179` (`at`), `:731`, `:758` (`Authorization`); `TreasuryClient` (generated) |
| F13 | The JVM host's adapter refuses `@Params`, looks contracts up without params, and accepts extra accounts only for `@Quorum`; `Contracts.code` returns the contract-wide `params` before any method's `authorizedBy`, so Treasury's `freeze` reports `params` today | `ServedContract.java:69-70`, `:117`, `:147-149`, `:258`; `Contracts.java:37-42`, `:78`, `:94` at `4335773d` |
| F14 | The npm host fingerprints and reads `contracts.json` and the two engine files, not `validators.json`; at the final M3b pin it also fingerprints the **launch closure** (the host's JavaScript files and package manifests captured at launch), all as lines `<sha256> 0 dir <path>` sorted by the paths' bytes | `js/joust/src/host/launch.js:32-51` at `4335773d`; `:28-38` at `2ffaf41b` |
| F15 | ADR-0022 D8 excludes live out-refs across the whole session; ADR-0022 D11's persisted keyed operation is `{designated, contract, method, instance, key, attempts}`, with no policy | `Reservations.java` (`prepare`, `avoids`); `KeyedOperation.java:8-26`, `:40-45` at `4335773d` |
| F16 | ADR-0009 D3 refuses a governor equal to the contract's own script or either shared verifier, and allows native-script governors; no runtime path builds a native governor (F2) | ADR-0009 `:77-100` |
| F17 | The schema codec sorts a typed map's entries by their keys' CBOR in RFC 8949 length-first order and refuses duplicates; JuLC's encoder and the JVM adapter's JSON values keep the order given. Reversing two map entries changes the applied hash (#157 review X12) | `SchemaCodec.java:118-132` at `d529bfd0` |
| F18 | The output verifier's Rewarding redeemer is `OutputClaims(claims, deposits)`, the map verifier's `MapClaims(claims)`; the builders omit a verifier's withdrawal when its claim list is empty | `julc-state-api/.../OutputClaims.java:12`, `.../mpf/MapClaims.java:7`; `AssetTx.java:258-262`; `Compose.java:210-223` at `d529bfd0` |
| F19 | Protocol 1's result schemas are **closed**: `initialize`, its `contracts` items, `describe`, its `calls` items and `deployment` all set `additionalProperties: false`, and `initialize.protocol` is `const 1`. Both hosts share one `Session`, which accepts any list of integer protocol versions containing 1 and answers protocol 1 (`no common protocol version` otherwise): offering `[1, 2]` to an M1–M3 host selects 1 | `docs/protocol/dev-host-1/results.schema.json`; `Session.java:95-104`, `:120`; `EngineSession.java:34` at `4335773d` |
| F20 | Restore recognizes a keyed body by its **keyed tokens** (29-byte names beginning `0x4B`, held at an output whose payment script hash is their policy, or minted) and refuses an entry without a matching `operation` only when those tokens are under a **catalog contract's hash**; for a `@Params` contract the catalog holds the unapplied template hash, so an applied policy escapes that guard | `Reservations.java:14-33` (`Body.keyedTokens`), `:261-269` at `4335773d` |
| F21 | The npm build writes `validators.json` (and each `<name>.plutus`) **only when** the build has validators, and never removes an earlier one: a rebuild that drops the last validator leaves the old manifest in place. It clears only `generated/`. `joust.lock.json` **is** rewritten by every successful build, and its `validators` map (`"<package>.<name>"` → `{purpose, scriptHash}`) is present only when the build has validators | `js/joust/src/build.js:115`, `:164-177` at `d529bfd0` and `4335773d` |
| F22 | The two codecs differ on integers and limits: `JsonValues` (JVM adapter) accepts a decimal string of 1–200 digits or a JSON integer with \|n\| ≤ 2^53, and caps collections at 1,024 items, nesting at 32 and byte strings at 16,384 bytes; `SchemaCodec` (engine) accepts a decimal string of any length and any JSON integer, with no collection limit | `JsonValues.java:37-38`, `:68`, `:90`, `:97`, `:149-157` at `4335773d`; `SchemaCodec.java:170-173` at `4335773d` |

## 2. Threat model and trust assumptions

ADR-0022 §2 holds: the host is no more trusted than the prepare service; the client checks every transaction before
signing (D13). New here:

- **T12. Wrong parameters, right shape.** The host builds a perfectly shaped call to a *differently parameterized*
  contract (another guardian, another limit). Only a client that **derives the applied hash itself, from bytes it
  encoded itself** (D3), sees it. Two encodings of the same values are two contracts (F5).
- **T13. Borrowed or widened approval.** A governor approval for another instance, action or contract; extra
  approvals in the list; approval data placed at another reward pointer (F10).
- **T14. A governor that approves everything:** the contract's own script or a shared verifier as governor (F16).
- **T15. Extra signers:** governor signers or co-signers smuggling a required signer the user does not expect.
- **T16. Wrong governor artifact:** two validators with one simple name, an npm manifest changed after
  `initialize`, or a stale manifest a rebuild left behind (F7, F14, F21).
- **T17. A well-shaped approval that cannot execute** (X21): signers that pass a set-equality check but are not
  eligible (an outsider in a quorum, a signer other than the one a governor's rules require). The ledger rejects it in
  phase 2 and the submitter forfeits the collateral. ADR-0022 D13 bounds that collateral but does not evaluate scripts;
  D5 counts quorum eligibility from the client's own reads, and Q6 records the open question for arbitrary governors.
- **T18. A journal that hides a keyed operation** (X22, X23): a restore entry whose keyed operation record is missing,
  relabelled, or made for a template the project has since rebuilt.

**Accepted liveness limit** (Y12): Conway requires a withdrawal to equal the reward account's whole balance, and
anyone can credit a registered account (a proposal's deposit return, a treasury withdrawal naming it). A governor
whose account holds a balance makes every zero withdrawal fail at the ledger, so its calls fail (closed) until the
governor's owner sweeps the account outside the dev host. ADR-0022's shared verifiers share this property.

**Trust assumption (unchanged in kind):** a governor is project code, like the contract. JOUST cannot verify that it
lists only the approvals its rules grant or refuses certificates (ADR-0009's obligations); the client shows which
governor, by qualified name and hash, authorises the call.

## 3. Decision

### D1. Scope, protocol 2 and capabilities

**Everything in this ADR is protocol 2** (X20). Protocol 1's result schemas are closed (F19), so a new field in
`initialize`, `describe` or `deployment` would make a schema-validating M1–M3 client refuse the whole session, even
for contracts it could use. Protocol 2 is protocol 1 plus the requests, fields, codes and answers this ADR adds, with
its own schemas (`docs/protocol/dev-host-2/`, protocol 1's with those additions):
- A client that supports this ADR offers `protocol: [1, 2]`. An M1–M3 host selects 1 from that list (F19); a host
  that ships this ADR selects 2. A client that offers `[1]` gets protocol 1.
- **In a protocol-1 session a host that ships this ADR answers exactly as an M3 host does** (I21): every reply
  validates against the unmodified `dev-host-1` result schemas, `initialize` advertises protocol 1's capabilities only
  (no `params`, `authorizedBy:*` or `fingerprint:2`), a parameterized contract is `unsupported:"params"`, `describe`
  adds nothing, and a request carrying a field this ADR adds gets the M3 host's answer **with its per-method error
  precedence** (X32, X38). Protocol-1 sessions run M3's request validation unchanged, and its order differs by
  method at `4335773d`: `Session.query` (`deployment`, `view`, `instances`, `objects`) checks the contract's support
  first, so Treasury's `deployment` with `params` is `unsupported/params`; `prepare` checks the call's field list
  first and `decode` its fields before the contract lookup, so Treasury's `prepare` with `call.params` and `decode`
  with `params` are `bad/request`. The oracle is the M3 host itself, compared field by field (X39): `fingerprint` and
  `versions` are checked against each launch independently (ADR-0022 D4: they must name the actual snapshot and
  runtime, so they differ between releases), and every other field, the capability list and every error code must
  equal the pinned M3 host's under identical project and chain fixtures. **The pinned M3 host is the final M3b host**
  (X46), with its `dev-host-1` schemas, recorded at M4a's entry: M3b adds `maps` to `capabilities` and
  `assetHistory`, `redeemers` and `datum` to `reads` for map contracts, so the rules above cite `4335773d` (M3a) for
  the precedence they quote, and M4a re-verifies that precedence at the M3b pin.
- The alternative, a client opt-in field in `initialize`, fails for the same reason as `fingerprintVersion` (D4): M1–M3
  hosts refuse an unknown `initialize` field, so the client could not offer it without knowing the host's version.
  The protocol list is the negotiation ADR-0022 already defines.

Protocol 2 serves, on both hosts, behind capabilities in `initialize`:
- **`params`** (M4a): `@Params` contracts, for every call kind ADR-0022 serves for unparameterized ones.
- **`authorizedBy:key`** (M4b) and **`authorizedBy:script`** (M4c): `@AuthorizedBy` **instance actions** (`action`,
  `destroy`) whose credential is a **`@Params` field** holding a `PubKeyCredential` (key) or a `ScriptCredential` of a
  **Plutus V3 withdraw validator** in the snapshot (script).

Still `unsupported`, code `authorizedBy`, with the reason in the message: credentials in state or owned fields (F1),
native-script governors (F16), `@AuthorizedBy` on owned, creation or keyed calls (F3, F10), and a credential branch
whose capability the host lacks. Capability strings are distinct so a client sees exactly what a host serves (D7).

### D2. Params travel with every request that names a parameterized contract

`deployment`, `prerequisites`, `publish`, `prepare`'s call, `view`, `instances`, `objects` and `decode` take
**`params`**: a JSON value of `x-joust.params` in **D2a's input domain**. Absent `params` for a parameterized contract
is `unsupported`, code `params` (protocol 1's answer); present but outside the domain or not encoding exactly is
`bad`, code `params`.
- **No session state:** the session never remembers params; two values are two deployments.
- **Identity:** listings, caches, decoded views and **MerkleMap mirrors, their per-reservation overlays and the
  `mapsStale` scope** (Y10) are keyed by the **applied policy**; a restored non-keyed map transaction carries no
  `params`, so its `mapsStale` scope is the policy its body spends or mints under. **Reservations are not:**
  ADR-0022 D8's out-ref exclusion stays session-wide, so two parameterizations can never reserve the same payer
  input or collateral (F15). ADR-0022 D11's persisted `operation` gains, for a parameterized contract, **`policy`**
  (the applied policy) and **`template`** (the unapplied template hash the params were applied to, X23).
- **Operation identity** (X42): for a parameterized contract, ADR-0022 D11's identity `{designated, contract,
  method, instance, key}` becomes **`{designated, policy, method, instance, key}`**; `contract` stays a label. Two
  operations on different instances or keys of one deployment share a policy and are different operations, as
  under ADR-0022. Attempt histories are compared per identity, never per policy.
- **One template predicate** (X42), **R(op, params)**: the snapshot holds a contract named `operation.contract`
  whose template hashes to `operation.template`, and applying `params` to that template gives `operation.policy`.
  A **retry** requires R with its request's params (else `bad`, code `operation`); a **restore** uses R to choose
  between live and retired (below). A stale `template` field with an otherwise matching policy fails R in both.
- **Restore** (ADR-0022 D8, whose entry is `{cbor, state, slot?, operation?}`; X19): the deployment binding applies
  **only to keyed operations**. An entry whose `operation` names a parameterized contract (ADR-0022 still requires
  that record for every pending keyed transaction) also carries **`params`**, persisted with it. The body proves
  the policy; the catalog name is a label the host cannot verify historically (X34). The host requires the body to
  spend or mint keyed tokens under `operation.policy` and to spend the designated input (else `bad`, code
  `operation`, as ADR-0022 refuses a record that does not match its body); then:
  - **R holds** (re-derivable): the operation is live and retryable with the same params. Two catalog names whose
    templates are the same bytes give the same policy, so a name-only change between them, with a matching
    `template`, is the same operation.
  - **R fails** (X23, X34: the template was rebuilt, the name now resolves to another template, the `template` field
    is stale, or the params no longer give the policy): the host cannot tell a rebuild from a mislabelled record and
    does not try.
  - **A record without `policy`** (X47: any protocol-1 record, or one written before its contract gained `@Params`)
    is recognized by its shape, not by what its contract name resolves to today. If the named contract is still
    unparameterized and its catalog hash is the policy the body's keyed tokens are under, ADR-0022's existing check
    applies unchanged. Otherwise (the contract now takes params, was renamed or was rebuilt) the host cannot
    re-derive the deployment and the entry is **retired**, as below, with the body's keyed tokens as its object; it
    is never refused for lacking the new fields.
    It restores the entry as a **retired operation**: its out-refs and keyed tokens stay reserved, its attempts
    history is kept, any retry of it or new keyed operation on its keyed object is `bad`, code `operation` ("not
    re-derivable from this build"), and it is **never discarded** (it may have been handed out): it ends only as
    ADR-0022 D8 ends a handed-out entry, by final inclusion or by expiry established at a tip beyond `validTo +
    settlementSlots` (X33). The restore completes, so the fence lifts; the original transaction can still land and
    settles as usual. The cost is liveness until that expiry, after which the client may start a **new** operation
    on the object.
- **Keyed bodies without a record** (X22, X27): recognizing a keyed operation cannot depend on the optional record,
  nor on the catalog's hashes, which are templates for parameterized contracts (F20); and a 29-byte `0x4B` name is
  reserved only in keyed contracts (ADR-0016), so a parameterized **non-keyed** contract may mint one legitimately.
  The host therefore never refuses such an entry: an entry **without** `operation` is restored by ADR-0022 D8's
  path, which holds its out-refs **and** every keyed token in its body (F20's structural set; a live entry's keyed
  tokens already block any keyed operation on them, `Reservations.prepare` at `4335773d`).
- **Every restored body with keyed tokens is possibly handed out** (X33, X40). The host has no independent
  evidence that a journaled body never left the client: a record may be missing, or its `attempts` may omit the
  body's own hash (`Reservations.restore` accepts that omission at `4335773d`, and `settled` then permits
  `discarded`). So in a protocol-2 session, for **every** restored entry whose body holds keyed tokens (F20's
  structural set), with or without a record, whatever its `attempts` say and whatever the policy, `discarded` is
  refused (`conflict`, as for a handed-out keyed attempt), and the entry ends only by final inclusion or by expiry
  at a tip beyond `validTo + settlementSlots`, as D8 ends a handed-out entry. A restored body that was in fact
  never exported costs liveness on its object until that expiry (a keyed body's window is bounded by D7's keyed
  rule). Entries prepared **within** the running session keep ADR-0022's settlement rules unchanged; the rule
  covers only restored entries, whose history the host did not see. A stripped keyed record
  thus loses only its attempt history, and nothing can retry or start another operation on that object while the
  old body can still land; an honest non-keyed body with such a token is held the same way, which costs at most its
  own validity window. An entry whose keyed tokens are under an unparameterized keyed catalog contract keeps
  ADR-0022's existing record check (F20) **only when its record's contract name still resolves to that catalog
  contract**; a present record whose name no longer re-derives the body's policy (renamed, or now parameterized) is
  retired, by the legacy-record rule above, **before** the catalog-name comparison runs (X52), keeping the
  designated-input and attempt checks. So no legacy record ever makes a restore batch fail for a name change.
- **The same body already held (rewind and replay)** (X54): after a rewind the host may already hold, live, the
  body a restore entry carries, with its operation record. ADR-0022's merge refuses an incoming entry whose record
  differs, an absent one included (`Reservations.restore`, `:387-397` at `2ffaf41b`), which would keep the fence up
  for a journal that merely omits the record. In a protocol-2 session: when an incoming entry has the same body
  hash, state and slot as a held live entry and **omits** `operation`, the host keeps the held entry as it is (its
  operation, attempts and reservation id) and applies the restored-body discard guard above to it; an incoming
  entry that **carries** a record merges as ADR-0022 merges it (same identity, a superset of attempts), and any
  other difference (state, slot, or a record of another identity) is refused as today.
- **A recordless body named in another entry's attempts** (X56): a rewind and restart can require restoring attempt A
  without its record and retry B whose record lists A's hash. ADR-0022's batch check requires both records to share
  an identity before it accepts a deadline for the other body (`Reservations.restore`, `:362-377` at `2ffaf41b`), so
  today the batch fails in either order. In a protocol-2 session, when one entry has no record and another entry's
  `attempts` name its body hash, the host checks that the named deadline equals that body's `validTo` and that the
  body spends the record's designated input; then it treats the recordless body as **an attempt of that
  operation**: both entries keep their own reservations (out-refs and keyed tokens) and the restored-body discard
  guard applies to both, in whichever order they arrive. If either check fails, the record is inconsistent with the
  body its own history names: that operation is **retired** (above) and the recordless body is held under the
  discard guard, so the restore still completes. When **both** entries carry records, ADR-0022's identity-conflict
  check applies unchanged.
- Every other entry (an ordinary call, a reference-script publication, a stake or governor registration) is restored
  by ADR-0022 D8's existing path, unchanged and without `params`: its reservation is of out-refs, which D8 already
  excludes session-wide. A later retry is compared with its own request's params.
- `deployment`'s result adds `params` (the Data, CBOR hex, D3's encoding) and the **applied** `scriptHashes` (`spend`,
  `mint`, `stake`) beside `unapplied`; the client requires `params` to equal its own bytes. **`deployment.governors`
  stays empty in protocol 2** (Y2): governors are named per call (D4), never pinned in a deployment; a client
  treats a non-empty list as a protocol violation.
- **`initialize` and `describe` under `params`** (Y2; protocol 2 only, X20): a host advertising `params` reports a
  parameterized contract **whose description carries D2a's `formats` marker** with its `unapplied` template hash and
  `parameterized: true` instead of `unsupported: "params"`; `describe` gives each method of such a contract the
  status its own code gives (a plain method is `served: true`; an `@AuthorizedBy` method follows D7). The
  contract-wide `params` code no longer masks a method's own code once `params` is served (F13). **The marker is
  per contract** (X44): in the same session, a parameterized contract without it keeps protocol 1's entry
  (`unsupported: "params"`, message "rebuild with JOUST M4a or later") in `initialize`, every one of its methods is
  `unsupported: "params"` in `describe`, and its requests are `unsupported/params`. A host without the capability
  keeps protocol 1's entry for every parameterized contract.
- **Fields that need a capability** (Y1): every field this ADR adds exists only in protocol 2 (D1). Within a
  protocol-2 session a client sends `params` only after `initialize` advertised `params`; `operation.policy`,
  `operation.template` and a restore entry's `params` only after `params` (all name a parameterized contract); and
  `governor` only after `authorizedBy:script`. A host refuses a field whose capability it did not advertise
  (`bad/request`), as it refuses any unknown field.

### D2a. One input domain for `params` (X25)

Both hosts check `params` (and a governor's `params`, D4) against one domain **before** either adapter decodes or
applies them, so the JVM's reflective codec and the engine's schema codec can never disagree on what is accepted (F22):
- **integers:** a JSON integer only when \|n\| ≤ 2^53 − 1; otherwise a decimal string matching
  `0|-?[1-9][0-9]{0,199}` (at most 200 digits, no leading zeros, no `-0`). Larger values are refused, not
  truncated. (`JsonValues` today also accepts ±2^53 as a JSON integer and leading zeros in strings; `SchemaCodec`
  accepts any length: both narrow to this rule in M4a.)
- **Java widths and text** (X30, X35): the description writes Java `int`, `long` and `BigInteger` alike as
  `integer`, and `String` and `byte[]` alike as `bytes`; the engine encodes them as unbounded integers and raw bytes,
  while the JVM adapter narrows `int`/`long` exactly and decodes a `String` as UTF-8, replacing malformed bytes
  (`JsonValues.java:72-73`, `:79` at `4335773d`). So the same request could be refused by one host or give another
  policy on the other. The description also writes `short`, `byte` and the boxed `Integer` and `Long` as
  `integer` (`DescriptionWriter.schemaOf`, `:588`, `:601-602` at `4335773d`), and `JsonValues` has no decoder for
  `short` or `byte` (X43). From M4a the processor writes, on every component **at any depth, inside records, lists,
  maps and `Optional`s**, a **`format`**: `int32` for `int` and `Integer`, `int64` for `long` and `Long`, `utf8` for
  `String`; and it writes the marker **`"formats": 1`** in `x-joust.params` **only when every reachable integer is
  one of those or `BigInteger`, and every reachable byte string is `byte[]` or `String`**. A params record reaching
  any other representation (`short`, `byte`, `char`, their boxed forms, or a type the processor cannot classify)
  gets no marker, so the contract is not served (below), and the processor reports a warning naming the component;
  it is not a build error, since the contract still works outside the dev host.
  Both hosts check the format before either adapter: an `int32`/`int64` value must be in that type's range, and a
  `utf8` value's bytes must be well-formed UTF-8 (strict, no replacement), so the JVM's decode and re-encode returns
  the same bytes. A params description **without** the marker (any artifact built before M4a: an older jar,
  `contracts.json` or class output) is not served: `unsupported/params`, "rebuild with JOUST M4a or later". The
  client reads the same marker and formats from its own snapshot. This is additive to the description: page
  verifiers ignore it.
- **byte strings:** lowercase hex of even length, at most 16,384 bytes;
- **booleans:** JSON `true` / `false` only;
- **lists, maps and records:** at most 1,024 items or entries each, nesting at most 32 levels; map entries as D3 step 1
  normalizes them;
- **raw `PlutusData`:** CBOR hex in D3's normal form, within the same byte-string limit, with **every constructor
  index, at any depth, at most 2^63 − 1** (X55): JuLC's CBOR decoder accepts larger (unsigned) indices, which the
  engine would apply, while the generated-client path narrows each index with `longValueExact()` in JuLC's
  `PlutusDataAdapter` and refuses it, so without the cap one host would derive a policy and the other refuse.

A value outside the domain is `bad`, code `params` (`governor` for a governor's params), with the same code and path
on both hosts. The domain is part of the protocol-2 schemas, and D8's boundary vectors pin it. (The same mismatch for
ordinary call arguments belongs to ADR-0022 M2, PR #154; this ADR covers params.)

### D3. The client anchors on its own application, byte for byte (amends ADR-0022 D13 rule 2)

For a parameterized contract (and for a governor, D4) the client must, from the verified snapshot only:
1. **Encode** the params from the description it read into Data, in **plutus-core's encoding** (F5): constructor tag
   forms as the ledger's (121–127, 1280–1400, then 102 with an explicit index), non-empty lists and constructor fields
   as indefinite arrays and empty ones as `80`, integers in the shortest form (tags 2/3 beyond 64 bits, a bignum magnitude over 64 bytes chunked like a byte
   string), byte strings over 64 bytes as indefinite strings of 64-byte chunks, maps as **definite-length** maps with
   their **entries sorted** by their keys' encodings in RFC
   8949 length-first order, duplicates refused (as `SchemaCodec` normalizes typed maps, F17). A value given as raw
   `PlutusData` CBOR hex must already be in this form, or it is refused (`bad`, code `params`): neither host
   re-normalizes raw Data. A contract's `@Params` record is **one** value; a plain validator's `@Param`s are **one
   value each, in declaration order** (F4).
2. **Apply:** take the unapplied `cborHex` (F6), remove the outer CBOR byte string to get `S`, decode `S`'s payload as
   a flat program (builtin function tags are opaque to the client: it copies the term bytes it does not need to
   understand, so a script using builtins an oracle cannot name still applies byte for byte), and build `[[f p1] … pn]`, each `pi` a Data constant of step 1's bytes, keeping the program's
   version; flat-encode it (with flat's padding) and wrap it as one CBOR byte string `S'`.
3. **Hash:** `blake2b_224(0x03 ‖ S')`. Refuse an applied contract script whose **outer `cborHex`, decoded**, exceeds
   15,000 bytes, as the runtime measures it (F4; an `S'` of 14,998 bytes wraps to 15,001 and is refused): `refusal`,
   code `scriptSize`.
4. **Derive** the address from that hash with the snapshot's `stakeSelf` and the session network (F4), the policy
   (the hash) and the reward address (script reward account, header `0xF0 | network`).
5. **Refuse** a `deployment`, a result or a body whose script, address, policy, reward address or `params` bytes
   differ.

The client keeps its typed params (field names and values) for D5's credential checks and the verifiable-set rules.
D8's application vectors are the oracle for steps 1–3.

### D4. Governors: what the request names, and what the host builds

An `@AuthorizedBy` action whose credential is a script takes **`governor`** in `prepare`'s call:
`{validator, params, signers}`:
- **`validator`** (T16): Gradle, the **fully qualified class name** of the `@WithdrawValidator`, resolved as
  `ValidatorScripts` resolves it (beside the defining class, failing on ambiguity, F7); npm, `{package, name}` of a
  `validators.json` entry. The Gradle resource must say `PlutusScriptV3`; a `validators.json` entry carries no
  language field (F7), and the format **implies Plutus V3** (the toolchain emits nothing else); both must have
  purpose `withdraw` (`bad`, code `governor`, otherwise), and D5 checks the witness's language independently.
- **The npm fingerprint is versioned** (amends ADR-0022 D3/D4; X17, Y1): `initialize`'s `build` gains
  `fingerprintVersion`, **npm only** (the JVM host's digest already covers its whole class path, so it has no version
  2; it keeps refusing the field as an unknown `build` key, `bad/request`, and a client never sends it there).
  Hosts from the release that ships M4c advertise capability **`fingerprint:2`** in every **protocol-2**
  `initialize` answer (never in protocol 1, X32). Because the field
  travels in the first request, before any capability is known, the client decides from the installed package it
  already resolves before launch (ADR-0022 D3): it sends `fingerprintVersion: 2` only when the resolved
  `@julclang/joust` package's own `package.json` version is at or above the release that ships M4c; an older host
  would refuse it with `bad/request`, and a client that nevertheless receives that answer starts a fresh host process
  without the field (a failed `initialize` ends the session's usefulness; nothing is retried in place). Version **1** (absent means 1: every protocol-1 client today) is
  **the final M3b host's file set and ordering** (X51, F14): `contracts.json`, the two engine files and the launch
  closure, as sorted lines; version **2** is that same set plus `validators.json` and `joust.lock.json` when present,
  as further lines in the same format, sorted with the rest. So a change to a captured host file changes both
  versions' digests, and an M3b client's digest still initializes a version-1 session.
  `fingerprintVersion: 2` is a protocol-2 field: a client sends it only together with `protocol: [1, 2]` (D1), and a
  host that selects protocol 1 refuses it (`bad/request`). **A manifest is current only if the build wrote it**
  (X24, T16): today a build with no validators leaves an earlier `validators.json` in place (F21), so the digest
  would cover, and the host would load, governors the project no longer has. M4c therefore changes the npm build to
  write `validators.json` on every successful build, as `[]` when there are no validators, and to remove stale
  `<name>.plutus` files with it. **The lock binds the manifest's exact bytes** (X31): **builds into one output
  directory are serialized** (X48) by an exclusive build marker (`.joust-build`, created exclusively before anything
  else and removed after the final rename); a second build that finds it waits or fails, and a marker left by a
  crashed build is removed only after `joust.lock.json` is, so a stale lock can never be republished by an
  overlapping writer. Under that marker the build **removes the previous `joust.lock.json` before writing anything
  else** (X41), and writes the new one **last** (after
  `contracts.json` and `validators.json`, by a temporary file and rename) with
  `buildDigests: {contracts, validators}`, the SHA-256 of the exact `contracts.json` and `validators.json` bytes it
  wrote (X31, X37). Version 2 also digests `joust.lock.json` (above), and **both** host and client serve script
  governors only when **no `.joust-build` marker is present** (X53: a build that crashed after creating it, before
  removing the old lock, leaves an old lock that still matches the old files), a lock is present, **and both** its
  digests equal the files beside it. A build
  interrupted anywhere before the lock leaves **no lock** (the old one was removed first), even when the rewritten
  `contracts.json` is byte-identical to the previous one, as it is after a validator-only rebuild; a lock without
  `buildDigests` (any build before M4c, F21) matches nothing. Either way the manifest is stale: the
  host advertises no `authorizedBy:script`, and the client treats an answer that does as a protocol violation. The
  fallback for older builds is a rebuild with the M4c toolchain. The host
  computes the digest the client asked for; under version 1 it serves no script governors (`authorizedBy:script`
  absent), so an old client keeps working, unchanged, on a project with the manifest. The host loads the manifest
  eagerly at `initialize` (version 2), so later changes on disk never reach the running session. **The client's half**
  (Y13), **npm sessions only** (the JVM host has no fingerprint version and keeps its single digest, F-Y2): it sends
  script-governor calls, `prerequisites.governors` and governor `publish` only in a version-2 session;
  it computes its D5 anchors from the same `validators.json` bytes it digested; and it treats a version-1 (or
  version-less) npm `initialize` answer that advertises `authorizedBy:script` as a protocol violation. `fingerprint:2`
  is informational: it tells a client that sent no version that a version-2 session is available on this host.
- **The client resolves the same artifact by a written rule** (T16; Y3), independently of the host:
  - **Gradle:** locate `<package path>/<SimpleName>.class` in the **first** snapshot class-path entry, in ordinal
    order, that holds it; read `META-INF/plutus/<SimpleName>.plutus.json` **from that same entry**. Refuse (`governor`)
    when that entry holds another class with the same simple name, the resource is missing, `type` is not
    `PlutusScriptV3`, `purpose` is not `withdraw`, or its `params` list differs from `governor.params` in arity or in
    the declared types.
  - **npm:** exactly one `validators.json` entry with that `{package, name}` (more than one, or none, is refused), with
    purpose `withdraw` and a `params` list matching `governor.params` as above.
  The host applies the same rule (ValidatorScripts resolves beside the defining class and refuses ambiguity, F7), and
  the kit has a refusal vector for each clause (D8).
- **`params`**: its `@Param` values in declaration order, as JSON of their declared types (`byte[]` hex, `BigInteger`
  integer or decimal string, `boolean` JSON boolean only, `PlutusData` CBOR hex), each within D2a's domain, encoded
  and applied as D3 says. Wrong arity or type, or a value outside the domain, is `bad`, code `governor`.
- **`signers`**: the 28-byte payment key hashes the governor's own rules require (Guardian: `owner`). The client names
  them; the host cannot know a governor's rules. **Count limit** (X50): the M3b host already refuses more than 64
  `cosigners` entries before building any account (`MAX_COSIGNERS`, `Session.java:303-310` at `2ffaf41b`,
  `bad/request`); `governor.signers` takes the same cap, **at most 64 entries**, and the two lists together at most
  **64 distinct** keys, all checked on both hosts before any account is built; a governor list or a union over the
  cap is `bad`, code `governor`. The protocol-2 schema states the limits.

The host applies the governor's params, refuses (`bad`, code `governor`) a governor whose applied hash differs from
the credential in the contract's params or equals the contract's own hash or either shared verifier's (F16), and
builds the call as the runtime does (F1, F2): exactly one zero withdrawal from the governor's reward address, the
governor script **in the witness set** (protocol 2 serves inline governors only; the reference form is a later
extension), `Approvals[Approval(policy, instance, tag)]` as its Rewarding redeemer, and `signers` added to the body.
A final transaction above the ledger's size limit is `refusal`, code `txSize`.

**The JVM path** (F12, F13; ADR-0022 D16): `ServedContract` gains params-aware dispatch through the generated
client's params entry points (`deploy(runtime, params, …)`, `at(runtime, params, instance)`, and the params-taking
lookups and reads), the params record decoded from JSON by the adapter's reflective codec (`JsonValues` in
`julc-state-serve`), which keeps a map's order as given: the JVM path therefore normalizes typed maps (RFC 8949
length-first order) and refuses duplicates, as D3 step 1 requires, **before** `deploy`/`at` apply the record through
`PlutusDataAdapter` (which keeps that order); and it builds the `Authorization`
argument (F12): `Authorization.key(WalletAccount)` for a key credential, `Authorization.script(reward, applied
governor, Approvals, signers as WalletAccounts)` for a script. The **HTTP prepare service is unchanged**: it keeps
refusing `@Params` (R3) and `@AuthorizedBy`, with its current answers, and a test pins that.

### D5. What the client checks for these calls (amends ADR-0022 D13 rules 3 and 4)

In addition to ADR-0022 D13:

**Signers** (T15, F9). The body's required signers must equal exactly, as a set:
`{caller, if the method uses Ctx, @SignedBy or @Quorum}` ∪ `{named co-signers}` ∪ `{the key credential, for a key
call}` ∪ `{governor.signers, for a script call}`. Wallet witnesses (the payer's inputs and collateral) are separate and
are not required signers. For a key call, the credential's key (from the client's own params) must be the caller or
a named co-signer; co-signers are allowed only for `@Quorum` or to supply that key. The key holder need not be the
caller or the payer: with credential B, payer A and `cosigners: [B]` on a method with no `Ctx`, `@SignedBy` or
`@Quorum`, the required signers are exactly `{B}`, and A still witnesses its inputs (X26, F9).

**Set equality is necessary, not sufficient** (X21, T17). It bounds who must sign; it does not show that those
signers authorize the call:
- **`@Quorum`** (ADR-0009 D2, I32): the client reads the quorum's `keys` and `threshold` itself, from the source
  ADR-0009 D2 gives them, **by call kind** (X29): `params.*` fields from its own params; otherwise the **input** record
  of the object the call spends, decoded against its own description from the input it resolved through its own node
  (ADR-0022 D13 rule 3): the instance's state for `action` and `destroy`, the owned record for `ownedAction` and
  `ownedDestroy` (whose council may differ from the parent's, and whose call need not spend the parent), the keyed
  record for `keyedAction`, and for `keyedRemove` the **`removed`** input of
  `Remove_T(anchor, removed)` (ADR-0016 D5: the removed node runs the removing method; its predecessor's council is
  not the source, X36). It requires at least `threshold` **distinct** required signers that are in `keys`; otherwise
  it refuses (`quorum`). **The protocol-2 quorum call kinds** (X36, X45) are exactly `action`, `destroy`,
  `ownedAction`, `ownedDestroy`, `keyedAction` and `keyedRemove`, and **both hosts must prepare each** of them when
  the call is otherwise servable. `keyedRetire` is excluded: the processor refuses `@Quorum` on a retiring exit so the
  owner can always leave (`ContractReader.java:783-787` at `4335773d`, ADR-0016 D1), so no such contract builds;
  a host reports any other kind with `@Quorum` `unsupported`. At `4335773d` the JVM catalog admits quorum on
  `keyedRemove` and `keyedRetire` (`Contracts.java:46`) while the engine's dispatcher refuses quorum on `keyedRemove`
  (`DescribedContract.java:189-192`); M4a changes the engine to prepare a quorum removal and the catalog to drop
  `keyedRetire`. The eligibility count also amends ADR-0022 D13 rule 3 for M1b's `@Quorum` calls, whose rule today
  checks only that signers are within the caller and the chosen co-signers.
- **Key credentials:** I17 already requires the credential's key among the required signers, which is the whole
  authorization rule of the key branch (F1).
- **Script governors:** whether `governor.signers` satisfy the governor's rules is known only by running it: a
  governor is opaque project code (§2). Q6 decides whether the client must evaluate phase 2 before signing or
  exporting a governor call; until it is decided, M4c cannot start (D9). An honest host evaluates the transaction it builds
  (ADR-0022 D6a: script evaluation is local and mandatory on every host build) and so refuses such a body; the gap is a hostile host making the submitter forfeit the collateral D13 bounds.
- **`is_valid`:** the submitter sets it, outside the body hash; it shows nothing about evaluation, and the kit's
  `is_valid` check is a shape rule only.

**Withdrawals and Rewarding redeemers** (T13, T14, F10). The allowed withdrawals are exactly: the governor's (script
calls), the output verifier's (methods that send, ADR-0022 D13), the map verifier's (map calls, ADR-0022 M3). Each is
compared by its **full reward-account bytes** (header with the session's network tag, then the hash), appears once,
and withdraws zero. Their pointers are their indices in the withdrawals sorted in the ledger's order (Haskell's `Ord` on the reward
account: network tag, then script credentials before key credentials, then the hash bytes), computed by the client
independently of the order the body's CBOR map happens to use (F-Y6); **every** pointer has
exactly one Rewarding redeemer, **decoded by purpose against its own type** (X13):
- the governor's: `Approvals` with **exactly one** `Approval(policy, target, action)` (the applied policy, the
  instance's token name, the method's redeemer constructor index from the client's description);
- the output verifier's: `OutputClaims` (F18), with claims for the call's sends as ADR-0022 D13 bounds them;
- the map verifier's: `MapClaims` (F18), as ADR-0022 M3's rules check them.

A redeemer that does not decode as its pointer's type (`Approvals` at a verifier's pointer, say) is refused, as is a
Rewarding redeemer at any other pointer, a duplicate or a missing one. **A verifier's withdrawal is present exactly
when the call produces claims for it** (the builders omit it for an empty claim list, F18): a withdrawal without
claims, or claims without their withdrawal, is refused.

**The governor** (T12, T14, T16): its hash is the credential in the client's own params; the client applies the
`governor.params` it sent to the governor from its snapshot (D3) and requires the same hash; the hash is not the
contract's, the output verifier's or the map verifier's; **exactly one** V3 script in the witness set hashes to it
(missing or reference-only is refused); no other script but those ADR-0022 D13 allows. Instance actions only.

**Registration** (D6; Y11): a governor's registration is accepted only as a **pure legacy (kind 0) stake registration**
of the governor's credential, in ADR-0022 D14's `stakeRegistration` shape, **with the mandatory upper bound**, and
with **no redeemers, witness scripts, collateral or reference inputs** (a Conway kind-7 registration of a script
credential needs the governor's own Certifying witness, which ADR-0009 D3 obliges a governor to refuse, so it could
never validate). The host's governor overload builds kind 0. This is stricter than the page's check (F11).

### D6. Prerequisites and publication for governors (amends ADR-0022 D14)

- **Eligible governors** (X16): the set of script credentials that the contract's params hold in fields named by
  `@AuthorizedBy` on served instance actions, derived from the verified description and the params, by the host and
  the client independently. A governor whose hash is not in that set is refused (`bad`, code `governor`), whatever
  other credential fields the params hold.
- `prerequisites {now, contract, params, governors?: [governor]}` adds, for each eligible `governor` given, its
  `stakeRegistration` (ADR-0022 D14's absence check and `unknown` handling apply). A contract with several governed
  methods lists the governors the client names; the host never guesses one.
- `publish {now, ttlSlots, contract, params, prerequisite, governor?, address}`: for `stakeRegistration:<hash>` of a
  governor, the request carries that `governor`; the host re-derives its hash (D4) and requires it to equal the
  prerequisite's hash and to be eligible, then builds a **governor-specific bounded registration**
  (a new overload registering an arbitrary script credential; the existing contract-registration path is not used).
- `prepare` of a script call is refused `prerequisite` while that governor's registration is `false` or
  `unknown`-unlearned; a governor the call does not use is never required.

### D7. Errors, codes and capability behaviour

New codes: `bad/params`, `bad/governor`, `refusal/txSize`, `refusal/scriptSize`; the checker gains `appliedScript`,
`governor`, `governorWithdrawal`, `governorRedeemer`, `governorSigner`, `governorWitness`, `verifierRedeemer`,
`quorum`. Observable behaviour (every row but the first two is a protocol-2 session):

| Situation | Answer |
|---|---|
| a client offering `[1]`, or any client of an M1–M3 host | protocol 1: ADR-0022's answers exactly, replies valid against the `dev-host-1` schemas (D1, I21) |
| any field this ADR adds, in a protocol-1 session | the M3 host's answer for that method, with its precedence (D1): e.g. Treasury `deployment` with `params` `unsupported/params`; Treasury `prepare` with `call.params`, or `decode` with `params`, `bad/request` |
| a parameterized contract, `params` absent (any client) | `unsupported/params` |
| `params` present, outside D2a's domain or does not encode exactly | `bad/params`, the same path on both hosts |
| any restored entry whose body holds keyed tokens (with or without a record, whatever its `attempts`) | out-refs and keyed tokens held; `discarded` refused (`conflict`); ends by final inclusion or margin-qualified expiry (D2) |
| a keyed restore entry for which R fails (rebuilt template, another template under its name, a stale `template` field, params that no longer give its policy) | restored as a retired operation, held and never discarded; its retries `bad/operation` (D2) |
| a retry for which R fails with its request's params | `bad/operation` (D2) |
| no `joust.lock.json`, a lock without `buildDigests`, or digests that do not equal `contracts.json` and `validators.json` | no `authorizedBy:script` in the session (D4) |
| a parameterized contract whose description lacks `"formats": 1` (built before M4a, or reaching an unformatted representation) | `initialize` and `describe` entries `unsupported: "params"`, requests `unsupported/params` ("rebuild"), on both hosts, while marked contracts in the same session are served (D2, D2a) |
| a `@Quorum` call of a kind outside the protocol-2 list | `unsupported` on both hosts (D5) |
| an `int32`/`int64` value out of range, or a `utf8` value that is not well-formed UTF-8 | `bad/params`, the same path on both hosts (D2a) |
| raw `PlutusData` with a constructor index above 2^63 − 1 at any depth | `bad/params` on both hosts (D2a) |
| a restore entry without `operation` whose body another entry's `attempts` name with its `validTo` | both restored, as attempts of that operation, under the discard guard, in either order (D2) |
| a restore entry omitting `operation` for a body the host already holds live, with the same state and slot | the held entry kept (operation, attempts, reservation id); the discard guard applies; the batch completes (D2) |
| a key-credential call on a host without `authorizedBy:key` | `unsupported/authorizedBy` ("key credentials") |
| a script-credential call on a host without `authorizedBy:script` | `unsupported/authorizedBy` ("script governors") |
| an npm client initializing with fingerprint version 1 (or none) on a project with `validators.json` | the version-1 digest; no `authorizedBy:script`; every other call as today |
| a client sends `fingerprintVersion` to a host that does not ship this ADR (any M1–M3 host), to the JVM host, or without offering protocol 2 | `bad/request` at `initialize`; the client starts a fresh host process without the field (D4) |
| a client sends `params`, `governor` or `operation.policy` to a host without the capability | `bad/request` (strict field lists); clients send them only after the capability (D2) |
| `initialize` on a host with `params`, a parameterized contract **whose description carries the `formats` marker** | `unapplied` hash and `parameterized: true`, no `unsupported` (D2); an unmarked one keeps `unsupported: "params"` (row above) |
| `describe` of an `@AuthorizedBy` method | `served: true` when **its contract is served (marked, D2) and the method passes its other support checks**, and either credential branch is served; otherwise its own `unsupported` code (`params` on an unmarked contract); the call entry adds `authorizedBy: [{field, branches}]` so a client knows which branch its params will need |

### D8. Verification and vectors

- **Application vectors (I16):** `(unapplied cborHex, params JSON, params Data, applied cborHex, applied hash)` for:
  Treasury and Guardian; a **zero-param** governor; a **multi-param governor with order-sensitive values**; every JSON
  type, nested records, lists, **maps given out of order** (normalized) and with a duplicate key (refused), raw
  `PlutusData` not in normal form (refused), byte strings of 64 and 65 bytes, integers at 2^64 ± 1; applied contracts
  whose decoded outer `cborHex` is **15,000 bytes (accepted) and 15,001 bytes (`refusal/scriptSize`)**. Generated by JuLC's `applyParams` and checked **independently** by Aiken's implementation
  (`com.bloxbean.cardano.aiken.AikenScriptUtil.applyParamToScript`, `aiken-java-binding 0.1.0` over Aiken's Rust code) and
  by the kit's own applier, written against the Plutus and flat specifications. Any disagreement blocks M4a. Malformed
  params (wrong type, `boolean` given as `1`, unsafe JSON numbers, wrong arity) each have a vector with its code.
- **Input-domain boundaries (D2a, X25), differential:** JSON integers at ±(2^53 − 1) (accepted) and ±2^53
  (refused); decimal strings of 200 digits (accepted, same applied bytes on both hosts) and 201 digits (refused),
  a leading zero and `-0` (refused); byte strings of 16,384 and 16,385 bytes; collections of 1,024 and 1,025 items;
  nesting of 32 and 33 levels; 2147483648 and 9223372036854775808 in a `BigInteger` component (accepted, same
  bytes on both hosts) and in `int` and `long` components (refused, `bad/params`); `ff` in a `String` component
  (refused) and in a `byte[]` component (accepted); a multi-byte UTF-8 string in a `String` component (accepted,
  same bytes); raw `PlutusData` `deployment` params with a nested constructor index of 2^63 − 1 (accepted, the same
  policy on both hosts) and 2^63 (`bad/params` on both, X55). Each refusal has the same code and path on both
  hosts. **Formats (X30, X35, X43, X44):** a nested
  record's `int` component carries `format`; `Optional<Long>` and `List<Integer>` elements at 2^63 and 2^31
  (refused on both hosts) and at the type's maximum (accepted, same bytes); a params record reaching a `short` or
  `Byte` gets no marker and a processor warning; one protocol-2 session with a **marked and an unmarked**
  parameterized contract: the marked one is `parameterized: true` with its plain methods served, the unmarked one
  `unsupported: "params"` in `initialize` and `describe`, **its governed (`@AuthorizedBy`) method
  `unsupported: "params"` too, on a host advertising both `authorizedBy` branches** (X49), and a `deployment` of it
  `unsupported/params`; a pre-M4a artifact likewise.
- **Mutations** (recomputed hashes, one targeted rule each, proven by disabling it): other params (definite-array
  encoding of the same values included); another governor; an **ineligible** governor (a script credential in a
  params field no served `@AuthorizedBy` names) and either of two eligible ones (positive); the contract's own hash
  as governor (**a unit test at the checker boundary**: a consistent end-to-end vector is impossible, since params
  cannot contain their own applied hash); a **shared verifier's hash as governor, end to end** (its hash is known
  before application, so params can hold it: the host refuses `bad/governor`, and a forged body is rejected by the
  checker); non-zero, second, missing, or wrong-network-tag governor withdrawal; governor-plus-output-verifier in
  **both** ledger orders (positive), and approval data at the verifier's pointer (`verifierRedeemer`); a verifier withdrawal without claims, and claims
  without it; `Approvals` naming another instance,
  action or policy, or an extra approval; a shifted pointer; missing governor witness and reference-only governor;
  payer ≠ governor owner (positive, F8), caller-as-key with a `@Quorum` co-signer (positive), overlapping keys, a
  missing, extra or substituted named signer; registration of a wrong target, without TTL, already registered, and
  `unknown`.
- **Key holder who is not the caller (X26), positive, both hosts:** Treasury's `freeze` with key credential B, payer
  A and `cosigners: [B]` (no `@Quorum`, `Ctx` or `@SignedBy`): the body requires exactly `{B}`, A witnesses its inputs,
  and the reference checker accepts; with the old quorum-only co-signer gate it would be refused, so the vector fails
  an adapter that kept it.
- **Quorum eligibility (X21, X29):** a 2-of-3 Treasury council `pay` whose required signers are the caller (a member) and
  an outsider: signer-set equality holds and the checker refuses `quorum`; the same with two members (positive); a
  duplicate member key counted once (refused). For an **owned** `@Quorum` call whose owned record's council differs
  from the parent instance's: signers meeting the object's threshold (positive) and signers meeting only the
  parent's (refused); the same pair for a keyed `@Quorum` call; and a `keyedRemove` whose `removed` node and
  predecessor have different councils (signers meeting the removed node's threshold accepted, the predecessor's
  only refused). Each positive is **prepared by both hosts' dispatchers** and accepted by the checker, the removal
  included (X45); a `keyedRetire` with `@Quorum` fails to build (processor test). M4a needs fixture contracts for
  these (owned, keyed and removal quorum), added to its entry gates.
- **Identity tests:** two parameterizations competing for one wallet out-ref (refused); **two disjoint keyed
  operations under one policy** (different instances or keys: both prepared, their histories kept apart, X42); a
  restore with a **stale `template` field** and an otherwise matching policy (retired), and a retry under the same
  stale record (`bad/operation`); a restored keyed entry whose
  `operation.policy` and body disagree (`bad/operation`, the batch commits nothing), one whose params no longer give
  its policy (retired, by R), separately from a valid restore followed by a retry
  with changed params (`bad/operation`); restarts with a pending **bounded governor registration** and a pending
  **reference-script publication** of a parameterized contract (both restored, positive, by D8's path); an npm `validators.json` changed **before** `initialize` against the old
  digest (`stageMismatch`), and changed **after** it (the running session keeps its loaded bytes); an old client
  (fingerprint version 1) and a new host on a project with the manifest (initializes, no `authorizedBy:script`); two
  packages with a `Guardian` (the Gradle qualified selector works; ambiguity fails closed).
- **Recordless attempt and its retry (X56), M4a:** attempt A expires, retry B is handed out with A in its
  `attempts`, a rewind revives A, the host restarts, and the journal restores A **without** its record and B with
  it, in both orders: reconciliation completes, both bodies stay held under the discard guard, and B's operation is
  live; the same with B naming A under a deadline other than A's `validTo` (B's operation retired, A held, the
  restore completes); and with both records present but of different identities (refused, as today).
- **Rewind and replay (X54), M4a:** prepare a keyed operation, hand it out, rewind, then restore a journal entry with
  the same body, state and slot but no `operation`: reconciliation completes, the held entry keeps its operation,
  attempts and reservation id, `discarded` is refused, and its inputs and keyed token stay held until final
  inclusion or margin-qualified expiry; the same replay carrying a record of another identity is refused as today.
- **Keyed restore (X22, X23, X27, X28, X40, X47), M4a:** a **protocol-1-to-2 restart**: a pending keyed operation
  recorded by an M3 client (no `policy`, `template` or `params`), its contract gaining `@Params` under the same name
  before the restart (retired: attempts, inputs and keyed tokens held, the restore completes); the same restart with
  the contract unchanged (ADR-0022's check, live); the same restart after the unchanged keyed script is **renamed**
  from A to B in the catalog (retired, not `bad/operation`: the restore completes and the original inputs and keyed
  tokens stay reserved, X52); a re-derivable keyed operation whose record keeps everything
  but drops **only its current attempt** from `attempts` (live; `discarded` refused; held until margin-qualified
  expiry); a pending keyed `@Params` entry with its `operation` dropped
  (restored by D8's path: its inputs and keyed token stay held; `discarded` after restore is refused; a new keyed
  operation on the object is refused while the body can land; expiry is refused at `validTo + settlementSlots` and
  established one slot later, after which a new operation is allowed); a **mixed project** (a parameterized keyed
  contract beside a parameterized non-keyed one that mints a 29-byte `0x4B` token) whose non-keyed pending entry
  restores and is held; a restart after a **template rebuild** while the original body can still land (retired:
  held, never discarded, a retry is `bad/operation`, the original body settles normally and releases them); the same
  rebuild with the **old template retained under another name** (still retired, the restore completes); and two
  live names with the **same template** (a name-only change restores as the same, retryable operation).
- **Stale manifest (X24, X31), M4c:** build Treasury with Guardian, remove the last validator, rebuild in the same
  directory, launch: no `authorizedBy:script` and a Guardian lookup fails; a lock from an older toolchain (no
  `buildDigests`): no `authorizedBy:script`; a **validator-only** rebuild (remove Guardian, Treasury unchanged)
  interrupted right after a byte-identical `contracts.json` write, and one interrupted after `validators.json`: no
  lock, so no `authorizedBy:script` (X41); **two overlapping builds** into one directory with identical contract bytes
  and different validator manifests (the second waits or fails at the build marker; neither leaves a lock matching
  the other's manifest, X48); a build that removes Guardian **interrupted right after creating `.joust-build`**,
  with the old matching lock still in place, then a fresh `initialize` before recovery (no `authorizedBy:script` on
  host or client, X53).
- **Fingerprint sets (X51), npm:** an M3b client's version-1 digest for the same snapshot initializes; changing a
  captured host JavaScript file changes both the version-1 and the version-2 digests.
- **Signer count (X50), both hosts:** 64 and 65 `governor.signers` entries (accepted; `bad/governor`), 64 distinct
  keys across `governor.signers` and `cosigners` (accepted), 65 (`bad/governor`), each refusal before any account is
  built, and duplicates across the two lists counted once in the union.
- **Differential:** the JVM host (through generated clients) and the Node host agree byte for byte on every M4 vector.
- **Governor artifact resolution** (Y3): a refusal vector for each clause of D4's client rule (a second class with the
  same simple name in the first entry, missing resource, wrong `type`, wrong `purpose`, `params` arity and type; npm:
  duplicate and missing `{package, name}`), and a positive where a later class-path entry holds a different class of
  the same name (the first entry wins).
- **Capability gating** (Y1, Y2): a new client against an M3 host (the field-gating rows of D7), and `initialize`/
  `describe` of Treasury with and without `params`.
- **Legacy session (X20, X32, X38, X39, X46), both hosts, differential against the pinned final M3b host** under
  identical project and chain fixtures: a client offering `[1]` to a host that ships this ADR, on a project with
  Treasury **and a servable unparameterized map contract** (its `maps` capability, `assetHistory`, `redeemers` and
  `datum` read kinds, and its `deployment` compared too):
  every `initialize`, `describe` and `deployment` reply validates against the **unmodified** `dev-host-1` result
  schemas; `fingerprint` and `versions` are checked against the launch; every other field and the capability list
  (no `params`, `authorizedBy:*` or `fingerprint:2`) equal the M3 host's; Treasury is `unsupported:"params"`; and
  Treasury's `deployment` with `params`, `prepare` with `call.params`, `decode` with `params`, and a `prepare` with
  a nested `operation.policy`, each get the M3 host's code; a client offering `[1, 2]` to an M3 host gets protocol 1
  and the same replies.
- **Governor registration** (Y11): mutation vectors refused for a Conway kind-7 registration, a redeemer or witness
  script, collateral and a reference input in a governor registration.

### D9. Milestones (ADR-0022 §8 numbering continues)

| Milestone | Scope | Entry | Exit |
|---|---|---|---|
| **M4a** | Protocol 2 and its schemas (D1); `@Params` contracts on both hosts (D2, D2a, D3, D4's JVM path for params); keyed restore with `operation.template` and retired operations (D2); the `@Quorum` eligibility count and quorum-kind parity (D5); the processor's params `formats` marker (D2a); application, input-domain and format vectors | this ADR accepted (three reviewers); ADR-0022 M3a and M3b approved; the r5 review's deferred P3s settled at entry: Y5 (raw-Data refusal as new behaviour, stated with its consequence), Y6 (governor params' Data mapping and closed type list), Y7 (a keyed `@Params` fixture contract and a large-param contract for the 15,000/15,001 pair, both needed by this milestone's exit; the final M3b host and its `dev-host-1` schemas pinned as the protocol-1 oracle (X46); and, from r9, owned and keyed `@Quorum` fixture contracts whose object council differs from the parent's), Y8 (Q2/Q4 options and an `outsideProject`-style code for external governors), Y9 (`cosigners` for a key holder who is not the caller, on both paths); and from the Fable final review: F-Y1 (an application vector for an asset-aware script such as Stall, with an oracle for scripts Aiken's binding cannot decode), F-Y5 (F7 and D4's artifact rule re-verified at the JuLC and CCL versions JOUST then pins, recorded with the vectors) | application vectors agree across JuLC, Aiken (or the stated oracle) and the kit; the input-domain boundary vectors give the same answer on both hosts; Treasury's non-governed calls served and checked on both hosts, including the quorum-eligibility vectors; the legacy-session, old-client and capability-gating vectors (D7, D8) pass; the keyed-restore vectors pass; I16, I20, I21, I22, I23 |
| **M4b** | Key-credential `@AuthorizedBy` (D4, D5 signers) | M4a | key vectors and mutations on both hosts, **including the non-caller key-holder positive** (X26); capability `authorizedBy:key`; I17, I19 |
| **M4c** | Plutus governors (D4, D5, D6), fingerprint version 2 with capability `fingerprint:2` and the lock-file binding (D4), and the npm build writing or emptying `validators.json` (D4, F21) | M4b; **Q6 decided** and, if it requires evaluation, the client's evaluation rule written into D5 | Treasury + Guardian recorded on a fresh DevKit (registration, `freeze`, payer ≠ owner); every D5/D8 mutation rejected with its code; the stale-manifest vectors pass; capability `authorizedBy:script`; I18, I19 |

## 4. Invariants

| ID | Invariant | Decisions | Test |
|---|---|---|---|
| I16 | The client's and the host's applications of params produce byte-identical applied scripts and hashes for every application vector | D2, D3, D8 | application vectors (JuLC, Aiken, kit) |
| I17 | A key-credential call is accepted only if the credential's key, from the client's own params, is a required signer that is the caller or a named co-signer | D4, D5 | M4b vectors |
| I18 | A governor call is accepted only with exactly one zero withdrawal from the governor anchored by the client's params, its V3 script in the witness set, and exactly one `Approvals` naming this policy, instance and action at that withdrawal's pointer; every other Rewarding redeemer belongs to an allowed verifier withdrawal that the call's claims require, and decodes as that verifier's claims type | D4, D5 | M4c mutations |
| I19 | The body's required signers equal exactly D5's signer set | D5 | M4b/M4c mutations, payer ≠ owner |
| I20 | Reservations exclude out-refs across all parameterizations in a session; operations are identified by `{designated, policy, method, instance, key}`; a keyed operation is retried only when R holds for its request's params; every restored body with keyed tokens is never discarded and keeps its out-refs and keyed tokens until final inclusion or margin-qualified expiry; a recordless replay of a body the host already holds live keeps the held entry; a recordless body named in another entry's attempts restores as an attempt of that operation, in either order; other pending transactions restore as under ADR-0022 | D2 | identity, keyed-restore and rewind-and-replay tests |
| I21 | A protocol-1 session with a host that ships this ADR is indistinguishable from one with an M3 host: apart from `fingerprint` and `versions` (checked against the launch), every reply field, the capability list and every error code (per-method precedence included) equal the final M3b host's, every reply validates against the unmodified `dev-host-1` schemas, and a client that sends no fingerprint version gets ADR-0022's digest on every project | D1, D2, D4, D7 | legacy-session (differential) and old-client vectors |
| I22 | Both hosts accept exactly D2a's input domain for `params` and governor params, formats and the raw-Data constructor-index cap included, refusing the rest with the same code and path; neither serves, advertises or describes as served a parameterized contract whose description lacks the `formats` marker | D2, D2a | input-domain boundary and format vectors; mixed marked/unmarked session |
| I23 | A `@Quorum` call is accepted only if at least `threshold` distinct required signers are in `keys`, both read by the client from its own params or from the input record of the object whose method runs (by call kind; `removed` for `keyedRemove`); both hosts prepare exactly the protocol-2 quorum call kinds | D5 | quorum-eligibility vectors (instance, owned, keyed, removal), through both dispatchers |

## 5. Alternatives considered

- **Session-scoped params** (`deployment` pins them): fewer bytes per call, but a hidden state every method must
  mirror. Rejected for D2's stateless form.
- **The host reports the applied hash and the client trusts it:** exactly T12. Rejected.
- **The client supplies the applied script:** the client must apply anyway to check it. Rejected.
- **The host infers governor signers from the governor's params:** works for Guardian only. Rejected (D4).
- **Reference-script governors now:** a second delivery form with its own prerequisite and checks; deferred (D4).
- **Credentials in state or owned fields:** the engine's dispatcher does not serve them (F1); both hosts must match.
- **Native-script governors now:** no runtime path builds them (F16); deferred.

## 6. Consequences

- A second protocol version: hosts and clients carry `dev-host-1` and `dev-host-2` schemas, and protocol 1 stays
  frozen (D1).
- Treasury-like contracts become usable from an IDE; IntelliAda's run panel gains `@Params` input and a governor
  picker over the snapshot's validators.
- Clients implement plutus-core Data encoding and UPLC param application (D3); the kit's vectors make it testable.
- Under **fingerprint version 2**, the npm host's launch digest covers `validators.json` and `joust.lock.json` (D4);
  version 1 is unchanged. The npm build always writes `validators.json` (D4, M4c).
  The keyed operation record gains `policy` and `template`, and a parameterized keyed restore entry `params`; an
  operation made for a since-rebuilt template restores as retired and cannot be retried (D2).
- Clients count `@Quorum` eligibility from their own reads (D5), for M1b's quorum calls too.
- Params descriptions gain a `formats` marker and per-component `format` (D2a); `@Params` contracts built before M4a
  must be rebuilt before a dev host serves them.
- The HTTP prepare service is unchanged.

## 7. Compatibility

Old clients are unaffected: they offer protocol 1, and a protocol-1 session with a host that ships this ADR is an
M3 session (D1, I21), so a client validating replies against the closed `dev-host-1` schemas keeps working,
Treasury-like contracts included (they stay `unsupported:"params"`). A **new** client offers `[1, 2]`; against an
M1–M3 host it gets protocol 1 and must then send none of the new fields. Within protocol 2 each field is gated by a
capability (D2), and `fingerprintVersion` by the installed package version as well (D4), because shipped hosts
refuse unknown fields (`bad/request`). The npm fingerprint is versioned (D4): a client that sends no
`fingerprintVersion` gets ADR-0022's digest on every project, including one with `validators.json`, and no script
governors (I21). In protocol 2 a restore entry may carry `params`, and a keyed `operation` `policy` and `template`
(D2). The `@Quorum` eligibility count (D5) tightens what an ADR-0022 client accepts for M1b's quorum calls; an honest
host's bodies already satisfy it.

## 8. Open questions

- **Q5. Governor registration and Conway's deprecations** (F-Y7): D5 accepts only the legacy kind-0 stake registration,
  which the Conway CDDL marks for deprecation, and the deposit it pays cannot be reclaimed through the dev host (no
  deregistration path is served). Options when kind 0 goes: a kind-7 registration with the governor's Certifying
  witness (needs governors to approve their own registration, against ADR-0009 D3 today), or registration outside the
  dev host. Lean: decide with ADR-0009's next revision.

- **Q6. Must the client evaluate a governor call before signing or exporting it?** (X21, T17; blocks M4c.) Signer-set
  equality (D5) cannot show that `governor.signers` satisfy an arbitrary governor's rules (Guardian's `freeze` named
  with a signer other than its applied `owner` passes every structural rule). ADR-0022 D13 does not evaluate scripts
  for any call: it bounds the collateral a failing body forfeits and relies on honest hosts evaluating (ADR-0022
  D6a). Options:
  - **(a) Evaluate script-governor calls (M4c).** The client runs phase 2 on the exact transaction with inputs
    resolved through its own node, its node's cost models and the body's declared budgets, and refuses on failure
    or on budgets above the declared ones. Cost: every client ships a Plutus evaluator (IntelliAda can use JuLC's VM
    on the JVM; npm clients have the engine's), and the kit gains evaluation vectors.
  - **(b) Evaluate every script-running call** (amends ADR-0022 D13 for M1–M3 calls too): closes T17 everywhere, at
    the same cost, plus re-certifying the M1–M3 clients.
  - **(c) Structural only, as ADR-0022 today:** accept the bounded collateral loss for governor calls too; the
    client shows the governor, its params and signers before signing.
  - Lean: **(a)** now, since a governor is the one call kind where the client cannot reason about the script at all,
    with (b) as a separate ADR-0022 amendment if IntelliAda's evaluator proves cheap.
- **Q2. Native-script governors** (F16): later, when a runtime path builds them.
- **Q3. Governor discovery.** Should `describe` list the snapshot's plain validators (qualified name, purpose, params)?
  Lean yes, as a convenience; the client still anchors on its own read.
- **Q4. Reference-script governors** (was Q1 in r1, now decided inline-only for protocol 2): a later extension for
  governors too large to attach.

## 9. Revision history

- **r1** (2026-11-21): initial version, after the maintainer chose "ADR amendment first" for ADR-0022 Q7.
- **r2** (2026-11-21, responds to the Codex review of `65d766d7`):
  - X1 → D3 (exact encoding, application and hashed bytes, address with `stakeSelf`, size limit), D2 (`params` bytes
    in `deployment`), F5, F6;
  - X2 → D4 and D5 (governor witness mandatory, reference-only refused, `txSize`), Q1 decided and moved to Q4;
  - X3 → D5 (all allowed withdrawals by full reward-account bytes, every pointer checked by purpose), D8, I18;
  - X4 → D5 (signer set per F9), F9, D8, I17, I19;
  - X5 → D4 (qualified selector, npm digest includes `validators.json`, V3 withdraw only), F7, F14, D8;
  - X6 → D2 (session-wide out-ref exclusion, deployment-keyed listings, `policy` in the keyed operation), F15, I20;
  - X7 → D6 (prerequisites and publish for governors, governor-specific registration, mandatory bound), D5;
  - X8 → D4 (the JVM path through generated clients and `Authorization`; the HTTP service unchanged), F12, F13;
  - X9 → D1 (distinct capabilities), D7 (behaviour table, `describe`);
  - X10 → D8 (zero- and multi-param governors, malformed params, size boundary, the T14 unit test), D9 (entry gates);
  - X11 → §1 (per-row pins), F1, F7, F10, F11, F13 narrowed or corrected.
- **r3** (2026-11-21, responds to the Codex round-2 review of `983c33b3`):
  - X12 → D3 (map normalization per F17, raw Data refused unless normal, size measured on the decoded outer `cborHex`,
    `refusal/scriptSize`), D8 (15,000 / 15,001 pair, out-of-order and duplicate maps), F17;
  - X13 → D5 (every Rewarding redeemer decoded against its purpose's type: `Approvals`, `OutputClaims`, `MapClaims`;
    verifier withdrawal present exactly when claims are), D7 (`verifierRedeemer`), D8, I18, F18;
  - X14 → D4 (the npm manifest implies V3; the witness language is checked in D5), D8 (before/after `initialize`);
  - X15 → D2 (restore entries carry `params`; policy checked against `operation.policy` and the body), D8, I20;
  - X16 → D6 (eligible governors derived from the description and params), D8;
  - X17 → D4 (fingerprint version 1/2, version 1 serves no script governors), D7, §7, D8, I21;
  - X18 → D8 (shared verifier as governor end to end; only the self-hash stays a checker-boundary unit test).
- **r4** (2026-11-21, responds to the Codex round-3 review of `a3d91454`): X19 → D2 (the restore binding applies to
  parameterized keyed operations only; other entries restore by ADR-0022 D8's path), D8 (positive restarts of a
  pending governor registration and a pending reference-script publication), I20; the §6 carryover now says
  fingerprint version 2.
- **r5** (2026-11-21, responds to the independent design and security review of `85f4c3cc`; design changes
  requested, security approved):
  - Y1 → D4 (`fingerprintVersion` npm-only, decided from the installed package's version, capability `fingerprint:2`,
    fresh process on `bad/request`), D2 (fields sent only after their capability), D7 (new-client rows), §7;
  - Y2 → D2 (`initialize` reports `parameterized: true` under `params`; `describe` follows each method's code;
    `deployment.governors` stays empty), D7, I21 restated;
  - Y3 → D4 (the client's artifact rule for Gradle and npm, with its refusals), D8 (a vector per clause);
  - Y4 → F5 (JuLC path and tag), F7 (`scriptHash`, `built`), F12 (the params entry points), D4 (the JVM path's
    reflective codec normalizes maps before `deploy`/`at`), D8 (Aiken binding version);
  - Y10 → D2 (mirrors, overlays and `mapsStale` keyed by the applied policy; restored entries by the body's policy);
  - Y11 → D5 (kind-0 registration with no redeemers, scripts, collateral or reference inputs);
  - Y12 → §2 (the withdraw-zero liveness limit and its recovery);
  - Y13 → D4 (the client's half of the version-2 binding);
  - Y5–Y9 left for M4a's entry review (listed in the PR reply).
- **r6** (2026-11-21, responds to the design and security round-2 review of `289ff3be`, both approved): Y14 →
  D4 (`fingerprint:2` ships with M4c), D9 (M4c scope), D2 (each gated field names its capability), I21 restated
  exactly; Y15 → status line, D9 M4a entry lists the deferred Y5–Y9; Y11 → D8 (registration mutation vectors).
- **r7** (2026-11-22, responds to the Fable final review of `05ea594b`, approved with P3s): F-Y1 → D3 step 2 (opaque
  builtin tags), D9 M4a entry (asset-aware vector, oracle); F-Y2 → D4 (the client's half is npm-only; `fingerprint:2`
  informational); F-Y3 → D9 M4a exit (I21 and the gating vectors); F-Y4 → F6, F8 (pins and the re-recording); F-Y5 →
  D9 M4a entry; F-Y6 → D5 (pointer order defined independently of the wire order); F-Y7 → Q5; F-Y8 → D3 step 1.
- **r8** (2026-11-22, responds to Codex review of `2ac93789`; its seven findings are numbered X20–X26 here):
  - X20 (closed M3 schemas) → D1 (everything here is protocol 2, negotiated by the existing protocol list; a
    protocol-1 session is an M3 session), D2 and D4 (fields and `fingerprintVersion` protocol-2 only), D7, D8
    (legacy-session vector), I21 restated, §7, F19;
  - X21 (set equality is not approval) → D5 (`@Quorum` eligibility count, also for M1b's quorum calls; key branch
    covered by I17; `is_valid` is not evidence), Q6 (client evaluation of governor calls, open, blocks M4c), D7
    (`quorum`), D8, D9, I23, T17;
  - X22 (keyed restore without its record) → D2 (structural keyed test, refusal before the batch commits), D7, D8,
    I20, T18, F20;
  - X23 (template rebuild) → D2 (`operation.template`; retired operations keep their reservation and can never be
    retried), D7, D8, I20, T18;
  - X24 (stale `validators.json`) → D4 (the build writes `[]` and removes stale scripts; version 2 digests
    `joust.lock.json` and uses only entries the lock lists), D7, D8, D9 M4c, T16, F21;
  - X25 (two params codecs) → D2a (one input domain checked before either adapter), D4, D7, D8, I22, F22;
  - X26 (non-caller key holder) → D5, D8 (M4b positive), D9 M4b exit.
- **r9** (2026-11-22, responds to Codex review of `b24b35db`; its six findings are X27–X32; it confirmed X26 and
  Q6's gate):
  - X27 (an honest non-keyed `0x4B` mint blocked restore) → D2 (no refusal: an entry without its record restores by
    D8's path with its out-refs and keyed tokens held until it settles or expires), D7, D8 (mixed-project vector), I20;
  - X28 (relabelling took the rebuild path) → D2 (persisted params tried against the other catalog templates first;
    a retired operation's cost bounded by its validity), D7, D8, I20;
  - X29 (quorum source for owned and keyed calls) → D5 (keys and threshold by call kind, from the spent object's
    input record), D8 (owned and keyed vectors, fixture contracts), I23;
  - X30 (fixed-width params components) → D2a (params integers are arbitrary-precision; `SubsetLint` extended to
    params records wherever declared), D7, D8 (processor tests, `BigInteger` boundaries), I22;
  - X31 (lock matched by name only) → D4 (`validatorsDigest` of the manifest bytes, lock written last; older builds
    serve no script governors), D7, D8;
  - X32 (`fingerprint:2` and error precedence in protocol 1) → D1 (M3 answers with M3 precedence; no protocol-2
    capabilities), D4 (`fingerprint:2` in protocol 2 only), D7, D8 (differential legacy vector), I21.
- **r10** (2026-11-22, responds to Codex review of `e8f8f2b1`; its eight findings are X33–X39
  here, one of them reopening X30):
  - X33 (a recordless or retired entry could be discarded, and release was tied to `validTo` alone) → D2 (such
    entries are possibly handed out: `discarded` refused, end only by final inclusion or expiry beyond `validTo +
    settlementSlots`), D7, D8, I20;
  - X34 (catalog names cannot prove a relabelling) → D2 (identity is the applied policy; anything the build cannot
    re-derive is retired, never refused; the r9 relabel refusal is withdrawn), D7, D8 (legacy alias, same-template
    names), I20;
  - X35 (`String` params decoded lossily on the JVM) → D2a (`utf8` format, strict on both hosts), D7, D8;
  - X36 (`keyedRemove` quorum source and host parity) → D5 (`removed` input; M4a aligns served quorum kinds), D8,
    I23;
  - X37 (an interrupted build after `contracts.json`) → D4 (`buildDigests` over `contracts.json` and
    `validators.json`), D7, D8;
  - X30 reopened (pre-M4a artifacts) → D2a (the processor writes a `formats` marker and per-component `format`; the
    r9 compile-time refusal is withdrawn; unmarked descriptions are not served), Risk line, D7, D8, I22;
  - X38 (protocol-1 precedence differs by method) → D1 (per-method precedence as M3 validates it), D7, D8;
  - X39 (whole-reply equality impossible across releases) → D1 (`fingerprint` and `versions` checked against the
    launch, the rest compared), D8, I21.
- **r11** (2026-11-22, responds to Codex review of `0fd6e440`; its seven findings are X40–X46; it confirmed the
  removal-quorum source and the three protocol-1 precedence examples):
  - X40 (a partial attempt history let a restored body be discarded) → D2 (every restored body with keyed tokens is
    possibly handed out, with or without a record), D7, D8, I20;
  - X41 (a validator-only rebuild left the old lock valid) → D4 (the build removes the old lock first and writes
    the new one last), D7, D8;
  - X42 (policy is not an operation identity; two template rules) → D2 (identity `{designated, policy, method,
    instance, key}`; one predicate R for restore and retry), D7, D8, I20;
  - X43 (boxed and other integer types) → D2a (`int32` for `int`/`Integer`, `int64` for `long`/`Long`, at any
    depth; no marker, with a warning, for anything else), D8;
  - X44 (marker per contract in `initialize`/`describe`) → D2, D7, D8 (mixed marked/unmarked session), I22;
  - X45 (quorum kinds) → D5 (the six protocol-2 kinds, both dispatchers; `keyedRetire` excluded as the processor
    refuses it), D7, D8, I23;
  - X46 (baseline predates maps) → D1 (the final M3b host is the oracle), D8 (a map contract in the legacy
    session), I21.
- **r12** (2026-11-22, responds to Codex review of `107ec9dc`; it confirmed X40, X43, X45 and X46 and Q6's gate; its
  four findings are X47–X50):
  - X47 (a protocol-1 record after its contract gains params) → D2 (records without `policy` recognized by shape;
    retired when not re-derivable, never refused), D8;
  - X48 (overlapping npm builds) → D4 (builds serialized per output directory by an exclusive marker), D8;
  - X49 (D7 rows ignored the per-contract marker) → D7 (`initialize` and `describe` rows qualified), D8;
  - X50 (no governor signer limit) → D4 (64 distinct keys across `governor.signers` and `cosigners`, before any
    account is built), D8.
- **r13** (2026-11-22, responds to Codex review of `05a192eb`, which confirmed X49 and X50; its three findings are
  X51–X53; no Codex review of r13 was run, the round budget being spent):
  - X51 (version 1 dropped the M3b launch closure) → D4 (version 1 is the final M3b file set and ordering; version 2
    adds two files to it), F14, D8;
  - X52 (a renamed legacy record still hit the catalog-name check) → D2 (retirement takes precedence when the name
    no longer re-derives the policy), D8;
  - X53 (a crash between marker and lock removal) → D4 (an outstanding `.joust-build` disqualifies script
    governors on host and client), D8.
- **r14** (2026-11-22, responds to Codex review of `c47909cf`, which confirmed X51–X53; its two findings are X54–X55):
  - X54 (a recordless replay of a held body after a rewind failed the merge) → D2 (same body, state and slot without
    a record keeps the held entry, under the discard guard), D7, D8 (rewind-and-replay vector);
  - X55 (raw Data constructor indices above 2^63 − 1) → D2a (cap at any depth in the shared prevalidation), D7, D8
    (differential at 2^63 − 1 and 2^63).
- **r15** (2026-11-22, responds to Codex review of `fe518c24`, which confirmed X54 and X55; its one finding is X56):
  - X56 (a recordless attempt blocked restoring its retry) → D2 (a recordless body named in another entry's
    attempts, with matching `validTo` and designated input, restores as an attempt of that operation; an
    inconsistent record is retired; both-records identity check unchanged), D7, D8 (both orders), I20.
