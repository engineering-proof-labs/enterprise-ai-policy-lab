package labs.engineeringproof.policylab.questions;

import java.util.List;
import java.util.UUID;

import labs.engineeringproof.policylab.policies.PolicyChunk;

interface AnswerGenerator {
    Draft generate(UUID requestId, String question, List<PolicyChunk> evidence);

    record Draft(String status, String answer, List<String> chunkIds) {
    }
}
