# ADR-0023: The dev host serves `@Params` contracts and `@AuthorizedBy` calls (ADR-0022 Q7)

**Status:** Proposed (r19). Design only.
**Date:** 2026-11-21
**Amends:** ADR-0022 (a dev host): its Q7 ("which milestone serves governors, and how the governor's applied script,
reward address, redeemer and signers are pinned per deployment"), its D16 rows for `@AuthorizedBy` and `@Params`, its
R3, its D3/D4 (the `initialize` protocol list: this ADR is protocol 2), D13 (including rule 3 for every `@Quorum`
call), D14 and I7 (the npm host's `validators.json` is data outside the fingerprint, bound by hash, D4) where stated.
Everything else in ADR-0022 holds, as revised by its r8 (D8's one content-addressed map node store; D11's keyed
retries resting on the designated input alone; `restore` without operation records).
**Requires ADR-0022 r8** (r19): r8 is on the dev-host stack (PR #156 and above), not in this PR's base
(`docs/adr-0022-dev-host`, PR #142, which carries r7). This ADR merges only after r8 is on its base, or with it, and
M4a's entry gate requires it (D9).
**Builds on:** ADR-0009 D3 (`@AuthorizedBy`, the `Approvals` binding, the governor's obligations), ADR-0013 phase 3
(`@Params` in the prepare service, R3), ADR-0015 M3 (governors from a page, `validators.json`).
**Decided by the maintainer (2026-11-21):** Q7 gets an ADR before any implementation. **(2026-11-23):** Q6, option
(a): the client evaluates phase 2 of a script-governor call before signing or exporting it (D5); every other call
keeps ADR-0022 D13's structural checks. **(2026-11-23):** the chain provider, the wallet and the client's journal are
trusted, as in any CCL or MeshJS app; only the host (like the prepare service) is not. r18 simplifies to that model.
**Facts** are pinned per row: `main` `d529bfd0` for the runtime, JS, processor and examples; PR #156's head `4335773d`
(ADR-0022 M3a) for the hosts; `4b599e6b` (PR #156's final head, ADR-0022 r8) for the keyed and restore rules this ADR
relies on (F9, F15). `DescribedContract.java` and `StateContractHandle.java` differ between the first two pins; the
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
| F9 | A body's required signers: the caller's key **only when** the method uses `Ctx` or `@SignedBy`, or the call is a keyed insert of a type declared `signedKey` (ADR-0016 I53: the inserted key must be a signatory); then every extra signer (co-signers, governor signers), deduplicated. The payer still witnesses its own inputs | `StateInstance.java:199-207`; `DescribedContract.java:310-313` at `d529bfd0`; `KeyedList.java:344` at `4b599e6b` |
| F10 | JOUST's page verifier, for a governor call: verifiable only when the credential is in the deployment's params; exactly one script credential; instance actions only; the governor's hash equals the params' credential; exactly one Rewarding redeemer **at the governor's withdrawal index**, equal to `Approvals[Approval(policy, instance, tag)]`. It does **not** examine a Rewarding redeemer at another reward index | `verify.js:124-192`, `:485-509`, `:515-536` at `d529bfd0` |
| F11 | The page's governor registration check: one pure registration of exactly the governor's script credential, the protocol deposit, wallet-only inputs and outputs, no withdrawals, mint or redeemers; it counts Plutus witness scripts and **allows an absent TTL** | `governors.js:56-114`; `ledger.js:101` at `d529bfd0` |
| F12 | Generated clients take the params record (`deploy(runtime, Config, …)`, `at(runtime, Config, instanceId)`) and an `Authorization` argument for `@AuthorizedBy` methods | `ClientGenerator.java:99-106` (`contract(runtime, P params)`, `policyIdFor`), `:120-121` (`deploy`), `:179` (`at`), `:731`, `:758` (`Authorization`); `TreasuryClient` (generated) |
| F13 | The JVM host's adapter refuses `@Params`, looks contracts up without params, and accepts extra accounts only for `@Quorum`; `Contracts.code` returns the contract-wide `params` before any method's `authorizedBy`, so Treasury's `freeze` reports `params` today | `ServedContract.java:69-70`, `:117`, `:147-149`, `:258`; `Contracts.java:37-42`, `:78`, `:94` at `4335773d` |
| F14 | The npm host fingerprints and reads `contracts.json` and the two engine files (and, from M3b, its launch closure); it neither fingerprints nor reads `validators.json` | `js/joust/src/host/launch.js:32-51` at `4335773d`; `:28-38` at `2ffaf41b`; `:28` at `4b599e6b` |
| F15 | ADR-0022 D8 excludes live out-refs across the whole session; ADR-0022 D11's persisted keyed operation is `{designated, contract, method, instance, key, attempts}`, with no policy; D11's rules 3 (`pending` on a reserved designation or object unit) and 4 (`mayHaveLanded` when the designation is spent) are checked per call, and a body's keyed tokens are recognized structurally (by the holding output's payment script hash), so an applied policy is covered | `Reservations.java:23-29` (`Body.keyedTokens`), `:144-146`, `:172` (`avoids`), `:193-195` (`checkOperation`); `KeyedOperation.java:11-12` at `4b599e6b` (ADR-0022 r8) |
| F16 | ADR-0009 D3 refuses a governor equal to the contract's own script or either shared verifier, and allows native-script governors; no runtime path builds a native governor (F2) | ADR-0009 `:77-100` |
| F17 | The schema codec sorts a typed map's entries by their keys' CBOR in RFC 8949 length-first order and refuses duplicates; JuLC's encoder and the JVM adapter's JSON values keep the order given. Reversing two map entries changes the applied hash (#157 review X12) | `SchemaCodec.java:118-132` at `d529bfd0` |
| F18 | The output verifier's Rewarding redeemer is `OutputClaims(claims, deposits)`, the map verifier's `MapClaims(claims)`; the builders omit a verifier's withdrawal when its claim list is empty | `julc-state-api/.../OutputClaims.java:12`, `.../mpf/MapClaims.java:7`; `AssetTx.java:258-262`; `Compose.java:210-223` at `d529bfd0` |
| F19 | Protocol 1's result schemas are **closed**: `initialize`, its `contracts` items, `describe`, its `calls` items and `deployment` all set `additionalProperties: false`, and `initialize.protocol` is `const 1`. Both hosts share one `Session`, which accepts any list of integer protocol versions containing 1 and answers protocol 1 (`no common protocol version` otherwise): offering `[1, 2]` to an M1–M3 host selects 1 | `docs/protocol/dev-host-1/results.schema.json`; `Session.java:95-104`, `:120`; `EngineSession.java:34` at `4335773d` |
| F20 | *(withdrawn in r19: it described a `restore` record check that ADR-0022 r8 removed)* | — |
| F21 | The npm build writes `validators.json` (and each `<name>.plutus`) **only when** the build has validators, and never removes an earlier one: a rebuild that drops the last validator leaves the old manifest in place. It clears only `generated/` | `js/joust/src/build.js:115`, `:164-177` at `d529bfd0` and `4335773d` |
| F22 | The two codecs differ on integers and limits: `JsonValues` (JVM adapter) accepts a decimal string of 1–200 digits or a JSON integer with \|n\| ≤ 2^53, and caps collections at 1,024 items, nesting at 32 and byte strings at 16,384 bytes; `SchemaCodec` (engine) accepts a decimal string of any length and any JSON integer, with no collection limit | `JsonValues.java:37-38`, `:68`, `:90`, `:97`, `:149-157` at `4335773d`; `SchemaCodec.java:170-173` at `4335773d` |

## 2. Threat model and trust assumptions

ADR-0022 §2 holds: the host is no more trusted than the prepare service; the client checks every transaction before
signing (D13). The chain provider (the client's node), the wallet and the client's own journal are **trusted**, as in
any CCL or MeshJS application (ADR-0022 D11 r8); defending against a lying provider is out of scope
(bloxbean/joust#169). New here:

- **T12. Wrong parameters, right shape.** The host builds a perfectly shaped call to a *differently parameterized*
  contract (another guardian, another limit). Only a client that **derives the applied hash itself, from bytes it
  encoded itself** (D3), sees it. Two encodings of the same values are two contracts (F5).
- **T13. Borrowed approval.** A governor approval for another instance, action or contract, or approval data placed
  at another reward pointer (F10). Extra approvals in the list are inert: no other script that could consume them
  runs (ADR-0022 D13).
- **T14. A governor that approves everything:** the contract's own script or a shared verifier as governor (F16).
- **T15. Extra signers:** governor signers or co-signers smuggling a required signer the user does not expect.
- **T16. Wrong governor artifact:** two validators with one simple name, or a manifest that is stale, changed or
  half-written (F7, F14, F21). The governor is bound by its applied hash (D4, D5), so a wrong artifact can only make a
  call fail closed. One residual is a build matter, not a host attack: a client that derives a credential for the user
  at deployment from a stale build deploys a contract governed by that stale governor. The client shows the hash it
  derived, and freshness is the build's job (F21), as for `contracts.json`.
- **T17. A well-shaped approval that cannot execute** (X21): signers that pass a set-equality check but are not
  eligible (an outsider in a quorum, a signer other than the one a governor's rules require). The ledger rejects it in
  phase 2 and the submitter forfeits the collateral. ADR-0022 D13 bounds that collateral but does not evaluate scripts;
  D5 counts quorum eligibility from the client's own reads, and, for script-governor calls, has the client evaluate
  phase 2 itself (Q6, decided (a)). Other calls keep the bounded-collateral exposure ADR-0022 accepts.
- **T18.** *(withdrawn in r18.)*

**Accepted liveness limit** (Y12): Conway requires a withdrawal to equal the reward account's whole balance, and
anyone can credit a registered account (a proposal's deposit return, a treasury withdrawal naming it). A governor
whose account holds a balance makes every zero withdrawal fail at the ledger, so its calls fail (closed) until the
governor's owner sweeps the account outside the dev host. ADR-0022's shared verifiers share this property.

**Trust assumption (unchanged in kind):** a governor is project code, like the contract. JOUST cannot verify that it
lists only the approvals its rules grant or refuses certificates (ADR-0009's obligations); the client shows which
governor authorises the call, by hash, and by qualified name where it resolves the artifact (D5).

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
  (no `params` or `authorizedBy:*`), a parameterized contract is `unsupported:"params"`, `describe`
  adds nothing, and a request carrying a field this ADR adds gets the M3 host's answer **with its per-method error
  precedence** (X32, X38). Protocol-1 sessions run M3's request validation unchanged, and its order differs by
  method at `4335773d`: `Session.query` (`deployment`, `view`, `instances`, `objects`) checks the contract's support
  first, so Treasury's `deployment` with `params` is `unsupported/params`; `prepare` checks the call's field list
  first and `decode` its fields before the contract lookup, so Treasury's `prepare` with `call.params` and `decode`
  with `params` are `bad/request`. **The oracle is protocol 1's own conformance material** (r19, replacing r10–r11's
  pinned M3 host binary, X39, X46): the `dev-host-1` conformance kit and result schemas of the final M3b release, run
  unchanged against a host that ships this ADR and is offered `[1]`, plus the three precedence examples above as
  plain vectors. `fingerprint` and `versions` are checked against each launch (ADR-0022 D4: they name the actual
  snapshot and runtime). M3b adds `maps` to `capabilities` and `assetHistory`, `redeemers` and `datum` to `reads` for
  map contracts, so the rules above cite `4335773d` (M3a) for the precedence they quote, and M4a re-verifies that
  precedence at the final M3b head.
- The alternative, a client opt-in field in `initialize`, fails because M1–M3
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
- **Identity:** listings, caches and decoded views are keyed by the **applied policy**. MerkleMap nodes need no
  key: ADR-0022 D8 (r8) keeps one content-addressed node store per session, every lookup starting from a root a chain
  datum names. **Reservations are not keyed by policy:** ADR-0022 D8's out-ref exclusion stays session-wide, so two
  parameterizations can never reserve the same payer input or collateral (F15).
- **Keyed calls follow ADR-0022 D11 (r8) unchanged** (r18). The persisted `operation` stays `{designated, contract,
  method, instance, key, attempts}`, and its identity must equal the call's (F15). An instance id derives from its
  seed out-ref (`StateContractHandle.instanceIdOf`), and a seed is spent once, so an instance exists under one applied
  policy only; every attempt spends the designated input, so the ledger lets at most one land. The host keeps no
  attempt history and does not check the client's journal against itself: the chain provider, the wallet and the
  client's journal are trusted (§2).
- **Restore** is ADR-0022 D8's, unchanged: transactions and their states, no `params` and no operation record. A
  pending keyed body of a parameterized contract is restored like any other; its designated input is one of its
  inputs, so it is reserved again (ADR-0022 D11).
- **Withdrawn in r18** (r2–r17's keyed-record and restore rules, written before ADR-0022 r8 removed the record from
  `restore`): `operation.policy` and `operation.template`, the template predicate R, retired operations, a restore
  entry's `params`, the restored-body discard guard, the link, retirement and attach rules for recordless bodies, and
  the per-policy `mapsStale` scope (X19, X22, X23, X27, X33, X34, X40, X42, X47, X52, X54, X56–X58, X60, X61; Y10).
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
  protocol-2 session a client sends `params` only after `initialize` advertised `params`, and `governor` only after
  `authorizedBy:script`. A host refuses a field whose capability it did not advertise
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

Under the trust model the formats buy identical refusal codes on both hosts, not safety: D3 and the client's
comparison of `deployment.params` with its own bytes already fail a codec divergence closed. **If M4a proves heavy,
this is the first cut** (r19): serve only params records of `BigInteger`, `byte[]`, `boolean`, records, lists and
maps, and report the rest `unsupported/params`.

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
- **The governor artifact is bound by its hash, not by the fingerprint** (r18; replaces r2–r17's fingerprint
  version 2, X17, X24, X31, X37, X41, X48, X51, X53, Y13). The host resolves the validator (below), applies
  `governor.params` (D3) and refuses a result that does not hash to the script credential in the contract's params;
  the client requires exactly one witness script hashing to that credential and evaluates it (D5). A manifest that
  changed, went stale or was half-written can therefore only make a call fail closed (`bad/governor` at the host,
  `governorWitness` at the client), never authorise another script. So ADR-0022 D4's fingerprint is unchanged: no
  `fingerprintVersion`, no `fingerprint:2`, no lock-file digests and no build marker. One build fix stays (M4c, F21):
  the npm build writes `validators.json` on every successful build, `[]` when there are no validators, and removes
  stale `<name>.plutus` files, so a removed validator is no longer found.
- **Where the host finds the artifact** (T16; Y3, reduced to the host in r19): Gradle,
  `META-INF/plutus/<SimpleName>.plutus.json` beside the defining class, as `ValidatorScripts` resolves it, refusing
  ambiguity (F7); npm, the one `validators.json` entry with that `{package, name}`. Ambiguity, a missing entry or a
  purpose other than `withdraw` is `bad/governor`; any other mis-resolution surfaces as the hash mismatch above.
  Clients do not resolve the artifact to check a call (D5).
- **`validators.json` on the npm host** (X63; r19): a protocol-1 session never reads it (I21). In protocol 2 the host
  reads it at `initialize` as data **outside ADR-0022 D4's fingerprint** (amends ADR-0022 I7: the file is bound by
  hash, D5, not by the digest). An absent file is an empty catalog (builds before M4c write none, F21); a file that
  does not parse fails only the requests that name a `governor` (`bad/governor`, naming the file), never
  `initialize` or any other request. The host keeps the copy it read, so after a rebuild its `bad/governor` message
  says to restart the host.
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
`{caller, if the method uses Ctx, @SignedBy or @Quorum, or the call is a keyedInit of a keyed type declared
signedKey (ADR-0016 I53; X62)}` ∪ `{named co-signers}` ∪ `{the key credential, for a key call}` ∪ `{governor.signers,
for a script call}`. Wallet witnesses (the payer's inputs and collateral) are separate and
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
- **Script governors: the client evaluates phase 2** (Q6, decided (a) by the maintainer on 2026-11-23; X21, T17).
  Whether `governor.signers` satisfy a governor's rules is known only by running it: a governor is opaque project
  code (§2). So before it signs or exports a script-governor call, the client evaluates the **exact transaction**
  itself:
  - **what it runs:** every script the transaction executes (the contract's spend, the governor's withdrawal, any
    verifier's), with the body bytes as received (ADR-0022 D13 rule 1) and the witness set's scripts, redeemers and
    datums;
  - **trusted inputs:** spent and reference inputs resolved through its own node (ADR-0022 D13 rule 3), never the
    host's reads; cost models from its node's protocol parameters, the same it uses for the script integrity hash;
  - **budgets:** each redeemer's declared execution units are its budget. A script that fails, or needs more than its
    redeemer declares, or a transaction whose declared units exceed the protocol's per-transaction limits, is
    refused (`evaluation`);
  - **the evaluator** is the client's own (IntelliAda can use JuLC's VM; npm clients the engine's evaluator). It may
    share an implementation with the host; the independence that matters is of its inputs, cost models and budgets,
    which the host never supplies. D8's evaluation vectors pin its results.
  - **the bytes it evaluates are the bytes received** (X59; tested by a seam test, D8): the evaluator is given the transaction exactly as
    received, never a decoded and re-encoded copy, since a script's `TxInfo` (its transaction id above all) is
    derived from those bytes and a valid body can re-encode differently. **Implementation defect to fix in M4c:** the
    engine's `Offline.evaluate` decodes the transaction (`Transaction.deserialize`,
    `julc-state-runtime-wasm/.../wasm/Offline.java:56` at `2ffaf41b`) and evaluates `tx.serialize()` (`:81`); it must
    pass the received bytes to `JulcTransactionEvaluator.evaluateTx`, keeping the decoded copy for input
    resolution only. The JVM host's own evaluation (`Session.java:169`, `:211`) runs inside QuickTx while it
    builds, on a provisional body whose budgets and integrity hash are not yet final, to compute those budgets;
    that is the host's build step, not D5's check, and it is unchanged. A JVM client evaluates through
    `JulcTransactionEvaluator.evaluateTx(bytes, utxos)`, which takes bytes, and must pass the received ones; M4c's
    entry confirms that JuLC derives `TxInfo.id` from the bytes it is given.
  This applies to script-governor calls only. Every other call, key-credential `@AuthorizedBy` calls and M1–M3 calls
  included, keeps ADR-0022 D13's structural checks, so ADR-0022 D13 itself is unchanged apart from naming governor
  calls as the exception. An honest host evaluates what it builds (ADR-0022 D6a), so an honest body always passes.
- **`is_valid`:** the submitter sets it, outside the body hash; it shows nothing about evaluation, and the kit's
  `is_valid` check is a shape rule only.

**Withdrawals and Rewarding redeemers** (T13, T14, F10). The allowed withdrawals are exactly: the governor's (script
calls), the output verifier's (methods that send, ADR-0022 D13), the map verifier's (map calls, ADR-0022 M3). Each is
compared by its **full reward-account bytes** (header with the session's network tag, then the hash), appears once,
and withdraws zero. Their pointers are their indices in the withdrawals sorted in the ledger's order (Haskell's `Ord` on the reward
account: network tag, then script credentials before key credentials, then the hash bytes), computed by the client
independently of the order the body's CBOR map happens to use (F-Y6); **every** pointer has
exactly one Rewarding redeemer, **decoded by purpose against its own type** (X13):
- the governor's: `Approvals` **containing** `Approval(policy, target, action)` (the applied policy, the instance's
  token name, the method's redeemer constructor index from the client's description); other approvals in the list
  are inert, since no other script that could consume them runs (ADR-0022 D13; r19);
- the output verifier's: `OutputClaims` (F18), with claims for the call's sends as ADR-0022 D13 bounds them;
- the map verifier's: `MapClaims` (F18), as ADR-0022 M3's rules check them.

A redeemer that does not decode as its pointer's type (`Approvals` at a verifier's pointer, say) is refused, as is a
Rewarding redeemer at any other pointer, a duplicate or a missing one. **A verifier's withdrawal is present exactly
when the call produces claims for it** (the builders omit it for an empty claim list, F18): a withdrawal without
claims, or claims without their withdrawal, is refused.

**The governor** (T12, T14, T16; reduced to the hash binding in r19): its hash is the script credential in the
client's own params; **exactly one** inline Plutus V3 script in the witness set hashes to it (missing or
reference-only is refused, `governorWitness`); that hash is not the contract's, the output verifier's or the map
verifier's; no other script but those ADR-0022 D13 allows; and the client's phase-2 evaluation of the transaction
passes (above). The client does not resolve or apply the governor's artifact to check a call: the ledger runs only a
witness script that hashes to the credential, and the client evaluates that exact script. A client may resolve it
(D4's lookup) to show the governor's name; a mismatch only drops the name. Instance actions only.

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
`quorum`, `evaluation`. Observable behaviour (every row but the first two is a protocol-2 session):

| Situation | Answer |
|---|---|
| a client offering `[1]`, or any client of an M1–M3 host | protocol 1: ADR-0022's answers exactly, replies valid against the `dev-host-1` schemas (D1, I21) |
| any field this ADR adds, in a protocol-1 session | the M3 host's answer for that method, with its precedence (D1): e.g. Treasury `deployment` with `params` `unsupported/params`; Treasury `prepare` with `call.params`, or `decode` with `params`, `bad/request` |
| a parameterized contract, `params` absent (any client) | `unsupported/params` |
| `params` present, outside D2a's domain or does not encode exactly | `bad/params`, the same path on both hosts |
| a parameterized contract whose description lacks `"formats": 1` (built before M4a, or reaching an unformatted representation) | `initialize` and `describe` entries `unsupported: "params"`, requests `unsupported/params` ("rebuild"), on both hosts, while marked contracts in the same session are served (D2, D2a) |
| a `@Quorum` call of a kind outside the protocol-2 list | `unsupported` on both hosts (D5) |
| an `int32`/`int64` value out of range, or a `utf8` value that is not well-formed UTF-8 | `bad/params`, the same path on both hosts (D2a) |
| raw `PlutusData` with a constructor index above 2^63 − 1 at any depth | `bad/params` on both hosts (D2a) |
| a key-credential call on a host without `authorizedBy:key` | `unsupported/authorizedBy` ("key credentials") |
| a script-credential call on a host without `authorizedBy:script` | `unsupported/authorizedBy` ("script governors") |
| a governor whose resolved artifact, with `governor.params` applied, does not hash to the credential in the params (a stale, changed or ambiguous manifest included) | `bad/governor` at the host; a body with no witness script hashing to the credential is refused by the client (`governorWitness`) (D4, D5) |
| `validators.json` absent, or not parseable, on the npm host (protocol 2) | absent: no validators; not parseable: requests naming a `governor` `bad/governor`, every other request answered as usual (D4) |
| a client sends `params` or `governor` to a host without the capability | `bad/request` (strict field lists); clients send them only after the capability (D2) |
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
  action or policy (an extra approval besides the right one is accepted); a shifted pointer; missing governor witness and reference-only governor;
  payer ≠ governor owner (positive, F8), caller-as-key with a `@Quorum` co-signer (positive), overlapping keys, a
  missing, extra or substituted named signer; registration of a wrong target, without TTL, already registered, and
  `unknown`.
- **Key holder who is not the caller (X26), positive, both hosts:** Treasury's `freeze` with key credential B, payer
  A and `cosigners: [B]` (no `@Quorum`, `Ctx` or `@SignedBy`): the body requires exactly `{B}`, A witnesses its inputs,
  and the reference checker accepts; with the old quorum-only co-signer gate it would be refused, so the vector fails
  an adapter that kept it.
- **Signed-key insert (X62), both hosts:** `Keys`'s `Pass.issue` (`examples/dsl`, a keyed insert of a `signed` type
  with no `Ctx`) with the caller as holder: the body requires exactly `{caller}` and the checker accepts; the same
  body with that required signer removed is refused.
- **Governor evaluation (Q6 (a), I24), M4c:** an honest Guardian `freeze` (evaluates, accepted); the same body naming
  a signer other than Guardian's applied `owner` (every structural rule passes; refused `evaluation`); a redeemer
  whose declared units are lowered below what the script needs, with the integrity hash recomputed (refused); declared
  units above the protocol's per-transaction limit (refused); a host whose reads disagree with the client's node on a
  spent input's datum (the client evaluates with its node's answer); and a key-credential `freeze` and an M3 call
  (not evaluated: structural checks only, as ADR-0022); and a **received-bytes seam test** on both client paths (the
  engine's `Offline.evaluate` and a JVM client's `evaluateTx` call): for a valid body whose CBOR re-encodes
  differently (an indefinite-length list where the encoder writes a definite one), the byte array handed to the
  evaluator is the one received (X59). r19 replaces r17's end-to-end transaction-id vector, which cannot be built: a
  redeemer cannot hold the id of a body whose integrity hash commits to that redeemer (X64).
- **Quorum eligibility (X21, X29):** a 2-of-3 Treasury council `pay` whose required signers are the caller (a member) and
  an outsider: signer-set equality holds and the checker refuses `quorum`; the same with two members (positive); a
  duplicate member key counted once (refused). For an **owned** `@Quorum` call whose owned record's council differs
  from the parent instance's: signers meeting the object's threshold (positive) and signers meeting only the
  parent's (refused); the same pair for a keyed `@Quorum` call; and a `keyedRemove` whose `removed` node and
  predecessor have different councils (signers meeting the removed node's threshold accepted, the predecessor's
  only refused). Each positive is **prepared by both hosts' dispatchers** and accepted by the checker, the removal
  included (X45); a `keyedRetire` with `@Quorum` fails to build (processor test). M4a needs fixture contracts for
  these (owned, keyed and removal quorum), added to its entry gates.
- **Identity tests (M4a):** two parameterizations competing for one wallet out-ref (refused); two keyed operations
  of two parameterizations naming one designated input (the second `pending`); a pending keyed `@Params` body
  restored after a restart (its inputs, the designation among them, reserved again; a new keyed operation on the
  object `pending`); a restart with a pending **reference-script publication** of a parameterized contract (restored,
  positive, by ADR-0022 D8's path).
- **Governor identity tests (M4c; moved from M4a in r19):** a restart with a pending **bounded governor
  registration** (restored, positive); two packages with a `Guardian` (the Gradle qualified selector works;
  ambiguity fails closed).
- **Stale manifest (r18), M4c:** build Treasury with Guardian, remove the last validator, rebuild in the same
  directory, launch: `validators.json` is `[]`, its `.plutus` file is gone, and a `freeze` naming Guardian is
  `bad/governor`; a manifest holding a Guardian whose code changed (so its applied hash differs): `bad/governor` at
  the host, and a forged body carrying that script is refused by the client (`governorWitness`). **Absent and
  malformed manifests (X63):** an ordinary `prepare` against a pre-M4c build with no `validators.json` is served; with
  a manifest that does not parse, `initialize` and an ordinary `prepare` succeed and a `freeze` is `bad/governor`.
- **Signer count (X50), both hosts:** 64 and 65 `governor.signers` entries (accepted; `bad/governor`), 64 distinct
  keys across `governor.signers` and `cosigners` (accepted), 65 (`bad/governor`), each refusal before any account is
  built, and duplicates across the two lists counted once in the union.
- **Differential:** the JVM host (through generated clients) and the Node host agree byte for byte on every M4 vector.
- **Governor artifact lookup** (Y3; host only, reduced in r19): the hash-mismatch vector (stale manifest, above) and
  one ambiguity vector per build system (two artifacts for one Gradle simple name beside the defining class; two npm
  entries with one `{package, name}`): `bad/governor`. Clients have no artifact vectors: they anchor on the hash
  (D5).
- **Capability gating** (Y1, Y2): a new client against an M3 host (the field-gating rows of D7), and `initialize`/
  `describe` of Treasury with and without `params`.
- **Legacy session (X20, X32, X38, X39, X46), both hosts, against the final M3b `dev-host-1` kit run unchanged**
  (r19): a client offering `[1]` to a host that ships this ADR, on a project with
  Treasury **and a servable unparameterized map contract** (its `maps` capability, `assetHistory`, `redeemers` and
  `datum` read kinds, and its `deployment` compared too):
  every `initialize`, `describe` and `deployment` reply validates against the **unmodified** `dev-host-1` result
  schemas; `fingerprint` and `versions` are checked against the launch; the kit's protocol-1 vectors pass and the
  capability list has no `params` or `authorizedBy:*`; Treasury is `unsupported:"params"`; and, as plain vectors,
  Treasury's `deployment` with `params`, `prepare` with `call.params` and `decode` with `params` each get the M3
  code D1 states; a client offering `[1, 2]` to an M3 host gets protocol 1 and the same replies.
- **Governor registration** (Y11): mutation vectors refused for a Conway kind-7 registration, a redeemer or witness
  script, collateral and a reference input in a governor registration.

### D9. Milestones (ADR-0022 §8 numbering continues)

| Milestone | Scope | Entry | Exit |
|---|---|---|---|
| **M4a** | Protocol 2 and its schemas (D1); `@Params` contracts on both hosts (D2, D2a, D3, D4's JVM path for params); the `@Quorum` eligibility count and quorum-kind parity (D5); the processor's params `formats` marker (D2a); application, input-domain and format vectors | this ADR accepted (three reviewers); ADR-0022 M3a and M3b approved, and ADR-0022 r8 merged on this ADR's base (r19); the r5 review's deferred P3s settled at entry: Y5 (raw-Data refusal as new behaviour, stated with its consequence), Y6 (governor params' Data mapping and closed type list), Y7 (a keyed `@Params` fixture contract and a large-param contract for the 15,000/15,001 pair, both needed by this milestone's exit; the final M3b `dev-host-1` kit and schemas pinned as the protocol-1 oracle (X46, r19); and, from r9, owned and keyed `@Quorum` fixture contracts whose object council differs from the parent's), Y8 (Q2/Q4 options and an `outsideProject`-style code for external governors), Y9 (`cosigners` for a key holder who is not the caller, on both paths); and from the Fable final review: F-Y1 (an application vector for an asset-aware script such as Stall, with an oracle for scripts Aiken's binding cannot decode) | application vectors agree across JuLC, Aiken (or the stated oracle) and the kit; the input-domain boundary vectors give the same answer on both hosts; Treasury's non-governed calls served and checked on both hosts, including the quorum-eligibility vectors; the legacy-session and capability-gating vectors (D7, D8) pass; the M4a identity vectors pass (D8); I16, I20, I21, I22, I23 |
| **M4b** | Key-credential `@AuthorizedBy` (D4, D5 signers) | M4a | key vectors and mutations on both hosts, **including the non-caller key-holder positive** (X26) and the signed-key insert pair (X62); capability `authorizedBy:key`; I17, I19 |
| **M4c** | Plutus governors (D4, D5, D6), the client's phase-2 evaluation of governor calls (D5, Q6 (a)), with the engine's `Offline.evaluate` fixed to evaluate the received bytes (D5, X59), and the npm build writing or emptying `validators.json` (D4, F21) | M4b; the reference checker's evaluator and its pinned cost models chosen and recorded with the vectors; JuLC confirmed to derive `TxInfo.id` from the bytes it is given (D5); F-Y5 (F7 and the host's artifact lookup re-verified at the JuLC and CCL versions JOUST then pins, recorded with the vectors; moved from M4a in r19) | Treasury + Guardian recorded on a fresh DevKit (registration, `freeze`, payer ≠ owner); every D5/D8 mutation rejected with its code, **the evaluation vectors included**, with the received-bytes seam test on both client paths; the stale, absent and malformed manifest vectors and the governor identity vectors pass; capability `authorizedBy:script`; I18, I19, I24 |

## 4. Invariants

| ID | Invariant | Decisions | Test |
|---|---|---|---|
| I16 | The client's and the host's applications of params produce byte-identical applied scripts and hashes for every application vector | D2, D3, D8 | application vectors (JuLC, Aiken, kit) |
| I17 | A key-credential call is accepted only if the credential's key, from the client's own params, is a required signer that is the caller or a named co-signer | D4, D5 | M4b vectors |
| I18 | A governor call is accepted only with exactly one zero withdrawal from the governor anchored by the client's params, exactly one V3 witness script hashing to it, and an `Approvals` at that withdrawal's pointer containing the approval for this policy, instance and action; every other Rewarding redeemer belongs to an allowed verifier withdrawal that the call's claims require, and decodes as that verifier's claims type | D4, D5 | M4c mutations |
| I19 | The body's required signers equal exactly D5's signer set | D5 | M4b/M4c mutations, payer ≠ owner, signed-key insert |
| I20 | Reservations exclude out-refs across all parameterizations in a session; listings, caches and decoded views are keyed by the applied policy; keyed calls of parameterized contracts follow ADR-0022 D11 (r8) unchanged, and restore is ADR-0022 D8's | D2 | identity tests |
| I21 | A protocol-1 session with a host that ships this ADR is indistinguishable from one with an M3 host: apart from `fingerprint` and `versions` (checked against the launch), it passes the final M3b `dev-host-1` kit unchanged, its capability list is protocol 1's, its error codes follow M3's per-method precedence, and every reply validates against the unmodified `dev-host-1` schemas | D1, D2, D7 | legacy-session and capability-gating vectors |
| I22 | Both hosts accept exactly D2a's input domain for `params` and governor params, formats and the raw-Data constructor-index cap included, refusing the rest with the same code and path; neither serves, advertises or describes as served a parameterized contract whose description lacks the `formats` marker | D2, D2a | input-domain boundary and format vectors; mixed marked/unmarked session |
| I23 | A `@Quorum` call is accepted only if at least `threshold` distinct required signers are in `keys`, both read by the client from its own params or from the input record of the object whose method runs (by call kind; `removed` for `keyedRemove`); both hosts prepare exactly the protocol-2 quorum call kinds | D5 | quorum-eligibility vectors (instance, owned, keyed, removal), through both dispatchers |
| I24 | A script-governor call is signed or exported only after the client's own phase-2 evaluation of the exact transaction, as received, passes, with inputs resolved through its own node, its node's cost models, and each redeemer's declared units as its budget; other calls keep ADR-0022 D13's structural checks | D5 | M4c evaluation vectors |

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
- ADR-0022's fingerprint, keyed operation record and `restore` are unchanged (D2, D4): the governor is bound by its
  applied hash, and keyed retries by their designated input. The npm build always writes `validators.json` (D4, M4c),
  and the npm host reads it outside the fingerprint, an absent or unreadable file affecting governor requests only.
- Clients need no governor artifact lookup to check a call: they anchor on the credential's hash and evaluate the
  witness script (D5).
- Clients count `@Quorum` eligibility from their own reads (D5), for M1b's quorum calls too.
- Clients that sign or export script-governor calls ship a Plutus V3 evaluator and run it with their own node's
  inputs and cost models (D5, Q6 (a)); clients that never serve governors need none.
- Params descriptions gain a `formats` marker and per-component `format` (D2a); `@Params` contracts built before M4a
  must be rebuilt before a dev host serves them.
- The HTTP prepare service is unchanged.

## 7. Compatibility

Old clients are unaffected: they offer protocol 1, and a protocol-1 session with a host that ships this ADR is an
M3 session (D1, I21), so a client validating replies against the closed `dev-host-1` schemas keeps working,
Treasury-like contracts included (they stay `unsupported:"params"`). A **new** client offers `[1, 2]`; against an
M1–M3 host it gets protocol 1 and must then send none of the new fields. Within protocol 2 each field is gated by a
capability (D2), because shipped hosts refuse unknown fields (`bad/request`). The fingerprint, the keyed `operation`
record and `restore` entries are ADR-0022's in both protocols (D2, D4). The `@Quorum` eligibility count (D5) tightens what an ADR-0022 client accepts for M1b's quorum calls; an honest
host's bodies already satisfy it. The client's phase-2 evaluation (D5, Q6 (a)) applies to script-governor calls only, which no
ADR-0022 host serves, so it changes nothing for existing calls.

## 8. Open questions

- **Q5. Governor registration and Conway's deprecations** (F-Y7): D5 accepts only the legacy kind-0 stake registration,
  which the Conway CDDL marks for deprecation, and the deposit it pays cannot be reclaimed through the dev host (no
  deregistration path is served). Options when kind 0 goes: a kind-7 registration with the governor's Certifying
  witness (needs governors to approve their own registration, against ADR-0009 D3 today), or registration outside the
  dev host. Lean: decide with ADR-0009's next revision.

- **Q6. Must the client evaluate a governor call before signing or exporting it? Decided (a) by the maintainer,
  2026-11-23**, and written into D5, I24 and M4c (D9). Kept here for the record. (X21, T17.) Signer-set
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
  - Lean was **(a)**, since a governor is the one call kind where the client cannot reason about the script at all;
    (b) stays possible as a separate ADR-0022 amendment if IntelliAda's evaluator proves cheap. **Decision: (a).**
- **Q2. Native-script governors** (F16): later, when a runtime path builds them.
- **Q3. Governor discovery.** Should `describe` list the snapshot's plain validators (qualified name, purpose, params)?
  Lean yes, as a convenience; the client still anchors on its own read.
- **Q4. Reference-script governors** (was Q1 in r1, now decided inline-only for protocol 2): a later extension for
  governors too large to attach.

## 9. Revision history

- **r1** (2026-11-21): initial version, after the maintainer chose "ADR amendment first" for ADR-0022 Q7.
*r2–r17 are collapsed to one line each in r19; the full entries are in the PR #157 thread and this file's git
history. "(withdrawn r18)" marks rules r18 removed.*

- **r2** (2026-11-21, Codex review of `65d766d7`, X1–X11): exact application and hashing (D3), governor witness
  mandatory, withdrawals by full reward-account bytes, the signer set, governor selection, prerequisites and publish,
  the JVM path, distinct capabilities, vectors, fact pins.
- **r3** (2026-11-21, Codex round 2 of `983c33b3`, X12–X18): map normalization and raw-Data refusal, Rewarding
  redeemers decoded by purpose, V3 manifest, eligible governors, shared-verifier vector; restore `params` and
  fingerprint versions (withdrawn r18).
- **r4** (2026-11-21, Codex round 3 of `a3d91454`, X19): keyed restore binding (withdrawn r18); restart vectors.
- **r5** (2026-11-21, design and security review of `85f4c3cc`, Y1–Y13): capability gating, `initialize`/`describe`
  under params, the client's artifact rule (reduced to the host in r19), fact corrections, kind-0 registration, the
  withdraw-zero liveness limit; per-policy `mapsStale` and the version-2 binding (withdrawn r18); Y5–Y9 deferred to
  M4a's entry.
- **r6** (2026-11-21, design and security round 2 of `289ff3be`, Y14–Y15): deferred items listed in M4a's entry,
  registration mutation vectors; `fingerprint:2` timing (withdrawn r18).
- **r7** (2026-11-22, Fable final review of `05ea594b`, F-Y1–F-Y8): opaque builtin tags, the asset-aware vector,
  pins and re-recording, pointer order independent of the wire, Q5, integer encoding.
- **r8** (2026-11-22, Codex review of `2ac93789`, X20–X26): protocol 2 by the existing protocol list, quorum
  eligibility and Q6 opened, one params input domain (D2a), the non-caller key holder; the npm build writes
  `validators.json` always; keyed restore and template rules and lock digests (withdrawn r18).
- **r9** (2026-11-22, Codex review of `b24b35db`, X27–X32): quorum source by call kind, params integer formats,
  protocol-1 precedence; recordless restore, relabelling and the lock digest (withdrawn r18).
- **r10** (2026-11-22, Codex review of `e8f8f2b1`, X33–X39, X30 reopened): the `utf8` format, the `keyedRemove`
  quorum source, the `formats` marker, per-method precedence, launch-checked fields; restore discard rules and build
  digests (withdrawn r18).
- **r11** (2026-11-22, Codex review of `0fd6e440`, X40–X46): `int32`/`int64` formats, the per-contract marker, the
  protocol-2 quorum kinds, the M3b baseline; attempt history, operation identity and lock ordering (withdrawn r18).
- **r12** (2026-11-22, Codex review of `107ec9dc`, X47–X50): D7's marker rows, the governor signer limit; legacy
  records and the build marker (withdrawn r18).
- **r13** (2026-11-22, Codex review of `05a192eb`, X51–X53): the version-1 file set, renamed legacy records and the
  crash marker (all withdrawn r18).
- **r14** (2026-11-22, Codex review of `c47909cf`, X54–X55): the raw-Data constructor-index cap; rewind-and-replay
  (withdrawn r18).
- **r15** (2026-11-22, Codex review of `fe518c24`, X56): recordless attempts (withdrawn r18).
- **r16** (2026-11-23, the maintainer decides Q6 (a); Codex review of `58c67de6`, X57–X58): the client's phase-2
  evaluation of script-governor calls (D5, I24); link and retirement rules (withdrawn r18).
- **r17** (2026-11-23, Codex review of `64e114f5`, X59–X61): the evaluator receives the bytes as received (X59, kept);
  record policy rules (withdrawn r18).
- **r18** (2026-11-24, simplifies r17 `d9513308` under the maintainer's trust model; no review findings): the chain
  provider, the wallet and the client's journal are trusted, as in any CCL or MeshJS application, and only the host
  is not (header, §2). The dev-host stack already applied this model to ADR-0022 (its r8: keyed retries rest on the
  designated input alone, `restore` carries no operation record, one content-addressed map node store), so the
  keyed-record and restore rules this ADR added on top of the older text are withdrawn:
  - D2: `operation.policy`, `operation.template`, the template predicate R, retired operations, a restore entry's
    `params`, the restored-body discard guard, the recordless link, retirement and attach rules, and the per-policy
    `mapsStale` scope (X19, X22, X23, X27, X33, X34, X40, X42, X47, X52, X54, X56–X58, X60, X61; Y10; r19 removed
    X59, which r18 listed by mistake: it is kept) → withdrawn; keyed calls
    and `restore` follow ADR-0022 D11 and D8 (r8) unchanged; T18 withdrawn; F20 marked as no longer relied on.
  - D4: fingerprint version 2, `fingerprint:2`, the lock-file digests and the build marker (X17, X24, X31, X37, X41,
    X48, X51, X53, Y13) → withdrawn. The governor is bound by its applied hash (the client's own application must
    equal the credential in its params, and exactly one witness script must hash to it), so a stale or changed
    manifest can only fail closed. The npm build fix that writes `validators.json` on every build (F21) stays.
  - D7 (restore, lock and fingerprint rows removed; a hash-mismatch row and a keyed-retry row added), D8 (identity
    tests restated; recordless, rewind-and-replay, keyed-restore and fingerprint-set vectors removed; one stale-manifest
    vector), D9 (M4a without keyed restore; M4c without fingerprint version 2), I20 and I21 restated, §6, §7.
  - Kept unchanged: protocol 2 and the legacy-session guarantee (D1, I21), the params input domain and formats (D2a,
    I22), byte-exact application (D3, I16), the governor checks (D4, D5, I17–I19), quorum eligibility (I23), the
    client's phase-2 evaluation of governor calls (Q6 (a), I24), registration (D6).
- **r19** (2026-11-24, responds to the reviews of r18 `1f09b191`: Codex (its three findings are X62–X64), Fable
  (one P2, seven P3s) and Opus (three P2s, six P3s); all three kept r18's hash binding):
  - X62 (a signed-key keyed insert with no `Ctx` lost its required signer) → D5 signer set, F9 (`KeyedList.java:344`
    at `4b599e6b`), D8 (`Keys` `Pass.issue` positive and negative), D9 M4b exit, I19;
  - X63, Opus P3-3, Fable P3-4 (`validators.json` absent or malformed; ADR-0022 I7) → D4 (protocol 1 never reads
    it; absent is empty; a parse failure fails only `governor` requests; outside the fingerprint, amending I7; restart
    after a rebuild), header (Amends I7), D7 row, D8 vectors, D9 M4c exit, §6;
  - X64, Fable P2 (the transaction-id vector is circular) → D8 and D9 M4c (a received-bytes seam test on both client
    paths; JuLC's `TxInfo.id` derivation moved into M4c's entry, also Opus P3-6); X59 removed from both withdrawal
    lists (D2, r18 entry);
  - Opus P2-1 (ADR-0022 r8 is not in this PR's base) → header ("Requires ADR-0022 r8"; fact pin `4b599e6b`), F15
    re-pinned with D11's rules 3 and 4, D9 M4a entry;
  - Opus P2-2 (M4a's exit needed M4c features) → D8 identity tests split (governor registration restart and two
    `Guardian`s to M4c), D9;
  - Opus P2-3, Fable P3-2 (the client's artifact rule adds no safety) → D4 (the lookup is the host's; one line per
    build system; mis-resolution surfaces as the hash mismatch), D5 (the client anchors on the credential's hash, one
    inline V3 witness and evaluation; artifact lookup optional, for display), §2 trust assumption, D7 row, D8 (host
    mismatch and ambiguity vectors only), D9 (F-Y5 moved to M4c's entry), §6;
  - Fable P3-3 (a keyed retry naming other params cannot occur: an instance exists under one policy) → D2 keyed
    bullet, D7 row and D8 vector removed;
  - Fable P3-5 (deploy-time residual) → T16;
  - Fable P3-6, Opus P3-1, P3-2, P3-4 (leftovers) → the nested `operation.policy` legacy vector removed; F20
    withdrawn; F14 and F21 trimmed; T18 reduced to its stub; the stale-manifest vector restated (a Guardian whose code
    changed); "old-client vectors" renamed (D9, I21); r2–r17 collapsed to one line each;
  - Opus P3-5 (exactly one approval goes beyond the chain) → D5 (`Approvals` containing the approval; extras inert),
    T13, I18, D8 (an extra approval is no longer a mutation);
  - Fable P3-7 (I21's oracle) → D1, D8 legacy session, D9 M4a entry, I21: the final M3b `dev-host-1` kit and schemas
    run unchanged plus the three precedence examples, instead of a pinned M3b host binary;
  - Fable P3-8 (D2a's parity machinery) → recorded in D2a as the first cut if M4a proves heavy; no change now.
