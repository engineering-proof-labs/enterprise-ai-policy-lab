package labs.engineeringproof.policylab.policies;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Component
@Profile("retrieval-eval")
final class RetrievalEvaluation implements ApplicationRunner {
    private static final String EMBEDDING_MODEL = "all-minilm:v2";
    private static final String EMBEDDING_ID = "1b226e2802db";
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final TransactionTemplate transaction;
    private final PolicyRepository policies;
    private final ObjectMapper json;
    private final RestClient ollama;

    RetrievalEvaluation(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc,
                        TransactionTemplate transaction, PolicyRepository policies, ObjectMapper json,
                        @Value("${lab.ollama.url}") String ollamaUrl) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.transaction = transaction;
        this.policies = policies;
        this.json = json;
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(Duration.ofSeconds(60));
        ollama = RestClient.builder().baseUrl(ollamaUrl).requestFactory(requests).build();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!"policy_eval".equals(jdbc.queryForObject("SELECT current_database()", String.class))) {
            throw new IllegalStateException("Run retrieval evaluation against the isolated policy_eval database.");
        }
        Fixture fixture;
        try (var input = new ClassPathResource("evaluation/questions.json").getInputStream()) {
            fixture = json.readValue(input, Fixture.class);
        }

        var tags = ollama.get().uri("/api/tags").retrieve().body(ModelTags.class);
        var digest = tags.models().stream().filter(model -> EMBEDDING_MODEL.equals(model.name()))
                .map(ModelTag::digest).findFirst().orElseThrow();
        if (!digest.startsWith(EMBEDDING_ID)) {
            throw new IllegalStateException("Unexpected embedding model digest: " + digest);
        }

        var corpus = jdbc.query("""
                SELECT id, section_title || E'\\n' || body AS text FROM policy_chunk ORDER BY id
                """, (row, index) -> new Passage(row.getString("id"), row.getString("text")));
        long started = System.nanoTime();
        var texts = Stream.concat(corpus.stream().map(Passage::text), fixture.cases().stream().map(Case::question))
                .toList();
        var embeddings = ollama.post().uri("/api/embed")
                .body(Map.of("model", EMBEDDING_MODEL, "input", texts, "truncate", false))
                .retrieve().body(EmbeddingResponse.class).embeddings();
        long embeddingMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        if (embeddings.length != texts.size()) {
            throw new IllegalStateException("Embedding response count differs from the input count.");
        }

        var comparison = transaction.execute(status -> {
            status.setRollbackOnly();
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
            jdbc.execute("""
                    CREATE TEMP TABLE evaluation_embeddings (
                        chunk_id text PRIMARY KEY,
                        embedding vector(384) NOT NULL
                    ) ON COMMIT DROP
                    """);
            for (int index = 0; index < corpus.size(); index++) {
                jdbc.update("INSERT INTO evaluation_embeddings VALUES (?, CAST(? AS vector))",
                        corpus.get(index).id(), Arrays.toString(embeddings[index]));
            }
            var rows = new ArrayList<Result>();
            for (int index = 0; index < fixture.cases().size(); index++) {
                rows.add(compare(fixture.cases().get(index), embeddings[corpus.size() + index]));
            }
            return new Comparison(jdbc.queryForObject(
                    "SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class), rows);
        });

        var scores = comparison.results();
        double threshold = calibrate(scores);
        for (var row : scores) {
            System.out.println("RETRIEVAL_CASE " + json.writeValueAsString(new CaseReport(
                    row.test().id(), row.test().split(), row.test().scenario(), row.test().group(), row.test().revision(),
                    row.test().expected(), row.textIds(), vectorIds(row, threshold), row.vector(), row.textUs(), row.vectorUs())));
        }
        System.out.println("RETRIEVAL_RUN " + json.writeValueAsString(Map.of(
                "corpus", fixture.corpus(), "chunks", corpus.size(), "cases", scores.size(),
                "database", jdbc.queryForObject("SHOW server_version", String.class),
                "pgvector", comparison.version(), "embeddingModel", EMBEDDING_MODEL, "embeddingDigest", digest,
                "embeddingBatchMs", embeddingMs, "vectorMinimumSimilarity", threshold, "limit", PolicyRepository.SEARCH_LIMIT)));
        for (var split : List.of("calibration", "holdout")) {
            var rows = scores.stream().filter(row -> row.test().split().equals(split)).toList();
            summary(split, "text", rows, Result::textIds);
            summary(split, "vector_exact", rows, row -> vectorIds(row, threshold));
        }
    }

    private Result compare(Case test, float[] embedding) {
        if (!policies.publish("supervisor-routing", test.revision())) {
            throw new IllegalArgumentException("Unknown evaluation revision " + test.revision());
        }

        var groups = List.of(test.group());
        long started = System.nanoTime();
        var text = policies.search(test.question(), groups).stream().map(PolicyChunk::chunkId).toList();
        long textUs = (System.nanoTime() - started) / 1_000;
        started = System.nanoTime();
        var vector = namedJdbc.query("""
                SELECT c.id, 1 - (e.embedding <=> CAST(:embedding AS vector)) AS similarity
                """ + PolicyRepository.ACTIVE_CHUNKS
                + "JOIN pg_temp.evaluation_embeddings e ON e.chunk_id = c.id\n" + PolicyRepository.DOCUMENT_ACCESS + """
                ORDER BY similarity DESC, c.id LIMIT :limit
                """, Map.of("embedding", Arrays.toString(embedding), "groups", groups,
                "limit", PolicyRepository.SEARCH_LIMIT),
                (row, index) -> new Candidate(row.getString("id"), row.getDouble("similarity")));
        long vectorUs = (System.nanoTime() - started) / 1_000;

        Stream.concat(Stream.concat(text.stream(), vector.stream().map(Candidate::id)), test.expected().stream())
                .distinct().forEach(id -> {
                    if (policies.findActiveChunk(id, groups).isEmpty()) {
                        throw new IllegalStateException("Ineligible evidence " + id + " for " + test.id());
                    }
                });
        return new Result(test, text, vector, textUs, vectorUs);
    }

    private static List<String> vectorIds(Result result, double threshold) {
        return result.vector().stream().filter(hit -> hit.similarity() >= threshold).map(Candidate::id).toList();
    }

    private static double calibrate(List<Result> results) {
        var rows = results.stream().filter(row -> row.test().split().equals("calibration")).toList();
        double bestScore = -1;
        double bestThreshold = 0;
        for (int index = 0; index <= 21; index++) {
            double threshold = index * 0.05;
            var counts = count(rows, row -> vectorIds(row, threshold));
            double score = (double) counts.positiveHits() / counts.positiveCases()
                    + (double) counts.emptyNegative() / counts.negativeCases();
            if (score >= bestScore) {
                bestScore = score;
                bestThreshold = threshold;
            }
        }
        return bestThreshold;
    }

    private void summary(String split, String method, List<Result> rows,
                         Function<Result, List<String>> retrieved) throws Exception {
        var counts = count(rows, retrieved);
        var latencies = rows.stream().mapToLong(row -> method.equals("text") ? row.textUs() : row.vectorUs())
                .sorted().toArray();
        long median = (latencies[(latencies.length - 1) / 2] + latencies[latencies.length / 2]) / 2;
        System.out.println("RETRIEVAL_SUMMARY " + json.writeValueAsString(Map.of(
                "split", split, "method", method, "counts", counts, "medianSqlUs", median)));
    }

    private static Counts count(List<Result> rows, Function<Result, List<String>> retrieved) {
        int positive = 0;
        int hits = 0;
        int negative = 0;
        int empty = 0;
        for (var row : rows) {
            var ids = retrieved.apply(row);
            if (row.test().expected().isEmpty()) {
                negative++;
                if (ids.isEmpty()) {
                    empty++;
                }
            } else {
                positive++;
                if (ids.containsAll(row.test().expected())) {
                    hits++;
                }
            }
        }
        return new Counts(positive, hits, negative, empty);
    }

    private record Comparison(String version, List<Result> results) {}
    private record Fixture(String corpus, List<Case> cases) {}
    private record Case(String id, String split, String scenario, String group, String revision,
                        String question, List<String> expected) {}
    private record Passage(String id, String text) {}
    private record Candidate(String id, double similarity) {}
    private record Result(Case test, List<String> textIds, List<Candidate> vector, long textUs, long vectorUs) {}
    private record CaseReport(String id, String split, String scenario, String group, String revision,
                              List<String> expected, List<String> text, List<String> vector,
                              List<Candidate> vectorRaw, long textUs, long vectorUs) {}
    private record Counts(int positiveCases, int positiveHits, int negativeCases, int emptyNegative) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingResponse(float[][] embeddings) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModelTag(String name, String digest) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModelTags(List<ModelTag> models) {}
}
