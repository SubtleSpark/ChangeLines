# ChangeLines

ChangeLines 是一个面向个人使用的 IntelliJ IDEA 代码 Review 辅助插件。

从 **0.3.0** 开始，插件不再修改 IDEA 的 Changes Tree renderer。核心入口改为 **Project View + IntelliJ Action System**：直接在项目树里看到本地变更的行数和审阅状态，用右键、工具栏或快捷键连续 Review。

最低版本：**IntelliJ IDEA 2026.1（build 261）**。

## 0.3.0 做了什么

当前 Review Scope 是 **Local Changes + Unversioned Files**。

Project View 中的变更文件会显示：

```text
ReleaseDAO.java              +36 -39  ○
ReleaseInstructionDAO.java   +24 -14  ✓
ReleaseServiceImpl.java      +361 -20 !
binary.dat                            ⊘
```

状态：

- `○` 未审阅
- `✓` 已审阅
- `!` 已审阅后内容发生变化，需要重审
- `⊘` 不可审阅，例如 binary、删除文件或无法安全读取内容

`+新增/-删除` 保留原先 ChangeLines 的红绿配色。

## Review 操作

Project View 右键 **ChangeLines 审阅**：

- 标记已审阅
- 取消已审阅
- 标记并打开下一个未审阅文件
- 打开下一个未审阅文件
- 全部取消审阅

Project View 顶部还会显示：

```text
审阅 7 / 40    下一个未审阅    重置审阅
```

其中 denominator 只面向可审阅/仍在计算的文件；不可审阅文件作为 skipped，不会让进度永远无法完成。鼠标悬停进度项可看到 changed / reviewable / needs-review / skipped 的明细。

所有 Action 都可在 **Settings → Keymap** 搜索 `ChangeLines` 后绑定快捷键。默认不抢占 IDEA 现有快捷键。

### 连续 Review

推荐把 **标记并打开下一个未审阅文件** 绑定到一个顺手的快捷键：

```text
看 Diff
  ↓
确认无问题
  ↓
Mark Reviewed & Next
  ↓
自动打开下一个
```

对于普通 Local Change，插件调用 IDEA 原生 Diff；Unversioned 文件没有 before revision，因此直接打开文件编辑器。

## 审阅状态为什么可信

审阅状态不是 `path -> reviewed=true`。

每次标记时保存的是：

```text
VCS root
+ 当前路径
+ before revision
+ before content
+ after content
        ↓
      SHA-256
```

如果已审阅文件继续被编辑，插件会立即显示 `!`，后台重新计算后再确认状态。切分支、baseline 变化或文件内容变化都会让旧 fingerprint 不再匹配，不会错误保留 Reviewed。

状态保存在当前项目的 IDEA workspace state 中，不写入源码，不需要团队共享，也不会主动执行 Git 命令。

## Unversioned / Binary / Deleted

- Unversioned 文件会合并到 Review Scope；若 ChangeListManager 返回目录，会递归收集其中未被 ignore 的文件。
- Binary、无法读取或超过保护大小的文件标记为 `⊘` 并跳过。
- Deleted file 没有 Project View 文件节点，因此当前版本将其计为 skipped，不尝试 hack Project Tree 来伪造节点。

## 为什么不做“行尾独立可点击按钮”

JetBrains 对 Project View 提供稳定的 `ProjectViewNodeDecorator`，适合显示附加文本/状态；但没有稳定公开的“给某一行尾部增加独立 click target”扩展点。

0.3.0 因此只使用：

- `ProjectViewNodeDecorator`
- Action System
- VCS `ChangeListManager`
- VFS / Document listeners
- `PersistentStateComponent`

不再通过 AWT 层级监听包装 Project Tree / Changes Tree renderer，也不注入自定义鼠标命中逻辑。

## 与 0.2.x 的区别

0.2.x 主要增强 **Changes Between / Changes Tree**。0.3.0 是一次主动重构，目标是更稳定的 IDEA 2026+ API 边界，因此删除了旧的 Changes Tree renderer 和 Diff UI 注入实现。

如果你的主要场景仍然是“任意两个历史 revision 的 Changes Between”，0.2.x Release 仍可安装；0.3.0 当前主攻 **本地尚未提交修改的连续 Review**。

## 性能

- VCS/VFS 事件触发后台刷新，不轮询。
- 2 个后台线程计算内容 fingerprint 和行数。
- Project View decorator 和 Action `update()` 只读取缓存，不做 diff / 文件 IO。
- 单侧文本保护上限 2,000,000 字符；Unversioned 在加载前还有 4 MB 快速保护。
- Myers 行级 diff 仍有工作量上限；即使复杂 diff 超限，只要 fingerprint 已生成，文件仍可以审阅，只是不显示 `+/-`。

## 安装

到 [Releases](https://github.com/SubtleSpark/ChangeLines/releases/latest) 下载最新版 ZIP。

IDEA：

**Settings → Plugins → 齿轮 → Install Plugin from Disk… → 选择 ZIP → 重启**

不要解压 ZIP，也不要下载 GitHub 自动生成的 Source code。

## 开发

需要 JDK 21、Gradle 9.0.0。

```bash
gradle test buildPlugin
gradle verifyPlugin
gradle runIde
python3 scripts/check_distribution.py build/distributions
```

CI 对 IDEA 2026.1 和 2026.2.0.1 做 Plugin Verifier 检查，并验证最终 ZIP 结构。

## 当前明确不做

- 原生 Project View 行尾独立可点击 icon
- 伪造 deleted file 的 Project View 节点
- 评论、审批、团队同步
- Split Mode / Remote Development 专门架构
- 为旧 IDEA 保留兼容层

这是个人工具项目，优先保持实现简单、状态可信和升级成本低。
