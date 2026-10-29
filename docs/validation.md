# 0.3.1 验证 — 2026-10-29

本机 macOS arm64 编译并发布，未使用GitHub Actions。目标发布页： https://github.com/Shirakawa-Kotone/tailcatWithMinecraft/releases/tag/v0.3.1 。

| 检查 | 结果 |
| --- | --- |
| 主构建35组合 | PASS，Gradle9.6/JDK25，首次重建依赖58分20秒 |
| 独立旧Forge两组合 | PASS，Gradle8.10.2/JDK21，真实JDK8工具，23秒 |
| Java核心 | 39项通过，实际Java8与25均已运行 |
| 通用启动器 | 11项通过 |
| Python回归 | 12项通过 |
| 七个发布包 | 原生哈希、嵌套适配哈希、元数据、Java8外层、许可证和体积检查全部通过 |
| 最终通用与macOS ARM64包 | 真实Java8提取、SHA-256校验、缓存和内置helper启动通过 |
| 账号验证变更 | 默认关闭，房主可开启；9项回归覆盖取消、失败恢复、幂等与切换恢复顺序 |
| 代码复核 | 已修复服务器重入执行器可能使旧设置晚恢复的竞态，无剩余重要发现 |

本轮Go代码及六个网络程序未变；此前真实双访客DERP小流量回显、普通/race测试记录沿用。当前新认证行为仍需真实Minecraft双客户端游戏验收；Windows/Linux执行及双机UDP切换同样保留为待验收项目。

| 文件 | 字节 |
| --- | ---: |
| tailcat-universal-0.3.1.jar | 18,643,626 |
| tailcat-universal-0.3.1-darwin-amd64.jar | 6,866,298 |
| tailcat-universal-0.3.1-darwin-arm64.jar | 6,202,812 |
| tailcat-universal-0.3.1-linux-amd64.jar | 6,971,960 |
| tailcat-universal-0.3.1-linux-arm64.jar | 6,135,300 |
| tailcat-universal-0.3.1-windows-amd64.jar | 6,929,309 |
| tailcat-universal-0.3.1-windows-arm64.jar | 6,048,172 |

附件另提供 `build-matrix.json` 和 `SHA256SUMS`。构建完成后清理项目缓存，报告保留在本地 `dist/reports/`。

---

# 验证记录 — 2026-10-29

Mod 0.3.0；构建／运行检查主机 macOS arm64。helper 保持0.2.0，Tailcat固定fe50bc39d802+mclink-backports；Java和Go网络程序独立标识版本。

## 本轮实际结果

| 检查 | 结果 |
| --- | --- |
| 主构建 Gradle9.6/JDK25 | PASS，35个加载器／版本组合，3分24秒 |
| 独立 ForgeGradle6/Gradle8.10.2/JDK21 | PASS，Forge1.16.5和1.20.4，12秒；MCP工具使用真实JDK8 |
| 全37个普通JAR | 当前0.3.0身份、翻译资源、入口类Java8/16/17/21/25目标均核实 |
| 核心JUnit | 30项，0失败、0错误；本轮已在实际Java8和25分别运行 |
| 通用启动器JUnit | 11项，0失败、0错误；实际Java8及25均通过 |
| Python完整性／打包／清理回归 | 12项通过；包含Compatibility位置、旧Fabric依赖、canonical core、混合载荷、损坏哈希和外部符号链接 |
| 最终7个发布JAR | 原生成员／SHA-256、适配层哈希／入口、Java8外层、许可证、元数据、体积阈值全部通过 |
| 最终通用JAR，真实Java8 | 提取darwin-arm64程序18,616,386字节，约607ms；缓存命中、version协议1启动通过 |
| 最终darwin-arm64单平台JAR，真实Java8 | 提取、缓存、内置程序version启动通过 |
| 真实helper联机复验 | 两位访客同时经DERP完成少量TCP回显、字节计数核实，房主EOF关闭两条活动连接通过 |
| 代码复核 | 通用类加载、真实FML构造支持、早期NeoForge识别、canonical core和发布清理边界无未解决的重要发现 |

## 最终产物

MB按1,000,000字节计算。通用包18.59MB；六个单平台包6.00–6.92MB，均完整离线内置helper。

| 文件 | 字节 |
| --- | ---: |
| tailcat-universal-0.3.0.jar | 18,591,547 |
| tailcat-universal-0.3.0-darwin-amd64.jar | 6,814,219 |
| tailcat-universal-0.3.0-darwin-arm64.jar | 6,150,733 |
| tailcat-universal-0.3.0-linux-amd64.jar | 6,919,881 |
| tailcat-universal-0.3.0-linux-arm64.jar | 6,083,221 |
| tailcat-universal-0.3.0-windows-amd64.jar | 6,877,230 |
| tailcat-universal-0.3.0-windows-arm64.jar | 5,996,093 |

每个包仍包含全部37个精确适配条目。完整SHA-256与选择矩阵在 `dist/build-matrix.json`；原始构建日志、JUnit XML、打包和网络检查结果在 `dist/reports/`。
一键发布完整复验通过（主构建21秒、独立Forge构建9秒），随后已清理17,675,828,636字节项目缓存；清理记录在 `dist/cache-cleanup.json`，保留最终JAR和报告。

## 沿用的helper验证

本轮没有修改已验证的Go网络逻辑。此前四个Go包普通／race测试通过，覆盖有界流式测速、取消、慢速TCP部分上传和实时状态统计；六个原生程序在这些修复后重新构建。
40个实际链接Go模块的许可证已收集并随包分发。不能把这些历史测试描述为本轮新跑的Go测试。

## 尚需真实游戏验收

编译和启动器单元测试不能替代实际Minecraft启动。14个版本在真实Fabric／Forge／NeoForge中打开界面、分享／加入世界和正常账号认证尚未逐一验收。
跨两台物理机器的UDP直连／DERP切换，以及Windows/Linux执行与系统签名／公证也仍待验证。
本次网络复验只使用小量回显数据，没有对公共DERP做带宽测试；测速仅允许已确认的直连路径。
