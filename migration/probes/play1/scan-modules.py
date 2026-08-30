#!/usr/bin/env python3
"""What a codebase is made of, and how it divides.

Four questions, in the order a person asks them: how big, in what languages,
split into what, and what does each part do. The first three are counting. The
fourth is not — this reports the evidence a reader would use to answer it and
stops there, because a probe that guesses at purpose produces a plausible
sentence with nothing behind it.

Vendored code is separated rather than counted, because a jQuery build is
thirty thousand lines that nobody migrates, and a total that includes it
describes the download rather than the project.

    ./scan-modules.py <root>
    ./scan-modules.py <root> --format=md
"""
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

LANGUAGES = {
    ".java": "Java", ".html": "Groovy template", ".js": "JavaScript",
    ".ts": "TypeScript", ".css": "CSS", ".scss": "SCSS", ".sql": "SQL",
    ".xml": "XML", ".yml": "YAML", ".yaml": "YAML", ".properties": "Properties",
    ".sh": "Shell", ".py": "Python", ".jsp": "JSP", ".json": "JSON",
}

# Third-party code, by the conventions people actually use. A vendored library
# is not part of the project's size and nobody migrates it.
VENDORED = re.compile(
    r"(^|/)(node_modules|bower_components|vendor|lib|libs|third[-_]?party|"
    r"jquery[^/]*|bootstrap[^/]*|\.min\.|dist|build|target|out)(/|$)",
    re.IGNORECASE)

# Where Play puts things. Used to name a directory's role, which is a fact
# about the framework rather than a claim about this codebase.
PLAY_ROLES = {
    "controllers": "HTTP entry points",
    "models": "persistent entities",
    "views": "Groovy templates",
    "jobs": "scheduled and startup work",
    "tags": "custom template tags",
    "conf": "configuration and routes",
    "test": "tests",
    "public": "static assets",
}

# Signals that say what a Java file is, taken from what it extends or imports.
# Each is a fact readable in one line, not an inference about behaviour.
# Anchored on the class declaration, because `Class<? extends Model>` in a
# method signature says nothing about what the enclosing class is. Without the
# anchor, BionimbuzWeb's BaseController — which takes that generic parameter —
# was reported as both a controller and an entity.
DECLARES = r"^\s*(?:public\s+|final\s+|abstract\s+)*class\s+\w+\s+extends\s+"

SIGNALS = [
    (re.compile(DECLARES + r"(GenericModel|Model)\b", re.M), "JPA entity"),
    (re.compile(DECLARES + r"\w*Controller\b", re.M), "controller"),
    (re.compile(DECLARES + r"Job\b", re.M), "scheduled job"),
    (re.compile(r"@OnApplicationStart"), "runs at startup"),
    (re.compile(r"@Every\("), "runs periodically"),
    (re.compile(r"@On\("), "runs on a cron schedule"),
    (re.compile(r"implements\s+\w*Binder\b"), "type binder"),
    (re.compile(DECLARES + r"(UnitTest|FunctionalTest)\b", re.M), "Play-bound test"),
    (re.compile(r"@Test\b"), "test"),
]


def is_vendored(path: Path, root: Path) -> bool:
    return bool(VENDORED.search(str(path.relative_to(root)).replace("\\", "/")))


def line_count(path: Path) -> int:
    try:
        return sum(1 for _ in path.open("rb"))
    except OSError:
        return 0


def signals_in(path: Path) -> list:
    """What this file announces itself to be."""
    if path.suffix != ".java":
        return []
    text = path.read_text(encoding="utf-8", errors="replace")
    return [label for pattern, label in SIGNALS if pattern.search(text)]


def module_of(path: Path, root: Path) -> str:
    """Which module a file belongs to.

    Play's own layout puts everything under `app/`, so the module is the next
    directory down from there when one exists — `app/billing/models/…` is the
    billing module. A flat `app/models/…` has no module, and saying so is more
    useful than inventing one.
    """
    parts = path.relative_to(root).parts
    if "app" in parts:
        after = parts[parts.index("app") + 1:]
        if len(after) > 1 and after[0] not in PLAY_ROLES:
            return after[0]
        return "(app)"
    return parts[0] if len(parts) > 1 else "(root)"


