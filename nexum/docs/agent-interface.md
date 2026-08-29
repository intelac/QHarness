# The agent interface

NEXUM speaks MCP, so an agent can run the FIX sessions and the orders on them.

The system stays where it is: it holds the sessions, the order state and the
journal, and an agent reaches that one running system rather than starting an
engine of its own. Tools are the entry point, not a second implementation.

```
agent  ──MCP over HTTP──>  NEXUM  ──FIX 4.4──>  venue
```

## Turning it on

```yaml
mcp:
  port: 18090
  bind: 127.0.0.1        # the tools place orders; who can reach them is not a default
  session: OMS->FUNDX    # the session an agent's orders are treated as arriving on
  destination: OMS->LSE  # what its acting calls are attributed to
  identity:              # what marks its messages as a client's
    115: FUNDX
  maxCalls: 500          # acting calls permitted; 0 hides the acting tools
  validMinutes: 480
```

`identity` matters and is easy to miss. An agent is a client like any other, and
a client is recognised by what its messages carry — commonly
OnBehalfOfCompID(115), but that is a deployment's choice. Without it an order
matches no client and is dropped at the first layer, which reads as the venue
having gone quiet.

`maxCalls: 0` leaves the acting tools registered but invisible. That is the safe
default and a poor one to leave implicit: a model that cannot see `place_order`
does not know it could ask for it.

## The tools

**Sessions** — without these an agent handed order tools is stuck the moment a
session is down, because the reason is invisible.

| tool | |
|---|---|
| `list_sessions` | every session and whether it is logged on |
| `session_status` | one session, with both sequence numbers |
| `logon_session` | bring one up |
| `logout_session` | clean logout, reason carried in Text(58) |
| `disconnect_session` | drop one that has stopped responding |
| `reset_session_sequence` | end of day; **both sides must reset together** |
| `set_session_sequence` | targeted alternative to a full reset |

**Orders**

| tool | |
|---|---|
| `place_order` | send one and wait for the venue's answer |
| `amend_order` | change quantity or price |
| `cancel_order` | withdraw |
| `get_order` | one order in detail |
| `list_orders` | what is being held |
| `order_events` | the state transitions, in order |

**Reading**

| tool | |
|---|---|
| `parse_fix` | a raw message, tag by tag, with names and meanings |

## Waiting, rather than polling

`place_order`, `amend_order` and `cancel_order` return when the venue has
answered — acknowledged, rejected, filled — not when the message is away. FIX
answers on another thread some milliseconds later, and a caller handed an order
still in PENDING_NEW knows nothing it can act on; a model in that position
spends a turn asking again.

The wait is registered **before** the message goes out. A venue answering in
under a millisecond would otherwise deliver its answer before anyone was
listening, and the tool would wait out its whole timeout for something that had
already happened.

A timeout is not an error. The order went out and the venue has not answered —
a real situation, reported as one, with the order's identifier so the agent can
look again.

`reset_session_sequence` is the exception: it logs the session out and back on,
so it reports no sequence numbers at all. Reading them as it returns gives the
values from a moment earlier, which reads as the reset having failed.

## Nothing takes a back door

Every acting tool puts its message through the door a real client uses — the
inbound event the pipeline listens on. An order placed by a model crosses the
same four layers, the same risk gates and the same routing as one that arrived
over a socket. A back channel would be quicker to write and would mean the
rules a desk relies on are not the rules an agent is held to.

## Connecting DeepSeek Harness

```yaml
# ~/.dsh/nexum.cordis.yml
- insert:
    - id: nexum-fix
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: nexum
        transport: streamable-http
        url: http://127.0.0.1:18090/mcp
        toolCallTimeoutMs: 30000
        failOnStartupError: true
```

The model then sees `mcp__nexum__place_order`, `mcp__nexum__list_sessions`, and
the rest.

## Running it locally

Three processes, in this order:

