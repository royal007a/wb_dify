# Unknown submission identity and exit

Scope: the original Hify repository, not hify-cc. Follow-up to static review of
76cf03a..641fdcb. No deployment, shared services, credentials or schema changes.

## Contracts

- An existing conversation/key record is replayed before mutable provider/agent
  eligibility checks, but the body digest must still match. New submissions keep
  all current eligibility checks. This is not user authorization; future ACLs
  must guard both creation and lookup/replay.
- Add a read-only GET by conversation/key, with no-store and header variance. It
  must not create a Run, write messages/events or dispatch. Missing records mean
  not found *now*, not proof the original in-flight request was cancelled.
- Cancelling an unknown submission uses lookup, never another creation POST.
  A late original request remains possible: keep the identity for another lookup,
  or allow an explicit local abandonment. No cancellation tombstone/exactly-once
  promise is added. Local abandonment detaches the conversation and warns that
  server work may continue; it does not automatically resend into a new one.
- Preserve corrected draft text on rejection, otherwise restore original text.
  Repeated unknown-submission errors update one event instead of appending spam.
- Bound consecutive SSE open/error churn without fresh persisted events; merely
  receiving HTTP 200 must not grant unlimited automatic reads. Manual sync remains
  available; no user message resubmission.

## Validation

1. Before source changes, run real Spring/H2 HTTP tests: create commits, disable
   provider, same-key retry; different body remains conflict, new key stays refused.
   Verify row/message/event/submission counts and lookup's missing/cross-conversation
   behavior and cache headers. A missing endpoint red is expected only for lookup.
2. Controlled browser reds: permanent 4xx has explicit exit, lookup cancel makes
   no second POST even when missing, rejected input remains, open/error flapping
   stops. Preserve original lifecycle tests with corrected cancellation semantics.
3. Run expanded browser suite, runtime, frontend, inventory and Harness gates.
   Register the new explicit route: inventory grows from 67 to 68. Do not count
   an endpoint inventory pass as all behavior validated.
4. Commit backend, frontend, tests/contracts and evidence in reviewable groups;
   hand off fixed commits to mymacclaude for read-only review. Record all skipped
   PG/real network cases. Server cancellation uses the existing API and its tests;
   this slice does not claim a cancellation that won a late original request.
