# Virtual Volunteer

A practical way to run **park run–style** finish timing with fewer people and less friction: **one volunteer in the finish zone** can do work that, in a typical setup, is split across three roles—a **token distributor**, a **timekeeper**, and someone who **scans codes and links them to times**. This app is built to make that kind of volunteering easier, faster, and less dependent on perfect manual scanning and clock coordination.

## What the app is trying to do

- **Reduce the volunteer headcount** at the line where it hurts most. You still need a finish presence; the goal is not “zero people,” but **one well-equipped volunteer** instead of a small handoff chain.
- **Use face recognition** so that linking people to their finish moment does not rely on scanning a code at exactly the right second for every runner. Barcodes and names remain useful; they are **supplements**, not the only way to get a time.
- **Treat photos as first-class**: they are both the **source of truth for matching and timing** and a **memory** of the day. Finishing photos and start-line crowds are part of the record, not an afterthought.
- **Work offline, on a device you actually carry to events.** Data is stored **locally**. If the same device is used across several races, the **face vectors and linked codes accumulate**—so recognizing people and attaching codes can get **easier and faster over time**, not harder.
- **Put history in one place.** The participants flow is there to help **move between races and see a person’s history** on that device, without juggling spreadsheets.
- **Deliver results when people care about them.** Exports and reports are available **right after the finish**, and even **while the event is still going**—so you are not waiting until Monday for a “modern” solution to an old, slow problem.

In short: **photos + local intelligence + a single finish workflow** to replace a heavier, more error-prone volunteer pattern—without pretending field sport is a datacenter.

## For developers and contributors

- **[`ARCHITECTURE.md`](./ARCHITECTURE.md)** — System behavior, entities, start/finish pipelines, and non‑negotiable product rules.
- **[`CODE_EXPLORER.md`](./CODE_EXPLORER.md)** — Map of main concepts to files (UI, data, domain).
- **[`AGENTS.md`](./AGENTS.md)** — Guidance for anyone (including AI assistants) working in this repository.

## Tech snapshot

Android app, **offline-first**, with photo capture, face detection and embedding–based matching, local database storage, and exports (e.g. ZIP, CSV, protocol-style XML). See `ARCHITECTURE.md` for the full picture.

---

*This README describes intent and product story; the architecture doc remains the source of truth for technical behavior and invariants.*
