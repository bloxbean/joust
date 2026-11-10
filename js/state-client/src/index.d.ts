// Types for @julclang/state-client (ADR-0013).

export type Int = bigint | number | string;
export type Hex = string;
export type DataHex = string;

/** A native-asset value: [[policyHex, [[assetNameHex, quantity]]]]. */
export type AssetsJson = Array<[Hex, Array<[Hex, Int]>]>;

export interface Pay {
  lovelace?: Int;
  assets?: AssetsJson;
}

/** The CIP-30 API a wallet's enable() returns (the calls the client uses). */
export interface Cip30Api {
  getNetworkId(): Promise<number>;
  getUtxos(): Promise<string[] | null>;
  getUsedAddresses(): Promise<string[]>;
  getChangeAddress(): Promise<string>;
  signTx(tx: string, partialSign?: boolean): Promise<string>;
  submitTx(tx: string): Promise<string>;
}

export interface Deployment {
  policy: Hex;
  address: string;
  networkId: number;
  referenceScripts?: string[];
}

/** A normalised UTxO, as chain providers answer (ADR-0015 §13.9). */
export interface ProviderUtxo {
  txHash: Hex; index: number; address: string; value: Array<{ unit: string; quantity: string }>;
  datumHash: Hex | null; inlineDatum: Hex | null; referenceScriptHash: Hex | null;
}

/**
 * A chain provider (ADR-0015 §13.9), the counterpart of cardano-client-lib's BackendService: it backs both the engine's
 * reads and the page's chain view. protocolParams() answers Blockfrost's parameter object.
 */
export interface ChainProvider {
  readonly name?: string;
  tip(): Promise<{ slot: number; hash: Hex; height: number | null; epoch: number | null; time: number | null }>;
  protocolParams(): Promise<Record<string, unknown>>;
  utxos(address: string): Promise<ProviderUtxo[]>;
  assetUtxos?(address: string, unit: string): Promise<ProviderUtxo[]>;
  output(ref: string): Promise<ProviderUtxo | null>;
  scriptCbor(hash: Hex): Promise<Hex | null>;
  submit(cbor: Hex): Promise<Hex>;
  awaitTx(hash: Hex, o?: { timeoutMs?: number; intervalMs?: number }): Promise<boolean>;
  txCbor?(hash: Hex): Promise<Hex | null>;
  account?(stakeAddress: string): Promise<{ registered: boolean | undefined; rewards: bigint }>;
}

export declare function blockfrostProvider(baseUrl: string, o?: { projectId?: string; fetchImpl?: typeof fetch }): ChainProvider & { base: string; get(path: string, o?: { allow404?: boolean }): Promise<any> };
export declare function ogmiosProvider(url: string, o?: { fetchImpl?: typeof fetch }): ChainProvider & { rpc(method: string, params?: object): Promise<any> };
export declare function chainOf(provider: ChainProvider): Chain;

export interface Chain {
  readonly provider?: ChainProvider;
  registered(stakeAddress: string): Promise<boolean | undefined>;
  rewards(stakeAddress: string): Promise<bigint>;
  tip(): Promise<number>;
  params(): Promise<Record<string, unknown>>;
  utxo(ref: string): Promise<{ address: string; value: Map<string, bigint>; referenceScriptHash: Hex | null; inlineDatum: Hex | null } | null>;
  utxosAt(address: string): Promise<Array<{ ref: string; address: string; value: Map<string, bigint>; inlineDatum: Hex | null }>>;
  findUnit(address: string, unit: string): Promise<{ ref: string; address: string; value: Map<string, bigint>; inlineDatum: Hex | null } | null>;
  submit(cbor: Hex): Promise<Hex>;
  awaitTx(hash: Hex, o?: { timeoutMs?: number; intervalMs?: number }): Promise<boolean>;
}

export interface Service {
  prepare(request: unknown): Promise<{ cbor: Hex; txHash: Hex; validTo: number; signers: Hex[] }>;
  view(request: unknown): Promise<unknown>;
}

export interface Report {
  fee: bigint;
  walletNet: bigint;
  next: unknown;
  redeemer: unknown;
  created: Hex | null;
  events?: unknown;
  outputs: Array<{ address: string; value: Map<string, bigint> }>;
}

export interface Review {
  contract: string;
  call: { kind: string; meta: unknown; args: unknown; pay: Pay; instance?: Hex; object?: Hex };
  report: Report;
  txHash: Hex;
}

export interface JoustOptions {
  /** @deprecated ignored: transactions are read with cardano-js-sdk */
  CSL?: unknown;
  deployment: Deployment;
  service: Service;
  chain: Chain;
  limits?: Partial<typeof DEFAULT_LIMITS>;
  onReview?: (review: Review) => Promise<void>;
  /** Where keyed operations' designated inputs are recorded (ADR-0016 D11); IndexedDB in a browser, else memory. */
  retryStore?: RetryStore;
}

export interface CallResult {
  txHash: Hex;
  report: Report;
}

