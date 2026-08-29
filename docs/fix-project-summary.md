# FIX 项目讨论总结

> 整理日期:2026-08-24
> 用途:回顾这轮对话里你表达的需求 + 调研得到的事实,供决策参考

---

# 第一部分:你说过的话(按时间顺序)

## 1. 起点:分析 DeepSeek Harness

让我深度分析 dsh 的 README 和架构文档。看完之后你的反馈是"太晦涩",于是逐层往下问:

- Cordis 是什么、四种事件分发的区别
- 「原生插件」是什么意思、一定要某种语言吗
- 真正的业务逻辑怎么实现的
- 大模型的返回值怎么接收
- **agent 如何判断结果**(模型返回自然语言,系统凭什么知道该停还是该继续)
- 工具都是 MCP 工具吗

**这一段的实质:你在系统性地拆解 dsh 的架构范式,不是在学怎么用它。**

## 2. 转折:FIX 协议

问有没有 FIX 协议插件 → 没有 → "我需要给 dsh 做" → 让我给方案。

我给了一份 9 周的机构级方案(双层架构、审批、风控、审计、UI),你的反应是逐步收紧范围:

| 你说的 | 实质 |
|---|---|
| "我只是想先完成一个能够收发 FIX message 的小工具" | 砍掉一切非必需 |
| "怎么感觉就是个 QuickFIX 做一个 CLI 呢" | ✅ 判断正确,我承认了 |
| "FIX 引擎是一个 socket,有状态的如何做" | 指出了工具调用模型的根本错配 |
| **"FIX message 多了之后 LLM 是没办法记住所有 message 的"** | ⭐ 指出了我方案的真实缺陷 |
| **"我甚至觉得它也许并不适合用 AI 来做"** | ⭐ 跳出了"怎么做",问"该不该做" |
| "不是的。我需要的是能收发能看到 message 的" | 否定了我提议的"离线调试助手" |

**这一段的实质:你在反复压缩范围,同时质疑前提。而我几次跟不上你的判断层级。**

## 3. 关键输入:fixparser.dev

你甩给我一个 URL,让我看看。这是转折点——它证明了这个方向有人在做,而且是商业产品。

然后:**"给我做全面的调查,我要做一个竞品"** → **"我想做一个开源项目"**

## 4. 最终澄清:你真正要的东西

在我给了一堆基于调研的定位建议后,你直接把方向拉回来:

> **"你可以不用质疑这个整个产品的市场。我做了 10 年的 FIX 方面的开发。我非常了解。**
> **我需要做一个 FIX engine 的系统,在 QuickFIX 基础之上可以做更多 customize 的处理。**
> **比如 validate 和 enrich message。QuickFIX 只是一个开始。"**

接着补充了系统的形态:

**系统分成五个部分:**
```
order engine
instrument
counterparty
order monitoring
alert system(监控告警)
```

**Counterparty 的内部结构:**
- 包含 session 的 setup
- 包含 plugin
- **通过 plugin 可以做 validation,还可以改 incoming 和 outgoing message**
- **分三层:client / session / destination**
- **保留 incoming 的 tag 和 outgoing 的 tag,并且可以自定义 tag**
- **"有点儿像 Ullink"**(UL BRIDGE)

**但是:**
> "这些都是未来的系统。现在我们要做的是最 basic 的事情"

**架构原则:**
> **"我觉得所有的功能都是独立的,一切皆是 plugin"**
> **"就像 DeepSeek Harness 一样的感觉"**

**最后卡住的地方:**
> "还差的很远。你结合 AI 了吗"
> "有 AI agent 的概念吗?我让你分析的 fixparser.dev 你都分析出来个啥?"
> **"我还没有想好。让我再想想。"**

---

# 第二部分:你的需求提炼

## 已经明确的

