# What is in this codebase

`samples-and-tests/booking`, the Play 1 booking sample. Surveyed as a trial of
the survey itself — the codebase is small enough that every figure can be
checked by hand, which is the point.

## In one paragraph

Eight Java files, 358 lines, eleven Groovy templates, three JPA entities.
Every endpoint renders a page; **none returns JSON**, so nothing here is
already an API. There is no `play/shell/value` layering — business logic sits
in the controllers and the entities, mixed with framework calls. Both tests
extend Play's own base classes, so neither survives a move to Spring as-is.
For its size this is the *hardest* shape a Play 1 codebase can have: no API
surface to build on and no test that runs off the framework.

## What could not be determined

- **Whether `${page+1}` and 24 other expressions are data dependencies.**
  `scan-template.py` reads property paths; arithmetic and ternaries it reports
  rather than interprets. 25 expressions across 11 templates are listed by
  file and line but not classified. Some are certainly pagination arithmetic
  on a value the action already passed; some may not be.
- **What the catch-all route reaches.** `*  /{controller}/{action}` dispatches
  by reflection at request time. Here the named routes and the public
  controller methods happen to match exactly (12 each, verified below), so
  nothing is hidden — but that is a fact about this codebase, not a property
  of the parser.
- **Whether the 7 template method calls are formatting or queries.**
  `booking.total.formatCurrency()` reads as formatting; confirming that
  requires reading the entity, which this survey did not do.

## Shape

```
[counted] 8 Java files, 358 lines
  find . -name "*.java" | wc -l
  find . -name "*.java" | xargs wc -l | tail -1

[counted] 11 Groovy templates
  find app/views -name "*.html" | wc -l

[counted] 0 layer directories
  find . -type d \( -name value -o -name shell -o -name play \)
```

No `play/shell/value` separation. Controllers call entities directly and
entities carry Play's persistence; there is no layer that moves unchanged.

## Routes

```
[counted] 15 lines, all parsed, 0 unparsed
  tools/parse-routes.py conf/routes
```

- **13** named endpoints
- **1** static mount (`/public/` → `staticDir:public`) — becomes a resource handler
- **1** dynamic dispatch (`*  /{controller}/{action}`)

[counted] The dynamic route hides nothing here: 12 public controller methods,
12 named in routes, identical sets — checked by comparing
`grep -oE "public static .* [a-zA-Z0-9_]+\(" app/controllers/*.java` against
the parsed actions. Worth repeating on any codebase; a mismatch is endpoints
that would migrate to nothing.

### What the endpoints answer with

```
[counted] renderJSON: 0     render(): 11     redirect: 0
  grep -c in app/controllers/*.java
```

**Every endpoint renders a page.** Nothing here is already an API, so every
one needs both an API designed for it and a React page to consume that API.
This is the split that most shapes the work, and here it falls entirely on
the expensive side.

## What the pages need

```
[counted] 72 field reads, 7 method calls, 16 reverse routes, 25 unresolved
  tools/scan-template.py app/views/**/*.html
```

| template | fields | calls | reverse | unresolved |
|---|---|---|---|---|
| Application/index | 0 | 0 | 1 | 2 |
| Application/register | 12 | 0 | 1 | 4 |
| Hotels/book | 19 | 0 | 3 | 8 |
| Hotels/confirmBooking | 14 | **5** | 1 | 0 |
| Hotels/index | 6 | **2** | 0 | 1 |
| Hotels/list | 6 | 0 | 1 | 1 |
| Hotels/settings | 5 | 0 | 1 | 2 |
| Hotels/show | 7 | 0 | 1 | 0 |
| errors/404, errors/500 | 2 | 0 | 0 | 2 |
| main | 1 | 0 | 7 | 5 |

[read] The 7 method calls are all formatting — `booking.total.formatCurrency()`,
`booking.checkinDate.format()` — at `Hotels/confirmBooking.html:33,36,37,40,41`
and `Hotels/index.html:94,95`. No lazy relation is walked in any template
here, which is why this sample is easier than a real codebase in this one
respect.

[read] `Hotels/list.html` reads six fields off each hotel — `name`, `address`,
`city`, `state`, `country`, `zip` — while the entity has seven. A DTO built
from the entity would carry a field the page never shows; one built from the
template carries exactly what it needs. This is the whole reason the templates
are scanned.

**16 reverse routes** — `@{show(hotel.id)}` and friends. These are URLs built
from the routes table, not calls. They are the pages' exits: each one is a
navigation path a React app has to keep.

## Framework coupling

```
[counted] by import, across all 8 files
  grep -rho "^import play\.[a-zA-Z.]*" --include="*.java" .
```

| import | count |
|---|---|
| `play.data.validation.*` | 5 |
| `play.test.*` | 3 |
| `play.mvc.*` | 3 |
| `play.db.jpa.*` | 3 |
| `play.mvc.Http.*` | 1 |
| `play.jobs.*` | 1 |
| `play.*` | 1 |

⚠ **This counts import lines only.** A fully-qualified `play.Play.configuration.get(…)`
would not appear. The second check —
`grep -rn "play\.[A-Z]" --include="*.java" . | grep -v "^import"` — is the one
that closes that gap, and on a codebase with a value layer it is the check
that decides whether that layer really is framework-free.

Every import here has one Spring replacement:
`play.data.validation` → `jakarta.validation`, `play.jobs` → `@Scheduled`,
`play.db.jpa` → Spring Data. Grouping by API is what turns N edits into a
handful of decisions.

## Entities

```
[counted] 3 classes extending Model, 10 static query call sites
```

Ten call sites is the size of the change, not the count of problems. Play's
`find("byEmailAndActive", …)` maps onto Spring Data's
`findByEmailAndActive(…)` almost exactly. What changes is that static Active
Record becomes an injected repository, so each of the ten call sites and all
test setup moves with it.

## Tests

```
[counted] 2 test files, 2 bound to Play, 1 uses Fixtures
```

**Both tests extend Play's base classes, so neither runs under Spring.** They
are specifications to be re-expressed, not a suite to be ported. There is no
plain-JUnit test on framework-free code here, which means this codebase has
**no working oracle** — nothing that can say a migrated module still behaves
as it did.

That is the most consequential fact in this survey. Everything else is a
question of effort; this one is a question of whether correctness can be
checked at all.

## What follows from this

[inferred] Three things, each resting on facts above:

**The oracle has to be built before anything moves.** Both tests are bound to
Play and there is no framework-free test. Until something can answer "does the
new code behave like the old", a migration produces code that compiles and
nothing more. The old application still runs, which makes response comparison
the cheapest oracle available.

**Every endpoint is expensive.** Zero `renderJSON` means no endpoint is
already an API. Each of the 13 needs an API designed from what its template
reads — which the template scan now provides — and a React page to consume it.

**The template scan is load-bearing, not supplementary.** `Hotels/list`
needs six of seven entity fields; `confirmBooking` calls five formatting
methods that decide whether the API returns numbers or strings. Neither fact
is visible in the Java. An API designed from the controllers alone would be
wrong in both directions — missing fields and carrying unused ones.
