/**
 * The FIX probe panel: a right-Sidebar tab type listing the probes running on
 * this machine.
 *
 * Two stages, as every tab type registers: the type itself into
 * `sidebarRightTabs`, and the body into the `sidebar.right.pane.tab` seat under
 * this package's id. The body reads each probe's HTTP endpoint directly, so
 * nothing here injects a Remote namespace — a probe is its own process and may
 * be pointed at any FIX engine, or at none.
 */
import type { Context as ClientContext } from '@deepseek-ai/cordis'
import type {} from '@deepseek-ai/dsh-client-locale/client'
import type {} from '@deepseek-ai/dsh-client-ui-renderer/client'
import type {} from '@deepseek-ai/dsh-client-ui-sidebar-right/client'
import { FIXPROBE_ID, fixProbeDefinition } from './definition.ts'
import { FixProbeBody } from './FixProbeBody.tsx'
import { en, zh } from './locales.ts'

export type { FixProbeKey } from './locales.ts'
export type { EndpointRow, ProbeListing, ProbeRow, ReadProbe } from './probes.ts'
export type { FixProbeBodyProps } from './FixProbeBody.tsx'
export { FIXPROBE_ID, FIXPROBE_KIND, fixProbeDefinition } from './definition.ts'

/** This package's copy namespace. */
const NS = 'fixProbe'

export const inject = ['slots', 'locale', 'sidebarRightTabs']

/**
 * Browser plugin body.
 * @param ctx - the client context this plugin registers into.
 */
export function apply(ctx: ClientContext): void {
  const t = ctx.locale.bind(NS)
  ctx.effect(() => ctx.locale.register(NS, { zh, en }), 'ui-fixprobe: dictionaries')
  ctx.effect(() => ctx.sidebarRightTabs.register(fixProbeDefinition(t)), 'ui-fixprobe: probe type')
  ctx.effect(() => ctx.slots.inject('sidebar.right.pane.tab', () => ctx.slots.register(
    { name: 'sidebar.right.pane.tab', key: FIXPROBE_ID, locale: NS },
    FixProbeBody,
  )), 'ui-fixprobe: probe tab body')
}
