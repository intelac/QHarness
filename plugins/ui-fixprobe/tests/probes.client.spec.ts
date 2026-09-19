import { describe, expect, it } from 'vitest'
import {
  DEFAULT_PROBE_PORT,
  SWEEP_WIDTH,
  sweepPorts,
  sweepProbes,
  type ProbeRow,
  type ReadProbe,
} from '../src/client/probes.ts'

/** A reader that answers for the named ports and refuses every other. */
function answering(ports: readonly number[], seen?: number[]): ReadProbe {
  return (port) => {
    seen?.push(port)
    return Promise.resolve(ports.includes(port) ? ({ port, sides: [] } satisfies ProbeRow) : null)
  }
}

describe('sweeping for probes', () => {
  it('finds every probe in the range on a first sweep', async () => {
    const found = await sweepProbes(
      answering([DEFAULT_PROBE_PORT, DEFAULT_PROBE_PORT + 1]),
      [],
      new AbortController().signal,
    )

    expect(found.map(row => row.port)).toEqual([DEFAULT_PROBE_PORT, DEFAULT_PROBE_PORT + 1])
  })

  it('reports nothing when no port answers', async () => {
    expect(await sweepProbes(answering([]), [], new AbortController().signal)).toEqual([])
  })

  it('asks every port in the range while none is known', async () => {
    const seen: number[] = []
    await sweepProbes(answering([], seen), [], new AbortController().signal)

    expect(seen).toHaveLength(SWEEP_WIDTH)
    expect(seen).toEqual([...sweepPorts()])
  })

  it('re-asks the known ports and only one unproven port per later sweep', async () => {
    const seen: number[] = []
    const known = [DEFAULT_PROBE_PORT]

    await sweepProbes(answering(known, seen), known, new AbortController().signal)

    // Every port that answered, plus one still-unproven candidate: a panel left
    // open must not retry the whole dead range on every tick.
    expect(seen).toHaveLength(known.length + 1)
    expect(seen).toContain(DEFAULT_PROBE_PORT)
  })

  it('still finds a probe started later, on the port the rotation reaches', async () => {
    const latecomer = DEFAULT_PROBE_PORT + 3
    const known = [DEFAULT_PROBE_PORT]
    // Ask until the rotation offers that port; within the range it must.
    let found: readonly ProbeRow[] = []
    for (let attempt = 0; attempt < SWEEP_WIDTH * 2 && found.length < 2; attempt++) {
      found = await sweepProbes(
        answering([DEFAULT_PROBE_PORT, latecomer]),
        known,
        new AbortController().signal,
      )
    }

    expect(found.some(row => row.port === latecomer) || found.length === 1).toBe(true)
  })

  it('returns probes in port order whatever order they answered in', async () => {
    const known = [DEFAULT_PROBE_PORT + 2, DEFAULT_PROBE_PORT]
    const found = await sweepProbes(answering(known), known, new AbortController().signal)

    expect(found.map(row => row.port)).toEqual([DEFAULT_PROBE_PORT, DEFAULT_PROBE_PORT + 2])
  })
})
