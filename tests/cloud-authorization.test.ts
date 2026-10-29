// @vitest-environment node
import { PGlite } from '@electric-sql/pglite';
import { readFileSync, readdirSync } from 'node:fs';
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
let db: PGlite;
const a='11111111-1111-4111-8111-111111111111', b='22222222-2222-4222-8222-222222222222';
const track='33333333-3333-4333-8333-333333333333', playlist='44444444-4444-4444-8444-444444444444';
const op=()=>crypto.randomUUID();
async function actor(id: string) { await db.exec(`reset role; set role authenticated; select set_config('request.jwt.claim.sub','${id}',false);`); }
async function apply(entity: string,id: string,version: number,patch: object,operation=op()) {
  return (await db.query<{result:{status:string;version:number;remote?:unknown}}>('select public.apply_sync_operation($1,$2,$3,$4,$5::jsonb) as result',[operation,entity,id,version,JSON.stringify(patch)])).rows[0].result;
}
beforeAll(async()=>{
  db=new PGlite();
  await db.exec(`create role anon;create role authenticated;create role service_role bypassrls;
    create schema auth;create schema storage;
    create table auth.users(id uuid primary key,raw_user_meta_data jsonb default '{}');
    create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
    grant usage on schema auth,public,storage to anon,authenticated,service_role;
    create table storage.buckets(id text primary key,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);
    create table storage.objects(id uuid primary key default gen_random_uuid(),bucket_id text,name text,metadata jsonb);
    alter table storage.objects enable row level security;
    grant select,insert,update,delete on storage.objects to authenticated;
    create function storage.foldername(name text) returns text[] language sql as $$select string_to_array(name,'/')$$;`);
  for(const file of readdirSync('supabase/migrations').sort()) await db.exec(readFileSync(`supabase/migrations/${file}`,'utf8'));
  await db.query('insert into auth.users(id) values($1),($2)',[a,b]);
},30000);
afterAll(async()=>{await db.close();});
describe('real PostgreSQL policies and sync RPC',()=>{
  it('scopes profiles and prevents owner mutation',async()=>{
    await actor(a);
    expect((await db.query('select * from profiles')).rows).toHaveLength(1);
    expect((await db.query('update profiles set display_name=$1 where id=$2 returning id',['attack',b])).rows).toHaveLength(0);
    await expect(db.query('update profiles set id=$1 where id=$2',[b,a])).rejects.toThrow();
  });
  it('applies idempotent CAS edits without deleting playlist relationships',async()=>{
    await actor(a);
    const operation=op();
    expect((await apply('library_items',track,0,{source_type:'local',title:'Song'},operation)).status).toBe('applied');
    expect((await apply('library_items',track,0,{source_type:'local',title:'Song'},operation)).version).toBe(1);
    await apply('playlists',playlist,0,{name:'Mix'});
    await apply('playlist_items',op(),0,{playlist_id:playlist,library_item_id:track,position:0});
    expect((await apply('playlists',playlist,1,{name:'New mix'})).version).toBe(2);
    expect((await db.query('select * from playlist_items')).rows).toHaveLength(1);
    expect((await apply('playlists',playlist,1,{name:'Stale'})).status).toBe('conflict');
    await expect(db.query('update playlists set name=$1',['bypass'])).rejects.toThrow();
  });
  it('rejects cross-user references, private fields and anonymous RPCs',async()=>{
    await actor(b);
    expect((await db.query('select * from library_items')).rows).toHaveLength(0);
    await expect(apply('favorites',track,0,{active:true})).rejects.toThrow();
    await expect(apply('playlists',op(),0,{name:'bad',user_id:a})).rejects.toThrow();
    await expect(apply('library_items',op(),0,{title:'bad',source_type:'local',uri:'/home/private'})).rejects.toThrow();
    await db.exec('reset role;set role anon;');
    await expect(db.query('select * from library_items')).rejects.toThrow();
    await expect(apply('playlists',op(),0,{name:'bad'})).rejects.toThrow();
  });
  it('enforces reservations, quotas, storage ownership and confirmation',async()=>{
    await actor(a);
    const object=op(),checksum='a'.repeat(64);
    await expect(db.query("select reserve_upload($1,$2,'song.mp3',26214401,$3,'audio/mpeg')",[track,object,checksum])).rejects.toThrow();
    await db.query("select reserve_upload($1,$2,'song.mp3',100,$3,'audio/mpeg')",[track,object,checksum]);
    await db.query("insert into storage.objects(bucket_id,name) values('music-private',$1)",[`${a}/${object}`]);
    expect((await db.query('select * from storage.objects')).rows).toHaveLength(0);
    await expect(db.query('select confirm_cloud_object($1,$2)',[object,a])).rejects.toThrow();
    await actor(b);
    await expect(db.query("insert into storage.objects(bucket_id,name) values('music-private',$1)",[`${a}/${object}`])).rejects.toThrow();
    expect((await db.query('select * from cloud_objects')).rows).toHaveLength(0);
    await db.exec('reset role');
    await db.query("update cloud_objects set status='verifying' where id=$1",[object]);
    await db.query('select confirm_cloud_object($1,$2)',[object,a]);
    await actor(a);expect((await db.query('select * from storage.objects')).rows).toHaveLength(1);
    await actor(b);expect((await db.query('select * from storage.objects')).rows).toHaveLength(0);
    await db.exec('reset role');await db.query('update storage_limits set quota_bytes=100');await actor(a);
    await expect(db.query("select reserve_upload($1,$2,'other.mp3',1,$3,'audio/mpeg')",[track,op(),'b'.repeat(64)])).rejects.toThrow();
  });
  it('keeps deletion tombstones and rejects stale resurrection',async()=>{
    await actor(a);await apply('playlists',playlist,2,{deleted_at:new Date().toISOString()});
    expect((await apply('playlists',playlist,2,{name:'Resurrect'})).status).toBe('conflict');
  });
});