| 项目 | 内容 |
|---|---|
| **要做什么** | 一个 FIX engine 系统,建在 QuickFIX 之上 |
| **核心能力** | validate、enrich、改写双向消息,以及更多定制处理 |
| **架构范式** | 一切皆插件,像 dsh 那样 |
| **系统构成** | order engine / instrument / counterparty / order monitoring / alert |
| **Counterparty 模型** | client / session / destination 三层,保留双向 tag,支持自定义 tag,参考 Ullink |
| **交付方式** | 开源项目 |
| **当前阶段** | 最 basic 的事情 |
| **你的背景** | 10 年 FIX 开发经验,不需要市场论证 |

## 尚未确定的(你正在想)

**⚠️ AI 在这个系统里扮演什么角色。**

我列过几种可能,你还没选:

- **A. AI 是操作者** —— 像 FIXParser 那样,AI 连 session、发消息、下单
- **B. AI 是配置生成器** —— 读 RoE 文档,生成 counterparty 配置和 plugin
- **C. AI 是诊断器** —— 消息被拒、会话断了、单子卡了,AI 分析根因
- **D. AI 是开发者** —— 你描述需求,AI 生成 plugin,热加载进去
- **E. AI 是运行时 plugin** —— 某些决策由 AI 做(在订单路径上)

**这个不定,架构就定不下来。**

## 其他待定项

- Java 版本(17 / 11 / 8)
- 内核自己写还是用 Spring(我建议自己写,约 300 行)
- Plugin 接口的具体契约(消息可变性、reject 语义、是否支持 IO、热加载)

---

# 第三部分:调研结果(事实部分)

> ⚠️ 说明:调研过程中 subagent 出现过虚构内容并自我更正。下面只保留**经 API 实测或多源交叉验证**的部分。

## A. 开源 FIX 引擎 —— 许可证与健康度

### 🔴 最重要的许可证发现

**整个 QuickFIX 家族(C++/J/Go/N/Rust binding)用的不是 Apache/MIT**,而是自定义的 **"QuickFIX Software License v1.0"**(GitHub 标为 `NOASSERTION`)。

- ✅ BSD 风格,**不是 GPL,无传染性,可闭源商用**
- ⚠️ **条款 3**:最终用户文档必须致谢 "This product includes software developed by quickfixengine.org"
- ⚠️ **条款 4/5**:未经书面许可,**产品名不得含 "QuickFIX"**,不得用其背书
- ⚠️ 未经 OSI 认证,Debian legal 曾就这两条讨论过能否进 main

**实务影响:能用能卖,但要加致谢声明,产品不能叫 "XXQuickFIX"。**

### 引擎对照表(GitHub/npm/PyPI API 实测,2026-08-24)

| 引擎 | 语言 | ★ | License | 最后推送 | 90天提交 | 备注 |
|---|---|---|---|---|---|---|
| quickfix/quickfix | C++ | 1981 | QuickFIX v1.0 | 2026-05-20 | **0** | v1.16.0 距上次发布隔 8 年(2018→2026),爆发式维护 |
| **quickfix-j/quickfixj** | Java | 1147 | QuickFIX v1.0 | 2026-08-17 | **100** | 事实标准,最健康。~50µs,锁重、GC 高 |
| quickfixgo/quickfix | Go | 892 | QuickFIX v1.0 | 2026-07-31 | 9 | **仍是 v0.9.10,未到 1.0** |
| connamara/quickfixn | C# | 524 | QuickFIX v1.0 | 2026-07-06 | — | 稳定 |
| fix8/fix8 | C++ | 467 | ❌ **LGPL-3.0** | 2025-12-12 | — | **唯一 copyleft,静态链接有风险** |
| **artiofix/artio** | Java | 359 | ✅ **Apache-2.0** | 2026-08-22 | 65 | Real Logic,Aeron 生态,低延迟,零分配 |
| **paritytrading/philadelphia** | Java | 345 | ✅ **Apache-2.0** | 2026-08-19 | 74 | 零分配,FIX 4.0–5.0SP2 全覆盖 |
| da4089/simplefix | Python | 255 | ✅ MIT | 2026-06-01 | — | **142K 周下载,但只是 parser,无 session** |
| **TimelordUK/jspurefix** | TS | 78 | ✅ **MIT** | 2026-08-22 | **100** | **87K 周下载**,完整 session,零原生依赖 |
| arthurlm/quickfix-rs | Rust | 60 | ⚠️ 标注错误 | 2026-05-24 | — | 声称 MIT/Apache,`LICENSE-APACHE` 实为 QuickFIX 许可证 |
| Validus/hotfix | Rust | 19 | ✅ MIT | 2026-05-13 | — | 仅 buy-side/initiator,仅 FIX 4.4 |
| truefix-labs/truefix | Rust | 3 | ✅ Apache/MIT | 2026-08-13 | — | 自称 483/483 一致性测试通过 |
| WTFIX | Python | — | ❌ LGPL-3.0 | **2021 已死** | — | 双重问题 |

