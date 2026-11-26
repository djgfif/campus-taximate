create table payment_method (
    id bigint not null auto_increment primary key,
    member_id bigint not null,
    provider varchar(30) not null,
    billing_key varchar(512) not null,
    card_label varchar(100) not null,
    active bit not null default true,
    registered_at datetime(6) not null,
    deleted_at datetime(6) null,
    constraint fk_payment_method_member foreign key(member_id) references member(id)
);
create index idx_payment_method_member on payment_method(member_id, active, deleted_at);

create table settlement_confirmation (
    id bigint not null auto_increment primary key,
    settlement_id bigint not null,
    member_id bigint not null,
    revision integer not null,
    confirmed_at datetime(6) not null,
    constraint fk_settlement_confirm_settlement foreign key(settlement_id) references settlement(id),
    constraint fk_settlement_confirm_member foreign key(member_id) references member(id),
    constraint uk_settlement_confirm unique(settlement_id, member_id, revision)
);

alter table party add column free_cancel_until datetime(6) null;
update party set free_cancel_until = prepay_deadline_at where prepay_deadline_at is not null;

alter table payment_order add column settlement_revision integer null;

alter table payout_ledger add column expected_amount bigint not null default 0;
alter table payout_ledger add column collected_amount bigint not null default 0;
update payout_ledger set expected_amount = amount, collected_amount = amount;
