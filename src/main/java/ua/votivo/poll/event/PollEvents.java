package ua.votivo.poll.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import ua.votivo.poll.model.Poll;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the events about a poll to the outbox in the transaction of the change itself, so an event exists
 * if and only if the change was committed. {@link OutboxRelay} publishes them.
 *
 * <p>Contract (JSON, snake_case; see docs/events.md in the integration repository):
 * <ul>
 *   <li>{@code poll.started}: the whole snapshot voting needs. The content of a published poll cannot change,
 *       so one event is enough for Vote Service and Result Service.</li>
 *   <li>{@code poll.ended}: {@code poll_id} and {@code reason} ({@code closed} or {@code blocked}).</li>
 * </ul>
 */
@Component
public class PollEvents {
    public static final String REASON_CLOSED = "closed";
    public static final String REASON_BLOCKED = "blocked";

    private final OutboxRepository outbox;
    private final JsonMapper json;

    public PollEvents(OutboxRepository outbox, JsonMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void pollStarted(Poll poll) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("poll_id", poll.getId());
        event.put("title", poll.getTitle());
        event.put("anonymous", poll.isAnonymous());
        event.put("allow_vote_change", poll.isAllowVoteChange());
        event.put("ends_at", poll.getEndsAt());
        event.put("questions", poll.getQuestions().stream().map(question -> {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("id", question.getId());
            q.put("text", question.getText());
            q.put("multiple", question.getType() == ua.votivo.poll.model.enums.QuestionType.MULTIPLE);
            q.put("options", question.getOptions().stream().map(option -> {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", option.getId());
                o.put("text", option.getText());
                return o;
            }).toList());
            return q;
        }).toList());
        enqueue(EventTopology.POLL_STARTED, event);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void pollEnded(Poll poll, String reason) {
        enqueue(EventTopology.POLL_ENDED, Map.of("poll_id", poll.getId(), "reason", reason));
    }

    private void enqueue(String routingKey, Map<String, Object> event) {
        outbox.save(new OutboxMessage(routingKey, json.writeValueAsString(event)));
    }
}