### 关键结论

- **许可证干净 + 完整 session + 活跃维护:** Artio、Philadelphia(Java, Apache-2.0)、jspurefix(TS, MIT)
- **要避开:** Fix8(LGPL)、WTFIX(LGPL 且死)、quickfix-rs(许可证标注错误)
- **Python 没有活跃的纯 Python session 引擎** —— simplefix 只是 parser,明确声明"不做 socket、不做恢复、不做持久化"
- 性能参考:QuickFIX/J ~50µs(锁重、分配多);Chronicle FIX <2µs;Artio/Philadelphia 是 JVM 上的零分配选项

---

## B. FIXParser —— 唯一的商业竞品

### 公司与产品

- **FIXParser Ltd**,英国公司注册号 12751046,伦敦
- **单人公司**,创始人 Victor Norgren(logotype)
- npm 包始于 2015,**471 个版本**,最新 9.4.11(2026-08-20),月度发版节奏
- **GitLab 仓库是私有的** —— npm/README/官网都链过去,但返回 404 或跳登录页;README 自己的 CI badge 是死链
- **没有 issue tracker、没有社区、没有 roadmap、没地方报 bug**
- HN / Reddit / SO **零实质讨论**
- ⚠️ 创始人 LinkedIn 现挂 "Inference Engine for Agents | LayerScale",2026-07 发布了 `@layerscale/layerscale` —— **可能已转向**

### 分档与定价

| 档 | 价格 | 内容 |
|---|---|---|
| Free | $0(需注册 license key,1 年过期) | **仅解析/校验** |
| Pro | **$5K+/年** | 创建/编码消息、FIX-as-JSON、远程连接、FIX Server、TCP/SSL/WS |
| Enterprise | **$10K+/年** | **MCP Server**、agent CLI、异常检测、分析看板 |

**"$5K+/$10K+" 是锚点不是价格。** 定价页是询价表单,无自助购买、无公开单价、无试用条款。

⚠️ **分档自相矛盾**:npm README 把 "AI Agent Integration" 列为 **Pro**,定价页说是 **Enterprise**。

### 商业模式的技术实现(我亲自下载解包验证)

**代码全给,用 PGP 签名的 license key 解锁:**

```js
// LicenseManager 内嵌两个硬编码 PGP 公钥
setLicenseKey() → base64 解码 → readCleartextMessage() → verify()
const [, , expiryTimestamp, licenseTypeValue] = text.split("|");
// 类型:free | trial | pro
```

**版本锁死:**
```js
#RELEASE_INFORMATION = "MTc4NzE4NzE2OTAxOA=="  // → 2026-08-19
// 若 releaseDate >= expiry 则拒绝 → 你的 license 让新版本失效,强制续费
```

**保护是纯客户端布尔判断,共 9 个 gate:**
```js
encode(separator = SOH) {
  if (!LicenseManager.validateLicense()) throw new Error(...);
}
```
无解密、无服务端回调、无遥测。代码**完全没混淆**,还附带 84MB sourcemap。

### 包体积

```
fixparser 9.4.11:  777 文件,134 MB 解包(17 MB tarball)
  增长:27MB(v4) → 41MB(v5) → 118MB(v7.4.3) → 134MB(v9.4)
  其中 ~84 MB 是 sourcemap
MCP 插件和 CLI 插件各自又打包整个库(每个 123 MB)
→ Enterprise 装完 250 MB+
```

