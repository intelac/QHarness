#!/usr/bin/env python3
"""The tests, sorted by whether they survive leaving Play.

The question this answers is not how many tests there are. It is whether
anything can say a migrated module still behaves as it did — and that turns
entirely on which base class a test extends.

A test extending `UnitTest` or `FunctionalTest`, or loading `Fixtures`, is
bound to the framework. It will not run under Spring, so it is a specification
to be re-expressed rather than a suite to be carried across. A plain JUnit
test on code that does not touch Play is bound to nothing and should run
unchanged after the move — which makes it the cheapest oracle a migration can
have, and the only one that exists before any work is done.

    ./scan-tests.py <root>
    ./scan-tests.py <root> --format=md
"""
import json
import re
import sys
from collections import Counter
from pathlib import Path

PLAY_BOUND = re.compile(
    r"extends\s+(?P<base>UnitTest|FunctionalTest|FunctionalTestCase)\b")
FIXTURES = re.compile(r"\bFixtures\s*\.\s*(?P<call>\w+)")
PLAY_IMPORT = re.compile(r"^\s*import\s+play\.", re.M)
JUNIT = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest)\b")
JUNIT_IMPORT = re.compile(r"^\s*import\s+org\.junit", re.M)

# What a test covers, guessed from its name — `UserServiceTest` covers
# `UserService`. A guess, and reported as one: the pairing is a naming
# convention, not a fact the file states.
SUBJECT = re.compile(r"^(?P<subject>\w+?)(?:Test|Tests|TestCase|IT|Spec)$")


def classify(text: str) -> dict:
    bound = PLAY_BOUND.search(text)
    fixtures = [m.group("call") for m in FIXTURES.finditer(text)]
    return {
        "base": bound.group("base") if bound else None,
        "fixtures": sorted(set(fixtures)),
        "playImports": len(PLAY_IMPORT.findall(text)),
        "cases": len(JUNIT.findall(text)),
        "usesJUnit": bool(JUNIT_IMPORT.search(text)),
    }


def scan(root: Path) -> dict:
    survives, bound, unclear = [], [], []
    bases = Counter()
    fixture_calls = Counter()

    for java in sorted(root.rglob("*.java")):
        parts = java.relative_to(root).parts
        name = java.stem
        looks_like_test = ("test" in (p.lower() for p in parts)
                           or name.endswith(("Test", "Tests", "TestCase", "IT")))
        if not looks_like_test:
            continue

        text = java.read_text(encoding="utf-8", errors="replace")
        facts = classify(text)
        subject = SUBJECT.match(name)
        entry = {
            "file": str(java.relative_to(root)),
            "cases": facts["cases"],
            "covers": subject.group("subject") if subject else None,
            **{k: v for k, v in facts.items() if k != "cases"},
        }

        if facts["base"]:
            bases[facts["base"]] += 1
            entry["reason"] = "extends %s" % facts["base"]
            bound.append(entry)
        elif facts["fixtures"]:
            fixture_calls.update(facts["fixtures"])
            entry["reason"] = "loads Fixtures"
            bound.append(entry)
        elif facts["playImports"]:
            entry["reason"] = "imports play.* (%d)" % facts["playImports"]
            bound.append(entry)
        elif facts["usesJUnit"] or facts["cases"]:
            survives.append(entry)
        else:
            # Named like a test, carrying no test annotations and no framework
            # coupling. A helper, or a test using something this does not
            # recognise. Neither counted as an oracle nor dropped.
            unclear.append(entry)

        if facts["fixtures"]:
            fixture_calls.update(facts["fixtures"])

    return {
        "root": str(root),
        "survives": survives,
        "playBound": bound,
        "unclear": unclear,
        "bases": dict(bases.most_common()),
        "fixtureCalls": dict(fixture_calls.most_common()),
        "counts": {
            "files": len(survives) + len(bound) + len(unclear),
            "survives": len(survives),
            "playBound": len(bound),
            "unclear": len(unclear),
            "casesThatSurvive": sum(e["cases"] for e in survives),
            "casesPlayBound": sum(e["cases"] for e in bound),
        },
    }


def as_markdown(result: dict) -> str:
    counts = result["counts"]
    lines = ["## Tests", "",
             "%d test files: **%d framework-free**, %d bound to Play, %d unclear."
             % (counts["files"], counts["survives"],
                counts["playBound"], counts["unclear"]), ""]

    if counts["survives"]:
        lines += ["### Framework-free — %d files, %d cases"
                  % (counts["survives"], counts["casesThatSurvive"]), "",
                  "No Play base class, no `Fixtures`, no `play.*` import."
                  " These should run unchanged after the move, which makes"
                  " them the only thing that can say a migrated module still"
                  " behaves as it did.", ""]
        lines += ["| file | cases | covers |", "|---|---|---|"]
        for entry in result["survives"][:25]:
            lines.append("| `%s` | %d | %s |"
                         % (entry["file"], entry["cases"],
                            "`%s`" % entry["covers"] if entry["covers"] else "—"))
        if len(result["survives"]) > 25:
            lines.append("| … | | %d more |" % (len(result["survives"]) - 25))
        lines.append("")
        lines += ["The `covers` column is read from the file name and is a"
                  " naming convention rather than something the file states.", ""]
    else:
        # The most consequential finding this probe can produce, so it is
        # said rather than left as an empty section.
        lines += ["### No framework-free tests", "",
                  "**Nothing here can verify a migration.** Every test is"
                  " bound to Play and will not run under Spring, so there is"
                  " no existing answer to whether migrated code behaves as the"
                  " old code did.", "",
                  "That is a different kind of problem from the rest of a"
                  " survey: everything else is a question of effort, and this"
                  " one is whether correctness can be checked at all. The old"
                  " application still runs, which makes comparing responses"
                  " between the two systems the cheapest oracle available —"
                  " but it has to be built before migration rather than"
                  " after.", ""]

    if result["playBound"]:
        lines += ["### Bound to Play — %d files, %d cases"
                  % (counts["playBound"], counts["casesPlayBound"]), "",
                  "Specifications, not a suite. Each states what the behaviour"
                  " is supposed to be, in the most concrete form available,"
                  " and that survives the move even though the code does"
                  " not.", ""]
        for base, count in result["bases"].items():
            lines.append("- `extends %s` — %d" % (base, count))
        if result["fixtureCalls"]:
            lines.append("- `Fixtures.%s` — %d"
                         % (list(result["fixtureCalls"])[0],
                            sum(result["fixtureCalls"].values())))
        lines.append("")

    if result["fixtureCalls"]:
        lines += ["### Fixtures", "",
                  "Nothing in Spring reads Play's fixture format, and its"
                  " `Company(google)` cross-reference syntax is read by"
                  " nothing at all. Replacing it — `@Sql`, Flyway, or a small"
                  " YAML loader — is a decision to make once for the whole"
                  " project.", ""]
        for call, count in result["fixtureCalls"].items():
            lines.append("- `Fixtures.%s()` — %d uses" % (call, count))
        lines.append("")

    if result["unclear"]:
        lines += ["### Unclear — %d files" % counts["unclear"], "",
                  "Named like tests, carrying no test annotations and no"
                  " framework coupling. Helpers, or tests using something this"
                  " does not recognise. Listed rather than counted either"
                  " way:", ""]
        lines += ["- `%s`" % e["file"] for e in result["unclear"][:10]]
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

    result = scan(root)
    print(as_markdown(result) if "--format=md" in sys.argv
          else json.dumps(result, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
