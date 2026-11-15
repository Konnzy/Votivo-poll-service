package ua.votivo.poll.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "answer_options")
public class AnswerOption {
    @Id
    private UUID id;
    @Column(nullable = false, length = 1000)
    private String text;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    protected AnswerOption() { }

    public AnswerOption(String text) {
        this.id = UUID.randomUUID();
        this.text = text;
    }

    void attachTo(Question question) { this.question = question; }
    public UUID getId() { return id; }
    public String getText() { return text; }
}