用户为 DRM 付出了 60MB OpenPGP 的体积代价(与 FIX 功能无关)。

### ⭐ 它的 AI Agent 到底是什么(你问的重点)

**就是 8 个 MCP 工具:**

| 工具 | 参数 |
|---|---|
| `parse` | fixString |
| `parseToJSON` | fixString |
| `verifyOrder` | clOrdID, handlInst, quantity, price, ordType, side, symbol, timeInForce |
| `executeOrder` | 同上 8 个 |
| `marketDataRequest` | mdUpdateType, symbols[], mdReqID, subscriptionRequestType |
| `getStockGraph` | symbol |
| `getStockPriceHistory` | symbol |
| `technicalAnalysis` | symbol |

**产品逻辑:让 LLM 直接成为交易终端的操作者。**

**技术细节:**
- 传输:`stdio`(bin `fixparser-mcp-local`)和 `http`(**端口 3099,路径 `/mcp`**)
- 配置全走环境变量:`FIXPARSER_LICENSE_KEY` / `HOST` / `PORT` / `SENDER` / `TARGET`
- 硬编码默认:FIX.4.4、端口 5001、HeartBtInt 10、EncryptMethod None

**唯一的安全设计:两步确认**
```
"verifyOrder must be called before executeOrder"
状态存在内存 Map 里
```

**🚨 严重安全缺陷:HTTP 传输零认证**
```
initializeHttpTransport() → node:http 服务器
无鉴权、无 Bearer token、无 CORS、无来源校验、无限流
session ID 对任何 initialize 请求随手 randomUUID() 发一个
→ 能连上端口 3099 的人就能通过 executeOrder 下真实订单
```

**🚨 上下文洪流:完全没处理**
```js
prices.push(data);
if (prices.length > maxPriceHistory) {
  prices.splice(0, prices.length - maxPriceHistory);
}
// MAX_PRICE_HISTORY = 1e5   每 symbol 十万条,symbol 数量无上限
```
**没有上下文管理、没有采样、没有聚合、没有背压、没有 token 预算。** 工具直接把完整 JSON 甩给 LLM。

> 这正是你在对话中指出的问题:「FIX message 多了之后 LLM 是没办法记住所有 message 的」

**"40+ 技术指标"是真的** —— 实际 65 个 `static calculate*` 方法(RSI、MACD、Bollinger、Ichimoku、Kalman、GARCH、ARIMA、Black-Scholes、二叉/三叉树、蒙特卡洛、Heston、SABR、Elliott Wave、Fourier/Hilbert、Sharpe/Sortino/Calmar、VAR)。
但注意:它们算的是**服务器自己收到的 FIX 行情**,不接任何历史数据源。图表走 `quickchart-js`(**外部 HTTP 服务,对交易公司是数据外泄风险**)。

**营销 vs 代码:** anomaly detection、sentiment analysis、adaptive learning、NLP —— grep 全部包,`anomaly`/`sentiment`/`langchain`/`openai`/`anthropic` **零命中**。这些功能没有实现代码。

### 市场表现:下滑

```
fixparser        12 个月共 208K 下载
                 峰值 17,576(2025-10)→ 之后 9.5K–14K/月
fixparser-common 峰值 10,113(2025-07)→ 2,703(2026-08)  ↓73%
fixparser-plugin-mcp  仅 1,592/月(核心库的 1/20)
npm 依赖此包的包:0 个
Glama 评级:D 质量 / D 维护 / "当前无法安装" / 无人认领
```

**对比:jspurefix(开源 MIT)87K 周下载 ≈ 348K/月,是 fixparser 的 20 倍以上。**

### 历史彩蛋

**FIXParser 到 v4.2.3(2021-08-17)为止是 MIT 许可的。**
2021-08-20 的 v5.0.0 同时改了许可证并加入 openpgp —— 同一版本完成商业化。
**v4.2.3 至今仍可从 npm 下载,仍是 MIT。** 另有 `leandrofinger/fixparser`(2019 年 MIT fork)。

### 文档

