package labs.engineeringproof.policylab.questions;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import labs.engineeringproof.policylab.policies.PolicyChunk;
import labs.engineeringproof.policylab.policies.PolicyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QuestionServiceTest {
    private static final String QUESTION = "Where do I route billing tickets?";
    private static final PolicyChunk BILLING = new PolicyChunk("billing-v1", "guide", "Guide", "v1",
            "Billing", "Use the Billing Operations queue.");

    private static final List<String> GROUPS = List.of("analyst");

    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final AnswerGenerator model = mock(AnswerGenerator.class);
    private final QuestionService service = new QuestionService(policies, model);

    @Test
    void returnsInsufficientEvidenceWithoutCallingModelWhenSearchIsEmpty() {
        when(policies.search(QUESTION, GROUPS)).thenReturn(List.of());

        assertThat(service.ask(UUID.randomUUID(), QUESTION, GROUPS).status()).isEqualTo("insufficient_evidence");
        verifyNoInteractions(model);
    }

    @Test
    void acceptsAnExplicitModelAbstention() {
        when(policies.search(QUESTION, GROUPS)).thenReturn(List.of(BILLING));
        when(policies.evidenceStillEligible(List.of(BILLING.chunkId()), GROUPS)).thenReturn(true);
        when(model.generate(any(), eq(QUESTION), eq(List.of(BILLING))))
                .thenReturn(new AnswerGenerator.Draft("insufficient_evidence", "", List.of()));

        var answer = service.ask(UUID.randomUUID(), QUESTION, GROUPS);
        assertThat(answer.status()).isEqualTo("insufficient_evidence");
        assertThat(answer.answer()).isNull();
        assertThat(answer.citations()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidDrafts")
    void rejectsInvalidDrafts(AnswerGenerator.Draft draft) {
        when(policies.search(QUESTION, GROUPS)).thenReturn(List.of(BILLING));
        when(policies.evidenceStillEligible(List.of(BILLING.chunkId()), GROUPS)).thenReturn(true);
        when(model.generate(any(), eq(QUESTION), eq(List.of(BILLING)))).thenReturn(draft);

        assertThatThrownBy(() -> service.ask(UUID.randomUUID(), QUESTION, GROUPS))
                .isInstanceOfSatisfying(QuestionFailure.class, failure ->
                        assertThat(failure.getBody().getProperties()).containsEntry("code", "invalid_model_output"));
    }

    private static Stream<AnswerGenerator.Draft> invalidDrafts() {
        return Stream.of(null,
                new AnswerGenerator.Draft("answered", "Route to Billing.", List.of("unknown")),
                new AnswerGenerator.Draft("answered", "Route to Billing.", List.of()),
                new AnswerGenerator.Draft("answered", " ", List.of(BILLING.chunkId())),
                new AnswerGenerator.Draft("insufficient_evidence", "", List.of(BILLING.chunkId())),
                new AnswerGenerator.Draft("unknown", "", List.of()));
    }
}
