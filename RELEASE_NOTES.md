## 0.3.0：Project View Review 重构

这版按照调研结果重写 Review 主链路，删除 0.2.x 中对 Changes Tree renderer、AWT hierarchy 和 Diff UI 的注入。

### 新入口

- Project View 直接显示本地变更的 `+新增/-删除`。
- 四态审阅：`○ 未审阅 / ✓ 已审阅 / ! 需重审 / ⊘ 不可审阅`。
- Local Changes 与 Unversioned Files 合并为 Review Scope。
- 已审阅文件再次变化会立即进入“需重审”，后台 fingerprint 会再次核实。
- Project View 右键提供 Mark / Unmark / Mark & Next / Next / Reset。
- Project View 顶部显示审阅进度，并提供 Next / Reset。
- 普通 Local Change 使用 IDEA 原生 Diff，Unversioned 直接打开编辑器。

### 状态保存

审阅记录保存在项目 workspace state。记录的是 VCS root、路径、before revision 与左右内容共同生成的 SHA-256 fingerprint，不是简单的 path 布尔值。

切分支、baseline 或内容发生变化时，旧 Reviewed 不会错误沿用。

### 边界

- Binary、读取失败和超出保护大小的文件作为 skipped。
- Deleted file 因没有 Project View 节点也作为 skipped。
- 不实现原生 Project View 行尾独立 clickable button；该能力没有稳定公开扩展点。
- 0.3.0 主攻 Local Changes Review；任意历史 revision 的 Changes Between 仍可使用 0.2.x。

最低 IntelliJ IDEA 2026.1（261）。