- **没有公开 API 参考**
- `/ai-agents` 页面**没有工具名、没有参数、没有配置示例**,纯营销
- 唯一的真文档在 `fixparser-plugin-mcp` 的 npm README —— **但官网从不提这个包名**
- 官网列的客户 logo(Goldman Sachs、Coinbase、UBS…)**无案例、无证言、无客户数,应视为未经证实**

---

## C. 其他 FIX + AI 项目 —— 基本是空地

搜遍 GitHub/GitLab/npm/PyPI/Maven/官方 MCP registry,**13 个项目,9 个是 0 星**。

| 项目 | 实况 |
|---|---|
| **APEX-Standard/protocol** | MCP 原生规范,自称"AI agent 与券商通信的标准",**明确定位为 FIX 的替代品**。CC-BY-4.0 规范 + Apache-2.0 参考实现(TS/Rust/Go/Java),`apex.session.*`/`apex.order.*`/`apex.risk.*` 工具命名空间,161 项一致性检查。**但是单人项目:16 commits、16★、0 fork、1 watcher、v0.3.0-alpha、无具名券商采用**。设计文档质量极高,等于免费 R&D。弱点:在重新发明 FIX 已有的东西(序列号、重传、消息日志、kill switch) |
| **CSOAI-ORG/fix-bridge-mcp** | 官方 MCP registry 里唯一的 FIX 条目,2026-08-19 发布,**0★ 0 fork**,3 个 stub 工具,README 自称 "100/100 A+++++"。占坑性质 |
| **slenderongithub/fix-protocol-mcp** | 离线报文解析器,4 个工具(parse/validate/build/explain),**0★,创建当天停更**,MIT。**FIX 4.4 字典和工具定义可参考** |
| **FixTool** | Apache-2.0,12★,Kotlin 桌面 FIX 客户端内嵌 MCP,单人维护 |
| **joyrana/fixai-platform** | "AI FIX 认证平台",最接近某些定位,但**只有 6 次提交,是空壳** |
| 其余 8 个 | 1–11 次提交的周末项目 |

### 关键负面证据(证明是空地)

- PyPI 上 `fix-protocol-mcp` / `fix-mcp` / `fixmcp` **全部 404**
- **awesome-mcp-servers(数百条目):零个 FIX**
- **LLMQuant/awesome-trading-agents(388★):grep "FIX protocol|quickfix" 结果为 0**
- 传统 FIX 厂商(Esprow / Broadridge-Itiviti / VeriFIX / B2BITS / Rapid Addition)**2025–2026 无任何 AI/LLM/MCP 功能发布**
- FIX Trading Community 2026-06 发布算法测试认证建议实践,**无 AI 内容**

---

## D. 相邻领域:零售券商 MCP —— 已饱和,且全绕开 FIX

```
Webull        2026-06  可下单($10K 名义上限 + 白名单)
cTrader/Spotware 2026-05  官方 MCP,可执行
ThinkMarkets           agent 代客执行
Robinhood              可下单
IBKR                   ⚠️ 只读,设计上不允许 agent 下单
IG Group               只读
Alpaca                 925★ MIT,默认 paper trading
Polygon                383★
```

**全部是 REST 封装,没有一个碰 FIX。**

### 值得借鉴的设计模式

**对抗上下文洪流:**

| 模式 | 出处 | 做法 |
|---|---|---|
| **SQL 侧信道** ⭐ | Polygon 官方 | `call_api` 用 `store_as` 落地到内存 SQLite,`query_data` 跑 SQL。**tick 数据永不进上下文** |
| **引用式组合** | APEX | 返回 URI 不返回 payload,附 `sequence` + `stale_after_ms`。同时解决 token 和一致性 |
| **资源/工具/通知三分** | APEX | "100ms 轮询一个品种 = 每分钟 600 次调用……花在 token 上的钱超过市场能赚的" |
| 服务端计算 | Polygon | greeks/sharpe/SMA 服务端算完再回 |
| 限定订阅窗口 | tastytrade | 期权链默认 ATM 上下 15 档,greeks 需显式 opt-in |
| 默认拒绝的工具面 | Alpaca | 白名单,"新端点默认排除" |
| 重连折叠行情 | APEX | SSE 重连只重放执行类事件,行情压成 gap-fill 标记 |

