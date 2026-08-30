#!/usr/bin/env python3
"""Which framework APIs a Play 1 codebase uses, and where.

The number that shapes a migration plan more than any other: how much of the
code touches Play at all. A layer that imports nothing from `play.*` is
ordinary Java and moves unchanged; one that does has as many changes as it
has call sites, grouped into as many decisions as it has distinct APIs.

Two checks, because the cheap one is not enough. `import play.…` finds the
common case; a fully-qualified `play.Play.configuration.get(…)` appears in no
import line and is invisible to it. A layer reported clean by the first check
and not the second has been checked for the easy half of the question.

    ./scan-imports.py <root>
    ./scan-imports.py <root> --format=md
    ./scan-imports.py <root> --layers value,shell,play
"""
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

IMPORT = re.compile(r"^\s*import\s+(static\s+)?(?P<name>play\.[\w.]*)", re.M)

# `play.Play`, `play.db.jpa.JPA` used inline. The capital letter is what makes
# it a type rather than a package fragment, which is how a qualified use is
# told from the tail of an import line — those are excluded separately.
QUALIFIED = re.compile(r"\bplay\.(?:[a-z]\w*\.)*(?P<type>[A-Z]\w*)")

# How a Play API maps into Spring. Only the ones with a single settled answer:
# a mapping that is really a decision does not belong in a probe's output.
KNOWN = {
    "play.db.jpa": "Spring Data JPA repository",
    "play.db": "Spring Data",
    "play.data.validation": "jakarta.validation",
    "play.data.binding": "Spring @ModelAttribute / converters",
    "play.jobs": "@Scheduled",
    "play.Logger": "org.slf4j.Logger",
    "play.cache": "Spring Cache",
    "play.libs.WS": "RestClient / WebClient",
    "play.mvc": "Spring MVC — but the controller is rewritten, not ported",
    "play.i18n": "MessageSource",
    "play.Play": "@ConfigurationProperties / Environment",
    "play.test": "no equivalent — Play-bound tests are specifications, not a suite",
    "play.exceptions": "no equivalent — Play's template resolution does not survive",
}


def spring_note(api: str) -> str:
    """The nearest Spring counterpart, longest prefix first."""
    for prefix in sorted(KNOWN, key=len, reverse=True):
        if api == prefix or api.startswith(prefix + "."):
            return KNOWN[prefix]
    return ""


def layer_of(path: Path, root: Path, layers: list) -> str:
    """Which layer a file sits in, by the first matching path segment.

    A codebase that separates business logic from framework code has already
    done the hardest part of a migration, and this is where that shows up: a
    value layer with no framework imports moves as ordinary Java.
    """
    parts = path.relative_to(root).parts
    for layer in layers:
        if layer in parts:
            return layer
    return "(unlayered)"


def scan(root: Path, layers: list) -> dict:
    by_layer = defaultdict(lambda: {
        "files": 0, "filesWithPlay": 0,
        "imports": Counter(), "qualified": Counter(),
        "qualifiedSites": [],
    })

    for java in sorted(root.rglob("*.java")):
        text = java.read_text(encoding="utf-8", errors="replace")
        layer = by_layer[layer_of(java, root, layers)]
        layer["files"] += 1

        imports = [m.group("name") for m in IMPORT.finditer(text)]
        layer["imports"].update(imports)

        # A qualified use is one that is not simply the tail of an import
        # line already counted above. Lines are checked individually so an
        # import and a qualified use on the same file do not mask each other.
        qualified = []
        for number, line in enumerate(text.splitlines(), start=1):
            if IMPORT.match(line):
                continue
            # A javadoc `@see play.jobs.Job#doJob()` is a reference, not a
            # call site: nothing has to change for it. Counting it inflates
            # the figure the migration plan is sized on, and a plan sized on
            # comments is worse than one sized on nothing.
            stripped = line.lstrip()
            if stripped.startswith(("//", "*", "/*")):
                continue
            for match in QUALIFIED.finditer(line):
                api = match.group(0).rsplit(".", 1)[0]
                qualified.append(api)
                if len(layer["qualifiedSites"]) < 200:
                    layer["qualifiedSites"].append({
                        "file": str(java.relative_to(root)),
                        "line": number,
                        "text": line.strip()[:100],
                    })
        layer["qualified"].update(qualified)

        if imports or qualified:
            layer["filesWithPlay"] += 1

    out = {}
    for name, data in by_layer.items():
        combined = Counter(data["imports"])
        combined.update(data["qualified"])
        out[name] = {
            "files": data["files"],
            "filesWithPlay": data["filesWithPlay"],
            "clean": data["filesWithPlay"] == 0,
            "byApi": [
                {"api": api, "count": count, "spring": spring_note(api)}
                for api, count in combined.most_common()
            ],
            "importCount": sum(data["imports"].values()),
            "qualifiedCount": sum(data["qualified"].values()),
            "qualifiedSites": data["qualifiedSites"],
        }
    return {"layers": out, "root": str(root)}


def as_markdown(result: dict) -> str:
    lines = []
    for name, layer in sorted(result["layers"].items()):
        lines.append("## %s" % name)
        lines.append("")
        lines.append("%d files, %d touching Play." % (layer["files"], layer["filesWithPlay"]))
        lines.append("")

        if layer["clean"]:
            # The finding that most changes an estimate, so it is stated
            # rather than left to be inferred from an empty table.
            lines += ["**No framework coupling.** Both checks came back empty:"
                      " no `import play.…` and no qualified `play.Type` use."
                      " This layer is ordinary Java and moves unchanged.", ""]
            continue

        lines += ["| API | uses | Spring |", "|---|---|---|"]
        for entry in layer["byApi"]:
            lines.append("| `%s` | %d | %s |"
                         % (entry["api"], entry["count"], entry["spring"] or "—"))
        lines.append("")

        if layer["qualifiedCount"]:
            lines += ["%d of those are qualified uses rather than imports —"
                      " invisible to an import-line check:" % layer["qualifiedCount"], ""]
            for site in layer["qualifiedSites"][:10]:
                lines.append("- `%s:%d` — `%s`" % (site["file"], site["line"], site["text"]))
            if len(layer["qualifiedSites"]) > 10:
                lines.append("- … and %d more" % (len(layer["qualifiedSites"]) - 10))
            lines.append("")

    return "\n".join(lines)


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        print(__doc__, file=sys.stderr)
        return 2

    root = Path(args[0])
    if not root.is_dir():
        print("no such directory: %s" % root, file=sys.stderr)
        return 1

    layers = ["value", "shell", "play"]
    for i, arg in enumerate(sys.argv):
        if arg.startswith("--layers="):
            layers = [s.strip() for s in arg.split("=", 1)[1].split(",") if s.strip()]
        elif arg == "--layers" and i + 1 < len(sys.argv):
            layers = [s.strip() for s in sys.argv[i + 1].split(",") if s.strip()]

    result = scan(root, layers)
    print(as_markdown(result) if "--format=md" in sys.argv
          else json.dumps(result, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
