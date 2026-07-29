---
name: author-doc
description: Interview-driven document authoring from a template skeleton — takes a Nook artifact template (a kind name like `prd`, or a path to any template file) as argument, interviews the user section by section using the template's own embedded guidance as the quality bar, then writes the finished document. Use when the user wants to write a PRD/manifesto/spec/RFC/design doc/etc. from a template, says "author the <kind> for X", "interview me for the <kind>", or invokes `/author-doc <template> [target-path]`.
---

# Author Doc

Write one document by interviewing the user through a template, one question
at a time. The template is the script: every template following the Nook convention
carries an HTML guidance comment per section stating what the section must
contain, the quality bar it must clear, and a `Format:` line. Those comments —
not this skill — are the authority on what "good" means for each section. Your
job is to extract answers that clear that bar, then assemble the document.

## Arguments

`/author-doc <template> [target-path]`

- **template** — a path to a template file, or a bare kind name (`prd`,
  `manifesto`, `spec`, …). Resolve a bare name by searching for `<kind>.md`
  among the template skeletons distributed with this skill; if none is found,
  ask the user where their templates live and remember the answer for the rest
  of the session.
- **target-path** (optional) — where the finished document goes. Ask in
  Phase 2 if omitted.

## Prerequisite

Before anything else, read `artifacts/tenets.md` (the project's base tenet
set). The tenets govern every question you ask and every sentence you write.
If the project layers its own tenets on top of the base set, read those too.

## Workflow

### Phase 1 — Parse the template

Read the template. Extract: the title placeholder (and whether the kind is
numbered — `{seq}` in the heading), the ordered section list, each section's
guidance comment, its `Format:` line if any, and which sections are marked
optional ("Optional — drop if/for …"). Also note the template's preamble
comment — it states the document's role and its anti-goals (what belongs in
*other* kinds); enforce those boundaries during the interview.

### Phase 2 — Establish subject and sources

Confirm what the document is about, where it will live, and — critically —
**what source material already exists** (a draft being converted, specs,
architecture notes, prior documents). If the conversation already answers
these, state your understanding in one line and move on; only ask for what's
genuinely missing. Read everything named before asking a single content
question.

