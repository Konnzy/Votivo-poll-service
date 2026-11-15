package ua.votivo.poll.dto.response;

import ua.votivo.poll.model.enums.AccessType;
import ua.votivo.poll.model.enums.PollStatus;
import java.time.Instant;
import java.util.UUID;

public record PollSummaryResponse(UUID id, String title, PollStatus status, AccessType accessType,
                                  boolean anonymous, boolean blocked, Instant createdAt) { }
