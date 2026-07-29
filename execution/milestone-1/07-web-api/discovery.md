# Web API approach

## Summary

- **The web app is the core's own answering side with the calling library
  underneath it, and almost nothing else.** The code that reads a request, runs
  it, and writes a reply already exists in `:core-service`; the calling library
  epic 05 built already offers the eleven operations under the one interface that
  code runs requests against. Put the second under the first, change the address
  from `/` to `/api`, and 25 requests out of 25 — every operation, all four
  refusals, and eight kinds of request that cannot be read — come back word for
  word identical to what the core's own connection answers. There is no
  translation to write, because there is nothing to translate.
- **What a request means is decided by the same code at both adapters, so the app
  turns down what it cannot read without the request ever crossing.** The eight
  unreadable requests were refused by the app itself and the stand-in core
  received none of them; every readable request reached it exactly once. Three of
  those refusals, though, say what was wrong in the words of the library that
  turns text into values — they tell the caller to change a setting in a library
  the caller does not have, and quote the whole request back inside the message.
- **Taken exactly as it comes, the shortcut breaks one requirement: it calls a
  core it could not reach a fault inside the core.** The handler's last clause
  catches everything left and says "something inside the core failed", so a core
  that was never running produces those words — where [spec-5](./spec-5.md), the
  requirements this epic answers to, asks a fault to say
  whether the core answered at all, because one is worth trying again and the
  other is not. One added clause tells them apart, and after it a call to a
  stopped core says `connection` and a defect inside the core says `core`.
  Whether those words belong in the message or in a field of their own is a
  choice this report does not close: the reply has no field for it today, and
  adding one is a change the core's own replies take at the same time.
- **The one case where the two candidate designs genuinely differ is a core
  answering with a field this build has never heard of.** The app that decodes
  every answer turns a write that already landed into a fault; an app that hands
  the core's reply back untouched carries the new field through. Nothing in this
  milestone produces that case — both halves ship from one source tree — but it
  is exactly the shape of what epic 08 does when it adds who did what.
- Recommendation in brief: build the app as the core's handler over the calling
  library, at `/api`, with the root address left free; add the one clause that
  tells a core that could not be reached from a core that broke, and take the
  question of where those words live to a decision; bind the app to the address a
  machine uses to reach itself, which a control run again shows is the whole of
  what keeps an outside caller out; and build nothing for a caller that walks
  away, because 100 runs say the write finishes on its own.

## Questions

- **Q1** — Can the web app be the core's own answering side with the calling
  library standing in for the core's catalog, and does what comes out of it match
  what the core's own connection answers, request for request?; informs: the
  shape of everything this epic builds, and spec-5's requirements that the app
  serve the core's own request and reply shape, apply no rule of its own, keep an
  unreadable request away from the store, and hand back what the core produced
  whole.
- **Q2** — Do the three ways a call can end arrive so that a caller can tell them
  apart — an answer, a refusal carrying the core's own code, and a fault that
  says whether the core answered at all?; informs: spec-5's requirements about
  how a call ends, and the acceptance criteria that drive a planted defect and a
  stopped core through this surface.
- **Q3** — What does this surface do about a caller that stops listening, several
  callers at once, a listing of five thousand items, a core that arrives late,
  and a caller on another machine?; informs: spec-5's requirements on several
  callers at once and on where the app listens.
- **Q4 (emerged)** — What does each candidate do when the core answers with a
  field this build has never heard of?; informs: the choice between the two
  candidates, and spec-5's assumption that epic 08 adds its fields by growing
  what already crosses rather than by adding something this surface must carry.
  Asked once the probes showed the two candidates agreeing on every case put to
  them, which left nothing to choose between them on.

Bound: one throwaway program on one machine (macOS, Apple silicon, JDK 25),
every library version taken from the repository's own pins, eleven probe groups,
a stand-in for the core rather than the core itself, no database, no browser, no
second machine, and no crowd larger than sixteen callers at once. That sufficed
because every question here is about the app that sits in front of the core,
which one honest execution settles, and because what the core does and what the
crossing to it guarantees are already settled and tested by epics 03 to 05 —
putting the real core behind these probes would have measured that work again
rather than this one. Five things the bound leaves untested are recorded as
limitations, and the browser was left out by decision rather than by cost.

## Method

