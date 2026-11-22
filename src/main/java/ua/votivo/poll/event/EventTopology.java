package ua.votivo.poll.event;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The exchange Poll Service publishes to. Consumers declare and bind their own queues. */
@Configuration
@EnableScheduling
public class EventTopology {
    public static final String EXCHANGE = "poll.events.exchange";
    public static final String POLL_STARTED = "poll.started";
    public static final String POLL_ENDED = "poll.ended";

    @Bean
    TopicExchange pollEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }
}
