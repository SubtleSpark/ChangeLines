## 安装

下载本页 Assets 中的 **ChangeLines-0.1.0.zip**，不要解压，也不要下载 Source code。

IntelliJ IDEA → Settings → Plugins → 齿轮 → Install Plugin from Disk… → 选择 ZIP → 重启 IDEA。

最低版本：**IntelliJ IDEA 2026.1（build 261）**。无需另外安装 Java 或 Gradle。

## 功能

在原生 **Changes Between … and …** 的每个文件后追加 `+新增行数 -删除行数`。使用同类原生 ChangesTree 的本地变更和 Git Log 文件列表也会显示统计。不创建新的工具窗口，不修改 Git 仓库。

比较的是各文件节点自身的两个 revision，不会用当前工作区内容替代历史分支对比。统计在后台完成，包含缓存、并发和大文件保护；保留原有图标、勾选框和 Diff 操作。

## 验证与限制

Release 工作流只在编译、统计测试、IDEA 2026.1 平台集成测试、2026.1 / 2026.2.0.1 二进制兼容性检查和安装包结构检查全部通过后发布；附件是同一次构建中被验证的 ZIP，附 SHA256SUMS。

集成测试使用真实 IDEA 平台和原生树组件，但不等同于各操作系统上的人工桌面验收。未覆盖未跟踪文件节点、Git staging 专用视图或远程开发前端的独立树实现。二进制或无法读取的文件显示状态，而不是错误地显示零行。
