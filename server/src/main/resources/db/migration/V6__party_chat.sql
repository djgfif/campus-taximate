alter table party add column settled_at datetime(6);

create table party_message (
    id bigint not null auto_increment,
    party_id bigint not null,
    member_id bigint not null,
    text varchar(200) not null,
    created_at datetime(6) not null,
    primary key (id),
    constraint fk_party_message_party foreign key (party_id) references party (id) on delete cascade,
    constraint fk_party_message_member foreign key (member_id) references member (id)
);
create index idx_party_message_party_id on party_message (party_id, id);