A throwaway Kotlin program in a scratch directory, with its own build, run
2026-07-28 and deleted after this report. It depends on the repository's own
built `:contract` module, so the probes drive the real contract library, the real
calling library, and the real reading of a request — not imitations of them.
Alongside it: Ktor 3.5.1 for both serving and calling and kotlinx.serialization
1.11.0 for the text format, both at the versions the repository pins.

**The core was a stand-in, not the core.** It is the core's own request handler,
copied line for line out of `CatalogServer`, running over a catalog that answers
with canned entities, records exactly what it was invoked with, and — driven by
the reference a call names — refuses with each of the four codes, throws a
defect, or takes its time. Spec-5 already permits this: its own assumption says
this epic's checks may stand up a stand-in core, because the assembled run
against a real store belongs to [epic 09](../09-full-system-test/). What crosses
to the real core, and what that crossing guarantees, is
[spec-3](../05-operation-catalog/spec-3.md)'s subject, and epic 05's discovery
reports it from the real services.

Three candidate web apps were built, all serving one address, `/api`:

- **Candidate A — the core's own handler, unchanged, with the calling library
  underneath.** The calling library offers the eleven operations under the same
  interface the core's handler runs a request against, so the substitution is one
  argument. Nothing else was written.
- **Candidate A with one clause added.** The same, plus a single catch that reads
  the origin the calling library already records on a failed call — whether an
  answer arrived at all — and puts it in the reply.
- **Candidate B — hand the request on as it arrived, hand the reply back as it
  arrived.** Nothing is decoded in between. The calling library offers no way to
  do this: its whole surface is the eleven typed operations, so this forwarder
  had to be written by hand, and an app built this way does not go through the
  calling library at all.

Eleven probe groups drove them. The same twenty-five requests sent to the core
directly and to each candidate, with the replies compared as whole values rather
than as text, and the core's own count of what actually reached it; thirteen
requests whose arrival was checked against what the core's catalog was invoked
with, one for one; a defect planted inside the core, a core that was never
started, and a core made to answer nothing at all; a core answering with one
field more than this build knows about; a listing of 5,000 items timed through
each adapter; a slow call and a fast one together; 100 runs of a caller that stops
listening while the core is still writing; every other way of knocking on the
app's adapter, including a request that says it came from a page and a request sent
as plain text; the app bound to the address a machine uses to reach itself, with
a control run first to establish which of this machine's addresses reach a server
at all; sixteen callers at once
against two ways of making the call to the core; the app started before the core,
with the core arriving, leaving, and coming back; and one check of whether the
reply shape can be widened to say which kind of fault it carries.

Not done: the real core and any database; any browser (see the limitations —
this was a decision, not an oversight); any second machine; any second web server
engine — the part of a web library that receives requests off the network — so
every result here is the one Ktor calls CIO; two callers contesting the
same handle, which a stand-in core with no store cannot answer; a program started
without its settings, which is ordinary startup code and cheaper to test than to
probe; and any crowd larger than sixteen callers.

## Findings

### FIND1 — The web app is the core's own answering side with the calling library underneath, and both adapters answer identically

**Confidence:** solid — twenty-five requests driven through three adapters and
compared as whole values · answers Q1

The core's handler reads a request, runs it against an operation catalog, and
writes a reply naming its own ending. The calling library implements that same
catalog interface. So the web app is that handler with one argument changed, and
the address changed from `/` to `/api`. Nothing was translated, because the
request and reply are the core's own.

Twenty-five requests were written once and sent to three adapters — the core's own
connection, candidate A, and candidate B:

| what was sent | the replies agree | reached the core: A / B |
| --- | --- | --- |
| each of the eleven operations, including a partial update in three states and a whole listing filter | yes | 1 / 1 |
| each of the four refusal codes | yes | 1 / 1 |
| eight requests that cannot be read — an operation nobody defined, a field the operation does not define, a missing required argument, a value of the wrong kind, contents that are not the format at all, a project named where none belongs, no project where one belongs, and a field the envelope does not define | yes | 0 / 1 |

All three replies were identical in 25 of 25 cases. A deletion's success carries
no entity and is still plainly not a refusal:

```
a delete's empty success -> {"outcome":"answer"}
```

### FIND2 — The same code reads a request at both adapters, so an unreadable one never crosses

**Confidence:** solid — the stand-in core's own count, taken per case · answers Q1

