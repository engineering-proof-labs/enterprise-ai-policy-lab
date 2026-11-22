package labs.engineeringproof.policylab.questions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

final class QuestionFailure extends ErrorResponseException {
    private QuestionFailure(HttpStatus status, String code, String detail) {
        super(status, ProblemDetail.forStatusAndDetail(status, detail), null);
        getBody().setProperty("code", code);
    }

    static QuestionFailure invalidModelOutput() {
        return new QuestionFailure(HttpStatus.BAD_GATEWAY, "invalid_model_output",
                "The model returned an invalid answer or citation.");
    }

    static QuestionFailure modelUnavailable() {
        return new QuestionFailure(HttpStatus.SERVICE_UNAVAILABLE, "model_unavailable",
                "The model is temporarily unavailable. Retry the question.");
    }
}
