package ua.votivo.poll.model;

import jakarta.persistence.*;
import ua.votivo.poll.model.enums.AccessType;
import ua.votivo.poll.model.enums.PollStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "polls")
public class Poll {
    @Id
    private UUID id;
    @Version
    private long version;
    @Column(nullable = false, length = 255)
    private String ownerId;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false, length = 5000)
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PollStatus status;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccessType accessType;
    @Column(nullable = false)
    private boolean anonymous;
    @Column(nullable = false)
    private boolean blocked;
    @Column(nullable = false, unique = true)
    private UUID shareToken;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    private Instant publishedAt;
    private Instant closedAt;
    private Instant archivedAt;
    @OneToMany(mappedBy = "poll", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderColumn(name = "position")
    private List<Question> questions = new ArrayList<>();

    protected Poll() { }

    public Poll(String ownerId, String title, String description, AccessType accessType,
                boolean anonymous, List<Question> questions) {
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        this.status = PollStatus.DRAFT;
        this.shareToken = UUID.randomUUID();
        this.createdAt = Instant.now();
        replaceContent(title, description, accessType, anonymous, questions);
    }

    public void replaceContent(String title, String description, AccessType accessType,
                               boolean anonymous, List<Question> questions) {
        this.title = title;
        this.description = description;
        this.accessType = accessType;
        this.anonymous = anonymous;
        this.questions.clear();
        questions.forEach(question -> { question.attachTo(this); this.questions.add(question); });
        this.updatedAt = Instant.now();
    }

    public void publish() {
        this.status = PollStatus.PUBLISHED;
        this.publishedAt = Instant.now();
        this.updatedAt = publishedAt;
    }

    public void close() {
        this.status = PollStatus.CLOSED;
        this.closedAt = Instant.now();
        this.updatedAt = closedAt;
    }

    public void archive() {
        this.status = PollStatus.ARCHIVED;
        this.archivedAt = Instant.now();
        this.updatedAt = archivedAt;
    }

    public void block() { this.blocked = true; this.updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public PollStatus getStatus() { return status; }
    public AccessType getAccessType() { return accessType; }
    public boolean isAnonymous() { return anonymous; }
    public boolean isBlocked() { return blocked; }
    public UUID getShareToken() { return shareToken; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getClosedAt() { return closedAt; }
    public Instant getArchivedAt() { return archivedAt; }
    public List<Question> getQuestions() { return Collections.unmodifiableList(questions); }
}
