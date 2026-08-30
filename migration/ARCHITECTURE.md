# How this is built, and why

Every structural decision here is the consequence of a measurement or a
published result, not a preference. They are recorded with their evidence
because the tempting alternatives all sound better than they are, and someone
— including whoever wrote this — will eventually want to "simplify" one of
them back into the shape the evidence rules out.

## The shape

```
determinism        enumerate → queue → one unit per fresh session
     ↓                                    no LLM decides what exists
consistency        frozen contracts + already-migrated exemplars
     ↓                                    files, not agent memory
one agent          single ReAct loop, one unit, one session
     ↓
verification       compiler and tests, from outside
```

Four layers, and the boundaries between them are the design. Nothing above the
agent layer asks the model anything; nothing below it is trusted to judge
itself.

## Why enumeration is not the agent's job

Swimm measured Claude Code reading real Medicare COBOL: **24–35% paragraph
coverage, 42% variance across three runs of the same task**, and one 70-line
payment-calculation paragraph missed on all three. Their conclusion is
structural, not a complaint about the model:

> There is no mechanism that guarantees every paragraph gets visited.

An agent exploring a codebase finds what it happens to find. For a migration
that is disqualifying, because **the routes it misses do not announce
themselves** — the work simply arrives incomplete, and nobody knows the
denominator. Scripts enumerate; the agent then works through a list it did not
choose.

This is also what three independent teams in legacy modernisation converged on
— IBM watsonx Code Assistant for Z, Swimm, Mechanical Orchard all put
deterministic static analysis under the LLM rather than beside it.

## Why one unit per fresh session

Long-horizon execution has a measured failure mode: **self-conditioning**. When
a model's context contains its own earlier mistakes, it becomes *more* likely
to make further ones, and scale does not remove the effect
([arXiv:2509.09677](https://arxiv.org/abs/2509.09677), ICLR 2026). The same
work separates planning from execution and finds long-task failure is
predominantly executional — the model knows what to do and errs while doing
it.

So a failed unit does not stay in the room. Each unit starts clean, and what
carries between units is written down rather than remembered.

The corollary is that per-step accuracy compounds: small improvements to a
single unit's success rate extend the reachable task length superlinearly.
Effort spent making one unit reliable is worth more than effort spent making
the loop clever.

## Why the agent never judges its own output

Intrinsic self-correction — a model reviewing its own work without external
signal — does not work, and can make things worse:

> LLMs struggle to self-correct their responses without external feedback, and
> at times, their performance even degrades after self-correction.
> — [arXiv:2310.01798](https://arxiv.org/abs/2310.01798), ICLR 2024

The distinction that matters is *external*. Reflexion's strong HumanEval
result stands on unit tests as ground truth; remove the tests and the loop is
reflection on nothing. So retries here are driven by compiler output and test
results, and "does this look right to you?" is never asked of the thing that
wrote it.

For a Play 1 codebase this puts the oracle question first: value-layer JUnit
that runs unchanged after the move is the cheapest ground truth available, and
a codebase without it needs one built before migration starts, not after.

## Why not multiple agents

The obvious design — one agent per module, run them in parallel — is the one
the evidence most consistently rules out.

Anthropic, who build multi-agent systems and published a successful one, are
explicit that coding is the wrong domain: such tasks "involve fewer truly
parallelizable tasks", and multi-agent runs at roughly **15× the token cost of
chat**. They exclude scenarios where agents share context or depend heavily on
one another — which describes a migration whose modules share an api layer.

Cognition names the failure directly: *actions carry implicit decisions, and
conflicting decisions carry bad results*. Parallel agents cannot see each
other's choices, so each settles naming, DTO shape, and error handling its own
way. Forty modules migrated in parallel is forty drifting dialects, and the
drift is invisible until they meet.

The Berkeley MAST taxonomy adds that this is not fixable by better
orchestration: 14 failure modes over 150 annotated traces, with the conclusion
that gains on benchmarks are often small and the failures "require more
sophisticated solutions"
([arXiv:2503.13657](https://arxiv.org/abs/2503.13657)).

**Parallelism is still available** — but over independent units against a
frozen contract, not agents negotiating with each other. The queue can run
several units at once; what none of them may do is decide something the
contract has not already decided.

## Why contracts are files

Anthropic's context engineering guidance names three techniques for long
horizons and ties each to a shape of work: compaction for conversational
back-and-forth, sub-agents for parallel exploration, and **structured
note-taking — external memory the agent re-reads — for iterative development
against milestones**. That is this shape.

There is a second reason, specific to migration. A contract is the unit of
human review. Reviewing a thousand endpoints is not possible; reviewing the
dozen decisions those endpoints instantiate is a morning's work. The contract
is where a person's judgement enters, so it has to be somewhere a person can
read and change.

## Why Google's pipeline is the model

It is the only comparable industrial case: a 32→64-bit identifier migration
across Google's monorepo, 12 months, 595 changes, 93,574 edits.

```
Kythe static index        find every reference — deliberately over-inclusive
rules/regex               sort into migrated / irrelevant / relevant / manual
LLM                       generate the diff, and only that
six gates                 request ok → non-blank → parses → necessary →
                          compiles → tests pass
human                     every change reviewed before submission
```

**The LLM never decides whether its own work is correct.** Compilers and tests
arbitrate. The result: 69% of edits LLM-generated, 25% of changes still fully
manual, about 50% time saved. Not 90% — half.

Two things to take from the honest version of those numbers. The LLM's share
is large but not total, and the remaining quarter never became automatable. And
the gates are cheap individually; it is their sequence that makes the output
trustworthy.

## What each layer must not do

The boundaries only hold if they are stated as prohibitions.

**Enumeration** must not summarise or judge. A route it cannot parse is
reported as unparsed, never dropped. Its output is a denominator; anything
that makes the denominator uncertain defeats it.

**The contract layer** must not be written by the agent alone. It may draft;
the decision is a person's, because the decisions here are about what this
system should become, and no amount of reading the old code answers that.

**The agent** must not exceed its unit, and must not decide anything the
contract left open. Meeting an unhandled case, it stops and says so —
inventing a local answer is exactly how forty dialects begin.

**Verification** must not run on anything the agent can edit. Test files are
read-only to it, and this is load-bearing rather than defensive: agents
forbidden from editing tests scored **41.5% against 22.8%** — the presence of
the cheat suppresses the fix (RepoRescue). A coverage floor catches the subtler
version, since legitimate refactoring moves coverage by under 2.5pp while
reward hacking moves it further and more erratically (FreshBrew's calibrated
5pp threshold).

## What is still unknown

Stated plainly, because the reasoning above is only as good as its footing.

**No published account exists of an agent completing a cross-framework
migration at this scale.** Every industrial success is a version upgrade
inside one framework, where every construct has a determinate counterpart. The
best benchmark number for that easier task is 52.3% end-to-end
(FreshBrew, Gemini 2.5 Flash, Java 8→17), and success rates decline
monotonically with dependency count and project size. Whole-repository
coordination — RepoRescue's L4 — is where agents currently fail: of 14 such
cases, Claude Code models passed at most 2.

So this architecture is not a plan that is known to work. It is the shape the
available evidence does not rule out, which is a weaker claim and the honest
one. The parts with real support are narrow and specific: enumerate
deterministically, isolate each unit, verify externally, keep decisions in
files, don't parallelise the agents.
