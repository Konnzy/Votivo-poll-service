package ua.votivo.poll.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ua.votivo.poll.event.OutboxMessage;
import ua.votivo.poll.event.OutboxRepository;
import ua.votivo.poll.repository.PollRepository;
import java.util.List;
import java.util.Collections;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PollControllerTest {
    private static final String BASE = "/api/v1/polls";
    private static final String QUESTION = """
            {"text":"Choose a language","type":"SINGLE","options":[{"text":"Java"},{"text":"Go"}]}
            """;
    private static final String VALID = body("PUBLIC", QUESTION);
    @Autowired MockMvc mvc;
    @Autowired PollRepository repository;
    @Autowired OutboxRepository outbox;
    @Autowired ua.votivo.poll.service.PollExpirationService expiration;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach void clean() { repository.deleteAll(); outbox.deleteAll(); }

    @Test void creationRequiresAuthentication() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/mine")).andExpect(status().isUnauthorized());
    }

    @Test void draftsMayOmitDeadlineButPublicationRequiresItAndRejectsPastDates() throws Exception {
        String noDeadline = VALID.replace("\"endsAt\":\"2099-12-31T12:00:00Z\",", "");
        String id = create(noDeadline);
        mvc.perform(post(BASE + "/" + id + "/publish").with(owner())).andExpect(status().isConflict());
        mvc.perform(post(BASE).with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content(VALID.replace("2099-12-31T12:00:00Z", "2000-01-01T00:00:00Z")))
                .andExpect(status().isBadRequest());
        mvc.perform(put(BASE + "/" + id).with(owner()).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.endsAt").value("2099-12-31T12:00:00Z"));
        transition(id, "publish", "PUBLISHED");
        assertThat(outbox.findAll().getFirst().getPayload()).contains("ends_at", "2099-12-31");
    }

    @Test void expirationClosesOnlyDuePollsAndEnqueuesExactlyOneEndEvent() throws Exception {
        String id = create(VALID);
        transition(id, "publish", "PUBLISHED");
        var due = java.time.Instant.parse("2099-12-31T12:00:00Z");
        expiration.closeIfDue(UUID.fromString(id), due.minusSeconds(1));
        mvc.perform(get(BASE + "/" + id)).andExpect(jsonPath("$.status").value("PUBLISHED"));
        assertThat(repository.findDueIds(ua.votivo.poll.model.enums.PollStatus.PUBLISHED, due,
                org.springframework.data.domain.PageRequest.of(0, 100))).contains(UUID.fromString(id));
        expiration.closeIfDue(UUID.fromString(id), due);
        expiration.closeIfDue(UUID.fromString(id), due);
        mvc.perform(get(BASE + "/" + id)).andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
        assertThat(outbox.findAll().stream().filter(e -> e.getRoutingKey().equals("poll.ended"))).hasSize(1);
        String draft = create(VALID);
        expiration.closeIfDue(UUID.fromString(draft), due);
        mvc.perform(get(BASE + "/" + draft).with(owner())).andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test void createsDraftWithIdentityFromJwtAndLocation() throws Exception {
        mvc.perform(post(BASE).with(owner()).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.ownerId").value("owner"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.questions[0].options[1].text").value("Go"));
    }

    @Test void administratorCanReviewAndBlockButCannotUseOwnerActionsEvenWithUserRole() throws Exception {
        String id = create(VALID);
        var adminOwner = jwt().jwt(j -> j.subject("owner")).authorities(
                new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"));
        mvc.perform(get(BASE + "/" + id).with(adminOwner)).andExpect(status().isOk());
        mvc.perform(get(BASE + "/mine").with(adminOwner)).andExpect(status().isForbidden());
        mvc.perform(post(BASE).with(adminOwner).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden());
        mvc.perform(put(BASE + "/" + id).with(adminOwner).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden());
        mvc.perform(delete(BASE + "/" + id).with(adminOwner)).andExpect(status().isForbidden());
        for (String action : new String[]{"publish", "close", "archive"}) {
            mvc.perform(post(BASE + "/" + id + "/" + action).with(adminOwner)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/admin/polls").with(adminOwner)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/polls/" + id + "/block").with(adminOwner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.blocked").value(true));
    }

    @Test void completeLifecyclePreservesArchivedContent() throws Exception {
        String id = create(VALID);
        mvc.perform(put(BASE + "/" + id).with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content(VALID.replace("Survey", "Updated"))).andExpect(status().isOk());
        transition(id, "publish", "PUBLISHED");
        mvc.perform(get(BASE)).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Updated"));
        mvc.perform(put(BASE + "/" + id).with(owner()).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isConflict());
        transition(id, "close", "CLOSED");
        transition(id, "archive", "ARCHIVED");
        mvc.perform(get(BASE)).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(BASE + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].options.length()").value(2))
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());
    }

    @Test void incompleteDraftCanBeSavedButCannotBePublished() throws Exception {
        String id = create(body("PUBLIC", ""));
        mvc.perform(post(BASE + "/" + id + "/publish").with(owner())).andExpect(status().isConflict());
        String singleOption = create(body("PUBLIC", QUESTION.replace(",{" + "\"text\":\"Go\"}", "")));
        mvc.perform(post(BASE + "/" + singleOption + "/publish").with(owner())).andExpect(status().isConflict());
    }

    @Test void rejectsInvalidStateTransitions() throws Exception {
        String id = create(VALID);
        mvc.perform(post(BASE + "/" + id + "/close").with(owner())).andExpect(status().isConflict());
        mvc.perform(post(BASE + "/" + id + "/archive").with(owner())).andExpect(status().isConflict());
        transition(id, "publish", "PUBLISHED");
        mvc.perform(post(BASE + "/" + id + "/publish").with(owner())).andExpect(status().isConflict());
    }

    @Test void anotherUserCannotReadDraftOrMutatePoll() throws Exception {
        String id = create(VALID);
        mvc.perform(get(BASE + "/" + id).with(other())).andExpect(status().isNotFound());
        mvc.perform(put(BASE + "/" + id).with(other()).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden());
        for (String action : new String[]{"publish", "close", "archive"}) {
            mvc.perform(post(BASE + "/" + id + "/" + action).with(other())).andExpect(status().isForbidden());
        }
        mvc.perform(delete(BASE + "/" + id).with(other())).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/mine").with(other())).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test void linkPollIsUnlistedAndRequiresShareToken() throws Exception {
        String response = mvc.perform(post(BASE).with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content(body("LINK", QUESTION))).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(response, "$.id");
        String token = JsonPath.read(response, "$.shareToken");
        mvc.perform(get(BASE + "/by-link/" + token)).andExpect(status().isNotFound());
        transition(id, "publish", "PUBLISHED");
        mvc.perform(get(BASE)).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(BASE + "/" + id)).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/by-link/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/by-link/" + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.shareToken").doesNotExist());
        transition(id, "close", "CLOSED");
        transition(id, "archive", "ARCHIVED");
        mvc.perform(get(BASE + "/by-link/" + token)).andExpect(status().isOk());
    }

    @Test void draftDeletionCascadesAndPublishedDeletionIsRefused() throws Exception {
        String id = create(VALID);
        mvc.perform(delete(BASE + "/" + id).with(owner())).andExpect(status().isNoContent());
        assertThat(repository.existsById(UUID.fromString(id))).isFalse();
        String published = create(VALID);
        transition(published, "publish", "PUBLISHED");
        mvc.perform(delete(BASE + "/" + published).with(owner())).andExpect(status().isConflict());
    }

    @Test void adminCanInspectAndBlockButCannotEditAnotherOwnersContent() throws Exception {
        String id = create(VALID);
        mvc.perform(get("/api/v1/admin/polls").with(other())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/polls/" + id + "/block").with(other())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/polls").with(admin())).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get(BASE + "/" + id).with(admin())).andExpect(status().isOk());
        mvc.perform(put(BASE + "/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/polls/" + id + "/block").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.blocked").value(true));
        mvc.perform(post(BASE + "/" + id + "/publish").with(owner())).andExpect(status().isConflict());
    }

    @Test void blockedPublishedPollIsHiddenButOwnerCanCloseIt() throws Exception {
        String id = create(VALID);
        transition(id, "publish", "PUBLISHED");
        mvc.perform(post("/api/v1/admin/polls/" + id + "/block").with(admin())).andExpect(status().isOk());
        mvc.perform(get(BASE + "/" + id)).andExpect(status().isNotFound());
        mvc.perform(get(BASE)).andExpect(jsonPath("$.totalElements").value(0));
        transition(id, "close", "CLOSED");
    }

    @Test void rejectsMoreThanFiftyQuestionsAndTwentyOptions() throws Exception {
        invalid(body("PUBLIC", String.join(",", Collections.nCopies(51, QUESTION))));
        invalid(body("PUBLIC", "{\"text\":\"Q\",\"type\":\"MULTIPLE\",\"options\":["
                + String.join(",", Collections.nCopies(21, "{\"text\":\"A\"}")) + "]}"));
        mvc.perform(post(BASE).with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content(body("PUBLIC", String.join(",", Collections.nCopies(50, QUESTION)))))
                .andExpect(status().isCreated());
    }

    @Test void validatesNestedFieldsNullsAndMalformedRequests() throws Exception {
        invalid(VALID.replace("Survey", " "));
        invalid(VALID.replace("Java", " "));
        invalid(VALID.replace("SINGLE", "UNKNOWN"));
        invalid(VALID.replace("\"anonymous\":true", "\"anonymous\":null"));
        invalid(body("PUBLIC", "null"));
        invalid("{");
    }

    @Test void validatesPaginationAndIds() throws Exception {
        mvc.perform(get(BASE).param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUBLISHED", "CLOSED", "ARCHIVED"})
    void contentIsImmutableAfterPublicationAndRejectedChangesLeaveItUntouched(String targetState) throws Exception {
        String id = create(VALID);
        transition(id, "publish", "PUBLISHED");
        if (!targetState.equals("PUBLISHED")) transition(id, "close", "CLOSED");
        if (targetState.equals("ARCHIVED")) transition(id, "archive", "ARCHIVED");
        String before = mvc.perform(get(BASE + "/" + id).with(owner()))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(put(BASE + "/" + id).with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content(VALID.replace("Survey", "Changed").replace("PUBLIC", "LINK")))
                .andExpect(status().isConflict());
        mvc.perform(delete(BASE + "/" + id).with(owner())).andExpect(status().isConflict());
        mvc.perform(get(BASE + "/" + id).with(owner())).andExpect(content().json(before, JsonCompareMode.STRICT));
    }

    @Test void blockedLinkPollIsHiddenAndItsDraftCannotBeEditedOrPublished() throws Exception {
        String id = create(body("LINK", QUESTION));
        String token = mvc.perform(get(BASE + "/" + id).with(owner()))
                .andReturn().getResponse().getContentAsString();
        String shareToken = JsonPath.read(token, "$.shareToken");
        mvc.perform(post("/api/v1/admin/polls/" + id + "/block").with(admin())).andExpect(status().isOk());
        mvc.perform(put(BASE + "/" + id).with(owner()).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isConflict());
        mvc.perform(post(BASE + "/" + id + "/publish").with(owner())).andExpect(status().isConflict());
        mvc.perform(get(BASE + "/by-link/" + shareToken)).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/" + id).with(owner())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.blocked").value(true));
    }

    @Test void publicReadsHideShareTokenAndEveryMutationRequiresAuthentication() throws Exception {
        String id = create(VALID);
        transition(id, "publish", "PUBLISHED");
        mvc.perform(get(BASE + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.shareToken").doesNotExist());
        mvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized());
        for (String action : new String[]{"publish", "close", "archive"}) {
            mvc.perform(post(BASE + "/" + id + "/" + action)).andExpect(status().isUnauthorized());
        }
        mvc.perform(delete(BASE + "/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/polls/" + id + "/block")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/polls")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/" + id).with(owner())).andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.blocked").value(false));
    }

    @Test void publishingQueuesPollStartedWithTheWholeSnapshot() throws Exception {
        String multiple = "{\"text\":\"Pick any\",\"type\":\"MULTIPLE\",\"options\":[{\"text\":\"A\"},{\"text\":\"B\"}]}";
        String id = create(body("PUBLIC", QUESTION + "," + multiple).replace("\"anonymous\":true", "\"anonymous\":false,\"allowVoteChange\":true"));
        assertThat(outbox.findAll()).as("a draft publishes nothing").isEmpty();

        transition(id, "publish", "PUBLISHED");

        List<OutboxMessage> messages = outbox.findAll();
        assertThat(messages).hasSize(1);
        OutboxMessage started = messages.getFirst();
        assertThat(started.getRoutingKey()).isEqualTo("poll.started");
        String payload = started.getPayload();
        assertThat((String) JsonPath.read(payload, "$.poll_id")).isEqualTo(id);
        assertThat((String) JsonPath.read(payload, "$.title")).isEqualTo("Survey");
        assertThat((Boolean) JsonPath.read(payload, "$.anonymous")).isFalse();
        assertThat((Boolean) JsonPath.read(payload, "$.allow_vote_change")).isTrue();
        assertThat((List<?>) JsonPath.read(payload, "$.questions")).hasSize(2);
        assertThat((Boolean) JsonPath.read(payload, "$.questions[0].multiple")).isFalse();
        assertThat((Boolean) JsonPath.read(payload, "$.questions[1].multiple")).isTrue();
        assertThat((List<?>) JsonPath.read(payload, "$.questions[0].options")).hasSize(2);
        String optionId = JsonPath.read(payload, "$.questions[0].options[1].id");
        assertThat(UUID.fromString(optionId)).isNotNull();
        assertThat((String) JsonPath.read(payload, "$.questions[0].options[1].text")).isEqualTo("Go");
    }

    @Test void allowVoteChangeDefaultsToFalseAndIsReturned() throws Exception {
        String id = create(VALID);
        mvc.perform(get(BASE + "/" + id).with(owner())).andExpect(jsonPath("$.allowVoteChange").value(false));
        mvc.perform(put(BASE + "/" + id).with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content(VALID.replace("\"anonymous\":true", "\"anonymous\":false,\"allowVoteChange\":true")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowVoteChange").value(true));
    }

    @Test void closingAndBlockingAPublishedPollQueuePollEnded() throws Exception {
        String closed = create(VALID);
        transition(closed, "publish", "PUBLISHED");
        transition(closed, "close", "CLOSED");
        String blocked = create(VALID);
        transition(blocked, "publish", "PUBLISHED");
        mvc.perform(post("/api/v1/admin/polls/" + blocked + "/block").with(admin())).andExpect(status().isOk());

        List<OutboxMessage> ended = outbox.findAll().stream()
                .filter(m -> m.getRoutingKey().equals("poll.ended")).toList();
        assertThat(ended).hasSize(2);
        assertThat(ended).anySatisfy(m -> {
            assertThat((String) JsonPath.read(m.getPayload(), "$.poll_id")).isEqualTo(closed);
            assertThat((String) JsonPath.read(m.getPayload(), "$.reason")).isEqualTo("closed");
        });
        assertThat(ended).anySatisfy(m -> {
            assertThat((String) JsonPath.read(m.getPayload(), "$.poll_id")).isEqualTo(blocked);
            assertThat((String) JsonPath.read(m.getPayload(), "$.reason")).isEqualTo("blocked");
        });
    }

    @Test void blockingADraftOrAnAlreadyClosedPollQueuesNothing() throws Exception {
        String draft = create(VALID);
        mvc.perform(post("/api/v1/admin/polls/" + draft + "/block").with(admin())).andExpect(status().isOk());
        String closed = create(VALID);
        transition(closed, "publish", "PUBLISHED");
        transition(closed, "close", "CLOSED");
        long before = outbox.count();

        mvc.perform(post("/api/v1/admin/polls/" + closed + "/block").with(admin())).andExpect(status().isOk());

        assertThat(outbox.count()).isEqualTo(before);
        assertThat(outbox.findAll()).extracting(OutboxMessage::getRoutingKey)
                .containsExactlyInAnyOrder("poll.started", "poll.ended");
    }

    @Test void aRejectedTransitionQueuesNothing() throws Exception {
        String id = create(VALID);
        mvc.perform(post(BASE + "/" + id + "/close").with(owner())).andExpect(status().isConflict());
        mvc.perform(post(BASE + "/" + id + "/publish").with(other())).andExpect(status().isForbidden());
        assertThat(outbox.findAll()).isEmpty();
    }

    private void invalid(String body) throws Exception {
        mvc.perform(post(BASE).with(owner()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
    private String create(String body) throws Exception {
        return JsonPath.read(mvc.perform(post(BASE).with(owner()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }
    private void transition(String id, String action, String state) throws Exception {
        mvc.perform(post(BASE + "/" + id + "/" + action).with(owner())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(state));
    }
    private static String body(String access, String questions) {
        return "{\"title\":\"Survey\",\"description\":\"Description\",\"accessType\":\"" + access
                + "\",\"anonymous\":true,\"endsAt\":\"2099-12-31T12:00:00Z\",\"questions\":[" + questions + "]}";
    }
    private static RequestPostProcessor owner() {
        return jwt().jwt(jwt -> jwt.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
    private static RequestPostProcessor other() {
        return jwt().jwt(jwt -> jwt.subject("other")).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
    private static RequestPostProcessor admin() {
        return jwt().jwt(jwt -> jwt.subject("admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"));
    }
}
