/** This package's copy, with the Chinese side as the authority for the key set. */
import type {} from '@deepseek-ai/dsh-client-ui-slots'

declare module '@deepseek-ai/dsh-client-ui-slots' {
  interface LocaleNamespaceMap {
    /** Probe panel type name, guide entry, endpoint rows, and the lines for an empty or unreachable listing. */
    fixProbe: FixProbeKey
  }
}

export const zh = {
  'type.label': 'FIX Probe',
  'guide.title': 'FIX Probe',
  'guide.description': '本机在跑的探针,以及每一端的连接和序号',
  'state.none': '没有探针在跑',
  'state.loading': '正在读取…',
  'probe.port': '端口 {port}',
  'endpoint.loggedOn': '已登录',
  'endpoint.connecting': '连接中',
  'endpoint.notStarted': '未启动',
  'endpoint.messages': '{count} 条消息',
  'endpoint.seq': '出 {sender} · 入 {target}',
  'probe.unreachable': '读不到状态',
  'action.refresh': '重新读取',
} satisfies Record<string, string>

export type FixProbeKey = keyof typeof zh

export const en = {
  'type.label': 'FIX Probe',
  'guide.title': 'FIX Probe',
  'guide.description': 'The probes running on this machine, and where each endpoint stands',
  'state.none': 'No probe is running',
  'state.loading': 'Reading…',
  'probe.port': 'port {port}',
  'endpoint.loggedOn': 'logged on',
  'endpoint.connecting': 'connecting',
  'endpoint.notStarted': 'not started',
  'endpoint.messages': '{count} messages',
  'endpoint.seq': 'out {sender} · in {target}',
  'probe.unreachable': 'cannot be read',
  'action.refresh': 'Reload',
} satisfies Record<FixProbeKey, string>
