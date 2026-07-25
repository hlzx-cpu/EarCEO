# EarCEO 跨电脑 Codex 交接文档

更新时间：2026-07-25

这份文档是新电脑上 AI/Codex 的第一入口。不要只看 GitHub 默认分支就开始开发；
默认分支目前落后于最新开发分支。开始工作前必须完整阅读本文和本文列出的四份核心文档。

## 1. 唯一源码与禁止事项

- GitHub：`https://github.com/hlzx-cpu/EarCEO`
- 唯一有效单仓库：`hlzx-cpu/EarCEO`
- 当前最新开发分支：`codex/audio-stream-uplink`
- 本文写入前最新代码提交：`c020262`
- GitHub 默认分支：`codex/android-sdk-baseline`
- 默认分支当前基线提交：`d571564`
- `/Users/hanliangzhaoxuan/Developer/AdvX26/EarCEO-Backend` 是历史副本，
  不能继续开发、不能把它重新合并为第二个后端仓库。
- 所有 Android、CEO Gateway、测试和文档继续放在 EarCEO 单仓库。
- 不要提交 AppKey、AppSecret、API token、LiveKit/测试 token、AAR、APK、WAV、
  Android SDK 本地路径或真实转写文本。

新电脑第一次 clone 后应执行：

```bash
git clone https://github.com/hlzx-cpu/EarCEO.git
cd EarCEO
git fetch --all --prune
git switch codex/audio-stream-uplink
git pull --ff-only
git log -1 --oneline
```

在本文提交推送后，`git log -1` 应显示包含 `tocodex.md` 的迁移提交，而不再是
上面的 `c020262`。`c020262` 是迁移文档之前的代码锚点。

## 2. 开工前必须完整阅读

按顺序完整阅读：

1. `tocodex.md`
2. `docs/next-session-handoff.md`
3. `README.md`
4. `docs/backend-contract.md`
5. `docs/development-plan.md`
6. `docs/android-voip-development.md`
7. `docs/android-development.md`

不要仅依据旧聊天摘要实现；仓库代码和这些文档才是事实来源。

## 3. GitHub 分支和 PR 状态

当前工作是线性堆叠的：

```text
codex/android-sdk-baseline
  d571564
    ↓
codex/android-restart-recovery
  60572b1
    ↓
codex/approval-state-contract
  f485d80
    ↓
codex/android-approval-card
  8223505
    ↓
codex/audio-stream-uplink
  e7fd0a5
  c020262
```

开放 PR：

- PR #1：`codex/android-approval-card`
  → `codex/approval-state-contract`
- PR #2：`codex/audio-stream-uplink`
  → `codex/android-approval-card`

2026-07-25 检查时，两者的 GitHub `mergeStateStatus` 都是 `CLEAN`，公开仓库安全
检查通过，没有文本级 merge conflict。它们仍是 draft 和堆叠 PR；不要因为默认
分支较旧，就把后续提交错误地 rebase 到 `d571564` 并丢掉中间里程碑。

GitHub 默认分支 `codex/android-sdk-baseline` 已启用 branch protection：

- 规则对管理员生效；
- 禁止 force-push；
- 禁止删除分支；
- 暂不强制 PR review 或 required status check，因此单人维护和当前堆叠 PR
  不会被锁死；
- `.github/workflows/public-repo-safety.yml` 仍会在 push 和 pull request 时运行
  `secret-boundary`。

如果之后要合并，顺序必须从底向上：

1. 先保证后端审批合同分支已经进入它的目标分支；
2. 再处理 PR #1；
3. PR #1 的提交进入上游后，把 PR #2 的 base 更新到合适的已合并分支；
4. 再处理 PR #2。

在没有确认目标分支策略前，不要强推、不要 `git reset --hard`、不要删除远端分支。

## 4. 已经完成的稳定产品路径

当前已完成并测试的主路径：

```text
iFLYBUDS Pro 3
  → Viaim Android SDK
  → Partial / Final 文本
  → 手机审核提交
  → CEO Gateway
  → 可靠任务生命周期
  → SSE 进度、结果、取消
  → Android 展示
```

可靠性能力包括：

- 稳定 client session ID 和 client turn ID；
- session/turn 幂等创建；
- 后端 allow-list `project_id`，手机不发送服务器路径；
- pending/running/completed/failed/cancelled 生命周期；
- 原子持久化、结果与错误 checkpoint；
- SSE `Last-Event-ID` 回放；
- Android 活跃任务与 cursor 的 SharedPreferences 持久化；
- 应用重启查询恢复，不重复 POST turn；
- 指数退避、抖动和上限；
- R2/R3 服务器所有的预派发审批；
- Android 审批卡、稳定 decision ID、重启恢复；
- 一次性仓库中的 `claude-code` 隔离验证。

