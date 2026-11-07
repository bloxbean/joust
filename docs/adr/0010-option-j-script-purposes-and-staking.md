# ADR-0010: Option J — every script purpose: rewards, certificates, votes, proposals; staked state

**Status:** Accepted with changes (r2): a security reviewer and Codex both returned "request changes"; resolved in §5
**Date:** 2026-11-07
**Builds on:** ADR-0001 (I12 enterprise state addresses), ADR-0009 (`@Params`, `@Quorum`, `@AuthorizedBy`).
**Stack:** #1–#10, then this.

## 1. Context

A Plutus V3 script can run for six purposes: minting, spending, rewarding (withdrawals), certifying (stake and DRep
certificates), voting (as a DRep, a constitutional committee member or a stake pool) and proposing (guardrails).
JOUST generates only minting and spending. Two consequences:
- **No governance or staking logic.** A JOUST treasury cannot delegate its stake, withdraw its rewards, register as a
  DRep or vote.
- **No rewards on locked funds.** State UTxOs sit at enterprise addresses (I12), so the funds they lock earn nothing.

A hand-written validator handles all six purposes.

## 2. Decision

### D1. Purpose handlers

```java
@StateContract(stakeSelf = true)
public class StakedTreasury {
    @Params public record Config(JulcList<byte[]> council, BigInteger quorum, byte[] payee) {}

    @Withdraw @Quorum(keys = "params.council", threshold = "params.quorum")
    public static JulcList<Payout> collect(Config cfg, BigInteger amount, Ctx ctx) { ... }

    @Certify @Quorum(keys = "params.council", threshold = "params.quorum")
    public static boolean certificate(Config cfg, TxCert cert) { ... }

    @Votes @Quorum(keys = "params.council", threshold = "params.quorum")
    public static boolean vote(Config cfg, Voter voter, GovernanceActionId action, Vote vote) { ... }

    @Propose
    public static boolean proposal(Config cfg, ProposalProcedure p) { ... }   // a guardrail
}
```

- **What a handler reads.** These purposes have no input of the contract, so a handler sees no instance state. It
  is governed by the `@Params` record, and `@Quorum`/`@AuthorizedBy` may only name `params.*` fields. So every
  instance of the contract type shares one reward account, governed by rules fixed at deploy. That is sound because
  the rules don't depend on any one instance. A handler that reads an instance's state needs a singleton contract,
  which is a follow-up.
- **The generated validator** gains `@Entrypoint`s for `Purpose.WITHDRAW`, `CERTIFY`, `VOTE` and `PROPOSE`, generated
  only for the handlers the contract declares. A purpose without a handler fails, so no certificate can deregister
  or redelegate the script's credential unless a handler allows it.
- **Authorization is required (r2).** `@Withdraw`, `@Certify` and `@Vote` must carry `@Quorum` or `@AuthorizedBy`
  over `params.*`; it is a processor error otherwise. An open handler would let anyone drain rewards, redelegate or
  cast the script's votes. `@Propose` is a guardrail predicate and needs none.
- **Approval identity outside spends (r2).** For `@AuthorizedBy` with a Plutus governor, the `Approval` is:
  - `policy` = this script;
  - `target` = `blake2b_224(serialiseData(scriptInfo))`, which binds the certificate (with its index), the voter, the
    proposal (with its index) or the reward credential;
  - `action` = 1000 + the purpose's tag.

  One approval can therefore never authorise a different certificate, vote or purpose.
- **`@Withdraw` (r2):**
  - **The redeemer** is `Rewards(BigInteger marker = 74, amount, args…, [ctx])`. Its first field is an integer, so it
    can never be read as an `Approvals` list.
  - **`amount`** must equal `tx.withdrawals()[own]`. The ledger requires the whole balance.
  - **The method returns `JulcList<Send>`** totalling exactly `amount`. They are claimed in the shared output registry
    (ADR-0007 D5) by `RewardRef(own)`: an `OutputClaim` whose `by` is `TxOutRef(blake2b_256(ownHash), −1)`, a
    reference no real input can have.
  - **Why the registry:** a reward output is then claimed like any send, so no send, payout or other contract's
    obligation can count it too. It holds even against validators built before this ADR, because they also read the
    registry. The I30 checks apply: not at a spent script's credential, and no JOUST tag.
  - **Withdrawal Ctx:** `Ctx.caller` must sign, and the validity bounds are checked as in any call. `Ctx.balance` is
    the amount, and `Ctx.received` is 0.
  - **Advice:** payouts should depend only on the params and the amount. The reward account can also receive
    proposal deposit refunds and treasury withdrawals.
