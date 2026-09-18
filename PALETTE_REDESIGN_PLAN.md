# X Client UI 配色重构实施计划（Palette Redesign Plan）

> **文档性质**：执行蓝图（Implementation Plan）  
> **完整文档**：见 [docs/ui-ux/palette-redesign-plan.md](file:///opt/x-client/docs/ui-ux/palette-redesign-plan.md)  
> **交互式原型**：见 [preview/index.html](file:///opt/x-client/preview/index.html)  
> **所属分支**：`feat/ui-theme-redesign`  

## 核心实施要点（给执行 Agent 的快速指引）

1. **绝对不改动任何 Android 布局结构**：
   - 保持当前主页 `Toolbar -> RecyclerView 节点列表 -> 右下角双 FAB` 的简洁设计；
   - 绝不增加顶部状态卡、流量统计图等占用空间的非必要元素。
2. **两处文件直接替换**：
   - `app/src/main/res/values/colors.xml`（Light 模式）
   - `app/src/main/res/values-night/colors.xml`（Dark 模式）
   - 完整可直接使用的 XML 代码已完整写入 [docs/ui-ux/palette-redesign-plan.md](file:///opt/x-client/docs/ui-ux/palette-redesign-plan.md) 第 3.1 节。
3. **构建提醒**：
   - **严格禁止本地 Gradle 构建**；
   - 语法检查确认无误后直接提交并推送，由 GitHub Actions 负责构建验证。
