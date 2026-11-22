package labs.engineeringproof.policylab.questions;

import java.util.List;

import labs.engineeringproof.policylab.policies.PolicyChunk;

interface AnswerGenerator {
    Draft generate(String question, List<PolicyChunk> evidence);

    record Draft(String status, String answer, List<String> chunkIds) {
    }
}
