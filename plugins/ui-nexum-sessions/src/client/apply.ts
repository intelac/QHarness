/** Register the FIX session bar into the conversation's input dock. */
import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import type {} from '@deepseek-ai/dsh-client-ui-conversation/client'
import { SessionBar } from './SessionBar.tsx'
import './session-bar.css'

/** Required services: the slot registry. */
export const inject = ['slots']

/**
 * Mount the session bar.
 *
 * <p>`conversation.input.dock` is a session-scoped list seat, which is what
 * makes this per-conversation rather than global: the component is mounted for
 * each conversation and decides for itself whether that conversation has
 * anything to do with FIX.
 *
 * @param ctx - Client root context.
 */
export function apply(ctx: ClientContext): void {
  ctx.slots.inject('conversation.input.dock', () => ctx.slots.register({
    name: 'conversation.input.dock',
    id: 'nexum-sessions',
    // After the shipped controls: this is context, not an action.
    order: 200,
  }, SessionBar))
}