The right-hand column of FIND1 is the part worth reading twice. Candidate A
refused all eight unreadable requests itself, and the core received none of them;
every readable request reached it exactly once. That is not a rule the app wrote:
it is the shared reading of a request, held in the contract library, which the
core's own connection uses and which the calling library reaches through the same
entry. The two cannot disagree about a request because there is one piece of code
deciding.

Candidate B sent all eight across and let the core refuse them. The wording that
came back was identical, so nothing is lost by that — but the request reached the
core, which is the thing spec-5 asks not to happen.

Two refusals are Nook's own words and read as intended:

```
an operation nobody defined  -> validation_failed: this connection carries no operation named "teleport_item"
a project where none belongs -> validation_failed: list_projects acts on the whole instance and names no project
no project where one belongs -> validation_failed: get_item acts inside a project; name the project
```

### FIND3 — Everything a caller supplies reaches the code behind the operation exactly as written

**Confidence:** solid — thirteen cases, each compared against the same request
sent straight to the core · answers Q1

For each request the stand-in core recorded what its catalog was actually invoked
with, and the record taken through each candidate was compared against the record
taken from a direct call. All thirteen matched, through both candidates.

The three states of a partial update stay apart all the way through:

```
only the name changed   -> UpdateItem(name=Set(value=Renamed), description=Keep, …)
the description cleared -> UpdateItem(name=Keep, description=Set(value=null), …)
the description set     -> UpdateItem(name=Keep, description=Set(value=text), …)
no field named at all   -> UpdateItem(name=Keep, description=Keep, … every field Keep)
```

So do the rest: a blocker list supplied empty arrives empty, a blocker list
carrying the same reference twice arrives still carrying it twice, a cleared
release arrives cleared, a filter part holding no values arrives holding no
values rather than being turned into "do not filter on this", a filter asking for
"no epic above" arrives as that, and a reference that is nearly but not quite a
well-formed id arrives as written for the core to interpret. Text arrived exactly
as sent, emoji, non-Latin script, line breaks, quotation marks and a backslash
included:

```
create_item in p: CreateItem(type=task, name=Søk 🔍 épico, slug=null,
                             description=line one
line two "quoted" and \ backslash, …)
```

### FIND4 — Three refusals say what was wrong in the words of the library that reads the text, and hand the caller advice meant for whoever wrote the program

**Confidence:** solid — read off the wire · answers Q1

Spec-5 asks that a request the surface cannot read come back naming what was
wrong with it — the field, the missing argument, the operation. All eight do.
Three of them also carry something else:

```
a field nobody defined:
  Encountered an unknown key 'colour' at path: $
  Use 'ignoreUnknownKeys = true' in 'Json {}' builder or '@JsonIgnoreUnknownKeys'
  annotation to ignore unknown keys.
  JSON input: {"type":"task","name":"x","colour":"red"}

a value of the wrong kind:
  String literal for value of key 'name' should be quoted at path: $.name
  Use 'isLenient = true' in 'Json {}' builder to accept non-compliant JSON.
  JSON input: {"type":"task","name":42}

a required argument missing:
  Field 'name' is required for type with serial name 'io.nook.contract.CreateItem',
  but it was missing
```

Each names the thing that was wrong, so the requirement is met on its own terms.
But the first two tell the caller to change a setting in a library the caller
does not have and cannot reach, the first two quote the whole request back inside
the message, and the third names an internal class. None of this is new — the
core's connection has answered this way since epic 05, and both adapters see it —
but this is the first surface where those words go to someone outside the two
programs that share the source tree.

### FIND5 — Taken as it comes, the shortcut calls a core it could not reach a fault inside the core

**Confidence:** solid — three kinds of failure driven through three candidates ·
answers Q2

The calling library reports a call that produced no verdict by throwing, and what
it throws already records whether an answer arrived at all. The core's handler,
though, has no clause for it: the exception falls through to the last catch,
which was written for a defect inside the core and says so.

| what happened | candidate A, unchanged | candidate A, one clause added |
| --- | --- | --- |
| a defect inside the core | `something inside the core failed: BreakdownException: something inside the core failed: …` | `core: something inside the core failed: …` |
| the core was never started | `something inside the core failed: BreakdownException: no answer came back from the core at http://127.0.0.1:59252: Connection refused` | `connection: no answer came back from the core at …: Connection refused` |
| the core answers nothing at all | — | `connection: … Request timeout has expired [request_timeout=1000 ms]` (after 1,007 ms) |

