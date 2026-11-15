package ua.votivo.poll.controller;

import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ua.votivo.poll.dto.response.*;
import ua.votivo.poll.service.PollService;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/polls")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPollController {
    private final PollService service;
    public AdminPollController(PollService service) { this.service = service; }

    @GetMapping
    public PageResponse<PollSummaryResponse> all(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.all(page, size);
    }

    @PostMapping("/{id}/block")
    public PollResponse block(@PathVariable UUID id) { return service.block(id); }
}