自动化基线：

```text
backend: 90 tests
Android: testDebugUnitTest
Android: assembleDebug
Android: lintDebug
public repository safety check
```

统一执行：

```bash
./scripts/test-all.sh
```

## 5. 已通过的真实设备验证

### Huawei Mate 60 + iFLYBUDS Pro 3 + Mac

已完成：

- Viaim SPP、鉴权、PCM、WAV、Partial/Final；
- 手机与 Mac 在可互通个人热点上的原生 LAN Gateway 闭环；
- slow mock 进度和完成；
- 活跃任务期间 force-stop；
- 重启后从持久化 session/turn/cursor 恢复；
- 没有重复提交 turn；
- 取消传到后端并清理 Android 恢复状态；
- 只在 `/private/tmp` 一次性 Git 仓库验证 `claude-code`。

### OnePlus Open（CPH2551，Android 16）

已完成：

- Viaim SDK 初始化，能力包括 `voice-stream`、`text-stream`；
- Core-Telecom 自管理账户注册；
- 通话进入 Telecom `ACTIVE`；
- Android WebRTC `AudioRecord` 以 48 kHz 单声道启动；
- Android 识别为 VOIP 麦克风会话；
- 修复 Android 16 `CallStyle` 通知不能由普通 Activity 直接发布的问题；
- 新增 `EarCeoCallService`，使用 microphone foreground-service type；
- 挂断后 Telecom call、前台服务、通知和音频焦点均释放。

尚未通过：

- OnePlus 到 Mac 的 WebRTC RTP 音频包没有完成验收；
- 当时个人热点隔离两端；
- ADB reverse 只能转发 TCP，不能替代 Android WebRTC 的 UDP ICE 媒体路径；
- 手机和 Mac 虽然都装了 Tailscale，但登录的是不同 tailnet。

不得把“LiveKit 信令连上房间”写成“音频已经上传成功”。验收至少需要服务器看到
一个发布音轨，并证明收到非零 RTP/audio frame。

## 6. 当前通话代码的状态

`codex/audio-stream-uplink` 包含通话基础：

- `android/.../audio/AudioSourcePolicy.kt`
  - 健康 Viaim PCM 优先；
  - disconnect、PCM timeout、注入失败后回退 Android microphone。
- `android/.../audio/Pcm16FrameBuffer.kt`
  - 将 Viaim 16 kHz、mono、PCM16 转换成 WebRTC callback 请求格式。
- `android/.../audio/ViaimAudioBufferCallback.kt`
  - 只在 Viaim 被选中时替换本地麦克风 buffer。
- `android/.../telecom/TelecomCallCoordinator.kt`
  - Core-Telecom self-managed outgoing call。
- `android/.../call/CallSessionCoordinator.kt`
  - 媒体和 Telecom 的启动、挂断、source watchdog。
- `android/.../call/EarCeoCallService.kt`
  - 目前只拥有 foreground notification 身份；
  - 媒体/session 仍由 `MainActivity` 持有，这是待修问题。
- `android/.../rtc/LiveKitCallTransport.kt`
  - 这是开发 spike，不是最终架构。

已知未完成问题：

- Activity 被销毁时会结束 call；media/session 应迁入 service；
- LiveKit/WebRTC runtime disconnect/reconnect 尚未驱动 Telecom 状态；
- Telecom 和媒体库的 audio routing ownership 还需统一；
- incoming call 尚未实现；
- Viaim PCM ring buffer 还可以减少分配；
- 真机 iFLYBUDS + Telecom + WebRTC 并发未验收。

## 7. 已确认的新产品决定：去掉 LiveKit 特定依赖

用户明确不接受“必须部署 LiveKit backend”。最终目标不是 LiveKit Cloud，也不是
LiveKit Server。最终结构应为：

```text
Viaim PCM / Android AudioRecord
          ↓
Android Core-Telecom
  （系统通话状态、系统控制、Bluetooth endpoint）
          ↓
标准 WebRTC PeerConnection
  （ICE + DTLS-SRTP + Opus）
          ↓
用户自己的远端 EarCEO server
```

这里的“无特定 backend”含义：

- 不依赖 LiveKit、Agora、Twilio 等专用平台；
- Android 和远端通过标准 WebRTC 互通；
- 远端 WebRTC 实现可替换。