The middle row is the failure. A core that is not running says "something inside
the core failed" — and spec-5 asks a fault to say whether the core answered,
precisely because one is worth trying again and the other is not. The added
clause is four lines and fixes all three rows.

Two things held without help. The core received the abandoned call exactly once,
so nothing anywhere sends a call a second time. And after every one of these
failures the next call was served normally.

### FIND6 — The reply has nowhere to say which kind of fault it is, and giving it one is a change both halves take together

**Confidence:** solid — both directions of the change executed · answers Q2

The fault reply carries a message and nothing else, so FIND5's clause puts the
origin into the text. A reply that carried it in a field of its own would let a
caller read it without matching words. That change is not free, and its cost runs
in one direction only:

```
today's fault reply, read by a shape carrying the new field
  -> Fault(message=something inside the core failed, reached=core)

a widened fault reply, read by today's shape
  -> Unexpected JSON token at offset 53: Encountered an unknown key 'reached' at path: $
```

A reader that knows the new field can read an old reply, because the field can
carry a default. A reader that does not know it cannot read a new reply at all,
because the format refuses a field nobody defined — deliberately, and for good
reasons recorded in the contract library. So the change is safe exactly as long
as everything that reads a reply ships from one source tree, which is what
spec-3 already assumes. It also lands on the core's own replies, not only on this
surface's, which makes it a decision rather than an implementation detail.

### FIND7 — A core one field ahead: the decoding app turns a landed write into a fault, the pass-through carries the field through

**Confidence:** solid — executed against a core answering with an extra field ·
answers Q4

Every probe before this one found the two candidates identical. This is where
they part. A stand-in core was made to answer a `create_item` with the entity the
contract defines plus one field more — `"assignee":"alex"` — which is the shape
of what epic 08 does when it records who did what.

```
candidate A -> {"outcome":"fault","message":"core: the core at … answered this call with
                something this cannot read: Encountered an unknown key 'assignee' …"}
candidate B -> {"outcome":"answer","result":{ … ,"assignee":"alex"}}
```

Candidate A decodes every answer into the entity it knows and writes it back out,
so a field it has never heard of stops the reply — and the write had already
landed. The calling library is deliberate about this: it reports such a reply as
a fault rather than a refusal, exactly so nobody tells a caller to correct work
that already happened. But the caller is still told the call produced no verdict,
when in fact it produced an entity.

Candidate B never looks inside, so the field travels through untouched.

Nothing in this milestone produces the case: both halves ship from one source
tree, and the core cannot answer with a field the app has not been built with.
It is a claim about what happens the day that stops being true.

### FIND8 — Every reply comes back under one number, and every other way of knocking gets the web server's own answer

**Confidence:** solid — every case in the first group, and five other approaches ·
answers Q2, Q3

Across all twenty-five requests of FIND1 — answers, refusals, and faults alike —
the set of numeric codes the replies came back under has exactly one member:

```
every reply's numeric code, over all twenty-five cases -> [200]
```

So nothing about the ending is read off the number, which is the point: an item
that is not there and an address that is not there cannot collide.

The other ways of arriving:

| what was sent | what came back |
| --- | --- |
| `POST /api` with a request | the answer |
| `GET /api` | 405, empty body |
| `POST /` | 404, empty body |
| `GET /` | 404, empty body |
| `POST /api/create_project` | 404, empty body |

None of the last four carries any of the four refusal codes, which is what
spec-5's edge case asks for, and the root address answers nothing at all, which
leaves it free for the interface arriving in milestone 4.

### FIND9 — A caller that walks away leaves the write alone

**Confidence:** solid — 100 runs, counted on the core's side · answers Q3

Each run sent an update through the web app to a stand-in core whose work takes
120 milliseconds, from a caller that gives up after 40. Every caller left before
its answer; the core finished every write:

```
callers that gave up waiting         -> 100 of 100
writes the core carried to their end -> 100
```

Nothing was built to make that true, and nothing needs to be. The calling library
blocks the thread it runs on until the core answers, and epic 05 already showed
the core's own write cannot be interrupted by a caller going away. This surface
adds no way to break that.

