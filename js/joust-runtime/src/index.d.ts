// @julclang/joust-runtime (ADR-0015): JOUST contracts from JavaScript with no JVM.
import type { JoustOptions, Cip30Api } from '@julclang/state-client';
export { JoustContract, VerificationError, blockfrostChain, toJson } from '@julclang/state-client';

export interface Clock { zeroSlot: number; zeroTimeMs: number; slotLengthMs: number }
/** A contract as `joust build` writes it in build/joust/contracts.json. */
export interface BuiltContract { description: string | object; script: string; transitions: Record<string, string> }
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
  readonly api: ChainApi;
  readonly chain: any;
  readonly service: EngineService;
  readonly deployments: Record<string, Deployment>;
  ensureDeployment(contract: string, wallet: Cip30Api): Promise<Deployment>;
  options(contract: string, extra?: object): JoustOptions;
}

export function joustRuntime(o: {
  CSL: any; chainUrl: string; projectId?: string; contracts: BuiltContract[]; clock: Clock; networkId?: number;
  deployments?: Record<string, Deployment>; ttlMs?: number; fetchImpl?: typeof fetch;
}): JoustRuntime;
export function engineService(o: { api: ChainApi; contracts: BuiltContract[]; clock: Clock; networkId?: number; ttlMs?: number; maxRounds?: number }): EngineService;
export function loadEngine(o?: { dir?: string }): Promise<any>;
export function blockfrostApi(baseUrl: string, o?: { projectId?: string; fetchImpl?: typeof fetch }): ChainApi;
export function emptySnapshot(): object;
export function fetchNeed(api: ChainApi, snapshot: object, kind: string, key: string): Promise<void>;
export function devkitClock(adminUrl?: string): Promise<Clock>;
export function devkitTopUp(address: string, ada: number, adminUrl?: string): Promise<void>;
export function devWallet(o: { CSL: any; chain: any; mnemonic?: string; index?: number; networkId?: number; confirm?: (txHex: string) => Promise<boolean> }): Cip30Api & { address: string; keyHash: string };
export function deriveAccount(CSL: any, mnemonic: string, index?: number, networkId?: number): any;
export const DEVKIT_MNEMONIC: string;
