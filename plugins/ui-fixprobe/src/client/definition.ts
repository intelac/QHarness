/**
 * Stage one of this package's registration: what the `fixprobe` tab type IS.
 *
 * The type is a page, not a viewer: it claims no address, because what it shows
 * is whatever probes are running on this machine rather than a thing the
 * session points at. The guide page offers it as an entry box.
 */
import type { SidebarRightTabDefinition } from '@deepseek-ai/dsh-client-ui-sidebar-right/client'
import type { TranslateNS } from '@deepseek-ai/dsh-client-locale/client'
import type {} from './locales.ts'

/** The tab kind this package owns. */
export const FIXPROBE_KIND = 'fixprobe'

/** This implementation's identity in the tab system, and the key its body registers under. */
export const FIXPROBE_ID = '@deepseek-ai/dsh-client-ui-fixprobe'

/**
 * The probe panel's registry definition.
 * @param t - namespace-bound translate, read fresh on every label call.
 * @returns the definition to register.
 */
export function fixProbeDefinition(t: TranslateNS<'fixProbe'>): SidebarRightTabDefinition {
  return {
    id: FIXPROBE_ID,
    kind: FIXPROBE_KIND,
    priority: 'builtin',
    title: () => t('type.label'),
    guide: [{
      order: 40,
      title: () => t('guide.title'),
      description: () => t('guide.description'),
    }],
  }
}
