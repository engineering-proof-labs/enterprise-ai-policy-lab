package labs.engineeringproof.policylab.questions;

import java.util.List;

import labs.engineeringproof.policylab.policies.PolicyChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class QuestionApiTest {
    private static final String QUESTION = "Where do I route billing tickets?";
    private static final String REQUEST = """
            {"question":"Where do I route billing tickets?"}
            """;
    private static final String BILLING_ID = "ticket-routing-v1-billing";
    private static final String SOURCE = "/sources/" + BILLING_ID;
    private static final String ANSWER = "Route billing tickets to the Billing Operations queue.";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17.12-bookworm");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AnswerGenerator generator;

    @Test
    void retrievesTheBillingSectionAndResolvesTheCitation() throws Exception {
        when(generator.generate(eq(QUESTION), anyList())).thenAnswer(call -> {
            List<PolicyChunk> evidence = call.getArgument(1);
            assertThat(evidence).extracting(PolicyChunk::chunkId).containsExactly(BILLING_ID);
            assertThat(evidence.getFirst().text()).startsWith(ANSWER);
            return new AnswerGenerator.Draft("answered", ANSWER, List.of(BILLING_ID));
        });

        var response = mvc.perform(post("/questions").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.status").value("answered"))
                .andExpect(jsonPath("$.answer").value(ANSWER))
                .andExpect(jsonPath("$.citations[0].documentId").value("ticket-routing"))
                .andExpect(jsonPath("$.citations[0].revisionId").value("v1"))
                .andExpect(jsonPath("$.citations[0].sectionTitle").value("Billing tickets"))
                .andExpect(jsonPath("$.citations[0].sourceUrl").value(SOURCE))
                .andReturn().getResponse();

        assertThat(response.getContentAsString()).contains(response.getHeader("X-Request-Id"));
        mvc.perform(get(SOURCE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkId").value(BILLING_ID))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.startsWith(ANSWER)));
    }

    @Test
    void abstainsWhenNoSectionMatchesWithoutCallingTheModel() throws Exception {
        mvc.perform(post("/questions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"What is the travel reimbursement policy?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("insufficient_evidence"))
                .andExpect(jsonPath("$.citations").isEmpty());

        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "ticket-routing-v1-password-reset"})
    void rejectsACitationThatWasNotSentToTheModel(String chunkId) throws Exception {
        when(generator.generate(eq(QUESTION), anyList())).thenReturn(new AnswerGenerator.Draft(
                "answered", ANSWER, List.of(chunkId)));

        mvc.perform(post("/questions").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isBadGateway())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code").value("invalid_model_output"))
                .andExpect(jsonPath("$.answer").doesNotExist());
    }

    @Test
    void rejectsInvalidRequestAndReturnsNotFoundForMissingSource() throws Exception {
        mvc.perform(post("/questions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":" "}
                                """))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/sources/unknown")).andExpect(status().isNotFound());

        verifyNoInteractions(generator);
    }
}
