#!/usr/bin/env python3
"""Every route in a Play 1 conf/routes, as JSON.

Play 1's routes file is three columns of text — method, path, controller
action — which is why this is the one part of the migration that automates
cleanly. A general code-graph tool reads about 85% of them and invents edges
for the rest; this reads all of them and invents nothing.

    ./parse-routes.py conf/routes
    ./parse-routes.py conf/routes --format=md
"""
import json
import re
import sys
from pathlib import Path

# METHOD PATH CONTROLLER.ACTION, with the action's arguments optional. `*`
# is a method here because Play accepts it as "any verb", and `WS` because
# WebSocket routes use it.
ROUTE = re.compile(
    r"^\s*(?P<method>GET|POST|PUT|DELETE|HEAD|OPTIONS|PATCH|WS|\*)"
    r"\s+(?P<path>\S+)"
    r"\s+(?P<action>[^\s(]+)"
    r"\s*(?P<args>\([^)]*\))?\s*$"
)

# A module include — `*  /admin  module:admin` — pulls in another routes file.
# Recorded rather than followed: which modules a deployment has is its own
# question, and a silent partial read is worse than a named gap.
MODULE = re.compile(r"^\s*\*?\s*(?P<prefix>\S*)\s+module:(?P<module>\S+)\s*$")

# `{<[0-9]+>id}` — a segment with a regex constraint. Spring writes the same
# thing as `{id:[0-9]+}`, so the two parts are captured separately.
TYPED_SEGMENT = re.compile(r"\{<(?P<regex>[^>]+)>(?P<name>[^}]+)\}")
PLAIN_SEGMENT = re.compile(r"\{(?P<name>[^<}][^}]*)\}")


def spring_path(play_path: str) -> str:
    """The same path as Spring writes it."""
    path = TYPED_SEGMENT.sub(lambda m: "{%s:%s}" % (m["name"], m["regex"]), play_path)
    # A trailing catch-all: Play's `/public/` with a `staticDir` target, or a
    # `*` wildcard. Spring's equivalent is `/**`.
    return path.replace("*", "**") if path.endswith("*") else path


def parse(text: str) -> dict:
    routes, includes, unparsed = [], [], []

    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.split("#", 1)[0].rstrip()
        if not line.strip():
            continue

        module = MODULE.match(line)
        if module:
            includes.append({
                "line": number,
                "prefix": module["prefix"] or "/",
                "module": module["module"],
            })
            continue

        route = ROUTE.match(line)
        if not route:
            # Named, not dropped. A route this cannot read is a route the
            # migration would otherwise lose without anyone noticing.
            unparsed.append({"line": number, "text": line.strip()})
            continue

        action = route["action"]
        controller, _, method = action.rpartition(".")
        path = route["path"]

        # `*  /{controller}/{action}  {controller}.{action}` — Play's dynamic
        # dispatch, the last line of most routes files. It is not an endpoint:
        # it reaches every action not named above it, by reflection at request
        # time. Listing it as a route puts a class called `{controller}` on the
        # migration checklist; leaving it out silently loses the fact that
        # unlisted actions were reachable at all. So it is recorded, and
        # labelled as what it is.
        dynamic = "{" in action

        routes.append({
            "line": number,
            "method": route["method"],
            "path": path,
            "springPath": spring_path(path),
            "controller": controller,
            "action": method,
            "dynamicDispatch": dynamic,
            "args": (route["args"] or "").strip("()").strip() or None,
            "pathParams": (
                [m["name"] for m in TYPED_SEGMENT.finditer(path)]
                + [m["name"] for m in PLAIN_SEGMENT.finditer(path)]
            ),
            # Static asset routes carry no controller and migrate to resource
            # handlers, not to methods.
            "static": action.startswith("staticDir:") or action.startswith("staticFile:"),
            # A catch-all reaches paths no line above it claimed. A static
            # mount ending in `/` is not one — it serves files under a prefix,
            # which is a resource handler in Spring, not an unmatched-request
            # fallback. Conflating them puts `/public/` on the endpoint list.
            "catchAll": dynamic or path.endswith("*"),
        })

    return {
        "routes": routes,
        "includes": includes,
        "unparsed": unparsed,
        "counts": {
            "routes": len(routes),
            "includes": len(includes),
            "unparsed": len(unparsed),
            "catchAll": sum(1 for r in routes if r["catchAll"]),
            "static": sum(1 for r in routes if r["static"]),
            "dynamicDispatch": sum(1 for r in routes if r["dynamicDispatch"]),
            "endpoints": sum(1 for r in routes
                             if not r["static"] and not r["dynamicDispatch"]),
        },
    }


def as_markdown(result: dict) -> str:
    lines = ["| method | play path | spring path | controller | action |",
             "|---|---|---|---|---|"]
    for r in result["routes"]:
        if r["static"] or r["dynamicDispatch"]:
            continue
        lines.append("| %s | `%s` | `%s` | %s | %s |" % (
            r["method"], r["path"], r["springPath"], r["controller"], r["action"]))

    counts = result["counts"]
    lines += ["", "%d routes, of which %d named endpoints, %d static mounts,"
              " %d dynamic dispatch; %d module includes."
              % (counts["routes"], counts["endpoints"], counts["static"],
                 counts["dynamicDispatch"], counts["includes"])]
    if counts["dynamicDispatch"]:
        lines += ["", "**Dynamic dispatch is present.** Actions not named above"
                  " were still reachable through it, so the routes file is not"
                  " the whole endpoint list — every public method on a"
                  " controller was an endpoint."]
    if counts["unparsed"]:
        lines += ["", "**%d lines did not parse** — these are routes that would"
                  " otherwise be lost silently:" % counts["unparsed"]]
        lines += ["- line %d: `%s`" % (u["line"], u["text"])
                  for u in result["unparsed"]]
    return "\n".join(lines)


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        print(__doc__, file=sys.stderr)
        return 2

    path = Path(args[0])
    if not path.is_file():
        print("no such file: %s" % path, file=sys.stderr)
        return 1

    result = parse(path.read_text(encoding="utf-8", errors="replace"))
    if "--format=md" in sys.argv:
        print(as_markdown(result))
    else:
        print(json.dumps(result, indent=2, ensure_ascii=False))

    # Unparsed lines are a finding, not a crash: the output is still useful,
    # and the exit code says a person should look.
    return 3 if result["unparsed"] else 0


if __name__ == "__main__":
    sys.exit(main())
