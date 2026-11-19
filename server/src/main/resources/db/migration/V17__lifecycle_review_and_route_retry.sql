ALTER TABLE party ADD COLUMN fare_recovery_at DATETIME(6);
ALTER TABLE party ADD COLUMN route_verification_pending BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ride_request ADD COLUMN pending_exit_reason VARCHAR(255);
ALTER TABLE ride_request ADD COLUMN exit_requested_at DATETIME(6);
