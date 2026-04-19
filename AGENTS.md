# Agent instructions

## Required: read `ARCHITECTURE.md` first

Before doing meaningful work on this codebase (features, refactors, debugging, migrations, or exports), **read [`ARCHITECTURE.md`](./ARCHITECTURE.md)** in the repository root.

That document defines:

- What the app is for and the main entities (**Race**, **Participant**, **FinishDetection**).
- Start vs finish pipelines and **non-negotiable invariants** (detection vs hashing, participant row when embedding fails, one official finish time per participant).
- The **30-second first-series** finish aggregation rule.
- Where UI, data, domain, and export code live.

Work should respect those rules unless the user explicitly changes product requirements.

## Required: keep `ARCHITECTURE.md` current

When you change behavior that affects:

- pipelines (start/finish processing),
- storage model (Room entities, migrations),
- finish aggregation or protocol/export semantics,
- offline testing or debug logging contracts,

then **update [`ARCHITECTURE.md`](./ARCHITECTURE.md)** in the same change set (or immediately after) so it stays accurate for developers and future agents.

Do not grow the file into a full API reference—only adjust **system-level** descriptions and invariants when they change.
