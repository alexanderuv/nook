# 08 — Deployment & cloud strategy

**Status:** Direction set · applies from the first hosted deploy

This explains, in plain terms, how Nook runs when it stops being a thing on one
laptop and starts being something you reach over the internet — and how to do that
without closing off the option of turning Nook into a product later.

Two goals drive everything here:

1. **Reach Nook from anywhere in the world** — but, at first, only me.
2. **Don't get stuck** if I later decide to sell Nook to other people.

The good news: these barely fight each other. Getting on the internet is mostly a
setup job, not a redesign. Staying open to a product is a handful of small choices
made early.

---

## The one rule that shapes the whole setup

Nook has one part that does all the writing — the **core service**. Everything that
changes data goes through it, and there must only ever be **one** of it running per
project at a time. That is on purpose: it's what keeps the database and the git
history from stepping on each other (ARCHITECTURE.md §3.3, §4.1).

So the obvious cloud move — "run five copies of everything so it's fast and never
goes down" — is exactly the thing we must **not** do to the core. Five cores writing
to the same project is the bug we designed the whole system to avoid.

This gives us a simple split:

- **The core** — run **one** of it. It's the part that holds files and must be
  handled with care.
- **The two front-end apps** (the web app and the MCP server) — these don't hold any
  data of their own; they just pass requests to the core. Run as many copies of these
  as you want.

Everything below follows from that split.

---

## Getting on the internet (goal 1)

### Don't build a login screen yet — put a lock in front

Today Nook has no login, on purpose: it only listens on the local machine (§8). The
moment it's on the internet, "no login" is no longer safe — anyone who finds the
address could change your data.

The cheap, correct fix is **not** to build accounts and passwords into Nook. It's to
put a **gate in front of it** — a service that checks it's really you before letting
any request through, and blocks everyone else. Off-the-shelf options do this in an
afternoon:

- **Cloudflare Access** or a cloud provider's built-in access proxy
- **Tailscale** (a private network only your devices can join)
- **oauth2-proxy** (sign in with an existing account, e.g. Google, before reaching
  the app)

Nook itself stays exactly as it is — no login code — but now it sits behind a lock
only you can open. This keeps the "no accounts inside Nook yet" decision honest even
though it's reachable worldwide.

### Where the app actually runs

Rent a place to run containers rather than managing your own servers. Good starting
points (any one — the choice barely matters):

- AWS **ECS / Fargate**, Azure **Container Apps**, or Google **Cloud Run**.

Run the **one core** as an always-on service with a disk attached (see below). Run
the **web app and MCP server** as separate services you can scale freely. Put the
access gate and HTTPS in front. Use a **managed database** for Postgres (AWS RDS,
Azure Database for PostgreSQL, Cloud SQL) — it's just a connection string.

**Skip Kubernetes for now.** It's a powerful way to run lots of services, but it's a
lot of machinery for three small ones. Move to it later *if* Nook grows enough to
need it; the setup above translates cleanly when that day comes.

### Keeping the files safe

The core keeps a working copy of each project's git repository on disk. A rented
container can be restarted at any time and lose whatever was on its local disk, so we
handle the files two ways together:

- **Attach a disk that survives restarts** (a "persistent volume") so the working
  copy isn't lost on a restart.
- **Push a copy to a hosted git remote** (like GitHub) and treat *that* as the real,
  safe copy. If the local copy is ever lost, the core can re-fetch it. Nook already
  supports this "sync to a remote" step (§3.3, docs/05); going to the cloud just means
  turning it on.

---

## Not painting myself into a corner (goal 2)

If Nook one day serves many customers, the expensive mistake is baking "there's only
one user" so deeply into the code that untangling it means a rewrite. We avoid that by
making a few small choices **now**. None of these is real product work — each is one
column or one decision today, and a full feature only if and when we productize. All
four are now settled:

1. **Tag data with an owner from day one.** ✅ **Done.** Each project carries an
   `owner_subject` — who owns it — kept separate from "who created this row." There's
   one owner today; later, "show only this customer's data" is a simple filter instead
   of a painful migration through every table. Because everything else already lives
   under a project, this one column is the whole seam. (ARCHITECTURE.md §6, §8; schema
   `project.owner_subject`.) This is the most important one.

2. **Let the core be split by project.** ✅ **Settled as a principle.** We treat the
   core as "the one that handles *these* projects," not "the one and only core in the
   world." Today it handles all of them. Later, serving many customers means running
   several cores and handing each a share of the projects — still one writer per
   project, no new locking needed. Nothing to build now; just don't write code that
   assumes there can only ever be one core.

3. **Use internet-friendly connections now.** ✅ **Already true.** The web app and the
   agent (MCP) connection already run over HTTP rather than only on the local machine
   ([01](./01-interface-contracts.md) — "both apps are HTTP servers"). So reaching Nook
   from anywhere is a deployment-and-gate matter, not a code change. Nothing to do.

4. **Shape "who did this" like a real sign-in.** ✅ **Done.** Every change is stamped
   with an actor stored as a stable *subject* string (§8) — the same shape a real login
   provider hands out. Today the access gate (or a local default) supplies it; later an
   in-app login supplies it — same column, no rework. There is deliberately no
   accounts table yet.

---

## What we're deliberately not doing yet

Building any of these now would be solving problems we don't have. Each becomes
possible to add — not a rewrite — because of the four choices above.

- Accounts, sign-up, and password management inside Nook.
- Teams, organizations, billing, and permission levels (who-can-do-what).
- Strong data separation between customers (separate databases per customer, etc.).
- Running in multiple regions of the world at once.
- Actually running several cores split by project.
- Kubernetes.

---

## In one paragraph

To reach Nook from anywhere: run it on a rented container service (one core with a
saved disk, plus scalable front-end apps), put an access gate in front so only you get
in, use a managed database, and turn on syncing the files to GitHub as the safe copy —
no login code and no Kubernetes needed. To stay open to a product: tag data with an
owner, treat the core as splittable by project, use internet-ready connections, and
record the actor like a real sign-in. Everything else waits until there's a real
reason to build it.

## Depends on / feeds

- Builds on the topology and consistency rules in ARCHITECTURE.md §3.3, §4.1 and
  [05](./05-project-and-ops.md).
- Revisits the "no auth, localhost-only" assumption of ARCHITECTURE.md §8 for the case
  where Nook is reachable over the internet.
