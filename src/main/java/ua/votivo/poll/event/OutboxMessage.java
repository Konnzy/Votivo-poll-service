package ua.votivo.poll.event;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** An event waiting to be published to RabbitMQ. Its id is the AMQP message_id. */
@Entity
@Table(name = "outbox_messages")
public class OutboxMessage {
    @Id
    private UUID id;
    @Column(nullable = false)
    private String routingKey;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private int attempts;
    @Column(length = 1000)
    private String lastError;

    protected OutboxMessage() { }

    public OutboxMessage(String routingKey, String payload) {
        this.id = UUID.randomUUID();
        this.routingKey = routingKey;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public void failed(String error) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
    }

    public UUID getId() { return id; }
    public String getRoutingKey() { return routingKey; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
}
