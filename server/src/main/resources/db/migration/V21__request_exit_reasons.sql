alter table ride_request modify column end_reason enum ('LEFT_ALONE','NO_SHOW','PARTY_CANCELED','ROUTE_VERIFICATION_EXPIRED','BOARDING_UNCONFIRMED','PREPAY_UNPAID','FREE_CANCEL','CHANGE_EXIT_FREE','LATE_CANCEL');

-- Restore reasons for already excluded requests from their durable membership history.
-- A request may have belonged to several parties; use its most recent exit only.
update ride_request r
set end_reason = (
    select case t.exit_reason when 'PAYER_NO_SHOW' then 'BOARDING_UNCONFIRMED' else t.exit_reason end
    from party_member_term t
    where t.request_id = r.id and t.exited_at is not null
      and t.id = (select max(last_term.id) from party_member_term last_term
                  where last_term.request_id = r.id and last_term.exited_at is not null)
)
where r.party_id is null and r.status = 'CANCELED' and r.end_reason is null
  and exists (select 1 from party_member_term t where t.request_id = r.id
      and t.id = (select max(last_term.id) from party_member_term last_term
                  where last_term.request_id = r.id and last_term.exited_at is not null)
      and t.exit_reason in ('BOARDING_UNCONFIRMED','PAYER_NO_SHOW','PREPAY_UNPAID','FREE_CANCEL','CHANGE_EXIT_FREE','LATE_CANCEL','ROUTE_VERIFICATION_EXPIRED'));
