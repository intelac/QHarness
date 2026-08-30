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

The probes have been run against four real Play 1 codebases, chosen because
each stresses a different axis:

| codebase | routes | templates | result |
|---|---|---|---|
| booking sample | 15/15 | 11 | catch-all, static mount |
| `just-test-cases` (Play's own) | 64/64 | 79 | 2 module includes, 6 dynamic dispatches |
| BionimbuzWeb (1.5.3, crud+secure) | 103/103 | 64 | dotted-package routing, 19 custom tags |
| jclaw (API backend) | 228/228 | 4 | largest route table found |

No unparsed lines in any of them. `just-test-cases` is Play's own deliberate
edge-case corpus and is where the scanner's worst bug surfaced: bare `${title}`
expressions — the commonest thing a template does — were being filed as
unreadable rather than as field reads. It cost 121 of 170 "unresolved"
expressions and was invisible on the booking sample, whose templates happen to
read everything through a dot.

**No open-source Play 1 application exists at the target scale.** Searched
systematically: the largest route table found anywhere is 228, against a
target of a thousand or more, and the largest template corpus is 79. Play 1
peaked in enterprise Java around 2010-2013 and its large survivors are behind
company walls — the one public candidate that looked right, SIGA, turned out
to have already migrated off Play 1.

So scale itself remains unverified. What has been verified is behaviour
against the awkward syntax real projects contain, which is a different and
lesser claim.

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
