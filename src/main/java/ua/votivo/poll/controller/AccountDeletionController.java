package ua.votivo.poll.controller;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ua.votivo.poll.service.PollOwnerLifecycleService;

@RestController
@RequestMapping("/internal/v1/users/{id}/deletion")
public class AccountDeletionController {
    private final PollOwnerLifecycleService lifecycle;

    public AccountDeletionController(PollOwnerLifecycleService lifecycle) { this.lifecycle = lifecycle; }

    @PutMapping
    public ResponseEntity<Void> reserve(@PathVariable UUID id) {
        lifecycle.reserveDeletion(id.toString());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> release(@PathVariable UUID id) {
        lifecycle.releaseDeletion(id.toString());
        return ResponseEntity.noContent().build();
    }
}
