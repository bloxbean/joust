// @julclang/joust-runtime (ADR-0015): JOUST contracts from JavaScript with no JVM.
import type { JoustOptions, Cip30Api, ChainProvider } from '@julclang/state-client';
export { JoustContract, VerificationError, blockfrostChain, chainOf, blockfrostProvider, ogmiosProvider, toJson } from '@julclang/state-client';

export interface Clock { zeroSlot: number; zeroTimeMs: number; slotLengthMs: number }
/** A contract as `joust build` writes it in build/joust/contracts.json. */
export interface BuiltContract { description: string | object; script: string; transitions: Record<string, string> }
/** A plain JuLC validator as `joust build` writes it in build/joust/validators.json (a governor, ...). */
export interface BuiltValidator { name: string; package: string; purpose: string; script: string; scriptHash: string; params: { name: string; type: string }[] }
/** A governor with its parameters applied (ensureGovernor): give it to a call as opts.governor = { governor, signers }. */
export interface Governor { name: string; script: string; hash: string; rewardAddress: string }
export interface Deployment { contract: string; policy: string; address: string; networkId: number; referenceScripts: string[] }
export interface Prepared { cbor: string; txHash: string; validTo: number; signers: string[]; plan: unknown; instance?: string; object?: string }

export interface ChainApi { base: string; get(path: string, o?: { allow404?: boolean }): Promise<any> }
export interface EngineService {
  prepare(request: object): Promise<Prepared>;
  deployment(contract: string, address: string): Promise<Deployment>;
  publish(contract: string, address: string): Promise<{ cbor: string; txHash: string; signers: string[]; plan: unknown } | null>;
  readonly rounds: number[];
}

export interface JoustRuntime {
  readonly api: ChainProvider;
  readonly provider: ChainProvider;
  readonly chain: any;
  readonly service: EngineService;
  readonly deployments: Record<string, Deployment>;
  ensureDeployment(contract: string, wallet: Cip30Api, params?: object): Promise<Deployment>;
  ensureGovernor(name: string, params: Record<string, unknown> | unknown[], wallet: Cip30Api): Promise<Governor>;
  options(contract: string, extra?: object): JoustOptions;
}

export function joustRuntime(o: {
  CSL?: any; provider?: ChainProvider; chainUrl?: string; projectId?: string; contracts: BuiltContract[]; clock: Clock; networkId?: number;
  deployments?: Record<string, Deployment>; validators?: BuiltValidator[]; ttlMs?: number; fetchImpl?: typeof fetch;
  mapMirror?: MapMirror;
}): JoustRuntime;
export function engineService(o: { api: ChainProvider | ChainApi; contracts: BuiltContract[]; clock: Clock; networkId?: number; ttlMs?: number; maxRounds?: number }): EngineService;
export function loadEngine(o?: { dir?: string }): Promise<any>;
export function blockfrostApi(baseUrl: string, o?: { projectId?: string; fetchImpl?: typeof fetch }): ChainApi;
export function emptySnapshot(): object;
export function fetchNeed(provider: ChainProvider | ChainApi, snapshot: object, kind: string, key: string): Promise<void>;
export function devkitClock(adminUrl?: string): Promise<Clock>;
export function devkitTopUp(address: string, ada: number, adminUrl?: string): Promise<void>;
export function devWallet(o: { chain: any; mnemonic?: string; index?: number; networkId?: number; confirm?: (txHex: string) => Promise<boolean> }): Cip30Api & { address: string; keyHash: string };
export function deriveAccount(mnemonic: string, index?: number, networkId?: number): { address: string; reward: string; keyHash: string; publicKey: string };
export const DEVKIT_MNEMONIC: string;

/** A MerkleMap mirror (ADR-0015 §13.12): Aiken's MPF; answers the engine's map proofs; rebuilt from the chain. */
export interface MapMirror {
  readonly states: Map<string, Map<string, string>>;
  get(root: string, key: string): Promise<string | undefined>;
  answer(need: string, units?: string[]): Promise<string>;
  stage(txHex: string): Promise<number>;
  sync(unit: string): Promise<number>;
}
export interface MirrorCache {
  state(root: string): Promise<Map<string, string> | null>;
  putState(root: string, state: Map<string, string>): Promise<void>;
  seen(unit: string): Promise<Set<string>>;
  putSeen(unit: string, seen: Set<string>): Promise<void>;
  close(): Promise<void>;
}
export function mapMirror(o?: { provider?: ChainProvider; cache?: MirrorCache }): MapMirror;
/** A persistent mirror cache over level: LevelDB in Node (a directory), IndexedDB in browsers (a name). */
export function levelMirrorCache(location: string): Promise<MirrorCache>;
export const EMPTY_ROOT: string;
