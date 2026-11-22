package labs.engineeringproof.policylab.questions;

import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import labs.engineeringproof.policylab.policies.PolicyChunk;
import labs.engineeringproof.policylab.policies.PolicyRepository;
import labs.engineeringproof.policylab.security.PolicySecurity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class QuestionController {
    private final QuestionService questions;
    private final PolicyRepository policies;

    QuestionController(QuestionService questions, PolicyRepository policies) {
        this.questions = questions;
        this.policies = policies;
    }

    @PostMapping("/questions")
    QuestionService.Answer ask(@Valid @RequestBody AskQuestionRequest request,
                               @AuthenticationPrincipal Jwt jwt, HttpServletResponse response) {
        var requestId = UUID.randomUUID();
        response.setHeader("X-Request-Id", requestId.toString());
        return questions.ask(requestId, request.question(), PolicySecurity.groups(jwt));
    }

    @GetMapping("/sources/{chunkId}")
    ResponseEntity<PolicyChunk> source(@PathVariable String chunkId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.of(policies.findActiveChunk(chunkId, PolicySecurity.groups(jwt)));
    }

    record AskQuestionRequest(@NotBlank @Size(max = 500) String question) {
    }
}
