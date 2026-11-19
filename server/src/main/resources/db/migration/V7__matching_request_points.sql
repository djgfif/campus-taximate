-- 3차 매칭: 기존 시연 요청의 요청 시각은 이관 시점으로 채운다.
alter table ride_request add column mode enum ('RESERVATION','INSTANT') not null default 'RESERVATION';
alter table ride_request add column time_basis enum ('DEPART','ARRIVE') not null default 'DEPART';
alter table ride_request add column early_arrival_minutes integer not null default 10;
alter table ride_request add column origin_lat float(53);
alter table ride_request add column origin_lon float(53);
alter table ride_request add column origin_name varchar(255);
alter table ride_request add column requested_at datetime(6) not null default CURRENT_TIMESTAMP(6);

alter table hub add column field_checked bit not null default true;
alter table hub add column active bit not null default true;

create table ride_request_point (
    request_id bigint not null,
    hub_id bigint not null,
    walk_seconds integer not null,
    walk_estimated bit not null,
    anchor_km float(53) not null,
    anchor_seconds integer not null,
    primary key (request_id, hub_id),
    constraint fk_request_point_request foreign key (request_id) references ride_request(id) on delete cascade,
    constraint fk_request_point_hub foreign key (hub_id) references hub(id)
);
insert into ride_request_point (request_id, hub_id, walk_seconds, walk_estimated, anchor_km, anchor_seconds)
    select id, hub_id, 0, true, solo_km, solo_seconds from ride_request where hub_id is not null;
-- party.hub_id와 party.departure_at은 V1에 이미 있다.
