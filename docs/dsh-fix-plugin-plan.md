# dsh-fix:DeepSeek Harness 的 FIX 协议插件技术方案

> 目标:AI 连上 FIX session,用自然语言驱动收发各类 FIX message。
> 环境:先对模拟器/UAT。引擎 Java + QuickFIX/J。
> 诉求:完整审计留痕、人工审批闸门、可视化订单面板、跨工具复用。

---

## 一、核心结论:双层架构

你的四项诉求里,前三项(审计/审批/UI)**必须拿到 `ctx`,只有 dsh 原生 Cordis 插件能做**;
第四项(跨工具复用)**必须是 MCP**。而引擎你选了 Java。

三者不冲突,答案是分层:

```
┌───────────────────────────────────────────────────────┐
│ L3  dsh 原生插件(TypeScript)  ← 审计/审批/UI/风控     │
│     @your-org/dsh-fix                                  │
│     · ctx.fixSession 服务                              │
│     · session.append 记账                              │
│     · ctx.approval 审批闸门                            │
│     · tools/pre-execute 风控 waterfall                 │
│     · 订单面板 UI 卡片                                  │
└──────────────────────┬────────────────────────────────┘
                       │ JSON-RPC / WebSocket(本机)
┌──────────────────────▼────────────────────────────────┐
│ L2  FIX 网关(Java)          ← 协议与会话              │
│     fix-gateway.jar                                    │
│     · QuickFIX/J:Logon/心跳/序列号/重传               │
│     · 订单状态机(ClOrdID → 生命周期)                  │
│     · 执行回报异步推送                                  │
│     · 同时暴露 MCP stdio 接口(跨工具复用)             │
└──────────────────────┬────────────────────────────────┘
                       │ FIX 4.4 over TCP
┌──────────────────────▼────────────────────────────────┐
│ L1  对手方:FIX 模拟器 / 券商 UAT                       │
└───────────────────────────────────────────────────────┘
```

**分层原则一句话:Java 守协议,TypeScript 守策略。**

有状态的东西(TCP 长连接、心跳、序列号)全部锁在 Java 进程里;
需要 `ctx` 的东西(记账、审批、UI)全部在 TS 插件里。
两边通过无状态的 RPC 通信 —— 这正好绕开"FIX 长连接 vs 工具调用无状态"的根本矛盾。

---

## 二、为什么不是单层

| 只做 MCP(纯 Java) | 只做原生插件(纯 TS) |
|---|---|
| ✅ 跨工具复用 | ✅ 审计/审批/UI 全有 |
| ❌ 拿不到 `ctx`,**无法记账** | ❌ Node 的 FIX 生态弱 |
| ❌ **无法接 ctx.approval** | ❌ 你的团队选了 Java |
| ❌ UI 只有通用卡片 | ❌ 无法跨工具复用 |
| ❌ 风控只能自己实现,绕不过就是绕不过 | |

双层同时满足两边。多出来的成本是一个进程边界 —— 对 FIX 这种本来就该独立进程的东西,这不是成本,是正确设计。

---

## 三、L2:Java FIX 网关

### 3.1 职责边界

**只做协议,不做策略。** 风控/审批/审计一律不在这层 —— 它们在 L3。
这层唯一的策略是 **hard limit**(见 3.4),作为最后一道物理护栏。

### 3.2 技术选型

| 组件 | 选择 | 依据 |
|---|---|---|
| FIX 引擎 | **QuickFIX/J** | 1147★,2026-08-17 仍在提交,支持 FIX 4.0–5.0 SP2 + FIXT1.1 |
| 传输 | Spring Boot + WebSocket | 执行回报要**异步推送**给 L3,纯 RPC 不够 |
| MCP 接口 | 官方 Java SDK,stdio | 跨工具复用走这条 |
| 模拟器 | 自建 acceptor(见 6.1) | 现成的都是 0★,不如自己写 |

