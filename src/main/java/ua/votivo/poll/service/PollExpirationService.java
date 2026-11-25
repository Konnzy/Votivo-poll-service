package ua.votivo.poll.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.votivo.poll.event.PollEvents;
import ua.votivo.poll.model.enums.PollStatus;
import ua.votivo.poll.repository.PollRepository;
import java.time.Instant;
import java.util.UUID;

@Service
public class PollExpirationService {
    private final PollRepository polls;
    private final PollEvents events;

    public PollExpirationService(PollRepository polls, PollEvents events) {
        this.polls = polls;
        this.events = events;
    }

    @Transactional
    public void closeIfDue(UUID id, Instant now) {
        polls.findForExpiration(id).ifPresent(poll -> {
            if (poll.getStatus() != PollStatus.PUBLISHED || poll.isBlocked()
                    || poll.getEndsAt() == null || poll.getEndsAt().isAfter(now)) return;
            poll.close();
            events.pollEnded(poll, PollEvents.REASON_CLOSED);
        });
    }
}
