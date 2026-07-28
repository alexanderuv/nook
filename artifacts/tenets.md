# Base tenets

Working principles for any agent contributing to this project. Each tenet is a
**principle: non-negotiable**. Nothing enforces a tenet mechanically — each names
its own check where one exists — but the absence of a gate is not permission: a
principle holds because it is a principle, not because something blocks the
alternative. Convenience, deadline pressure, an expert audience, or "just this
once" are never grounds to set one aside: the tenets bind every artifact and
every stage of work — drafts, plans, and internal documents included — and a
deliverable that violates one is unfinished, whatever else it achieves. The project may layer its own tenets on top of this
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

- **Plain words over jargon — absolute, without exception.** Everything written
  for a reader — questions, explanations, and every document, planning artifacts
  included — uses everyday language. No unexplained trade terms. No compressed
  shorthand: not bare token runs ("REQ17/REQ23/EDGE3"), not arrow chains
  ("resolve → validate → insert"), not abbreviations a newcomer would have to
  decode. Say what a term means in plain words at first use ("a spike — a small
  throwaway experiment built only to answer a question"). Where an artifact is cited, weave the citation into a
  sentence that states the point in plain words, so the pointer is a
  cross-check, never required reading. A concrete example the reader can
  picture beats a category name. No claimed audience lifts this rule: "the
  readers are experts" or "it's an internal doc" is never grounds to skip the
  plain-words pass — internal documents are read by future agents with no
  context, exactly the readers jargon locks out. Nor is length: when plain
  words make a document longer, the document gets longer; this tenet is never
  traded against brevity. A document that fails this check is unfinished,
  whatever else it achieves — rewriting it is part of the work, not polish
  after it. Check: if a reader outside the specialty would have to ask what a
  sentence means, rewrite the sentence; expect a reviewer to bounce the whole
  document on a single unexplained term.

- **Adopt the standard before designing a mechanism.** Before specifying how
  anything is exchanged, validated, reported, identified, or timed, name the
  published standard that already covers it and take it unless there is a stated
  reason it does not fit. Error replies over HTTP, request shapes, dates,
  identifiers, authentication, pagination — the world solved these long ago,
  wrote each one down as a public specification, and implemented it many times
  over in every language. Whether this project happens to depend on such a
  library already is beside the point: the standard exists, callers and their
  tools know it, and the burden is on anyone proposing something else. Deriving
  one from
  first principles costs a design debate, a bespoke implementation, tests, and
  documentation, and hands every caller something that works nowhere else and
  that no tool understands. The cost is hidden, too: a home-made mechanism
  re-opens settled questions as if they were new, one design meeting at a time.
  Check: for any mechanism a document specifies, it names the standard it adopts,
  or the one it rejected with the reason — "none was considered" fails.

- **Established names, never invented ones.** This is the other half of plain
  words, and the half that is easy to get backwards: the rule above bans an
  *unexplained* term, never a *technical* one. Anything the industry, the
  protocol, or the library already has a name for keeps that name — timeout,
  loopback address, HTTP status, client, idempotent, bad request — glossed in a
  few words the first time and used plainly after. Never coin a synonym to make
  a term sound simpler. A coined word cannot be searched for, matches nothing in
  any documentation the reader meets next, and turns a term they already knew
  into one they have to learn; it is worse than jargon precisely because it
  reads as plain English, which hides that a standard concept was meant. The
  damage compounds the moment the words leave prose: a name invented in a
  document becomes a field on the wire and a class in the code, and then it is a
  contract that outside callers are held to. Where a project genuinely has a
  thing of its own, name it plainly and define it once — that is what a
  definitions list is for. Check: read the definitions list of any document; an
  entry that teaches a word the wider world already has a name for is a defect
  in the vocabulary, not a service to the reader — replace the word with the
  established one and delete the entry.
