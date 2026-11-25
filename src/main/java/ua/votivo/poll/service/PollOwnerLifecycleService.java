package ua.votivo.poll.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;
import ua.votivo.poll.model.PollOwnerLifecycle;
import ua.votivo.poll.model.enums.PollStatus;
import ua.votivo.poll.repository.*;
import ua.votivo.poll.exception.InvalidPollStateException;

@Service
@Transactional
public class PollOwnerLifecycleService {
    private final PollOwnerLifecycleRepository owners;
    private final PollRepository polls;

    public PollOwnerLifecycleService(PollOwnerLifecycleRepository owners, PollRepository polls) {
        this.owners = owners;
        this.polls = polls;
    }

    // Called inside the poll mutation transaction; lock stays held until that mutation commits.
    public void requireActiveOwner(String owner) {
        if (lock(owner).isDeletionReserved()) throw new AccessDeniedException("Account deletion is in progress");
    }

    public void reserveDeletion(String owner) {
        var lifecycle = lock(owner);
        if (polls.existsByOwnerIdAndStatus(owner, PollStatus.PUBLISHED))
            throw new InvalidPollStateException("Close published polls before deleting the account");
        lifecycle.reserveDeletion();
    }

    public void releaseDeletion(String owner) {
        lock(owner).releaseDeletion();
    }

    private PollOwnerLifecycle lock(String owner) {
        owners.ensureOwner(owner);
        return owners.lockOwner(owner).orElseThrow();
    }
}
