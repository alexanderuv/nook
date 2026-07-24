# Base tenets

Working principles for any agent contributing to this project. Each tenet is a
**principle: non-negotiable**. Nothing enforces a tenet mechanically — each names
its own check where one exists — but the absence of a gate is not permission: a
principle holds because it is a principle, not because something blocks the
alternative. Convenience, deadline pressure, or "just this once" are never
grounds to set one aside. The project may layer its own tenets on top of this
base set; where the two collide, the project tenet wins — revising the set is
the project's prerogative, never a working agent's.

## Principles

- **Code comments stand alone.** Never write planning-artifact IDs (step,
  requirement, goal, or finding numbers) or references to markdown documents into
  code or its comments. A comment becomes authoritative the moment it is written
  and must hold on its own, timeless; artifacts drift, archive, and renumber — a
  comment that leans on one decays with it. State the underlying reason in plain
  words instead. Citing artifacts *between* artifacts stays right and expected;
  the ban is on code. Check: sweep the change's diff for artifact-ID tokens and
  `.md` references; expect zero hits in code.

- **The right fix, not the quick one.** When a defect surfaces, fix the cause, not
  the symptom — a papered-over root cause returns with interest, and the paper
  obscures it next time. If the right fix doesn't fit the current item's scope,
  say so and raise it as its own item rather than landing the shortcut silently.

- **Verify, don't trust.** A change isn't done because it was made; it's done when
  an observable check has shown it working — a test run red-then-green, a boundary
  deliberately violated to prove its guard fires, an endpoint actually called.
  Report outcomes faithfully: a failing check is a finding to surface, never to
  soften.

- **Scope is a contract.** Do what the item names; park every temptation found
  along the way — the adjacent refactor, the "while I'm here" cleanup — as a new
  item instead of folding it in. Small diffs keep review honest and history
  legible.

- **Numbered series start at 01.** Epics, documents, and any other numbered
  sequence begin at 01, never 00 — the first item of a series is the first, and
  zero-based numbering in human-facing series reads as a programmer reflex, not a
  convention.

- **Plain words over jargon.** Questions, explanations, and documents use everyday
  language — no unexplained trade terms, no compressed shorthand. Where a technical
  term is unavoidable, say what it means in plain words at first use ("a spike — a
  small throwaway experiment built only to answer a question"). A concrete example
  the reader can picture beats a category name. Check: if a reader outside the
  specialty would have to ask what a sentence means, rewrite the sentence.
