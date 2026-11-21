package ua.votivo.poll.repository;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import ua.votivo.poll.model.*;
import ua.votivo.poll.model.enums.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@Tag("postgres")
@SpringBootTest
@ActiveProfiles("postgres-test")
class PostgresPersistenceTest {
    @Autowired PollRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate transaction;

    @BeforeEach void clean() { repository.deleteAll(); }

    @Test void migratesPersistsReplacesAndCascadesInPostgres() {
        var poll = repository.saveAndFlush(new Poll("owner", "Survey", "", AccessType.PUBLIC, true,
                List.of(question("First"), question("Second"))));
        transaction.executeWithoutResult(status -> {
            var loaded = repository.findById(poll.getId()).orElseThrow();
            assertThat(loaded.getQuestions()).extracting(Question::getText).containsExactly("First", "Second");
            assertThat(loaded.getQuestions().getFirst().getOptions()).extracting(AnswerOption::getText)
                    .containsExactly("A", "B");
            loaded.replaceContent("Updated", "", AccessType.LINK, false, List.of(question("Replacement")));
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM questions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_options", Integer.class)).isEqualTo(2);
        repository.deleteById(poll.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM questions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_options", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isPositive();
    }

    @Test void rejectsStaleConcurrentLifecycleUpdate() {
        var saved = repository.saveAndFlush(new Poll("owner", "Survey", "", AccessType.PUBLIC, true, List.of()));
        var first = repository.findById(saved.getId()).orElseThrow();
        var stale = repository.findById(saved.getId()).orElseThrow();
        first.block();
        repository.saveAndFlush(first);
        stale.publish();
        assertThatThrownBy(() -> repository.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test void failedTransactionRestoresContentAndChildren() {
        var poll = repository.saveAndFlush(new Poll("owner", "Original", "", AccessType.PUBLIC, true,
                List.of(question("First"), question("Second"))));
        var originalQuestionIds = jdbc.queryForList("SELECT id FROM questions ORDER BY id", java.util.UUID.class);
        var originalOptionIds = jdbc.queryForList("SELECT id FROM answer_options ORDER BY id", java.util.UUID.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            var loaded = repository.findById(poll.getId()).orElseThrow();
            loaded.replaceContent("Changed", "", AccessType.LINK, false, List.of(question("Replacement")));
            repository.flush();
            throw new IllegalStateException("Simulated failure after database writes");
        })).isInstanceOf(IllegalStateException.class);
        var restored = repository.findById(poll.getId()).orElseThrow();
        assertThat(restored.getTitle()).isEqualTo("Original");
        assertThat(restored.getAccessType()).isEqualTo(AccessType.PUBLIC);
        assertThat(restored.isAnonymous()).isTrue();
        assertThat(jdbc.queryForList("SELECT id FROM questions ORDER BY id", java.util.UUID.class))
                .containsExactlyElementsOf(originalQuestionIds);
        assertThat(jdbc.queryForList("SELECT id FROM answer_options ORDER BY id", java.util.UUID.class))
                .containsExactlyElementsOf(originalOptionIds);
    }

    private Question question(String text) {
        return new Question(text, QuestionType.SINGLE, List.of(new AnswerOption("A"), new AnswerOption("B")));
    }
}
