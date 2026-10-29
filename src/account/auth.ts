import { create } from 'zustand';
import type { User } from '@supabase/supabase-js';
import { cloud, requireCloud, passwordError } from './client';
export type AuthStatus = 'initializing' | 'signed-out' | 'signing-in' | 'authenticated' | 'refreshing' | 'offline' | 'expired' | 'recovery';
export const useAccount = create<{ status: AuthStatus; user: User | null; error: string | null }>(() => ({ status: cloud ? 'initializing' : 'signed-out', user: null, error: null }));
let generation = 0;
let started = false;
async function validate() {
  if (!cloud) return;
  const request = ++generation;
  try {
    const { data, error } = await cloud.auth.getUser();
    if (request !== generation) return;
    if (error) {
      // Retain only a user already validated in this running process when offline.
      const known = useAccount.getState().user;
      useAccount.setState({ status: !navigator.onLine && known ? 'offline' : 'expired', user: !navigator.onLine ? known : null, error: 'Session validation unavailable. Reconnect or sign in again.' });
      return;
    }
    useAccount.setState({ user: data.user, status: 'authenticated', error: null });
  } catch {
    if (request === generation) useAccount.setState({ status: 'expired', user: null, error: 'Secure session restoration failed.' });
  }
}
export function startAccount() {
  if (started || !cloud) return;
  started = true;
  cloud.auth.onAuthStateChange((event, session) => {
    if (!session || event === 'SIGNED_OUT') {
      generation++;
      useAccount.setState({ user: null, status: 'signed-out', error: null });
    } else if (event === 'PASSWORD_RECOVERY') {
      generation++;
      useAccount.setState({ user: session.user, status: 'recovery' });
    } else {
      useAccount.setState({ status: event === 'TOKEN_REFRESHED' ? 'refreshing' : 'initializing' });
      // Never await another auth API inside the SDK's auth lock.
      setTimeout(() => void validate(), 0);
    }
  });
  window.addEventListener('online', () => void validate());
}
export async function signIn(email: string, password: string) {
  useAccount.setState({ status: 'signing-in', error: null });
  const { error } = await requireCloud().auth.signInWithPassword({ email: email.trim(), password });
  if (error) { useAccount.setState({ status: 'signed-out' }); throw new Error('Unable to sign in. Check your credentials and email verification.'); }
  await validate();
}
export async function signUp(email: string, password: string, displayName: string) {
  const issue = passwordError(password);
  if (issue) throw new Error(issue);
  const { error } = await requireCloud().auth.signUp({ email: email.trim(), password, options: { data: { display_name: displayName.trim().slice(0, 100) } } });
  if (error) throw new Error('Registration could not be completed. Check your details or try account recovery.');
}
export async function sendCode(email: string, recovery: boolean) {
  const auth = requireCloud().auth;
  // Always present the same result for unknown and existing addresses.
  if (recovery) await auth.resetPasswordForEmail(email.trim());
  else await auth.resend({ type: 'signup', email: email.trim() });
}
export async function verifyCode(email: string, token: string, recovery: boolean) {
  const { error } = await requireCloud().auth.verifyOtp({ email: email.trim(), token: token.trim(), type: recovery ? 'recovery' : 'signup' });
  if (error) throw new Error('This code could not be verified. Request a new code and try again.');
}
export async function changePassword(password: string) {
  const issue = passwordError(password);
  if (issue) throw new Error(issue);
  const { error } = await requireCloud().auth.updateUser({ password });
  if (error) throw new Error('Password update failed. Verify a fresh recovery code and try again.');
  await validate();
}
export async function signOut(others = false) {
  const { error } = await requireCloud().auth.signOut({ scope: others ? 'others' : 'local' });
  if (error) throw new Error('Sign out could not be completed. Reconnect and retry.');
}
