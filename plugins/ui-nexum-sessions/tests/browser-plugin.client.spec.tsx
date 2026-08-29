// @vitest-environment jsdom
import { Context } from '@deepseek-ai/cordis'
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import {
  EMPTY_CHAT_SNAPSHOT, EMPTY_CONVERSATION_VIEWS, SlotRegistry,
} from '@deepseek-ai/dsh-client-runtime/client'
import type { ConversationSnapshot, SessionId } from '@deepseek-ai/dsh-client-runtime/client'
import type { SnapshotSelectorHook } from '@deepseek-ai/dsh-client-ui-slots'
import { apply, inject } from '../src/client/index.ts'
import { SessionBar, type SessionBarProps } from '../src/client/SessionBar.tsx'
import { apply as nodeApply } from '../src/index.ts'

afterEach(cleanup)

const HOLE = 'conversation.input.dock'

/**
 * How many engines the bar watches.
 *
 * <p>More than one is the ordinary case: testing an order router means running
 * the engine the agent works through and the system under test, and each is
 * watched on its own stream.
 */
const ENGINES = 2

async function bench(declare = true) {
  const ctx = new Context()
  await ctx.plugin(SlotRegistry).await()
  const slots = ctx.get('slots') as SlotRegistry
  const declareHole = () => slots.register({
    name: 'root',
    children: { [HOLE]: { kind: 'list', scope: 'session' } },
  } as never, () => null)
  const disposeHole = declare ? declareHole() : undefined
  return { ctx, slots, declareHole, disposeHole }
}

const SID = 's1' as SessionId

/** A conversation carrying the two members the reveal rule reads. */
function conversation(
  parts: Pick<ConversationSnapshot, 'runningCalls' | 'nodes'>,
): ConversationSnapshot {
  return {
    sessionId: SID, views: EMPTY_CONVERSATION_VIEWS, chat: EMPTY_CHAT_SNAPSHOT,
    turnTimings: new Map(), turnEnds: new Map(), partial: null,
    pending: [], queue: [], running: false, composerPhase: 'active', removed: false,
    openState: 'open', openError: null, hasMore: false, loadingOlder: false,
    promptError: null, blank: false, subagent: null, lastAgentError: null,
    ...parts,
  }
}

/** A `useSession` over one fixed conversation. */
function sessionOf(snapshot: ConversationSnapshot): SnapshotSelectorHook<ConversationSnapshot> {
  return selector => selector(snapshot)
}

/**
 * The session-scoped standard kit. The bar reads `useSession` only; the rest
 * satisfies the seat and throws if anything ever reaches for it.
 */
function kitFor(snapshot: ConversationSnapshot): SessionBarProps {
  return {
    sessionId: SID,
    useSession: sessionOf(snapshot),
    useSessions: (() => { throw new Error('unused') }) as never,
    useWorkspaces: (() => { throw new Error('unused') }) as never,
    useProjection: (() => undefined) as never,
    session: snapshot,
    input: { draft: '', imageIds: [], draftRev: 0, phase: 'plain', occurrences: [], queue: [] },
  } as unknown as SessionBarProps
}

/** The captured handlers of one fake EventSource, keyed by event name. */
interface FakeStream {
  listeners: Map<string, (event: MessageEvent<string>) => void>
  closed: boolean
  /** Report the stream as connected, which a real EventSource does on open. */
  open: () => void
  url: string
  emit: (name: string, data: unknown) => void
  fail: () => void
}

/**
 * Replace EventSource for one test. jsdom has none, and a real one would need
 * a server; what the component's correctness rests on is which frames it
 * reacts to, which this makes directly assertable.
 */
function stubEventSource(): { streams: FakeStream[]; restore: () => void } {
  const streams: FakeStream[] = []
  class Fake {
    listeners = new Map<string, (event: MessageEvent<string>) => void>()
    closed = false
    onerror: (() => void) | null = null
    url: string
    constructor(url: string) {
      this.url = url
      const stream: FakeStream = {
        listeners: this.listeners,
        closed: false,
        url,
        emit: (name, data) => {
          this.listeners.get(name)?.({ data: JSON.stringify(data) } as MessageEvent<string>)
        },
        open: () => {
          this.listeners.get('open')?.({} as MessageEvent<string>)
        },
        fail: () => { this.onerror?.() },
      }
      Object.defineProperty(stream, 'closed', { get: () => this.closed })
      streams.push(stream)
    }

    addEventListener(name: string, handler: (event: MessageEvent<string>) => void) {
      this.listeners.set(name, handler)
    }

    close() { this.closed = true }
  }
  const original = (globalThis as Record<string, unknown>).EventSource
  ;(globalThis as Record<string, unknown>).EventSource = Fake
  return {
    streams,
    restore: () => { (globalThis as Record<string, unknown>).EventSource = original },
  }
}

