package labs.engineeringproof.policylab.questions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import labs.engineeringproof.policylab.policies.PolicyChunk;
import labs.engineeringproof.policylab.policies.PolicyRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class QuestionApiTest {
    private static final String QUESTION = "Where do I route billing tickets?";
    private static final String EXCEPTION = "Route duplicate charge billing tickets";
    private static final String BILLING_ID = "ticket-routing-v1-billing";
    private static final String V2 = "supervisor-routing-v2-duplicate-charge";
    private static final String V3 = "supervisor-routing-v3-duplicate-charge";
    private static final String V1 = "supervisor-routing-v1-duplicate-charge";
    private static final String PUBLISH = "/policies/supervisor-routing/active-revision";
    private static final String ANSWER = "Route billing tickets to the Billing Operations queue.";
    private static final SignedJwtIssuer ISSUER = new SignedJwtIssuer();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17.12-bookworm");

    @Autowired MockMvc mvc;
    @Autowired PolicyRepository policies;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @MockitoBean AnswerGenerator generator;

    String analyst;
    String supervisor;

    @DynamicPropertySource
    static void security(DynamicPropertyRegistry properties) {
        properties.add("lab.auth.issuer", ISSUER::issuer);
        properties.add("lab.auth.jwk-set-uri", ISSUER::jwksUri);
        properties.add("spring.jdbc.template.query-timeout", () -> "8s");
    }

    @AfterAll
    static void stopIssuer() {
        ISSUER.close();
    }

    @BeforeEach
    void signTokens() throws Exception {
        analyst = ISSUER.token("analyst");
        supervisor = ISSUER.token("supervisor", "policy-publisher");
    }

    @Test
    void rejectsMissingInvalidWrongIssuerWrongAudienceAndExpiredTokens() throws Exception {
        var expiration = Instant.now().plusSeconds(300);
        var parts = analyst.split("\\.");
        var invalidSignature = parts[0] + "." + parts[1] + "." +
                (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        mvc.perform(post("/questions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/sources/" + BILLING_ID)).andExpect(status().isUnauthorized());

        for (var token : List.of(invalidSignature, "invalid",
                ISSUER.customToken("https://wrong.example.invalid", "policy-api", expiration, "analyst"),
                ISSUER.customToken(ISSUER.issuer(), "other-api", expiration, "analyst"),
                ISSUER.customToken(ISSUER.issuer(), "policy-api", Instant.now().minusSeconds(120), "analyst"),
                ISSUER.customToken(ISSUER.issuer(), "policy-api", null, "analyst"))) {
            mvc.perform(question(QUESTION, token)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(generator);
    }

    @Test
    void retrievesTheBillingSectionAndResolvesTheCitation() throws Exception {
        when(generator.generate(any(), eq(QUESTION), anyList())).thenAnswer(call -> {
            List<PolicyChunk> evidence = call.getArgument(2);
            assertThat(evidence).extracting(PolicyChunk::chunkId).containsExactly(BILLING_ID);
            assertThat(evidence.getFirst().text()).startsWith(ANSWER);
            return new AnswerGenerator.Draft("answered", ANSWER, List.of(BILLING_ID));
        });

        var response = mvc.perform(question(QUESTION, analyst))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.status").value("answered"))
                .andExpect(jsonPath("$.answer").value(ANSWER))
                .andExpect(jsonPath("$.citations[0].documentId").value("ticket-routing"))
                .andExpect(jsonPath("$.citations[0].revisionId").value("v1"))
                .andExpect(jsonPath("$.citations[0].sectionTitle").value("Billing tickets"))
                .andExpect(jsonPath("$.citations[0].sourceUrl").value("/sources/" + BILLING_ID))
                .andReturn().getResponse();

        assertThat(response.getContentAsString()).contains(response.getHeader("X-Request-Id"));
        mvc.perform(source(BILLING_ID, analyst)).andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkId").value(BILLING_ID))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.startsWith(ANSWER)));
    }

    @Test
    void sendsOnlyTheActiveExceptionToTheSupervisorAndAppliesRevocation() throws Exception {
        assertThat(jdbc.queryForObject("""
                SELECT id FROM policy_chunk
                WHERE document_id = 'supervisor-routing' AND search_terms @@ plainto_tsquery('english', ?)
                ORDER BY ts_rank(search_terms, plainto_tsquery('english', ?)) DESC, id LIMIT 1
                """, String.class, EXCEPTION, EXCEPTION)).isEqualTo(V1);
        mvc.perform(question(EXCEPTION, analyst)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("insufficient_evidence"));
        verifyNoInteractions(generator);
        var forbidden = mvc.perform(source(V2, analyst)).andExpect(status().isNotFound()).andReturn().getResponse();
        var unknown = mvc.perform(source("unknown", analyst)).andExpect(status().isNotFound()).andReturn().getResponse();
        assertThat(forbidden.getContentAsString()).isEqualTo(unknown.getContentAsString());

        when(generator.generate(any(), eq(EXCEPTION), anyList())).thenAnswer(call -> {
            List<PolicyChunk> evidence = call.getArgument(2);
            assertThat(evidence).extracting(PolicyChunk::chunkId).containsExactly(V2);
            var chunk = evidence.getFirst();
            assertThat(chunk.text()).contains("Revenue Assurance");
            return new AnswerGenerator.Draft("answered", chunk.text(), List.of(V2));
        });
        mvc.perform(question(EXCEPTION, supervisor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.citations[0].chunkId").value(V2));
        mvc.perform(source(V2, supervisor)).andExpect(status().isOk());
        mvc.perform(source(V1, supervisor)).andExpect(status().isNotFound());
        mvc.perform(source(V3, supervisor)).andExpect(status().isNotFound());

        revokeSupervisor();
        try {
            mvc.perform(source(V2, supervisor)).andExpect(status().isNotFound());
            mvc.perform(question(EXCEPTION, supervisor)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("insufficient_evidence"));
        } finally {
            grantSupervisor();
        }
    }

    @Test
    @Transactional
    void appliesPermissionsBeforeTheRankLimit() {
        for (int index = 0; index < 4; index++) {
            var document = "restricted-" + index;
            jdbc.update("INSERT INTO policy_document (id, title) VALUES (?, ?)", document, document);
            jdbc.update("INSERT INTO policy_revision (document_id, id) VALUES (?, 'v1')", document);
            jdbc.update("UPDATE policy_document SET active_revision_id = 'v1' WHERE id = ?", document);
            jdbc.update("INSERT INTO policy_document_group VALUES (?, 'supervisor')", document);
            jdbc.update("""
                    INSERT INTO policy_chunk (id, document_id, revision_id, section_title, body)
                    VALUES (?, ?, 'v1', 'Billing tickets routing', ?)
                    """, document, document, "Route billing tickets. ".repeat(30));
        }

        assertThat(jdbc.queryForList("""
                SELECT c.id FROM policy_chunk c JOIN policy_document d
                    ON d.id = c.document_id AND d.active_revision_id = c.revision_id
                WHERE c.search_terms @@ plainto_tsquery('english', ?)
                ORDER BY ts_rank(c.search_terms, plainto_tsquery('english', ?)) DESC, c.id LIMIT 3
                """, String.class, QUESTION, QUESTION)).allMatch(id -> id.startsWith("restricted-")).hasSize(3);
        assertThat(policies.search(QUESTION, List.of("analyst"))).extracting(PolicyChunk::chunkId)
                .containsExactly(BILLING_ID);
    }

    @Test
    void abstainsWhenNoSectionMatchesWithoutCallingTheModel() throws Exception {
        mvc.perform(question("What is the travel reimbursement policy?", analyst))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("insufficient_evidence"))
                .andExpect(jsonPath("$.citations").isEmpty());
        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "ticket-routing-v1-password-reset"})
    void rejectsACitationThatWasNotSentToTheModel(String chunkId) throws Exception {
        when(generator.generate(any(), eq(QUESTION), anyList())).thenReturn(new AnswerGenerator.Draft(
                "answered", ANSWER, List.of(chunkId)));

        mvc.perform(question(QUESTION, analyst)).andExpect(status().isBadGateway())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code").value("invalid_model_output"))
                .andExpect(jsonPath("$.answer").doesNotExist());
    }

    @Test
    void returnsAStable503WhenTheProviderExceedsItsDeadline() throws Exception {
        when(generator.generate(any(), eq(QUESTION), anyList())).thenThrow(QuestionFailure.modelUnavailable());
        mvc.perform(question(QUESTION, analyst)).andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code").value("model_unavailable"))
                .andExpect(jsonPath("$.answer").doesNotExist());
    }

    @Test
    void rejectsInvalidRequestAndReturnsNotFoundForMissingSource() throws Exception {
        mvc.perform(question(" ", analyst)).andExpect(status().isBadRequest());
        mvc.perform(source("unknown", analyst)).andExpect(status().isNotFound());
        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void discardsAnAnswerWhenEvenUncitedEvidenceChangesMidFlight(boolean revoke) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var input = "Route tickets";
        when(generator.generate(any(), eq(input), anyList())).thenAnswer(call -> {
            List<PolicyChunk> chunks = call.getArgument(2);
            assertThat(chunks).extracting(PolicyChunk::chunkId).contains(BILLING_ID, V2);
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new AnswerGenerator.Draft("answered", ANSWER, List.of(BILLING_ID));
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> mvc.perform(question(input, supervisor)));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                if (revoke) {
                    revokeSupervisor();
                } else {
                    mvc.perform(publication("v3", supervisor)).andExpect(status().isNoContent());
                }
                release.countDown();
                pending.get(10, TimeUnit.SECONDS).andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("evidence_changed"))
                        .andExpect(jsonPath("$.answer").doesNotExist());
                mvc.perform(source(V2, supervisor)).andExpect(status().isNotFound());
                if (!revoke) {
                    mvc.perform(source(V3, supervisor)).andExpect(status().isOk());
                }
            } finally {
                release.countDown();
                if (revoke) {
                    grantSupervisor();
                } else {
                    assertThat(policies.publish("supervisor-routing", "v2")).isTrue();
                }
            }
        }
    }

    @Test
    void concurrentPublicationsSerializeOnOneDocument() throws Exception {
        mvc.perform(publication("v1", analyst)).andExpect(status().isForbidden());
        mvc.perform(publication("missing", supervisor)).andExpect(status().isNotFound());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var guard = dataSource.getConnection()) {
            guard.setAutoCommit(false);
            try {
                try (var lock = guard.prepareStatement(
                        "SELECT id FROM policy_document WHERE id = 'supervisor-routing' FOR UPDATE")) {
                    lock.executeQuery().close();
                }
                var first = executor.submit(() -> policies.publish("supervisor-routing", "v1"));
                var second = executor.submit(() -> policies.publish("supervisor-routing", "v3"));
                await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE wait_event_type = 'Lock' AND query LIKE 'UPDATE policy_document d SET active_revision_id%'
                        """, Integer.class) == 2);
                guard.commit();
                assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
                assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();

                var active = policies.search(EXCEPTION, List.of("supervisor"));
                assertThat(active).extracting(PolicyChunk::chunkId).singleElement().isIn(V1, V3);
                var activeId = active.getFirst().chunkId();
                mvc.perform(source(activeId, supervisor)).andExpect(status().isOk());
                mvc.perform(source(activeId.equals(V1) ? V3 : V1, supervisor)).andExpect(status().isNotFound());
            } finally {
                guard.rollback();
                assertThat(policies.publish("supervisor-routing", "v2")).isTrue();
            }
        }
    }

    private void revokeSupervisor() {
        jdbc.update("DELETE FROM policy_document_group WHERE document_id = 'supervisor-routing' AND group_id = 'supervisor'");
    }

    private void grantSupervisor() {
        jdbc.update("INSERT INTO policy_document_group VALUES ('supervisor-routing', 'supervisor') ON CONFLICT DO NOTHING");
    }

    private static MockHttpServletRequestBuilder question(String text, String token) {
        return post("/questions").contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .content("{\"question\":\"" + text + "\"}");
    }

    private static MockHttpServletRequestBuilder source(String chunkId, String token) {
        return get("/sources/" + chunkId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder publication(String revision, String token) {
        return put(PUBLISH).contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .content("{\"revisionId\":\"" + revision + "\"}");
    }
}