**风控与审批:**

| 模式 | 出处 |
|---|---|
| **"模型提议,运行时强制"** ⭐ | APEX:"LLM 产生意图,确定性代码在工具调用抵达券商**之前**校验。**模型永不直接与券商对话**" |
| **熔断在代码层、在问 LLM 之前** | APEX:7 种熔断(行情过期/风控过期/序列断裂/未重建状态就重连/kill switch/受限品种/非交易时段) |
| **三重 AND 门** | tastytrade:`ENABLE_LIVE_TRADING=true`(否则下单工具**根本不注册**)+ `FORCE_DRY_RUN=false` + 显式传 `dry_run=false` |
| 按实时账户状态算额度 | tastytrade:从 `used_derivative_buying_power` 推导,**不是内存计数器** → 重启和多实例下仍正确 |
| **执行发生在 AI 会话之外** | IBKR:agent 起草,人在 IBKR 平台提交 |
| **信任边界包装** | Alpaca `security.py`:每个结果包 `trust: "untrusted_tool_output"` |

⚠️ **MCP annotations(`readOnlyHint`/`destructiveHint`)只是提示不是保证,不能当门禁。**

---

## E. 监管与安全(如果 AI 要碰真实订单)

### ESMA 监管简报(2026-02-26,ESMA74-1505669079-10311)

经 ESMA 官方 + 四家律所交叉验证:

> **即使有人工介入**,只要计算机算法决定了**任何单个订单参数**(下单路由和后处理除外),就构成 MiFID II 下的"算法交易"。
> **人在环路中不构成豁免。**

符合 AI 系统定义的还要承担 EU AI Act 义务。

### SEC Rule 15c3-5(d)

预交易风控必须处于券商的**"直接且排他的控制"**之下 → **风控层不能在 LLM 内部、后面或下游。**

### FINRA 2026 监管报告

点名可执行的 MCP 需要治理框架,**责任在券商方**(监管信号,尚非规则)。

### MCPTox(arXiv 2508.14925)

45 个真实 MCP server、353 个工具、20 个 agent:

```
工具投毒攻击成功率      高达 72.8%(o1-mini)
最高拒绝率              不足 3%(Claude-3.7-Sonnet)
且"能力越强的模型往往越易受攻击" —— 攻击利用的正是指令遵循能力
```

**三条合起来的结论:模型层护栏不可靠,风控门必须是确定性的、在 LLM 之外。**

---

## F. FIX 生态痛点(SO 语料实测)

**语料:** ~1,873 个 SO 问题(quickfix 751、fix-protocol 626、quickfixj 349、quickfixn 147)

**无人回答率 28–35%**(fix-protocol 177/626、quickfix 212/751、quickfixj 122/349)—— **专业知识锁在公司内部**

**市场规模信号:**
- SO 提问量 2015 年 116 题 → 2025 年 17 题(**-85%**)
- 英国 FIX 技能中位年薪 **£100,000(+11% YoY)**,但只有约 **111 个常设岗位**
- Jane Street 有专门的 "FIX Onboarding Engineer" 职位
- HN 上基本零 FIX 讨论
- VS Code 市场整个 FIX 生态只有 2 个扩展,最火的 `geh-fixmaster` 只有 5,962 装机

> ⚠️ 两份调研在"痛点排名第一"上有分歧:一份说方言碎片化(26.5% 按主题分类),一份说测试/模拟(最高票 + 最高浏览)。两者不冲突——方言是**最普遍**的痛,测试是**最集中**的痛。

### 痛点清单

**① 测试 / 没有对手方可连**
- 全领域最高票 + 最高浏览:*"How to test my FIX client? Is there a fake FIX exchange out there?"* —— **31 票,24,897 浏览**
- 加 *"Open Source FIX Client Simulator"* —— 12 票,15,518 浏览
- **SO 给的标准答案是死的**:FIXimulator,37★,**最后提交 2012-10-31,连 license 文件都没有**
- 活着的全是 quote-based 企业方案(VeriFIX/Broadridge、Esprow ETP、B2BITS、PhiFIX),FIXSIM 唯一透明定价 **$500/月**

