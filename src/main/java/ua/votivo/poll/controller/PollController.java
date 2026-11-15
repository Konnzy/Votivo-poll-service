package ua.votivo.poll.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ua.votivo.poll.dto.request.PollRequest;
import ua.votivo.poll.dto.response.*;
import ua.votivo.poll.service.PollService;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/polls")
public class PollController {
    private final PollService service;
    public PollController(PollService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<PollResponse> create(@Valid @RequestBody PollRequest request) {
        var poll = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/polls/" + poll.id())).body(poll);
    }

    @GetMapping("/{id}")
    public PollResponse get(@PathVariable UUID id) { return service.get(id); }

    @GetMapping("/by-link/{token}")
    public PollResponse getByLink(@PathVariable UUID token) { return service.getByLink(token); }

    @GetMapping
    public PageResponse<PollSummaryResponse> publicPolls(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.publicPolls(page, size);
    }

    @GetMapping("/mine")
    public PageResponse<PollSummaryResponse> mine(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.mine(page, size);
    }

    @PutMapping("/{id}")
    public PollResponse update(@PathVariable UUID id, @Valid @RequestBody PollRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/publish")
    public PollResponse publish(@PathVariable UUID id) { return service.publish(id); }

    @PostMapping("/{id}/close")
    public PollResponse close(@PathVariable UUID id) { return service.close(id); }

    @PostMapping("/{id}/archive")
    public PollResponse archive(@PathVariable UUID id) { return service.archive(id); }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
