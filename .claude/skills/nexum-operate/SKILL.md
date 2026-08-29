---
name: nexum-operate
description: Ask a running NEXUM what it is doing and why an order went the way it did — list sessions and how they are wired, work out where an order would route before sending it, read an order's history, and tell apart the endings that look alike. Use when diagnosing or operating a NEXUM deployment. For driving FIX traffic into an order router as a counterparty, use fix-harness.
---

# Asking NEXUM what it is doing

These tools read a running engine. They are `mcp__nexum__*`, they answer from
what the process currently holds rather than from a file on disk, and the
answer is current in a way configuration is not.

| To find out | Call |
|---|---|
| What sessions exist and how each is wired | `list_sessions` |
| Where an order would go, before sending it | `explain_routing` |
| What is live right now | `list_orders` |
| One order's current state | `get_order` |
| Everything that happened to an order | `order_events` |
| Add or drop a counterparty while it runs | `create_session`, `remove_session`, `list_added_sessions` |

`place_order`, `amend_order` and `cancel_order` also exist. They inject a
request inside the engine, which is right for operating a deployment and wrong
for testing one — an order that did not arrive over FIX has skipped the part a
test is checking. See `fix-harness` for sending as a real counterparty.

## Naming an order

One order answers to four different identifiers, and which one you are holding
depends on which message you just read:

- the identity this system minted — `20260828:OMS->FUNDX:CLIENT-1`
- the `ClOrdID` the client sent
- the `ClOrdID` this system put on the wire to the venue
- the venue's own `OrderID`

**Any of the four works.** `get_order`, `order_events`, `amend_order` and
`cancel_order` try each in turn. Reading `11=O0000001` off the venue side and
asking for that is correct — it does not need translating first.

An order not found is either an id nothing ever used or an order that has
already been released: settled orders are dropped about half an hour after
their last report, and only the journal remains. The refusal says which.

`list_orders` shows what is live, which is not everything that exists.

## Where an order would go

An order's venue is decided by what it carries, not by the session it arrives
on: one connection can serve several clients, and the venue is a business
decision rather than a property of the link. So a session being logged on does
not mean orders reach it, and reading the session list will not say where an
order goes.

`explain_routing` answers that without sending anything.

```
explain_routing
explain_routing symbol=BP quantity=1000 price=50 onBehalfOf=FUNDX
```

With no arguments it lists every rule in the order they are tried, first match
winning. Describe an order and it reports the client it would be recognised as
and the destination it would take — and when it would go nowhere, which
condition failed and what the order carried instead. That turns "it did not
route" into something to act on.

It reads the same rules the engine routes on, so it cannot disagree with what
would actually happen.

Worth asking **before** sending an order into a deployment whose configuration
you did not write, and before concluding an order was refused: an order that
never matched a destination rule was never refused by anyone.

The rules match on FIX tags only, and only on top-level ones — a repeating
group's contents are deliberately out of reach. Anything a rule reads that has
no parameter of its own goes in `fields` as `tag=value` pairs:

```
explain_routing symbol=BP fields=100=XLON,15=GBP
```

This says where an order would go, not whether it would arrive. Those are
different questions, and `list_sessions` owns the second.

## How a session is wired

`list_sessions` reports each session's role, port, and — for an initiator —
where it dials. That is what a counterparty needs to connect, and it comes from
the running engine rather than from the configuration file, which may not be
what is running.

The session id names both CompIDs and **they are crossed** from the
counterparty's point of view: `OMS->FUNDX` means the engine calls itself `OMS`
and expects `FUNDX`, so a counterparty connecting to it is
`senderCompId=FUNDX targetCompId=OMS`.

A session reporting port `0` and role `unknown` is one the engine knows exists
and nothing about how it is reached. That is an admitted gap, not a default.

## Adding a counterparty while it runs

```
create_session sessionId=OMS->NEWVENUE role=acceptor port=19895 version=FIX44
list_added_sessions
remove_session sessionId=OMS->NEWVENUE
```

An acceptor listens for a counterparty; an initiator dials out to `host` and
`port`. These live until the process ends and are not written to the
configuration file — a session that should survive a restart belongs there,
added by whoever runs the deployment.

## What an order's ending means

