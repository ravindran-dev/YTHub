import { createClient } from 'npm:@supabase/supabase-js@2';
const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'authorization, apikey, content-type, x-client-info', 'Access-Control-Allow-Methods': 'POST, OPTIONS' };
const respond = (status: number, data: unknown) => new Response(JSON.stringify(data), { status, headers: { ...cors, 'Content-Type': 'application/json' } });
const hex = (buffer: ArrayBuffer) => [...new Uint8Array(buffer)].map(v => v.toString(16).padStart(2,'0')).join('');
function validAudio(bytes: Uint8Array, mime: string) {
  const text = new TextDecoder('ascii');
  if (mime==='audio/flac') return text.decode(bytes.slice(0,4))==='fLaC';
  if (mime==='audio/ogg') return text.decode(bytes.slice(0,4))==='OggS';
  if (mime==='audio/wav') return text.decode(bytes.slice(0,4))==='RIFF' && text.decode(bytes.slice(8,12))==='WAVE';
  if (mime==='audio/mp4') return text.decode(bytes.slice(4,8))==='ftyp';
  return mime==='audio/mpeg' && (text.decode(bytes.slice(0,3))==='ID3' || (bytes[0]===255 && (bytes[1]&224)===224));
}
Deno.serve(async request => {
  if(request.method==='OPTIONS') return new Response('',{headers:cors});
  if(request.method!=='POST') return respond(405,{error:'POST required'});
  const url=Deno.env.get('SUPABASE_URL')!;
  const service=Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!;
  const admin=createClient(url,service,{auth:{persistSession:false,autoRefreshToken:false}});
  const token=request.headers.get('Authorization')?.replace(/^Bearer /i,'');
  if(!token) return respond(401,{error:'Authentication required'});
  const {data:{user},error}=await admin.auth.getUser(token);
  if(error||!user) return respond(401,{error:'Invalid session'});
  try {
    if(Number(request.headers.get('content-length')||0)>8192) return respond(413,{error:'Request too large'});
    const input=await request.text();if(input.length>8192) return respond(413,{error:'Request too large'});
    const body=JSON.parse(input);
    if(body.action==='delete-account') {
      if(body.confirmation!=='DELETE MY ACCOUNT' || typeof body.password!=='string') return respond(400,{error:'Confirmation and password required'});
      const verifier=createClient(url,Deno.env.get('SUPABASE_ANON_KEY')!,{auth:{persistSession:false,autoRefreshToken:false}});
      const verified=await verifier.auth.signInWithPassword({email:user.email!,password:body.password});
      if(verified.error || verified.data.user?.id!==user.id) return respond(403,{error:'Reauthentication failed'});
      await verifier.auth.signOut();
      // Mark reservations first to prevent new uploads; local copies are never touched.
      const marked=await admin.from('cloud_objects').update({status:'deleting'}).eq('user_id',user.id).neq('status','deleted');
      if(marked.error) throw marked.error;
      for(;;) {
        const {data:objects,error:listError}=await admin.storage.from('music-private').list(user.id,{limit:100});
        if(listError) throw listError;if(!objects?.length) break;
        const removed=await admin.storage.from('music-private').remove(objects.map(o=>`${user.id}/${o.name}`));if(removed.error) throw removed.error;
      }
      const deleted=await admin.auth.admin.deleteUser(user.id);if(deleted.error) throw deleted.error;
      return respond(200,{status:'deleted'});
    }
    if(!['complete','delete'].includes(body.action)||typeof body.object_id!=='string') return respond(400,{error:'Invalid action'});
    const {data:object,error:findError}=await admin.from('cloud_objects').select('*').eq('id',body.object_id).eq('user_id',user.id).single();
    if(findError||!object) return respond(404,{error:'Object unavailable'});
    if(body.action==='complete') {
      if(object.status==='ready') return respond(200,{status:'ready'});
      if(!['pending','verifying'].includes(object.status)) return respond(409,{error:'Reservation unavailable'});
      const claim=await admin.from('cloud_objects').update({status:'verifying'}).eq('id',object.id).in('status',['pending','verifying']).select('id');
      if(claim.error||!claim.data?.length) return respond(409,{error:'Reservation changed'});
      const downloaded=await admin.storage.from('music-private').download(object.object_path);
      if(downloaded.error) throw downloaded.error;
      if(downloaded.data.size!==object.bytes || downloaded.data.size>26214400) return respond(422,{error:'File size mismatch; remove and retry'});
      const buffer=await downloaded.data.arrayBuffer();
      if(hex(await crypto.subtle.digest('SHA-256',buffer))!==object.sha256 || !validAudio(new Uint8Array(buffer),object.mime_type)) return respond(422,{error:'File integrity or audio validation failed; remove and retry'});
      // Confirmation and the library link are one database transaction.
      const completed=await admin.rpc('confirm_cloud_object',{object_id:object.id,owner_id:user.id});
      if(completed.error) throw completed.error;
      return respond(200,{status:'ready'});
    }
    const marked=await admin.from('cloud_objects').update({status:'deleting'}).eq('id',object.id).eq('user_id',user.id);if(marked.error) throw marked.error;
    const removed=await admin.storage.from('music-private').remove([object.object_path]);if(removed.error) throw removed.error;
    const finished=await admin.rpc('finish_cloud_deletion',{object_id:object.id,owner_id:user.id});if(finished.error) throw finished.error;
    return respond(200,{status:'deleted'});
  } catch { return respond(500,{error:'Operation did not complete. Retry; local files are unchanged.'}); }
});
