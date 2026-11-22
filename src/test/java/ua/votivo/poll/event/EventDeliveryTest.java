package ua.votivo.poll.event;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** Requires a disposable broker: never run this suite against a shared RabbitMQ. */
@Tag("messaging")
@SpringBootTest
@ActiveProfiles("postgres-test")
class EventDeliveryTest {
    @Autowired OutboxRepository outbox;
    @Autowired OutboxRelayLockRepository locks;
    @Autowired RabbitTemplate rabbit;
    @Autowired TransactionTemplate tx;
    private RabbitAdmin admin;

    @BeforeEach void emptyQueues() {
        outbox.deleteAll();
        admin = new RabbitAdmin(rabbit.getConnectionFactory());
        EventTopology.declare(admin);
        admin.purgeQueue(EventTopology.VOTE_QUEUE);
        admin.purgeQueue(EventTopology.RESULT_QUEUE);
    }
    @AfterEach void clean() {
        outbox.deleteAll();
        admin.purgeQueue(EventTopology.VOTE_QUEUE);
        admin.purgeQueue(EventTopology.RESULT_QUEUE);
    }

    @Test void lateConsumersReceiveTheSameStartedAndEndedEventsInOrder() {
        var started = outbox.saveAndFlush(new OutboxMessage(EventTopology.POLL_STARTED, "{\"poll_id\":\"same-poll\"}"));
        var ended = outbox.saveAndFlush(new OutboxMessage(EventTopology.POLL_ENDED, "{\"poll_id\":\"same-poll\",\"reason\":\"closed\"}"));
        // Neither consumer is connected. Remove one subscription to reproduce the original startup race.
        admin.deleteQueue(EventTopology.RESULT_QUEUE);
        new OutboxRelay(outbox, rabbit, tx, locks, admin).relay();
        assertThat(outbox.count()).isZero();
        for (String queue : new String[]{EventTopology.VOTE_QUEUE, EventTopology.RESULT_QUEUE}) {
            Message first = rabbit.receive(queue, 2000);
            Message second = rabbit.receive(queue, 2000);
            assertThat(first).as(queue + " receives started").isNotNull();
            assertThat(second).as(queue + " receives ended").isNotNull();
            assertThat(first.getMessageProperties().getMessageId()).isEqualTo(started.getId().toString());
            assertThat(second.getMessageProperties().getMessageId()).isEqualTo(ended.getId().toString());
            assertThat(first.getMessageProperties().getReceivedRoutingKey()).isEqualTo(EventTopology.POLL_STARTED);
            assertThat(second.getMessageProperties().getReceivedRoutingKey()).isEqualTo(EventTopology.POLL_ENDED);
        }
    }

    @Test void consumerDeclarationsRemainCompatibleAndRejectedEventsReachDeadLetterQueues() {
        // A second declaration with the consumer's exact queue arguments must not close the channel.
        for (String name : new String[]{EventTopology.VOTE_QUEUE, EventTopology.RESULT_QUEUE}) {
            admin.declareQueue(QueueBuilder.durable(name).deadLetterExchange("votivo.dlx")
                    .deadLetterRoutingKey(name).build());
            admin.purgeQueue(name + ".dlq");
        }
        var event = outbox.saveAndFlush(new OutboxMessage(EventTopology.POLL_STARTED, "{}"));
        new OutboxRelay(outbox, rabbit, tx, locks, admin).relay();
        for (String queue : new String[]{EventTopology.VOTE_QUEUE, EventTopology.RESULT_QUEUE}) {
            rabbit.execute(channel -> {
                var delivery = channel.basicGet(queue, false);
                assertThat(delivery).isNotNull();
                channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
                return null;
            });
            Message rejected = rabbit.receive(queue + ".dlq", 2000);
            assertThat(rejected).isNotNull();
            assertThat(rejected.getMessageProperties().getMessageId()).isEqualTo(event.getId().toString());
        }
    }
}
