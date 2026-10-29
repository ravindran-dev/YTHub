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
