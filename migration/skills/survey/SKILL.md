---
name: survey-play1
description: Establish what a Play 1.x codebase actually contains, before anyone decides what to do with it — module structure, every route, every template's data needs, every dependency on the framework, and an explicit account of what could not be determined. Use as the first step on a Play 1 codebase nobody has a complete picture of.
---

# Finding out what is there

Nobody has a complete picture of a codebase this size, and the person asking
for this survey least of all — that is why they are asking. So do not ask them
what the project contains. Go and find out.

The one thing worth more than any number here is knowing **what the number is
out of**. A migration that misses two hundred routes fails silently; a
migration that mishandles two hundred routes fails loudly and gets fixed. So
every count in this survey must be a count of something enumerable, and
anything that could not be enumerated must be said so, by name.

## The rule that makes this survey worth reading

**Say how you know.** Every figure carries the command that produced it, and
every limit of that command is stated in the same breath:

```
Modules: 47
  from: directories under app/ containing a build.properties
  ⚠ a module laid out differently would not be counted — checked for
    stragglers with `find app -name "*.java" -not -path "app/*/*"`: none
```

Without the second and third lines a reader cannot tell a real 47 from a 47
that missed a directory. With them, they can check the one thing that matters
in ten seconds.

Three labels, used on every claim:

```
[counted]   a command produced this; the command is shown
[read]      read in the source; the file and line are shown
[inferred]  worked out from the above; the reasoning is shown
```

There is no fourth label for guesses. If it would be a guess, it goes in
**What could not be determined** instead, which is a section of this report,
not a failure of it.

## What to establish

Work outward: shape first, then contents, then the framework coupling that
decides how hard each part will be to move.

### 1. Shape

How many modules, and how each is laid out. Do not assume the layout is
uniform — find the exceptions, because exceptions are where migration plans
break.

```bash
find . -name "*.java" | wc -l
find . -name "*.java" | xargs wc -l | tail -1
ls -d app/*/ 2>/dev/null
find . -type d -name value -o -type d -name shell -o -type d -name play
```

A codebase organised as `module/{play,shell,value}` has already done the
hardest part of a migration — separating business logic from the framework.
Confirm the layers really are what they claim: a `value` directory that
imports `play.*` is not a value layer, and finding that out now is worth more
than any other number in this report.

### 2. Routes

```bash
tools/parse-routes.py conf/routes --format=md
```

It reports what it could not parse; carry that through rather than rounding it
away. Three things it flags matter more than the total:

- **catch-all** — `*  /{controller}/{action}` means the routes file is not the
  endpoint list. Every public method on every controller was reachable. Count
  those methods too and report both numbers.
- **module includes** — `module:admin` pulls in another routes file that this
  did not follow. Find and parse those as well, or say they were not.
- **static mounts** — not endpoints; they become resource handlers.

### 3. What each endpoint returns

The single most useful split in the whole survey, because it separates the
work that is nearly done from the work that has not started:

```bash
grep -c "renderJSON" app/controllers/*.java     # already an API
grep -c "render(" app/controllers/*.java        # renders a page
grep -c "redirect" app/controllers/*.java
```

An endpoint that already answers with JSON is close to a Spring
`@RestController`. One that renders a Groovy template needs a React page and
an API designed for it, which is a different order of work. Report the ratio.

### 4. What the pages need

```bash
tools/scan-template.py app/views/**/*.html --format=md
```

This is the part no general tool provides, and the reason it matters is
specific: a Play 1 action passes local variables to its template by name, and
the template may then call methods on them. `${user.orders.size()}` is a
database query that appears nowhere in the Java. An API designed from the
controller alone will be missing exactly the fields the page needs.

Report per template: the fields read, the **method calls** (flag these — they
are either formatting or a lazy relation being walked, and the difference
matters), and the reverse routes.

**`@{Hotels.show(id)}` is a URL, not a call.** Never record it as a call
edge; every code-graph tool does, and a false edge is worse than a missing one
because it gets believed.

### 5. Coupling to the framework

This decides how much of the codebase can move unchanged, so it is the number
the migration plan is built on.

