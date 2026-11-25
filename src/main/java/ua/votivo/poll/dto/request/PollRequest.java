package ua.votivo.poll.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import ua.votivo.poll.model.enums.AccessType;
import ua.votivo.poll.model.enums.QuestionType;
import java.util.List;
import java.time.Instant;

public record PollRequest(
        @NotBlank @Size(max = 200) String title,
        @NotNull @Size(max = 5000) String description,
        @NotNull AccessType accessType,
        @NotNull Boolean anonymous,
        Boolean allowVoteChange,
        @Future Instant endsAt,
        @NotNull @Size(max = 50) List<@NotNull @Valid QuestionRequest> questions) {

    public record QuestionRequest(
            @NotBlank @Size(max = 2000) String text,
            @NotNull QuestionType type,
            @NotNull @Size(max = 20) List<@NotNull @Valid AnswerOptionRequest> options) { }

    public record AnswerOptionRequest(@NotBlank @Size(max = 1000) String text) { }
}
