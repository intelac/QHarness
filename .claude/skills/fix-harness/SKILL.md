---
name: fix-harness
description: Test an order router over FIX from both sides — stand up client and market counterparties, send orders, amendments and cancels in as a client, answer as a market, and check what the system under test passed back. Use for conformance testing any FIX order engine. For asking a running NEXUM what it is doing or why an order went the way it did, use nexum-operate.
---

# Testing an order router from both sides

A router sits between two counterparties: clients send it orders, and it sends
them on to a market. Testing one means standing on both sides — sending
requests in as a client, and answering as the market — and checking what it
passed back.

Everything here is done with one process, `fixprobe`, and it knows nothing
about the system it is pointed at. That is the point: what it tests is usually
not the engine in front of you.

```
java -jar fixprobe/target/fixprobe.jar          # MCP on 127.0.0.1:18099
java -jar fixprobe/target/fixprobe.jar 18101    # somewhere else
```

It takes a port and nothing else. There is no configuration file: which system
is being tested, on which port, as which CompID, are answers a scenario gives
when it connects, and they change from one run to the next.

## The tools

Every tool here is `mcp__fixprobe__harness_*`. The examples write them as
`harness_connect side=market port=…` for brevity; read each as a call to the
tool of that name with those arguments.

| To do this | Call |
|---|---|
| Bring an endpoint up, take one down | `harness_connect`, `harness_disconnect` |
| See what the harness holds | `harness_status` |
| Send as a client | `harness_send_order`, `harness_send_cancel`, `harness_send_replace` |
| Answer as a market | `harness_send_execution`, `harness_send_cancel_reject` |
| Read what crossed | `harness_traffic`, `harness_clear_traffic` |

The system under test has none of these — it is the thing being tested, not the
thing doing the testing.

**Nothing here injects an order inside the system under test.** If the engine
being tested offers a tool that places an order internally, that is the
opposite of what a conformance test checks: whether an order arriving over FIX,
from a counterparty, is handled correctly. Orders go in through
`harness_send_order`.

There is also a shell wrapper, `scripts/harness`, for driving a probe by hand
from a terminal; it is not how an agent holding these tools should call them.
`FIXPROBE_PORT` chooses which probe it talks to.

Several probes can run at once, each on its own port. Two pointed at each other
check a set of CompIDs and ports before any system under test is involved.

## Bring the sides up

**The market side must be started first.** The system under test dials out to
it, and an initiator with nothing to reach simply retries.

```
harness_connect side=market port=<the port it dials out to> senderCompId=<market> targetCompId=<the system>
harness_connect side=client port=<the port it listens on> senderCompId=<client> targetCompId=<the system>
harness_status
```

Wait until `status` reports both sides `logged on` before sending anything; a
tool called before then says so rather than failing silently.