export declare const DEFAULT_LIMITS: { maxCollateral: bigint; maxValiditySlots: number; maxStartSlots: number; feeMargin: bigint };
export declare class VerificationError extends Error {
  problems: string[];
  report: Report;
}
export declare class JoustContract {
  constructor(o: JoustOptions & { description: unknown });
  deploy(wallet: Cip30Api, args?: object, pay?: Pay): Promise<CallResult & { instance: Hex }>;
  at(instance: Hex): JoustInstance;
  /** Abandons the wallet's unsettled keyed operation (the user's explicit choice; an earlier attempt may still land). */
  abandonKeyed(wallet: Cip30Api): Promise<unknown>;
}
export declare class JoustInstance {
  readonly id: Hex;
  read(): Promise<{ ref: string; state: unknown; lovelace: bigint } | null>;
  state(): Promise<unknown>;
  call(wallet: Cip30Api, name: string, args?: object, pay?: Pay): Promise<CallResult>;
  create(wallet: Cip30Api, name: string, args?: object, pay?: Pay): Promise<CallResult & { object: Hex }>;
  object(objectId: Hex): JoustObject;
  objects(): Promise<Array<{ id: Hex; type: string; value: unknown; lovelace: bigint }>>;
  /** The list of a keyed type (ADR-0016), by its simple name. */
  keyed(type: string): JoustKeyedList;
}
/** One keyed type's list in one instance (ADR-0016): lookup by key, the walk from the head, inserts. */
export declare class JoustKeyedList {
  unit(key: Hex): string;
  find(key: Hex): Promise<{ ref: string; key: Hex; retired: boolean; value: unknown; lovelace: bigint } | null>;
  exists(key: Hex): Promise<boolean>;
  keys(): Promise<Array<{ key: Hex; retired: boolean; value: unknown }>>;
  insert(wallet: Cip30Api, method: string, args?: object, pay?: Pay, opts?: { abandon?: boolean }): Promise<CallResult & { key: Hex }>;
  object(key: Hex): JoustKeyedObject;
}
export declare class JoustKeyedObject {
  readonly key: Hex;
  call(wallet: Cip30Api, name: string, args?: object, pay?: Pay, opts?: { cosigners?: Cip30Api[]; abandon?: boolean }): Promise<CallResult>;
  read(): Promise<{ ref: string; key: Hex; retired: boolean; value: unknown; lovelace: bigint } | null>;
}
/** A record store for keyed operations' designated inputs (ADR-0016 D11); IndexedDB in a browser. */
export interface RetryStore {
  all(): Promise<unknown[]>;
  put(record: unknown): Promise<void>;
  delete(id: string): Promise<void>;
}
export declare function memoryRetryStore(): RetryStore;
export declare function indexedDbRetryStore(name?: string): RetryStore;
export declare function defaultRetryStore(): RetryStore;
/** Thrown while a keyed operation of the wallet may still land: retry the same call, or abandon it explicitly. */
export declare class PendingKeyedOperation extends Error {
  readonly record: { designated: string; attempts: Array<{ txHash: string; validTo: number | null }> };
}
export declare function verifyKeyed(o: unknown): Promise<{ problems: string[]; report: Report }>;
export declare function keyIdOf(instance: Hex, typeId: Hex, key: Hex): Hex;
export declare function typeIdOf(simple: string): Hex;
export declare function compareKeys(a: Hex, b: Hex): number;
export declare class JoustObject {
  readonly id: Hex;
  call(wallet: Cip30Api, name: string, args?: object, pay?: Pay): Promise<CallResult>;
  read(): Promise<{ ref: string; type: string; value: unknown; lovelace: bigint } | null>;
}
export declare function httpService(baseUrl: string, o?: { fetchImpl?: typeof fetch }): Service;
export declare function blockfrostChain(baseUrl: string, o?: { projectId?: string; fetchImpl?: typeof fetch }): Chain;
export declare function verifyPrepared(o: unknown): Promise<{ problems: string[]; report: Report }>;
/** Plutus data without a ledger library (pdata.js). */
export type PlutusData = { constr: number; fields: PlutusData[] } | { int: bigint } | { bytes: Uint8Array } | { list: PlutusData[] }
  | { map: Array<[PlutusData, PlutusData]> };
export declare function codec(description: unknown): {
  encode(schema: unknown, value: unknown, path?: string): PlutusData;
  decode(schema: unknown, data: PlutusData | Hex, path?: string): unknown;
};
export declare function decodeData(cbor: Hex | Uint8Array): PlutusData;
export declare function encodeData(d: PlutusData): Uint8Array;
export declare function dataHex(d: PlutusData): Hex;
export declare function parseTx(cbor: Hex): any;
export declare function txHash(cbor: Hex): Hex;
export declare function toJson(v: unknown): unknown;
export declare function instanceIdOf(txHash: Hex, index: number): Hex;
export declare function objectIdOf(txHash: Hex, index: number): Hex;
