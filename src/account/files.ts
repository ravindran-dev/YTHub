import { invoke, isTauri } from '@tauri-apps/api/core';
import { requireCloud, scopedClient } from './client';
export type CloudObject = { id: string; library_item_id: string; display_name: string; object_path: string; bytes: number; sha256: string; status: string; mime_type: string; updated_at: string };
export type Usage = { used_bytes: number; quota_bytes: number; max_file_bytes: number };
export const mimeTypes: Record<string,string> = {mp3:'audio/mpeg',m4a:'audio/mp4',flac:'audio/flac',ogg:'audio/ogg',opus:'audio/ogg',wav:'audio/wav'};
const hex=(buffer:ArrayBuffer)=>[...new Uint8Array(buffer)].map(v=>v.toString(16).padStart(2,'0')).join('');
export async function storageUsage(): Promise<Usage> { const {data,error}=await requireCloud().rpc('storage_usage');if(error) throw error;return data; }
export async function cloudFiles(): Promise<CloudObject[]> {
  const result: CloudObject[]=[];
  for(let offset=0;;offset+=500) {const {data,error}=await requireCloud().from('cloud_objects').select('*').neq('status','deleted').order('id').range(offset,offset+499);if(error) throw error;result.push(...data);if(data.length<500) return result;}
}
export async function fileAction(action: string, object_id?: string, extra: Record<string,string> = {}, owner?: string) {
  const client=owner ? await scopedClient(owner) : requireCloud();
  const {data,error}=await client.functions.invoke('account-files',{body:{action,object_id,...extra}});
  if(error||data?.error) throw new Error(data?.error||'Cloud operation failed. Retry when connected.');return data;
}
export async function uploadFile(file: File, signal: AbortSignal, progress: (percent:number)=>void, retry?: CloudObject) {
  const user=(await requireCloud().auth.getUser()).data.user;if(!user) throw new Error('Sign in first');
  const client=await scopedClient(user.id);
  const usage=await storageUsage();
  const mime=mimeTypes[file.name.split('.').pop()?.toLowerCase()||''];
  if(!mime || !file.size || file.size>usage.max_file_bytes) throw new Error(`Choose a supported audio file up to ${Math.floor(usage.max_file_bytes/1048576)} MB.`);
  if(signal.aborted) throw new Error('Upload cancelled');
  const checksum=hex(await crypto.subtle.digest('SHA-256',await file.arrayBuffer()));
  if(retry && (retry.sha256!==checksum || retry.bytes!==file.size)) throw new Error('Choose the original file to retry this upload.');
  const itemId=retry?.library_item_id||crypto.randomUUID();
  if(!retry) {
    const result=await client.rpc('apply_sync_operation',{operation_id:crypto.randomUUID(),entity_type:'library_items',entity_id:itemId,expected_version:0,patch:{source_type:'imported',title:file.name.replace(/\.[^.]+$/,''),media_format:file.name.split('.').pop()?.toUpperCase()}});
    if(result.error||result.data?.status!=='applied') throw new Error('Could not save library metadata');
  }
  const {data:object,error}=await client.rpc('reserve_upload',{item_id:itemId,object_id:retry?.id||crypto.randomUUID(),file_name:file.name,file_bytes:file.size,checksum,mime});
  if(error) throw new Error(error.message);
  if(object.status==='ready') {progress(100);return;}
  if(object.status==='verifying') {await fileAction('complete',object.id,{},user.id);progress(100);return;}
  if(object.status!=='pending') throw new Error('Remove this incomplete reservation before retrying.');
  const {data:{session}}=await requireCloud().auth.getSession();if(!session || session.user.id!==user.id) throw new Error('Session expired');
  const env=(import.meta as ImportMeta & {env:Record<string,string>}).env;
  try {
    await new Promise<void>((resolve,reject)=>{
      const xhr=new XMLHttpRequest();
      const cancel=()=>xhr.abort();
      const finish=(error?:Error)=>{signal.removeEventListener('abort',cancel);if(error) reject(error);else resolve();};
      xhr.open('POST',`${env.VITE_SUPABASE_URL}/storage/v1/object/music-private/${object.object_path}`);
      xhr.setRequestHeader('Authorization',`Bearer ${session.access_token}`);xhr.setRequestHeader('apikey',env.VITE_SUPABASE_PUBLISHABLE_KEY);xhr.setRequestHeader('Content-Type',mime);
      xhr.upload.onprogress=e=>{if(e.lengthComputable) progress(Math.floor(e.loaded/e.total*95));};
      xhr.onload=()=>finish(xhr.status>=200&&xhr.status<300 || xhr.status===409 ? undefined : new Error('Upload failed. Retry with the same file.'));
      xhr.onerror=()=>finish(new Error('Upload interrupted. Retry with the same file.'));
      xhr.onabort=()=>finish(new Error('Upload cancelled'));
      signal.addEventListener('abort',cancel,{once:true});
      if(signal.aborted) {finish(new Error('Upload cancelled'));return;}
      xhr.send(file);
    });
    await fileAction('complete',object.id,{},user.id);progress(100);
  } catch(error) {
    if(signal.aborted) {try {await fileAction('delete',object.id,{},user.id);} catch {throw new Error('Cancelled locally. Remove the pending cloud upload when connected to release its reservation.');}}
    throw error;
  }
}
export async function downloadFile(object:CloudObject,signal:AbortSignal,progress:(percent:number)=>void) {
  const client=requireCloud();
  const {data,error}=await client.storage.from('music-private').createSignedUrl(object.object_path,60);
  if(error) throw error;
  const response=await fetch(data.signedUrl,{signal,referrerPolicy:'no-referrer'});
  if(!response.ok||!response.body) throw new Error('Download failed');
  const reader=response.body.getReader();const parts:Uint8Array<ArrayBuffer>[]=[];let size=0;
  try {for(;;){const {done,value}=await reader.read();if(done) break;size+=value.length;if(size>object.bytes||size>26214400) throw new Error('Download exceeds expected size');parts.push(value);progress(Math.floor(size/object.bytes*95));}} finally {await reader.cancel();}
  const blob=new Blob(parts,{type:object.mime_type});
  if(size!==object.bytes||hex(await crypto.subtle.digest('SHA-256',await blob.arrayBuffer()))!==object.sha256) throw new Error('Integrity verification failed. No file was saved.');
  if(isTauri()) {
    const user=(await client.auth.getUser()).data.user;if(!user)throw new Error('Session expired');
    await invoke('cloud_media_save',{owner:user.id,object:object.id,checksum:object.sha256,data:Array.from(new Uint8Array(await blob.arrayBuffer()))});
  } else saveBlob(blob,object.display_name);
  progress(100);
}
export function saveBlob(blob:Blob,name:string) {const url=URL.createObjectURL(blob);const link=document.createElement('a');link.href=url;link.download=name.replace(/[\\/]/g,'_');link.click();setTimeout(()=>URL.revokeObjectURL(url),30000);}
export async function exportAccount() {
  const client=requireCloud();const user=(await client.auth.getUser()).data.user;if(!user) throw new Error('Sign in first');
  const output:Record<string,unknown>={exported_at:new Date().toISOString(),user:{id:user.id,email:user.email}};
  for(const table of ['profiles','user_devices','library_items','playlists','playlist_items','favorites','playback_history','playback_preferences','cloud_objects']) {
    const rows:unknown[]=[];for(let offset=0;;offset+=500){const {data,error}=await client.from(table).select('*').range(offset,offset+499);if(error) throw error;rows.push(...data);if(data.length<500) break;}output[table]=rows;
  }
  saveBlob(new Blob([JSON.stringify(output,null,2)],{type:'application/json'}),'ythub-account-export.json');
}
