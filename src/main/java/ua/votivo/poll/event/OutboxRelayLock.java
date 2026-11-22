package ua.votivo.poll.event;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One database row elects a publisher for the duration of a relay transaction. */
@Entity
@Table(name = "outbox_relay_lock")
public class OutboxRelayLock {
    @Id
    private int id;

    protected OutboxRelayLock() { }
}
