alter table party add column boarding_code varchar(6);
alter table party add column boarding_code_expires_at datetime(6);
alter table ride_request add column boarding_code_failures integer not null default 0;
