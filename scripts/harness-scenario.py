#!/usr/bin/env python3
"""One scenario that exercises every harness tool against a router.

A single order's life, told from both sides of the system under test, using
each of the thirteen tools at the point in the story where it belongs rather
than calling them in a list to tick them off:

    connect (x2), status          stand on both sides of the system
    create_session                a venue asks to be added mid-session
    list_added_sessions           confirm it joined
    clear_traffic                 start the story clean
    send_order                    the client buys
    traffic                       see what the system forwarded, and under
                                  which id
    send_execution                the market acknowledges, then trades
    send_replace                  the client amends what is left
    send_cancel                   the client tries to cancel the amended order
    send_cancel_reject            the market refuses, order still working
    send_execution                the market fills the rest anyway
    remove_session                the venue leaves
    disconnect (x2)               both sides come down

Every step asserts on what came back, so a green run means the router mapped
its ids both ways and kept the quantities straight — not merely that the calls
returned.

Point it at another system with CLIENT_PORT and MARKET_PORT: the client side
dials the port the system listens on, the market side listens on the port it
dials out to.
"""
from __future__ import annotations

import json
import re
import sys
import time
import urllib.request

import os

# The probe, not the engine: harness tools live in their own process now.
MCP = "http://127.0.0.1:%s/mcp" % os.environ.get("FIXPROBE_PORT", "18099")

CLIENT_PORT = 19890
MARKET_PORT = 19891
CLIENT_COMP_ID = "FUNDX"
MARKET_COMP_ID = "LSE"
SYSTEM_COMP_ID = "OMS"

# A session brought up mid-scenario, on a port nothing else holds.
EXTRA_SESSION = "OMS->EXTRAVENUE"
EXTRA_PORT = 19895

SOH = "\x01"
_id = 0
_used: set[str] = set()
_failures: list[str] = []
_step = 0


def call(tool: str, **arguments) -> dict:
    global _id
    _id += 1
    _used.add(tool)
    body = {"jsonrpc": "2.0", "id": _id, "method": "tools/call",
            "params": {"name": tool, "arguments": arguments}}
    request = urllib.request.Request(
        MCP, data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json",
                 "Accept": "application/json, text/event-stream"})
    with urllib.request.urlopen(request, timeout=40) as response:
        payload = json.load(response)
    if "error" in payload:
        return {"error": True, "text": json.dumps(payload["error"]), "data": {}}
    result = payload.get("result", {})
    return {"error": result.get("isError", False),
            "text": (result.get("content") or [{}])[0].get("text", ""),
            "data": result.get("structuredContent") or {}}


def step(title: str) -> None:
    global _step
    _step += 1
    print(f"\n{_step:>2}. {title}")


def ok(what: str) -> None:
    print(f"      ok    {what}")


def fail(what: str) -> None:
    print(f"      FAIL  {what}")
    _failures.append(what)


def note(what: str, actual, expectation: str) -> None:
    """Report a value the scenario observes without judging it.

    Used where the right answer is a decision about the system under test
    rather than something this can settle — asserting either way would turn an
    open question into a passing or failing test.
    """
    print(f"      note  {what} = {actual}   ({expectation})")


def check(what: str, actual, expected) -> None:
    if actual == expected:
        ok(f"{what} = {actual}")
    else:
        fail(f"{what} = {actual!r}, expected {expected!r}")


def tag(raw: str, number: str) -> str | None:
    found = re.search(rf"(?:^|{SOH}){number}=([^{SOH}]*)", raw)
    return found.group(1) if found else None


def awaiting(side: str, msg_type: str, matches, seconds: float = 20) -> str | None:
    deadline = time.time() + seconds
    while time.time() < deadline:
        rows = call("harness_traffic", side=side, msgType=msg_type)["data"].get("messages") or []
        for row in rows:
            if matches(row["raw"]):
                return row["raw"]
        time.sleep(0.4)
    return None


