ALTER TABLE party ADD COLUMN boarding_code VARCHAR(6);
ALTER TABLE party ADD COLUMN boarding_generation INT NOT NULL DEFAULT 0;
ALTER TABLE party ADD COLUMN boarding_deadline_at DATETIME(6);
ALTER TABLE party ADD COLUMN boarding_closed_at DATETIME(6);
ALTER TABLE ride_request ADD COLUMN boarding_confirmed_at DATETIME(6);
CREATE TABLE boarding_attempt (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 party_id BIGINT NOT NULL,
 member_id BIGINT NOT NULL,
 generation INT NOT NULL,
 failures INT NOT NULL DEFAULT 0,
 CONSTRAINT uk_boarding_attempt UNIQUE (party_id, member_id, generation)
);
