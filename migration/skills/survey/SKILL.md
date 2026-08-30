---
name: survey-play1
description: Establish what a Play 1.x codebase contains before anyone decides what to do with it — size, languages, modules, every route, what each page needs, framework coupling, the data model, and whether anything can verify a migration. Six probes do the counting; this says how to run them and how to report what comes back.
---

# Finding out what is there

Nobody holds a complete picture of a codebase this size, and the person asking
for this survey least of all — that is why they are asking. So do not ask them
for facts that are in the source. Go and read it.

The probes do the counting. Your job is to run them, read what they report,
and write it up in a form someone can check. What they cannot answer — and
there is a lot — goes in its own section rather than being smoothed over.

## Say how you know

Every claim carries its source, because a reader cannot tell a sound number
from a plausible one without it:

```
[counted]   a probe or command produced this; it is named
[read]      read in the source; the file and line are given
[inferred]  worked out from those; the reasoning is shown
```

There is no fourth label for a guess. If it would be a guess, it belongs in
**What could not be determined**, which is a section of the report and not a
failure of it.

## The probes

All six live in `/Users/kangyi/projects/QHarness/migration/probes/play1/`.
Each takes `--format=md` for a readable table, or nothing for JSON. Run them
from the application root.

```bash
P=/Users/kangyi/projects/QHarness/migration/probes/play1

python3 $P/scan-modules.py .                       # size, languages, modules
python3 $P/parse-routes.py conf/routes             # every route
python3 $P/scan-template.py app/views/**/*.html    # what each page needs
python3 $P/scan-imports.py app                     # framework coupling
python3 $P/scan-models.py app                      # entities and query sites
python3 $P/scan-tests.py .                         # what can verify a migration
```

Together they take under a second on a real application. There is no reason to
run a subset.

**Exit code 3 means a probe met something it could not read**, and it lists
what. That is a finding to carry into the report, not a failure to retry
around — three unparsed lines named is a small problem, three dropped silently
is three endpoints that vanish from the migration and reappear in production.

**Large output goes to a file.** A scan of a thousand templates emits over a
megabyte, and the harness replaces it with
`[output truncated; full output: /var/…]`. When that happens, say so and read
what you need from the file rather than summarising what you did not see.

## What each probe answers

**`scan-modules`** — how big, in what languages, split into what. It sets
vendored code aside: a jQuery build is thirty thousand lines nobody migrates,
and a total that counts it describes the download. For each module it names
the entities, controllers and scheduled jobs, which is the closest a count
gets to saying what a module is *for* — `PriceTableUpdaterJob` suggests a
domain in a way that "4 jobs" does not. Treat those names as a hint, and label
anything concluded from them `[inferred]`.

**`parse-routes`** — every route, with three things that matter more than the
total. A **catch-all** (`* /{controller}/{action}`) means the routes file is
not the endpoint list: every public controller method was reachable, so count
those too and report both numbers. A **module include** (`module:crud`) means
routes live in a file this did not follow. **Static mounts** are resource
handlers, not endpoints.

**`scan-template`** — the part no general tool provides. A Play 1 action
passes local variables to its template by name, and the template may then call
methods on them: `${user.orders.size()}` is a database query appearing nowhere
in the Java. An API designed from the controller alone will be missing exactly
the fields the page needs.

Report the method calls separately from the field reads. They divide into
formatting (`date.format()`) and business logic executing in a view
(`executor.getCommandLineWithDefault()`), and the second kind is a decision
someone has to make.

`@{Hotels.show(id)}` is a **URL**, not a call. The probe classifies it
correctly; do not undo that in the write-up.

**`scan-imports`** — how much code touches the framework, grouped by API,
which is what turns hundreds of edits into a dozen decisions. It runs two
checks because the cheap one is half the answer: `import play.…` misses a
fully-qualified `play.db.jpa.FileAttachment` used inline.

**`scan-models`** — the entities, their relations, and every static query call
site. The call-site count is the size of the change: each becomes a repository
call, so each file holding one gains an injected dependency and every test
setting up an entity statically sets up a repository instead.

It also reports whether the entities rely on Play's `PropertiesEnhancer`.
Public fields with no accessors means the getters are woven in at compile time
and have to be written by hand on the way out; private fields with accessors
already present means that work does not exist. The two sample codebases give
opposite answers, so this is worth reading rather than assuming.

**`scan-tests`** — and this one answers a different kind of question. Every
other probe measures effort; this one measures whether correctness can be
checked at all.

A test extending `UnitTest` or loading `Fixtures` is bound to Play and will
not run under Spring — a specification to re-express, not a suite to carry
across. A plain JUnit test on framework-free code runs unchanged and is the
cheapest oracle a migration can have. **A codebase with none of the second
kind cannot verify anything**, and that belongs near the top of the report,
not in a table at the bottom.

## The report

Ordered so a reader who stops after the first page still has the most
important thing.

```markdown
# What is in this codebase

## In one paragraph
Enough to orient someone: how big, how organised, and the one or two facts
that will most shape what happens next.

## What could not be determined
First, not last. A reader needs the shape of the gaps before trusting
anything above them. Each with what it would take to answer.

## Size and languages          [counted], vendored code separated
## Modules                     what each contains, by name
## Routes                      total, catch-all, module includes
## What the pages need         fields, and the method calls separately
## Framework coupling          by API, with the limits of the check
## Entities                    count, query sites, enhancer or not
## Tests                       framework-free vs Play-bound — say which

## What follows from this
Not a plan. The facts point somewhere, and saying where is useful as long as
it is labelled [inferred] and the reader can see what it rests on.
```

## What not to do

- **Do not ask for facts that are in the code.** Module count, route count,
  test style — read them. Ask only what the code cannot answer: why they are
  migrating, whether the old system still runs, how they want to be involved.
- **Do not report a total without its denominator.** "About 800 routes" is
  unusable; "812 parsed, 3 unparsed, 1 module include not followed" can be
  acted on.
- **Do not let an inference wear the same voice as a count.** The reader
  spends their attention on the inferences and cannot do that if everything
  sounds equally certain.
- **Do not summarise output you did not see.** If the result was truncated,
  say so and read the file. A truncated result reported as if fully read
  produces something that looks complete and is not.
- **Do not skip a probe because another one seems to cover it.** They overlap
  in what they read and not in what they answer.
