package ua.votivo.poll.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ua.votivo.poll.model.Poll;
import ua.votivo.poll.model.enums.AccessType;
import ua.votivo.poll.model.enums.PollStatus;
import java.util.Optional;
import java.util.UUID;

public interface PollRepository extends JpaRepository<Poll, UUID> {
    Page<Poll> findByOwnerId(String ownerId, Pageable pageable);
    Page<Poll> findByAccessTypeAndStatusAndBlockedFalse(AccessType accessType, PollStatus status, Pageable pageable);
    Optional<Poll> findByShareToken(UUID shareToken);
}