**An anchored document inherits its scope from its anchor.** When the target
attaches the document to a work item (a path inside an epic/task folder, a
named item), the anchor chain — the item's own description, the requirement
it traces to, its parent milestone/epic documents — *is* source material:
read it unprompted and treat it as authoritative. What the document is about,
and what it must cover, are fixed by that chain; never interview the user to
restate them (e.g. a discovery's Questions derive from the anchor item's
stated requirement, goals, and risks — draft them, don't ask).

### Phase 3 — Triage the sections, interview only the gaps

Silently sort every section into three buckets:

- **Derivative** — overviews, summaries, anything whose content restates other
  sections. Never interview material; write these last, from the finished
  sections.
- **Answered by the sources** — draft directly, no question. The user reviews
  them with the whole draft in Phase 4, not one at a time.
- **Gaps, conflicts, judgment calls** — this is the interview. If the sources
  answer everything, say so and go straight to the draft. Scope an anchor
  chain already fixes (Phase 2) is never a gap — deriving it is drafting, not
  asking.

Interview the gaps one question per turn, numbered in one running series across
the whole interview. Order by leverage: whatever the template itself calls most
important first (a PRD's Problem, a manifesto's Vision), then whatever each
answer unblocks. The finished document keeps template order regardless of
interview order.

For each question:

- **Open with the decision, in one sentence.** Name what the user is deciding
  and what turns on it — no more ("This settles whether I run the tool and
  report what happened, or the doc can only repeat what its manual claims").
  Not the reading that got you here, not why the decision is theirs rather
  than yours: that test is for your own triage — if you can't state in one
  line why this is the *user's* call, don't ask it, decide it and declare it
  at the pre-write gate. A question whose intent the user has to
  reverse-engineer reads as random, however well-phrased its options are —
  and so does one buried under three sentences of setup.
- **Never ask an open question.** Every question arrives as a brief: what is
  being decided, what the evidence says, two to four concrete options, and a
  recommendation — never a blank prompt that hands the framing back ("how should
  refusals work?", "what should the goals be?", "anything I'm missing?"). The
  finding-out is yours; only the decision is theirs. Think of briefing a chief
  executive on a call that turns on the market: you bring the market, they bring
  the judgment — you do not send them off to survey it. So a question the user
  cannot answer without first going and researching it is a question you have not
  finished preparing. If you cannot name two candidates, you have not read enough
  to ask yet — go back to the sources. Where the answer space is genuinely
  continuous — a number, a name, a sentence — bound it anyway: offer the two or
  three values the prior art points at, and let the user overwrite one.
- **Your options bound your homework, not their answer.** The user may answer
  with something no option named — a constraint you did not know about, an
  approach you missed, a flat rejection of the premise. That is their call to
  make and it is not a failure to follow instructions; treat it as new evidence
  about the problem, and never as a reason to hand the question back. What it
  changes is only where the next round of work falls: still on you. Go find out
  what their answer costs and what else it moves, then come back with that —
  which is the bullet below.
- **Options come from prior art, and the question names it.** Ground every
  option in something already settled somewhere. Look first inside the project:
  what it decided in a comparable place, how a sibling document handled the same
  section, what an earlier epic did and what that cost. When the project is
  silent, look to projects of the same kind — the library being used, a
  comparable tool, the convention its ecosystem already follows. Then put that
  source *in the question*, with what it established, so the user decides
  against evidence rather than against your taste: "epic 05 stood up a scratch
  build and deleted it afterwards" or "the protocol's own reference client sends
  the older shape unless told otherwise". A question with no prior art behind it
  was asked too early.
- **A changed plan is announced, not implied.** When the user's answer rules
  out every option as presented — they lack the tool, reject the premise, or
  name a constraint no option accounted for — whatever you substitute is a
  *new decision*, not a detail of the old one: before acting on it, state
  plainly — as its own visible statement,
  not a clause in passing — what you will now do instead, why it still honors
  their answer, and what it involves that they might not expect (downloads,
  processes run, services touched). The user must never discover after the
  fact which path was actually taken; if the substitute is more invasive than
  anything the original options described, it goes back to them as a question,
  not forward as a judgment call.
- **A settled answer stays settled** — never re-ask it. Research the user
  asked for that confirms their direction gets reported and its consequences
  declared; the question reopens only if the findings contradict it.
- **Recommend one option by letter**, with a one-sentence rationale. Ground it
  in the project's own corpus first (its specs, design docs, existing
  documents); research outward only when the corpus is silent. Where two are
  genuinely equal, say so rather than inventing a preference.
- **The guidance comment is the acceptance test.** When an answer fails the
  template's own stated test (an adjective where it demands a number, a
  solution-shaped "problem", a requirement tracing to no goal), push back once
  in one line — then yield if the user overrules.
- **A draft that misses its comment's test is a gap, not a footnote.** This
  applies to sections drafted from sources too: if what you drafted can't
  clear the section's stated bar without input (a goal with no target number,
  a problem with no evidence), that's an interview question — propose the
  concrete fix and ask. Never write a known shortfall planning to disclose it
  later.
- **Optional sections get an explicit keep/drop decision** with a
  recommendation. Never silently omit; never pad a section that should drop.
- **Respect kind boundaries.** Content the preamble assigns to another kind
  (spec-altitude detail in a PRD, architecture in a manifesto) gets flagged
  and parked — offer to note it as input for that other document, don't
  absorb it.
