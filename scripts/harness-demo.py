#!/usr/bin/env python3
"""Walk a full order lifecycle through a router, from both sides of it.

Drives the harness over MCP with the calls an agent makes. Each scenario sends
a request in as a client, answers as the market, and checks what the router
passed back — the id it used, and the quantities it carried.

Five orders, covering what an order does in its life:

    A  new -> ack -> partial fill -> full fill
    B  new -> ack -> amend up -> fill at the amended quantity
    C  new -> ack -> partial fill -> cancel, keeping what already traded
    D  new -> ack -> cancel refused, order still working
    E  new -> rejected outright

Point it at another system by changing CLIENT_PORT and MARKET_PORT: the client
side dials the port the system listens on, the market side listens on the port
it dials out to.
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

# The system under test: it listens here for clients, and dials out to there.
CLIENT_PORT = 19890
MARKET_PORT = 19891

CLIENT_COMP_ID = "FUNDX"
MARKET_COMP_ID = "LSE"
SYSTEM_COMP_ID = "OMS"

SOH = "\x01"
_id = 0
_failures: list[str] = []


def call(tool: str, **arguments) -> dict:
    global _id
    _id += 1
    body = {"jsonrpc": "2.0", "id": _id, "method": "tools/call",
            "params": {"name": tool, "arguments": arguments}}
    request = urllib.request.Request(
        MCP, data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json",
                 "Accept": "application/json, text/event-stream"})
    with urllib.request.urlopen(request, timeout=40) as response:
        payload = json.load(response)
    result = payload.get("result", {})
    return {"error": result.get("isError", False),
            "text": (result.get("content") or [{}])[0].get("text", ""),
            "data": result.get("structuredContent") or {}}


def tag(raw: str, number: str) -> str | None:
    found = re.search(rf"(?:^|{SOH}){number}=([^{SOH}]*)", raw)
    return found.group(1) if found else None


def check(what: str, actual, expected) -> bool:
    if actual == expected:
        print(f"        ok    {what} = {actual}")
        return True
    print(f"        FAIL  {what} = {actual!r}, expected {expected!r}")
    _failures.append(f"{what}: {actual!r} != {expected!r}")
    return False


def awaiting(side: str, msg_type: str, matches, seconds: float = 20) -> str | None:
    """Wait for a message the scenario is about, so nothing races the wire."""
    deadline = time.time() + seconds
    while time.time() < deadline:
        rows = call("harness_traffic", side=side, msgType=msg_type)["data"].get("messages") or []
        for row in rows:
            if matches(row["raw"]):
                return row["raw"]
        time.sleep(0.4)
    return None


def send_order(cl_ord_id: str, symbol: str, quantity: int, price: float) -> str | None:
    """Send an order in and return the id the router used towards the market."""
    call("harness_clear_traffic")
    sent = call("harness_send_order", clOrdId=cl_ord_id, symbol=symbol, side="buy",
                quantity=quantity, price=price, onBehalfOf=CLIENT_COMP_ID)
    if sent["error"]:
        print(f"        FAIL  {sent['text']}")
        _failures.append(sent["text"])
        return None
    forwarded = awaiting("market", "D", lambda raw: tag(raw, "55") == symbol)
    if forwarded is None:
        print("        FAIL  the order never reached the market side")
        _failures.append(f"{cl_ord_id} never reached the market")
        return None
    return tag(forwarded, "11")


def ack(venue_id: str, symbol: str, quantity: int, price: float) -> None:
    call("harness_send_execution", clOrdId=venue_id, orderId=f"MKT-{venue_id}",
         symbol=symbol, side="buy", orderQty=quantity,
         execType="new", ordStatus="new", leavesQty=quantity, price=price)


def scenario_a() -> None:
    print("\nA. new -> ack -> partial fill -> full fill")
    venue_id = send_order("A-1", "BP", 1000, 50)
    if venue_id is None:
        return
    print(f"        ok    router forwarded it as {venue_id}")
    ack(venue_id, "BP", 1000, 50)

    call("harness_send_execution", clOrdId=venue_id, orderId=f"MKT-{venue_id}",
         symbol="BP", side="buy", orderQty=1000, execType="trade",
         ordStatus="partially_filled", lastQty=400, cumQty=400, leavesQty=600, price=50)
    partial = awaiting("client", "8", lambda raw: tag(raw, "39") == "1")
    if partial:
        check("partial: ClOrdID", tag(partial, "11"), "A-1")
        check("partial: CumQty", tag(partial, "14"), "400")
        check("partial: LeavesQty", tag(partial, "151"), "600")
    else:
        _failures.append("A: no partial fill reached the client")
        print("        FAIL  no partial fill reached the client")

    call("harness_send_execution", clOrdId=venue_id, orderId=f"MKT-{venue_id}",
         symbol="BP", side="buy", orderQty=1000, execType="trade",
         ordStatus="filled", lastQty=600, cumQty=1000, leavesQty=0, price=50)
    filled = awaiting("client", "8", lambda raw: tag(raw, "39") == "2")
    if filled:
        check("filled: CumQty", tag(filled, "14"), "1000")
        check("filled: LeavesQty", tag(filled, "151"), "0")
    else:
        _failures.append("A: no full fill reached the client")
        print("        FAIL  no full fill reached the client")


def scenario_b() -> None:
    print("\nB. new -> ack -> amend -> fill at the amended quantity")
    venue_id = send_order("B-1", "VOD", 500, 40)
    if venue_id is None:
        return
    ack(venue_id, "VOD", 500, 40)
    awaiting("client", "8", lambda raw: tag(raw, "39") == "0")

    amended = call("harness_send_replace", clOrdId="B-2", origClOrdId="B-1", symbol="VOD",
                   side="buy", quantity=800, price=41, onBehalfOf=CLIENT_COMP_ID)
    if amended["error"]:
        print(f"        FAIL  {amended['text']}")
        _failures.append(amended["text"])
        return

    # The amendment has to reach the market as a replace request, naming the
    # order it amends.
    forwarded = awaiting("market", "G", lambda raw: tag(raw, "55") == "VOD")
    if forwarded is None:
        print("        FAIL  the amendment never reached the market side")
        _failures.append("B: amendment never reached the market")
        return
    amend_id, orig = tag(forwarded, "11"), tag(forwarded, "41")
    print(f"        ok    router forwarded the amendment as {amend_id} (orig {orig})")
    check("amend: OrderQty", tag(forwarded, "38"), "800")

    call("harness_send_execution", clOrdId=amend_id, orderId=f"MKT-{venue_id}",
         origClOrdId=orig, symbol="VOD", side="buy", orderQty=800,
         execType="replaced", ordStatus="replaced", leavesQty=800, price=41)
    replaced = awaiting("client", "8", lambda raw: tag(raw, "39") == "5")
    if replaced:
        check("replaced: ClOrdID", tag(replaced, "11"), "B-2")
    else:
        _failures.append("B: no replace confirmation reached the client")
        print("        FAIL  no replace confirmation reached the client")

    call("harness_send_execution", clOrdId=amend_id, orderId=f"MKT-{venue_id}",
         symbol="VOD", side="buy", orderQty=800, execType="trade",
         ordStatus="filled", lastQty=800, cumQty=800, leavesQty=0, price=41)
    filled = awaiting("client", "8", lambda raw: tag(raw, "39") == "2")
    if filled:
        check("filled at amended qty: CumQty", tag(filled, "14"), "800")
    else:
        _failures.append("B: no fill reached the client")
        print("        FAIL  no fill reached the client")


def scenario_c() -> None:
    print("\nC. new -> ack -> partial fill -> cancel keeps what traded")
    venue_id = send_order("C-1", "SHEL", 600, 25)
    if venue_id is None:
        return
    ack(venue_id, "SHEL", 600, 25)
    call("harness_send_execution", clOrdId=venue_id, orderId=f"MKT-{venue_id}",
         symbol="SHEL", side="buy", orderQty=600, execType="trade",
         ordStatus="partially_filled", lastQty=200, cumQty=200, leavesQty=400, price=25)
    awaiting("client", "8", lambda raw: tag(raw, "39") == "1")

    call("harness_send_cancel", clOrdId="C-2", origClOrdId="C-1", symbol="SHEL",
         side="buy", quantity=600, onBehalfOf=CLIENT_COMP_ID)
    forwarded = awaiting("market", "F", lambda raw: tag(raw, "55") == "SHEL")
    if forwarded is None:
        print("        FAIL  the cancel never reached the market side")
        _failures.append("C: cancel never reached the market")
        return
    cancel_id, orig = tag(forwarded, "11"), tag(forwarded, "41")
    print(f"        ok    router forwarded the cancel as {cancel_id}")

    call("harness_send_execution", clOrdId=cancel_id, orderId=f"MKT-{venue_id}",
         origClOrdId=orig, symbol="SHEL", side="buy", orderQty=600,
         execType="canceled", ordStatus="canceled", cumQty=200, leavesQty=0, price=25)
    cancelled = awaiting("client", "8", lambda raw: tag(raw, "39") == "4")
    if cancelled:
        # Cancelling stops the rest; it does not undo what was done.
        check("cancelled: CumQty survives", tag(cancelled, "14"), "200")
        check("cancelled: LeavesQty", tag(cancelled, "151"), "0")
    else:
        _failures.append("C: no cancel confirmation reached the client")
        print("        FAIL  no cancel confirmation reached the client")


def scenario_d() -> None:
    print("\nD. new -> ack -> cancel refused, order still working")
    venue_id = send_order("D-1", "AZN", 200, 90)
    if venue_id is None:
        return
    ack(venue_id, "AZN", 200, 90)
    awaiting("client", "8", lambda raw: tag(raw, "39") == "0")

    call("harness_send_cancel", clOrdId="D-2", origClOrdId="D-1", symbol="AZN",
         side="buy", quantity=200, onBehalfOf=CLIENT_COMP_ID)
    forwarded = awaiting("market", "F", lambda raw: tag(raw, "55") == "AZN")
    if forwarded is None:
        print("        FAIL  the cancel never reached the market side")
        _failures.append("D: cancel never reached the market")
        return

    call("harness_send_cancel_reject", clOrdId=tag(forwarded, "11"),
         origClOrdId=tag(forwarded, "41"), orderId=f"MKT-{venue_id}",
         responseTo="cancel", ordStatus="new", reason="too late to cancel")
    reject = awaiting("client", "9", lambda raw: True)
    if reject:
        # 434 is the only field that says which request was refused: OrdStatus
        # here reports the order's own state, which a refusal does not change.
        check("reject: CxlRejResponseTo", tag(reject, "434"), "1")
        check("reject: OrigClOrdID", tag(reject, "41"), "D-1")
    else:
        _failures.append("D: no cancel reject reached the client")
        print("        FAIL  no cancel reject reached the client")


def scenario_e() -> None:
    print("\nE. new -> rejected outright")
    venue_id = send_order("E-1", "TSCO", 300, 10)
    if venue_id is None:
        return
    call("harness_send_execution", clOrdId=venue_id, orderId=f"MKT-{venue_id}",
         symbol="TSCO", side="buy", orderQty=300, execType="rejected",
         ordStatus="rejected", leavesQty=0, price=10, text="unknown symbol")
    rejected = awaiting("client", "8", lambda raw: tag(raw, "39") == "8")
    if rejected:
        check("rejected: ClOrdID", tag(rejected, "11"), "E-1")
        check("rejected: LeavesQty", tag(rejected, "151"), "0")
    else:
        _failures.append("E: no rejection reached the client")
        print("        FAIL  no rejection reached the client")


def main() -> None:
    print("=" * 68)
    print("Order lifecycle through a router, driven from both sides")
    print("=" * 68)

    call("harness_disconnect", side="client")
    call("harness_disconnect", side="market")

    # The market listens first: the system under test dials out to it.
    call("harness_connect", side="market", port=MARKET_PORT,
         senderCompId=MARKET_COMP_ID, targetCompId=SYSTEM_COMP_ID)
    call("harness_connect", side="client", port=CLIENT_PORT,
         senderCompId=CLIENT_COMP_ID, targetCompId=SYSTEM_COMP_ID)

    for _ in range(40):
        if call("harness_status")["text"].count("logged on") == 2:
            break
        time.sleep(0.5)
    else:
        print("FAIL — the harness never logged on both sides")
        print(call("harness_status")["text"])
        sys.exit(1)
    print("both sides logged on")

    scenario_a()
    scenario_b()
    scenario_c()
    scenario_d()
    scenario_e()

    print("\n" + "=" * 68)
    if _failures:
        print(f"{len(_failures)} check(s) failed:")
        for failure in _failures:
            print(f"  - {failure}")
        sys.exit(1)
    print("every check passed")


if __name__ == "__main__":
    main()
