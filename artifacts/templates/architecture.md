<!--
Architecture overview — the project's **orientation artifact**, and a per-project
singleton: one living map, fixed-named `/architecture.md`, unnumbered, never
attached to an item. Answers: *how does the system hang together today?* Its
reader is whoever arrives without context — almost always an agent, before its
first task — and its test is that they can orient without spelunking: find the
right component, know the rules they must not break, and see how the pieces meet.
Three disciplines rule it. It describes **now**: present state only, updated in
place when reality changes (git keeps the history; the ADR stream keeps the why —
cite ADR#s, never duplicate them). Its sections are **roles, not web-dev
furniture**: "core structures" means database entities in a service, IRs in a
compiler, the scene graph in a game engine, tensors and checkpoints in an ML
pipeline — read every section's label through your project's domain, and drop
what genuinely has no referent rather than forcing a web-app shape onto a system
that isn't one. It is a **map, not a pitch**: no roadmap, no aspirations, no
selling — what should exist someday belongs in frame-stage docs; what exists
belongs here. This document never tracks state in prose: the project tracker offers structure
constructs (releases, statuses, dependency edges) for projects that want
timeline/status tracking, and git already keeps document history.
-->

# {Title} — Architecture

## Purpose

<!--
What this system is and who it's for, in one short paragraph: the job it does,
for whom, and the one or two properties it is built to have (the design's center
of gravity — "correctness over speed", "single-writer simplicity"). A newcomer
reads only this and can correctly say what they're inside of. No history, no
pitch — the manifesto argues why this should exist; this section states what does.
-->

## Context

<!--
The boundary: what is inside this system and what it touches on the outside —
the actors and systems it serves, consumes, or integrates with, and what crosses
the boundary in each direction. For a service: callers, downstream dependencies,
event streams. For a compiler: source languages in, targets out, the toolchain
around it. For a library: the applications that embed it and the platform APIs it
sits on. A context diagram earns its place here when the boundary has more than a
few edges. Everything named here is *outside* — if it's yours, it's a component
below.
-->

## Components

<!--
The parts, each with its single responsibility and its home in the code. This is
the section agents orient by: the component→path map is what turns "fix the
tokenizer bug" into opening the right directory. One entry per major part — the
altitude where each has one nameable job; finer structure belongs to the code
itself. State the load-bearing relationships ("nothing writes to the store except
the core service") — a component diagram helps once entries interconnect. Keep
responsibilities honest: what the component does today, not what it's becoming.
Format: `- **<component>** — <its one responsibility>; lives in: <path>.`
-->

## Core structures

<!--
Optional — drop when the system has no shared representations worth naming. The
structures the components exchange and hold — the nouns of the system — each with
what it represents and which component owns it. Domain decides what these are:
entities and their stores in a service; the representations of a compiler
pipeline (tokens → AST → IR) and the invariants each stage guarantees; the scene
graph and asset formats of an engine; datasets, tensors, and checkpoints in an ML
system. Describe meaning and ownership, not storage detail — full schemas and
type definitions live in the code they bind.
Format: `- **<structure>** — <what it represents>; owned by: <component>;
flows: <from → to, where it moves between components>.`
-->

## Key flows

<!--
The two to four end-to-end paths that carry the system's real work, each walked
through the components above: what enters, which component does what to it, what
comes out. A request's path through a service; a source file's path through the
compiler; a frame's path through the engine. These are the routes a reader
traces when something misbehaves — name what happens at the failure points on
the way, and stop at two-to-four: a map of every path is a map of none.
Format: one `### <flow name>` per path — numbered steps, each naming the
component acting and what it hands to the next.
-->

## Invariants

<!--
The rules that hold everywhere — what any change, by anyone or any agent, must
not break: the concurrency model ("single writer; nothing else touches the DB"),
ownership boundaries ("content lives in git; the DB holds only pointers"),
representation guarantees ("every pass preserves SSA form"), compatibility
promises ("the CLI's stdout format is public API"). Each invariant is one
checkable sentence — a reviewer can ask "does this change violate it?" and get a
yes or no — with the ADR that set it cited, or "convention" when it predates the
stream. This section is the overview's contract with every future change; it
earns more care per line than anything else here.
Format: `- **<short-name>** — <the rule>; set by: <ADR-#, or "convention">.`
Name invariants so reviews can cite them ("violates **single-writer**").
-->

## Known weaknesses

<!--
Optional — drop only if genuinely none; rarely true of a real system. The honest
swamp map: the debt, the fragile module, the component that works but shouldn't
be extended, the scaling cliff that hasn't been hit yet. For each: the weakness
and the standing advice ("don't add features here — strangle it via the new
importer instead"). This section is what stops a well-meaning agent from building
on the part everyone knows is condemned — the tribal "oh, don't touch that"
written down. Pair each with the item tracking its fix when one exists; the item
is where any plan to fix it lives.
Format: `- **<area>** — <the weakness>; advice: <what to do and not do about it>.`
-->
