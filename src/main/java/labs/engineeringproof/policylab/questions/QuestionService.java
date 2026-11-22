package labs.engineeringproof.policylab.questions;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import labs.engineeringproof.policylab.policies.PolicyChunk;
import labs.engineeringproof.policylab.policies.PolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
final class QuestionService {
    private static final Logger log = LoggerFactory.getLogger(QuestionService.class);
    private static final String ANSWERED = "answered";
    private static final String INSUFFICIENT = "insufficient_evidence";

    private final PolicyRepository policies;
    private final AnswerGenerator generator;

    QuestionService(PolicyRepository policies, AnswerGenerator generator) {
        this.policies = policies;
        this.generator = generator;
    }

    Answer ask(UUID requestId, String question, List<String> groups) {
        long started = System.nanoTime();
        List<PolicyChunk> evidence;
        try {
            evidence = policies.search(question, groups);
        } catch (DataAccessException failure) {
            log.warn("requestId={} retrievalMs={} retrievalStatus=policy_storage_unavailable",
                    requestId, elapsedMs(started));
            throw failure;
        }
        var selectedIds = evidence.stream().map(PolicyChunk::chunkId).toList();
        log.info("requestId={} retrievalMs={} selectedEvidenceChunkIds={}", requestId, elapsedMs(started), selectedIds);
        if (evidence.isEmpty()) {
            return new Answer(requestId, INSUFFICIENT, null, List.of());
        }

        started = System.nanoTime();
        AnswerGenerator.Draft draft;
        try {
            draft = generator.generate(requestId, question, evidence);
        } catch (QuestionFailure failure) {
            log.warn("requestId={} generationMs={} generationStatus={}", requestId, elapsedMs(started), failure.code());
            throw failure;
        }
        log.info("requestId={} generationMs={} generationStatus=received", requestId, elapsedMs(started));
        if (!policies.evidenceStillEligible(selectedIds, groups)) {
            throw QuestionFailure.evidenceChanged();
        }
        validateFields(draft);
        if (INSUFFICIENT.equals(draft.status())) {
            return new Answer(requestId, INSUFFICIENT, null, List.of());
        }

        var sentById = evidence.stream().collect(Collectors.toMap(PolicyChunk::chunkId, Function.identity()));
        if (!sentById.keySet().containsAll(draft.chunkIds())) {
            throw QuestionFailure.invalidModelOutput();
        }
        var citations = draft.chunkIds().stream().distinct().map(sentById::get).map(Citation::from).toList();
        return new Answer(requestId, ANSWERED, draft.answer(), citations);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static void validateFields(AnswerGenerator.Draft draft) {
        if (draft == null || draft.answer() == null || draft.chunkIds() == null
                || draft.chunkIds().stream().anyMatch(Objects::isNull)) {
            throw QuestionFailure.invalidModelOutput();
        }
        if (INSUFFICIENT.equals(draft.status()) && draft.answer().isEmpty() && draft.chunkIds().isEmpty()) {
            return;
        }
        if (ANSWERED.equals(draft.status()) && !draft.answer().isBlank() && !draft.chunkIds().isEmpty()) {
            return;
        }
        throw QuestionFailure.invalidModelOutput();
    }

    record Answer(UUID requestId, String status, String answer, List<Citation> citations) {
    }

    record Citation(String chunkId, String documentId, String documentTitle, String revisionId,
                    String sectionTitle, String sourceUrl) {
        static Citation from(PolicyChunk chunk) {
            return new Citation(chunk.chunkId(), chunk.documentId(), chunk.documentTitle(), chunk.revisionId(),
                    chunk.sectionTitle(), "/sources/" + chunk.chunkId());
        }
    }
}