const FUNDX = {
  sessionId: 'OMS->FUNDX',
  loggedOn: true,
  nextSenderSeqNum: 5,
  nextTargetSeqNum: 5,
  beginString: 'FIX.4.4',
}

const LSE = {
  sessionId: 'OMS->LSE',
  loggedOn: true,
  nextSenderSeqNum: 9,
  nextTargetSeqNum: 9,
  beginString: 'FIX.4.4',
}

describe('nexum session bar plugin', () => {
  it('declares only the slot service it uses', () => {
    expect(inject).toEqual(['slots'])
  })

  it('provides no host-side behaviour', () => {
    // The node half exists so the plugin can appear in the host cordis.yml;
    // loading it must contribute nothing and must not throw.
    expect(() => { nodeApply() }).not.toThrow()
  })

  it('fills the dock for declarations before or after apply, and leaves with its fiber', async () => {
    const before = await bench()
    const fiber = before.ctx.plugin({ inject: [...inject], apply })
    await fiber.await()
    expect(before.slots.entries(HOLE)).toHaveLength(1)

    before.disposeHole?.()
    expect(before.slots.entries(HOLE)).toHaveLength(0)
    before.declareHole()
    await Promise.resolve()
    expect(before.slots.entries(HOLE)).toHaveLength(1)

    // Registry-contribution disposal proof: the fiber going down empties the dock.
    await fiber.dispose()
    expect(before.slots.entries(HOLE)).toHaveLength(0)

    const after = await bench(false)
    const late = after.ctx.plugin({ inject: [...inject], apply })
    await late.await()
    after.declareHole()
    await Promise.resolve()
    expect(after.slots.entries(HOLE)).toHaveLength(1)
    await late.dispose()
    expect(after.slots.entries(HOLE)).toHaveLength(0)
  })
})

describe('when the bar reveals itself', () => {
  it('renders nothing in a conversation that has never touched FIX', () => {
    const stub = stubEventSource()
    const { container } = render(
      <SessionBar {...kitFor(conversation({
        runningCalls: [{ name: 'bash' }] as never,
        nodes: [{ kind: 'tool-result', call: { name: 'read_file' } }] as never,
      }))} />,
    )
    expect(container.firstChild).toBeNull()
    // The stream is the expensive half: a conversation with no FIX work must
    // not open one at all, not merely hide what it receives.
    expect(stub.streams).toHaveLength(0)
    stub.restore()
  })

  it('renders once a FIX tool call is still running', async () => {
    const stub = stubEventSource()
    render(
      <SessionBar {...kitFor(conversation({
        runningCalls: [{ name: 'mcp__nexum__list_sessions' }] as never,
        nodes: [],
      }))} />,
    )
    await waitFor(() => { expect(stub.streams).toHaveLength(ENGINES) })
    stub.restore()
  })

  it('renders once a FIX tool call has finished', async () => {
    const stub = stubEventSource()
    render(
      <SessionBar {...kitFor(conversation({
        runningCalls: [],
        // A finished call reaches the record as the result that answered it.
        nodes: [{ kind: 'tool-result', call: { name: 'mcp__nexum__place_order' } }] as never,
      }))} />,
    )
    await waitFor(() => { expect(stub.streams).toHaveLength(ENGINES) })
    stub.restore()
  })

  it('is not fooled by a tool-result carrying no call', async () => {
    const stub = stubEventSource()
    const { container } = render(
      <SessionBar {...kitFor(conversation({
        runningCalls: [],
        nodes: [{ kind: 'tool-result', call: null }] as never,
      }))} />,
    )
    expect(container.firstChild).toBeNull()
    expect(stub.streams).toHaveLength(0)
    stub.restore()
  })
})

