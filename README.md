# ChangeLines

在 IntelliJ IDEA 原生 **Changes 文件树**中显示逐文件新增/删除行数，并保存 review 进度：

```text
已审阅 1 / 3                          审阅
ReleaseServiceImpl.java     +12  -3   已审阅
ReleaseDAO.xml              +5   -1
ReleaseFacadeImpl.java      +8   -2   需重审
```

最低版本 **IntelliJ IDEA 2026.1（261）**；Java 21、2026.1 SDK 构建，二进制兼容性检查覆盖 2026.1 和 2026.2.0.1。没有设置版本上限，不代表已经验证所有未来版本。

## 下载和安装

到 [Releases](https://github.com/SubtleSpark/ChangeLines/releases/latest) 下载 **ChangeLines-0.2.0.zip**。

IDEA → **Settings → Plugins → 齿轮 → Install Plugin from Disk…** → 选择 ZIP → **重启 IDEA**。

不要解压 ZIP，也不要选择 GitHub 自动生成的 Source code。已安装旧版时直接安装此包更新，无需另装 JDK 或 Gradle。尚未发布到 JetBrains Marketplace。

## Review 用法

在原来的 Changes 页面选择文件，右键 **ChangeLines 审阅**，或者点击列表顶部的 **审阅**：

- **标记已审阅 / 取消已审阅**：支持多选文件，不自动包含只选中目录时的所有子文件。
- **标记并打开下一个未审阅文件**：针对单个文件，标记后跳过已审阅项，展开目标目录并复用 IDEA 的原生 Diff 打开行为。
- **打开下一个未审阅文件**：仅导航，不改变标记。到列表末尾时回到开头查找。

树上只给已审阅、需重审或待核对的文件增加文字，不把整行变灰，也不把“打开过”当作“审阅完成”。顶部显示 **已审阅 N / M**，重新加载时待核对项不计入 N。

能通过实际 `Change` 对象明确关联到当前树的原生 Diff，会在工具栏增加“标记已审阅”和“标记并打开下一个”的按钮。Diff 按钮绑定的是显示中的文件，不能误标其他树当前选中的文件；来源关闭、模型换代或来源有歧义时禁用/不提供按钮，此时在树中操作即可。保留 IDEA 原生的预览/独立窗口行为。

**Settings → Keymap** 搜索 `ChangeLines`，可为树上的四个动作设置快捷键；默认不占用快捷键。标记只记录阅读进度，不代表审批通过。

## 审阅记录怎样保存

使用 IDEA 的 `PersistentStateComponent` 保存到 **IDEA 配置目录**的 `options/ChangeLinesReviews.xml`，不是 `.idea` 文件，不修改 Git 仓库，也不参与 Settings Sync。只保存标识与内容哈希，不保存源代码。

记录绑定 **比较上下文 + 左右文件路径 + 左右内容 SHA-256**，不是仅按文件名或 `+N -N` 记忆。

主要目标 **Changes Between…** 使用项目路径和完整原生对比标签标题作为上下文：同一组分支再次比较、重开窗口或重启 IDEA 后，可恢复进度；分支提交更新但某文件两侧内容完全没变时继续保留标记，任一侧变化就显示 **需重审**。重新标记前会核对当前内容；多选中有任一文件无法核对时不部分写入标记。

普通 Git Log 复用树但切换提交，因此另外使用实际 revision 组合隔离记录。普通本地 Changes 也单独隔离；基线 revision 改变时可能新建记录，不保证跨提交保留。无法确定身份的第三方比较只使用窗口级临时记录，不猜测归属。完整对比标题变化（包括界面语言变化）会被视为新上下文。内容回到已确认的完全相同版本时，该版本仍可视为已审阅。

**首版边界**：二进制、无法读取、超过单侧内容大小限制的文件不能标记，但仍计入总文件数；此时进度不一定能达到 M/M。没有评论、审批、团队共享或云同步功能。

## 支持范围与颜色

主要支持 **Changes Between…** 分支/标签/提交对比，及使用原生 `ChangesTree`、以 `Change` 为文件节点的本地 Changes 和 Git Log 文件列表。保留图标、勾选框、分组、文件状态色、双击 Diff 和快捷操作，不新增工具窗口。

不覆盖未跟踪文件节点、Git staging 专用节点、远程开发前端的独立树，以及完全替换原生组件的第三方视图。

行数沿用 0.1.0 / 0.1.2 的 **新增绿色、删除红色** 与明暗主题适配，不增加配色模式。文件名、字体、图标、背景和选中样式由原生 renderer 决定。审阅状态使用 IDEA 原生的辅助文字样式。

## 统计与性能边界

每个节点读取自身 `Change.beforeRevision` / `afterRevision`，不用当前分支的 git diff 代替历史对比。同名文件不同对比独立计算。

修改一行计为 `+1 -1`；新增 `+N -0`，删除 `+0 -N`；空文件为零行，纯重命名 `+0 -0`。空白修改计入统计，CRLF/CR 转 LF 后比较，末行是否有换行符会影响统计。最短插入/删除序列可能与 Git 对复杂重复文本的启发式结果不同。审阅指纹则使用未归一化的完整两侧内容，保守识别变化。

`binary`、`too large`、`unavailable` 分别表示二进制、超限、读取失败，不伪装成零行。

读取、统计、指纹在后台完成。每项目 2 个计算线程、256 个排队任务、2,048 条统计缓存。可见行、选中的文件，以及恢复进度时曾标记的文件按需计算；未审阅且不可见的文件不为统计进度而全量读取。每个活动比较还保存微小的不可变结果，防止大比较反复淘汰指纹；关闭树时释放。

单侧最多 2,000,000 字符、100,000 行，并限制比较工作量。只有比较工作量超限但完整指纹已生成时，仍可标记。VCS 历史内容加载后才能检查长度，读取阶段不是严格内存上限。本地编辑/VFS 变化会使工作区统计失效；关闭项目取消后台任务。无遥测，不上传代码。

## 开发、验证与发布

开发需要 JDK 21、Gradle 9.0.0；CI 固定版本，暂未包含 Gradle Wrapper。

```bash
gradle test buildPlugin
gradle verifyPlugin
gradle runIde
python3 scripts/check_distribution.py build/distributions
```

测试覆盖 5,000 组随机 LCS 交叉校验与统计边界、IDEA 服务/原生树、配色、XML 持久化、左右内容指纹、跨比较隔离、多选、内容变化、导航、Diff 绑定及清理。自动化平台测试不等同于每个操作系统上的人工界面验收。

普通 push / PR 执行测试、二进制兼容性检查和 ZIP 检查。更新 version、本文文件名及 `RELEASE_NOTES.md` 后，向 main 提交带 **[release]** 的 commit，或手动运行工作流勾选 publish。全部检查成功后从同一次构建获取 ZIP，生成 SHA-256 并发布 GitHub Release，不覆盖既有 release。构建使用 contents: read，发布仅使用仓库 GITHUB_TOKEN 的 contents: write。

实现使用 AWT 层级事件发现原生树、包装 renderer，并利用公开的菜单通知和 Diff 扩展 API；不反射访问私有字段，不替换树模型。UI 集成需要随 IDEA 更新进行回归。

参考：[平台插件 SDK](https://plugins.jetbrains.com/docs/intellij/)、[状态持久化](https://plugins.jetbrains.com/docs/intellij/persisting-state-of-components.html)、[2026.1 ChangesTree](https://github.com/JetBrains/intellij-community/blob/261/platform/vcs-impl/shared/src/com/intellij/openapi/vcs/changes/ui/ChangesTree.java)。