def main() -> None:
    print("=" * 70)
    print("Every harness tool, in one order's life")
    print("=" * 70)

    # ---- standing on both sides -------------------------------------
    step("bring both sides up (market first: the system dials out to it)")
    call("harness_disconnect", side="client")
    call("harness_disconnect", side="market")
    market = call("harness_connect", side="market", port=MARKET_PORT,
                  senderCompId=MARKET_COMP_ID, targetCompId=SYSTEM_COMP_ID)
    client = call("harness_connect", side="client", port=CLIENT_PORT,
                  senderCompId=CLIENT_COMP_ID, targetCompId=SYSTEM_COMP_ID)
    if market["error"] or client["error"]:
        fail(f"could not connect: {market['text']} / {client['text']}")
        report()
        return
    ok("both sides started")

    step("wait for both sides to log on")
    for _ in range(40):
        status = call("harness_status")
        if status["text"].count("logged on") == 2:
            break
        time.sleep(0.5)
    else:
        fail(f"never logged on: {call('harness_status')['text']}")
        report()
        return
    for line in call("harness_status")["text"].splitlines():
        ok(line)

    # ---- a venue joins mid-session ----------------------------------
    step("a venue asks to be added while the engine runs")
    call("remove_session", sessionId=EXTRA_SESSION)
    created = call("create_session", sessionId=EXTRA_SESSION, role="acceptor",
                   port=EXTRA_PORT, version="FIX44")
    if created["error"]:
        fail(created["text"])
    else:
        ok(created["text"])

    step("confirm it joined")
    listed = call("list_added_sessions")
    if EXTRA_SESSION in listed["text"]:
        ok(f"{EXTRA_SESSION} is listed")
    else:
        fail(f"not listed: {listed['text']}")

    # ---- the order's life -------------------------------------------
    step("start the story clean")
    ok(call("harness_clear_traffic")["text"])

    step("the client buys 1000 BP at 50")
    sent = call("harness_send_order", clOrdId="X-1", symbol="BP", side="buy",
                quantity=1000, price=50, onBehalfOf=CLIENT_COMP_ID)
    if sent["error"]:
        fail(sent["text"])
        report()
        return
    ok(sent["text"])

    step("see what the system forwarded, and under which id")
    forwarded = awaiting("market", "D", lambda raw: tag(raw, "55") == "BP")
    if forwarded is None:
        fail("the order never reached the market side")
        report()
        return
    venue_id = tag(forwarded, "11")
    ok(f"forwarded as {venue_id} (the client sent X-1)")
    check("forwarded OrderQty", tag(forwarded, "38"), "1000")
    check("forwarded Price", tag(forwarded, "44"), "50")
    check("forwarded OnBehalfOf", tag(forwarded, "115"), CLIENT_COMP_ID)

    step("the market acknowledges")
    ok(call("harness_send_execution", clOrdId=venue_id, orderId="MKT-X",
            symbol="BP", side="buy", orderQty=1000, execType="new",
            ordStatus="new", leavesQty=1000, price=50)["text"])
    acked = awaiting("client", "8", lambda raw: tag(raw, "39") == "0")
    if acked is None:
        fail("the acknowledgement never reached the client")
    else:
        check("ack ClOrdID mapped back", tag(acked, "11"), "X-1")
        check("ack OrderID", tag(acked, "37"), "MKT-X")

    step("the market trades 400 of the 1000")
    ok(call("harness_send_execution", clOrdId=venue_id, orderId="MKT-X",
            symbol="BP", side="buy", orderQty=1000, execType="trade",
            ordStatus="partially_filled", lastQty=400, cumQty=400,
            leavesQty=600, price=50)["text"])
    partial = awaiting("client", "8", lambda raw: tag(raw, "39") == "1")
    if partial is None:
        fail("the partial fill never reached the client")
    else:
        check("partial ClOrdID", tag(partial, "11"), "X-1")
        check("partial CumQty", tag(partial, "14"), "400")
        check("partial LeavesQty", tag(partial, "151"), "600")

    step("the client amends the rest down to 800 at 51")
    amended = call("harness_send_replace", clOrdId="X-2", origClOrdId="X-1",
                   symbol="BP", side="buy", quantity=800, price=51,
                   onBehalfOf=CLIENT_COMP_ID)
    if amended["error"]:
        fail(amended["text"])
    else:
        ok(amended["text"])
    replace_out = awaiting("market", "G", lambda raw: tag(raw, "55") == "BP")
    if replace_out is None:
        fail("the amendment never reached the market side")
        report()
        return
    amend_id = tag(replace_out, "11")
    ok(f"forwarded as {amend_id}, amending {tag(replace_out, '41')}")
    check("amended OrderQty", tag(replace_out, "38"), "800")

    step("the market confirms the amendment, keeping what traded")
    ok(call("harness_send_execution", clOrdId=amend_id, origClOrdId=venue_id,
            orderId="MKT-X", symbol="BP", side="buy", orderQty=800,
            execType="replaced", ordStatus="replaced", cumQty=400,
            leavesQty=400, price=51)["text"])
    replaced = awaiting("client", "8", lambda raw: tag(raw, "39") == "5")
    if replaced is None:
        fail("the replace confirmation never reached the client")
    else:
        check("replaced ClOrdID", tag(replaced, "11"), "X-2")
        check("replaced OrigClOrdID", tag(replaced, "41"), "X-1")
        # An amendment does not undo a position.
        check("replaced CumQty survives", tag(replaced, "14"), "400")
        check("replaced LeavesQty", tag(replaced, "151"), "400")

    step("the client tries to cancel what is left")
    cancelled = call("harness_send_cancel", clOrdId="X-3", origClOrdId="X-2",
                     symbol="BP", side="buy", quantity=800,
                     onBehalfOf=CLIENT_COMP_ID)
    if cancelled["error"]:
        fail(cancelled["text"])
    else:
        ok(cancelled["text"])
    cancel_out = awaiting("market", "F", lambda raw: tag(raw, "55") == "BP")
    if cancel_out is None:
        fail("the cancel never reached the market side")
        report()
        return
    ok(f"forwarded as {tag(cancel_out, '11')}, cancelling {tag(cancel_out, '41')}")

    step("the market refuses the cancel")
    ok(call("harness_send_cancel_reject", clOrdId=tag(cancel_out, "11"),
            origClOrdId=tag(cancel_out, "41"), orderId="MKT-X",
            responseTo="cancel", ordStatus="partially_filled",
            reason="too late to cancel")["text"])
    reject = awaiting("client", "9", lambda raw: True)
    if reject is None:
        fail("the refusal never reached the client")
    else:
        # 434 is the only field that says a cancel was refused rather than a
        # replace: OrdStatus reports the order's own state, which a refusal
        # leaves alone.
        check("refusal CxlRejResponseTo", tag(reject, "434"), "1")
        check("refusal OrigClOrdID", tag(reject, "41"), "X-2")

    step("the order fills anyway, since the cancel was refused")
    ok(call("harness_send_execution", clOrdId=amend_id, orderId="MKT-X",
            symbol="BP", side="buy", orderQty=800, execType="trade",
            ordStatus="filled", lastQty=400, cumQty=800, leavesQty=0,
            price=51)["text"])
    filled = awaiting("client", "8", lambda raw: tag(raw, "39") == "2")
    if filled is None:
        fail("the fill never reached the client")
    else:
        check("filled ClOrdID", tag(filled, "11"), "X-2")
        check("filled CumQty", tag(filled, "14"), "800")
        check("filled LeavesQty", tag(filled, "151"), "0")

    # ---- clearing up -------------------------------------------------
    step("the venue leaves")
    removed = call("remove_session", sessionId=EXTRA_SESSION)
    if removed["error"]:
        fail(removed["text"])
    else:
        ok(removed["text"])

    step("both sides come down")
    ok(call("harness_disconnect", side="client")["text"])
    ok(call("harness_disconnect", side="market")["text"])
    after = call("harness_status")
    if after["text"].count("not started") == 2:
        ok("neither side is up")
    else:
        fail(f"still up: {after['text']}")

    report()


def report() -> None:
    expected = {
        "harness_connect", "harness_disconnect", "harness_status",
        "harness_clear_traffic", "harness_traffic", "harness_send_order",
        "harness_send_replace", "harness_send_cancel", "harness_send_execution",
        "harness_send_cancel_reject", "create_session", "remove_session",
        "list_added_sessions",
    }
    missed = expected - _used
    print("\n" + "=" * 70)
    print(f"tools exercised: {len(_used & expected)}/{len(expected)}")
    if missed:
        print("never called: " + ", ".join(sorted(missed)))
        _failures.append(f"tools never called: {sorted(missed)}")
    if _failures:
        print(f"\n{len(_failures)} check(s) failed:")
        for failure in _failures:
            print(f"  - {failure}")
        sys.exit(1)
    print("every check passed")


if __name__ == "__main__":
    main()
