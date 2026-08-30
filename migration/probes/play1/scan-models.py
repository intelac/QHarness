#!/usr/bin/env python3
"""The data model, and every place it is queried from.

Two things a migration is sized on. The entities say what the system stores;
the query call sites say how much has to move when static Active Record
becomes an injected repository — which is the real cost, because the
translation itself is nearly free.

Play's `find("byEmailAndActive", …)` maps onto Spring Data's
`findByEmailAndActive(…)` almost exactly: the comparator set is the same. What
changes is architectural. Every one of these call sites, and every test that
sets one up, moves.

    ./scan-models.py <root>
    ./scan-models.py <root> --format=md
"""
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

# Anchored on the declaration: `Class<? extends Model>` in a signature says
# nothing about what the enclosing class is.
ENTITY = re.compile(
    r"^\s*(?:public\s+|final\s+|abstract\s+)*class\s+(?P<name>\w+)"
    r"\s+extends\s+(?:GenericModel|Model)\b", re.M)

# A persisted field, either style. Play 1 lets an entity declare public fields
# and weaves the accessors in at compile time with Javassist; a project can
# also write private fields and accessors by hand. Which one it chose decides
# whether accessors have to be generated on the way out, so both are matched
# and the visibility is kept.
FIELD = re.compile(
    r"^\s*(?P<visibility>public|private|protected)\s+"
    r"(?!static|class|final\s+class|abstract)"
    r"(?:final\s+|transient\s+|volatile\s+)*"
    r"(?P<type>[\w<>\[\],.\s]+?)\s+(?P<name>\w+)\s*(?:=[^;]*)?;", re.M)

# A hand-written accessor. Their presence is the tell: an entity with getters
# is not relying on the enhancer, and nothing has to be generated for it.
ACCESSOR = re.compile(r"^\s*public\s+[\w<>\[\],.]+\s+(get|set|is)[A-Z]\w*\s*\(", re.M)

RELATION = re.compile(r"@(?P<kind>OneToMany|ManyToOne|OneToOne|ManyToMany)")

# `User.find(...)`, `Hotel.findAll()`, `Booking.count()`. The capital head is
# what makes it an entity rather than a local variable.
QUERY = re.compile(
    r"\b(?P<entity>[A-Z]\w*)\s*\.\s*"
    r"(?P<method>find|findAll|findById|count|delete|deleteAll|"
    r"all|em|save|refresh)\s*\(")

# Play's own query DSL: `find("byTitleLikeAndAuthor", …)`. The string names
# the comparison, which is what Spring Data expresses as a method name.
BY_QUERY = re.compile(r"""find\w*\(\s*["'](?P<criteria>by\w+)["']""", re.I)


def spring_equivalent(criteria: str) -> str:
    """The Spring Data method this Play criteria string becomes."""
    return "findBy" + criteria[2:] if criteria.lower().startswith("by") else ""


def scan(root: Path) -> dict:
    entities = {}
    queries = defaultdict(list)
    criteria = Counter()

    for java in sorted(root.rglob("*.java")):
        text = java.read_text(encoding="utf-8", errors="replace")
        relative = str(java.relative_to(root))

        for match in ENTITY.finditer(text):
            name = match.group("name")
            body = text[match.end():]
            entities[name] = {
                "file": relative,
                "line": text[:match.start()].count("\n") + 1,
                "fields": [
                    {"name": f.group("name"), "type": f.group("type").strip(),
                     "visibility": f.group("visibility")}
                    for f in FIELD.finditer(body)
                ],
                "accessors": len(ACCESSOR.findall(body)),
                "relations": Counter(
                    r.group("kind") for r in RELATION.finditer(body)),
            }

        for number, line in enumerate(text.splitlines(), start=1):
            stripped = line.lstrip()
            if stripped.startswith(("//", "*", "/*", "import ")):
                continue
            for match in QUERY.finditer(line):
                queries[match.group("entity")].append({
                    "file": relative, "line": number,
                    "method": match.group("method"),
                    "text": line.strip()[:100],
                })
            for match in BY_QUERY.finditer(line):
                criteria[match.group("criteria")] += 1

    # Only calls on things that are actually entities. `Logger.info(…)` and
    # `Collections.emptyList()` match the shape and are not queries.
    known = {name: sites for name, sites in queries.items() if name in entities}
    unknown = {name: len(sites) for name, sites in queries.items()
               if name not in entities}

    return {
        "root": str(root),
        "entities": {
            name: {
                **data,
                "fieldCount": len(data["fields"]),
                "publicFields": sum(1 for f in data["fields"]
                                    if f["visibility"] == "public"),
                "relations": dict(data["relations"]),
                "querySites": len(known.get(name, [])),
            }
            for name, data in sorted(entities.items())
        },
        "querySites": {name: sites for name, sites in sorted(known.items())},
        "criteria": [
            {"play": name, "spring": spring_equivalent(name), "uses": count}
            for name, count in criteria.most_common()
        ],
        "counts": {
            "entities": len(entities),
            "fields": sum(len(e["fields"]) for e in entities.values()),
            "publicFields": sum(
                1 for e in entities.values() for f in e["fields"]
                if f["visibility"] == "public"),
            "accessors": sum(e["accessors"] for e in entities.values()),
            "querySites": sum(len(s) for s in known.values()),
            "criteriaStrings": len(criteria),
            "unmatched": unknown,
        },
    }


