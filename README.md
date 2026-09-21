# ChangeLines

在 IntelliJ IDEA 原生 **Changes 文件树**中显示每个文件的 `+新增 / -删除` 行数，并记录哪些差异已经审阅。

**主要入口就是 `Changes Between…` 分支／标签／版本对比窗口，不是 Project View。工作区没有任何未提交修改时也应生效。**

最低 **IntelliJ IDEA 2026.1（261）**。CI 使用 2026.1 编译，验证 2026.1 和 2026.2.0.1；没有版本上限不代表已经验证全部未来版本。

## 安装

到 [Releases](https://github.com/SubtleSpark/ChangeLines/releases/latest) 下载 **ChangeLines-0.3.1.zip**。

IDEA → Settings → Plugins → 齿轮 → Install Plugin from Disk… → 选择 ZIP → 重启。

不要解压 ZIP，不要下载 Source code，不需要自己装 JDK 或 Gradle。旧版直接覆盖更新。

## 0.3.0 回退问题与 0.3.1 修复

0.3.0 错把入口迁成了 Project View + Local Changes，同时删除了 Changes Tree 集成。插件因此能够正常安装并通过基础加载测试，但不会对 `Changes Between…` 产生任何效果。这是产品场景回退，不是用户配置问题。

0.3.1 撤回这次入口迁移，恢复真实比较树、左右 revision 统计和比较级审阅状态；保留 2026+ 的 API 验证门禁。新回归测试明确构造 `VcsChanges` 原生窗口、native ChangesBrowser、没有本地修改的历史比较，验证自动发现、行数、标记、删除文件和原生工具栏。

## 使用

打开原来的 `Changes Between…` 窗口即可看到：

```text
ReleaseServiceImpl.java    +12  -3  已审阅
ReleaseDAO.xml             +5   -1
ReleaseFacadeImpl.java     +8   -2  需重审
```

在文件列表上方的**原生工具栏**中直接操作：

- **标记已审阅／取消已审阅**：选中文件后点击，支持多选。
- **审阅并下一个**：确认当前单个文件，并打开下一个未审阅文件。
- **下一个未审阅**：只导航，不标记；扫描折叠目录中的文件，末尾循环到开头。
- **审阅**菜单：标记、取消、重置当前比较。重置需确认，不影响其他比较。

空间不足时按钮进入 IDEA 原生工具栏溢出菜单。对不使用 ChangesBrowser 标准工具栏的 ChangesTree，插件在列表上方放置同样的原生 ActionToolbar。没有自绘圆角、Swing JMenu/JPopupMenu 或额外设置项。

能明确关联到该比较树的原生 Diff 中也有标记／审阅并下一个按钮。它们绑定 Diff 实际显示的 `Change`，不会误标别的窗口选中的同名文件。来源关闭、模型替换或归属不明确时禁用。

Keymap 搜索 `ChangeLines` 可绑定快捷键。普通 Local Changes 的标准右键菜单也保留审阅动作；动态创建的 Changes Between 右键菜单不再注入手写子菜单，使用工具栏即可。**本版没有实现行尾独立可点击按钮。**

## 进度、保存与失效

进度示例：`已审阅 7 / 40 · 跳过 2`。已知二进制、无法读取或内容过大的文件不计入可审阅分母；仍在计算的文件会显示核对中，不冒充已审阅。删除文本文件通过 before revision 审阅，不因为没有磁盘文件而跳过。

标记记录的是 **项目／比较上下文 + 左右路径 + 左右内容 SHA-256**，不是当前文件名或单个布尔值。对同一组分支重新比较，内容不变的文件保留标记，任一侧内容改变显示“需重审”。标记前必须拿到完整有效的两侧内容指纹，本地编辑后旧缓存不能被再次确认。打开文件本身不自动标记。

使用本机 IDEA 配置目录的 `options/ChangeLinesReviews.xml`，不写入项目，不参加 Settings Sync，不保存源代码。0.2.x 中相同比较的有效记录可以继续读取；0.3.0 的 workspace 本地修改标记不转换为历史比较标记，以免错误串用。

`Changes Between…` 使用项目路径和完整原生比较标签标题区分比较；标题或界面语言改变会产生新上下文。普通 Git Log 额外使用实际 revision 组合隔离；无法确定身份的第三方树只用窗口级临时记录。不要将此进度当作团队审批。

## 范围与性能

直接读取树里每个 `Change.beforeRevision / afterRevision`，不拿当前分支的 Local Changes 或 `git diff` 代替历史比较。支持新增、修改、删除、重命名。保留文件状态色、原生图标、复选框、分组和双击 Diff 行为；红绿行数仍沿用 0.1.2／0.2.0 的配色。

每项目 2 个后台计算线程，最多 256 个排队任务、2,048 条共享统计缓存；比较窗口为已完成的不可变 revision 保留小型结果。读取、hash 和 diff 不在 EDT 或 Action.update() 中执行，界面更新合并处理。关闭项目取消后台任务并移除集成。

单侧文本限制 2,000,000 字符，行级 diff 有行数和工作量保护。若只是 diff 工作量超限而完整指纹可用，仍允许审阅；内容大小超限、读取失败或二进制不允许标记。历史内容加载后才能检查长度，因此读取阶段不是严格内存上限。

尚不覆盖未跟踪文件专用节点、Git staging 专用节点、远程开发前端和完全自绘的第三方树。0.3.1 不再提供 0.3.0 那个 Project View-only 工作流。

## 构建与验收

开发：JDK 21、Gradle 9.0.0。

```bash
gradle test buildPlugin
gradle verifyPlugin
python3 scripts/check_distribution.py build/distributions
gradle runIde
```

CI 验证：统计随机交叉检查、原生 IDEA 平台和颜色测试、状态持久化、比较隔离、原生 Changes Between 场景、Diff 绑定、缓存失效、导航和安装包内容。Plugin Verifier 对兼容性、弃用、Internal、计划移除 API 和缺失依赖报错。自动化平台测试不等于 macOS 实机人工验收。

发布：main 的提交信息包含 `[release]`，或手动执行工作流并勾选 publish。全部检查通过后发布**同次构建**的 ZIP 和 SHA256SUMS，不覆盖已有 Release。

技术边界：比较文件树没有与 ProjectViewNodeDecorator 等价的通用注册点。这里只发现并包装原生 ChangesTree 的 renderer，不反射私有字段，不替换树模型；菜单和工具栏走 Action System，标准 ChangesBrowser 使用其公开 addToolbarAction()。这层 UI 适配仍需随 IDEA 更新回归，不能仅凭 Plugin Verifier 宣称永久兼容。
