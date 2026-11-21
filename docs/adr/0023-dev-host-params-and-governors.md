# ADR-0023: The dev host serves `@Params` contracts and `@AuthorizedBy` calls (ADR-0022 Q7)

**Status:** Proposed (r7). Design only.
**Date:** 2026-11-21
**Amends:** ADR-0022 (a dev host): its Q7 ("which milestone serves governors, and how the governor's applied script,
reward address, redeemer and signers are pinned per deployment"), its D16 rows for `@AuthorizedBy` and `@Params`, its
R3, its D3/D4 (npm fingerprint), D8 (reservation and operation identity), D11 (operation record), D13 and D14 where
stated. Everything else in ADR-0022 holds.
**Builds on:** ADR-0009 D3 (`@AuthorizedBy`, the `Approvals` binding, the governor's obligations), ADR-0013 phase 3
(`@Params` in the prepare service, R3), ADR-0015 M3 (governors from a page, `validators.json`).
**Decided by the maintainer (2026-11-21):** Q7 gets an ADR before any implementation.
**Facts** are pinned per row: `main` `d529bfd0` for the runtime, JS, processor and examples; PR #156's head `4335773d`
(ADR-0022 M3a) for the hosts. `DescribedContract.java` and `StateContractHandle.java` differ between the two pins; the
rows cite the pin they were read at.
**Risk:** high. Nothing here changes a validator, a description or what a page verifier accepts. But it lets the host
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
| F14 | The npm host fingerprints and reads `contracts.json` and the two engine files only, not `validators.json` | `js/joust/src/host/launch.js:32-51` at `4335773d` |
| F15 | ADR-0022 D8 excludes live out-refs across the whole session; ADR-0022 D11's persisted keyed operation is `{designated, contract, method, instance, key, attempts}`, with no policy | `Reservations.java` (`prepare`, `avoids`); `KeyedOperation.java:8-26`, `:40-45` at `4335773d` |
| F16 | ADR-0009 D3 refuses a governor equal to the contract's own script or either shared verifier, and allows native-script governors; no runtime path builds a native governor (F2) | ADR-0009 `:77-100` |
| F17 | The schema codec sorts a typed map's entries by their keys' CBOR in RFC 8949 length-first order and refuses duplicates; JuLC's encoder and the JVM adapter's JSON values keep the order given. Reversing two map entries changes the applied hash (#157 review X12) | `SchemaCodec.java:118-132` at `d529bfd0` |
| F18 | The output verifier's Rewarding redeemer is `OutputClaims(claims, deposits)`, the map verifier's `MapClaims(claims)`; the builders omit a verifier's withdrawal when its claim list is empty | `julc-state-api/.../OutputClaims.java:12`, `.../mpf/MapClaims.java:7`; `AssetTx.java:258-262`; `Compose.java:210-223` at `d529bfd0` |

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
- **T16. Wrong governor artifact:** two validators with one simple name, or an npm manifest changed after
  `initialize` (F7, F14).

**Accepted liveness limit** (Y12): Conway requires a withdrawal to equal the reward account's whole balance, and
anyone can credit a registered account (a proposal's deposit return, a treasury withdrawal naming it). A governor
whose account holds a balance makes every zero withdrawal fail at the ledger, so its calls fail (closed) until the
governor's owner sweeps the account outside the dev host. ADR-0022's shared verifiers share this property.

**Trust assumption (unchanged in kind):** a governor is project code, like the contract. JOUST cannot verify that it
lists only the approvals its rules grant or refuses certificates (ADR-0009's obligations); the client shows which
governor, by qualified name and hash, authorises the call.

## 3. Decision

### D1. Scope and capabilities

Protocol 1 serves, on both hosts, behind capabilities in `initialize`:
- **`params`** (M4a): `@Params` contracts, for every call kind ADR-0022 serves for unparameterized ones.
- **`authorizedBy:key`** (M4b) and **`authorizedBy:script`** (M4c): `@AuthorizedBy` **instance actions** (`action`,
  `destroy`) whose credential is a **`@Params` field** holding a `PubKeyCredential` (key) or a `ScriptCredential` of a
  **Plutus V3 withdraw validator** in the snapshot (script).

Still `unsupported`, code `authorizedBy`, with the reason in the message: credentials in state or owned fields (F1),
native-script governors (F16), `@AuthorizedBy` on owned, creation or keyed calls (F3, F10), and a credential branch
whose capability the host lacks. Capability strings are distinct so a client sees exactly what a host serves (D7).

### D2. Params travel with every request that names a parameterized contract

`deployment`, `prerequisites`, `publish`, `prepare`'s call, `view`, `instances`, `objects` and `decode` take
**`params`**: a JSON value of `x-joust.params` (the engine's wire form; integers as JSON integers within the frame
codec's exact range or decimal strings). Absent `params` for a parameterized contract is `unsupported`, code `params`
(today's answer, so old clients see no change); present but not encoding exactly is `bad`, code `params`.
- **No session state:** the session never remembers params; two values are two deployments.
- **Identity:** listings, caches, decoded views and **MerkleMap mirrors, their per-reservation overlays and the
  `mapsStale` scope** (Y10) are keyed by the **applied policy**; a restored non-keyed map transaction carries no
  `params`, so its `mapsStale` scope is the policy its body spends or mints under. **Reservations are not:**
  ADR-0022 D8's out-ref exclusion stays session-wide, so two parameterizations can never reserve the same payer
  input or collateral (F15). ADR-0022 D11's persisted `operation` gains **`policy`** (the applied policy) for a
  parameterized contract; a retry whose request params give another policy is `bad`, code `operation`.
- **Restore** (ADR-0022 D8, whose entry is `{cbor, state, slot?, operation?}`; X19): the deployment binding applies
  **only to keyed operations**. An entry whose `operation` names a parameterized contract (ADR-0022 still requires
  that record for every pending keyed transaction) also carries **`params`**, persisted with it; the host reapplies
  them to `operation.contract` and requires the policy to equal `operation.policy` and the policy the restored body
  spends or mints under; any disagreement is `bad`, code `operation`. Every other entry (an ordinary call, a
  reference-script publication, a stake or governor registration) is restored by ADR-0022 D8's existing path,
  unchanged and without `params`: its reservation is of out-refs, which D8 already excludes session-wide. A later
  retry is compared with its own request's params.
- `deployment`'s result adds `params` (the Data, CBOR hex, D3's encoding) and the **applied** `scriptHashes` (`spend`,
  `mint`, `stake`) beside `unapplied`; the client requires `params` to equal its own bytes. **`deployment.governors`
  stays empty in protocol 1** (Y2): governors are named per call (D4), never pinned in a deployment; a client
  treats a non-empty list as a protocol violation.
- **`initialize` and `describe` under `params`** (Y2): a host advertising `params` reports a parameterized contract
  with its `unapplied` template hash and `parameterized: true` instead of `unsupported: "params"` (a host without
  the capability keeps today's entry); `describe` gives each method of such a contract the status its own code gives
  (a plain method is `served: true`; an `@AuthorizedBy` method follows D7). The contract-wide `params` code no longer
  masks a method's own code once `params` is served (F13).
- **Fields that need a capability** (Y1): a client sends `params` only after `initialize` advertised `params`; `operation.policy` and a
  restore entry's `params` only after `params` (both name a parameterized contract); and `governor` only after
  `authorizedBy:script`; an older host
  refuses unknown fields (`bad/request`), so these answers are not `unsupported` there.

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
  Hosts from the release that ships M4c advertise capability **`fingerprint:2`** in every `initialize` answer. Because the field
  travels in the first request, before any capability is known, the client decides from the installed package it
  already resolves before launch (ADR-0022 D3): it sends `fingerprintVersion: 2` only when the resolved
  `@julclang/joust` package's own `package.json` version is at or above the release that ships M4c; an older host
  would refuse it with `bad/request`, and a client that nevertheless receives that answer starts a fresh host process
  without the field (a failed `initialize` ends the session's usefulness; nothing is retried in place). Version **1** (absent means 1: every protocol-1 client today) is ADR-0022's three files;
  version **2** adds `validators.json` when present, in the same line format after `contracts.json`. The host
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
  integer or decimal string, `boolean` JSON boolean only, `PlutusData` CBOR hex), each encoded and applied as D3 says.
  Wrong arity or type is `bad`, code `governor`.
- **`signers`**: the 28-byte payment key hashes the governor's own rules require (Guardian: `owner`). The client names
  them; the host cannot know a governor's rules.

The host applies the governor's params, refuses (`bad`, code `governor`) a governor whose applied hash differs from
the credential in the contract's params or equals the contract's own hash or either shared verifier's (F16), and
builds the call as the runtime does (F1, F2): exactly one zero withdrawal from the governor's reward address, the
governor script **in the witness set** (protocol 1 serves inline governors only; the reference form is a later
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
a named co-signer; co-signers are allowed only for `@Quorum` or to supply that key.

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
`governor`, `governorWithdrawal`, `governorRedeemer`, `governorSigner`, `governorWitness`, `verifierRedeemer`.
Observable behaviour:

| Situation | Answer |
|---|---|
| a parameterized contract, `params` absent (any client) | `unsupported/params` |
| `params` present, does not encode exactly | `bad/params` |
| a key-credential call on a host without `authorizedBy:key` | `unsupported/authorizedBy` ("key credentials") |
| a script-credential call on a host without `authorizedBy:script` | `unsupported/authorizedBy` ("script governors") |
| an npm client initializing with fingerprint version 1 (or none) on a project with `validators.json` | the version-1 digest; no `authorizedBy:script`; every other call as today |
| a client sends `fingerprintVersion` to a host that does not ship this ADR (any M1–M3 host), or to the JVM host | `bad/request` at `initialize`; the client starts a fresh host process without the field (D4) |
| a client sends `params`, `governor` or `operation.policy` to a host without the capability | `bad/request` (strict field lists); clients send them only after the capability (D2) |
| `initialize` on a host with `params`, a parameterized contract | `unapplied` hash and `parameterized: true`, no `unsupported` (D2) |
| `describe` of an `@AuthorizedBy` method | `served: true` when either credential branch is served; the call entry adds `authorizedBy: [{field, branches}]` so a client knows which branch its params will need |

### D8. Verification and vectors

- **Application vectors (I16):** `(unapplied cborHex, params JSON, params Data, applied cborHex, applied hash)` for:
  Treasury and Guardian; a **zero-param** governor; a **multi-param governor with order-sensitive values**; every JSON
  type, nested records, lists, **maps given out of order** (normalized) and with a duplicate key (refused), raw
  `PlutusData` not in normal form (refused), byte strings of 64 and 65 bytes, integers at 2^64 ± 1; applied contracts
  whose decoded outer `cborHex` is **15,000 bytes (accepted) and 15,001 bytes (`refusal/scriptSize`)**. Generated by JuLC's `applyParams` and checked **independently** by Aiken's implementation
  (`com.bloxbean.cardano.aiken.AikenScriptUtil.applyParamToScript`, `aiken-java-binding 0.1.0` over Aiken's Rust code) and
  by the kit's own applier, written against the Plutus and flat specifications. Any disagreement blocks M4a. Malformed
  params (wrong type, `boolean` given as `1`, unsafe JSON numbers, wrong arity) each have a vector with its code.
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
- **Identity tests:** two parameterizations competing for one wallet out-ref (refused); a restored keyed entry whose
  params, `operation.policy` and body disagree (`bad/operation`), separately from a valid restore followed by a retry
  with changed params (`bad/operation`); restarts with a pending **bounded governor registration** and a pending
  **reference-script publication** of a parameterized contract (both restored, positive, by D8's path); an npm `validators.json` changed **before** `initialize` against the old
  digest (`stageMismatch`), and changed **after** it (the running session keeps its loaded bytes); an old client
  (fingerprint version 1) and a new host on a project with the manifest (initializes, no `authorizedBy:script`); two
  packages with a `Guardian` (the Gradle qualified selector works; ambiguity fails closed).
- **Differential:** the JVM host (through generated clients) and the Node host agree byte for byte on every M4 vector.
- **Governor artifact resolution** (Y3): a refusal vector for each clause of D4's client rule (a second class with the
  same simple name in the first entry, missing resource, wrong `type`, wrong `purpose`, `params` arity and type; npm:
  duplicate and missing `{package, name}`), and a positive where a later class-path entry holds a different class of
  the same name (the first entry wins).
- **Capability gating** (Y1, Y2): a new client against an M3 host (the field-gating rows of D7), and `initialize`/
  `describe` of Treasury with and without `params`.
- **Governor registration** (Y11): mutation vectors refused for a Conway kind-7 registration, a redeemer or witness
  script, collateral and a reference input in a governor registration.

### D9. Milestones (ADR-0022 §8 numbering continues)

| Milestone | Scope | Entry | Exit |
|---|---|---|---|
| **M4a** | `@Params` contracts on both hosts (D2, D3, D4's JVM path for params), application vectors | this ADR accepted (three reviewers); ADR-0022 M3a and M3b approved; the r5 review's deferred P3s settled at entry: Y5 (raw-Data refusal as new behaviour, stated with its consequence), Y6 (governor params' Data mapping and closed type list), Y7 (a keyed `@Params` fixture contract and a large-param contract for the 15,000/15,001 pair, both needed by this milestone's exit), Y8 (Q2/Q4 options and an `outsideProject`-style code for external governors), Y9 (`cosigners` for a key holder who is not the caller, on both paths); and from the Fable final review: F-Y1 (an application vector for an asset-aware script such as Stall, with an oracle for scripts Aiken's binding cannot decode), F-Y5 (F7 and D4's artifact rule re-verified at the JuLC and CCL versions JOUST then pins, recorded with the vectors) | application vectors agree across JuLC, Aiken (or the stated oracle) and the kit; Treasury's non-governed calls served and checked on both hosts; the old-client and capability-gating vectors (D7, D8) pass; I16, I20, I21 |
| **M4b** | Key-credential `@AuthorizedBy` (D4, D5 signers) | M4a | key vectors and mutations on both hosts; capability `authorizedBy:key`; I17, I19 |
| **M4c** | Plutus governors (D4, D5, D6) and fingerprint version 2 with capability `fingerprint:2` (D4) | M4b | Treasury + Guardian recorded on a fresh DevKit (registration, `freeze`, payer ≠ owner); every D5/D8 mutation rejected with its code; capability `authorizedBy:script`; I18, I19 |

## 4. Invariants

| ID | Invariant | Decisions | Test |
|---|---|---|---|
| I16 | The client's and the host's applications of params produce byte-identical applied scripts and hashes for every application vector | D2, D3, D8 | application vectors (JuLC, Aiken, kit) |
| I17 | A key-credential call is accepted only if the credential's key, from the client's own params, is a required signer that is the caller or a named co-signer | D4, D5 | M4b vectors |
| I18 | A governor call is accepted only with exactly one zero withdrawal from the governor anchored by the client's params, its V3 script in the witness set, and exactly one `Approvals` naming this policy, instance and action at that withdrawal's pointer; every other Rewarding redeemer belongs to an allowed verifier withdrawal that the call's claims require, and decodes as that verifier's claims type | D4, D5 | M4c mutations |
| I19 | The body's required signers equal exactly D5's signer set | D5 | M4b/M4c mutations, payer ≠ owner |
| I20 | Reservations exclude out-refs across all parameterizations in a session; a keyed operation retried or restored with params giving another policy is refused; other pending transactions restore as under ADR-0022 | D2 | identity tests |
| I21 | A client that sends no fingerprint version gets the same digest as under ADR-0022 on every project; its contract entries are ADR-0022's except that a host advertising `params` reports `parameterized: true` where ADR-0022 reported `unsupported:"params"`; and every call it can issue without the new fields gets ADR-0022's answer | D2, D4, D7 | old-client vector |

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

- Treasury-like contracts become usable from an IDE; IntelliAda's run panel gains `@Params` input and a governor
  picker over the snapshot's validators.
- Clients implement plutus-core Data encoding and UPLC param application (D3); the kit's vectors make it testable.
- Under **fingerprint version 2**, the npm host's launch digest covers `validators.json` (D4); version 1 is unchanged.
  The keyed operation record gains `policy`, and a parameterized keyed restore entry `params` (D2).
- The HTTP prepare service is unchanged.

## 7. Compatibility

Additive for old clients: new capabilities and codes, and fields an old client never sends. A **new** client
against an M1–M3 host must not send the new fields: each is gated by a capability (D2) or, for
`fingerprintVersion`, by the installed package version (D4), because shipped hosts refuse unknown fields
(`bad/request`). A client that never sends `params` sees today's
`unsupported/params`; a host without a capability answers `unsupported/authorizedBy` as today (D7). The npm
fingerprint is versioned (D4): a client that sends no `fingerprintVersion` gets ADR-0022's digest on every project,
including one with `validators.json`, and no script governors (I21). A restore entry may carry `params` (D2).

## 8. Open questions

- **Q5. Governor registration and Conway's deprecations** (F-Y7): D5 accepts only the legacy kind-0 stake registration,
  which the Conway CDDL marks for deprecation, and the deposit it pays cannot be reclaimed through the dev host (no
  deregistration path is served). Options when kind 0 goes: a kind-7 registration with the governor's Certifying
  witness (needs governors to approve their own registration, against ADR-0009 D3 today), or registration outside the
  dev host. Lean: decide with ADR-0009's next revision.

- **Q2. Native-script governors** (F16): later, when a runtime path builds them.
- **Q3. Governor discovery.** Should `describe` list the snapshot's plain validators (qualified name, purpose, params)?
  Lean yes, as a convenience; the client still anchors on its own read.
- **Q4. Reference-script governors** (was Q1 in r1, now decided inline-only for protocol 1): a later extension for
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
