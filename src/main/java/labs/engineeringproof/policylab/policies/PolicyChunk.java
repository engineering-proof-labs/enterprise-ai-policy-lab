package labs.engineeringproof.policylab.policies;

public record PolicyChunk(String chunkId, String documentId, String documentTitle, String revisionId,
                          String sectionTitle, String text) {
}
