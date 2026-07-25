# 从这里开始

## 为什么建立 V2

V1 已经具备 Project 上传、版本、Candidate、沙箱、持久化 Plan 和结果展示，但通用 Agent 核心逐渐形成以下问题：

- 后端规定过细的工具调用和 Plan 形状。
- Router、Planner、Evidence、Candidate、Sandbox 和 Final Synthesis 高度耦合。
- 中间 Evidence 格式可能提前终止本可恢复的任务。
- Agent 在隔离环境中的读写、执行、依赖和联网能力不足。
- 大量逻辑集中在少数超大 Service，导致修改和并行开发困难。
- 自动测试偏向固定内部路径，真实模型换一种表达就可能暴露新断层。

V2 不继续修补旧 Runtime。它重新实现 Agent 核心，同时选择性迁移经过审查的成熟外围能力。

## 已冻结的方向

1. 顶层只有 `DIRECT` 和 `PERSISTENT_PLAN_EXECUTE`。
2. 需要 Project、工具或副作用的请求统一进入持久化 Plan。
3. TaskFrame 固定“完成什么”，Plan 和 Step 决定“怎样完成”。
4. Step 内允许模型自主读写、执行、测试、安装依赖和有界修复。
5. Plan 支持 checkpoint、暂停、取消、恢复和 bounded replan。
6. Agent 在隔离 Workspace 内拥有完整修改能力。
7. 原 ProjectVersion 保持不可变；用户接受 diff 后才生成新版本。
8. 网络和密钥通过 ExecutionProfile 分级提供。
9. 命令、文件、网络、stdout、stderr、diff 和 receipt 自动进入事件日志。
10. 不再要求后端预先预测模型每一次工具调用。

## 当前阶段

`Wave 0 / Bootstrap`、`Wave 1 / Runtime Contracts` 和 `Wave 2 / 独立基础能力` 已完成。
`Wave 3 / Runtime 主链` 正在按依赖顺序实现，已经不再是纯规划状态。

已完成并合并的 Wave 3 基础切片包括二元 Router、TaskFrame/Plan/Checkpoint 冻结、原子
Persistence bootstrap、execution start/recovery、Workspace 物化与校验、Plan execution
context 及其 Runtime composition。当前安全停止点、精确提交、开放 Issue/PR、测试证据和下一
切片见 [当前开发状态](ACTIVE_DEVELOPMENT.md)。

仍未完成的关键产品能力：

- Runtime Step activation composition、effect/receipt/progress/completion/replan/recovery 主链。
- 单轮 Step kernel、bounded Step Agent Loop 和 bounded repair/replan。
- 真实模型或执行后端、耐久数据库适配器和生产级隔离。
- 产品 API、Web UI、用户接受/拒绝与新 ProjectVersion 闭环。
- 真实登录到结果的自动化用户旅程。
- V1 选择性迁移；V1 仍按 `MIGRATION_MAP.md` 默认 `UNASSESSED`，禁止直接复制。

当前内存 Persistence 不承诺进程重启耐久性；确定性 Sandbox 不等同于真实进程、网络、资源
或密钥隔离。数据库 schema 和线协议也尚未冻结。

## 下一步

1. 读取 [当前开发状态](ACTIVE_DEVELOPMENT.md)，再用 Git/GitHub 核对其中记录的 SHA、
   Issue、Draft PR 和 CI；外部状态可能在文档写入后变化，不得猜测。
2. 先完成当前 Draft PR 的 fixed-head 静态审查、自动化测试和 CI 门禁。只有主对话可以决定
   Ready、合并和合并顺序。
3. 合并后以 GitHub merge commit 作为下一 Issue 的唯一 `baseCommit`，发布
   Runtime Step activation composition Issue。
4. 每个功能继续使用独立 Issue、worktree、`codex/` 分支和 Draft PR；不得越过
   `ACTIVE_DEVELOPMENT.md` 中的依赖顺序并行实现下游能力。

## 新主对话建议指令

> 完整读取 `AGENTS.md`、`docs/START_HERE.md`、`docs/ARCHITECTURE.md`、
> `docs/MIGRATION_MAP.md`、`docs/DEVELOPMENT_PROCESS.md`、`docs/ROADMAP.md` 和
> `docs/ACTIVE_DEVELOPMENT.md`。你是 PaperAgent V2 主对话，负责架构冻结、Issue、PR
> 审查、合并顺序和阶段验收，不直接承担普通功能实现。先核对 Git/GitHub 当前状态，再从
> `ACTIVE_DEVELOPMENT.md` 的停止点继续；不要复制 V1 代码或读取 `.env`。
