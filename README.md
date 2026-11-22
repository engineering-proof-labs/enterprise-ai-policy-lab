# Enterprise AI Policy Lab

A Java lab for answering support policy questions from a fictional ticket routing guide. The first slice looks up sections of the active revision in PostgreSQL, sends them to Ollama through Spring AI, and validates the model's citations before returning them.

This slice runs locally with a shared fixture. JWT, group permissions, and revision publication are the next slice. Retrieval currently requires every normalized search term to match, so a paraphrase can miss a relevant section.

## Run the demo

Use Docker with Compose and PowerShell 7 for the demo script. The first run builds the app and downloads the Qwen2.5 1.5B model. Ollama and PostgreSQL are reachable from the app on the Compose network. The HTTP API listens on 127.0.0.1:18080. To use a different host port, set LAB_HTTP_PORT before starting Compose and pass the matching -BaseUrl to the demo script.

```sh
docker compose up -d --build
docker compose exec -T ollama ollama pull qwen2.5:1.5b
pwsh -File scripts/demo.ps1
```

The script prints the answer to “Where do I route billing tickets?”, opens its citation, and asks a question about travel reimbursement. To inspect the evidence IDs selected for model context, match the response's `requestId` to `docker compose logs app`. To stop the services and keep the data and model volumes, run `docker compose down`.

## HTTP contract

`POST /questions` accepts JSON such as `{"question":"Where do I route billing tickets?"}`. The question must contain text and fit within 500 characters.

| Result | HTTP | Body |
| --- | --- | --- |
| Answer | 200 | `status: answered`, `answer`, `requestId`, and document, revision, section, and URL for each citation |
| Insufficient evidence | 200 | `status: insufficient_evidence`, `requestId`, a null `answer`, and empty `citations` |
| Invalid input | 400 | Problem detail |
| Invalid model output or citation | 502 | Problem detail with `code: invalid_model_output` |
| Model or policy storage unavailable | 503 | Problem detail with `code: model_unavailable` or `code: policy_storage_unavailable` |

A valid question gets a server-generated `X-Request-Id` even when processing fails. Fetch the citation's `sourceUrl` with `GET /sources/{chunkId}` to read its text. An unknown or inactive chunk returns 404.

PostgreSQL ranks matching sections of the active revision and returns up to three. An empty result skips generation. The model returns the answer and cited IDs under a JSON schema. The API checks those IDs against the exact set of retrieved sections and builds the citation fields from PostgreSQL. Citation membership alone cannot establish whether the source supports the model's claim.

## Verify

Use Java 21 and a running Docker engine for the deterministic suite. Testcontainers starts a separate PostgreSQL, applies the Flyway migrations, and runs the API with a model substitute. The Compose demo above exercises the real model separately.

```sh
./mvnw verify
```

On Windows use `mvnw.cmd verify` or `./mvnw.cmd verify` in PowerShell. The app uses Spring Boot 3.5.15 and Spring AI 1.1.8. [Spring AI release notes](https://spring.io/blog/2026/06/12/spring-ai-1-1-8-1-0-9-avaialble-now/) · [Ollama structured output](https://docs.ollama.com/capabilities/structured-outputs)
