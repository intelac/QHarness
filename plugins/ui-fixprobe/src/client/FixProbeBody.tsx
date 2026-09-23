/**
 * The probe panel: every probe the sweep found, and each endpoint it has up.
 *
 * State is the last sweep's result and nothing else, so it lives in the
 * component rather than a store: there is no user-made state to carry across a
 * remount, and a stale reading is worse than a missing one. The sweep repeats
 * on a timer because a probe's sequence numbers move with every heartbeat.
 */
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import type { PropsLocale, PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots'
import type {} from './locales.ts'
import { readProbe, sweepProbes, type EndpointRow, type ProbeListing } from './probes.ts'
import css from './FixProbeBody.module.css'

/** How often the panel re-reads the probes, in milliseconds. */
const POLL_MS = 2000

/** What the panel is showing right now. */
type Reading =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly probes: ProbeListing }

export type FixProbeBodyProps =
  & PropsRuntime<'sidebar.right.pane.tab'>
  & PropsLocale<'fixProbe'>

/**
 * The panel body.
 * @param props - the tab information hook and this namespace's translate.
 * @returns one block per probe, or the line for an empty sweep.
 */
export function FixProbeBody({ useTabInfo, t }: FixProbeBodyProps): ReactNode {
  const { tab } = useTabInfo()
  const { signal, visible } = tab
  const [reading, setReading] = useState<Reading>({ kind: 'loading' })

  // The ports that have answered, so later sweeps stop re-asking dead ones.
  const answering = useRef<readonly number[]>([])

  const sweep = useCallback(async (): Promise<void> => {
    if (signal.aborted) return
    const probes = await sweepProbes(readProbe, answering.current, signal)
    if (signal.aborted) return
    answering.current = probes.map(probe => probe.port)
    setReading({ kind: 'ready', probes })
  }, [signal])

  useEffect(() => {
    // A hidden tab holds its last reading rather than polling behind the one
    // being looked at: nothing here is worth a request the reader cannot see.
    if (!visible) return
    void sweep()
    const timer = setInterval(() => { void sweep() }, POLL_MS)
    return () => { clearInterval(timer) }
  }, [visible, sweep])

  if (reading.kind === 'loading') {
    return <div className={css.state} data-fixprobe-state="loading">{t('state.loading')}</div>
  }
  if (reading.probes.length === 0) {
    return <div className={css.state} data-fixprobe-state="none">{t('state.none')}</div>
  }
  return (
    <div className={css.panel} data-fixprobe-state="ready">
      {reading.probes.map(probe => (
        <section key={probe.port} className={css.probe}>
          <h3 className={css.port}>{t('probe.port', { port: String(probe.port) })}</h3>
          {probe.sides === undefined
            ? <p className={css.unreachable}>{t('probe.unreachable')}</p>
            : probe.sides.map(side => <Endpoint key={side.endpoint} side={side} t={t} />)}
        </section>
      ))}
    </div>
  )
}

/** One endpoint row: its state as a dot, its name, and what has crossed it. */
function Endpoint({ side, t }: { side: EndpointRow; t: PropsLocale<'fixProbe'>['t'] }): ReactNode {
  const state = side.loggedOn ? 'up' : side.started ? 'connecting' : 'down'
  const label = side.loggedOn
    ? t('endpoint.loggedOn')
    : side.started ? t('endpoint.connecting') : t('endpoint.notStarted')
  return (
    <div className={css.endpoint} data-endpoint={side.endpoint} data-state={state}>
      <span className={css.dot} data-state={state} />
      <span className={css.name}>{side.endpoint}</span>
      {side.version !== undefined && <span className={css.version}>{side.version}</span>}
      <span className={css.label}>{label}</span>
      {side.started && (
        <span className={css.detail}>{t('endpoint.messages', { count: String(side.messages) })}</span>
      )}
      {side.nextSenderSeqNum !== undefined && side.nextTargetSeqNum !== undefined && (
        <span className={css.seq}>
          {t('endpoint.seq', {
            sender: String(side.nextSenderSeqNum),
            target: String(side.nextTargetSeqNum),
          })}
        </span>
      )}
    </div>
  )
}
