package labs.engineeringproof.policylab.policies;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class PolicyStorageErrors {
    @ExceptionHandler(DataAccessException.class)
    ProblemDetail dataUnavailable() {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Policy storage is temporarily unavailable.");
        problem.setProperty("code", "policy_storage_unavailable");
        return problem;
    }
}
