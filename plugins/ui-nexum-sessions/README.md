# @deepseek-ai/dsh-client-ui-nexum-sessions

English | [中文](README.zh.md)

Live FIX session state for conversations doing FIX work. It registers one entry into ui-conversation's `conversation.input.dock` list slot, rendering each FIX session's connectedness and sequence numbers beside the composer: a dot per session, its id, and `nextSenderSeqNum/nextTargetSeqNum` — the pair either side of a FIX link quotes first when a session is diagnosed.

The bar is self-revealing. Every conversation mounts the component, and the component decides from that conversation's own record whether to render anything: it looks for a call to a tool named `mcp__nexum__*`, either still running or recorded as a finished tool result. A conversation that has never reached for a FIX tool renders nothing, so the bar costs an unrelated conversation no screen space. Because the decision is derived per render from the session store rather than stored here, it survives a remount and is correct immediately when an old conversation is reopened.

State arrives pushed, over the NEXUM engine's `/api/sessions/stream` Server-Sent Events endpoint. A subscriber is sent a `snapshot` event before any change, so the bar is populated on connect rather than staying blank until a session happens to change; each subsequent `session` event carries the state the session was left in, so a row is replaced from the event without a follow-up request. Polling would be wrong here in both directions: a session dropping is exactly the moment a poll interval is too long, and it is the moment this exists for. When the stream errors the bar says NEXUM is unreachable instead of continuing to show the last known rows, which would present stale state as current; `EventSource` reconnects on its own and the reconnect brings a fresh snapshot.

NEXUM is a separate process holding the FIX sessions and every order's state. This package only reads it, and never mutates a session: connecting, resetting sequence numbers, and everything else the engine offers reach it through the model's `mcp__nexum__*` tools, not through this bar.

The node half is an empty `apply`: it exists so the plugin appears in the host cordis.yml and Loader, while the browser half ships through `exports["./client"]` and is discovered through the `dsh.client` manifest declaration.

## Model Experience

None, as the bar is browser chrome read by the person watching the conversation: nothing it renders reaches a model request, and it contributes no tool, prompt text, or session event. The model learns session state only by calling a NEXUM tool itself.

#### KV Cache effect

None; this package neither assembles nor sends a provider request.

## Known Limitations and Deferred Work

- **The engine origin is fixed at `http://127.0.0.1:18080`** — the browser half is loaded by the module loader rather than composed by the cordis Loader, so it has no `Config` to read a deployment-varying URL from. A deployment where NEXUM is not on the viewer's own machine needs that plumbing first.
- **Detection is by tool-name prefix** — a NEXUM MCP server mounted in cordis.yml under a `serverName` other than `nexum` produces tool names this component does not recognise, and the bar stays hidden.
- **No per-session filtering from the browser** — the endpoint accepts a `sessions=` filter, but the bar always subscribes to every session the engine holds.
