package ua.votivo.poll.dto.response;

import ua.votivo.poll.model.enums.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PollResponse(UUID id, String ownerId, String title, String description,
                           PollStatus status, AccessType accessType, boolean anonymous,
                           boolean allowVoteChange, boolean blocked, UUID shareToken, Instant createdAt, Instant updatedAt,
                           Instant publishedAt, Instant closedAt, Instant archivedAt,
                           List<QuestionResponse> questions) {
    public record QuestionResponse(UUID id, String text, QuestionType type,
                                   List<AnswerOptionResponse> options) { }
    public record AnswerOptionResponse(UUID id, String text) { }
}
