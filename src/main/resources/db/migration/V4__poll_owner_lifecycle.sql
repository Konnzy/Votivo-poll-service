CREATE TABLE poll_owner_lifecycle (
    owner_id VARCHAR(255) PRIMARY KEY,
    deletion_reserved BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_polls_owner_status ON polls(owner_id, status);