**② 方言碎片化**

HN 从业者原话:
> "**碎片化到令人难以置信。** 同一家券商的不同部门都能有不同标准……核心问题是你可以在 FIX 消息上强加任意语义。**'GBp' 是便士吗?有时是!有时不是!**"

> "**没人真的遵守 4.2 或 5.0,他们说自己'衍生自'它**,然后你跑完他们 100 条消息的一致性测试才知道改了什么。"

**FIX Orchestra(机读 RoE)本该解决,但社会性失败** —— 官方 `FIXTradingCommunity/orchestrations` 仓库(14★)只有 `Examples/` 和 `FIX Standard/`,**零个真实场馆文件**。大多数 RoE 仍是 PDF。厂商 OnixS 自认"对 RoE 规范的解读**通常是手工过程**",并为此单卖一个 "FIX Dialects" 产品。

**③ 误导性 Reject / 调试不透明**

错误信息**主动指错方向**:

| 报错 | 浏览 | 真实原因 |
|---|---|---|
| "Required tag missing 但 tag 明明在" | — | 字段属于重复组 |
| "Field not found 但字段存在" | 3,980 | 组 1 有、组 2 没有 |
| "Tag not defined for this message type" | 8,656 | 字典里有,但消息定义里没加 |

其他高频:`58=Conditionally Required Field Missing`、`Tag appears more than once in 'W' message`、`Disconnecting: END_OF_STREAM`、`value out of range for this tag`

**每一个都需要专家把误导性症状映射到字典/组根因。没有工具做这件事。**

**④ 字典管理**
- *"Add user defined fields in the FIX dictionary"*(12 票)
- *"How to customize field types per exchange?"*
- 事实标准的 DataDictionary XML 格式**没有 schema、作者不详**

**⑤ Session 生命周期**
- *"QuickFIX what are StartTime and EndTime supposed to do?"*(14 票)
- 序列号、logon 失败
- QuickFIX/J 自己的高热 open issue:failover 第二地址重试(18 评论)、session 结束后的 stale file handle、关闭校验时的额外字段日志(10 评论)

**⑥ 日志可读性 —— 查看已解决,别做日志查看器**
- aifixparser.com 免费且已做订单重建
- GitHub 上一堆 0★ 弃坑解析器
- QuickFIX 维护者原话:"没有此功能……QuickFIX 不认为这有用,cron/grep 组合就能搞定"

### 被证伪的假设

- ❌ **"引擎贵 / 厂商锁定是痛点"** —— 1,048 题里**只有 2 题**提成本。QuickFIX 免费化解了这个问题
- ❌ **"人才稀缺是痛点"** —— 开发者从不抱怨这个,反而当**护城河**
- ❌ **"上游已死"** —— 主流引擎都活跃。**死的是工具,不是引擎**

---

# 第四部分:我给过但已作废的建议

为避免误导,明确列出:

| 我说过 | 为什么作废 |
|---|---|
| 9 周机构级方案(双层架构 + 审批 + 风控 + UI) | 你说要做最 basic 的 |
| "上下文管理是核心卖点" | 从竞品缺陷倒推,不是从用户痛点正推 |
| "生命周期串联是最强空白" | aifixparser 免费且已做订单重建 |
| "做离线调试助手" | 你明确否定:"我需要的是能收发能看到 message 的" |
| "也许不适合用 AI 来做" | 你有 10 年经验,不需要我做市场判断 |
| "从模拟器切入" | 基于市场调研,但你的目标是 FIX engine 平台 |

**唯一还成立的建议:** 用 dsh 的架构范式(Context + Plugin + inject + waterfall + 可逆副作用 + 事件日志)来构建这个 FIX 系统。**因为这是你自己得出的结论。**

---

# 第五部分:待决策清单

## 🔴 阻塞性(不定就没法往下走)

**1. AI 在系统里的角色**

