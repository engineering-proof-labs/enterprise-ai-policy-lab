# Evaluation

The fictional corpus `routing-fixture-1` has eight sections across four revisions of two documents. The fixture labels each question with its profile, active supervisor revision, and the section IDs needed for an answer. An empty expected list marks cases that require abstention. The [labeled questions](../src/main/resources/evaluation/questions.json) were recorded before running either strategy.

## Retrieval

Both methods return at most three candidates after applying the same document-group and active-revision filter. The text method uses `plainto_tsquery` and `ts_rank`. The vector method computes exact cosine distance in pgvector 0.8.7 with Ollama's `all-minilm:v2` embedding model, ID `1b226e2802db`. It uses a 0.65 minimum similarity selected on calibration by maximizing positive coverage plus the rate of empty negative results. Ties favor the higher threshold. The holdout was excluded from that selection.

| Split | Method | Questions | Required evidence in top 3 | Empty negative results | Median SQL round trip |
| --- | --- | ---: | ---: | ---: | ---: |
| Calibration | Text | 10 | 5/7 | 3/3 | 2.30 ms |
| Calibration | Exact vector | 10 | 5/7 | 3/3 | 3.05 ms |
| Holdout | Text | 12 | 5/7 | 4/5 | 2.07 ms |
| Holdout | Exact vector | 12 | 5/7 | 5/5 | 2.12 ms |

A hit requires all labeled sections to be retrieved. The run checks every candidate against access and revision rules before scoring. The SQL medians describe one local pass on PostgreSQL 17.12, with the vectors already computed. Embedding 30 strings in one call took 1,384 ms. The small corpus and shared host make the timings unsuitable for sizing a production system.

Both methods missed the holdout paraphrases `h01` and `h03`. The correct passages ranked first by cosine similarity before thresholding, with scores of 0.356 and 0.447. Text returned three permitted passages for the ambiguous `h07` question, “Route tickets.” The vector threshold returned none there. The app keeps text retrieval because the two methods found the same required evidence in the holdout, and text keeps the live path independent of an embedding model. The response evaluation checks how the model handles the ambiguous case.

Run after building the demo image. The evaluator uses a separate database and rolls back its active-revision changes and temporary embedding table.

```sh
docker compose --profile evaluation up -d --wait eval-db ollama
docker compose exec -T ollama ollama pull all-minilm:v2
docker compose --profile evaluation run --rm --no-deps -T -e SPRING_DATASOURCE_URL=jdbc:postgresql://eval-db:5432/policy_eval app --spring.main.web-application-type=none --spring.profiles.active=retrieval-eval
```

## Response quality

Use the running demo stack and [warm up Qwen2.5](../README.md#run-the-demo) first. The script runs the 12 holdout questions twice, retrieves the cited sections with the caller's token, and prints the model ID, configuration, stage times, and usage when available. It restores the supervisor revision to `v2`.

```sh
pwsh -File scripts/evaluate-quality.ps1 -Repetitions 2
pwsh -File scripts/check-provider-outage.ps1
```

Review each answer against its question and quoted sections. A correct supported response answers the question and every substantive claim follows from the cited passages. A correct abstention is a result of `insufficient_evidence` on a case with no sufficient authorized passage. A positive case that abstains is an incorrect abstention. If it lacked a labeled section in the selected IDs, record a retrieval miss. If the labeled evidence was present, record a generation failure. Unsupported answers and invalid structured replies are also generation failures. Track HTTP or provider failures independently. The outage script temporarily stops the local provider, checks for a bounded `503` and content-free request logs, and restores the service.


### Observed on 22 November 2026

Qwen2.5 1.5B (`65ec06548149`) ran through Ollama 0.40.17 with text retrieval, temperature 0, seed 42, 4,096 context tokens, 256 output tokens and a 30-second provider deadline for this run. The warm run made 24 API requests across 12 distinct holdout questions and two repetitions. Twelve of the requests called the model.

| Manual outcome | Count |
| --- | ---: |
| Correct answer with all claims supported by its citations | 9 |
| Correct abstention | 8 |
| Incorrect abstention caused by missing retrieved evidence | 4 |
| Generation failure with an answer returned | 3 |
| Operational failure in the quality run | 0 |

Two generation failures were answers to the ambiguous `h07` prompt. The third, `h10` in repetition 2, named the correct Legacy Escalations queue but referred to the document ID as a second chunk ID in the answer text. The structured citation pointed to the right section, so only the source review caught the extra claim. The four retrieval failures were `h01` and `h03` twice each. The generated replies reported 2,750 total tokens across 12 calls. Search took 1–3 ms and generation took 1.971–7.038 s in this warmed local run. These counts describe repeated observations on a small fictional corpus.

In the separate outage run, stopping Ollama gave a `503 model_unavailable` in 2.12 s. The request log retained selected IDs and timings. The provider was restored after the check.
