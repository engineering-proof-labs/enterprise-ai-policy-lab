CREATE TABLE policy_document (
    id text PRIMARY KEY,
    title text NOT NULL,
    active_revision_id text
);

CREATE TABLE policy_revision (
    document_id text NOT NULL REFERENCES policy_document(id),
    id text NOT NULL,
    PRIMARY KEY (document_id, id)
);

ALTER TABLE policy_document ADD CONSTRAINT active_revision_of_document
    FOREIGN KEY (id, active_revision_id) REFERENCES policy_revision(document_id, id);

CREATE TABLE policy_chunk (
    id text PRIMARY KEY,
    document_id text NOT NULL,
    revision_id text NOT NULL,
    section_title text NOT NULL,
    body text NOT NULL CHECK (char_length(body) BETWEEN 1 AND 1500),
    search_terms tsvector GENERATED ALWAYS AS (
        to_tsvector('english', section_title || ' ' || body)
    ) STORED,
    FOREIGN KEY (document_id, revision_id) REFERENCES policy_revision(document_id, id)
);