> 注:QuickFIX/J 的 license 是 NOASSERTION(非标准 SPDX),商用前请让法务确认。

### 3.3 对外接口

**同步调用(L3 → L2):**

```
POST /session/logon        建立 FIX 会话
POST /session/logout       断开
GET  /session/status       会话状态、序列号、心跳
POST /message/send         发送任意 FIX message  ← 核心
POST /message/build        只构造不发送(给预览/审批用)
GET  /order/{clOrdId}      查单笔订单状态
GET  /orders               查全部活跃订单
```

**异步推送(L2 → L3,WebSocket):**

```
{ type: "exec_report",  clOrdId, ordStatus, lastQty, lastPx, raw }
{ type: "reject",       refSeqNum, reason, raw }
{ type: "session_event",kind: "logon"|"logout"|"disconnect"|"resend" }
{ type: "raw_in",       raw }   ← 每条入站报文,供 L3 全量记账
{ type: "raw_out",      raw }   ← 每条出站报文,供 L3 全量记账
```

`raw_in` / `raw_out` 是审计的基础 —— **L3 要记的是原始报文,不是解析后的摘要**。

### 3.4 Hard limit(最后护栏)

即使 L3 被绕过,这层也必须拒绝:

```java
// 配置化,启动时加载,运行时不可改
maxOrderQty          单笔最大数量
maxOrderNotional     单笔最大名义金额
maxOrdersPerMinute   频率上限
allowedSymbols       标的白名单
allowedMsgTypes      允许的 MsgType 白名单(见下)
sessionMode          SIMULATOR | UAT | PRODUCTION
```

**`allowedMsgTypes` 是重点。** 你要的是"自然语言发送各种 FIX message",但不是所有 MsgType 都该开放:

| 分类 | MsgType | 建议 |
|---|---|---|
| 会话层 | 0/1/2/4/5/A(Heartbeat, TestRequest, ResendRequest, SequenceReset, Logout, Logon) | **禁止模型直接发** —— 引擎自己管,模型乱发会破坏会话 |
| 交易 | D/F/G(NewOrderSingle, Cancel, Replace) | 开放,走审批 |
| 查询 | H/AF/AD(OrderStatus, OrderMassStatus, TradeCaptureReportRequest) | 开放,只读免审批 |
| 行情 | V/x/c(MarketDataRequest, SecurityListRequest, SecurityDefRequest) | 开放,只读免审批 |

**会话层消息必须由引擎独占。** 模型发一条 `35=4`(SequenceReset)就能把会话搞乱,这是不可接受的。

---

## 四、L3:dsh 原生 Cordis 插件

这层是整个方案的价值所在 —— **dsh 的架构在这里的收益是其他 harness 给不了的**。

### 4.1 包结构

```
packages/
  fix-session/          ctx.fixSession 服务(连 L2,管连接)
  tool-fix/             模型可见的工具
  fix-guard/            风控 waterfall(独立包,可单独禁用)
  fix-ui/               订单面板 Client 插件
```

拆四个包不是过度设计 —— **风控要能单独审计和单独禁用**,UI 要进 Client aggregate(dsh 的 Host/Client 是两个 TS program,不能混)。

### 4.2 `ctx.fixSession` 服务

```ts
export const name = 'fix-session'
export const inject = ['sessions']

export function apply(ctx: Context, config: Config) {
  const gateway = new GatewayClient(config.gatewayUrl)

  ctx.effect(() => {                          // ← Cordis 可逆副作用
    const ws = gateway.connectWebSocket()

    ws.on('raw_in',  msg => appendFixEvent(ctx, 'fix/inbound',  msg))
    ws.on('raw_out', msg => appendFixEvent(ctx, 'fix/outbound', msg))
    ws.on('exec_report', report => {
      appendFixEvent(ctx, 'fix/exec-report', report)
      ctx.emit('fix/exec-report', report)     // ← 给 UI 用
    })

    return () => ws.close()                   // ← 撤销键,插件卸载时自动断开
  })

  ctx.set('fixSession', new FixSessionService(gateway))
}
```

