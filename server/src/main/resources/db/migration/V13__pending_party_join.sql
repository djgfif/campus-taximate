-- 합류자의 승인 전에는 기존 일행을 바꾸지 않는다. 좌석 예약은 일행 잠금과 이 포인터로 직렬화한다.
alter table party add column pending_join_id bigint;
alter table ride_request add column pending_join_id bigint;
alter table ride_request modify column status enum ('WAITING','JOINING','MATCHED','COMPLETED','UNMATCHED','CANCELED') not null;
create table party_join (
 id bigint not null auto_increment primary key, party_id bigint, request_id bigint, member_id bigint, payment_order_id bigint unique,
 state varchar(255), offered_at datetime(6), deadline_at datetime(6), base_group_version bigint not null,
 payer_id bigint, departure_at datetime(6), total_fare bigint not null
);
create index idx_party_join_state on party_join(state,deadline_at);
create table party_join_member (
 id bigint not null auto_increment primary key, join_id bigint, request_id bigint, ride_order integer not null,
 ride_seconds integer not null, ratio_units bigint not null, expected_share bigint not null,
 constraint uk_join_member unique(join_id,request_id),constraint fk_join_member_join foreign key(join_id) references party_join(id)
);
