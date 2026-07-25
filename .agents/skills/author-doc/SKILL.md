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

Interview the gaps one question per turn. Order by leverage: whatever the
template itself calls most important first (a PRD's Problem, a manifesto's
Vision), then whatever each answer unblocks. The finished document keeps
template order regardless of interview order.

For each question:

- **Open with the decision and its stakes.** Before any options, one or two
  plain sentences naming what the user is deciding and what hangs on the
  answer — what it unblocks, changes, or costs ("this settles whether I
  actually run the tool and report what happens, or the doc can only repeat
  what its manual claims"). The test: if you can't state in one
  line why this is the *user's* call rather than yours, it isn't an interview
  question — make the judgment call yourself and declare it at the pre-write
  gate. A question whose intent the user has to reverse-engineer reads as
  random, however well-phrased its options are.
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
- **1–2 recommendations**, one-sentence rationale each, default marked when
  one is clearly stronger. Ground them in the project's own corpus first (its
  specs, design docs, existing documents); research outward only when the
  corpus is silent.
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
- Use `AskUserQuestion` when the answer space is a small fixed set; prose
  otherwise.
- **Open questions are exhausted by interviewing, not deposited.** As long as
  an open question remains that the user could settle, the interview
  continues — an open-questions entry is reserved for what the user *also*
  cannot answer ("I don't know", evidence that doesn't exist yet, a future
  investigation); it records an asked question, never an unasked one.
  (A template whose open-questions section is an *output* of the work —
  discovery's, for instance — carries its own rule; the template wins.)

#### Voice

You are a colleague asking a question, not a process reporting status.

- Never narrate the machinery: no phase announcements, no "parsing the
  template", "mined draft", "quality bar", or per-section progress reports.
- One short question in plain words, with just enough context to answer it.
  If answering requires reading three paragraphs first, the question isn't
  ready to ask.
- **Plain words means approachable words.** No unexplained jargon, no
  compressed shorthand ("strict whole-graph form", "off-matrix pairing") —
  say what a thing does in everyday language. For a technical choice, show
  one concrete example of what happens under each option ("with option A, a
  typo in the config file stops the program at startup with an error; with
  option B, the program runs anyway on default values") rather than naming
  categories; a consequence the user can picture beats a taxonomy. If the
  user has to ask what a question means, the question was asked wrong.
- **A structured question is self-contained.** The user reads the question
  dialog on its own — chat prose before the tool call may never be seen, so
  context placed there is context lost. The question text itself must set
  the scene before asking: where the question comes from (which source
  says what, or is silent on what), why it surfaces now, and what the
  answer settles — then the ask. A question that assumes the reader
  followed your reading so far will land as "where is this coming from?".
- **Options are sentences, not specifications.** The same plain-words bar
  applies inside a structured choice. A label is a short plain phrase a
  reader parses at a glance ("Keep names and slugs separate"), never
  syntax fragments or shorthand ("slug? input; name never touches it"). A
  description carries exactly one idea: what the user's world looks like
  under that option, ideally as the concrete example above. Everything
  else — secondary trade-offs, which documents need amending, edge
  implications — waits for a follow-up question, or for the judgment calls
  reported with the finished file. If
  a description needs a second sentence, it's carrying material that
  belongs elsewhere.
- Quote the template only when pushing back, and only the one relevant line.

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
