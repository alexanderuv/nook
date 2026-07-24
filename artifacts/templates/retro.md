<!--
Retrospective — learn-stage artifact, typically on an epic or a bug. Answers:
*what did doing it teach us?* — written after the work (or the incident), when
what actually happened can be compared with what was expected. A postmortem is
this kind attached to a bug; incident reports are house variants. The cardinal
rule is **blameless**: name systems, decisions, and processes — never people.
Assume everyone acted in good faith on the information they had; the question is
always why the system made the mistake easy, not who made it. A retro that blames
gets quiet, incomplete retros forever after. The second rule: dig past symptoms —
"the deploy broke" is an observation; "nothing verified the config schema before
rollout" is a cause someone can fix. Retros are how lessons compound: future
readers — often agents, before starting similar work — will consult this instead
of relearning the expensive way, so lessons must be stated as checkable guidance,
not sentiment ("spike unfamiliar APIs before committing the approach", never "be
more careful"). Follow-up actions are *named* here but *live* as project items —
that is where owners and completion are tracked; a lesson every future effort
should obey is tenet material. This document never tracks state in prose: the project
tracker offers structure constructs (releases, statuses, dependency edges) for projects that
want timeline/status tracking, and git already keeps document history.
-->

# {Title} — Retro-{seq}

## Summary

<!--
The retro in three to five sentences: what the work or incident was, the headline
of how it actually went against expectations, and the one or two lessons that
matter most. Most future readers stop here — write it so the biggest lesson
travels even if nothing else is read. For an incident: what broke, the impact in
numbers (duration, users or data affected), and the cause in a phrase.
-->

## What happened

<!--
The factual account, agreed before any judgment: what was set out to be done and
what actually unfolded — scope that moved, estimates against reality, the
surprises and the turning points. For an incident, this is a timeline (`- <time>
— <event>` entries: detection, escalation, decisions, resolution) plus the
impact stated in numbers. Facts a participant would recognize, cited where
possible (the framing docs as the record of what was expected, PRs, monitoring) —
interpretation waits for the sections below, because an account colored by
conclusions can't be trusted by the reader who wasn't there.
-->

## What worked

<!--
What went right, explicitly — the practices worth repeating and the decisions
that paid off, each with why it helped, so the behavior gets reinforced rather
than accidentally dropped next time. Real entries only: praise without a
repeatable practice behind it teaches nothing. For incidents, response wins count
(fast detection, a runbook that worked).
Format: `- **WIN1** — <what worked>; keep: <the practice to repeat>.`
-->

## What didn't

<!--
What went wrong or cost more than it should have — each dug to a systemic cause,
blameless. Go past the first answer (five-whys is a useful discipline): the cause
is a process gap, a missing check, a wrong assumption, a tool limit — something
fixable — never a person's failing. Near-misses belong here too: where we got
lucky is a risk revealed without being paid for, and it's cheapest to record now.
Two to five entries dug deep beat a laundry list of symptoms.
Format: `- **MISS1** — <what went wrong, or the near-miss>; cause: <the systemic
cause, dug past the symptom>.`
-->

## Lessons

<!--
The durable generalizations — the payload the rest of the document exists to
justify. Each lesson: a statement of guidance a future effort can actually follow,
checkable rather than sentimental, traced to the WIN/MISS evidence behind it. Ask
of each: would this have changed a decision, and will a stranger (or an agent)
starting similar work know what to do differently after reading it? A lesson that
every future effort should obey is a candidate for the project's tenets — say so,
and propose it there; this document records where it was learned.
Format: `- **LESSON1** (WIN/MISS #s) — <the checkable guidance>.`
-->

## Follow-ups

<!--
The actions this retro demands — named here, tracked elsewhere: every follow-up
worth doing becomes a project item (that is where owners, status, and completion
live), and this section records what was spawned and why, plus the honest "not
filed" entries with their reasoning, so a future reader can tell declined from
forgotten. Distinguish the fix for this specific gap from the fix for the class
of failure (mitigative vs. preventative) — the class fix is usually the one worth
the follow-up. An unactioned MISS is a decision to accept the risk; make that
decision on the page, not by silence.
Format: `- **ACT1** (LESSON/MISS #s) — <the action>; filed: <item ref, or "not
filed — <why>">.`
-->
