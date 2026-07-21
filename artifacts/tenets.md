# Base tenets

Nook's shipped, instance-global tenet set ([docs/03](../docs/03-skills-and-tenets.md)):
common conventions applicable to any project, honored by every agent that acts
through Nook. Projects layer their own project tenets on top; where the two collide,
the project tenet wins. Advisory in v1 — injected into context and honored, not
mechanically enforced — so each tenet names its own check where one exists.

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
