package ua.votivo.poll.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.mockito.ArgumentCaptor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OutboxRelayTest {
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final OutboxRelayLockRepository locks = mock(OutboxRelayLockRepository.class);
    private final AmqpAdmin admin = mock(AmqpAdmin.class);
    private OutboxRelay relay;

    @BeforeEach void setUp() {
        var txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(locks.tryAcquire()).thenReturn(Optional.of(new OutboxRelayLock()));
        relay = new OutboxRelay(outbox, rabbit, new TransactionTemplate(txManager), locks, admin);
    }

    @Test void confirmedMessagesAreSentToTheExchangeWithTheEventIdAndDeleted() {
        var first = new OutboxMessage("poll.started", "{\"poll_id\":\"1\"}");
        var second = new OutboxMessage("poll.ended", "{\"poll_id\":\"1\"}");
        when(outbox.findTop100ByOrderByCreatedAtAscIdAsc()).thenReturn(List.of(first, second));
        brokerAnswers(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)));

        relay.relay();

        var message = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq(EventTopology.EXCHANGE), eq("poll.started"), message.capture(), any(CorrelationData.class));
        assertThat(message.getValue().getMessageProperties().getMessageId()).isEqualTo(first.getId().toString());
        assertThat(message.getValue().getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(new String(message.getValue().getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"poll_id\":\"1\"}");
        verify(rabbit).send(eq(EventTopology.EXCHANGE), eq("poll.ended"), any(Message.class), any(CorrelationData.class));
        verify(outbox).delete(first);
        verify(outbox).delete(second);
    }

    @Test void anUnroutableMessageStaysInTheOutboxAndStopsTheBatch() {
        var first = new OutboxMessage("poll.started", "{}");
        var second = new OutboxMessage("poll.ended", "{}");
        when(outbox.findTop100ByOrderByCreatedAtAscIdAsc()).thenReturn(List.of(first, second));
        brokerAnswers(correlation -> {
            correlation.setReturned(mock(ReturnedMessage.class));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        relay.relay();

        verify(outbox, never()).delete(any(OutboxMessage.class));
        verify(rabbit, times(1)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getLastError()).contains("poll.started");
        assertThat(second.getAttempts()).isZero();
    }

    @Test void aNackedMessageStaysInTheOutbox() {
        var message = new OutboxMessage("poll.started", "{}");
        when(outbox.findTop100ByOrderByCreatedAtAscIdAsc()).thenReturn(List.of(message));
        brokerAnswers(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "disk full")));

        relay.relay();

        verify(outbox, never()).delete(any(OutboxMessage.class));
        assertThat(message.getAttempts()).isEqualTo(1);
        assertThat(message.getLastError()).contains("disk full");
    }

    @Test void aBrokerThatIsDownKeepsTheMessageAndDoesNotThrow() {
        var message = new OutboxMessage("poll.started", "{}");
        when(outbox.findTop100ByOrderByCreatedAtAscIdAsc()).thenReturn(List.of(message));
        doThrow(new org.springframework.amqp.AmqpConnectException(new java.io.IOException("refused")))
                .when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        relay.relay();

        verify(outbox, never()).delete(any(OutboxMessage.class));
        assertThat(message.getAttempts()).isEqualTo(1);
    }

    @Test void anotherPublisherHoldingTheLockLeavesTheOutboxAlone() {
        when(locks.tryAcquire()).thenReturn(Optional.empty());
        relay.relay();
        verifyNoInteractions(outbox, rabbit, admin);
    }

    @Test void failedQueueDeclarationKeepsTheEventWithoutPublishingIt() {
        var message = new OutboxMessage("poll.started", "{}");
        when(outbox.findTop100ByOrderByCreatedAtAscIdAsc()).thenReturn(List.of(message));
        doThrow(new IllegalStateException("queue declaration failed")).when(admin).declareQueue(any());
        relay.relay();
        verifyNoInteractions(rabbit);
        verify(outbox, never()).delete(any(OutboxMessage.class));
        assertThat(message.getAttempts()).isEqualTo(1);
    }

    private void brokerAnswers(Consumer<CorrelationData> answer) {
        doAnswer(invocation -> {
            answer.accept(invocation.getArgument(3));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
}
