# QHarness

A FIX 4.4 order routing engine an agent can operate, and a conformance probe
for testing order routers from both sides of them.

They are separate on purpose. NEXUM holds FIX sessions and every order's state,
and answers questions about them — where an order would route, what happened to
it, why a request was refused. The probe holds no opinion about any of that: it
stands on both sides of *someone else's* router, sending requests in as a
client and answering as a market, and reports what that system passed back.

Testing NEXUM is then the same job as testing anyone else's engine, done with
the same tool.

## Layout

| Path | What it is |
|---|---|
| `nexum/` | The FIX engine, and the agent tools that read it. |
| `fixprobe/` | The conformance probe. Its own process; knows nothing of the engine. |
| `mcp/` | The MCP server both of them are built on — tools, registry, HTTP host. |
| `plugins/ui-nexum-sessions/` | Browser plugin: live FIX session state beside the composer. |
| `scripts/` | Driving the probe: one call at a time, or a whole scenario. |
| `harness/` | **Submodule** — [deepseek-harness](https://github.com/deepseek-ai/deepseek-harness), the agent runtime. |
| `.claude/skills/` | What to do with either side, for an agent that reads skills. |

The dependency runs one way. `fixprobe` does not depend on `nexum`, so a probe
cannot quietly acquire knowledge of the system it is meant to test from the
outside.

`harness/` is a dependency too, so nothing of ours lives inside it: a local
commit in a vendored tree has to be carried forward through every upstream
upgrade. Our plugin sits in `plugins/`, and `scripts/sync-plugins.sh` copies it
into the workspace to be built.

## Getting it

```sh
git clone --recursive <url>
mvn -q package          # builds all three modules
```

## Running the engine

Write a configuration — the fields are documented in `nexum/docs/`, and the
tests under `nexum/src/test` build working ones — then:

```sh
java -jar nexum/target/nexum.jar /path/to/nexum.yaml
```

The journal path in that file is the order book's source of truth: it is
append-only, and every order's state is folded from it rather than held only in
memory.

An `mcp:` block in that file opens the agent interface, and is absent unless a
deployment asks for it. The configuration itself is not in git — it holds
per-machine ports, journal paths, and on a deployment a password.

## Testing a router

The probe takes a port and nothing else. Which system is being tested, on which
port, as which CompID, are answers a scenario gives when it connects:

```sh
java -jar fixprobe/target/fixprobe.jar          # MCP on 127.0.0.1:18099
```

Several can run at once, each on its own port, so one client is not a limit —
and two pointed at each other check a set of CompIDs before any system under
test is involved.

```sh
# the market side must come up first: the system dials out to it
./scripts/harness connect side=market port=<it dials out to> \
    senderCompId=LSE targetCompId=OMS
./scripts/harness connect side=client port=<it listens on> \
    senderCompId=FUNDX targetCompId=OMS
./scripts/harness status

./scripts/harness send_order clOrdId=T-1 symbol=BP side=buy \
    quantity=1000 price=50 onBehalfOf=FUNDX
./scripts/harness traffic side=market msgType=D
```

The CompIDs are crossed and are not names you choose: they are what the
counterparty's own configuration expects.

`scripts/harness-scenario.py` exercises every probe tool against a router;
`scripts/harness-demo.py` walks five orders — partial then full fill, an
amendment, a cancel that keeps what traded, a refused cancel, and a rejection —
and reports which checks failed.

Nothing the market side sends is derived: `execType` and `ordStatus` go out
exactly as given, including combinations a real venue would never produce,
because how a system handles those is what a conformance test is for.

## Skills

Two, split by what is being asked rather than by which process answers:

- `.claude/skills/fix-harness/` — driving FIX traffic into any order engine.
  Names no particular one.
- `.claude/skills/nexum-operate/` — asking a running NEXUM what it is doing and
  why an order ended where it did.

Each carries the diagnostics from its own vantage and says what it cannot see.
A probe sees "nothing arrived" and cannot tell a refusal from a routing miss
from a downed venue; the engine can name which. Neither answers the whole
question alone.

## Tests

```sh
mvn test          # 456
```

They are written to fail: each mechanism was checked by reintroducing the bug
it guards against and watching the test catch it.

## Credentials

Model provider keys are referenced by name and resolved per request from the
agent runtime's credential store, so no key belongs in this repository or in
any configuration file it contains.