def as_markdown(result: dict) -> str:
    counts = result["counts"]
    lines = ["## Entities", "",
             "**%d entities, %d persisted fields, %d query call sites.**"
             % (counts["entities"], counts["fields"], counts["querySites"]), ""]

    lines += ["| entity | fields | relations | queried from |", "|---|---|---|---|"]
    for name, entity in result["entities"].items():
        relations = ", ".join("%d %s" % (c, k)
                              for k, c in entity["relations"].items()) or "—"
        lines.append("| `%s` | %d | %s | %d |"
                     % (name, entity["fieldCount"], relations, entity["querySites"]))
    lines.append("")

    if result["criteria"]:
        lines += ["### Query criteria", "",
                  "Play names the comparison in a string; Spring Data names it"
                  " in the method. The mapping is close to mechanical — the"
                  " comparator set is the same — which is why the cost of this"
                  " migration is not the translation.", "",
                  "| Play | Spring Data | uses |", "|---|---|---|"]
        for entry in result["criteria"][:25]:
            lines.append("| `find(\"%s\")` | `%s(…)` | %d |"
                         % (entry["play"], entry["spring"] or "?", entry["uses"]))
        if len(result["criteria"]) > 25:
            lines.append("| … | | %d more |" % (len(result["criteria"]) - 25))
        lines.append("")

    lines += ["### What moves", "",
              "%d call sites is the size of the change. Each becomes a"
              " repository method call, so each file holding one gains an"
              " injected dependency, and every test that set up an entity"
              " statically sets up a repository instead."
              % counts["querySites"], ""]

    # Play 1 entities carry public fields and no accessors — Javassist weaves
    # those in at compile time. Removing the enhancement means writing them,
    # and it is the kind of work that is invisible until the build breaks.
    # Which style the entities use decides whether accessors have to be
    # written on the way out — a mechanical job, but one that is invisible
    # until the build breaks.
    if counts["publicFields"] and counts["accessors"] < counts["publicFields"]:
        lines += ["Entities declare **%d public fields** against %d written"
                  " accessors, so this codebase leans on Play's"
                  " `PropertiesEnhancer`: the getters and setters are woven in"
                  " at compile time and are not in the source. Dropping the"
                  " enhancement means generating them — best done while the"
                  " code still runs under Play, where the existing tests can"
                  " confirm nothing moved."
                  % (counts["publicFields"], counts["accessors"]), ""]
    else:
        lines += ["Entities declare **%d fields** (%d public) and **%d"
                  " accessors written by hand**, so this codebase does not"
                  " depend on Play's `PropertiesEnhancer`. That removes a"
                  " whole class of migration work: nothing has to be generated"
                  " to replace what the enhancer was providing."
                  % (counts["fields"], counts["publicFields"],
                     counts["accessors"]), ""]

    if counts["unmatched"]:
        top = sorted(counts["unmatched"].items(), key=lambda kv: -kv[1])[:8]
        lines += ["### Not counted", "",
                  "Calls matching the query shape on things that are not"
                  " entities in this tree — static utilities, or entities"
                  " defined elsewhere. Listed rather than dropped, because"
                  " the second case would be a real gap:", ""]
        lines += ["- `%s` — %d" % (name, count) for name, count in top]
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
