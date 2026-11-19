alter table party add column route_verification_deadline_at datetime(6);
update party set route_verification_deadline_at = departure_at where route_verification_pending = true;
create index idx_party_route_verification on party(route_verification_pending, route_verification_deadline_at);
alter table ride_request modify column end_reason enum ('LEFT_ALONE','NO_SHOW','PARTY_CANCELED','ROUTE_VERIFICATION_EXPIRED');