### FIND10 — The app recovers on its own when the core arrives, leaves, and comes back

**Confidence:** solid — four calls through one app that was never restarted ·
answers Q3

The app was started pointing at an address where nothing was listening:

```
the first call, nothing listening -> fault, connection: no answer came back from the core at …
the core is started, same call    -> answer, the project
the core is stopped, same call    -> fault, connection: no answer came back …
the core is back, same call       -> answer, the project
```

Nothing on the app's side was restarted or rebuilt at any point. The app holds
one calling library for its life, and that is what recovers.

### FIND11 — Five thousand items cross two hops whole and in order, and reading them back costs about three times what passing them through does

**Confidence:** solid — three runs per adapter, compared against the core's own ·
answers Q3

| the adapter | three runs | what arrived |
| --- | --- | --- |
| straight to the core | 102, 50, 38 ms | 5,000 items, the core's own order |
| candidate A (decodes and rewrites) | 150, 127, 96 ms | the same 5,000, the same order, the same 2,233,924 characters |
| candidate B (hands the reply back) | 47, 42, 40 ms | the same |

Both are far inside the thirty seconds a call waits, so the difference decides
nothing today. It is worth recording because it is the price of the decoding that
FIND7 also charges for: candidate A reads five thousand items out of text and
writes them back into it, on top of what the core already did.

A slow call and a fast one together showed no interference: the slow one finished
at 1,509 milliseconds and the fast one, started fifty milliseconds later, at 3.

### FIND12 — The loopback binding is what turns an outside caller away, and a control run proves it

**Confidence:** solid for the binding; every caller was on this machine ·
answers Q3

The loopback address is the one a machine uses to reach itself, and nothing
outside the machine can route to it. This machine has two addresses that are not
the loopback, and only one of them reaches a server at all, so the control run
comes first — as epic 05's and epic 06's did:

| address | app listening on every address | app listening on 127.0.0.1 |
| --- | --- | --- |
| `192.168.50.95` (the local network) | answered 200 | `ConnectException — Connection refused` |
| `10.5.0.2` (a tunnel) | could not connect either way | — |
| `127.0.0.1` | answered 200 | answered 200 |

The address that demonstrably reaches an app listening everywhere is refused by
the same app bound to the loopback. No credential was presented in any of these
calls and none was asked for.

### FIND13 — The app serves a request that says it came from a page, and a request sent as plain text

**Confidence:** solid about the app; nothing here involves a browser · answers Q3

Two requests were sent to `/api` alongside the ordinary ones:

```
carrying the address of a page as its sender -> served, identically
sent as plain text rather than as JSON       -> served, identically
```

The first is what spec-5 requires: no restriction on where a request came from,
beyond the machine it came from. The second is not required by anything, and it
is recorded because of what it removes. Every request carries a label saying what
kind of contents it holds, and Nook's are the format called JSON — ordinary text
made of named fields. The app never consults that label: it reads the body
whatever the sender called it, so a caller that labels its request plain text is
served exactly like one that labels it JSON.

Both facts are about the app. Neither is evidence about what a page open in a
browser can do, and this report makes no claim about that — see the limitations.

### FIND14 — This probe could not separate the two ways of making the call to the core

**Confidence:** weak — a negative result, and the setup could not isolate what it
was measuring · answers Q3

The calling library blocks the thread it is called on. Epic 05 recommended
handing the store's work to threads that are allowed to sit and wait rather than
running it on the few a web server answers on, and named it as something that
looks like a detail. The same choice arises here, one layer out.

Both arrangements were built and driven with four and then sixteen slow calls in
flight, each holding for a second and a half, with a fast call sent afterwards:

```
handed to waiting threads ->  4 slow calls in flight, a fast one answered after 8 ms
on the answering threads  ->  4 slow calls in flight, a fast one answered after 5 ms
handed to waiting threads -> 16 slow calls in flight, a fast one answered after 7 ms
on the answering threads  -> 16 slow calls in flight, a fast one answered after 5 ms
```

Neither arrangement made the fast caller wait, so nothing here chooses between
them. An earlier run at thirty-two callers did make both arrangements slow, but
that run cannot be read either: the stand-in core and the web app were one
program, so the threads the arrangement is about were shared between the app
making the call and the core answering it. Separating them needs two programs,
which this probe did not build. The recommendation below therefore carries epic
05's reasoning forward rather than a measurement of this surface.

