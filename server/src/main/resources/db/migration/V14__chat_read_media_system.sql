-- 일행 채팅: 시스템 알림(보낸 사람 없음), 사진, 중복 전송 방지, 읽음 위치.
alter table party_message modify column member_id bigint null;
alter table party_message add column kind varchar(10) not null default 'TEXT';
alter table party_message add column image_path varchar(255);
alter table party_message add column image_type varchar(40);
alter table party_message add column client_id varchar(64);
create unique index uk_party_message_client on party_message (party_id, member_id, client_id);

create table party_chat_read (
    id bigint not null auto_increment,
    party_id bigint not null,
    member_id bigint not null,
    last_read_id bigint not null,
    primary key (id),
    constraint uk_party_chat_read unique (party_id, member_id),
    constraint fk_chat_read_party foreign key (party_id) references party (id) on delete cascade,
    constraint fk_chat_read_member foreign key (member_id) references member (id)
);
