package ua.votivo.poll.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import ua.votivo.poll.dto.request.PollRequest;
import ua.votivo.poll.dto.response.*;
import ua.votivo.poll.exception.*;
import ua.votivo.poll.mapper.PollMapper;
import ua.votivo.poll.model.Poll;
import ua.votivo.poll.model.enums.*;
import ua.votivo.poll.repository.PollRepository;
import ua.votivo.poll.security.CurrentUserProvider;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PollService {
    private final PollRepository repository;
    private final PollMapper mapper;
    private final PollAccessService access;
    private final PollValidationService validation;
    private final CurrentUserProvider users;

    public PollService(PollRepository repository, PollMapper mapper, PollAccessService access,
                       PollValidationService validation, CurrentUserProvider users) {
        this.repository = repository;
        this.mapper = mapper;
        this.access = access;
        this.validation = validation;
        this.users = users;
    }

    @Transactional
    public PollResponse create(PollRequest request) {
        var poll = new Poll(users.require().id(), request.title(), request.description(),
                request.accessType(), request.anonymous(), mapper.questions(request));
        return mapper.response(repository.save(poll), true);
    }

    public PollResponse get(UUID id) {
        var poll = find(id);
        boolean manager = access.canManage(poll);
        if (!manager && (poll.isBlocked() || poll.getStatus() == PollStatus.DRAFT
                || poll.getAccessType() != AccessType.PUBLIC)) throw new PollNotFoundException();
        return mapper.response(poll, manager);
    }

    public PollResponse getByLink(UUID token) {
        var poll = repository.findByShareToken(token).orElseThrow(PollNotFoundException::new);
        if (poll.isBlocked() || poll.getStatus() == PollStatus.DRAFT || poll.getAccessType() != AccessType.LINK) {
            throw new PollNotFoundException();
        }
        return mapper.response(poll, access.canManage(poll));
    }

    public PageResponse<PollSummaryResponse> publicPolls(int page, int size) {
        return PageResponse.from(repository.findByAccessTypeAndStatusAndBlockedFalse(
                AccessType.PUBLIC, PollStatus.PUBLISHED, page(page, size)).map(mapper::summary));
    }

    public PageResponse<PollSummaryResponse> mine(int page, int size) {
        return PageResponse.from(repository.findByOwnerId(users.require().id(), page(page, size)).map(mapper::summary));
    }

    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<PollSummaryResponse> all(int page, int size) {
        return PageResponse.from(repository.findAll(page(page, size)).map(mapper::summary));
    }

    @Transactional
    public PollResponse update(UUID id, PollRequest request) {
        var poll = owned(id);
        validation.requireEditable(poll);
        poll.replaceContent(request.title(), request.description(), request.accessType(),
                request.anonymous(), mapper.questions(request));
        return mapper.response(poll, true);
    }

    @Transactional
    public PollResponse publish(UUID id) {
        var poll = owned(id);
        validation.requirePublishable(poll);
        poll.publish();
        return mapper.response(poll, true);
    }

    @Transactional
    public PollResponse close(UUID id) {
        var poll = owned(id);
        validation.requireState(poll, PollStatus.PUBLISHED);
        poll.close();
        return mapper.response(poll, true);
    }

    @Transactional
    public PollResponse archive(UUID id) {
        var poll = owned(id);
        validation.requireState(poll, PollStatus.CLOSED);
        poll.archive();
        return mapper.response(poll, true);
    }

    @Transactional
    public void delete(UUID id) {
        var poll = owned(id);
        // Until Vote Service supplies a coordinated no-votes check, only never-published drafts are deletable.
        validation.requireState(poll, PollStatus.DRAFT);
        repository.delete(poll);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public PollResponse block(UUID id) {
        var poll = find(id);
        poll.block();
        return mapper.response(poll, true);
    }

    private Poll owned(UUID id) {
        var poll = find(id);
        access.requireOwner(poll);
        return poll;
    }

    private Poll find(UUID id) { return repository.findById(id).orElseThrow(PollNotFoundException::new); }

    private PageRequest page(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