## Implications & recommendation

- **Build the web app as the core's own request handler with the calling library
  underneath, serving `/api`** (FIND1, FIND2, FIND3) — the request and reply are
  the core's own, so there is nothing to translate, and twenty-five requests come
  back identical at both adapters without a line written for it. This is what makes
  spec-5's demand for one contract structural rather than a promise: there is one
  piece of code deciding what a request means and one deciding what a reply says.
  The epic's work is the program around it — where it listens, what it is told
  from outside — not a surface of its own.
- **Add the one clause that tells a core which could not be reached from a core
  that broke** (FIND5) — without it the app answers "something inside the core
  failed" for a core that was never started, which is the opposite of what a
  caller needs to decide whether to try again. The calling library already
  records which it was; the clause only has to read it. Worth a test of its own,
  because nothing about the code looks wrong when it is wrong — the same trap
  epic 05 recorded about where the reading of a request sits.
- **Take the question of where a fault's origin lives to a decision, rather than
  settling it here** (FIND6) — a field of its own is what lets a caller read it
  without matching words, and the change is safe while everything ships from one
  source tree, which spec-3 already assumes. But it widens the reply the core
  answers with too, so it belongs to whoever owns that shape, not to this
  surface. Recording the decision is the epic's act; this report only says the
  change works and what it costs. (See Open questions.)
- **Do not build the pass-through, and record what that gives up** (FIND7,
  FIND11) — it agrees with the decoding app on every case this milestone can
  produce, it cannot use the calling library at all, and using it would leave
  spec-5's statement that every call goes through that library false. What it
  would have bought is one thing: an answer carrying a field this build has never
  heard of reaches the caller instead of becoming a fault. That case cannot arise
  while both halves ship together, and the day it can, the fix is a version rule
  rather than a second design.
- **Expect epic 08 to grow the entities rather than to add a field beside them**
  (FIND7) — spec-5 assumes exactly that, and this is the evidence for why the
  assumption matters: an app that decodes every answer refuses a field it was not
  built with, after the write has already landed. As long as the app and the core
  are built together the case never occurs; the assumption is what keeps it that
  way.
- **Serve at `/api` and leave the root address answering nothing** (FIND8) — the
  root already answers nothing, so the interface arriving in milestone 4 has it
  free, and every other address gives the web server's own reply carrying none of
  the four codes, which is what spec-5's edge case describes.
- **Let every reply come back under one number** (FIND8) — it already does, and
  it is what keeps "the item is not there" from arriving as the same number as
  "the address is not there". This costs nothing to keep and would be easy to
  lose by letting an exception escape the handler.
- **Build nothing to protect a write from a caller that walks away** (FIND9) —
  100 runs say the core finishes on its own, for the same reason epic 05 found:
  the work blocks the thread it runs on and cannot be interrupted by the caller
  leaving. The matching acceptance criterion is a test to write, not a mechanism
  to build.
- **Bind the app to the loopback address, and take both addresses from outside
  the program** (FIND12) — the binding is demonstrably what refuses a caller on
  an address that otherwise reaches the app, with no credential involved, which
  is exactly what spec-5 claims the whole of its protection is.