describe('what the bar shows', () => {
  const fixWork = conversation({
    runningCalls: [{ name: 'mcp__nexum__list_sessions' }] as never,
    nodes: [],
  })

  it('is populated by the snapshot that arrives before any change', async () => {
    const stub = stubEventSource()
    render(<SessionBar {...kitFor(fixWork)} />)
    await waitFor(() => { expect(stub.streams).toHaveLength(ENGINES) })

    stub.streams[0]?.emit('snapshot', { sessions: [LSE], at: 1 })

    await screen.findByText('OMS->LSE')
    await screen.findByText('9/9')
    stub.restore()
  })

  it('replaces a row from the event that reports it, without asking again', async () => {
    const stub = stubEventSource()
    render(<SessionBar {...kitFor(fixWork)} />)
    await waitFor(() => { expect(stub.streams).toHaveLength(ENGINES) })
    stub.streams[0]?.emit('snapshot', { sessions: [LSE], at: 1 })
    await screen.findByText('9/9')

    stub.streams[0]?.emit('session', {
      ...LSE, kind: 'LOGOUT', loggedOn: false, nextSenderSeqNum: 10,
    })

    await screen.findByText('10/9')
    expect(screen.queryByText('9/9')).toBeNull()
    stub.restore()
  })

  it('an engine that stops reporting takes only its own rows away', async () => {
    const stub = stubEventSource()
    render(<SessionBar {...kitFor(fixWork)} />)
    await waitFor(() => { expect(stub.streams).toHaveLength(ENGINES) })

    stub.streams[0]?.emit('snapshot', { sessions: [LSE], at: 1 })
    stub.streams[1]?.emit('snapshot', { sessions: [FUNDX], at: 1 })
    await screen.findByText('OMS->LSE')
    await screen.findByText('OMS->FUNDX')

    // An engine being down is ordinary here — the system under test is started
    // and stopped between scenarios — so its rows go and the other engine's
    // stay, rather than the bar declaring everything unreachable.
    stub.streams[0]?.fail()

    await waitFor(() => { expect(screen.queryByText('OMS->LSE')).toBeNull() })
    expect(screen.queryByText('OMS->FUNDX')).not.toBeNull()
    stub.restore()
  })

  it('drops a malformed row rather than rendering a partial one', async () => {
    const stub = stubEventSource()
    render(<SessionBar {...kitFor(fixWork)} />)
    await waitFor(() => { expect(stub.streams).toHaveLength(ENGINES) })

    // The rows cross a socket from a separate process, so a frame missing a
    // field is a real possibility rather than one the declaration rules out.
    stub.streams[0]?.emit('snapshot', {
      sessions: [LSE, { sessionId: 'OMS->BROKEN' }],
      at: 1,
    })

    await screen.findByText('OMS->LSE')
    expect(screen.queryByText('OMS->BROKEN')).toBeNull()
    stub.restore()
  })

  it('ignores a session event that is not a complete row', async () => {
    const stub = stubEventSource()
    render(<SessionBar {...kitFor(fixWork)} />)
    await waitFor(() => { expect(stub.streams).toHaveLength(ENGINES) })
    stub.streams[0]?.emit('snapshot', { sessions: [LSE], at: 1 })
    await screen.findByText('9/9')

    stub.streams[0]?.emit('session', { sessionId: 'OMS->LSE', kind: 'LOGOUT' })

    // The known row survives: a frame that cannot be read replaces nothing.
    await screen.findByText('9/9')
    // The whole bar is asserted, not just the surviving cell: an unreadable
    // frame that overwrote the row would render undefined sequence numbers.
    expect(document.body.textContent).toBe('OMS->LSE9/9')
    stub.restore()
  })

  it('stops retrying an engine that was never there', async () => {
    const stub = stubEventSource()
    render(<SessionBar {...kitFor(fixWork)} />)
    await waitFor(() => { expect(stub.streams).toHaveLength(ENGINES) })

    // One engine answers; the other is simply not deployed.
    stub.streams[0]?.open()
    stub.streams[0]?.emit('snapshot', { sessions: [LSE], at: 1 })
    stub.streams[1]?.fail()

    await screen.findByText('OMS->LSE')
    // EventSource reconnects for ever by default, so an engine that is not
    // there would have the browser retrying a refused connection for the life
    // of the page. The one that answered stays open.
    expect(stub.streams[1]?.closed).toBe(true)
    expect(stub.streams[0]?.closed).toBe(false)
    stub.restore()
  })

  it('closes the stream when the conversation goes away', async () => {
    const stub = stubEventSource()
    const view = render(<SessionBar {...kitFor(fixWork)} />)
    await waitFor(() => { expect(stub.streams).toHaveLength(ENGINES) })

    view.unmount()

    expect(stub.streams.every(stream => stream.closed)).toBe(true)
    stub.restore()
  })
})
