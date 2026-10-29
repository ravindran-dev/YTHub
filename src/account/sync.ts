import { create } from 'zustand';
import { scopedClient } from './client';
import { useAccount } from './auth';
export type Entity = 'library_items' | 'playlists' | 'playlist_items' | 'favorites' | 'playback_preferences';
export type RecordRow = Record<string, unknown> & { version: number };
export type Operation = { operation_id: string; user_id: string; device_id: string; entity_type: Entity; entity_id: string; expected_version: number; patch: Record<string, unknown>; created_at: string; attempt_count: number; last_error: string | null; status: 'pending' | 'in-progress' | 'failed' | 'conflict'; remote?: RecordRow };
export type Snapshot = { queue: Operation[]; rows: Partial<Record<Entity, RecordRow[]>>; enabled: boolean; lastSync: string | null };
const empty = (): Snapshot => ({ queue: [], rows: {}, enabled: false, lastSync: null });
export const useSync = create<Snapshot & { status: string; owner: string | null }>(() => ({ ...empty(), status: 'Paused', owner: null }));
const storageKey = (id: string) => `ythub-cloud-v1:${id}`;
// Metadata only: no paths, credentials, audio bytes, or session tokens in this cache.
function persist() {
  const { owner, queue, rows, enabled, lastSync } = useSync.getState();
  if (owner) localStorage.setItem(storageKey(owner), JSON.stringify({ queue, rows, enabled, lastSync }));
}
export function selectAccount(owner: string | null) {
  if (owner === useSync.getState().owner) return;
  let state = empty();
  if (owner) {
    try { state = { ...state, ...JSON.parse(localStorage.getItem(storageKey(owner)) || '{}') }; } catch { /* Preserve invalid data for diagnostics; don't delete it. */ }
    state.queue = state.queue.filter(op => op.user_id === owner).map(op => ({ ...op, status: op.status === 'in-progress' ? 'pending' : op.status }));
  }
  useSync.setState({ ...state, owner, status: state.enabled ? 'Waiting to sync' : 'Paused' });
}
export function enableSync(enabled: boolean) { useSync.setState({ enabled, status: enabled ? 'Waiting to sync' : 'Paused' }); persist(); if (enabled) void synchronize(); }
export function enqueue(entity_type: Entity, entity_id: string, patch: Record<string, unknown>) {
  const state = useSync.getState();
  if (!state.owner || useAccount.getState().user?.id !== state.owner) throw new Error('Sign in first.');
  if (state.queue.some(op => op.entity_id === entity_id && op.entity_type === entity_type)) throw new Error('Sync the pending edit for this item before changing it again.');
  const row = state.rows[entity_type]?.find(r => rowId(entity_type, r) === entity_id);
  let device = localStorage.getItem('ythub-device');
  if (!device) { device = crypto.randomUUID(); localStorage.setItem('ythub-device', device); }
  const operation: Operation = { operation_id: crypto.randomUUID(), user_id: state.owner, device_id: device, entity_type, entity_id, patch, expected_version: row?.version || 0, created_at: new Date().toISOString(), attempt_count: 0, last_error: null, status: 'pending' };
  useSync.setState({ queue: [...state.queue, operation], status: 'Waiting to sync' });
  persist(); void synchronize();
}
function rowId(entity: Entity, row: RecordRow) { return String(row[entity === 'favorites' ? 'library_item_id' : entity === 'playback_preferences' ? 'user_id' : 'id']); }
export function resolveConflict(operationId: string, keepLocal: boolean) {
  const state = useSync.getState();
  useSync.setState({ queue: state.queue.flatMap(op => op.operation_id !== operationId ? [op] : keepLocal ? [{ ...op, operation_id: crypto.randomUUID(), expected_version: op.remote?.version || 0, status: 'pending' as const, last_error: null }] : []) });
  persist(); void synchronize();
}
let running = false;
let failures = 0;
let nextAttempt = 0;
export async function synchronize(force = false) {
  const initial = useSync.getState();
  if (running || !initial.enabled || !initial.owner || useAccount.getState().status !== 'authenticated' || (!force && Date.now() < nextAttempt)) return;
  const owner = initial.owner;
  const current = () => useSync.getState().owner === owner && useAccount.getState().user?.id === owner;
  running = true;
  useSync.setState({ status: 'Syncing' });
  try {
    const client = await scopedClient(owner);
    for (const op of initial.queue) {
      if (!current() || !useSync.getState().enabled) return;
      if (op.status === 'conflict') continue;
      const update = (patch: Partial<Operation>) => {
        useSync.setState(s => ({ queue: s.queue.map(item => item.operation_id === op.operation_id ? { ...item, ...patch } : item) })); persist();
      };
      update({ status: 'in-progress', attempt_count: op.attempt_count + 1 });
      const { data, error } = await client.rpc('apply_sync_operation', { operation_id: op.operation_id, entity_type: op.entity_type, entity_id: op.entity_id, expected_version: op.expected_version, patch: op.patch });
      if (!current()) return;
      if (error) { update({ status: 'failed', last_error: error.code || 'Request failed' }); throw error; }
      if (data.status === 'conflict') update({ status: 'conflict', remote: data.remote, last_error: 'Changed on another device. Choose which edit to keep.' });
      else if (data.status === 'applied') { useSync.setState(s => ({ queue: s.queue.filter(item => item.operation_id !== op.operation_id) })); persist(); }
      else throw new Error('Unrecognized server response');
    }
    const rows: Snapshot['rows'] = {};
    for (const entity of ['library_items','playlists','playlist_items','favorites','playback_preferences'] as Entity[]) {
      const records: RecordRow[] = [];
      for (let offset = 0; ; offset += 500) {
        const { data, error } = await client.from(entity).select('*').eq('user_id', owner).order(entity === 'favorites' ? 'library_item_id' : entity === 'playback_preferences' ? 'user_id' : 'id').range(offset, offset + 499);
        if (error) throw error;
        records.push(...data as RecordRow[]);
        if (data.length < 500) break;
      }
      rows[entity] = records;
    }
    if (!current()) return;
    failures = 0; nextAttempt = 0;
    useSync.setState(s => ({ rows, lastSync: new Date().toISOString(), status: s.queue.length ? 'Waiting to sync' : 'Synced' })); persist();
  } catch {
    if (current()) { nextAttempt = Date.now() + Math.min(300000, 2000 * 2 ** failures++); useSync.setState({ status: navigator.onLine ? 'Sync failed' : 'Offline' }); persist(); }
  } finally { running = false; }
}
