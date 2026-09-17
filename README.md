# ChangeLines

在 IntelliJ IDEA 原生 **Changes 文件树**的每个文件后显示新增、删除行数：

```text
ReleaseServiceImpl.java      +12  -3
ReleaseDAO.xml               +5   -1
```

最低版本：**IntelliJ IDEA 2026.1（build 261）**。使用 Java 21、2026.1 SDK 构建；兼容性检查覆盖 2026.1 和 2026.2.0.1。不设置版本上限不等于已经验证所有未来版本。

## 下载和安装

到 [Releases](https://github.com/SubtleSpark/ChangeLines/releases/latest) 下载 **ChangeLines-0.1.1.zip**。

IDEA → **Settings → Plugins → 齿轮 → Install Plugin from Disk…** → 选择 ZIP → **重启 IDEA**。

不要解压这个 ZIP，也不要选 GitHub 自动生成的 Source code。安装后打开项目，照常使用 Changes 页面，无需配置，也不需要安装 JDK/Gradle。尚未发布到 JetBrains Marketplace。

## 使用范围

- **Changes Between … and …**：分支、标签、两个提交之间的文件比较，是本插件的主要目标。
- 使用原生 `ChangesTree`、以 `Change` 为文件节点的本地 Changes 和 Git Log 变更列表。
- 保留原有图标、勾选框、分组、文件状态颜色、双击 Diff 和快捷操作；不创建独立统计窗口。

不覆盖未跟踪文件节点、Git staging 专用节点、远程开发前端的独立树实现，以及完全替换原生组件的第三方视图。

## 颜色与主题

文件名、字体、背景由原生 renderer 决定。自 0.1.1 起，行数统计不再定义 RGB 或插件专用主题色：`+新增` 复用 IDEA 的 `FileStatus.ADDED`，`-删除` 复用 `FileStatus.DELETED`，每次渲染读取当前 VCS 配色。选中行沿用原生树的对比度规则；状态未指定颜色时继承文字色。

因此不保证每个主题都显示绿色/红色，例如原生删除色为灰色时，删除行数也会是灰色。可通过 IDEA 自带的 **Settings → Version Control → File Status Colors** 调整 Added / Deleted；插件不提供另一套独立配色。

## 统计规则和性能边界

每个节点读取其自身 `Change.beforeRevision` / `afterRevision`，**不使用当前分支的 git diff 代替历史对比**。同名文件的不同对比窗口独立缓存。

修改一行计为 `+1 -1`；新增文件为 `+N -0`；删除文件为 `+0 -N`。空文件为零行，纯重命名显示 `+0 -0`。空白修改计入统计，CRLF/CR 转 LF 后比较，末行是否有换行符会影响统计。采用最短逐行插入/删除序列，复杂重复文本可能与 Git 的启发式 diff 不同。

`binary` 表示二进制，`too large` 表示超出保护限制，`unavailable` 表示读取失败；不会把无法统计的文件显示为零行。

内容读取和比较都在后台进行。每个项目最多 2 个计算线程、256 个排队任务、2,048 条缓存；固定行高时只为可见行发起计算。单侧最多 2,000,000 个字符、100,000 行，另有计算量限制。VCS 提供者加载完历史内容后才能检查字符数，因此这不是读取阶段的严格内存上限。

本地文档编辑、文件系统变化会使工作区统计失效，历史 revision 结果不随打字反复失效。项目关闭时取消任务。无遥测、不上传代码、不执行修改 Git 仓库的命令。

## 开发和测试

开发需要 JDK 21 和 Gradle 9.0.0；最终用户不需要这些工具。CI 固定使用该 Gradle 版本，仓库暂不包含 Gradle Wrapper。

```bash
gradle test buildPlugin     # 编译、测试，生成 build/distributions/ChangeLines-0.1.1.zip
gradle verifyPlugin         # IDEA 2026.1 / 2026.2.0.1 二进制兼容性检查
gradle runIde               # 启动安装了插件的沙箱 IDEA
python3 scripts/check_distribution.py build/distributions
```

测试包含统计边界场景、**5,000 组随机数据与独立 LCS 算法交叉校验**，以及运行在 IDEA 2026.1 平台上的服务加载、原生 ChangesTree 渲染、后台读取、跨对比缓存隔离、取消重试、二进制、读取失败和大文件保护测试。颜色回归测试覆盖原生 VCS 配色、同一个 renderer 在配色修改后更新颜色，以及聚焦/未聚焦的选中行规则。

平台集成测试不等同于各操作系统上的人工桌面验收。人工检查重点：历史版本对比不受当前工作区影响；滚动和刷新后数字不裁剪；本地未保存编辑更新；分组、勾选、双击 Diff 和明暗主题正常。

## 发布流程

普通 push / PR 运行测试、兼容性检查、安装包检查；成功的 ZIP 可在 Actions 的 **ChangeLines-plugin** artifact 中获取。

发布新版本时先更新 `build.gradle.kts` 的 version、README 文件名和 `RELEASE_NOTES.md`，再向 main 提交带 **[release]** 标记的 commit；也可在 Actions 手动运行工作流并勾选 publish。

只有全部检查成功后才运行发布任务：从同一次构建下载 ZIP、复查元数据、生成 SHA-256、创建指向当前提交的 tag 和 GitHub Release。既有 release 不会被覆盖。构建任务只需要 contents: read，发布任务仅使用仓库自带 GITHUB_TOKEN 的 contents: write，无需保存个人访问令牌。仓库/组织策略若禁止此权限，发布任务会明确失败。

## 实现说明

2026.1 的 `ChangeNodeDecorator` 是创建节点时传入的对象，不是覆盖全部比较树的统一扩展点。本插件通过 AWT 层级事件发现原生 `ChangesTree`，包装原来的 Swing `TreeCellRenderer`，在原组件中追加统计；不反射访问私有字段，也不替换数据模型。UI 集成仍需随 IDEA 更新回归验证。

- [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
- [插件依赖](https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html)
- [2026.1 ChangesTree](https://github.com/JetBrains/intellij-community/blob/261/platform/vcs-impl/shared/src/com/intellij/openapi/vcs/changes/ui/ChangesTree.java)
