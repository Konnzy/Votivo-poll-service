-- The publisher holds this row until confirms and outbox deletions commit together.
-- Other instances skip this lock, not individual messages, so ended cannot overtake started.
CREATE TABLE outbox_relay_lock (
    id INTEGER PRIMARY KEY CHECK (id = 1)
);
INSERT INTO outbox_relay_lock (id) VALUES (1);
