package db.migration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

public class V4__supervisor_procedure extends BaseJavaMigration {
    private static final String DOCUMENT = "supervisor-routing";

    @Override
    public void migrate(Context context) throws Exception {
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(), true));
        jdbc.update("INSERT INTO policy_document (id, title) VALUES (?, ?)", DOCUMENT, "Supervisor routing procedure");
        jdbc.update("INSERT INTO policy_document_group (document_id, group_id) VALUES (?, 'supervisor')", DOCUMENT);
        for (var revision : List.of("v1", "v2", "v3")) {
            jdbc.update("INSERT INTO policy_revision (document_id, id) VALUES (?, ?)", DOCUMENT, revision);
            try (var file = Objects.requireNonNull(getClass().getResourceAsStream(
                    "/policies/" + DOCUMENT + "-" + revision + ".md"))) {
                var markdown = new String(file.readAllBytes(), StandardCharsets.UTF_8);
                var sections = markdown.split("(?m)^## ");
                for (int index = 1; index < sections.length; index++) {
                    var section = sections[index].split("\\R", 2);
                    var key = switch (section[0]) {
                        case "Duplicate charge exception" -> "duplicate-charge";
                        case "Priority outage routing" -> "priority-outage";
                        default -> throw new IllegalArgumentException("Unknown fixture heading: " + section[0]);
                    };
                    jdbc.update("""
                            INSERT INTO policy_chunk (id, document_id, revision_id, section_title, body)
                            VALUES (?, ?, ?, ?, ?)
                            """, DOCUMENT + "-" + revision + "-" + key, DOCUMENT, revision, section[0], section[1].strip());
                }
            }
        }
        jdbc.update("UPDATE policy_document SET active_revision_id = 'v2' WHERE id = ?", DOCUMENT);
    }
}
