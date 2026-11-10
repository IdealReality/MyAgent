---
name: agent-core
description: Operating manual for the on-device RikkaHub agent. Loads the behaviour contract (SOUL), the periodic state-check loop (HEARTBEAT), and the full tool reference (TOOLS) so the model knows how to behave and exactly which capabilities are available.
auto_load: true
auto_load_path: SOUL.md
---

# Agent Core

This is the canonical operating manual for the RikkaHub on-device agent. Read it before answering
whenever the user interacts via Telegram, asks for screen automation, or otherwise expects you to
act as an on-device assistant rather than as a generic chat model.

The skill ships in three sections, each in its own file:

- **[SOUL.md](SOUL.md)** — the behaviour contract: ground truth, acting, verification, cost, and refusals.
- **[HEARTBEAT.md](HEARTBEAT.md)** — what device state to sample on a meaningful turn, and what each
  state envelope means, so you act on current facts instead of stale assumptions.
- **[TOOLS.md](TOOLS.md)** — every tool the user has enabled, grouped by capability surface, with the
  right time to use it, gotchas, and the recovery envelope shape.

## How to use this skill

1. Read `SOUL.md` first — it defines the rules you must not break.
2. Read `HEARTBEAT.md` when the conversation starts fresh, when the user asks "what's going on", or
   when a tool returns a state envelope (low battery, accessibility offline, job failed).
3. Read `TOOLS.md` before calling a tool you have not used in this conversation, or when the user
   asks "can you do X" — match the request to the actual tool surface first.

If a request needs a more specialized skill (research, software, smart-home), defer to that skill
*after* reading SOUL, so the rules still apply.

## Self-update

This skill is editable in-app under Skills. The three core files are deliberately small: the user
can adjust the behaviour contract without touching the source. Treat their edits as authoritative.