| 选项 | AI 的位置 | 监管风险 |
|---|---|---|
| A. 操作者(像 FIXParser) | 连 session、发消息、下单 | 高(ESMA 适用) |
| B. 配置生成器 | 读 RoE → 生成 counterparty 配置和 plugin | 无 |
| C. 诊断器 | 分析拒单、断线、卡单的根因 | 无 |
| D. 开发者 | 描述需求 → 生成 plugin → 热加载 | 无 |
| E. 运行时 plugin | 在订单路径上做决策 | 最高 |

**一个反推方法:你用 dsh 的时候,希望对它说什么话?一句具体的话就能反推出整个设计。**

## 🟡 技术选型

**2. 引擎底座**
- QuickFIX/J(你已指定)—— 记得加致谢,产品名不含 "QuickFIX"
- 或考虑 Artio / Philadelphia(Apache-2.0,更干净,零分配)

**3. Java 版本** —— 17 / 11 / 8

**4. 内核** —— 自己写(约 300 行,零依赖)vs Spring/Guice

## 🟢 Plugin 契约(最 basic 版本要定的)

**5. 消息可变性** —— plugin 直接改 vs 返回新对象

**6. Reject 语义**
- inbound reject → 自动回 `35=3` / `35=j` / `35=8`?谁构造?
- outbound reject → 怎么通知 order engine?订单状态改成什么?

**7. Plugin 能否做 IO** —— QuickFIX/J 的 `fromApp` 是同步回调,阻塞会拖垮心跳

**8. Plugin 怎么加载** —— 配置类名 + 反射 / SPI / 脚本;**能否热加载**

**9. Plugin 间共享状态** —— 无状态 / 共享 context / 自持状态

**10. 全局 plugin vs counterparty plugin** —— 执行顺序

---

# 附:dsh 架构范式速查(供设计参考)

## 三样核心

```java
// 1. 服务容器 —— 按 key 找服务,不 import 实现
ctx.transport / ctx.orders / ctx.instruments / ctx.counterparties

// 2. 依赖声明 —— 自动拓扑排序,无启动清单
List<String> inject() { return List.of("dictionary"); }

// 3. 可逆副作用 —— 卸载时自动撤销,热插拔的前提
ctx.effect(() -> {
    dictionary.register(customTags);
    return () -> dictionary.unregister(customTags);
});
```

## 四种事件分发

| 模式 | await | 顺序 | 返回值 | 用途 |
|---|---|---|---|---|
| `emit` | 否 | 注册序 | 无 | 日志、指标 |
| `parallel` | 是 | 并行 | 无 | 多个 store 都落盘 |
| `serial` | 是 | 注册序 | 有 | 挨个问答复 |
| **`waterfall`** | 否 | **嵌套** | 有 | **validate / enrich / reject** ⭐ |

**waterfall 规矩:只有有决策权的监听器才能短路(不调 `next()`)。观察和标注类必须放行。**
→ 在 FIX 里:validator 有权拒绝,logger 必须放行。

## 那条铁律

> **dsh:模型可见即已记录。** 抵达模型请求的一切必须能从日志重建,由运行时不变量断言。
>
> **你的系统:发到线路上的一切必须先记账。** —— 在金融场景这不是设计偏好,是合规刚需。

**推论:** 订单状态不是内存里的 map,是**从事件流 fold 出来的**。
→ Order Monitoring 直接建投影,Alert 直接订阅,重启回放天然支持,审计免费拿到。
**你五个域里有三个是这么来的。**

## Seam 三件套

一个可替换能力必须同时设计:**接口 + 实现 + 消费方**。单一角色不构成 seam。

```
Transport seam   接口=收发消息   实现=QuickFIX/Artio/自研   消费方=order engine
Store seam       接口=持久化     实现=内存/文件/DB
```

**换 transport 实现,order engine 一行不改。**

## 一处建议不照搬

dsh 用 YAML + `!!js` 表达式做配置。Java 里没有现成的 JS 求值,建议改成:**配置只做声明,条件逻辑用 profile 分文件叠加**。叠加机制照搬,表达式不搬。