- **`@Certify` (r2).** Before the method runs, the generated code requires:
  - the certificate's credential is the own script;
  - the certificate is one of `RegStaking` (with or without a deposit), `DelegStaking`, `RegDeleg`, `RegDRep` and
    `UpdateDRep`.

  **Refused outright:** unregistration (`UnRegStaking`, `UnRegDRep`), committee certificates and pool certificates.
  The refund of an unregistration enters the transaction's value with no destination the script could pin, and those
  certificates have no use for a fund-holding contract.

  The method then decides. Typed helpers make exact checks easy: `Certs.delegatesOnlyTo(cert, pool)` (exactly
  `Delegatee.Stake`), `Certs.delegatesVoteTo(cert, drep)` and `Certs.isRegistration(cert)`.
- **`@Vote`:**
  - The voter comes from `VotingScript`. The generated loop takes exactly that voter's entry in `tx.votes()`, and
    fails if there is none.
  - The method is called for each `(action id, vote)` and must allow every one.
  - The handler sees the governance action's id, not its content.
- **`@Propose`:** the method gets the proposal procedure and returns whether it complies. The ledger runs it only for
  `ParameterChange` and `TreasuryWithdrawals` proposals, and only once a constitution names this script as its
  guardrail. It is intended for a standalone guardrail contract.

### D2. `@StateContract(stakeSelf = true)`: staked state (I12 → I36)

- **The address.** State and object UTxOs sit at the base address: payment credential and stake credential both the
  contract's own script. `isStateAddress` requires exactly this staking credential (or none, for the default
  `StakeMode.NONE`), so there is still one address per contract.
- **Where the rewards go.** All instances' funds accrue to the script's reward account. Only `@Withdraw` can move
  them, and only `@Certify` can register, delegate or unregister the credential.
- **Bootstrapping.** The runtime registers the stake credential with `registerStake(payer)`; legacy registration
  needs no script witness, and anyone may do it harmlessly. It delegates with a client call that runs `@Certify`.
  `RegStaking` with a deposit is also allowed, for when legacy registration is retired.
- **The address check (r2).** `isStateAddress` compares the whole staking credential, as Data, with
  `StakingHash(ScriptCredential(own))`. Pointer credentials and other credentials are refused, and so is the
  enterprise variant.
- **Deposit.** `MIN_ADA` includes the 28 extra bytes of a base address.
- **Disclosure.** All instances' funds earn rewards, and carry delegation weight, for whoever governs the params. The
  client exposes the delegation, and users of a multi-owner contract should know it.
- **Requirement.** A contract with `stakeSelf = true` must declare `@Withdraw` and `@Certify`, or its rewards are
  unreachable (processor error).

### D3. Invariants added

| ID | Invariant |
|---|---|
| I36 | A state UTxO's address is exactly (own script, the configured stake credential); with `stake = SELF` the contract's own rules govern the rewards and certificates of its funds |
| I37 | A withdrawal from the contract's credential is fully allocated by its `@Withdraw` method's sends, each claimed in the output registry by `RewardRef(own)` |
| I38 | Every certificate, vote and proposal for which the contract's script runs passes its handler (after the built-in certificate filter); a purpose without a handler fails. Legacy stake registration needs no witness, so it is outside the handlers' control and harmless |

### D4. Example: `stakedtreasury`

A council-governed treasury whose funds are staked:
- deposits are an instance action;
- `collect` sends the rewards to the payee;
- `certificate` allows registration and delegation to one pool fixed in the params, and refuses everything else;
- `vote` lets the council vote as a script DRep;
- `proposal` is a guardrail that refuses treasury withdrawals over a cap.

