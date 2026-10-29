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
