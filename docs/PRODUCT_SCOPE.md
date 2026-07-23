# 产品范围

## 第一版要具备

- 上传文件夹或 ZIP 并形成不可变 ProjectVersion。
- 保存 manifest、文件 hash 和版本历史。
- `DIRECT` 普通回答。
- 持久化 Plan-and-Execute。
- TaskFrame、Plan revision、checkpoint、暂停、取消和恢复。
- Plan Step 内自主工具循环。
- 隔离 Workspace 内完整增删改。
- 受控 shell、构建、测试、依赖安装和网络。
- 根据真实 stdout/stderr 进行有界修复和重试。
- 自动保存事件、命令、网络、receipt 和 Workspace diff。
- 用户审查、接受、拒绝、回滚和导出。
- 刷新或 API 重启后不重复用户消息、最终回答和副作用。

## 第一版不具备

- 无人监督运行数天的自主科研循环。
- 默认访问宿主机任意文件、生产数据库或生产服务。
- 默认获得本机全部密钥。
- 未经用户确认直接覆盖原 Project 或合并 GitHub `main`。
- 产品内部自由多 Agent 群体协作。
- 支持所有语言、构建系统、云平台和科研工具。
- 自动保证所有科研推断和实验结论正确。
- 保证所有任务一定成功。
- 自动兼容或恢复 V1 Plan、Candidate 和 checkpoint 数据。

## 默认权限档位

第一版默认 `SANDBOX_STANDARD`：

- 隔离 Workspace 内完整读写。
- 非特权命令执行。
- 允许受信依赖源和必要官方文档联网。
- 不提供宿主机文件、生产凭据或无关 Project 数据。

后续可以增加 `READ_ONLY`、`SANDBOX_EXTENDED` 和本地可信 `FULL_ACCESS`。
