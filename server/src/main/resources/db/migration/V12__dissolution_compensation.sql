-- S38 해산 보전은 실제 운행 정산 없이 확정한다. 같은 일행에는 지급 장부를 한 번만 만든다.
alter table payout_ledger drop foreign key fk_payout_settlement;
alter table payout_ledger modify column settlement_id bigint null;
alter table payout_ledger add constraint fk_payout_settlement foreign key(settlement_id) references settlement(id);
alter table payout_ledger add constraint uk_payout_party unique(party_id);
alter table compensation_entry add constraint uk_compensation_party_request unique(party_id,request_id);