- **Open questions are exhausted by interviewing, not deposited.** As long as
  an open question remains that the user could settle, the interview
  continues — an open-questions entry is reserved for what the user *also*
  cannot answer ("I don't know", evidence that doesn't exist yet, a future
  investigation); it records an asked question, never an unasked one.
  (A template whose open-questions section is an *output* of the work —
  discovery's, for instance — carries its own rule; the template wins.)

#### How a question is laid out

Write every question into the reply as text. Do **not** use `AskUserQuestion`
for it: its dialog cannot hold a context paragraph, lettered options and a
recommendation at once, and the user answers by typing rather than by picking
from a list.

Head each question with its number in the running series and a title that names
the decision, so the heading alone says what is being settled. **The title obeys
the plain-words tenet like everything else** — an everyday phrase understood
cold, never a stack of hyphenated coinages or compressed shorthand the reader
has to unpack ("Which tests run again against the real database", never "Which
stand-in-proved checks the assembled run repeats for real"). Under it, in this
order:

1. **Context** — one paragraph of one or two sentences: the fact the answer
   turns on and where it comes from (the sibling spec, the earlier epic, the
   published standard). Not the chain of reading that got you there.
2. **The question** — on its own line, one sentence, stating the choice.
3. **Options** — lettered **A**, **B**, **C** (two to four), each a short label
   and one sentence of what the user's world looks like under it. Each names the
   prior art it comes from where it has one.
4. **A recommendation** — which letter, and one sentence of why. Where the
   options are genuinely equal, say that instead of manufacturing a preference.
5. **An open door** — one line inviting an answer none of the letters names.

The shape, on a real decision:

> **Question 3 — Where the assembled system's programs are started from**
>
> `ToolProgramTest` already launches the MCP server as a real operating-system
> process with `ProcessBuilder`, against a stand-in core; nothing yet starts all
> three programs at once.
>
> **The question:** do the core, the MCP server and the web app run here as
> separate processes, or as servers built inside one test process?
>
> - **A — Three real processes.** Each started from its own `main` with its
>   `NOOK_*` settings in the environment, against embedded Postgres.
> - **B — One test process.** `CatalogServer`, `ToolServer` and `WebApi` built in
>   the test JVM on real loopback ports; the three `main` functions never run.
> - **C — Core as a process, adapters in-process.** The store and the connection
>   are real; the adapters' own startup is not.
>
> **Recommendation:** A — it is the only shape in which the settings each program
> reads at startup are exercised at all.
>
> Or answer in your own words, if a constraint here accounts for none of these.

#### Voice

You are a colleague asking a question, not a process reporting status.

- Never narrate the machinery: no phase announcements, no "parsing the
  template", "mined draft", "quality bar", or per-section progress reports.
- One short question in plain words, with just enough context to answer it.
  If answering requires reading three paragraphs first, the question isn't
  ready to ask.
- **The length budget is real.** The context paragraph is one or two sentences
  and the question itself is one; an option's label is at most six words and its
  description one sentence. Run longer and you are explaining your reasoning
  rather than asking — cut the explanation, never the facts.
- **Name the thing, then gloss it — never paraphrase it away.** Precision is
  not jargon. Write "the loopback address (`127.0.0.1`)", not "the address a
  machine uses to reach itself"; "the fault reply's `message` field", not "the
  words the reply carries". The real name is shorter, tells the reader what to
  search for, and is what the code and the project's own documents call it.
  What the plain-words tenet bans is an *unexplained* term and compressed
  shorthand ("strict whole-graph form", "REQ17/EDGE3") — so the fix is a
  three-word gloss at first use, not a circumlocution, and a term the
  project's own documents already use needs no gloss at all. Technical
  detail is what makes a question answerable; strip it and you have asked
  about nothing.
- **Show the consequence, not the category.** For a technical choice, one
  concrete line of what happens beats a paragraph of characterization: "a typo
  in the config stops the program at startup" against "the program starts on
  defaults nobody chose". A code fragment, a wire payload, or a filename does
  this better than any sentence — reach for it first, in a fenced block under
  the option it belongs to when it needs more than a line.
- **Self-contained means it stands without the chat, not that it recaps your
  reading.** The question must carry the one fact the answer turns on. It must
  not carry the
  chain of documents you read to find that fact: name a source in a clause
  ("spec-5 requires…", "epic 05 did this, and it cost…"), never in a sentence
  of its own.
- **Cut what the user already knows.** They asked for this document; they know
  what it is and why they are being asked for input. Whatever the previous
  question or their own last answer established is context to lean on, not
  context to restate.
- **Options are sentences, not specifications.** A label is a short plain
  phrase parsed at a glance ("Keep names and handles separate"), never a
  syntax fragment ("slug? input; name never touches it"). A description
  carries exactly one idea: what the user's world looks like under that
  option. Secondary trade-offs, which documents need amending, edge
  implications — those wait for a follow-up question or for the judgment
  calls reported with the finished file.
- Quote the template only when pushing back, and only the one relevant line.

Calibration, on the context paragraph of a real question:

> **Too long** — "The discovery for epic 07 left exactly one thing for you to
> settle before a plan can be written, and it is a change to something already
> shipped. Today a call that never reached the core and a call the core
> answered by breaking both come back as a fault carrying a message and
> nothing else — the reply has no place to say which of the two happened.
> Spec-5 requires a caller to tell them apart, because one is worth trying
> again and the other is not. …" (four more sentences)
>
> **Right** — "A fault reply carries only `message`, so a caller can't tell
> 'the core was unreachable' from 'the core answered and broke' — and spec-5
> needs that distinction, since only the first is worth retrying."

Same facts, a quarter of the words — and the short one names the field, which
the long one spent two sentences avoiding. The choice itself then goes on its
own line, and the two candidates become options A and B.

When the questions run out, the interview is over and the document gets
written — in that same turn, without asking permission to write it. Never dump
the draft for confirmation: nobody reads a document inside a chat turn, so a
"look right?" over the whole text is not an actionable question, and neither is
"shall I write it now?" — the user asked for the document, and the answer to
that ask is the file. Report the **delta between the interview and the draft**
alongside the written file: in one line each, the judgment calls you made that
no answer explicitly settled (a framing chosen, a number derived, content cut).
If there are none, say so. The document itself gets reviewed where documents
are read — as the written file, which the user can change or discard.

### Phase 4 — Settle identity, then write

Settle, and state alongside the finished file rather than asking about first:
title, sequence number if the kind is numbered (scan the destination's siblings
for existing `<KIND>-<n>` handles; take the next free), and final path. A
target path passed as an argument is an instruction about
*where*, not a settled decision about *naming*: if its filename obscures the
artifact's identity (a generic name like `README.md` or `notes.md` carrying a
numbered kind), write it under a name that says what the document is and say so.
Follow the destination's sibling convention for the filename. Where an item's
folder holds its documents kind-named (a milestone folder's `prd.md`, an epic
folder's `discovery.md`), name the file after the **kind** — the folder
already carries the subject, and a subject-named file among kind-named
siblings hides what the artifact is. Never mix the two (no
`discovery-build-approach.md`).
Then write the document:

- Follow the template exactly: same heading order, `{Title}`/`{seq}` filled,
  **all guidance comments stripped**, each section obeying its `Format:` line.
- **The naming rule from Voice governs the prose too, not just the questions.**
  A thing the world already names keeps that name in the written document —
  timeout, loopback address, bad request, client, idempotent — glossed once
  where the audience needs it. Never coin a plain-sounding synonym: it reads as
  everyday language, so it passes a plain-words review while hiding that a
  standard concept was meant, and it travels from the document into field names
  and class names, where it becomes a contract. Two checks before the file is
  written: every entry in a definitions section defines a noun of *this*
  domain — one teaching a word the wider world already has a name for means the
  document should be using that name instead; and every mechanism the document
  specifies names the standard it adopts, or the one it rejected and why.
- Content is only what the interview agreed. Questions the interview asked
  and the user could not settle go in the template's open-questions section
  verbatim (or a trailing one if the template lacks it) — never guessed on
  the user's behalf, and never parked there unasked.
- No state tracking in prose (statuses, progress, dates-as-schedule) — the
  templates state this rule themselves; hold to it even when one doesn't.

### Phase 5 — Self-check

Re-read the written doc against each section's guidance comment and the
preamble. A shortfall found here means Phase 3 missed a question — so treat it
that way: propose the concrete fix (or ask the one question that unblocks it)
and apply it; never just disclose it. Then report, briefly: path written,
sections filled vs dropped, fixes applied, and where to focus a human read of
the file. Point the user at the file — that is the review surface, not the
chat.

## Hard rules

- **One question per turn.** Two candidates → ask the one that shapes the next.
- **The template's comments outrank this skill and your habits.** When they
  conflict with your instincts about the kind, the comment wins.
- **Never write a section from silence** — confirmed source material or an
  answer, nothing else. "I don't know" becomes an open question.
- **Recommend, then yield.** The user is the design authority.
- **The finished doc contains no template scaffolding** — no guidance
  comments, no unfilled placeholders, no empty sections.

## When NOT to use this skill

- Editing an existing finished document — read it and propose targeted edits.
- The user wants the doc drafted without interrogation — offer to write it
  directly from sources and confirm.
- No template exists for the shape they want — offer plain drafting, or ask
  whether the shape is really a house-style variant of a template they already
  have (a kind is a workflow role, not a topic — a threat model is a design
  doc about security).
