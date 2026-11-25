package ua.votivo.poll.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ua.votivo.poll.model.Poll;
import ua.votivo.poll.model.enums.AccessType;
import ua.votivo.poll.model.enums.PollStatus;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface PollRepository extends JpaRepository<Poll, UUID> {
    @Query("select p.id from Poll p where p.status = :status and p.blocked = false and p.endsAt <= :now order by p.endsAt, p.id")
    List<UUID> findDueIds(PollStatus status, Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Poll p where p.id = :id")
    Optional<Poll> findForExpiration(UUID id);
    boolean existsByOwnerIdAndStatus(String ownerId, PollStatus status);
    Page<Poll> findByOwnerId(String ownerId, Pageable pageable);
    Page<Poll> findByAccessTypeAndStatusAndBlockedFalse(AccessType accessType, PollStatus status, Pageable pageable);
    Optional<Poll> findByShareToken(UUID shareToken);
}
