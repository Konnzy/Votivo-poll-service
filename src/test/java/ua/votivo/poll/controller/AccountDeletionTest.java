package ua.votivo.poll.controller;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.security.account-deletion-secret=test-deletion-secret")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountDeletionTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ua.votivo.poll.service.PollOwnerLifecycleService lifecycle;
    @Autowired ua.votivo.poll.repository.PollRepository repository;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    private RequestPostProcessor owner(UUID id) {
        return jwt().jwt(token -> token.subject(id.toString()))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
    }

    private String draft(UUID owner) throws Exception {
        String result = mvc.perform(post("/api/v1/polls").with(owner(owner))
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"title":"Survey","description":"","accessType":"PUBLIC","anonymous":false,"endsAt":"2099-12-31T12:00:00Z",
                     "questions":[{"text":"Pick","type":"SINGLE","options":[{"text":"A"},{"text":"B"}]}]}
                    """)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(result, "$.id");
    }

    @Test
    void draftsAllowDeletionButReservationPreventsPublishingEvenWithExistingTokens() throws Exception {
        UUID id = UUID.randomUUID();
        String poll = draft(id);
        String path = "/internal/v1/users/" + id + "/deletion";
        mvc.perform(put(path)).andExpect(status().isUnauthorized());
        mvc.perform(put(path).with(owner(id))).andExpect(status().isUnauthorized());
        mvc.perform(put(path).header("X-Account-Deletion-Secret", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(put(path).header("X-Account-Deletion-Secret", "test-deletion-secret"))
                .andExpect(status().isNoContent());
        mvc.perform(put(path).header("X-Account-Deletion-Secret", "test-deletion-secret"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/polls/" + poll + "/publish").with(owner(id)))
                .andExpect(status().isForbidden());
        mvc.perform(delete(path).header("X-Account-Deletion-Secret", "test-deletion-secret"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/polls/" + poll + "/publish").with(owner(id))).andExpect(status().isOk());
    }

    @Test
    void reservationWaitsForConcurrentPublicationAndRejectsThePublishedPoll() throws Exception {
        UUID id = UUID.randomUUID();
        UUID poll = UUID.fromString(draft(id));
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        var started = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<?> pending = transaction.execute(status -> {
                lifecycle.requireActiveOwner(id.toString());
                var future = executor.submit(() -> {
                    started.countDown();
                    lifecycle.reserveDeletion(id.toString());
                });
                try {
                    org.assertj.core.api.Assertions.assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> future.get(150, java.util.concurrent.TimeUnit.MILLISECONDS))
                            .isInstanceOf(java.util.concurrent.TimeoutException.class);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                repository.findById(poll).orElseThrow().publish();
                return future;
            });
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> pending.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(ua.votivo.poll.exception.InvalidPollStateException.class);
        }
    }

    @Test
    void onlyPublishedUnfinishedPollsPreventDeletion() throws Exception {
        UUID id = UUID.randomUUID();
        String poll = draft(id);
        mvc.perform(post("/api/v1/polls/" + poll + "/publish").with(owner(id))).andExpect(status().isOk());
        String path = "/internal/v1/users/" + id + "/deletion";
        mvc.perform(put(path).header("X-Account-Deletion-Secret", "test-deletion-secret"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/polls/" + poll + "/close").with(owner(id))).andExpect(status().isOk());
        mvc.perform(put(path).header("X-Account-Deletion-Secret", "test-deletion-secret"))
                .andExpect(status().isNoContent());
    }
}
