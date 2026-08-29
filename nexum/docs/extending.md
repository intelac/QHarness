# Extending NEXUM

Everything below is a seam that already exists. Nothing here requires editing a
file that ships with the system — if you find yourself doing that to add
behaviour, the seam is missing and that is worth reporting.

## The four layers

A message crosses four layers on the way in, and the same four in reverse on the
way out:

```
              inbound                          outbound
  wire → SESSION → CLIENT → ROUTING → DESTINATION → wire
  wire ← SESSION ← CLIENT  ·············  DESTINATION ← wire
```

`SESSION` is a physical socket. `CLIENT` and `DESTINATION` are resolved by
fingerprint — several clients can share one session, and routing picks a
destination per message. Outbound crosses the counterparty layer first and the
session layer last, so a session plugin always sees the finished message and has
the final say before the wire.

Mount a plugin on a layer with a scope:

```java
ctx.onGate(TransportEvents.MESSAGE_INBOUND, Scope.client("FUND_X"),
        (Events.Gate<TransportEvents.InFlight>) (flight, next) -> {
            if (flight.message().get(FixTags.CURRENCY) == null) {
                return next.apply(flight.with(
                        flight.message().set(FixTags.CURRENCY, "USD")));
            }
            return next.apply(flight);
        });
```

Three things a gate can do:

| | how | effect |
|---|---|---|
| pass through | `next.apply(flight)` | the next plugin runs |
| rewrite | `next.apply(flight.with(rewritten))` | downstream sees your version |
| hold back | `flight.reject("why")` | nothing further runs, nothing is sent |

`reject` takes a reason because it ends up in an operator's hands. "rejected" on
its own is not something anyone can act on.

## Adding a message type

Write a `MessageHandler` and register it. This is the whole of it — no file in
`io.nexum.routing` changes:

```java
public final class MassCancelHandler implements MessageHandler {

    @Override
    public Set<String> handles() {
        return Set.of("q");
    }

    @Override
    public void handle(Context ctx, OrderServices services,
                       TransportEvents.InFlight arrival) {
        String venue = services.router()
                .toDestination(arrival.message())
                .orElse(null);
        if (venue == null) {
            return;
        }
        String ours = services.wireIds().forCancel();
        OutboundPath.toDestination(ctx, services.transport(), venue, venue,
                arrival.message().set(FixTags.CL_ORD_ID, ours));
    }
}
```

Register it from a plugin, through `effect` so unloading removes it:

```java
public final class MassCancelPlugin implements Plugin {

    public String name() {
        return "mass-cancel";
    }

    public List<String> inject() {
        return List.of("handlers");
    }

    public void apply(Context ctx) {
        HandlerRegistry handlers = ctx.get("handlers");
        ctx.effect(() -> handlers.register(new MassCancelHandler()));
    }
}
```

`inject` matters: it is what makes the loader run `order-pipeline` first, so
`handlers` exists by the time this asks for it.

Two handlers claiming the same type at the same `order()` is refused rather than
resolved. Silently preferring one would make which handler runs depend on load
order, which is not something anyone can debug. Give one a different `order()`
if you genuinely mean to layer them.

`OrderServices` carries what a handler needs: the cache, the book, the identity
resolver, the wire-id minter, the router, the transport and the journal. Send
through `OutboundPath` rather than `transport.send` directly — the transport
takes a session id and cannot run the counterparty layers.

## Listening to what happens

Events are declared as typed keys, so the compiler binds the name and the
payload together. Subscribing to a name nothing publishes was a real bug here
once — a monitor watched `order/updated`, which no longer existed, and reported
a null venue id for months.

```java
ctx.on(OrderEvents.STATE_CHANGED, (OutboundEvent.StateChanged changed) ->
        log.info("{} {} -> {}", changed.orderId(), changed.from(), changed.to()));
```

The catalogues are `OrderEvents`, `RoutingEvents` and `TransportEvents`. Read
them rather than this table, which will fall behind; these are the ones worth
knowing about:

| key | published when |
|---|---|
| `OrderEvents.CREATED` | an order is accepted and identified |
| `OrderEvents.STATE_CHANGED` | the state machine advanced |
| `OrderEvents.QUANTITY_CHANGED` | a fill moved the position without changing state |
| `OrderEvents.REQUEST_SENT` / `REQUEST_ANSWERED` | a cancel or replace went out / was answered |
| `OrderEvents.DISAGREEMENT` | the venue said something the order cannot reconcile |
| `OrderEvents.REPORT_UNMATCHED` | a report arrived for an order we do not know |
| `RoutingEvents.RULE_UNMATCHED` | no client or destination matched |
| `RoutingEvents.MESSAGE_UNHANDLED` | no handler claims that message type |

