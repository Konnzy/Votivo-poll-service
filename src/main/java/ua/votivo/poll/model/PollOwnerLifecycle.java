package ua.votivo.poll.model;

import jakarta.persistence.*;

@Entity
@Table(name = "poll_owner_lifecycle")
public class PollOwnerLifecycle {
    @Id
    private String ownerId;
    private boolean deletionReserved;

    protected PollOwnerLifecycle() {}
    public boolean isDeletionReserved() { return deletionReserved; }
    public void reserveDeletion() { deletionReserved = true; }
    public void releaseDeletion() { deletionReserved = false; }
}
