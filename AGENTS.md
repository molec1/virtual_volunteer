# Agent instructions

## Required: read `ARCHITECTURE.md` and `CODE_EXPLORER.md` first

Before doing meaningful work on this codebase (features, refactors, debugging, migrations, or exports):

1. **Read [`ARCHITECTURE.md`](./ARCHITECTURE.md)** in the repository root. It defines:

   - What the app is for and the main entities (**Race**, **Participant**, **ParticipantEmbedding**, **FinishDetection**).
   - Start vs finish pipelines and **non-negotiable invariants** (detection vs hashing, participant row when embedding fails, one official finish time per participant).
   - The **30-second first-series** finish aggregation rule.
   - Where UI, data, domain, and export code live.

2. **Read [`CODE_EXPLORER.md`](./CODE_EXPLORER.md)** in the repository root. It maps main concepts (race, participant, identity registry, lookup, matching, etc.) to **concrete files** (persistence, repository, domain pipelines, UI, and key layouts).

Work should respect the architecture rules unless the user explicitly changes product requirements.

## Code size (Kotlin sources)

Prefer **implementation files under ~200 lines** when structure allows. After a feature, if a file grows past that, **extract clearly separable logic** into focused types or files (same package or a sensible subpackage) when the split is obvious and keeps behavior easy to follow.

If a file must stay large (tight coupling, or extraction would obscure invariants), **leave it** and add a **short comment** at the class or file end noting a future split opportunity—do not force artificial fragmentation.

## Required: keep `ARCHITECTURE.md` current

When you change behavior that affects:

- pipelines (start/finish processing, **multi-embedding match / append**),
- storage model (Room entities, migrations, **`participant_embeddings`**),
- finish aggregation or protocol/export semantics,
- offline testing or debug logging contracts,

then **update [`ARCHITECTURE.md`](./ARCHITECTURE.md)** in the same change set (or immediately after) so it stays accurate for developers and future agents.

Do not grow the file into a full API reference—only adjust **system-level** descriptions and invariants when they change.

## Required: keep `CODE_EXPLORER.md` current

When you add or move **main entities**, **screens**, or **significant file paths** that a developer would need to find quickly, **update [`CODE_EXPLORER.md`](./CODE_EXPLORER.md)** in the same change set (or immediately after).

Keep that file a **lightweight index**—not a second architecture doc. If a task does not change what belongs in the explorer, you do not need to edit it.
