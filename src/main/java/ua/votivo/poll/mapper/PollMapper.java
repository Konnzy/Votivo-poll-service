package ua.votivo.poll.mapper;

import org.springframework.stereotype.Component;
import ua.votivo.poll.dto.request.PollRequest;
import ua.votivo.poll.dto.response.PollResponse;
import ua.votivo.poll.dto.response.PollSummaryResponse;
import ua.votivo.poll.model.*;
import java.util.List;

@Component
public class PollMapper {
    public List<Question> questions(PollRequest request) {
        return request.questions().stream().map(q -> new Question(q.text(), q.type(),
                q.options().stream().map(o -> new AnswerOption(o.text())).toList())).toList();
    }

    public PollResponse response(Poll poll, boolean includeShareToken) {
        return new PollResponse(poll.getId(), poll.getOwnerId(), poll.getTitle(), poll.getDescription(),
                poll.getStatus(), poll.getAccessType(), poll.isAnonymous(), poll.isAllowVoteChange(), poll.isBlocked(),
                includeShareToken ? poll.getShareToken() : null,
                poll.getCreatedAt(), poll.getUpdatedAt(), poll.getPublishedAt(), poll.getClosedAt(),
                poll.getArchivedAt(), poll.getEndsAt(), poll.getQuestions().stream().map(q ->
                new PollResponse.QuestionResponse(q.getId(), q.getText(), q.getType(),
                        q.getOptions().stream().map(o ->
                                new PollResponse.AnswerOptionResponse(o.getId(), o.getText())).toList())).toList());
    }

    public PollSummaryResponse summary(Poll poll) {
        return new PollSummaryResponse(poll.getId(), poll.getTitle(), poll.getStatus(),
                poll.getAccessType(), poll.isAnonymous(), poll.isBlocked(), poll.getCreatedAt(), poll.getEndsAt());
    }
}
