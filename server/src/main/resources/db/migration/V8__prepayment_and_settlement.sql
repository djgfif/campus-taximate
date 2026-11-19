-- 기존 일행은 송금 방식(0)을 유지하고 새 확정 일행만 선결제 방식(2)을 사용한다.
alter table party modify column status enum ('CANCELED','COMPLETED','CONFIRMED','SETTLING','UNSETTLED','PREPAYING','READY','AWAITING_FARE','OBJECTION_WINDOW','REVIEW_PENDING','SETTLED','DISSOLVED','NOT_OPERATED','FARE_MISSING') not null;
alter table party add column payment_flow_version integer not null default 0;
alter table party add column confirmed_at datetime(6);
alter table party add column prepay_deadline_at datetime(6);
alter table party add column started_at datetime(6);
alter table party add column fare_reminded_at datetime(6);
alter table settlement add column objection_deadline_at datetime(6);
alter table settlement add column finalized_at datetime(6);
create index idx_party_payment_due on party(status, prepay_deadline_at);

create table payment_order (
    id bigint not null auto_increment primary key, version bigint not null,
    order_id varchar(255) not null unique, payment_key varchar(255) unique,
    party_id bigint not null, member_id bigint not null, request_id bigint, settlement_id bigint,
    purpose varchar(255) not null, status varchar(255) not null,
    amount bigint not null, approved_amount bigint not null, refunded_amount bigint not null, refund_target bigint not null,
    deadline_at datetime(6) not null, approved_at datetime(6), created_at datetime(6) not null,
    constraint uk_payment_obligation unique(party_id,member_id,purpose),
    constraint fk_payment_party foreign key(party_id) references party(id),
    constraint fk_payment_member foreign key(member_id) references member(id),
    constraint fk_payment_request foreign key(request_id) references ride_request(id),
    constraint fk_payment_settlement foreign key(settlement_id) references settlement(id)
);
create index idx_payment_due on payment_order(status,deadline_at);
create index idx_payment_member on payment_order(member_id,purpose,status);
create table payment_operation (
    id bigint not null auto_increment primary key, version bigint not null, payment_order_id bigint not null,
    idempotency_key varchar(255) not null unique, type varchar(255) not null, status varchar(255) not null,
    amount bigint not null, target_refund bigint not null, reason varchar(255), attempts integer not null,
    next_attempt_at datetime(6) not null, lease_until datetime(6), completed_at datetime(6), last_error varchar(255),
    constraint fk_operation_order foreign key(payment_order_id) references payment_order(id)
);
create index idx_payment_operation_due on payment_operation(status,next_attempt_at);
create table settlement_share (
    id bigint not null auto_increment primary key, settlement_id bigint not null, party_id bigint not null, member_id bigint not null,
    ratio_units bigint not null, ride_order integer not null, amount bigint not null, payer bit not null,
    constraint uk_settlement_share unique(settlement_id,member_id),
    constraint fk_share_settlement foreign key(settlement_id) references settlement(id),
    constraint fk_share_party foreign key(party_id) references party(id),
    constraint fk_share_member foreign key(member_id) references member(id)
);
create table fare_objection (
    id bigint not null auto_increment primary key, settlement_id bigint not null, member_id bigint not null,
    reason varchar(500) not null, created_at datetime(6) not null,
    constraint uk_fare_objection unique(settlement_id,member_id),
    constraint fk_objection_settlement foreign key(settlement_id) references settlement(id),
    constraint fk_objection_member foreign key(member_id) references member(id)
);
create table payout_ledger (
    id bigint not null auto_increment primary key, settlement_id bigint not null unique, party_id bigint not null, member_id bigint not null,
    amount bigint not null, recorded_at datetime(6) not null,
    constraint fk_payout_settlement foreign key(settlement_id) references settlement(id),
    constraint fk_payout_party foreign key(party_id) references party(id),
    constraint fk_payout_member foreign key(member_id) references member(id)
);
