import { createClient } from '@supabase/supabase-js';
import { invoke, isTauri } from '@tauri-apps/api/core';
const env = (import.meta as ImportMeta & { env: Record<string, string> }).env;
const url = env.VITE_SUPABASE_URL || '';
const key = env.VITE_SUPABASE_PUBLISHABLE_KEY || '';
const memory = new Map<string, string>();
export const configured = /^https:\/\/[^/]+$/.test(url) && !!key;
export const cloud = configured ? createClient(url, key, {
  auth: {
    detectSessionInUrl: false,
    autoRefreshToken: true,
    persistSession: true,
    storage: {
      getItem: async (key: string) => isTauri() ? invoke<string | null>('account_secret_read', { key }) : memory.get(key) || null,
      setItem: async (key: string, value: string) => {
        if (isTauri()) await invoke('account_secret_write', { key, value });
        else memory.set(key, value);
      },
      removeItem: async (key: string) => {
        if (isTauri()) await invoke('account_secret_write', { key, value: null });
        else memory.delete(key);
      },
    },
  },
}) : null;
export function requireCloud() {
  if (!cloud) throw new Error('Cloud accounts are not configured in this build. Local features remain available.');
  return cloud;
}
export function passwordError(password: string) {
  return password.length < 12 ? 'Use at least 12 characters.' : '';
}
// Freeze the credential for an operation batch so switching accounts cannot
// send an old account's queued metadata using the new account's access token.
export async function scopedClient(owner: string) {
  const {data:{session},error}=await requireCloud().auth.getSession();
  if(error || !session || session.user.id!==owner) throw new Error('Account changed or session expired.');
  const token=session.access_token;
  return createClient(url,key,{accessToken:async()=>token});
}
