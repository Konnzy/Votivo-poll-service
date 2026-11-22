package ua.votivo.poll.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Publishes the outbox to RabbitMQ. Delivery is at least once: a crash between the broker confirm and the delete
 * publishes the message again, which is why every consumer drops an event id it has already handled.
 *
 * <p>Both durable consumer queues are declared before each non-empty batch. A singleton database lock keeps
 * another instance from publishing later events while this instance awaits confirms. The batch stops at the
 * first failure; the lock is held until deletions and retry metadata commit.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final OutboxRepository outbox;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate tx;
    private final OutboxRelayLockRepository locks;
    private final AmqpAdmin admin;

    public OutboxRelay(OutboxRepository outbox, RabbitTemplate rabbit, TransactionTemplate tx,
                       OutboxRelayLockRepository locks, AmqpAdmin admin) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.tx = tx;
        this.locks = locks;
        this.admin = admin;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay.interval:1s}")
    public void relay() {
        try {
            tx.executeWithoutResult(status -> publishPending());
        } catch (RuntimeException e) {
            log.error("Outbox relay failed", e);
        }
    }

    private void publishPending() {
        if (locks.tryAcquire().isEmpty()) return;
        boolean topologyDeclared = false;
        for (OutboxMessage message : outbox.findTop100ByOrderByCreatedAtAscIdAsc()) {
            try {
                if (!topologyDeclared) {
                    EventTopology.declare(admin);
                    topologyDeclared = true;
                }
                publish(message);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                message.failed(e.getMessage());
                log.warn("Publishing event {} ({}) failed, attempt {}: {}",
                        message.getId(), message.getRoutingKey(), message.getAttempts(), e.getMessage());
                return;
            }
            outbox.delete(message);
        }
    }

    private void publish(OutboxMessage message) throws Exception {
        MessageProperties properties = MessagePropertiesBuilder.newInstance()
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(message.getId().toString())
                .setTimestamp(Date.from(message.getCreatedAt()))
                .build();
        var correlation = new CorrelationData(message.getId().toString());
        rabbit.send(EventTopology.EXCHANGE, message.getRoutingKey(),
                new Message(message.getPayload().getBytes(StandardCharsets.UTF_8), properties), correlation);

        var confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (correlation.getReturned() != null) {
            throw new IllegalStateException("no queue is bound for routing key " + message.getRoutingKey());
        }
        if (!confirm.ack()) {
            throw new IllegalStateException("broker nacked the message: " + confirm.reason());
        }
    }
}
