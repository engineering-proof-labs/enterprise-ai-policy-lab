package labs.engineeringproof.policylab.policies;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PolicyRepository {
    private static final String ACTIVE_CHUNKS = """
            SELECT c.id, c.document_id, d.title, c.revision_id, c.section_title, c.body
            FROM policy_chunk c
            JOIN policy_document d ON d.id = c.document_id AND d.active_revision_id = c.revision_id
            """;
    private static final RowMapper<PolicyChunk> CHUNK = (row, index) -> new PolicyChunk(
            row.getString("id"), row.getString("document_id"), row.getString("title"),
            row.getString("revision_id"), row.getString("section_title"), row.getString("body"));

    private final JdbcTemplate jdbc;

    public PolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PolicyChunk> search(String question) {
        return jdbc.query(ACTIVE_CHUNKS + """
                CROSS JOIN plainto_tsquery('english', ?) AS query(terms)
                WHERE c.search_terms @@ query.terms
                ORDER BY ts_rank(c.search_terms, query.terms) DESC, c.id
                LIMIT 3
                """, CHUNK, question);
    }

    public Optional<PolicyChunk> findActiveChunk(String chunkId) {
        return jdbc.query(ACTIVE_CHUNKS + "WHERE c.id = ?", CHUNK, chunkId).stream().findFirst();
    }
}
