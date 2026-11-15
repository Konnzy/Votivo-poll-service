package ua.votivo.poll.model;

import jakarta.persistence.*;
import ua.votivo.poll.model.enums.QuestionType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "questions")
public class Question {
    @Id
    private UUID id;
    @Column(nullable = false, length = 2000)
    private String text;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private QuestionType type;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "poll_id", nullable = false)
    private Poll poll;
    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderColumn(name = "position")
    private List<AnswerOption> options = new ArrayList<>();

    protected Question() { }

    public Question(String text, QuestionType type, List<AnswerOption> options) {
        this.id = UUID.randomUUID();
        this.text = text;
        this.type = type;
        options.forEach(option -> { option.attachTo(this); this.options.add(option); });
    }

    void attachTo(Poll poll) { this.poll = poll; }
    public UUID getId() { return id; }
    public String getText() { return text; }
    public QuestionType getType() { return type; }
    public List<AnswerOption> getOptions() { return Collections.unmodifiableList(options); }
}
