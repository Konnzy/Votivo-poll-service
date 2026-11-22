package ua.votivo.poll.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With the gateway secret configured, Poll Service serves only calls that come through the gateway. */
@SpringBootTest(properties = "app.security.internal-secret=gateway-secret")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GatewayAuthTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;

    @Test void refusesCallsWithoutOrWithAWrongGatewaySecret() throws Exception {
        mvc.perform(get("/api/v1/polls").with(jwt())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/polls").header("X-Internal-Auth", "guess")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/polls/mine").header("X-Internal-Auth", "gateway-secret-but-longer"))
                .andExpect(status().isUnauthorized());
    }

    @Test void servesTheGatewayAndKeepsHealthProbesOpen() throws Exception {
        mvc.perform(get("/api/v1/polls").header("X-Internal-Auth", "gateway-secret")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    @Test void needsTheUserOrAdminRole() throws Exception {
        mvc.perform(get("/api/v1/polls/mine").header("X-Internal-Auth", "gateway-secret").with(jwt()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/polls/mine").header("X-Internal-Auth", "gateway-secret")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/polls").header("X-Internal-Auth", "gateway-secret")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/polls/mine").header("X-Internal-Auth", "gateway-secret"))
                .andExpect(status().isUnauthorized());
    }
}
