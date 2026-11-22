package ua.votivo.poll.event;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {
    /**
     * Locks the oldest messages. The lock timeout -2 is SKIP LOCKED: several instances can relay side by side
     * without publishing the same message twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    List<OutboxMessage> findTop100ByOrderByCreatedAtAscIdAsc();
}
