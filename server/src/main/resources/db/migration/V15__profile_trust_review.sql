-- 사람 신뢰 정보: 학과·학번(공개 선택)과 탑승 뒤 칭찬.
alter table member add column department varchar(40);
alter table member add column admission_year int;
alter table member add column show_profile bit not null default true;

create table ride_review (
    id bigint not null auto_increment,
    party_id bigint not null,
    reviewer_id bigint not null,
    target_id bigint not null,
    tag varchar(20) not null,
    created_at datetime(6) not null,
    primary key (id),
    constraint uk_ride_review unique (party_id, reviewer_id, target_id, tag),
    constraint fk_review_party foreign key (party_id) references party (id) on delete cascade,
    constraint fk_review_reviewer foreign key (reviewer_id) references member (id),
    constraint fk_review_target foreign key (target_id) references member (id)
);
create index idx_ride_review_target on ride_review (target_id);
