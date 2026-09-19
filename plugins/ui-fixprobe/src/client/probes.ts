/**
 * Reading the probes running on this machine, from the browser.
 *
 * A probe answers `/api/status` on the same port it serves MCP on, and sets
 * `Access-Control-Allow-Origin`, so the page reads each one directly. There is
 * no host round trip and nothing to keep in step with the engine under test —
 * a probe may be pointed at any FIX engine, or at none yet.
 *
 * Discovery is a short sweep from the probe's default port. A probe registers
 * itself under `~/.nexum/probes` for anything that can read a directory, which
 * a browser cannot; the sweep is what remains, and it costs one request per
 * candidate port against loopback.
 */

/** The port a probe takes when started without one. */
export const DEFAULT_PROBE_PORT = 18099

/** How many consecutive ports the sweep tries, starting at the default. */
export const SWEEP_WIDTH = 8

/** One endpoint of one probe, as `/api/status` reports it. */
export interface EndpointRow {
  readonly endpoint: string
  readonly started: boolean
  readonly loggedOn: boolean
  readonly messages: number
  /** Absent until there is a session to read it from; zero would name a sequence reset. */
  readonly nextSenderSeqNum?: number
  readonly nextTargetSeqNum?: number
}

/** One probe as the panel lists it. */
export interface ProbeRow {
  readonly port: number
  /** Absent when the port answered but its body could not be read as a listing. */
  readonly sides?: readonly EndpointRow[]
}

/** What one sweep found, in port order. */
export type ProbeListing = readonly ProbeRow[]

/** Reads one probe's status, or nothing when that port is not a probe. */
export type ReadProbe = (port: number, signal: AbortSignal) => Promise<ProbeRow | null>

/**
 * Ask one port for a probe's status.
 *
 * @param port - the candidate port.
 * @param signal - aborts the request with the tab.
 * @returns the probe's row, or null when nothing there answered as one.
 */
export async function readProbe(port: number, signal: AbortSignal): Promise<ProbeRow | null> {
  let response: Response
  try {
    response = await fetch(`http://127.0.0.1:${port}/api/status`, { signal })
  } catch {
    // Nothing is listening, or it refused the cross-origin read: either way
    // this port holds no probe this panel can show.
    return null
  }
  if (!response.ok) {
    return null
  }
  try {
    const body: unknown = await response.json()
    const sides = (body as { sides?: unknown }).sides
    return Array.isArray(sides) ? { port, sides: sides as readonly EndpointRow[] } : { port }
  } catch {
    // It answered and is therefore something, but not a listing: say the port
    // is there and leave the rows out rather than dropping it from the panel.
    return { port }
  }
}

/** Every port the sweep considers, in order. */
export function sweepPorts(): readonly number[] {
  return Array.from({ length: SWEEP_WIDTH }, (_, index) => DEFAULT_PROBE_PORT + index)
}

/**
 * Sweep for probes, narrowing to the ports that have answered.
 *
 * The first sweep tries the whole range; later ones re-ask only the ports that
 * answered, plus one unproven port per pass. A probe started later is still
 * found, while a panel left open does not retry six dead ports every two
 * seconds — each of which the browser logs as a failed request.
 *
 * @param read - how one port is asked, so a test can answer without a network.
 * @param known - the ports that answered last time; empty on the first sweep.
 * @param signal - aborts the sweep with the tab.
 * @returns the probes that answered, in port order.
 */
export async function sweepProbes(
  read: ReadProbe,
  known: readonly number[],
  signal: AbortSignal,
): Promise<ProbeListing> {
  const all = sweepPorts()
  const unproven = all.filter(port => !known.includes(port))
  const candidates = known.length === 0
    ? all
    // One unproven port per pass, rotating by the clock so the whole range is
    // covered within a few sweeps without probing all of it each time.
    : [...known, ...(unproven.length === 0
      ? []
      : [unproven[Math.floor(Date.now() / 1000) % unproven.length]!])]
  const found = await Promise.all(candidates.map(port => read(port, signal)))
  return found
    .filter((row): row is ProbeRow => row !== null)
    .sort((left, right) => left.port - right.port)
}
