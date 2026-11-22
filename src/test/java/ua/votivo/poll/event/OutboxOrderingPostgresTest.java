package ua.votivo.poll.event;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Tag("postgres")
@SpringBootTest
@ActiveProfiles("postgres-test")
class OutboxOrderingPostgresTest {
    @Autowired OutboxRepository outbox;
    @Autowired OutboxRelayLockRepository locks;
    @Autowired TransactionTemplate tx;

    @BeforeEach void clean() { outbox.deleteAll(); }
    @AfterEach void removeEvents() { outbox.deleteAll(); }

    @Test void endedCannotPassStartedWhileAnotherPublisherWaitsForConfirmation() throws Exception {
        var rabbit = mock(RabbitTemplate.class);
        var admin = mock(AmqpAdmin.class);
        var startedSending = new CountDownLatch(1);
        var confirmStarted = new CountDownLatch(1);
        var first = new OutboxRelay(outbox, rabbit, tx, locks, admin);
        var second = new OutboxRelay(outbox, rabbit, tx, locks, admin);
        doAnswer(invocation -> {
            String key = invocation.getArgument(1);
            if (EventTopology.POLL_STARTED.equals(key)) {
                startedSending.countDown();
                if (!confirmStarted.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("confirm timed out");
            }
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        outbox.saveAndFlush(new OutboxMessage(EventTopology.POLL_STARTED, "{\"poll_id\":\"same-poll\"}"));
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> sending = executor.submit(first::relay);
            try {
                assertThat(startedSending.await(10, TimeUnit.SECONDS)).isTrue();
                outbox.saveAndFlush(new OutboxMessage(EventTopology.POLL_ENDED, "{\"poll_id\":\"same-poll\"}"));
                second.relay();
                verify(rabbit, never()).send(anyString(), eq(EventTopology.POLL_ENDED), any(Message.class), any(CorrelationData.class));
            } finally { confirmStarted.countDown(); }
            sending.get(10, TimeUnit.SECONDS);
        }
        second.relay();
        var order = inOrder(rabbit);
        order.verify(rabbit).send(eq(EventTopology.EXCHANGE), eq(EventTopology.POLL_STARTED), any(Message.class), any(CorrelationData.class));
        order.verify(rabbit).send(eq(EventTopology.EXCHANGE), eq(EventTopology.POLL_ENDED), any(Message.class), any(CorrelationData.class));
        assertThat(outbox.count()).isZero();
        Boolean released = tx.execute(status -> locks.tryAcquire().isPresent());
        assertThat(released).as("lock is released after publishing").isTrue();
    }
}
