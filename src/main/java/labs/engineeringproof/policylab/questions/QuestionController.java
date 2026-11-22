package labs.engineeringproof.policylab.questions;

import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import labs.engineeringproof.policylab.policies.PolicyChunk;
import labs.engineeringproof.policylab.policies.PolicyRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
final class QuestionController {
    private final QuestionService questions;
    private final PolicyRepository policies;

    QuestionController(QuestionService questions, PolicyRepository policies) {
        this.questions = questions;
        this.policies = policies;
    }

    @PostMapping("/questions")
    QuestionService.Answer ask(@Valid @RequestBody AskQuestionRequest request, HttpServletResponse response) {
        var requestId = UUID.randomUUID();
        response.setHeader("X-Request-Id", requestId.toString());
        return questions.ask(requestId, request.question());
    }

    @GetMapping("/sources/{chunkId}")
    PolicyChunk source(@PathVariable String chunkId) {
        return policies.findActiveChunk(chunkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found."));
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail dataUnavailable() {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Policy storage is temporarily unavailable.");
        problem.setProperty("code", "policy_storage_unavailable");
        return problem;
    }

    record AskQuestionRequest(@NotBlank @Size(max = 500) String question) {
    }
}
