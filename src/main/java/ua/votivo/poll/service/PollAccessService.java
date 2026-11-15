package ua.votivo.poll.service;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import ua.votivo.poll.model.Poll;
import ua.votivo.poll.security.CurrentUserProvider;

@Service
public class PollAccessService {
    private final CurrentUserProvider users;

    public PollAccessService(CurrentUserProvider users) { this.users = users; }

    public boolean canManage(Poll poll) {
        return users.current().map(user -> user.admin() || poll.getOwnerId().equals(user.id())).orElse(false);
    }

    public void requireOwner(Poll poll) {
        if (!poll.getOwnerId().equals(users.require().id())) throw new AccessDeniedException("Owner required");
    }
}
