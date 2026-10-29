# 版本与构建矩阵 — 0.3.1

当前验证 14 个明确版本、37 个加载器组合。严格按版本选择适配器，不声明覆盖相邻补丁号。
1.16.5–1.19.4 没有 NeoForge；1.20.1 使用保留 Forge 包名的早期 NeoForge 47.1.106。

## 实际编译配置

Fabric 列的依赖版本是 Fabric API；完整构建配置与 Fabric Loader 版本在对应模块的 `build.gradle`。

| 模块 | Minecraft | 加载器 | Java 目标 | 固定依赖版本 | 构建入口 |
| --- | --- | --- | --- | --- | --- |
| fabric1165 | 1.16.5 | fabric | 8 | 0.42.0+1.16 | root |
| fabric1171 | 1.17.1 | fabric | 16 | 0.46.1+1.17 | root |
| fabric1182 | 1.18.2 | fabric | 17 | 0.77.0+1.18.2 | root |
| fabric1192 | 1.19.2 | fabric | 17 | 0.77.0+1.19.2 | root |
| fabric1194 | 1.19.4 | fabric | 17 | 0.87.2+1.19.4 | root |
| fabric1201 | 1.20.1 | fabric | 17 | 0.92.6+1.20.1 | root |
| fabric1204 | 1.20.4 | fabric | 17 | 0.97.3+1.20.4 | root |
| fabric1206 | 1.20.6 | fabric | 21 | 0.100.8+1.20.6 | root |
| fabric1211 | 1.21.1 | fabric | 21 | 0.115.6+1.21.1 | root |
| fabric12111 | 1.21.11 | fabric | 21 | 0.141.6+1.21.11 | root |
| fabric1214 | 1.21.4 | fabric | 21 | 0.119.4+1.21.4 | root |
| fabric2612 | 26.1.2 | fabric | 25 | 0.155.3+26.1.2 | root |
| fabric262 | 26.2 | fabric | 25 | 0.161.0+26.2 | root |
| fabric263 | 26.3 | fabric | 25 | 0.165.0+26.3 | root |
| forge1165 | 1.16.5 | forge | 8 | 1.16.5-36.2.42 | forge6 |
| forge1171 | 1.17.1 | forge | 16 | 1.17.1-37.1.1 | root |
| forge1182 | 1.18.2 | forge | 17 | 1.18.2-40.3.12 | root |
| forge1192 | 1.19.2 | forge | 17 | 1.19.2-43.5.2 | root |
| forge1194 | 1.19.4 | forge | 17 | 1.19.4-45.4.5 | root |
| forge1201 | 1.20.1 | forge | 17 | 1.20.1-47.4.0 | root |
| forge1204 | 1.20.4 | forge | 17 | 1.20.4-49.2.13 | forge6 |
| forge1206 | 1.20.6 | forge | 21 | 1.20.6-50.2.14 | root |
| forge1211 | 1.21.1 | forge | 21 | 1.21.1-52.1.20 | root |
| forge12111 | 1.21.11 | forge | 21 | 1.21.11-61.2.4 | root |
| forge1214 | 1.21.4 | forge | 21 | 1.21.4-54.1.22 | root |
| forge2612 | 26.1.2 | forge | 25 | 26.1.2-64.1.5 | root |
| forge262 | 26.2 | forge | 25 | 26.2-65.1.5 | root |
| forge263 | 26.3 | forge | 25 | 26.3-66.1.0 | root |
| neoforge1201 | 1.20.1 | neoforge | 17 | 1.20.1-47.1.106 | root |
| neoforge1204 | 1.20.4 | neoforge | 17 | 20.4.253 | root |
| neoforge1206 | 1.20.6 | neoforge | 21 | 20.6.144 | root |
| neoforge1211 | 1.21.1 | neoforge | 21 | 21.1.172 | root |
| neoforge12111 | 1.21.11 | neoforge | 21 | 21.11.48 | root |
| neoforge1214 | 1.21.4 | neoforge | 21 | 21.4.161 | root |
| neoforge2612 | 26.1.2 | neoforge | 25 | 26.1.2.124 | root |
| neoforge262 | 26.2 | neoforge | 25 | 26.2.0.93 | root |
| neoforge263 | 26.3 | neoforge | 25 | 26.3.0.99 | root |

## 两套构建工具

- `root`：Gradle 9.6、构建 JVM 25；Fabric Loom 1.15.5、ModDevGradle 2.0.148 / legacy 2.0.141、ForgeGradle 7.0.40 按版本选用。Java 编译目标为 8/16/17/21/25。
- `forge6`：独立 Gradle 8.10.2、构建 JVM 21、ForgeGradle 6.0.42，入口 `platforms/forge-legacy-build/settings.gradle`。1.16.5 的 MCP 工具另需真实 JDK 8。主构建不会误配置这两个模块。
- 正式发布使用 `scripts/package.sh`，统一调用两套构建并在导出、校验成功后清理项目缓存。Gradle 发行版下载有官方 SHA-256 校验。

## 无混淆版本

26.1+ 直接使用官方名称。Fabric 使用 `net.fabricmc.fabric-loom` NoRemap 插件，不声明 `mappings`，依赖使用 `implementation`，打包任务是 `jar`。
较早版本使用旧映射／重映射路线；Forge 1.16.5 的类名仍按对应 MCP 工具链适配。
26.1.2、26.2、26.3 分别保留 GUI 与 LAN API 适配，不能只替换版本号。

## 通用包的选择过程

外层启动器和共享核心仅含 Java 8 类。启动器检测当前加载器、游戏版本及 Java 主版本，精确选择 `META-INF/tailcat/adapters.json` 的一个条目。
选中的适配 JAR 经 SHA-256 校验后缓存，通过独立子类加载器启动；其余版本的类不进入加载器扫描路径。
六平台原生压缩载荷、XZ 解码器和许可证只存一份。单平台包保留完整适配矩阵，只裁剪原生程序。

## 官方依据

- [Mojang 版本清单](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)：版本、运行 Java 与下载元数据。
- [Fabric API Maven 元数据](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml)：所用 API 坐标。
- [Forge Maven 元数据](https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml)：Forge 坐标。
- [NeoForge Maven 元数据](https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml)：NeoForge 坐标。
- [ModDevGradle Legacy 文档](https://github.com/neoforged/ModDevGradle/blob/main/LEGACY.md)：旧 Forge 工具链支持范围。

编译验证和真实游戏验收分别记录在 [validation.md](validation.md)。
