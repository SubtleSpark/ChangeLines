## 0.1.1：跟随 IDEA 内置配色

移除行数统计自定义的明暗两套 RGB 和 `ChangeLines.added` / `ChangeLines.removed` 主题键。

- 文件名、字体、图标、背景继续由 IDEA 原生 renderer 绘制，不做修改。
- `+新增` 使用 IDEA 内置 `FileStatus.ADDED` 颜色，`-删除` 使用 `FileStatus.DELETED` 颜色。颜色由当前 VCS 配色设置决定，不强制绿色或红色；部分主题的删除色可能为灰色。
- 每次渲染重新读取配色，支持切换主题/配色方案以及修改 VCS 颜色，不缓存旧的 RGB。
- 选中行的颜色和对比度继续遵循原生树组件规则。未指定状态颜色时继承原生文字色。

统计算法和支持的 Changes 页面与 0.1.0 相同。新增原生配色、动态配色修改和选中行配色回归测试。

## 安装 / 更新

下载本页 Assets 中的 **ChangeLines-0.1.1.zip**，不要解压，也不要下载 Source code。

IntelliJ IDEA → Settings → Plugins → 齿轮 → Install Plugin from Disk… → 选择 ZIP → 重启 IDEA。已安装 0.1.0 时直接安装此包更新。

最低版本：**IntelliJ IDEA 2026.1（build 261）**。无需另外安装 Java 或 Gradle。

## 验证与限制

Release 工作流只在编译、统计测试、IDEA 2026.1 平台集成测试、2026.1 / 2026.2.0.1 二进制兼容性检查和安装包结构检查全部通过后发布；附件是同一次构建中被验证的 ZIP，附 SHA256SUMS。

平台集成测试不等同于所有第三方主题和各操作系统上的人工桌面验收。未覆盖未跟踪文件节点、Git staging 专用视图或远程开发前端的独立树实现。
