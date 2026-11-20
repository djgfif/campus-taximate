-- 확정 분담금(S43): 정산마다 (받은 확정 몫 − 결제자에게 돌려준 금액)을 정산 적립금에 기록한다.
-- covered는 원래라면 추가 결제가 됐을 부족분을 적립금이 메운 금액이다.
create table reserve_entry (
    id bigint not null auto_increment primary key,
    settlement_id bigint not null unique,
    party_id bigint not null,
    amount bigint not null,
    covered bigint not null,
    paid_amount bigint not null,
    fixed_total bigint not null,
    recorded_at datetime(6) not null,
    constraint fk_reserve_settlement foreign key(settlement_id) references settlement(id),
    constraint fk_reserve_party foreign key(party_id) references party(id)
);
