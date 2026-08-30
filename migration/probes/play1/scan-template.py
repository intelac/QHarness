#!/usr/bin/env python3
"""What a Groovy template actually needs, as JSON.

A Play 1 action passes local variables to its view by name, through bytecode
enhancement, and the template may then call methods on them. So the data an
endpoint has to return after migration is not derivable from the action: half
the answer is in the view, and `${hotel.orders.size()}` is a database query
that appears nowhere in the Java.

No tool reads these. Groovy templates went away with Play 2, and every code
graph built since treats `app/views/*.html` as HTML.

    ./scan-template.py app/views/Hotels/list.html
    ./scan-template.py app/views/Hotels/*.html --format=md
"""
import json
import re
import sys
from pathlib import Path

# ${...} — an expression. The common case is a property path, but it can be
# any Groovy, so the raw text is kept alongside what could be read from it.
EXPR = re.compile(r"\$\{([^}]*)\}")

# @{...} and @@{...} — reverse routing. `@{show(hotel.id)}` is a URL built
# from a route, NOT a call to show(). Every code graph tool records it as a
# call and draws an edge that does not exist.
REVERSE = re.compile(r"@@?\{([^}]*)\}")

# &{'key'} — a message lookup against conf/messages.
MESSAGE = re.compile(r"&\{\s*['\"]([^'\"]+)['\"]")

# #{tag ...}, #{/tag}, #{tag /} — Play's template tags.
TAG_OPEN = re.compile(r"#\{(?P<name>[a-zA-Z][\w.]*)\s*(?P<args>[^}]*?)/?\}")
TAG_CLOSE = re.compile(r"#\{/(?P<name>[a-zA-Z][\w.]*)\}")

# `as:'hotel'` in #{list hotels, as:'hotel'} — the loop variable is bound
# here, so `hotel.name` further down is not a variable the action passed.
AS_BINDING = re.compile(r"\bas\s*:\s*['\"](\w+)['\"]")

# A property path: `hotel.name`, `user.orders.size()`. The head is the root
# object; a trailing `(` means the last segment is a method call, which is
# the case worth flagging.
PATH = re.compile(r"\b(?P<root>[a-z_]\w*)(?P<rest>(?:\.\w+)+)(?P<call>\s*\()?")

# `${title}` — the whole expression is one name. This is the commonest thing
# a template does and the first version of this script missed all of it: the
# path pattern above requires a dot, so a bare variable matched nothing and
# was filed as unreadable. On the Play test suite that was 121 of 170
# "unresolved" expressions — a scanner reporting a gap where the answer was
# plainly there.
BARE = re.compile(r"^[a-z_]\w*$", re.IGNORECASE)

# Groovy and template built-ins that are not data from the action.
BUILTINS = {
    "play", "request", "session", "flash", "params", "errors", "lang",
    "messages", "out", "it", "_response_encoding", "_", "this",
}


