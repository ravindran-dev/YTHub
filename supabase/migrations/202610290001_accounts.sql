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
