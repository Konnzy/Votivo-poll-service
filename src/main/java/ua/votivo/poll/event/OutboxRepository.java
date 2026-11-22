package ua.votivo.poll.event;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {
    /** Called only after acquiring the singleton relay lock in the same transaction. */
    List<OutboxMessage> findTop100ByOrderByCreatedAtAscIdAsc();
}
