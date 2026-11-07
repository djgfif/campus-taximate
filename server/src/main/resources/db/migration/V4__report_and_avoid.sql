create table member_avoid (
    id bigint not null auto_increment primary key,
    member_id bigint not null,
    avoided_member_id bigint not null,
    created_at datetime(6) not null,
    constraint uk_member_avoid unique (member_id, avoided_member_id),
    constraint fk_avoid_owner foreign key (member_id) references member(id),
    constraint fk_avoid_target foreign key (avoided_member_id) references member(id)
);
create table member_report (
    id bigint not null auto_increment primary key,
    reporter_id bigint not null,
    reported_id bigint not null,
    party_id bigint not null,
    reason varchar(20) not null,
    memo varchar(200),
    created_at datetime(6) not null,
    constraint uk_member_report unique (reporter_id, reported_id, party_id),
    constraint fk_report_actor foreign key (reporter_id) references member(id),
    constraint fk_report_target foreign key (reported_id) references member(id),
    constraint fk_report_party foreign key (party_id) references party(id) on delete cascade
);
create index idx_report_target on member_report (reported_id, party_id);
create table member_restriction (
    id bigint not null auto_increment primary key,
    member_id bigint not null,
    reason varchar(30) not null,
    created_at datetime(6) not null,
    expires_at datetime(6) not null,
    constraint fk_restriction_member foreign key (member_id) references member(id)
);
create index idx_restriction_member on member_restriction (member_id, expires_at);
-- 취소/노쇼 뒤 ride_request.party_id가 지워져도 최초 배정 권한을 보존한다.
create table party_assignment (
    id bigint not null auto_increment primary key,
    party_id bigint not null,
    member_id bigint not null,
    request_id bigint not null,
    constraint uk_party_assignment unique (party_id, member_id),
    constraint fk_assignment_party foreign key (party_id) references party(id) on delete cascade,
    constraint fk_assignment_member foreign key (member_id) references member(id)
);
insert into party_assignment (party_id, member_id, request_id)
    select party_id, member_id, id from ride_request where party_id is not null;
alter table transfer_request add column reminded_at datetime(6);