**The CompIDs are crossed, and they are not names you choose.** They are what
the counterparty's own configuration expects, so `senderCompId` is who the
harness claims to be and `targetCompId` is the system it is talking to — the
mirror image of what that system has. Getting them wrong looks like this: TCP
connects, a logon goes out, and the connection is dropped without an answer
(`Encountered END_OF_STREAM` in the system's log).

Where those values come from is the system under test's business, not the
harness's. Ask whoever runs it, or read its session listing if it offers one:
most engines name both CompIDs together, often as a single session id like
`QUATTRO->FUNDX`, and report the port alongside. Read that pair the way the
counterparty sees it — the sender is the far side, so it is your target.

`host` is client-side only, defaulting to `127.0.0.1`. A market side listens, so
it has no host — testing across machines means the system under test dials
*your* address, which is in its configuration rather than yours.

Every endpoint speaks FIX.4.4. A system on 4.2 or 5.0 cannot be reached.

## More than two endpoints

A router serving several clients over one session decides between them by what
their orders carry, and testing that it decides correctly needs more than one
client at a time. Endpoints are named, and `client` and `market` are just the
usual two:

```
harness_connect side=desk-b dials=out port=9880 senderCompId=DESKB targetCompId=OMS
harness_send_order endpoint=desk-b clOrdId=B-1 symbol=BP side=buy quantity=500 price=50 onBehalfOf=DESKB
harness_traffic side=desk-b msgType=8
```

`client` and `market` imply which way they connect; any other name needs
`dials=out` (it dials in as a client would) or `dials=in` (it waits, as a
market does). Every send and read tool takes `endpoint`, defaulting to the
usual side for that message, so a scenario with one client needs none of this.

A name already connected is refused rather than replacing what is running, and
`status` lists whatever is up.

**Each endpoint needs its own CompID, and the system under test needs a session
for it.** A CompID identifies one session, so two endpoints claiming the same
one fight over it: both report `connecting`, each logon knocking the other off,
and neither settles. The symptom reads like the second endpoint broke the
first. Give the second client a CompID of its own — and check the system under
test has a session configured to accept it, or its logon is dropped with no
answer.

## One order, step by step

Take one step at a time and read the traffic before the next: each step's
output names the id the following step needs.

**1. Send an order in, as a client.**

```
harness_clear_traffic
harness_send_order clOrdId=S-1 symbol=BP side=buy quantity=1000 price=50 onBehalfOf=FUNDX
```

`onBehalfOf` is FIX tag 115. A router serving many clients over one session
identifies them by it and may drop what it cannot attribute — the symptom is an
order that leaves the client side and never reaches the market side.

**2. Read what the system forwarded.**

```
harness_traffic side=market msgType=D
```

The `ClOrdID(11)` here is the system's **own** id, not the one sent in. Every
market-side reply must name that one, and every report coming back to the
client should name the client's id again. That translation is what a router
most often gets wrong.

**3. Answer as the market.**

```
harness_send_execution clOrdId=<the system's id> orderId=MKT-1 symbol=BP side=buy orderQty=1000 execType=new ordStatus=new leavesQty=1000 price=50
```

Nothing is derived: `execType` and `ordStatus` are sent exactly as given,
including combinations a real venue would never produce, because how the system
handles those is what a conformance test is for.

**4. Check what reached the client.**

```
harness_traffic side=client msgType=8
```

## What to check

| Field | Why |
|---|---|
| `ClOrdID(11)` | must be the client's own id, not the venue's |
| `CumQty(14)` | total traded so far; it accumulates and never exceeds `OrderQty` |
| `LeavesQty(151)` | what is still working; `CumQty + LeavesQty = OrderQty` |
| `OrdStatus(39)` | `0` new, `1` partially filled, `2` filled, `4` cancelled, `8` rejected |
| `CxlRejResponseTo(434)` | on a `9`, `1` refuses a cancel and `2` a replace — the only field that says which |

A cancel confirmation must still carry what already traded. Cancelling stops
the rest; it does not undo what was done, and a router reporting zero there
tells its client a position vanished.

**That is on the sender, too.** `cumQty` is optional on
`harness_send_execution`, and omitting it sends zero — a claim that nothing has
traded, not a blank. Carry it on every report of an order that has traded,
cancels and rejections included; the harness does not remember what it filled a
moment ago. `leavesQty` is the one figure it will work out for you
(`orderQty - cumQty`) when you leave it off.

## The rest of the lifecycle

```
# amend
harness_send_replace clOrdId=S-2 origClOrdId=S-1 symbol=BP side=buy quantity=800 price=51 onBehalfOf=FUNDX
harness_traffic side=market msgType=G

# cancel
harness_send_cancel clOrdId=S-3 origClOrdId=S-1 symbol=BP side=buy quantity=1000 onBehalfOf=FUNDX
harness_traffic side=market msgType=F

# refuse a cancel, as a market would
harness_send_cancel_reject clOrdId=<its cancel id> origClOrdId=<the order it named> orderId=MKT-1 responseTo=cancel ordStatus=new reason="too late"
```

Message types: `D` new order, `F` cancel request, `G` cancel/replace request,
`8` execution report, `9` cancel reject.

**A request the system refuses is answered, not ignored.** A cancel or an
amendment the system will not act on should come back as a `9` naming which of
the two it refuses in `434`. Silence there is a finding: the system decided
something and told nobody.

## Running the whole thing at once

`scripts/harness-demo.py` walks five orders — partial then full fill, an
amendment, a cancel that keeps what traded, a refused cancel, and a rejection —
and reports which checks failed. Point it at another system by changing
`CLIENT_PORT` and `MARKET_PORT` at the top; `FIXPROBE_PORT` chooses which probe
runs it.

## Diagnosing, from what crossed the wire

This is what the harness alone can see, which is less than what happened. Most
of these have several causes that look identical from out here; whether they can
be told apart depends on what the system under test will tell you about itself.
If it is a NEXUM, `nexum-operate` reads its side of the same events.

**An order left the client and never reached the market.** Not necessarily a
fault. Three different things look identical from here: the system refused it,
the system routed it to a venue whose session is down, or no routing rule
matched it at all. The harness cannot tell them apart — every one of them is
"nothing arrived". Check whether anything came back on the client side
(`harness_traffic side=client msgType=8`), then ask the system.

A missing or wrong `onBehalfOf` is the most common cause: a router that cannot
attribute an order to a client drops it, and the drop is quiet.

**A market-side reply is refused.** Either that side is not logged on yet, or
the `clOrdId` is the client's rather than the one the system forwarded — read
`harness_traffic side=market msgType=D` and take `ClOrdID(11)` from there.

**A reply came back naming an id nobody sent.** The translation between the
client's id and the system's own is where routers go wrong most often. Both
directions are worth checking on every report.

**The market side will not bind.** Something else holds that port; a probe from
an earlier scenario is the usual culprit.

**Two endpoints both stuck at `connecting`.** They share a CompID and are
knocking each other off. One CompID, one session.

**An empty line instead of a result.** Hand-built JSON, not the harness script.
Use `scripts/harness`.

## What not to do

- **Do not retry a refused order unchanged.** A venue that refused it once
  refuses it again. Change what it objected to, or ask why.
- **Do not read the system under test's configuration to infer its topology.**
  It may not be what is actually running, and on a real engagement it is not
  yours to read. `harness_status` says what the harness holds; anything about
  the other side is a question for the other side.
- **Do not treat "nothing came back" as a rejection.** It is three faults
  wearing the same face, and they call for opposite responses. Find out which
  before changing the order.
