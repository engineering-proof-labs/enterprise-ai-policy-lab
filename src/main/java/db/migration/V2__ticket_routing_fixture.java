package db.migration;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

public class V2__ticket_routing_fixture extends BaseJavaMigration {
    private static final String DOCUMENT = "ticket-routing";
    private static final String REVISION = "v1";
    private static final Map<String, String> CHUNK_IDS = Map.of(
            "Billing tickets", "ticket-routing-v1-billing",
            "Password reset", "ticket-routing-v1-password-reset"
    );

    @Override
    public void migrate(Context context) throws Exception {
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(), true));
        try (var fixture = Objects.requireNonNull(getClass().getResourceAsStream("/policies/ticket-routing-v1.md"))) {
            var markdown = new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
            var sections = markdown.split("(?m)^## ");
            var title = sections[0].strip().substring(2);
            jdbc.update("INSERT INTO policy_document (id, title) VALUES (?, ?)", DOCUMENT, title);
            jdbc.update("INSERT INTO policy_revision (document_id, id) VALUES (?, ?)", DOCUMENT, REVISION);
            for (int i = 1; i < sections.length; i++) {
                var headingAndBody = sections[i].split("\\R", 2);
                var heading = headingAndBody[0];
                jdbc.update("""
                        INSERT INTO policy_chunk (id, document_id, revision_id, section_title, body)
                        VALUES (?, ?, ?, ?, ?)
                        """, CHUNK_IDS.get(heading), DOCUMENT, REVISION, heading, headingAndBody[1].strip());
            }
            jdbc.update("UPDATE policy_document SET active_revision_id = ? WHERE id = ?", REVISION, DOCUMENT);
        }
    }
}
