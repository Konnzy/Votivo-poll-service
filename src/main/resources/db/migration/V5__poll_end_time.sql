ALTER TABLE polls ADD COLUMN ends_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX idx_polls_due ON polls (status, blocked, ends_at);