它不意味着远端完全不需要媒体接收端。远端必须有一个标准 WebRTC peer 来终止
DTLS-SRTP、解码 Opus 并把 PCM frame 交给业务逻辑。不要自己实现 ICE、DTLS、
SRTP 或 Opus 加密协议。

建议 MVP：

- Android：直接使用可靠、维护中的 WebRTC Android PeerConnection 库；
- 信令：复用现有 FastAPI 鉴权，增加很小的 offer/answer API；
- 远端：先在 `backend/` 内使用 `aiortc` 实现标准 peer；
- 媒体：`MediaStreamTrack.recv()` 得到 audio frame，转为 PCM16；
- 网络：远端公开 UDP 端口；必要时配置标准 STUN/TURN；
- 将远端实现隐藏在 EarCEO 自己的接口后，未来可换 Pion/GStreamer。

`aiortc` 是一个实现选择，不应泄漏到 Android 协议。Android 只认标准 SDP/ICE/
WebRTC，后端实现可以替换。

## 8. 下一阶段的具体实现顺序

不要一次性把所有功能写完。按以下阶段，每阶段测试、提交、推送。

### 阶段 A：移除 LiveKit 产品耦合

1. 保留 `CallMediaTransport` 接口、Telecom、foreground service、Viaim source
   policy 和 PCM tests。
2. 新建 provider-neutral `WebRtcCallTransport`。
3. 删除：
   - `LiveKitCallTransport.kt`；
   - `io.livekit:livekit-android`；
   - `LIVEKIT_URL` / `LIVEKIT_TOKEN` BuildConfig；
   - `livekit.url` / `livekit.devToken` local properties。
4. 调研并固定一个受维护的 Android WebRTC artifact；不要复制 LiveKit 的内部
   `livekit.org.webrtc` 类。
5. 调整类名、字符串、README 和 PR #2 描述，避免继续把 LiveKit 作为目标。
6. 跑 `./scripts/test-all.sh`。

### 阶段 B：EarCEO 自己的最小 WebRTC 信令

建议先做 non-trickle ICE MVP，减少状态面：

1. Android 创建 offer；
2. 等待本地 ICE gathering complete；
3. 通过已有 bearer token POST SDP；
4. 后端创建 peer，设置 remote description，返回 answer SDP；
5. Android 设置 answer；
6. 后端保存 `call_id` 和 peer lifecycle；
7. 增加显式 DELETE/hangup；
8. 之后再决定是否需要 trickle ICE/WebSocket。

建议 API 形状（实现前同步更新 `docs/backend-contract.md`）：

```text
POST   /v1/realtime/calls
DELETE /v1/realtime/calls/{call_id}
```

创建请求至少包括：

```json
{
  "client_call_id": "uuid",
  "sdp": "...",
  "type": "offer"
}
```

响应至少包括：

```json
{
  "call_id": "server-id",
  "sdp": "...",
  "type": "answer"
}
```

要求：

- 继续使用现有 API token；
- 限制 SDP 大小和 Content-Type；
- call ID 幂等；
- 服务器设置最大 call duration、idle timeout 和并发上限；
- disconnect、timeout、DELETE 都必须关闭 peer 和 audio consumer；
- 日志不能记录完整 SDP 中可能包含的网络隐私信息，默认只记 call ID 和状态。

### 阶段 C：远端收到可验证 PCM

1. 后端只接收 audio track；
2. 将 frame resample 为约定格式，例如 PCM16/16 kHz/mono；
3. 先接一个测试 sink，不接 ASR；
4. 记录 frame count、byte count、首帧时间和最近帧时间，不记录原始音频；
5. 增加 integration test，证明 offer/answer 后收到非零 audio frame；
6. 只有这一步通过，才算“手机音频上传到自己的远端后端”。

### 阶段 D：生命周期和系统集成加固

1. 把 `CallSessionCoordinator`、PeerConnection 和 Viaim capture ownership
   移入 `EarCeoCallService`；
2. Activity 只绑定/发送用户操作和展示状态；
3. WebRTC failed/disconnected/closed 必须结束或更新 Telecom call；
4. Telecom hold/end/endpoint change 必须传回媒体层；
5. 明确只有 Telecom 控制系统 endpoint routing，媒体层不能与其争夺；
6. 为 end-during-connect、double-end、service restart、permission denial、
   ICE timeout 增加测试。

### 阶段 E：真实设备验收

按顺序：

