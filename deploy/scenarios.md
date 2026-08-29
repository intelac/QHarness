# Order scenarios

`Scenarios` drives named cases at a running NEXUM and says whether each behaved.
One order proves the path is open; these prove it behaves.

```bash
# on the server — the venue must be started with matching symbols first
docker run --rm --network host --entrypoint java nexum:0.1.0 \
  -cp /app/nexum.jar io.nexum.demo.Scenarios 127.0.0.1 9880 '<password>'

# or just some of them
... io.nexum.demo.Scenarios 127.0.0.1 9880 '<password>' amend-after-partial cancel-refused
```

## The venue's symbols

`VenueRunner` takes `SYMBOL:behaviour` pairs. The scenarios expect these:

```bash
java -cp /app/nexum.jar io.nexum.demo.VenueRunner 9881 \
  BP:rest GLEN:partial TSCO:reject RIO:refuse-cancel
```

| behaviour | what the venue does |
|---|---|
| `rest` | acknowledges and leaves it working |
| `partial` | trades a third, leaves the rest working |
| `reject` | refuses the order outright |
| `silent` | accepts it and never reports again |
| `refuse-cancel` | rests, and refuses any cancel |
| *(unnamed symbol)* | acknowledges, half-fills, then fills |

## The cases

| scenario | what it checks |
|---|---|
| `new-fill` | an ordinary order fills |
| `new-rest` | a resting order is acknowledged and stays working |
| `new-partial` | a partial reports what actually traded, not a full fill |
| `new-rejected` | an order the venue refuses comes back REJECTED |
| `cancel-resting` | a cancel against a working order is confirmed |
| `cancel-after-partial` | cancelling keeps the quantity already filled |
| `cancel-too-late` | a cancel against a filled order never reaches the venue |
| `cancel-rejected-order` | nor does one against a rejected order |
| `cancel-refused` | a refusal comes back as 35=9 with CxlRejResponseTo(434)=1 |
| `amend-resting` | a replace against a working order is accepted |
| `amend-after-partial` | a replace works against an order that already has a position |
| `amend-then-cancel` | a cancel quoting the *original* id still finds an order that was replaced |
| `amend-unknown` | an amendment for an order never sent does not reach the venue |

Three of these assert **silence** — that nothing reached the venue. Those are
the ones worth having: refusing a request the order should not have accepted is
invisible unless something checks that nothing happened.

## Why these particular cases

`cancel-after-partial` and `amend-after-partial` exist because the simulator
could not previously reach that state at all: a symbol either traded out
completely or never traded. An amendment against an order with a non-zero
cumQty and a live remainder had nothing to act on, which is the state most
worth exercising.

`amend-then-cancel` covers identifier bookkeeping. After a replace the venue
knows the order by the replace's ClOrdID, but the client may still quote the
one it first used — both must find the same order.

## Restarting the venue desynchronises the session

`VenueRunner` keeps its sequence numbers in memory, so restarting it resets the
venue to 1 while NEXUM — which persists them — carries on from where it was.
NEXUM then correctly ignores reports whose sequence numbers have gone backwards,
and orders sit in PENDING_NEW with `reports: 0` while the venue's own log shows
it answered.

That is the FIX session layer behaving properly, not a fault. After restarting
the venue, restart NEXUM too:

```bash
docker compose -f /root/nexum/docker-compose.prod.yml restart
```

Both sides use `ResetOnLogon=Y`, so a fresh logon puts them back in step.
