package ua.votivo.poll.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ua.votivo.poll.model.enums.PollStatus;
import ua.votivo.poll.repository.PollRepository;
import java.time.Instant;

@Component
@ConditionalOnProperty(name = "app.expiration.enabled", havingValue = "true", matchIfMissing = true)
public class PollExpirationJob {
    private static final Logger log = LoggerFactory.getLogger(PollExpirationJob.class);
    private final PollRepository polls;
    private final PollExpirationService expiration;

    public PollExpirationJob(PollRepository polls, PollExpirationService expiration) {
        this.polls = polls;
        this.expiration = expiration;
    }

    @Scheduled(fixedDelayString = "${app.expiration.interval:5s}")
    public void closeDuePolls() {
        Instant now = Instant.now();
        for (var id : polls.findDueIds(PollStatus.PUBLISHED, now, PageRequest.of(0, 100))) {
            try {
                expiration.closeIfDue(id, now);
            } catch (RuntimeException error) {
                log.error("Failed to close expired poll {}", id, error);
            }
        }
    }
}
