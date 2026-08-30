#!/usr/bin/env python3
"""Build a Play 1 corpus large enough to test a probe's behaviour at scale.

No public Play 1 application approaches the size of the ones still running
inside companies — the largest route table findable anywhere is 228 against a
target of a thousand or more. So scale has to be constructed, and the honest
way to construct it is from real code rather than generated text.

Two modes, and they answer different questions:

    aggregate   namespace several real applications into one tree.
                Real syntax, real variety, real awkwardness — but bounded by
                how much real code exists. Answers: does this hold up across
                independently-written codebases?

    amplify     replicate one application N times under rewritten package
                names. Reaches any size, but every copy is the same code.
                Answers: does this stay correct and fast at N routes?

Amplification cannot tell you whether a probe handles syntax it has not seen,
because there is no new syntax in the twentieth copy. Aggregation cannot reach
a thousand routes, because that much real Play 1 is not public. Use both, for
what each is good for.

    ./synth-corpus.py aggregate <out> <app-dir>...
    ./synth-corpus.py amplify <out> <app-dir> --times 20
"""
import re
import shutil
import sys
from pathlib import Path

ROUTE = re.compile(
    r"^(?P<verb>\s*(?:GET|POST|PUT|DELETE|HEAD|OPTIONS|PATCH|WS|\*))"
    r"(?P<gap1>\s+)(?P<path>\S+)"
    r"(?P<gap2>\s+)(?P<action>\S+)(?P<rest>.*)$"
)


def namespaced_routes(text: str, prefix: str) -> str:
    """The same routes under a path and package prefix.

    Only the path and the controller move. Everything else — the verb, the
    argument list, the spacing, the comments — is left exactly as written,
    because a corpus that quietly normalises its input tests the probe
    against the normalisation rather than against Play.
    """
    out = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            out.append(line)
            continue

        match = ROUTE.match(line)
        if not match:
            # A module include, or something this does not recognise. Carried
            # through unchanged: dropping it would make the corpus easier
            # than the code it came from, which defeats the exercise.
            out.append(line)
            continue

        path = match["path"]
        action = match["action"]

        # `/` becomes `/prefix`, not `/prefix/`.
        moved = "/" + prefix if path == "/" else "/" + prefix + path

        # staticDir: and staticFile: name a directory, not a controller, and
        # that directory moved with the rest of the app.
        if action.startswith("staticDir:") or action.startswith("staticFile:"):
            kind, _, target = action.partition(":")
            moved_action = "%s:%s/%s" % (kind, prefix, target) if target else action
        elif action.startswith("module:"):
            moved_action = action
        else:
            moved_action = "%s.%s" % (prefix, action)

        out.append("%s%s%s%s%s%s" % (
            match["verb"], match["gap1"], moved,
            match["gap2"], moved_action, match["rest"]))
    return "\n".join(out) + "\n"


def repackage_java(text: str, prefix: str) -> str:
    """Java under a package prefix.

    Play 1 controllers usually sit in the default package or in `controllers`,
    so a prefix has to be inserted rather than substituted.
    """
    if re.search(r"^\s*package\s+", text, re.M):
        return re.sub(r"^(\s*package\s+)", r"\1%s." % prefix, text, count=1, flags=re.M)
    # No package line: give it one. `models` and `controllers` are the two
    # Play looks for, and a class in neither is found by name anyway.
    return "package %s;\n\n%s" % (prefix, text)


def copy_app(source: Path, destination: Path, prefix: str) -> dict:
    """One application, namespaced into the destination tree."""
    counts = {"routes": 0, "templates": 0, "java": 0}

    views = source / "app" / "views"
    if views.is_dir():
        target = destination / "app" / "views" / prefix
        shutil.copytree(views, target, dirs_exist_ok=True)
        counts["templates"] = sum(1 for _ in target.rglob("*.html"))

    app = source / "app"
    if app.is_dir():
        for java in app.rglob("*.java"):
            relative = java.relative_to(app)
            out = destination / "app" / prefix / relative
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(
                repackage_java(java.read_text(encoding="utf-8", errors="replace"), prefix),
                encoding="utf-8")
            counts["java"] += 1

    routes = source / "conf" / "routes"
    if routes.is_file():
        moved = namespaced_routes(
            routes.read_text(encoding="utf-8", errors="replace"), prefix)
        counts["routes"] = sum(
            1 for line in moved.splitlines()
            if line.strip() and not line.strip().startswith("#"))
        fragment = destination / "conf" / "routes.d" / ("%s.routes" % prefix)
        fragment.parent.mkdir(parents=True, exist_ok=True)
        fragment.write_text(moved, encoding="utf-8")

    return counts


def assemble(destination: Path, sources: list, prefixes: list) -> dict:
    """Build the tree and the single routes file the probes will read."""
    if destination.exists():
        shutil.rmtree(destination)
    (destination / "conf").mkdir(parents=True)

    total = {"routes": 0, "templates": 0, "java": 0, "apps": 0}
    header = ["# Synthetic corpus. Assembled from real Play 1 applications,",
              "# each namespaced under its own path and package prefix.", ""]
    body = []

    for source, prefix in zip(sources, prefixes):
        if not (source / "conf" / "routes").is_file():
            print("  skipped %s — no conf/routes" % source.name, file=sys.stderr)
            continue
        counts = copy_app(source, destination, prefix)
        for key in ("routes", "templates", "java"):
            total[key] += counts[key]
        total["apps"] += 1

        fragment = destination / "conf" / "routes.d" / ("%s.routes" % prefix)
        body += ["# ---- %s (%d routes) ----" % (prefix, counts["routes"]),
                 fragment.read_text(encoding="utf-8").rstrip(), ""]
        print("  %-24s routes=%-4d tpl=%-4d java=%d"
              % (prefix, counts["routes"], counts["templates"], counts["java"]))

    (destination / "conf" / "routes").write_text(
        "\n".join(header + body), encoding="utf-8")
    return total


def main() -> int:
    if len(sys.argv) < 4:
        print(__doc__, file=sys.stderr)
        return 2

    mode, out = sys.argv[1], Path(sys.argv[2])
    rest = [a for a in sys.argv[3:] if not a.startswith("--")]

    if mode == "aggregate":
        sources = [Path(p) for p in rest]
        prefixes = [re.sub(r"[^a-z0-9]", "", s.name.lower()) or "app%d" % i
                    for i, s in enumerate(sources)]

    elif mode == "amplify":
        times = 20
        for i, arg in enumerate(sys.argv):
            if arg == "--times" and i + 1 < len(sys.argv):
                times = int(sys.argv[i + 1])
        source = Path(rest[0])
        base = re.sub(r"[^a-z0-9]", "", source.name.lower()) or "app"
        sources = [source] * times
        prefixes = ["%s%02d" % (base, n) for n in range(times)]

    else:
        print("unknown mode: %s" % mode, file=sys.stderr)
        return 2

    missing = [s for s in sources if not s.is_dir()]
    if missing:
        print("no such directory: %s" % missing[0], file=sys.stderr)
        return 1

    total = assemble(out, sources, prefixes)
    print("\n  %d applications → %d routes, %d templates, %d java files"
          % (total["apps"], total["routes"], total["templates"], total["java"]))
    print("  %s" % out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
