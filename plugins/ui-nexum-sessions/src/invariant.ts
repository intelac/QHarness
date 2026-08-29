/**
 * Package-owned invariant companion for `@deepseek-ai/dsh-client-ui-nexum-sessions`.
 * @module @deepseek-ai/dsh-client-ui-nexum-sessions/invariant
 */

/* jscpd:ignore-start */
import type { Context } from '@deepseek-ai/cordis'
import type { InvariantInstaller } from '@deepseek-ai/dsh-invariants'

const PACKAGE_NAME = '@deepseek-ai/dsh-client-ui-nexum-sessions'

/** Cordis companion plugin name. */
export const name = 'client-ui-nexum-sessions-invariant'
/** Service required before the companion can reserve package ownership. */
export const inject = ['invariants']

/**
 * No runtime invariant: this package emits no cordis events and owns no mutable
 * cross-plugin relation. Session rows are read-only projections of NEXUM's SSE
 * stream, held in component state and owned by the engine that sends them; the
 * one slot contribution's register/dispose symmetry is audited by the ui-slots
 * package's invariant, and whether a conversation reveals the bar is derived
 * per-render from the session store rather than stored here.
 */
const install: InvariantInstaller = () => {}

/**
 * Register this package's invariant companion.
 * @param ctx - Cordis context carrying the invariant service.
 * @returns the installed registration's disposer after setup succeeds.
 */
export const apply = (ctx: Context): Promise<() => void> =>
  Promise.resolve(ctx.invariants.register(PACKAGE_NAME, install))
/* jscpd:ignore-end */
