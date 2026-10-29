-- Apply in the Supabase SQL Editor. Generated from the ordered migrations.

begin;
create table public.profiles (
 id uuid primary key references auth.users(id) on delete cascade,
 display_name text not null default '' check(length(display_name)<=100), avatar_path text,
 created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table public.user_devices (
 id uuid primary key, user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
 device_name text not null, platform text not null, app_version text not null,
 last_seen_at timestamptz not null default now(), created_at timestamptz not null default now(), unique(id,user_id)
);
create table public.library_items (
 id uuid primary key, user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
 source_type text not null check(source_type in ('local','imported','cloud','metadata')),
 source_identifier text, title text not null, artist text not null default '', album text not null default '',
 duration_ms bigint not null default 0 check(duration_ms>=0), media_format text, artwork_reference text,
 metadata_hash text, created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
 deleted_at timestamptz, version bigint not null default 1, unique(id,user_id)
);
create table public.playlists (
 id uuid primary key, user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
 name text not null check(length(name) between 1 and 200), description text not null default '', artwork_reference text,
 is_favorite boolean not null default false, created_at timestamptz not null default now(),
 updated_at timestamptz not null default now(), deleted_at timestamptz, version bigint not null default 1, unique(id,user_id)
);
create table public.playlist_items (
 id uuid primary key, user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
 playlist_id uuid not null, library_item_id uuid not null, position numeric not null,
 added_at timestamptz not null default now(), updated_at timestamptz not null default now(), deleted_at timestamptz,
 foreign key(playlist_id,user_id) references public.playlists(id,user_id) on delete cascade,
 foreign key(library_item_id,user_id) references public.library_items(id,user_id) on delete cascade
);
create table public.favorites (
 user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
 library_item_id uuid not null, active boolean not null default true, updated_at timestamptz not null default now(),
 primary key(user_id,library_item_id), foreign key(library_item_id,user_id) references public.library_items(id,user_id) on delete cascade
);
create table public.playback_history (
 id uuid primary key, user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
 library_item_id uuid not null, played_at timestamptz not null default now(),
 completion_percentage numeric check(completion_percentage between 0 and 100),
 foreign key(library_item_id,user_id) references public.library_items(id,user_id) on delete cascade
);
create table public.playback_preferences (
 user_id uuid primary key default auth.uid() references auth.users(id) on delete cascade,
 speed numeric not null default 1 check(speed between .5 and 2), repeat_mode smallint not null default 0 check(repeat_mode between 0 and 2),
 shuffle boolean not null default false, sort_order text not null default 'recent', view_mode text not null default 'list',
 updated_at timestamptz not null default now(), version bigint not null default 1
);
-- Server timestamps and immutable ownership; no client clock determines conflict order.
create function public.stamp_account_row() returns trigger language plpgsql set search_path='' as $$
begin
 if TG_OP='UPDATE' and new.user_id is distinct from old.user_id then raise exception 'Ownership is immutable'; end if;
 new.updated_at=clock_timestamp(); return new;
end $$;
do $$ declare t text; begin
 foreach t in array array['profiles','user_devices','library_items','playlists','playlist_items','favorites','playback_history','playback_preferences'] loop
 execute format('alter table public.%I enable row level security',t);
 execute format('revoke all on public.%I from anon, authenticated',t);
 execute format('grant select, insert, update, delete on public.%I to authenticated',t);
 if t='profiles' then
 execute format('create policy owner_access on public.%I for all to authenticated using (id=(select auth.uid())) with check (id=(select auth.uid()))',t);
 else
 execute format('create policy owner_access on public.%I for all to authenticated using (user_id=(select auth.uid())) with check (user_id=(select auth.uid()))',t);
 execute format('create index on public.%I(user_id)',t);
 end if;
 if t in ('library_items','playlists','playlist_items','favorites','playback_preferences') then
 execute format('create trigger stamp before update on public.%I for each row execute function public.stamp_account_row()',t);
 end if;
 end loop;
end $$;
-- Deny media operations until a trusted quota/reservation service is deployed.
insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
values ('music-private','music-private',false,104857600,array['audio/mpeg','audio/mp4','audio/flac','audio/ogg','audio/wav'])
on conflict(id) do nothing;
create policy music_owner_read on storage.objects for select to authenticated
using(bucket_id='music-private' and (storage.foldername(name))[1]=(select auth.uid())::text);
-- No client INSERT/UPDATE/DELETE policies: this prevents unenforced quota bypass.
commit;


begin;
alter table public.playlist_items add column version bigint not null default 1;
alter table public.favorites add column version bigint not null default 1;
alter table public.library_items add column cloud_object_id uuid;
create table public.sync_receipts (
 user_id uuid not null references auth.users(id) on delete cascade,
 operation_id uuid not null, result jsonb not null, created_at timestamptz not null default now(),
 primary key(user_id,operation_id)
);
alter table public.sync_receipts enable row level security;
revoke all on public.sync_receipts from anon,authenticated;
create function public.bootstrap_profile() returns trigger language plpgsql security definer set search_path='' as $$
begin insert into public.profiles(id,display_name) values(new.id,left(coalesce(new.raw_user_meta_data->>'display_name',''),100)); return new; end $$;
create trigger account_created after insert on auth.users for each row execute function public.bootstrap_profile();
insert into public.profiles(id) select id from auth.users on conflict do nothing;
-- All durable metadata writes go through a version check and an idempotency receipt.
create function public.apply_sync_operation(operation_id uuid, entity_type text, entity_id uuid, expected_version bigint, patch jsonb)
returns jsonb language plpgsql security definer set search_path='' as $$
declare actor uuid:=auth.uid(); old_row jsonb; next_row jsonb; result jsonb; table_name text; fields text[]; allowed jsonb; row_version bigint; key_pred text; assignments text;
begin
 if actor is null then raise exception 'Authentication required' using errcode='42501'; end if;
 if patch is null or jsonb_typeof(patch)<>'object' or expected_version is null or expected_version<0 then raise exception 'Invalid operation'; end if;
 if pg_column_size(patch)>65536 then raise exception 'Operation too large'; end if;
 perform pg_advisory_xact_lock(hashtextextended(actor::text,0));
 select r.result into result from public.sync_receipts r where r.user_id=actor and r.operation_id=apply_sync_operation.operation_id;
 if found then return result; end if;
 case entity_type
 when 'library_items' then table_name:='library_items'; fields:=array['source_type','source_identifier','title','artist','album','duration_ms','media_format','metadata_hash','deleted_at'];
 when 'playlists' then table_name:='playlists'; fields:=array['name','description','is_favorite','deleted_at'];
 when 'playlist_items' then table_name:='playlist_items'; fields:=array['playlist_id','library_item_id','position','deleted_at'];
 when 'favorites' then table_name:='favorites'; fields:=array['active'];
 when 'playback_preferences' then table_name:='playback_preferences'; fields:=array['speed','repeat_mode','shuffle','sort_order','view_mode'];
 else raise exception 'Unsupported entity'; end case;
 key_pred:=case when entity_type='favorites' then 'library_item_id' when entity_type='playback_preferences' then 'user_id' else 'id' end;
 if entity_type='playback_preferences' and entity_id<>actor then raise exception 'Invalid preference owner'; end if;
 execute format('select to_jsonb(t) from public.%I t where user_id=$1 and %I=$2 for update',table_name,key_pred) into old_row using actor,entity_id;
 row_version:=coalesce((old_row->>'version')::bigint,0);
 if row_version<>expected_version then return jsonb_build_object('status','conflict','remote',old_row,'version',row_version); end if;
 select coalesce(jsonb_object_agg(key,value),'{}') into allowed from jsonb_each(patch) where key=any(fields);
 if allowed<>patch then raise exception 'Unsupported or private field'; end if;
 next_row:=coalesce(old_row,'{}')||allowed||jsonb_build_object('user_id',actor,key_pred,entity_id,'version',row_version+1,'updated_at',clock_timestamp());
 -- Fill required defaults for new records without accepting client ownership or timestamps.
 if old_row is null then
  if entity_type='library_items' then next_row:=jsonb_build_object('artist','','album','','duration_ms',0,'created_at',now())||next_row; end if;
  if entity_type='playlists' then next_row:=jsonb_build_object('description','','is_favorite',false,'created_at',now())||next_row; end if;
  if entity_type='playlist_items' then next_row:=jsonb_build_object('added_at',now())||next_row; end if;
  if entity_type='playback_preferences' then next_row:=jsonb_build_object('speed',1,'repeat_mode',0,'shuffle',false,'sort_order','recent','view_mode','list')||next_row; end if;
  execute format('insert into public.%I select (jsonb_populate_record(null::public.%I,$1)).*',table_name,table_name) using next_row;
 else
  select string_agg(format('%I = r.%I',a.attname,a.attname),',') into assignments
  from pg_catalog.pg_attribute a where a.attrelid=format('public.%I',table_name)::regclass
  and a.attnum>0 and not a.attisdropped and a.attname=any(fields||array['version','updated_at']);
  execute format('update public.%I t set %s from jsonb_populate_record(null::public.%I,$1) r where t.user_id=$2 and t.%I=$3',table_name,assignments,table_name,key_pred) using next_row,actor,entity_id;
 end if;
 execute format('select to_jsonb(t) from public.%I t where user_id=$1 and %I=$2',table_name,key_pred) into next_row using actor,entity_id;
 result:=jsonb_build_object('status','applied','record',next_row,'version',row_version+1);
 insert into public.sync_receipts(user_id,operation_id,result) values(actor,operation_id,result);
 return result;
end $$;
revoke all on function public.apply_sync_operation(uuid,text,uuid,bigint,jsonb) from public,anon;
grant execute on function public.apply_sync_operation(uuid,text,uuid,bigint,jsonb) to authenticated;
revoke insert,update,delete on public.library_items,public.playlists,public.playlist_items,public.favorites,public.playback_preferences from authenticated;
revoke all on function public.bootstrap_profile() from public,anon,authenticated;
commit;


begin;
create table public.storage_limits (
 singleton boolean primary key default true check(singleton), quota_bytes bigint not null check(quota_bytes>0), max_file_bytes bigint not null check(max_file_bytes>0)
);
insert into public.storage_limits values(true,1073741824,26214400);
alter table public.storage_limits enable row level security;
revoke all on public.storage_limits from anon,authenticated;
create table public.cloud_objects (
 id uuid primary key, user_id uuid not null references auth.users(id) on delete cascade,
 library_item_id uuid not null, object_path text not null unique, display_name text not null check(length(display_name) between 1 and 255),
 bytes bigint not null check(bytes>0), sha256 text not null check(sha256 ~ '^[0-9a-f]{64}$'), mime_type text not null,
 status text not null default 'pending' check(status in ('pending','verifying','ready','deleting','deleted')),
 created_at timestamptz not null default now(), updated_at timestamptz not null default now(), expires_at timestamptz not null default(now()+interval '24 hours'),
 unique(id,user_id), foreign key(library_item_id,user_id) references public.library_items(id,user_id) on delete cascade
);
alter table public.library_items add constraint cloud_owner foreign key(cloud_object_id,user_id) references public.cloud_objects(id,user_id);
alter table public.cloud_objects enable row level security;
revoke all on public.cloud_objects from anon,authenticated;
grant select on public.cloud_objects to authenticated;
create policy own_objects on public.cloud_objects for select to authenticated using(user_id=(select auth.uid()));
create index on public.cloud_objects(user_id,status);
create function public.storage_usage() returns jsonb language sql stable security definer set search_path='' as $$
 select jsonb_build_object('quota_bytes',l.quota_bytes,'max_file_bytes',l.max_file_bytes,'used_bytes',coalesce((select sum(bytes) from public.cloud_objects where user_id=auth.uid() and status<>'deleted'),0)) from public.storage_limits l where auth.uid() is not null;
$$;
create function public.reserve_upload(item_id uuid, object_id uuid, file_name text, file_bytes bigint, checksum text, mime text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare actor uuid:=auth.uid(); limits public.storage_limits; existing public.cloud_objects; used bigint;
begin
 if actor is null then raise exception 'Authentication required' using errcode='42501'; end if;
 perform pg_advisory_xact_lock(hashtextextended(actor::text,0));
 if not exists(select 1 from public.library_items where id=item_id and user_id=actor and deleted_at is null) then raise exception 'Library item unavailable'; end if;
 if mime not in ('audio/mpeg','audio/mp4','audio/flac','audio/ogg','audio/wav') or file_name ~ '[/\\]' then raise exception 'Unsupported file'; end if;
 select * into limits from public.storage_limits;
 if file_bytes is null or file_bytes<=0 or file_bytes>limits.max_file_bytes then raise exception 'File size exceeds limit'; end if;
 select * into existing from public.cloud_objects where user_id=actor and id=object_id;
 if found then
  if existing.library_item_id<>item_id or existing.sha256<>checksum or existing.bytes<>file_bytes then raise exception 'Reservation mismatch'; end if;
  return to_jsonb(existing);
 end if;
 select * into existing from public.cloud_objects where user_id=actor and sha256=checksum and status='ready' limit 1;
 if found then return to_jsonb(existing); end if;
 select coalesce(sum(bytes),0) into used from public.cloud_objects where user_id=actor and status<>'deleted';
 if used+file_bytes>limits.quota_bytes then raise exception 'Storage quota exceeded'; end if;
 insert into public.cloud_objects(id,user_id,library_item_id,object_path,display_name,bytes,sha256,mime_type)
 values(object_id,actor,item_id,actor::text||'/'||object_id::text,file_name,file_bytes,checksum,mime) returning * into existing;
 return to_jsonb(existing);
end $$;
revoke all on function public.storage_usage(),public.reserve_upload(uuid,uuid,text,bigint,text,text) from public,anon;
grant execute on function public.storage_usage(),public.reserve_upload(uuid,uuid,text,bigint,text,text) to authenticated;
-- Storage calls are authorized by a reservation, not by a client-provided owner alone.
drop policy music_owner_read on storage.objects;
create policy music_confirmed_read on storage.objects for select to authenticated using(bucket_id='music-private' and exists(select 1 from public.cloud_objects c where c.object_path=name and c.user_id=(select auth.uid()) and c.status='ready'));
create policy music_reserved_insert on storage.objects for insert to authenticated with check(bucket_id='music-private' and exists(select 1 from public.cloud_objects c where c.object_path=name and c.user_id=(select auth.uid()) and c.status='pending' and c.expires_at>now()));
-- Serialize object creation with server-side cancellation/deletion status changes.
create function public.guard_reserved_object() returns trigger language plpgsql security definer set search_path='' as $$
declare reservation public.cloud_objects;
begin
 if new.bucket_id<>'music-private' then return new; end if;
 select * into reservation from public.cloud_objects where object_path=new.name for update;
 if not found or reservation.status<>'pending' or reservation.expires_at<=now() then raise exception 'Upload reservation unavailable'; end if;
 return new;
end $$;
create trigger guard_music_insert before insert on storage.objects for each row execute function public.guard_reserved_object();
revoke all on function public.guard_reserved_object() from public,anon,authenticated;
update storage.buckets set file_size_limit=26214400 where id='music-private';
commit;


begin;
create function public.confirm_cloud_object(object_id uuid,owner_id uuid) returns void language plpgsql security definer set search_path='' as $$
declare item uuid;
begin
 update public.cloud_objects set status='ready',updated_at=now() where id=object_id and user_id=owner_id and status='verifying' returning library_item_id into item;
 if not found then raise exception 'Reservation changed'; end if;
 update public.library_items set cloud_object_id=object_id,version=version+1 where id=item and user_id=owner_id;
end $$;
create function public.finish_cloud_deletion(object_id uuid,owner_id uuid) returns void language plpgsql security definer set search_path='' as $$
begin
 update public.cloud_objects set status='deleted',updated_at=now() where id=object_id and user_id=owner_id and status='deleting';
 if not found then raise exception 'Deletion not started'; end if;
 update public.library_items set cloud_object_id=null,version=version+1 where cloud_object_id=object_id and user_id=owner_id;
end $$;
revoke all on function public.confirm_cloud_object(uuid,uuid),public.finish_cloud_deletion(uuid,uuid) from public,anon,authenticated;
grant execute on function public.confirm_cloud_object(uuid,uuid),public.finish_cloud_deletion(uuid,uuid) to service_role;
commit;
