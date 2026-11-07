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

export interface Chain {
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
  CSL: unknown;
  deployment: Deployment;
  service: Service;
  chain: Chain;
  limits?: Partial<typeof DEFAULT_LIMITS>;
  onReview?: (review: Review) => Promise<void>;
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
}
export declare class JoustInstance {
  readonly id: Hex;
  read(): Promise<{ ref: string; state: unknown; lovelace: bigint } | null>;
  state(): Promise<unknown>;
  call(wallet: Cip30Api, name: string, args?: object, pay?: Pay): Promise<CallResult>;
  create(wallet: Cip30Api, name: string, args?: object, pay?: Pay): Promise<CallResult & { object: Hex }>;
  object(objectId: Hex): JoustObject;
  objects(): Promise<Array<{ id: Hex; type: string; value: unknown; lovelace: bigint }>>;
}
export declare class JoustObject {
  readonly id: Hex;
  call(wallet: Cip30Api, name: string, args?: object, pay?: Pay): Promise<CallResult>;
  read(): Promise<{ ref: string; type: string; value: unknown; lovelace: bigint } | null>;
}
export declare function httpService(baseUrl: string, o?: { fetchImpl?: typeof fetch }): Service;
export declare function blockfrostChain(baseUrl: string, o?: { projectId?: string; fetchImpl?: typeof fetch }): Chain;
export declare function verifyPrepared(o: unknown): Promise<{ problems: string[]; report: Report }>;
export declare function codec(CSL: unknown, description: unknown): {
  encode(schema: unknown, value: unknown, path?: string): unknown;
  decode(schema: unknown, data: unknown, path?: string): unknown;
};
export declare function toJson(v: unknown): unknown;
export declare function instanceIdOf(txHash: Hex, index: number): Hex;
export declare function objectIdOf(txHash: Hex, index: number): Hex;
