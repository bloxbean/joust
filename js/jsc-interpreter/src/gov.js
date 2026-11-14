// M3c (ADR-0010, ADR-0018 D5; semantics §1.4, §2.10): the ledger types of the script purposes, as .jsc 2 reads them:
// each sealed type's constructors in the ledger's tag order, and each record's fields, with their IR types. Written from
// the semantics' table, independently of the Java front end's (Gov.java); JscV2M3cIrStrictTest compares the two. A field
// of type "Data" (ParameterChange.parameters, PlutusData in the ledger) is carried, never read.

const CRED = 'Credential';
const OPT_ID = { option: 'GovernanceActionId' };
const OPT_HASH = { option: 'Hash' };
const CRED_MAP = { ledgerMap: ['Credential', 'Int'] };
const f = (name, type) => ({ name, type });

/** The sealed types: their constructors in tag order, each with its fields. */
export const GOV_SUMS = {
  TxCert: [
    ['RegStaking', f('credential', CRED), f('deposit', { option: 'Int' })],
    ['UnRegStaking', f('credential', CRED), f('refund', { option: 'Int' })],
    ['DelegStaking', f('credential', CRED), f('delegatee', 'Delegatee')],
    ['RegDeleg', f('credential', CRED), f('delegatee', 'Delegatee'), f('deposit', 'Int')],
    ['RegDRep', f('credential', CRED), f('deposit', 'Int')],
    ['UpdateDRep', f('credential', CRED)],
    ['UnRegDRep', f('credential', CRED), f('refund', 'Int')],
    ['PoolRegister', f('poolId', 'Key'), f('poolVfr', 'Key')],
    ['PoolRetire', f('pubKeyHash', 'Key'), f('epoch', 'Int')],
    ['AuthHotCommittee', f('cold', CRED), f('hot', CRED)],
    ['ResignColdCommittee', f('cold', CRED)],
  ],
  Delegatee: [
    ['Stake', f('poolId', 'Key')],
    ['Vote', f('dRep', 'DRep')],
    ['StakeVote', f('poolId', 'Key'), f('dRep', 'DRep')],
  ],
  DRep: [['DRepCredential', f('credential', CRED)], ['AlwaysAbstain'], ['AlwaysNoConfidence']],
  Voter: [['CommitteeVoter', f('credential', CRED)], ['DRepVoter', f('credential', CRED)], ['StakePoolVoter', f('pubKeyHash', 'Key')]],
  Vote: [['VoteNo'], ['VoteYes'], ['Abstain']],
  GovernanceAction: [
    ['ParameterChange', f('id', OPT_ID), f('parameters', 'Data'), f('constitutionScript', OPT_HASH)],
    ['HardForkInitiation', f('id', OPT_ID), f('protocolVersion', 'ProtocolVersion')],
    ['TreasuryWithdrawals', f('withdrawals', CRED_MAP), f('constitutionScript', OPT_HASH)],
    ['NoConfidence', f('id', OPT_ID)],
    ['UpdateCommittee', f('id', OPT_ID), f('removedMembers', { list: CRED }), f('addedMembers', CRED_MAP), f('newQuorum', 'Rational')],
    ['NewConstitution', f('id', OPT_ID), f('constitution', 'Constitution')],
    ['InfoAction'],
  ],
};

/** The records, read by projection. */
export const GOV_RECORDS = {
  GovernanceActionId: [f('txId', 'Bytes'), f('govActionIx', 'Int')],
  ProposalProcedure: [f('deposit', 'Int'), f('returnAddress', CRED), f('governanceAction', 'GovernanceAction')],
  ProtocolVersion: [f('major', 'Int'), f('minor', 'Int')],
  Rational: [f('numerator', 'Int'), f('denominator', 'Int')],
  Constitution: [f('script', OPT_HASH)],
};

/** Every type name, and Data (a PlutusData field's). */
export const GOV_TYPES = new Set([...Object.keys(GOV_SUMS), ...Object.keys(GOV_RECORDS), 'Data']);

/** The constructor names of a sealed type, in tag order. */
export function govCtors(type) {
  return (GOV_SUMS[type] ?? []).map((c) => c[0]);
}

/** The fields of a record, or of the constructor `ctor` of a sealed type; undefined when there is none. */
export function govFields(type, ctor) {
  if (type in GOV_RECORDS && (ctor === undefined || ctor === type)) return GOV_RECORDS[type];
  const c = (GOV_SUMS[type] ?? []).find((x) => x[0] === ctor);
  return c ? c.slice(1) : undefined;
}

/** Whether an IR type is, or holds, a purposes' ledger type or a LedgerMap (never a field, a redeemer or a datum). */
export function holdsGov(t) {
  if (typeof t === 'string') return GOV_TYPES.has(t);
  if (!t || typeof t !== 'object') return false;
  const [k, v] = Object.entries(t)[0];
  if (k === 'ledgerMap') return true;
  if (k === 'list' || k === 'option') return holdsGov(v);
  if (k === 'map') return holdsGov(v[1]);
  return false;
}
