package ua.votivo.poll.event;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.ArrayList;
import java.util.List;

/** Shared declarations match the existing Vote and Result consumer contracts. */
@Configuration
@EnableScheduling
public class EventTopology {
    public static final String EXCHANGE = "poll.events.exchange";
    public static final String POLL_STARTED = "poll.started";
    public static final String POLL_ENDED = "poll.ended";
    public static final String VOTE_QUEUE = "vote-service.poll-events";
    public static final String RESULT_QUEUE = "result-service.poll-events";
    public static final String DEAD_LETTER_EXCHANGE = "votivo.dlx";

    @Bean
    Declarables pollEventsTopology() {
        return declarations();
    }

    /** Declare both subscriptions before sending, including after a queue was removed or the broker restarted. */
    public static void declare(AmqpAdmin admin) {
        var topology = declarations();
        topology.getDeclarablesByType(Exchange.class).forEach(admin::declareExchange);
        topology.getDeclarablesByType(Queue.class).forEach(admin::declareQueue);
        topology.getDeclarablesByType(Binding.class).forEach(admin::declareBinding);
    }

    private static Declarables declarations() {
        var exchange = new TopicExchange(EXCHANGE, true, false);
        var deadLetter = new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
        List<Declarable> items = new ArrayList<>(List.of(exchange, deadLetter));
        for (String name : List.of(VOTE_QUEUE, RESULT_QUEUE)) {
            var queue = QueueBuilder.durable(name).deadLetterExchange(DEAD_LETTER_EXCHANGE)
                    .deadLetterRoutingKey(name).build();
            var dlq = QueueBuilder.durable(name + ".dlq").build();
            items.add(queue);
            items.add(dlq);
            items.add(BindingBuilder.bind(queue).to(exchange).with(POLL_STARTED));
            items.add(BindingBuilder.bind(queue).to(exchange).with(POLL_ENDED));
            items.add(BindingBuilder.bind(dlq).to(deadLetter).with(name));
        }
        return new Declarables(items);
    }
}
