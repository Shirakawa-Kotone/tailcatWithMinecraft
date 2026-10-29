# 本机构建与发布

完整矩阵需要 JDK8、17、21、25、Python3.9+、curl、tar。通过 `TC_JAVA8_HOME`、`TC_JAVA17_HOME`、`TC_JAVA21_HOME`、`TC_JAVA25_HOME` 指定路径，或使用系统已安装的对应JDK。

主构建使用Gradle9.6和JDK25；Forge1.16.5/1.20.4使用独立Gradle8.10.2、JDK21，旧MCP工具另需真实JDK8。

```sh
# helper改变时，先重建原生程序与许可证；仓库已包含完整压缩载荷。
./scripts/build-natives.sh
bash -c 'source scripts/go-env.sh; python3 scripts/generate-notices.py'

# 编译全部37组合、测试、导出七个JAR，再清理项目缓存
./scripts/package.sh

# 开发期间保留缓存；已编译完可直接导出
./scripts/package.sh --keep-cache
./scripts/package.sh --no-build
```

产物在 `dist/`：全平台通用包、六个单平台包、`build-matrix.json`及`reports/`。默认清理项目内Java/Go缓存和build目录，保留源码、原生压缩载荷、Gradle工具分发包和最终产物。失败时保留缓存及日志供排查。首次完整构建会下载多版本游戏依赖和工具链，临时占用十几GB磁盘；清理后再次完整构建需要重新下载。

```sh
python3 -m unittest discover -s tests -v
python3 scripts/verify-artifacts.py
# 可选：两位访客的小流量真实网络复验，不进行公共中继带宽测试。
python3 scripts/smoke-network.py --allow-network
```

使用本机产物发布GitHub Release；不使用GitHub Actions。发布时逐项核对 `build-matrix.json` 的文件名与SHA-256，并同步README中的直达下载表。

# 分享时的账号验证

0.3.1起，新启动的mod默认不要求Minecraft正版账号验证。房主可以在分享界面切换；切换会重建邀请并断开已有访客。
设置仅作用于当前分享的内置服务器；停止分享或启动失败时恢复之前的认证设置。不会修改启动器、全局账号信息或其他服务器的配置。
关闭验证时，邀请仍是隧道访问凭据，但Minecraft账号身份未经验证；离线身份的UUID／玩家数据可能与在线身份不同。