### 4.3 审计:扩展 `SessionEventMap`(核心)

dsh 那条铁律 **"模型可见即已记录"** 在这里是真正的合规资产。

```ts
declare module '@deepseek-ai/dsh-session/types' {
  interface SessionEventMap {
    'fix/outbound':     { raw: string; msgType: string; clOrdId?: string }
    'fix/inbound':      { raw: string; msgType: string; clOrdId?: string }
    'fix/exec-report':  { clOrdId: string; ordStatus: string; lastQty?: number; lastPx?: number }
    'fix/session':      { kind: 'logon'|'logout'|'disconnect'|'resend'; detail: string }
    'fix/rejected':     { reason: string; rule: string; proposed: string }
  }
}
```

**收益:任何一笔委托都能回放出 AI 当时看到了什么、基于什么决策、发了什么原始报文。**

监管问"这笔单为什么发出去",答案不是翻日志文件拼凑 —— 是**从会话日志精确重放**。这是 dsh 事件溯源架构的直接变现。

注意存原始报文(`raw`),不是解析后的摘要。**审计要的是字节级证据。**

### 4.4 审批闸门

dsh 有现成的 `packages/interaction/user-approval`,直接接:

```ts
async execute(args, exec) {
  const built = await ctx.fixSession.build(args)      // 先构造,不发送

  if (needsApproval(built.msgType)) {
    const decision = await ctx.approval.request({
      title: `发送 ${built.msgType} — ${built.summary}`,
      detail: renderFixPreview(built.raw),            // ← 人看到的是完整报文
      risk: assessRisk(built),
    })
    if (decision !== 'allow') {
      exec.agent.session.append('fix/rejected', {
        reason: 'user-denied', rule: 'approval', proposed: built.raw,
      })
      throw new Error('用户拒绝了该 FIX 消息')
    }
  }

  return ctx.fixSession.send(built)
}
```

**关键:先 `build` 再 `send`,中间插审批。** 人看到的是**即将发出的完整报文**,不是 AI 的自然语言描述 —— 描述可能和实际报文不符。

### 4.5 风控 waterfall

独立包,挂在 `tools/pre-execute` 上:

```ts
ctx.on('tools/pre-execute', async (call, next) => {
  if (!call.name.startsWith('fix_')) return next()    // 不管别的工具

  const verdict = riskEngine.check(call.args)
  if (verdict.denied) {
    return { kind: 'deny', reason: verdict.reason }   // ← 不调 next(),直接拦
  }
  if (verdict.needsApproval) {
    return { kind: 'ask', reason: verdict.reason }
  }
  return next()
})
```

这正是 Cordis waterfall 那条规矩的正当用法:**风控有决策权,所以可以短路。**

风控规则(建议全部配置化):
- 单笔数量/金额上限
- 日内累计敞口
- 标的白名单
- MsgType 白名单(和 L2 的 hard limit 对齐,双重保险)
- 频率限制
- **权限自动过期** —— 见 4.7

### 4.6 模型可见的工具

你要的是"自然语言发各种 FIX message",所以工具设计要**既能表达通用性,又不能失控**。

建议 6 个工具:

| 工具 | 用途 | 审批 |
|---|---|---|
| `fix_session_status` | 查会话状态、序列号、心跳 | 免 |
| `fix_send_order` | 发单(D),结构化参数 | **必须** |
| `fix_cancel_order` | 撤单(F) | **必须** |
| `fix_replace_order` | 改单(G) | **必须** |
| `fix_query` | 查询类(H/AF/V/x…) | 免 |
| `fix_send_raw` | **发任意 MsgType** | **必须 + 更严** |

`fix_send_raw` 是"各种 FIX message"这个需求的落点,但要额外约束:

```ts
{
  name: 'fix_send_raw',
  description:
    '发送任意 FIX 消息。仅用于结构化工具无法表达的场景。'
  + '会话层消息(Logon/Logout/Heartbeat/ResendRequest/SequenceReset)一律禁止 —— 由引擎管理。'
  + '每次调用都需要人工审批,审批人看到的是完整报文。',
  parameters: {
    msgType: { type: 'string', required: true },
    fields:  { type: 'object', required: true,   // tag → value
               description: '业务字段。BeginString/BodyLength/CheckSum/MsgSeqNum 由引擎计算,不要提供。' },
    reason:  { type: 'string', required: true,
               description: '为什么结构化工具不够用。会记入审计日志。' },
  },
}
```

**`reason` 必填是有意的** —— 强迫模型说明为什么要走这条口子,而且这句话进审计日志。

### 4.7 权限租约(强烈建议)

参考 dsh 生态里 `clustr-trading-console` 的设计:

> **执行权限是租来的,不是给的。**

```ts
interface FixPermit {
  sessionId:     string
  allowedSymbols: string[]   // 1–30 个
  maxOrders:      number
  maxNotional:    number
  expiresAt:      number     // ← 自动过期
}
```

- 默认状态:**只读**,只能查询
- 要下单:用户显式授予 permit,指定标的/笔数/金额/有效期
- permit 过期或用户切回只读:**自动撤销未使用的额度**

这条在 FIX 场景比散户场景更重要 —— 机构环境下"AI 有无限下单权"是不可接受的。

### 4.8 订单面板 UI

Client 插件(进 `tsconfig.client.json`),监听 `fix/exec-report` 渲染:

- 活跃委托列表:ClOrdID / 标的 / 方向 / 数量 / 已成 / 状态
- 成交明细流
- 会话状态条:连接/序列号/心跳/最后心跳时间
- 原始报文查看器(点开看字节级内容)

**注册 `presentCall` / `presentResult`,让每次 FIX 工具调用在对话流里显示为订单卡片**,而不是一坨 JSON。

---

## 五、MCP 层(跨工具复用)

L2 的 Java 进程**同时**暴露 MCP stdio 接口,让 Claude Code / Cursor 也能用:

```json
{
  "mcpServers": {
    "fix": { "command": "java", "args": ["-jar", "fix-gateway.jar", "--mcp"] }
  }
}
```

dsh 侧配置(`mcp-client` 的实际用法):

```yaml
- id: mcp-fix
  name: '@deepseek-ai/dsh-mcp-client'
  config:
    serverName: fix
    transport: stdio
    command: java
    args: ['-jar', '/opt/fix-gateway.jar', '--mcp']
```

**但要明确告知用户能力差异:**

| | dsh 原生插件 | MCP 模式 |
|---|---|---|
| 审计留痕 | ✅ 进会话日志,可回放 | ❌ 只有 L2 自己的日志 |
| 人工审批 | ✅ `ctx.approval` | ⚠️ 只能靠 L2 的 hard limit |
| 订单面板 | ✅ | ❌ 通用卡片 |
| 风控 | ✅ 两层(L3 + L2) | ⚠️ 单层(L2) |

**建议:MCP 模式默认只开放只读工具**(查询/行情),下单类工具仅在 dsh 原生模式下启用。
理由:MCP 拿不到 `ctx.approval`,让它下单等于放弃审批闸门。

---

## 六、实施路线

### 阶段 0:模拟器 + 链路打通(1 周)

现成的 FIX 模拟器都是 0★(`snirSha/fix-trading-simulator`、`roddytin/mockfix`),**建议自建**:
用 QuickFIX/J 写个 acceptor,收到 `35=D` 就回 `35=8`(New → PartialFill → Filled)。
几百行,完全可控,还能造各种异常场景(拒单、断线、序列号跳变)。

**验收:** `java -jar fix-gateway.jar` 能连上模拟器,完成 Logon,发一笔 D 收到 8。

### 阶段 1:L2 网关(2 周)