An order ends in one of these. They look similar and call for opposite
responses, so read the state before deciding anything.

| It says | What happened | What to do |
|---|---|---|
| `not sent` | The order never left this system. No venue saw it. | Nothing is wrong with the order. Check whether the destination session is logged on; the same order goes out unchanged once it is. |
| `rejected` | A venue looked at the order and refused it. | Something about the order: the symbol, the price, the account. Read the text on the report. |
| `on market` | Acknowledged and working. | Wait, or amend or cancel it. |
| `partial fill` | Some quantity traded; the rest is still working. | Nothing yet. `cumQty` is the running total, not the last trade. |
| `fully filled` | Done. | Nothing. |
| `cancelled` | Stopped. Anything already traded still stands. | Check `cumQty` — cancelling does not undo a fill. |

**`not sent` is not a rejection.** Told it was refused, the natural move is to
go hunting for a fault in the order — a wrong symbol, a client that is not
recognised — and there is none to find. Two things stop an order here, and
they are not the same: it was routed and the link was down, or it was never
routed at all. `list_sessions` answers the first, `explain_routing` the
second. Ask both before changing anything about the order.

## Reading an order's history

`order_events` folds the journal back into what happened, in order. The journal
is the record and the cache is a convenience, so this is the account that
survives a restart.

Each event carries the messages that caused it, prefixed by direction:

| Prefix | Means |
|---|---|
| `c.` | what the client sent |
| `d.` | what went out to the venue |
| `m.` | what the venue sent back |
| `r.` | what was sent back to the client |

An event with a `c.` and no `r.` on a request that was refused means the engine
decided something and told nobody — that is a bug, not a state.

## Diagnosing, in the order worth trying

**An order was placed and nothing came back.** `order_events` first — the
reason is on the last state change. Then `list_sessions`: an order cannot be
answered by a venue this deployment is not connected to. If it never routed,
`explain_routing` with the same fields says which condition failed.

**A request was refused and it is not clear why.** `order_events` carries the
reason on the state change. For a cancel or an amendment, `434` on the reject
says which of the two was refused — `1` a cancel, `2` a replace — and the order
itself is untouched either way.

**An amendment or cancel disappeared.** Check whether one was already
outstanding. A second request while one is pending is refused rather than
queued, and the refusal goes back as a `9`.

**An order shows quantities that do not add up.** `CumQty + LeavesQty` should
equal the current `OrderQty`, and an accepted amendment changes `OrderQty`. A
filled quantity larger than the order usually means an amendment moved the
identifier without moving the terms.

## When the engine's own account is not enough

These tools see what the engine did. They do not see what actually crossed the
wire, and the two can differ — that gap is where the interesting faults live.

Reach for `fix-harness` and a probe when:

- **The engine says it sent something and the counterparty says it never
  arrived.** `order_events` shows a `d.` and the venue disagrees. Only a probe
  standing in for that venue settles it.
- **A counterparty cannot log on.** The engine reports the session down and
  nothing about why. A probe dialling the same port with the same CompIDs
  reproduces it in isolation, without waiting on the other firm.
- **A fix needs proving.** Reading the journal says what happened; sending the
  same sequence in again says whether it still happens.
- **The order has to arrive over FIX.** `place_order` skips the session, the
  routing decision on an inbound message, and the id translation. When any of
  those is what is in question, the order has to come in as a client's.

Point a probe at the port and CompIDs `list_sessions` reports, and remember
they are crossed: a probe connecting to `OMS->FUNDX` is `senderCompId=FUNDX
targetCompId=OMS`.

The reverse also holds. A probe watching a NEXUM sees "nothing came back" and
cannot tell a refusal from a routing miss from a downed venue — `order_events`
and `explain_routing` are what separate them. Neither side answers the whole
question alone.

## What not to do

- **Do not build sessions to work around a failure.** `create_session` adds a
  counterparty this deployment should have; it does not fix an order, and a
  session invented mid-diagnosis leaves the deployment holding something nobody
  configured.
- **Do not read the configuration file to infer topology.** `list_sessions`
  answers what is connected and `explain_routing` answers what routes where.
  Both are current; the file may not be what is running.
- **Do not conclude an order was released because a lookup failed.** Any of its
  four identifiers finds it. A failed lookup that named one of them is
  something else.
