alter table party add column instant_group bit not null default false;
alter table party add column called_at datetime(6);
alter table ride_request add column nearby_at datetime(6);
update party set instant_group=true where id in (select party_id from ride_request where mode='INSTANT' and party_id is not null);
update party set instant_group=true where id in (select o.party_id from payment_order o join ride_request r on r.id=o.request_id where r.mode='INSTANT');
-- 이관된 즉시 요청도 S37 모집 마감으로 표시한다.
update ride_request set deadline_slot=timestampadd(minute,5,requested_at) where mode='INSTANT';