1. OnePlus system microphone → remote non-zero audio frames；
2. 挂断完整释放；
3. 重复 start/end 两轮；
4. iFLYBUDS 连接后，Viaim PCM 自动升为优先；
5. 拔掉/断开耳机后自动回退 system microphone；
6. Huawei Mate 60 + iFLYBUDS Pro 3 + Mac/remote server；
7. Bluetooth endpoint、系统通话控制、后台/回前台；
8. 网络断开和恢复。

## 9. 暂时不要做

除非用户重新明确扩大范围，否则不要做：

- HFP 自定义控制；
- PCM/WAV 通过旧 REST 上传；
- 后端 ASR；
- Android TTS；
- 正式公网部署；
- 正式生产鉴权；
- incoming call；
- 大幅 UI/Compose 重写；
- 将 `claude-code` 指向真实项目或宽泛目录。

标准 WebRTC 媒体传输本身不等于“PCM/WAV 上传”；它是当前获准的实时音频路径。

## 10. 新电脑必须手工迁移的本地文件

以下内容故意不在 GitHub：

### Viaim AAR

```text
android/app/libs/VisionHeadsetOpen-v1.0.0.aar
```

它受供应商分发限制。可以从旧电脑的官方 Viaim SDK delivery 中手工复制，但不要
提交 Git。

### Android local.properties

从模板重新创建：

```bash
cp android/local.properties.example android/local.properties
```

至少在本地填写这些键：

- `sdk.dir`：新电脑的 Android SDK 绝对路径；
- `viaim.appKey`：本地保存的 Viaim AppKey；
- `viaim.appSecret`：本地保存的 Viaim AppSecret；
- `earceo.backendUrl`：手机可访问的 EarCEO server 地址；
- `earceo.apiToken`：与后端一致的本地 token；
- `earceo.projectId`：默认开发值为 `adventurex-demo`。

LiveKit 配置是待移除的 spike 配置，不要在新电脑创建长期 LiveKit token。

### backend/.env

```bash
cp backend/.env.example backend/.env
```

用新电脑/远端服务器自己的路径与 token 配置。不要复制旧电脑的临时
`/private/tmp` allow-list。

### Python 和 Android 工具链

已验证版本/要求：

- JDK 17；
- Android compile/target SDK 35；
- Android platform-tools；
- Python 项目级虚拟环境；
- `uv`；
- 真机 ADB。

创建后端环境：

```bash
uv venv backend/.venv
uv pip install --python backend/.venv/bin/python \
  -r backend/requirements.txt \
  -r backend/requirements-dev.txt
```

新阶段增加 `aiortc` 时，要把它固定到仓库依赖并进入自动化，而不是只装到全局
Python。

## 11. 安全边界

- `EARCEO_BACKEND` 默认必须是 `mock`；
- `claude-code` 只能显式开启；
- `EARCEO_PROJECTS` 必须只映射到明确 allow-list；
- `claude-code` 真机/集成验证只能在新建的一次性 Git 仓库；
- 手机永远不发送任意 repo path；
- R2/R3 审批由服务器策略决定；
- token、转写全文、音频、SDP/ICE 隐私数据不得进入日志或 Git；
- 公开仓库安全检查失败时不得推送。

## 12. 每个阶段的完成条件

每次提交前：

```bash
git status --short
git diff --check
./scripts/test-all.sh
```

然后：

```bash
git add <明确文件>
git commit -m "<single-purpose message>"
git push
```

文档必须和代码一起更新。不要把“能编译”“连上信令”和“真实音频闭环”混为一谈。

## 13. 给新 Codex 的首条指令模板

```text
继续开发 https://github.com/hlzx-cpu/EarCEO。
先 checkout codex/audio-stream-uplink，并完整阅读 tocodex.md、
docs/next-session-handoff.md、README.md、docs/backend-contract.md、
docs/development-plan.md、docs/android-voip-development.md。

GitHub 默认分支落后，当前工作是堆叠分支；不要 reset、强推或绕过中间里程碑。
LiveKitCallTransport 只是未验收 spike，产品已经决定不依赖 LiveKit backend。
下一阶段先保留 Core-Telecom、EarCeoCallService、Viaim 优先级和 PCM buffer，
将媒体层替换成标准 provider-neutral WebRTC PeerConnection，并在现有 FastAPI
后端增加最小 offer/answer endpoint。远端先只输出可计数的 PCM frame，不做 ASR、
TTS、HFP、正式部署或 UI 大改。

分阶段开发，每阶段运行 ./scripts/test-all.sh、更新文档、提交并推送。真实 RTP
验收必须证明服务器收到一个音轨和非零 audio frames，不能只以信令连接作为通过。
```
