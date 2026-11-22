package labs.engineeringproof.policylab.policies;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
final class PublicationController {
    private final PolicyRepository policies;

    PublicationController(PolicyRepository policies) {
        this.policies = policies;
    }

    @PutMapping("/policies/{documentId}/active-revision")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void publish(@PathVariable String documentId, @Valid @RequestBody Publication request) {
        if (!policies.publish(documentId, request.revisionId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Policy revision not found.");
        }
    }

    record Publication(@NotBlank @Size(max = 80) String revisionId) {
    }
}
