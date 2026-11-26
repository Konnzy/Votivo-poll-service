package ua.votivo.poll.service;

import org.springframework.stereotype.Service;
import ua.votivo.poll.exception.InvalidPollStateException;
import ua.votivo.poll.model.Poll;
import ua.votivo.poll.model.enums.PollStatus;

@Service
public class PollValidationService {
    public void requireState(Poll poll, PollStatus expected) {
        if (poll.getStatus() != expected) {
            throw new InvalidPollStateException("Operation requires state " + expected);
        }
    }

    public void requireEditable(Poll poll) {
        requireState(poll, PollStatus.DRAFT);
        requireNotBlocked(poll);
    }

    public void requireNotBlocked(Poll poll) {
        if (poll.isBlocked()) throw new InvalidPollStateException("Poll is blocked");
    }

    public void requirePublishable(Poll poll) {
        requireEditable(poll);
        var endsAt = poll.getEndsAt();
        if (endsAt != null && !endsAt.isAfter(java.time.Instant.now())) {
            throw new InvalidPollStateException("Update or remove the past end date before publishing the poll");
        }
        if (poll.getQuestions().isEmpty() || poll.getQuestions().stream().anyMatch(q -> q.getOptions().size() < 2)) {
            throw new InvalidPollStateException("Publication requires at least one question and two options per question");
        }
    }
}
