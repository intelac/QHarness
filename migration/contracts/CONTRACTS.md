# Contracts

A contract is one decision, decided once, and then binding on every unit that
meets the case it covers.

They exist because a thousand endpoints cannot each be reviewed, but the dozen
decisions those endpoints instantiate can. Review happens here. Below this
layer the agent applies what was decided; it does not decide.

They also exist because parallel agents cannot see each other's choices.
Cognition's account of why multi-agent systems drift is that *actions carry
implicit decisions* — each agent settles naming, DTO shape, error handling its
own way, and the divergence is invisible until the pieces meet. A frozen
contract is what removes the implicit part.

## One contract

```markdown
# Dates crossing the API boundary

status: proposed | accepted | superseded
decided-by: <name>, <date>
supersedes: <contract-id, if any>

## The case

Play 1 templates format dates in the view — `${booking.checkinDate.format()}`
appears 4 times across 2 templates. React needs the value; the question is
who formats it.

## Options

**A. Server returns ISO 8601, client formats.**
The client knows the viewer's locale and the server does not. Dates stay
comparable and sortable in transit.
Cost: every consuming component needs a formatting helper.

**B. Server returns a formatted string.**
Matches what the old template emitted, so the page is a closer copy.
Cost: the value is no longer a date. Sorting, filtering and arithmetic on the
client all become string operations, and the server has to guess a locale.

## Decision

**A.** Booking dates are compared (check-in before check-out) and that
comparison belongs in the client, which rules out B.

## How it applies

Any `DateTime` or `Date` field crossing the API is serialised ISO 8601, UTC.
A template calling `.format()` on a date does **not** make it a string field —
it makes a formatting requirement for the React component.

## Where this was already applied

- `endpoints/hotels-confirm-booking` — checkinDate, checkoutDate
- `endpoints/hotels-index` — checkinDate, checkoutDate
```

The last section is the one people skip and the one that pays. When a
contract later turns out to be wrong, it is the list of everything that has to
change.

## Which questions become contracts

A question is contract-shaped when its answer will be needed more than once
and there is no way to derive it from the old code. Two tests, both required.

The recurring ones in a Play 1 migration:

| Question | Why the old code cannot answer it |
|---|---|
| What does an API return? | The action rendered a page; the split between server and client did not exist |
| Where does a permission check live? | A `canEdit` flag in JSON is a server decision surfacing in the view — legitimate, or a leak, depending on what you decide |
| Pagination shape | Play's `fetch(page, size)` is one convention; the wire format is a new choice |
| Error shape | `renderError` and Play's error templates have no single Spring counterpart |
| Date and money formatting | Templates formatted in the view; the boundary moves |
| What replaces `Fixtures` | Nothing in Spring reads Play's fixture format, and its `Company(google)` cross-references are read by nothing at all |
| Transaction boundaries | Play gave one per request; Spring can be finer, and the choice is not implied by the old code |
| Naming | `UserController` or `UsersController`; `/api/users` or `/users` — trivial each time, corrosive when forty modules answer differently |

The last row looks too small to write down. It is exactly the kind of implicit
decision that produces forty dialects.

## What is not a contract

A mechanical correspondence is not a decision. These go in the migration
rules, applied without asking:

```
conf/routes line        →  @GetMapping / @PostMapping
{<[0-9]+>id}            →  {id:[0-9]+}
@Before / @After        →  HandlerInterceptor preHandle / postHandle
@Finally                →  afterCompletion(ex)
only / unless           →  addPathPatterns / excludePathPatterns
find("byEmailAndActive")→  findByEmailAndActive()
@Every / @On            →  @Scheduled(fixedRate=) / @Scheduled(cron=)
@Required / @MaxSize    →  @NotNull / @Size(max=)
validation.hasErrors()  →  BindingResult.hasErrors()
```

Play's static finders map onto Spring Data unusually cleanly — the whole
comparator set (`And`, `Or`, `Like`, `Between`, `LessThan`, `IsNull`, `In`) is
covered. `@On` already uses Quartz cron, so expressions port nearly verbatim.

**What is not mechanical about that row** is architectural: static Active
Record becomes an injected repository, so every call site and every test setup
moves. The translation is free; the restructuring is not.

## Status, and what it gates

```
proposed    the agent drafted it, or a person sketched it. Not binding.
accepted    a person decided. Binding on every unit from here on.
superseded  replaced. The replacement lists what has to be revisited.
```

**No code is generated from a proposed contract.** The whole arrangement rests
on this: an agent that may act on its own draft has quietly become the
decider, and the review layer is decoration.

## Superseding

Contracts turn out wrong. The cost is bounded only if the reversal is
mechanical, which is why *Where this was already applied* is not optional
bookkeeping.

```
1. Write the new contract, cite the old one in `supersedes`
2. Mark the old one superseded
3. Take its "already applied" list — that is the rework queue
4. Re-run those units
```

Nothing else needs deciding again. That is the property being bought.

## Drafting, for the agent

Draft when a unit meets a case no contract covers. Do not decide it, and do
not proceed past it.

A useful draft names the case in terms of what was found — file and line —
gives two or three real options rather than one dressed as inevitable, states
each cost concretely, and says which one you would pick and why. The
recommendation is wanted; making it is not.

Three labels, and they mean different things to a reviewer:

```
[read]      in the source, at this file and line
[inferred]  worked out from what was read; the reasoning is shown
[assumed]   no evidence; needs confirming before anything rests on it
```

A reviewer's attention goes to `[assumed]` first. Presenting one as `[read]`
wastes the review, and the review is the only place a wrong decision gets
caught before it is instantiated a hundred times.
