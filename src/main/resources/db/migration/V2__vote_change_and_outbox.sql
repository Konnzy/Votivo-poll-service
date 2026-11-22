-- Whether a participant may change or revoke a vote while the poll is published. Vote Service enforces it.
ALTER TABLE polls ADD COLUMN allow_vote_change BOOLEAN NOT NULL DEFAULT FALSE;

-- Events are written in the transaction of the change they describe and published to RabbitMQ afterwards,
-- so a crash between the commit and the publication cannot lose an event (transactional outbox).
-- The id is the event id: it travels as the AMQP message_id and lets a consumer drop a redelivered message.
CREATE TABLE outbox_messages (
    id UUID PRIMARY KEY,
    routing_key VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(1000)
);

CREATE INDEX idx_outbox_created ON outbox_messages(created_at, id);
