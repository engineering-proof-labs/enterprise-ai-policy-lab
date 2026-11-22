CREATE TABLE policy_document_group (
    document_id text NOT NULL REFERENCES policy_document(id),
    group_id text NOT NULL,
    PRIMARY KEY (document_id, group_id)
);

INSERT INTO policy_document_group (document_id, group_id)
VALUES ('ticket-routing', 'analyst'), ('ticket-routing', 'supervisor');