def scan(text: str) -> dict:
    bound = set()          # loop variables, bound by the template itself
    for match in AS_BINDING.finditer(text):
        bound.add(match.group(1))
    # `#{list hotels}` with no `as:` binds `_hotel`… but the common default
    # is plain `item`, and Play also exposes `_index`, `_isLast` and friends.
    bound |= {"item", "_index", "_isFirst", "_isLast", "_parity", "_size"}

    accesses, expressions, reverses, messages = [], [], [], []
    tags, unresolved = {}, []

    for number, line in enumerate(text.splitlines(), start=1):
        for match in REVERSE.finditer(line):
            inner = match.group(1).strip()
            # `@{Hotels.show(id)}` or `@{show(id)}` — controller optional.
            name, _, args = inner.partition("(")
            reverses.append({
                "line": number,
                "raw": inner,
                "action": name.strip(),
                "args": args.rstrip(")").strip() or None,
                # Said plainly because everything else gets this wrong: this
                # builds a URL. It is not a method call on the controller.
                "kind": "reverse-route",
            })

        for match in MESSAGE.finditer(line):
            messages.append({"line": number, "key": match.group(1)})

        for match in TAG_OPEN.finditer(line):
            name = match.group("name")
            tags.setdefault(name, []).append({
                "line": number, "args": match.group("args").strip() or None})

        for match in EXPR.finditer(line):
            raw = match.group(1).strip()
            expressions.append({"line": number, "raw": raw})

            found = False
            for path in PATH.finditer(raw):
                root = path.group("root")
                if root in BUILTINS:
                    continue
                segments = path.group("rest").lstrip(".").split(".")
                accesses.append({
                    "line": number,
                    "root": root,
                    "path": root + path.group("rest"),
                    "leaf": segments[-1],
                    # A method call in a view is the interesting case: it can
                    # be a getter, or it can be a lazy relation being walked,
                    # which is a query the action never made.
                    "isCall": bool(path.group("call")),
                    "loopVariable": root in bound,
                })
                found = True

            # A bare name: the action passed this variable and the template
            # prints it whole. No field is read off it, so `path` is the name
            # itself and there is nothing to walk.
            if not found and BARE.match(raw) and raw not in BUILTINS:
                accesses.append({
                    "line": number, "root": raw, "path": raw, "leaf": raw,
                    "isCall": False, "loopVariable": raw in bound,
                })
                found = True

            # An expression with no readable property path — `${page+1}`,
            # `${x ? a : b}`. Recorded rather than dropped: it may still be a
            # data dependency, and a silent gap is the failure mode here.
            if not found and raw:
                unresolved.append({"line": number, "raw": raw})

    # Every root is recorded, loop variables included. A loop variable is not
    # something the action passed, but the fields read through it are exactly
    # what each element of the collection must carry — `hotel.name` inside
    # `#{list hotels}` is the reason the API returns a name at all. Dropping
    # them leaves the page's real data requirement invisible, which is the
    # one thing this script exists to find.
    roots = {}
    for access in accesses:
        entry = roots.setdefault(access["root"], {
            "fields": set(), "calls": set(), "loopVariable": access["loopVariable"]})
        (entry["calls"] if access["isCall"] else entry["fields"]).add(access["path"])

    return {
        "requires": sorted(n for n, v in roots.items() if not v["loopVariable"]),
        "accesses": accesses,
        "byRoot": {name: {"fields": sorted(v["fields"]),
                          "calls": sorted(v["calls"]),
                          "loopVariable": v["loopVariable"]}
                   for name, v in sorted(roots.items())},
        "reverseRoutes": reverses,
        "messages": messages,
        "tags": {name: len(uses) for name, uses in sorted(tags.items())},
        "unresolvedExpressions": unresolved,
        "counts": {
            "accesses": len(accesses),
            "calls": sum(1 for a in accesses if a["isCall"]),
            "reverseRoutes": len(reverses),
            "unresolved": len(unresolved),
        },
    }


def as_markdown(path: Path, result: dict) -> str:
    lines = ["## %s" % path, ""]

    if result["requires"]:
        lines += ["Needs from the action: %s" %
                  ", ".join("`%s`" % r for r in result["requires"]), ""]

    for root, use in result["byRoot"].items():
        lines.append("**%s**%s" % (
            root, " (loop element)" if use["loopVariable"] else ""))
        for field in use["fields"]:
            lines.append("- `%s`" % field)
        for call in use["calls"]:
            # Flagged, because this is the field an extracted API will be
            # missing: nothing in the Java says the page needs it.
            lines.append("- `%s()` — method call in the view" % call)
        lines.append("")

    if result["reverseRoutes"]:
        lines += ["Reverse routes (URLs built from routes, **not calls**):"]
        lines += ["- `%s` → %s" % (r["raw"], r["action"])
                  for r in result["reverseRoutes"]] + [""]

    if result["tags"]:
        lines += ["Tags: %s" % ", ".join("`#{%s}`×%d" % (n, c)
                                         for n, c in result["tags"].items()), ""]

    if result["unresolvedExpressions"]:
        lines += ["**%d expressions could not be read as property paths** —"
                  " check these by hand:" % len(result["unresolvedExpressions"])]
        lines += ["- line %d: `${%s}`" % (u["line"], u["raw"])
                  for u in result["unresolvedExpressions"]] + [""]

    return "\n".join(lines)


def main() -> int:
    paths = [Path(a) for a in sys.argv[1:] if not a.startswith("--")]
    if not paths:
        print(__doc__, file=sys.stderr)
        return 2

    markdown = "--format=md" in sys.argv
    output, unresolved = [], 0

    for path in paths:
        if not path.is_file():
            print("no such file: %s" % path, file=sys.stderr)
            return 1
        result = scan(path.read_text(encoding="utf-8", errors="replace"))
        unresolved += result["counts"]["unresolved"]
        output.append(as_markdown(path, result) if markdown
                      else {"file": str(path), **result})

    print("\n".join(output) if markdown
          else json.dumps(output, indent=2, ensure_ascii=False))

    # Unreadable expressions are a finding, not a failure.
    return 3 if unresolved else 0


if __name__ == "__main__":
    sys.exit(main())