1. **LM Studio** — load the model, so its `/v1/models` is not an empty catalog.
2. **NEXUM** — `java -jar target/nexum.jar <config>.yaml`, which brings up the
   FIX sessions and the MCP endpoint.
3. **DeepSeek Harness** — `dsh web --no-open --port 3080` from
   `~/projects/QHarness/dsh`.

The harness configuration lives in `~/.dsh/profiles/web/cordis.patch.yml`.
Three things about that file cost an hour to learn:

- A patch names an **existing id** to override its config. Naming that id
  inside an `insert` is a duplicate and refuses to boot.
- A route needs `apiKeyEnv` even when the endpoint verifies nothing. Omitting
  it fails every request with `No API key for provider`; the value itself is
  never checked, so any placeholder does.
- The default directory picker delegates to the operating system's own dialog,
  which a browser cannot reach. `dsh-host-directory-picker-browse` renders the
  chooser in the page instead.
- A workspace can be created without the UI:
  `POST /api/workspace.create` with
  `{"type":"client-request","rpcId":"...","method":"workspace.create","payload":{"path":"..."}}`.
- An override changes `config` but **not `name`** — the entry keeps its plugin.
  Swapping a plugin means disabling the entry and inserting a different id.
- The bundle already mounts `dsh-llm-pi-ai` as `llm-pi-ai`. Mounting a second
  instance makes both declare pi-ai's built-in providers, and the duplicate is
  refused with `DUPLICATE_DIRECTORY`. Add routes to the existing instance.

`llm` is the service, not a provider — disabling it leaves every consumer,
pi-ai included, waiting for a service nothing provides.

## Checking the seam without the UI

The harness depends on the official MCP SDK, so the same client it uses can be
driven directly. This proves the protocol end without involving a model:

```bash
SDK=$(ls -d ~/projects/QHarness/dsh/node_modules/.pnpm/@modelcontextprotocol+sdk@*/node_modules | head -1)
cd "$SDK/.." && node --input-type=module -e "
import {StreamableHTTPClientTransport} from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import {Client} from '@modelcontextprotocol/sdk/client/index.js';
const t = new StreamableHTTPClientTransport(new URL('http://127.0.0.1:18090/mcp'));
const c = new Client({name:'probe',version:'1'},{capabilities:{}});
await c.connect(t);
console.log((await c.listTools()).tools.map(x=>x.name).join(', '));
await c.close();
"
```

## Subscribing to session state

Polling for session state is either stale or wasteful, and the thing it is
watching for — a session dropping — is exactly the moment the gap matters. The
engine already announces every logon, logout, disconnection and resequence;
`/api/sessions/stream` carries those announcements out over SSE.

```bash
# Every session
curl -N http://127.0.0.1:18080/api/sessions/stream

# One of them: a subscriber following a single counterparty is not woken
# by the others
curl -N 'http://127.0.0.1:18080/api/sessions/stream?sessions=OMS-%3ELSE'
```

Two frame kinds:

```
event: snapshot
data: {"sessions":[{"sessionId":"OMS->LSE","loggedOn":true,
                    "nextSenderSeqNum":2,"nextTargetSeqNum":2, ...}], "at":...}

event: session
data: {"sessionId":"OMS->LSE","kind":"LOGOUT","detail":"...",
       "loggedOn":false,"nextSenderSeqNum":4,"nextTargetSeqNum":4, ...}
```

**The snapshot arrives first, before any event.** Without it a page opening
during a quiet spell shows nothing at all until a session happens to change —
which, when everything is healthy, is never.

**Every event carries the state it left the session in.** A subscriber
reacting to a logon wants the sequence numbers that came with it, and asking
for them separately races the next event.

**`loggedOn` comes from the event, not from the engine's snapshot.** The engine
announces a logout while the connection is still open, so reading its state at
that moment reports a session that has just gone down as still up — the one
field a subscriber is watching. Sequence numbers are read as they are: those
have already moved by the time the event is announced.

The monitor's own status bar is a subscriber, which is why a session dropping
turns its dot red immediately rather than up to a poll later.
