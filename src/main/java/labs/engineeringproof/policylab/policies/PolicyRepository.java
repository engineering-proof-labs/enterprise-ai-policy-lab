package labs.engineeringproof.policylab.policies;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PolicyRepository {
    public static final int SEARCH_LIMIT = 3;
    private static final String CHUNK_COLUMNS = """
            SELECT c.id, c.document_id, d.title, c.revision_id, c.section_title, c.body
            """;
    static final String ACTIVE_CHUNKS = """
            FROM policy_chunk c
            JOIN policy_document d ON d.id = c.document_id AND d.active_revision_id = c.revision_id
            """;
    static final String DOCUMENT_ACCESS = """
            WHERE EXISTS (
                SELECT 1 FROM policy_document_group g
                WHERE g.document_id = d.id AND g.group_id IN (:groups)
            )
            """;
    private static final RowMapper<PolicyChunk> CHUNK = (row, index) -> new PolicyChunk(
            row.getString("id"), row.getString("document_id"), row.getString("title"),
            row.getString("revision_id"), row.getString("section_title"), row.getString("body"));

    private final NamedParameterJdbcTemplate jdbc;

    public PolicyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PolicyChunk> search(String question, List<String> groups) {
        if (groups.isEmpty()) {
            return List.of();
        }
        return jdbc.query(CHUNK_COLUMNS + ACTIVE_CHUNKS + DOCUMENT_ACCESS + """
                AND c.search_terms @@ plainto_tsquery('english', :question)
                ORDER BY ts_rank(c.search_terms, plainto_tsquery('english', :question)) DESC, c.id
                LIMIT :limit
                """, Map.of("groups", groups, "question", question, "limit", SEARCH_LIMIT), CHUNK);
    }

    public Optional<PolicyChunk> findActiveChunk(String chunkId, List<String> groups) {
        if (groups.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.query(CHUNK_COLUMNS + ACTIVE_CHUNKS + DOCUMENT_ACCESS + "AND c.id = :id",
                Map.of("groups", groups, "id", chunkId), CHUNK).stream().findFirst();
    }

    public boolean evidenceStillEligible(List<String> ids, List<String> groups) {
        return !groups.isEmpty() && jdbc.queryForObject(
                "SELECT count(*) " + ACTIVE_CHUNKS + DOCUMENT_ACCESS + "AND c.id IN (:ids)",
                Map.of("groups", groups, "ids", ids), Long.class) == ids.size();
    }

    public boolean publish(String documentId, String revisionId) {
        return jdbc.update("""
                UPDATE policy_document d SET active_revision_id = :revision
                WHERE d.id = :document AND EXISTS (
                    SELECT 1 FROM policy_revision r WHERE r.document_id = d.id AND r.id = :revision
                )
                """, Map.of("document", documentId, "revision", revisionId)) == 1;
    }
}
