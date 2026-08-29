/** A live FIX session bar, shown only where FIX work is happening. */
import { useEffect, useState } from 'react'
import type { PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots'
import type {} from '@deepseek-ai/dsh-client-ui-conversation/client'

/** One session, as the stream reports it. */
interface SessionRow {
  sessionId: string
  loggedOn: boolean
  nextSenderSeqNum: number
  nextTargetSeqNum: number
  beginString: string
}

/**
 * The engines this bar watches.
 *
 * <p>More than one, because more than one is the ordinary case: testing an
 * order router means running the engine the agent works through *and* the
 * system under test, and a bar that watches only the first reports sequence
 * numbers from an engine the conversation is not about. Each is watched
 * independently, so one being down leaves the others reporting.
 *
 * <p>Still fixed rather than configured. The browser half is loaded by the
 * module loader rather than composed by the cordis Loader, so it has no
 * `Config` to read from; a deployment whose engines are not on the viewer's own
 * machine needs that plumbing first.
 */
const NEXUM_ORIGINS = [
  { origin: 'http://127.0.0.1:18080', label: null },
  { origin: 'http://127.0.0.1:18081', label: 'sut' },
] as const

/**
 * Tool names this plugin recognises as FIX work.
 *
 * <p>The harness namespaces an MCP server's tools by the name the config gave
 * it, so every tool from the `nexum` server arrives under this prefix.
 */
const NEXUM_TOOL_PREFIX = 'mcp__nexum__'

/**
 * Live FIX session state.
 *
 * <p>Rendered only once this session has actually reached for a FIX tool.
 * A bar that is always present is one more thing to ignore in every
 * conversation that has nothing to do with trading; this appears when it starts
 * being the thing you want to know and stays for the rest of the session.
 *
 * <p>State arrives pushed rather than polled. A session dropping is exactly the
 * moment a poll interval is too long, and it is the moment this exists for.
 */
export function SessionBar({ useSession }: SessionBarProps) {
  // Whether this conversation has touched FIX at all. Read from the session's
  // own record rather than kept here, so it survives a remount and is right
  // immediately when an old session is reopened.
  const usesFix = useSession((state) => {
    if (state.runningCalls.some(call => call.name.startsWith(NEXUM_TOOL_PREFIX))) {
      return true
    }
    // A finished call appears as a tool-result carrying the call it answered.
    // There is no 'tool-call' node kind: the call itself lives in an assistant
    // message's blocks, and the result is what the conversation records.
    return state.nodes.some(node =>
      node.kind === 'tool-result'
      && node.call?.name.startsWith(NEXUM_TOOL_PREFIX) === true)
  })

  // Keyed by engine, because two engines can serve a session of the same name
  // — an order router and the system it is tested against both call theirs
  // OMS->LSE — and one map would have the second silently replace the first.
  const [byEngine, setByEngine] = useState<Readonly<Record<string, readonly SessionRow[]>>>({})
  const [reachable, setReachable] = useState(true)

  useEffect(() => {
    if (!usesFix) return undefined

    const streams = NEXUM_ORIGINS.map(({ origin, label }) => {
      const stream = new EventSource(`${origin}/api/sessions/stream`)
      const key = label ?? origin

      const replace = (rows: readonly SessionRow[]) => {
        setByEngine(current => ({ ...current, [key]: rows }))
      }

      // The current state arrives first, before any change, so the bar is
      // populated the moment it appears rather than staying blank until
      // something happens to a session.
      stream.addEventListener('snapshot', (event) => {
        const frame: unknown = JSON.parse((event as MessageEvent<string>).data)
        const rows = isRecord(frame) ? frame['sessions'] : undefined
        replace(Array.isArray(rows) ? rows.filter(isSessionRow) : [])
        setReachable(true)
      })

      stream.addEventListener('session', (event) => {
        const row: unknown = JSON.parse((event as MessageEvent<string>).data)
        if (!isSessionRow(row)) return
        // The event carries the state it left the session in, so the row is
        // replaced from it without asking for anything.
        setByEngine((current) => {
          const held = current[key] ?? []
          const without = held.filter(s => s.sessionId !== row.sessionId)
          return {
            ...current,
            [key]: [...without, row].sort((a, b) => a.sessionId.localeCompare(b.sessionId)),
          }
        })
      })

      // An engine that is not running is ordinary here — one is started and
      // stopped between scenarios — so a failing stream drops its own rows
      // rather than declaring everything unreachable.
      //
      // It is also closed rather than left to retry. EventSource reconnects on
      // its own for ever, and an engine that is simply not deployed would have
      // the browser retrying a refused connection every few seconds for the
      // life of the page, filling the console with failures that say nothing
      // new. A reconnect belongs to an engine that was there and went away,
      // which a page reload covers.
      let opened = false
      stream.addEventListener('open', () => { opened = true })
      stream.onerror = () => {
        replace([])
        if (!opened) {
          stream.close()
        }
      }
      return stream
    })

    return () => { streams.forEach(stream => { stream.close() }) }
  }, [usesFix])

  // One list, each row remembering which engine reported it.
  const sessions = NEXUM_ORIGINS.flatMap(({ origin, label }) =>
    (byEngine[label ?? origin] ?? []).map(row => ({ row, label })))

  if (!usesFix) return null

  if (!reachable) {
    return (
      <div className="nexum-bar nexum-bar--down">
        <span className="nexum-dot" />
        NEXUM unreachable
      </div>
    )
  }

  if (sessions.length === 0) {
    return (
      <div className="nexum-bar">
        <span className="nexum-dot" />
        no FIX sessions
      </div>
    )
  }

  return (
    <div className="nexum-bar">
      {sessions.map(({ row, label }) => (
        <span key={`${label ?? ''}${row.sessionId}`} className="nexum-session">
          <span className={`nexum-dot${row.loggedOn ? ' nexum-dot--up' : ''}`} />
          {/* Which engine reported it, when more than one does. Two engines
              name their sessions alike, so the id alone says nothing about
              which one a row belongs to. */}
          {label === null ? null : <span className="nexum-engine">{label}</span>}
          {row.sessionId}
          {/* Sequence numbers, which is what anyone diagnosing a session asks
              for first — sender over target, the way both sides quote them. */}
          <span className="nexum-seq">
            {row.nextSenderSeqNum}/{row.nextTargetSeqNum}
          </span>
        </span>
      ))}
    </div>
  )
}

/**
 * Props for the input-dock seat: the owner share plus the session-scoped
 * standard kit, of which this reads only `useSession`.
 */
export type SessionBarProps = PropsRuntime<'conversation.input.dock'>

/** Whether a parsed JSON value is an object that can carry fields. */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/**
 * Whether one parsed frame carries a complete session row.
 *
 * <p>The rows arrive over a socket from a separate process, so a frame missing
 * a field or carrying the wrong type is a real possibility rather than one the
 * type declaration rules out. Dropping such a row leaves the bar showing the
 * sessions it does understand.
 */
function isSessionRow(value: unknown): value is SessionRow {
  return isRecord(value)
    && typeof value['sessionId'] === 'string'
    && typeof value['loggedOn'] === 'boolean'
    && typeof value['nextSenderSeqNum'] === 'number'
    && typeof value['nextTargetSeqNum'] === 'number'
    && typeof value['beginString'] === 'string'
}