The last three are the ones to alert on. They mean a message reached the system
and nothing sensible happened to it.

## Replacing a piece

Registered under a name, so a plugin providing the same name replaces it:

| name | interface | what a replacement is for |
|---|---|---|
| `transport` | `Transport` | a different FIX engine |
| `journal` | `OrderJournal` | order history to a database or a queue |
| `orders` | `OrderCache` | a shared or distributed cache |
| `router` | `Router` | routing that is not fingerprint-based |

`OrderJournal` is one method. What reaches the wire is recorded first, so an
implementation that buffers is choosing to lose the tail of a crash.

## Dialects

A FIX version supplies the baseline. A session that deviates states only its
deviations, as an overlay:

```java
ctx.<DialectRegistry>get("dialects").overlay("OMS->LSE", DialectOverlay.builder()
        .field(9303, "VenueSpecificFlag")
        .group(FixTags.NO_PARTY_IDS, 448, 447, 452)
        .build());
```

Repeating groups need a template. Without one the parser cannot know where a
group ends, and a message will be silently misread rather than rejected.

## Rules worth knowing

**What an event means is the order's decision.** A handler tells the order what
arrived and acts on what it concluded. Deciding in a handler whether a cancel is
legal is how one call site comes to disagree with another.

**Journal before you send.** An order the venue knows about and this system has
forgotten cannot be recovered.

**ExecType(150) over OrdStatus(39).** OrdStatus is a summary the venue chose. On
a Cancel Reject it still reads `New`.

**ClOrdID(11) over OrderID(37) when matching a report.** Every ClOrdID this
system puts on the wire was minted here and never reissued. OrderID belongs to
the venue, and a venue that has restarted hands out the same one again —
preferring it sends a report to whichever order claimed that id first and
leaves the right order waiting for an acknowledgement that already arrived and
went elsewhere. OrderID is still what matches a report that carries no ClOrdID
of ours.

**Clients never see our identifiers.** Ours go to venues, the client's come back
to clients, and the cache maps between them.

---

The examples above are compiled and run by `ExtendingDocTest`. If one stops
working, that test fails before a reader finds out the slow way.

## Reading an order's history

Every entry in the monitor reads the same way — the event, then the state it
left the order in:

```
ACCEPTED_FROM_CLIENT     →  PENDING_NEW
ACK                      →  NEW
PARTIAL_FILL  300        →  PARTIALLY_FILLED
FILL  600                →  FILLED
CANCEL_PENDING           →  PENDING_CANCEL
CANCELLED                →  CANCELED
```

Both values come off the event itself: `OrderEventType` is what the state
machine recognised, and the state is what it decided.

**They are displayed under different names than the enums carry**, through
`OrderEventType.label()` and `OrderState.label()`. Seven event names are also
state names — `REPLACED`, `EXPIRED`, `SUSPENDED`, `STOPPED`, `CALCULATED`,
`DONE_FOR_DAY`, `ACCEPTED_FOR_BIDDING` — and the event `CANCELLED` differs from
the state `CANCELED` by one letter. Rendered alike, a row reads
`REPLACED → REPLACED` and says nothing about which half is which.

Events are named the way FIX names them, so a history reads in the same words
as the specification and a counterparty's onboarding pack: `new order single`
(35=D), `order cancel request` (35=F), `execution report: partial fill`
(150=1). States are named for what they are rather than what FIX calls them —
`NEW` means an order the venue is working, which reads as its opposite to
anyone who has not spent years with the protocol, so it displays as
`on market`.

The enum names themselves do not change: they are what the state machine, the
journal and every test are written against.

Clicking an entry opens the message behind it, tag by tag: the number, the
field name, the value, and what that value means where the value is a code
(`39=1` → `PartiallyFilled`, `434=1` → `OrderCancelRequest`). Session fields
are dimmed rather than hidden — they are how a message is matched to a line in
the engine's own log.

Names come from `FixDictionary`, which carries the standard FIX 4.4 fields. A
tag it does not know is still shown as its number: an unrecognised tag is
exactly the one someone needs to go and ask about.
