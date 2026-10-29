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
