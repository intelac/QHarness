# BionimbuzWeb

A Play 1.5.3 web application, read by script and reported here. Produced as a
worked example of what a project reader produces — every figure carries the
command behind it, so a reader can check any of them in seconds rather than
trusting the whole.

**Three labels, and they mean different things.** `[counted]` came from a
command, shown. `[read]` was read in the source, at the file and line given.
`[inferred]` was worked out from those, with the reasoning shown. There is no
label for a guess: what could not be determined has its own section, near the
top, because knowing the shape of the gaps is what makes the rest usable.

## In one paragraph

7,616 lines of Java across 73 files, 64 Groovy templates, 103 routes. Four
packages — `models` (31 files, 3,956 lines), `controllers` (23), `common`
(15), `jobs` (4). Twenty-six JPA entities, over half the Java in the project,
reached through 61 static query call sites. Built on Play's `crud` and
`secure` modules, which supply roughly a third of the template tags. Three
test files.

## What could not be determined

- **How much of the UI is CRUD scaffolding.** `#{crud.custom}` appears 68
  times and `#{crud.form}` 21 across the templates, but the module's own
  templates were not read, so what those tags render is unknown. A migration
  estimate that counts these as hand-written pages would be wrong; one that
  counts them as free would also be wrong.
- **What `getCommandLineWithDefault()` does.**
  [read] `views/executors/details.html` calls it twice on
  `executorSelected`. It is business logic executing in a view, and whether it
  is a getter or a computation was not established — the method was not read.
- **Whether the three test files run.** [counted] none extend `UnitTest` or
  `FunctionalTest`, which is what would bind them to Play. That makes them
  *candidates* for surviving a move to Spring; nothing here ran them.
- **The 103 unresolved template expressions.** Ternaries and method calls the
  scanner reports rather than interprets. Each is named with its file and
  line; none was classified.

## Shape

```
[counted]  73 Java files, 7,616 lines
  find app -name "*.java" | wc -l
  find app -name "*.java" | xargs wc -l | tail -1
```

| package | files | lines |
|---|---|---|
| `models` | 31 | 3,956 |
| `controllers` | 23 | 2,226 |
| `common` | 15 | 769 |
| `jobs` | 4 | 665 |

[inferred] The weight is in `models` — more than half the Java, and more than
`controllers` and `common` together. In a Play 1 application that usually
means the entities carry behaviour rather than only fields, since Play's
`Model` base class makes an entity the natural place to put a query. Worth
checking before planning around it; this rests on the line count and the
framework's habits, not on having read the entities.

There is no `play`/`shell`/`value` layering. Framework calls, IO and logic sit
together in the same classes, which is the ordinary Play 1 arrangement and the
one that makes a migration expensive: nothing moves without being untangled
first.

## Routes

```
[counted] 103 lines, all parsed, 0 unparsed
  probes/play1/parse-routes.py conf/routes
```

101 named endpoints, 1 static mount, 1 dynamic dispatch.

[read] Controllers are in dotted packages — `adm.HomeController.index`,
`adm.UserController.list`. Play allows this and most route parsers assume the
flat form; this one splits on the last dot, which is why the count is 103 and
not a smaller number with silent losses.

[read] The `secure` module supplies its own routes:
`GET /login Secure.login`. Authentication is the module's, not this
application's.

## Templates

```
[counted] 64 templates: 676 field reads, 96 method calls, 102 reverse routes,
          103 unresolved
  probes/play1/scan-template.py app/views/**/*.html
```

The tags, by frequency:

| tag | uses | what it is |
|---|---|---|
| `#{field}` | 117 | form field binding |
| `#{set}` | 98 | template variable |
| `#{if}` | 90 | conditional |
| **`#{crud.custom}`** | **68** | **crud module** |
| `#{ifError}` | 61 | validation display |
| `#{form}` | 48 | form |
| `#{extends}` | 42 | layout inheritance |
| **`#{crud.form}`** | **21** | **crud module** |
| `#{jsRoute}` | 15 | reverse route into JavaScript |
| **`#{crud.pagination}`** | **12** | **crud module** |

[inferred] Roughly a third of tag usage is `crud.*`. Those pages were
scaffolded rather than written, which cuts both ways for a migration: less
bespoke markup to reproduce, but no source to read for what the page is
supposed to do — the behaviour lives in a module this project only configures.

### The method calls are the interesting part

96 of them, and they split into two kinds that matter differently.

**Framework plumbing** — `field.error.raw()` (61), `_fields.toString()` (12).
These vanish with the templates.

**Business logic in the view** — and these do not:

```
[read] executorSelected.getCommandLineWithDefault()   views/executors/details.html ×2
[read] object.startupScript.substring()               ×1
[read] object.startupScript.length()                  ×1
[read] object.creationDate.format()                   ×3
[read] object.priceTableDate.format()                 ×1
```

A Play 1 action passes local variables to its template by name, and the
template may then call methods on them. So the data an endpoint must return
after migration is not derivable from the action — half the answer is in the
view. `getCommandLineWithDefault()` is the clearest case: something is
computed at render time that no API contract currently describes.

## Framework coupling

```
[counted] by import line, across app/
  grep -rho "^import play\.[a-zA-Z.]*" --include="*.java" app | sort | uniq -c
```

| import | count |
|---|---|
| `play.db.jpa.GenericModel` | 25 |
| `play.Logger` | 13 |
| `play.i` (i18n) | 12 |
| `play.data.validation.Required` | 12 |
| `play.data.binding.NoBinding` | 12 |
| `play.exceptions.TemplateNotFoundException` | 11 |
| `play.data.binding.Binder` | 10 |
| `play.data.validation.MaxSize` | 9 |
| `play.data.validation.Validation` | 8 |
| `play.db.Model` | 7 |
| `play.data.validation.MinSize` | 5 |
| `play.jobs.Job` | 4 |

⚠ **This counts import lines only.** A fully-qualified
`play.Play.configuration.get(…)` would not appear here. The check that closes
that gap —
`grep -rn "play\.[A-Z]" --include="*.java" app | grep -v "^import"` — was not
run, so this table is a lower bound.

[inferred] The distribution is unusually concentrated: validation
(`Required`, `MaxSize`, `MinSize`, `Validation` — 34 together) and persistence
(`GenericModel`, `Model` — 32) are most of it. Both have close Spring
counterparts. The awkward entries are the smaller ones —
`TemplateNotFoundException` (11) is coupled to Play's template resolution,
which does not survive a move to React at all.

## Entities

```
[counted] 26 classes extending Model or GenericModel
[counted] 61 static query call sites
```

26 entities against 31 files in `models` — nearly every model class is an
entity.

61 call sites is the size of the change, not a count of problems. Play's
`find("byEmailAndActive", …)` maps onto Spring Data's
`findByEmailAndActive(…)` almost exactly. What changes is architectural:
static Active Record becomes an injected repository, so each of the 61 sites
and every test setup moves with it.

## Tests

```
[counted] 3 test files, 0 extending UnitTest or FunctionalTest
```

Three tests for 7,616 lines of Java.

[inferred] Nothing here can say whether a migrated module still behaves the
same. That is not an effort question like the others on this page — it is
whether correctness can be checked at all. The three files not extending
Play's base classes means they *might* run under Spring unchanged, which would
make them the only oracle available; that was not verified.

## What this page is not

It says what is in the codebase. It does not say what to do about it, and the
`[inferred]` lines are the reader's to check rather than conclusions to build
on. The two that would most change a plan — how much of the UI is crud
scaffolding, and whether those three tests run — are both in **What could not
be determined**, and both are answerable in an afternoon by someone who opens
the files.
