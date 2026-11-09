-- Reliable chat synchronization, historical access boundaries and report evidence.
alter table party add column chat_closed_at datetime(6);

alter table party_assignment
    add column left_at datetime(6),
    add column last_visible_message_id bigint;

alter table party_message add column client_message_id varchar(64);
create unique index uk_party_message_client on party_message (party_id, member_id, client_message_id);

alter table member_report
    add column message_id bigint,
    add column message_snapshot varchar(200);
