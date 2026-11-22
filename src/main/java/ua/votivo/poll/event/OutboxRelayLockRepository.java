package ua.votivo.poll.event;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import java.util.Optional;

public interface OutboxRelayLockRepository extends JpaRepository<OutboxRelayLock, Integer> {
    /** Skip the singleton lock when another instance is publishing; never skip individual events. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select relayLock from OutboxRelayLock relayLock where relayLock.id = 1")
    Optional<OutboxRelayLock> tryAcquire();
}
