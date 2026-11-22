# The Same Question, Different Evidence

A support analyst and a supervisor ask where to route duplicate charge billing tickets. The ordinary guide covers standard billing, and the restricted procedure covers the exception. The archived procedure names a queue that was valid in an earlier revision.

| Caller and revision | Observed result | Evidence |
| --- | --- | --- |
| Analyst, `v2` active | `insufficient_evidence` | No matching authorized section |
| Supervisor, `v2` active | Revenue Assurance | `supervisor-routing-v2-duplicate-charge` |
| Supervisor, after publishing `v3` | Payments Review | `supervisor-routing-v3-duplicate-charge` |

Run the [demo script](../README.md#run-the-demo) to ask the same question three times. Open each source URL using the supervisor token while that revision is current and match the `requestId` to the evidence IDs in the application log. The script checks that the analyst cannot open the restricted source and that the old source returns `404` after publication.

## What the tests establish

PostgreSQL applies document permission and active revision before the top-three limit. Tests make prohibited documents outrank an authorized section and verify that only allowed sections reach generation. The active-revision pointer is a foreign key scoped to the document. A concurrency test queues two publications on the same row and verifies that the final active revision is the only one reachable. In separate tests, revocation or publication happens while the model is waiting. The API returns `409 evidence_changed` even when the changed chunk was absent from the draft's citation list.

The API establishes citation membership and current access on a corpus of eight sections. In the real-model evaluation, the broad question “Route tickets” returned a list of requirements for three different procedures. The question did not identify a ticket type, and the answer omitted the invoice availability condition. Both repetitions should have abstained. The result demonstrates why the evaluation checks question intent and claim support after validating source IDs.

During demo development, the first schema allowed free-form citation IDs and the supervisor request produced a rejected draft. The returned schema now enumerates the IDs selected by SQL for that request, and the demo passed for both revisions after that change. The ambiguous evaluation case remains open and is included in the reported denominator. [Evaluation results](evaluation.md)