- QuickFIX/J 会话管理 + 订单状态机
- HTTP + WebSocket 接口
- Hard limit + MsgType 白名单
- **验收:** curl 发单,WebSocket 收到 exec report

### 阶段 2:L3 只读插件(1.5 周)

- `ctx.fixSession` 服务 + WebSocket 订阅
- `SessionEventMap` 扩展,全量报文记账
- `fix_session_status` / `fix_query` 两个只读工具
- **验收:** AI 能用自然语言查会话状态和订单,且**每条报文都能从会话日志回放出来**

### 阶段 3:审批 + 下单(2 周)

- `ctx.approval` 接入,build/send 分离
- `fix-guard` 风控 waterfall
- 权限租约机制
- `fix_send_order` / `fix_cancel_order` / `fix_replace_order`
- **验收:** AI 说"买 100 股 AAPL",弹审批框显示完整报文,确认后发出

### 阶段 4:通用报文 + UI(2 周)

- `fix_send_raw`(严格白名单 + 强制 reason)
- 订单面板 Client 插件
- **验收:** AI 能发出结构化工具覆盖不到的 MsgType,且面板实时反映状态

### 阶段 5:MCP 导出(0.5 周)

- L2 加 `--mcp` 模式,只导出只读工具
- **验收:** Claude Code 能查行情

**总计约 9 周。** 阶段 2 结束就有可演示的价值。

---

## 七、必须避开的坑

| 坑 | 后果 | 对策 |
|---|---|---|
| **让模型发会话层消息** | 一条 `35=4` 搞乱序列号,会话崩 | MsgType 白名单,L2/L3 双层拦截 |
| **审批时只给模型的自然语言描述** | 描述与实际报文不符,人批了个假的 | 审批框展示 **build 出的完整报文** |
| **只记解析后的摘要** | 审计缺字节级证据,监管不认 | 记 `raw`,原始报文 |
| **模型自己算 MsgSeqNum/CheckSum** | 必错,且破坏会话 | 引擎独占计算,schema 里明确禁止提供 |
| **执行回报当同步返回值** | FIX 回报是异步多条,会漏 | WebSocket 推送 + 状态机 |
| **AI 有常驻下单权** | 失控风险 | 权限租约 + 自动过期 |
| **UI 包进 Host aggregate** | dsh 的 `Context` 声明合并冲突,编译失败 | Client 插件只登记进 `tsconfig.client.json` |
| **QuickFIX/J license 未核实** | 商用风险 | license 是 NOASSERTION,**上生产前让法务确认** |

---

## 八、这个方案对 dsh 的意义

值得单独说一句:**这可能是 dsh 生态里第一个 FIX 插件。**

我查过 —— 官方仓库零结果,`awesome-dsh-plugin-stock` 26 个金融插件零提及,
`LLMQuant/awesome-trading-agents` 整个 LLM 交易生态也明确指出:

> Key gap: **No FIX-native implementations.**

而 dsh 恰恰是最适合做这件事的 harness —— 因为**事件溯源 + 审批 seam + waterfall 风控**
这三样,正好对应机构交易场景最硬的三个要求:审计、授权、风控。

做出来之后建议加 `dsh-plugin` topic,并提给 `awesome-dsh-plugin-stock`。

---

## 附:关键依赖

| 组件 | 地址 | 状态 |
|---|---|---|
| QuickFIX/J | https://github.com/quickfix-j/quickfixj | ★1147,2026-08-17 活跃,license 需核实 |
| fix-protocol-mcp | https://github.com/slenderongithub/fix-protocol-mcp | ★0 停更,但**FIX 4.4 字典和工具定义可参考** |
| dsh user-approval | `packages/interaction/user-approval` | 官方内置 |
| dsh mcp-client | `packages/mcp/mcp-client` | 官方内置 |
| clustr-trading-console | https://github.com/0xEryx/clustr-trading-console | 风控设计参考 |