```bash
# Per layer, which framework APIs are used and how often
grep -rho "^import play\.[a-zA-Z.]*" --include="*.java" . | sort | uniq -c | sort -rn

# The question worth answering first
grep -rl "^import play\." --include="*.java" . | grep "/value/"
```

An empty result for that last one means the value layer carries no framework
dependency and moves as ordinary Java. **State the limit of that check in the
same breath**: it reads `import` lines only, so a fully-qualified
`play.Play.configuration.get(…)` would not appear. Run the second check and
report both:

```bash
grep -rn "play\.[A-Z]" --include="*.java" */value/ | grep -v "^import"
```

Each distinct framework API is a class of work with one replacement, not one
problem per call site. Group by API and count; that grouping is what turns
several hundred edits into a dozen decisions.

### 6. Entities and their query sites

```bash
grep -rl "extends Model\|extends GenericModel" --include="*.java" .
grep -rn "\.\(find\|findAll\|findById\|count\|delete\)(" --include="*.java" . | wc -l
```

Play's static finders map unusually cleanly onto Spring Data — `find("byEmailAndActive", …)`
becomes `findByEmailAndActive(…)` — so the translation is not the cost. The
cost is architectural: static Active Record becomes injected repositories, so
every call site and every test setup changes. Count the call sites; that count
is the size of the change.

### 7. Tests

```bash
find . -path "*/test/*" -name "*.java" | wc -l
grep -rl "extends UnitTest\|extends FunctionalTest" --include="*.java" .
grep -rl "Fixtures\." --include="*.java" .
```

Tests are the only thing that can say a migrated module still behaves the
same, so their shape decides how much of this migration is verifiable at all.

Two kinds, and the difference is the whole point. A test that extends
`UnitTest` or loads `Fixtures` is bound to Play and will not run under Spring
— it is a **specification** to be re-expressed, not a suite to be ported. A
plain JUnit test on the value layer is bound to nothing and should run
unchanged after the move — it is a **working oracle**, and its count is the
best news this survey can carry.

Report both counts separately. Report where they sit: value-layer tests are
worth more than play-layer tests, because that is where they survive.

## The report

Ordered so that a reader who stops after the first page still has the most
important thing.

```markdown
# What is in this codebase

## In one paragraph
Enough to orient someone: how big, how it is organised, and the one or two
facts that will most shape a migration.

## What could not be determined
First, not last. A reader needs to know the shape of the gaps before they
trust anything above them.
- Routes in module `admin` — `conf/routes` includes `module:admin`, which
  was not found on disk. N routes unaccounted for.
- Templates using `#{myTag}` — a custom tag whose definition was not found;
  its data needs are unknown. Affects N templates.

## Shape
[counted] figures, each with its command.

## Routes
Total, and the split by what they answer with. The catch-all note if present.

## Framework coupling
By layer, by API, with counts. The value-layer question answered explicitly
with both checks and their limits.

## Entities
Count, and the number of static query call sites.

## Tests
The two kinds, counted separately, with where they live.

## What follows from this
Not a plan — the survey does not decide anything. But the facts point
somewhere, and saying where is useful as long as it is labelled [inferred]
and the reader can see the facts it rests on.
```

## What not to do

- **Do not ask the person for facts that are in the code.** Module count,
  route count, layer structure, test style — go and read them. Ask only about
  things the code cannot answer: why they are migrating, whether the old
  system can still be run, how they want to be involved.
- **Do not report a total without its denominator.** "About 800 routes" is
  unusable; "812 parsed, 3 lines unparsed, 1 module include not followed" is
  actionable.
- **Do not let an inference wear the same voice as a count.** They get
  different labels for a reason: the reader spends their attention on the
  inferences, and cannot do that if everything sounds equally certain.
- **Do not smooth over a parser failure.** Three unparsed lines named is a
  small, fixable problem. Three unparsed lines silently dropped is three
  endpoints that disappear from the migration and reappear in production.
- **Do not stop at the first layer that looks clean.** A value layer with no
  `import play.*` still has to be checked for fully-qualified use. The cheap
  check and the thorough check disagree often enough to be worth both.
