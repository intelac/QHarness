# The pipeline

Deterministic. Nothing here asks a model anything.

Its job is to decide *what exists* and *what order it happens in*, so that by
the time an agent is involved the question is only *how to do this one thing*.

```
1  enumerate    scripts read the codebase → complete inventory
2  partition    inventory → units, by rule
3  order        units → queue, by dependency
4  dispatch     one unit → one fresh session → agent
5  gate         compiler, tests, coverage → pass or return
6  record       what happened, and what it means for the queue
```

Steps 1–3 run once and rerun when the codebase changes. Steps 4–6 run per
unit, thousands of times.

## 1 · Enumerate

Every probe reports three things: what it found, **how many it looked at**,
and what it could not read. The middle one is why this is not the agent's job
— an agent produces a list, a probe produces a list *and its denominator*.

```
probes/play1/parse-routes.py      routes, incl. catch-all and static mounts
probes/play1/scan-template.py     template field reads, method calls, reverse routes
probes/play1/scan-imports.py      framework coupling per layer, by API
probes/play1/scan-modules.py      modules and their play/shell/value split
probes/play1/scan-models.py       entities and every static-query call site
probes/play1/scan-tests.py        tests, split by whether they survive the move
```

A probe that cannot parse a line **names it**. `parse-routes.py` returns exit
3 with the unparsed lines listed; the pipeline treats that as a stop, not a
warning. Three unparsed lines named is a small problem. Three unparsed lines
dropped is three endpoints that vanish from the migration and reappear in
production.

Output is JSON under `inventory/`. It is the denominator for everything after
it, and every later count is checked against it.

## 2 · Partition

A unit is what one agent handles in one session. Two rules decide the split.

**Small enough to fit.** Every implementation surveyed converges on truncation
regardless of how it finds code — SWE-agent and OpenHands cap at 100 files,
Cline at 48k characters, Goose at 50k. The limit is not what you can find, it
is what a model can be given at once.

**Verifiable alone.** A unit whose correctness cannot be established without
also finishing another unit is not a unit; it is half of one.

For a Play 1 codebase the natural units, coarse to fine:

```
module          if it compiles and tests independently
layer           value / shell / play within a module
endpoint        one route, its action, its template
entity          one model and its query call sites
```

Prefer the coarsest that still fits. Fewer units means fewer seams, and seams
are where drift lives.

## 3 · Order

Dependencies first, and the order is not a matter of taste.

```
value   →  no framework coupling; moves as ordinary Java; JUnit comes with it
shell   →  depends on value; injection changes; few tests
play    →  depends on both; rewritten rather than moved
```

Value first because it is where the tests are, and a migrated value layer with
green tests is the first real oracle the project has. Play last because
rewriting against a moving target is doing the work twice.

Between modules, topological order over the api layer. Where that layer is
tightly coupled — mutual dependencies, no clean leaves — the sequence changes
rather than the plan: move **every module's value and shell together**, since
those are ordinary Java whose coupling to each other does not matter, and take
the play layer module by module afterwards.

## 4 · Dispatch

**One unit, one fresh session.** Not a long session working through a list.

Long-horizon execution has a measured failure mode — self-conditioning: a
model whose context holds its own earlier errors becomes *more* likely to err
again, and larger models do not escape it
([arXiv:2509.09677](https://arxiv.org/abs/2509.09677)). A failed unit must not
stay in the room.

What the session receives:

```
the unit            files, and what is being asked of them
the contracts       every accepted one — this is the consistency anchor
an exemplar         the nearest already-migrated unit, if there is one
the gates           the commands that will judge the result
the prohibitions    test files are read-only; unhandled cases stop
```

The exemplar does more than the contracts can. A contract says dates are ISO;
an exemplar shows what a finished controller in this project looks like. Style
converges by example more reliably than by rule.

Parallel dispatch is allowed. What is shared is the frozen contract set; what
is never shared is a decision made mid-flight. Agents do not talk to each
other — that is the arrangement Cognition names as the source of drift, and
Anthropic excludes for coding work at roughly 15× the token cost.

## 5 · Gate

In order. Each is cheap; the sequence is what makes the output trustworthy.
This is Google's arrangement, where the LLM generates and the compiler and
tests arbitrate — 69% of edits machine-written, and not one of them trusted
because the machine said so.

```
1  files changed      did it touch anything outside its unit?
2  test files intact  did it modify a test?          ← hard stop
3  compiles           mvn -q compile
4  tests pass         mvn test
5  coverage           within 5pp of before
6  behaviour          old system and new, same input, compared
```

**Gate 2 is not a formality.** Agents forbidden from editing tests scored
41.5% against 22.8% — the presence of the option suppresses the fix
(RepoRescue). Enforce it with file permissions, not instructions.

**Gate 5 catches the subtle version.** Legitimate refactoring moves coverage
under 2.5pp; reward hacking moves it further and more erratically. FreshBrew
calibrated 5pp over 50 migrations. The failure it catches looks like this: a
model met a `NoSuchBeanDefinitionException`, the exception was swallowed by an
internal event publisher, the tests stayed green, and the agent declared
success. Business logic dead, suite passing. Only the coverage drop showed it.

**Gate 6 is the only one that answers the real question.** The rest establish
that the code runs. Whether it does what the old code did needs the old code,
which is why the old system stays alive.

A failure returns the unit to the queue with the gate output attached — and
into a **fresh** session. Retrying inside the failed session is the
self-conditioning case.

Three failures on the same gate stops being a retry and becomes a finding:
either the contracts do not cover this, or something about the unit is not
what the inventory said.

## 6 · Record

Per unit: which gates passed, what changed, which contracts applied, how many
attempts.

The contract application is the part that earns its keep. When a contract is
superseded, this record is the rework queue — without it, revising a decision
means re-examining everything.

## What the pipeline never does

- **Decide what exists.** That is enumeration's job, and its output is checked
  against itself.
- **Ask a model to judge its own work.** Intrinsic self-correction degrades
  performance ([arXiv:2310.01798](https://arxiv.org/abs/2310.01798)); every
  gate is external.
- **Let an agent past an unhandled case.** It stops and drafts a contract. The
  alternative is a local answer that contradicts thirty other local answers.
- **Run a unit against proposed contracts.** Accepted only.
- **Batch failures into one long session.** Each retry is fresh.

## Where this is honest about itself

There is no published account of an agent completing a cross-framework
migration at this scale. The best benchmark figure for a *simpler* task —
Java 8→17, same framework — is 52.3% end-to-end, declining with dependency
count and project size (FreshBrew). Whole-repository coordination is where
agents currently fail outright.

So the pipeline is not a solved method. It is an arrangement that keeps the
parts with evidence behind them — deterministic enumeration, isolated units,
external verification, decisions in files — and gives the parts without
evidence somewhere to fail visibly rather than quietly.
