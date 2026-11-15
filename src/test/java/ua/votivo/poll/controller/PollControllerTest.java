package ua.votivo.poll.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ua.votivo.poll.repository.PollRepository;
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
    @MockitoBean JwtDecoder decoder;

    @BeforeEach void clean() { repository.deleteAll(); }

    @Test void creationRequiresAuthentication() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/mine")).andExpect(status().isUnauthorized());
    }

    @Test void createsDraftWithIdentityFromJwtAndLocation() throws Exception {
        mvc.perform(post(BASE).with(owner()).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.ownerId").value("owner"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.questions[0].options[1].text").value("Go"));
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
                + "\",\"anonymous\":true,\"questions\":[" + questions + "]}";
    }
    private static RequestPostProcessor owner() { return jwt().jwt(jwt -> jwt.subject("owner")); }
    private static RequestPostProcessor other() { return jwt().jwt(jwt -> jwt.subject("other")); }
    private static RequestPostProcessor admin() {
        return jwt().jwt(jwt -> jwt.subject("admin")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
