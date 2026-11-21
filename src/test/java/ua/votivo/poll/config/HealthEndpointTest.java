package ua.votivo.poll.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.json.JsonCompareMode;
import javax.sql.DataSource;
import java.sql.SQLException;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthEndpointTest {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @MockitoBean JwtDecoder decoder;
    @MockitoSpyBean DataSource dataSource;

    @AfterEach void acceptTraffic() {
        AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test void probesArePublicAndDoNotExposeComponentsOrDetails() throws Exception {
        for (String probe : new String[]{"liveness", "readiness"}) {
            mvc.perform(get("/actuator/health/" + probe)).andExpect(status().isOk())
                    .andExpect(content().json("{\"status\":\"UP\"}", JsonCompareMode.STRICT));
        }
        mvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health/readiness/db")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env").with(jwt())).andExpect(status().isNotFound());
    }

    @Test void refusingTrafficFailsReadinessButKeepsLiveness() throws Exception {
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"status\":\"OUT_OF_SERVICE\"}", JsonCompareMode.STRICT));
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    @Test void unavailableDatabaseFailsReadinessButKeepsLiveness() throws Exception {
        doThrow(new SQLException("Private database connection failure")).when(dataSource).getConnection();
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"status\":\"DOWN\"}", JsonCompareMode.STRICT));
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
}
