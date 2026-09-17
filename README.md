# ChangeLines

在 IntelliJ IDEA 原生 **Changes 文件树**的每个文件后面显示新增、删除行数：

```text
ReleaseServiceImpl.java      +12  -3
ReleaseDAO.xml               +5   -1
```

最低目标版本：**IntelliJ IDEA 2026.1（build 261）**。使用 Java 21 编译，并以 2026.1 SDK 为基线；不设置兼容版本上限，不代表已经验证所有未来版本。

## 使用范围

- **Changes Between ... and ...**：分支、标签、两个提交之间的文件比较，这是本插件的主要目标。
- 使用原生 `ChangesTree`、以 `Change` 为文件节点的本地 Changes、Git Log 变更列表。
- 保留原有图标、勾选框、分组、文件状态颜色、双击 Diff 和快捷操作；不创建独立的统计窗口。

不覆盖未跟踪文件节点、Git staging 专用节点、远程开发前端的独立树实现，以及完全替换原生组件的第三方视图。

## 安装

1. 打开仓库的 **Actions → Build and verify**，选择成功的构建。
2. 下载 **ChangeLines-plugin** artifact，解压外层下载包，得到 `ChangeLines-0.1.0.zip`。
3. IDEA：**Settings → Plugins → 齿轮 → Install Plugin from Disk…**，选择这个插件 ZIP。
4. 按 IDEA 提示重启，打开项目后使用原来的 Changes 页面，无需配置。

尚未发布到 JetBrains Marketplace。以 Actions 的实际执行结果为准；源码提交本身不代表构建、兼容性验证或真实 IDE 界面验收已通过。

## 统计规则

每个节点读取其自身 `Change.beforeRevision` / `afterRevision`，**不使用当前分支的 `git diff` 代替历史对比**。不同对比窗口里的同名文件不会共用错误的结果。

- 修改一行计为 `+1 -1`；新增文件为 `+N -0`，删除文件为 `+0 -N`。
- 空文件是 0 行；重命名但内容不变显示 `+0 -0`。
- 空白修改计入统计；CRLF/CR 转 LF 后比较；末行是否有换行符会影响统计。
- 使用最短逐行插入/删除序列。复杂的重复文本可能与 Git 的启发式 diff 输出不同。
- `binary`：二进制；`too large`：超出保护限制；`unavailable`：内容加载失败。不会把无法统计的文件伪装成 0 行。

内容读取与比较在后台进行。每个项目最多 2 个计算线程、256 个排队任务、2,048 条缓存；固定行高时只为可见行发起计算。单侧最多 2,000,000 个字符、100,000 行，另有计算量限制。历史内容由 VCS 提供者加载后才能检查字符数，因此该限制不是 VCS 内容加载阶段的严格内存上限。

本地文档编辑、文件系统变化会使工作区统计失效；历史 revision 结果不随打字反复失效。关闭项目会取消任务；插件卸载时恢复原 renderer 并移除事件监听。无遥测，不上传代码，不执行修改 Git 仓库的命令。

## 开发和验证

需要 JDK 21、Gradle 9.0.0 或兼容版本。仓库未包含 Gradle Wrapper，CI 使用固定版本的 Gradle。

```bash
gradle test buildPlugin       # 编译、自动测试、生成安装 ZIP
gradle verifyPlugin           # 对 IDEA 2026.1 做二进制兼容性检查
gradle runIde                # 启动安装了插件的沙箱 IDEA
```

只运行无依赖的统计测试：

```bash
mkdir -p build/offline-tests
javac --release 21 -d build/offline-tests \
  src/main/java/dev/subtlespark/changelines/LineDiff.java \
  src/test/java/dev/subtlespark/changelines/LineDiffSelfTest.java
java -cp build/offline-tests dev.subtlespark.changelines.LineDiffSelfTest
```

统计测试覆盖新增、删除、修改、空行、空文件、中文、重复行、换行符、无末尾换行、大量公共前后缀、大小限制、取消，以及 **5,000 组随机数据与独立 LCS 算法交叉校验**。它们不替代 IDEA 内的交互验收。

真实 IDE 验收清单：

- 在 2026.1 打开两个历史 revision 的 Changes 对比，同时让当前工作区包含不同修改，确认统计仍来自被比较的版本。
- 同时打开同名文件的两个不同版本对比，确认数字不串窗口。
- 检查新增、删除、重命名、二进制、空文件；检查本地未保存编辑后数字更新。
- 检查分组、滚动、选择、勾选、双击 Diff、明暗主题；确认数字出现后没有被行宽裁剪。
- 打开多个项目，然后关闭项目、禁用插件，检查任务退出和原始界面恢复。

## 实现说明

2026.1 的 `ChangeNodeDecorator` 是创建节点时传入的对象，不是覆盖所有比较树的统一扩展点。本插件通过 AWT 层级事件发现原生 `ChangesTree`，包装它现有的 Swing `TreeCellRenderer`，在原组件后追加统计。不使用反射访问私有字段，也不替换树的数据模型。此类 UI 集成仍需随 IDEA 更新进行回归验证。

参考：

- [JetBrains 平台版本与 Java 要求](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html)
- [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
- [2026.1 ChangesTree 源码](https://github.com/JetBrains/intellij-community/blob/261/platform/vcs-impl/shared/src/com/intellij/openapi/vcs/changes/ui/ChangesTree.java)
- [2026.1 ChangeNodeDecorator 源码](https://github.com/JetBrains/intellij-community/blob/261/platform/vcs-impl/shared/src/com/intellij/openapi/vcs/changes/ui/ChangeNodeDecorator.java)
