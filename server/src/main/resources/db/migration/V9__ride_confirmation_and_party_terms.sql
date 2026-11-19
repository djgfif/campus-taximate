-- 기존 값은 지우지 않고 새 운행 확인 상태를 확장한다.
alter table party modify column status enum ('CANCELED','COMPLETED','CONFIRMED','SETTLING','UNSETTLED','PREPAYING','READY','AWAITING_FARE','OBJECTION_WINDOW','REVIEW_PENDING','SETTLED','DISSOLVED','NOT_OPERATED','FARE_MISSING','RIDE_UNCONFIRMED') not null;
alter table party add column group_version bigint not null default 1;
alter table party add column ride_asked_at datetime(6);
alter table party add column review_reason varchar(255);
alter table party add column admin_review_required bit not null default false;
alter table payment_order add column excluded_at datetime(6);
alter table payment_order add column last_checked_at datetime(6);
alter table payment_order add column gateway_status varchar(255);
create table party_member_term (
 id bigint not null auto_increment primary key, party_id bigint not null, request_id bigint not null, member_id bigint not null,
 confirmed_share bigint not null, confirmed_prepay bigint not null, migrated_unknown bit not null,
 boarding_unconfirmed_at datetime(6), exited_at datetime(6), exit_reason varchar(255), refund_held bit not null,
 constraint uk_party_member_term unique(party_id,request_id),
 constraint fk_term_party foreign key(party_id) references party(id),
 constraint fk_term_request foreign key(request_id) references ride_request(id),
 constraint fk_term_member foreign key(member_id) references member(id)
);
-- 원래 확정값을 추측하지 않는다. 이관 시점 현재값임을 별도 표시한다.
insert into party_member_term(party_id,request_id,member_id,confirmed_share,confirmed_prepay,migrated_unknown,refund_held)
 select r.party_id,r.id,r.member_id,coalesce(r.expected_share,0),coalesce(o.amount,0),true,false
 from ride_request r left join payment_order o on o.party_id=r.party_id and o.request_id=r.id and o.purpose='PREPAY'
 where r.party_id is not null;
create table party_change (
 id bigint not null auto_increment primary key, party_id bigint not null, group_version bigint not null,
 reason varchar(255), changed_at datetime(6),
 constraint uk_party_change unique(party_id,group_version),
 constraint fk_change_party foreign key(party_id) references party(id)
);
create table ride_confirmation (
 id bigint not null auto_increment primary key, party_id bigint not null, member_id bigint not null,
 rode bit not null, answered_at datetime(6),
 constraint uk_ride_confirmation unique(party_id,member_id),
 constraint fk_ride_answer_party foreign key(party_id) references party(id),
 constraint fk_ride_answer_member foreign key(member_id) references member(id)
);
