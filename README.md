# Enterprise AI Policy Lab

A Java and Spring AI lab for answering questions about fictional support policies. A support analyst and a supervisor can ask the same question and receive different evidence. PostgreSQL filters by document permission and active revision before the app sends passages to the model.

## Run the demo

Use Docker with Compose and PowerShell 7. The API listens on `127.0.0.1:18080` and Keycloak on `127.0.0.1:18081`. The first run builds the app, imports a demo realm, and downloads the Qwen2.5 1.5B model. The warm-up command loads the model before the timed API request. Compose gives the model one attempt with a 75-second deadline for CPU startup and inference. Set `LAB_OLLAMA_TIMEOUT` before starting Compose to adjust that deadline.

```sh
docker compose up -d --build
docker compose exec -T ollama ollama pull qwen2.5:1.5b
docker compose exec -T ollama ollama run qwen2.5:1.5b "Reply with the word ready."
pwsh -File scripts/demo.ps1
```

The script authenticates both fixture users and asks the same duplicate-charge routing question. It checks that the analyst gets `insufficient_evidence` and cannot open the supervisor's source. It checks that the supervisor gets Revenue Assurance in `v2` and Payments Review after publishing `v3`. It opens both citations while they are active, verifies that the old source returns `404`, and restores `v2`. The script prints the authenticated source URL and the model's evidence IDs for each supervisor request.

The Keycloak realm contains the demo users `analyst` and `supervisor`, with passwords `analyst-demo` and `supervisor-demo`. The realm uses a direct password grant for this loopback demonstration. Set `LAB_HTTP_PORT` or `LAB_IDP_PORT` before starting Compose to change host ports and pass the matching `-BaseUrl` or `-IdentityUrl` to the script. Run `docker compose --profile evaluation down` to stop the lab while retaining the named data and model volumes.

## HTTP contract

Send a signed access token in the `Authorization: Bearer` header. `POST /questions` accepts a single question of up to 500 characters, for example `{"question":"Route duplicate charge billing tickets"}`.

| Route or result | HTTP | Behavior |
| --- | --- | --- |
| `POST /questions`, answered | 200 | `answered`, answer text, request ID, and citations with document, revision, section, and source URL |
| `POST /questions`, insufficient evidence | 200 | `insufficient_evidence`, request ID, null answer, and no citations |
| `GET /sources/{chunkId}` | 200 or 404 | Reads the current authorized source. A missing, inactive, or inaccessible ID gets the same empty 404. |
| `PUT /policies/{documentId}/active-revision` | 204 or 404 | A member of `policy-publisher` can select a revision using `{"revisionId":"v3"}`. A missing document or revision returns 404. |
| Absent or invalid token | 401 | JWT authentication failed. |
| Insufficient publication permission | 403 | A valid token lacks the publisher group. |
| Invalid input | 400 | Problem detail |
| Evidence changed during generation | 409 | `evidence_changed`. The client can retry the question. |
| Invalid model output or citation | 502 | `invalid_model_output` |
| Model or policy storage unavailable | 503 | `model_unavailable` or `policy_storage_unavailable` |

The resource server verifies signature, issuer, expiration and audience before reading groups. Document permissions are checked in SQL on each request. Identity group changes apply after token renewal or expiration. Search ranks up to three active, permitted sections and releases the database connection before generation. The app requires a valid citation for an answer and revalidates every selected section after generation. Revocation can invalidate the response once observed. A later revocation cannot undo text already sent to the provider.

`X-Request-Id` connects a question to stage durations, evidence IDs, and token counts when the provider supplies them. The app logs those fields without question text, answer text, source bodies, or bearer tokens.

## Verify and reproduce

Use Java 21 and a running Docker engine for the deterministic suite. Testcontainers starts PostgreSQL, applies the Flyway migrations, and runs the API with real signed test tokens and a controlled answer generator. Separate tests run the Ollama adapter against a local HTTP fixture to verify JSON conversion and deadlines.

```sh
./mvnw --batch-mode --no-transfer-progress verify
```

Use `mvnw.cmd verify` on Windows. See [The Same Question, Different Evidence](docs/case.md) for the demonstration and [Evaluation](docs/evaluation.md) for the retrieval comparison, quality rubric, and reproduction commands.