On DevKit:
- register and delegate the stake credential (`@Certify` by quorum);
- a delegation to another pool is refused;
- withdraw the rewards (`@Withdraw`, after an epoch boundary; or zero, if none have accrued yet);
- register as a DRep and vote, if the DevKit governance setup allows; otherwise in the harness;
- every certificate variant, and every way a delegation can be wrong, in the harness.

## 3. Consequences and limits

- **One reward account per contract type,** governed by the params, so it is shared by all instances. Per-instance
  rewards would need one stake credential per instance, which a script hash cannot give.
- **Handlers are predicates over the purpose's data plus the params.** Combining them with instance state is a
  follow-up: a singleton contract whose params fix its one instance.
- **`StakeMode.SELF` changes every state address,** so the script hash and the addresses differ from `NONE`.

## 5. Review r1 and its resolution

S = security reviewer, C = Codex.

| Finding | Source | Resolution |
|---|---|---|
| **High.** A reward payout tag could double-satisfy a send | S3, C1 | Reward payments are sends claimed in the output registry by `RewardRef(own)`. Every JOUST validator from ADR-0007 on reads the registry, so no other obligation can claim them |
| **High.** Certificate and vote handlers without authorization; refunds to the submitter | S1, C3 | Authorization is required. Unregistration is refused outright, so there is no refund to steal |
| **High.** Certificate variants are easy to under-check | S2 | A built-in filter (own credential, allowed kinds only) and typed `Certs` helpers |
| **High.** No approval identity for non-spend purposes | C2 | `target = blake2b_224(serialiseData(scriptInfo))`, `action = 1000 + purpose` |
| Medium. Withdrawal `Ctx` semantics | C4, S9 | Defined: caller signs, bounds checked, `balance = amount`, `received = 0` |
| Medium. The withdraw redeemer read as `Approvals` | S4 | A leading integer marker, so it never decodes as a list |
| Medium. A shared reward account and voting weight | S5 | Disclosed in D2 |
| Medium. Coverage overstated (legacy registration, DRep, guardrail) | C5, S8 | I38 narrowed; `RegDRep`/`UpdateDRep` allowed; guardrail use documented |
| Low. Addresses, minUTxO, votes, script size | S6, S7, S9 | Exact staking-credential equality; `MIN_ADA` +28 bytes; votes loop on the own voter only; entrypoints only for declared handlers |

## 6. Implementation notes and measurements

- **Naming.** The annotation is `@Votes`, not `@Vote`, so that it doesn't clash with `org.julclang.ledger.Vote`.
  Staking is a boolean, `stakeSelf`, because JuLC cannot compile an enum in the bundled API sources.
- **Blueprint.** JuLC's pinned CIP-57 revision has no vocabulary for the `VOTE`/`PROPOSE` purposes, so contracts with
  those handlers compile with `-Ajulc.blueprint=false`. This is a JuLC follow-up, and also needed for the CIP-57/CIP-30
  work.
- **Sizes and budgets.** StakedVault validator: 12 KB, all six purposes. Harness budgets:

| Call | CPU | Mem |
|---|---|---|
| withdraw 5 ADA to the payee (quorum, one claimed send) | 81.3M | 278k |
| withdraw 0 | 27.7M | 109k |
| certify: delegation | 18.6M | 80k |
| vote (one action, quorum) | 25.9M | 97k |
| guardrail | 12.0M | 40k |
| deposit (staked address) | 117.8M | 377k |

- **Harness.** 5 tests:
  - a staked open and a deposit, through the attack suite;
  - withdrawals: one signature, a wrong amount, a wrong marker, the rewards sent to a thief, an unclaimed send;
  - certificates: another pool, a stake-plus-vote delegation to an attacker DRep, unregistration, DRep unregistration,
    a committee certificate, one signature;
  - votes: one signature, no votes;
  - the guardrail cap.
- **On DevKit:**
  - deploy at a base address staked to the script;
  - a deposit;
  - stake registration;
  - delegation to the DevKit pool by quorum (`@Certify` on-chain), with one signature refused;
  - a reward withdrawal (`@Withdraw` on-chain);
  - a quorum close.

  Voting and proposals are tested in the harness: the DevKit needs an enacted constitution and live governance
  actions for them.
