-- 원본 확정값은 V9의 스냅샷을 유지한다. 관리자 권한은 명시적으로만 부여한다.
alter table member add column admin bit not null default false;
alter table party modify column status enum ('CANCELED','COMPLETED','CONFIRMED','SETTLING','UNSETTLED','PREPAYING','READY','AWAITING_FARE','OBJECTION_WINDOW','REVIEW_PENDING','SETTLED','DISSOLVED','NOT_OPERATED','FARE_MISSING','RIDE_UNCONFIRMED','PAYER_PENDING') not null;
alter table party add column original_payer_id bigint;
alter table party add column successor_id bigint;
alter table party add column succession_deadline_at datetime(6);
alter table party add column succession_status varchar(255);
update party set original_payer_id=payer_id;
alter table settlement_share add column raw_amount bigint;
alter table settlement_share add column compensation_credit bigint not null default 0;
alter table fare_objection add column resolved_at datetime(6);
alter table fare_objection add column resolution varchar(255);
alter table fare_objection add column admin_id bigint;
alter table payment_order add column admin_handled_at datetime(6);
alter table payment_order add column admin_handled_by bigint;
alter table payment_order add column admin_note varchar(2000);
create table boarding_case (
 id bigint not null auto_increment primary key, party_id bigint, request_id bigint, member_id bigint,
 kind varchar(255), status varchar(255), opened_at datetime(6), deadline_at datetime(6),
 decided_at datetime(6), decided_by bigint, decision_reason varchar(2000), no_compensation bit not null default false,
 constraint uk_boarding_case unique(party_id,request_id),
 constraint fk_boarding_case_party foreign key(party_id) references party(id),
 constraint fk_boarding_case_request foreign key(request_id) references ride_request(id)
);
create index idx_boarding_case_due on boarding_case(status,deadline_at);
-- 기존 새 흐름에서 보류한 직전 취소도 확정 때 상한으로 정산한다. 이미 끝난 정산은 소급하지 않는다.
insert into boarding_case(party_id,request_id,member_id,kind,status,opened_at,deadline_at,decided_at,decision_reason,no_compensation)
 select t.party_id,t.request_id,t.member_id,'LATE_CANCEL','CONFIRMED',t.exited_at,t.exited_at,t.exited_at,'직전 취소 보류 이관',
 case when t.member_id=p.original_payer_id then true else false end
 from party_member_term t join party p on p.id=t.party_id
 where t.refund_held=true and t.exit_reason='LATE_CANCEL' and p.payment_flow_version>=2
 and p.status not in ('SETTLED','COMPLETED');
create table boarding_vote (
 id bigint not null auto_increment primary key, case_id bigint, actor_id bigint, came bit not null, created_at datetime(6),
 constraint uk_boarding_vote unique(case_id,actor_id), constraint fk_boarding_vote_case foreign key(case_id) references boarding_case(id)
);
create table boarding_explanation (
 id bigint not null auto_increment primary key, case_id bigint, member_id bigint, explanation varchar(2000), created_at datetime(6),
 constraint uk_boarding_explanation unique(case_id), constraint fk_boarding_explanation_case foreign key(case_id) references boarding_case(id)
);
create table compensation_entry (
 id bigint not null auto_increment primary key, party_id bigint, settlement_id bigint, request_id bigint, member_id bigint,
 raw_share bigint not null, credit bigint not null, charge bigint not null, net_share bigint not null,
 constraint uk_compensation_entry unique(settlement_id,request_id), constraint fk_compensation_settlement foreign key(settlement_id) references settlement(id)
);
-- 관리자 처리 이력은 대상의 보관 기간과 별개로 남긴다.
create table admin_audit (
 id bigint not null auto_increment primary key, actor_id bigint, created_at datetime(6), action varchar(255), target_type varchar(255), target_id bigint,
 decision varchar(255), evidence varchar(2000), operation_key varchar(255), fingerprint varchar(2000),
 constraint uk_admin_audit_key unique(operation_key)
);