def scan(root: Path) -> dict:
    languages = Counter()
    lines = Counter()
    vendored_files = 0
    vendored_lines = 0
    modules = defaultdict(lambda: {
        "files": 0, "lines": 0, "languages": Counter(),
        "roles": Counter(), "signals": Counter(), "entities": [],
        "controllers": [], "jobs": [],
    })

    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix not in LANGUAGES:
            continue

        count = line_count(path)
        if is_vendored(path, root):
            vendored_files += 1
            vendored_lines += count
            continue

        language = LANGUAGES[path.suffix]
        languages[language] += 1
        lines[language] += count

        module = modules[module_of(path, root)]
        module["files"] += 1
        module["lines"] += count
        module["languages"][language] += 1

        relative = str(path.relative_to(root))
        for part in path.relative_to(root).parts:
            if part in PLAY_ROLES:
                module["roles"][part] += 1

        for signal in signals_in(path):
            module["signals"][signal] += 1
            name = path.stem
            if signal == "JPA entity":
                module["entities"].append(name)
            elif signal == "controller":
                module["controllers"].append(name)
            elif signal in ("scheduled job", "runs at startup",
                            "runs periodically", "runs on a cron schedule"):
                if name not in module["jobs"]:
                    module["jobs"].append(name)

    return {
        "root": str(root),
        "languages": [
            {"language": name, "files": count, "lines": lines[name]}
            for name, count in languages.most_common()
        ],
        "totals": {
            "files": sum(languages.values()),
            "lines": sum(lines.values()),
            "vendoredFiles": vendored_files,
            "vendoredLines": vendored_lines,
        },
        "modules": {
            name: {
                "files": data["files"],
                "lines": data["lines"],
                "languages": dict(data["languages"].most_common()),
                "roles": {r: {"count": c, "means": PLAY_ROLES[r]}
                          for r, c in data["roles"].most_common()},
                "signals": dict(data["signals"].most_common()),
                "entities": sorted(data["entities"]),
                "controllers": sorted(data["controllers"]),
                "jobs": sorted(data["jobs"]),
            }
            for name, data in sorted(modules.items())
        },
    }


def as_markdown(result: dict) -> str:
    totals = result["totals"]
    lines = ["# %s" % result["root"], ""]

    lines += ["## Size", "",
              "**%s lines across %d files**, excluding vendored code."
              % ("{:,}".format(totals["lines"]), totals["files"]), ""]
    if totals["vendoredFiles"]:
        lines += ["%d vendored files (%s lines) were set aside — third-party"
                  " code nobody migrates, and a total that counts it describes"
                  " the download rather than the project."
                  % (totals["vendoredFiles"],
                     "{:,}".format(totals["vendoredLines"])), ""]

    lines += ["## Languages", "", "| language | files | lines |", "|---|---|---|"]
    for entry in result["languages"]:
        lines.append("| %s | %d | %s |"
                     % (entry["language"], entry["files"],
                        "{:,}".format(entry["lines"])))
    lines.append("")

    lines += ["## Modules", ""]
    for name, module in result["modules"].items():
        lines.append("### %s" % name)
        lines.append("")
        lines.append("%d files, %s lines — %s"
                     % (module["files"], "{:,}".format(module["lines"]),
                        ", ".join("%d %s" % (c, l)
                                  for l, c in module["languages"].items())))
        lines.append("")

        if module["roles"]:
            lines += ["Directories, and what Play uses each for:", ""]
            for role, info in module["roles"].items():
                lines.append("- `%s/` — %s (%d files)"
                             % (role, info["means"], info["count"]))
            lines.append("")

        # Named rather than counted: a list of entity names says more about
        # what a module is for than the number of them does, and it is the
        # closest a count can get to answering "what does this do".
        for label, key in (("Entities", "entities"),
                           ("Controllers", "controllers"),
                           ("Scheduled work", "jobs")):
            names = module[key]
            if not names:
                continue
            shown = ", ".join("`%s`" % n for n in names[:12])
            if len(names) > 12:
                shown += ", … (%d more)" % (len(names) - 12)
            lines += ["**%s** (%d): %s" % (label, len(names), shown), ""]

    lines += ["## What this does not say", "",
              "Nothing above says what a module is *for*. The entity and"
              " controller names are the strongest hint a count can offer, and"
              " they are hints — `Executor` and `Plugin` suggest a domain"
              " without describing it. Naming a module's purpose means reading"
              " it, and a probe that did so would be producing a sentence"
              " rather than a measurement.", ""]

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

    result = scan(root)
    print(as_markdown(result) if "--format=md" in sys.argv
          else json.dumps(result, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
