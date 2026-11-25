package ua.votivo.poll.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import ua.votivo.poll.model.PollOwnerLifecycle;

public interface PollOwnerLifecycleRepository extends JpaRepository<PollOwnerLifecycle, String> {
    @Modifying
    @Query(value = "insert into poll_owner_lifecycle(owner_id, deletion_reserved) values (:owner, false) on conflict do nothing", nativeQuery = true)
    void ensureOwner(String owner);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PollOwnerLifecycle o where o.ownerId = :owner")
    Optional<PollOwnerLifecycle> lockOwner(String owner);
}
