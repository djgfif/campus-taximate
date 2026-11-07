-- 웹 푸시 구독과 승차 10분 전 안내 기록
create table push_subscription (created_at datetime(6) not null, id bigint not null auto_increment, member_id bigint not null, auth varchar(100) not null, p256dh varchar(200) not null, endpoint varchar(500) not null, primary key (id)) engine=InnoDB;
alter table push_subscription add constraint uk_push_subscription_endpoint unique (endpoint);
alter table push_subscription add constraint fk_push_subscription_member foreign key (member_id) references member (id);
alter table ride_request add column reminded_at datetime(6);
