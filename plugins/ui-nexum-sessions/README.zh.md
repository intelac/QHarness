# @deepseek-ai/dsh-client-ui-nexum-sessions

[English](README.md) | 中文

面向正在处理 FIX 工作的会话，实时显示 FIX 会话状态。它向 ui-conversation 的 `conversation.input.dock` 列表槽位注册一个条目，在输入框旁渲染每个 FIX 会话的连接状态与序列号：每个会话一个圆点、会话 id，以及 `nextSenderSeqNum/nextTargetSeqNum` —— 诊断一个会话时，FIX 链路两端最先援引的就是这一对数字。

这个状态条会自行决定是否出现。每个会话都会挂载该组件，而组件从该会话自身的记录判断要不要渲染任何东西：它查找名为 `mcp__nexum__*` 的工具调用，无论是仍在运行的，还是已记录为工具结果的。从未使用过 FIX 工具的会话不渲染任何内容，因此无关会话不会为它付出屏幕空间。由于这个判断是每次渲染时从会话存储派生的，而非保存在本地，它能在重新挂载后依然成立，并在重新打开旧会话时立即正确。

状态以推送方式送达，走 NEXUM 引擎的 `/api/sessions/stream` Server-Sent Events 端点。订阅者在任何变更之前会先收到一个 `snapshot` 事件，因此状态条在连接时即被填充，而不是一直空白到某个会话恰好发生变化；随后每个 `session` 事件都携带该会话变更后的状态，因此一行可以直接由事件替换，无需再发一次请求。轮询在两个方向上都是错的：会话掉线正是轮询间隔显得过长的时刻，而这恰恰是本组件存在的意义。当流出错时，状态条会说明 NEXUM 不可达，而不是继续显示最后已知的行 —— 那会把陈旧状态当作当前状态呈现；`EventSource` 会自行重连，重连会带回一份新的快照。

NEXUM 是一个独立进程，持有 FIX 会话和每一笔订单的状态。本包只读取它，从不修改会话：连接、重置序列号，以及引擎提供的其他一切操作，都通过模型的 `mcp__nexum__*` 工具抵达，而不经由这个状态条。

Node 侧是一个空的 `apply`：它的存在只是为了让该插件出现在宿主 cordis.yml 与 Loader 中，浏览器侧则通过 `exports["./client"]` 交付，并由 `dsh.client` 清单声明被发现。

## Model Experience

None, as the bar is browser chrome read by the person watching the conversation: nothing it renders reaches a model request, and it contributes no tool, prompt text, or session event. The model learns session state only by calling a NEXUM tool itself.

#### KV Cache effect

None; this package neither assembles nor sends a provider request.

## Known Limitations and Deferred Work

- **引擎来源地址固定为 `http://127.0.0.1:18080`** —— 浏览器侧由模块加载器加载，而非由 cordis Loader 组合，因此没有 `Config` 可供读取随部署变化的 URL。若 NEXUM 不在浏览者本机上，需要先补上这条链路。
- **依据工具名前缀判断** —— 若在 cordis.yml 中以 `nexum` 之外的 `serverName` 挂载 NEXUM MCP 服务，产生的工具名本组件无法识别，状态条将保持隐藏。
- **浏览器侧无法按会话过滤** —— 端点接受 `sessions=` 过滤参数，但状态条始终订阅引擎持有的全部会话。