- **Hand the call to the core to threads that are allowed to sit and wait**
  (FIND14, and epic 05's own finding) — *this is reasoning, not a measurement*:
  the probe could not separate the two arrangements, so what carries the
  recommendation is epic 05's, one layer in. The calling library blocks the
  thread it runs on, the pool a web server answers on is small, and the cost of
  the safer arrangement is one line inside the handler.
- **Reword the three refusals that quote a library at the caller** (FIND4) — they
  name the right thing, so no requirement fails, but they tell an outside caller
  to change a setting they cannot reach and echo the whole request back inside
  the message. The words belong to the contract library and are shared with the
  core's connection, so improving them is a change there rather than here.
  Whether it is urgent enough to do before this surface ships is opinion, not a
  finding; either way it is its own item rather than something to fold into this
  epic.

## Limitations

- **The core behind the app was a stand-in, not the core** — at risk: nothing
  here shows a request reaching the real write and read paths and coming back, so
  every claim about fidelity is a claim about this app and the calling library,
  not about the assembled system; would raise confidence: driving spec-1's and
  spec-2's own criteria through this surface against the real core, which spec-5
  already assigns to [epic 09](../09-full-system-test/).
- **No browser was involved, by decision** — at risk: spec-5 states plainly that
  a page a person has open in a browser on this machine can call this address and
  delete a project, and names revisiting that as a condition of the surface
  reaching real users. This report does not verify it. What FIND13 shows is only
  that the app serves a request carrying a page's address and serves a body
  labelled plain text — which removes one thing that might otherwise have stood
  in a browser's way, and is a reason to expect the claim is true rather than
  evidence that it is; would raise confidence: one page served from a second
  address on this machine, opened in a browser, calling `/api`.
- **The "caller from another machine" was this machine** — at risk: the binding
  result is evidence about which of this machine's addresses a bound app answers
  on, not about a genuinely remote caller, and a network arrangement that
  forwards traffic onto the loopback would defeat it unnoticed; would raise
  confidence: one call from a second machine on the same network. (Epics 05 and
  06 record the same limitation on the same question.)
- **One web server engine, one machine, one run of each timing** — at risk: every
  result here is Ktor's CIO engine on macOS, and the epic has not chosen an
  engine; epic 05 measured one question on two engines and got the same answer,
  which is weak evidence that the rest would; would raise confidence: repeating
  the endings and concurrency groups on the other engine, or choosing the engine
  first and re-running once.
- **The two ways of making the call to the core were not separated** — at risk:
  the arrangement recommended above rests on epic 05's reasoning rather than on
  anything measured here, and the probe's own setup could not isolate it because
  the app and the stand-in core shared one program; would raise confidence:
  repeating the crowded runs with the core in a program of its own, which this
  epic can do once there is an app to run separately.
- **Nothing was measured under load, and no crowd exceeded sixteen** — at risk:
  every concurrency result is a handful of callers doing one thing each, and says
  nothing about many people and many agents at once; would raise confidence: a
  load probe against the built surface. (The write path, the read path, and the
  connection each left the same question behind; this is the fourth instance of
  it, and one probe against the assembled system would answer all four.)
- **Two callers contesting the same handle was not driven** — at risk: spec-5
  asks that two callers creating an item of the same name both succeed with
  different handles, and a stand-in core with no store cannot answer that; would
  raise confidence: nothing worth spending here — it is the store's guarantee,
  epic 05 drove it across the connection, and epic 09 drives it through this
  surface.
- **A program started without its settings was not probed** — at risk: the
  requirement that such a program stop and name the missing setting is left
  entirely to the epic's own code; would raise confidence: nothing worth spending
  — it is ordinary startup code, the core already does it, and a test of it is
  cheaper than a probe of it.

## Open questions

**Needs action:**

- **Q5** — Does a fault reply gain a field naming what was reached, or does the
  message carry those words?; blocks: the acceptance criteria that ask a caller to
  tell "the core could not be reached" from "the core answered and broke", which a
  message can satisfy only by being matched on its wording; would take: a decision,
  because the change widens the reply the core itself answers with and so belongs
  with whoever owns that shape — FIND6 shows it works and that it is safe only
  while everything that reads a reply ships from one source tree.

**Follow-ups:**

- **Q6** — Can a page open in a browser on this machine actually call this
  address and delete a project?; matters because: spec-5 states it as fact and
  makes revisiting it a condition of this surface reaching real users, and this
  report deliberately did not test it — FIND13 removes one reason to doubt it but
  proves nothing; would take: serving one page from a second address on this
  machine and opening it in a browser.
- **Q7** — Do the three refusals that quote the text-reading library at the
  caller need rewording before this surface faces anyone outside the two programs
  that share the source tree?; matters because: they tell a caller to change a
  setting in a library they do not have and echo the whole request back inside
  the message, and this is the first surface where those words leave the pair;
  would take: a decision about the wording, and a change in the contract library
  rather than in this epic.
- **Q8** — Does handing the call to the core to threads that may sit and wait
  actually matter, and at what crowd?; matters because: the arrangement is
  recommended on reasoning carried over from epic 05 rather than on anything this
  probe could measure; would take: repeating the crowded runs with the core
  running as its own program.
- **Q9** — Does the surface behave the same on the other web server engine?;
  matters because: every result here is one engine and the epic has not chosen
  one; would take: re-running two of the probe groups against the other engine,
  or choosing first and re-running once.
