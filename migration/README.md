# Migrating a Play 1 codebase

Machinery for carrying a large Play Framework 1.x application to Spring Boot
and React, with an agent doing the volume and a person deciding the questions
that have no answer in the old code.

Nobody holds a complete picture of a codebase this size — that is the premise,
not a complaint. So the arrangement never asks a person for facts that are in
the source, and never asks an agent for a judgement the source cannot settle.

## The parts

```
ARCHITECTURE.md     the four layers and the evidence for each boundary
pipeline/           what runs, in what order, deterministically
contracts/          decisions, made once, binding on every unit
probes/play1/       scripts that enumerate — the denominator
skills/survey/      how an agent establishes what is there
```

## Where to start

`ARCHITECTURE.md`. Every structural choice here is the consequence of a
measurement or a published result, and the tempting alternatives mostly sound
better than they are. The reasoning is recorded so that a later simplification
has to argue with the evidence rather than with a preference.

## The shape, in one page

```
enumerate      scripts, not the agent — a list and its denominator
   ↓
partition      units small enough to fit and verifiable alone
   ↓
dispatch       one unit, one fresh session, frozen contracts
   ↓
gate           compiler, tests, coverage, behaviour — all external
   ↓
record         what applied where, so a wrong decision is reversible
```

Four rules hold it together, each with evidence in `ARCHITECTURE.md`:

**Scripts enumerate.** An agent finds what it happens to find — 24–35%
coverage with 42% run-to-run variance, measured on real legacy code. A
migration cannot be built on a list whose denominator is unknown.

**One unit, one session.** A context holding a model's own earlier mistakes
makes further mistakes more likely, and scale does not fix it.

**The agent never grades itself.** Intrinsic self-correction degrades
performance. Compilers and tests arbitrate; test files are read-only to the
agent, which is worth 41.5% against 22.8%.

**One agent, not many.** Parallel agents cannot see each other's implicit
decisions, so forty modules become forty dialects. Parallelism goes into
independent units against a frozen contract instead.

## Status

Verified on the Play 1 booking sample: both probes parse it completely
(15/15 routes including catch-all and static mounts; 11 templates yielding 72
field reads, 7 method calls, 16 reverse routes), and `SURVEY-booking.md` is
what the survey produces.

Not verified at scale. Nothing here has met a codebase of hundreds of
thousands of lines, and the first run on one will find things this design did
not anticipate.

## What is missing

```
probes      imports, modules, models, tests
pipeline    the runner itself; unit state; the queue
gates       the coverage check and the behaviour comparison
skills      the per-unit migration skill
```

The probes are the next thing, because everything downstream reads their
output.

## What is not known

No published account exists of an agent completing a cross-framework migration
at this scale. The nearest industrial case — Google's 32→64-bit identifier
migration — is a version-level change inside one framework, and it still took
twelve months, left a quarter of the changes fully manual, and saved about
half the effort rather than most of it.

This is not a method known to work. It is the arrangement the available
evidence does not rule out, which is a weaker claim and the accurate one.
