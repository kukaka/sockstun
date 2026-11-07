# 上游关系

本项目是 [`heiher/sockstun`](https://github.com/heiher/sockstun) 的 fork。

## 分支与远端约定

| 远端 | 指向 | 用途 |
|---|---|---|
| `upstream` | `https://github.com/heiher/sockstun.git` | 上游原仓库，只读。用来拉上游修复 |
| `origin` | 本项目自己的 GitHub 仓库 | 推送我们的改动 |

```bash
git remote -v
git remote rename origin upstream
git remote add origin <你的仓库地址>
git push -u origin main
```

## 基线版本

| 项 | 值 |
|---|---|
| 上游仓库 | `heiher/sockstun` |
| 基线提交 | `df7b2a0342079b2abf3a7030f41e9b77a23def14`（v8.0，2026-07） |
| 子模块 | `app/src/main/jni/hev-socks5-tunnel` @ `1711a443d72d89679c0a2044f36085358a101bc4`（2.19.3-23-g1711a44） |

上游的原始 README 保留在 [`docs/upstream-readme.md`](docs/upstream-readme.md)
（俄文版在 `docs/upstream-readme-ru.md`）。

## 我们改了什么

完整说明见 [`docs/design.md`](docs/design.md)。改动概览：

| 文件 | 改动 |
|---|---|
| `app/src/main/java/hev/sockstun/WifiUtils.java` | **新增**。SSID 读取与归一化、权限判断、定位服务状态检测 |
| `app/src/main/java/hev/sockstun/WifiWatchService.java` | **新增**。前台服务，监听 Wi-Fi 变化并切换配置 |
| `app/src/main/java/hev/sockstun/WifiBindingActivity.java` | **新增**。SSID↔配置 绑定管理界面 |
| `app/src/main/res/layout/wifilist.xml`、`wifiitem.xml` | **新增**。绑定管理界面布局 |
| `app/src/main/res/values-zh-rCN/strings.xml` | **新增**。完整中文翻译 |
| `app/src/main/java/hev/sockstun/Preferences.java` | Wi-Fi 绑定表、MTU 改为 per-profile 可配、profile 索引访问器、`sameVpnScope()` |
| `app/src/main/java/hev/sockstun/TProxyService.java` | 抽出 `writeConfigFile()`；新增 `ACTION_RELOAD`，在同一 TUN fd 上重启原生隧道 |
| `app/src/main/java/hev/sockstun/MainActivity.java` | Wi-Fi 区域 UI、权限申请与说明、MTU 输入框、默认配置选择 |
| `app/src/main/java/hev/sockstun/ServiceReceiver.java` | 开机时一并拉起 Wi-Fi 监听服务 |
| `app/src/main/AndroidManifest.xml` | 新增权限、`WifiWatchService`、`WifiBindingActivity` |
| `app/build.gradle` | compileSdk/targetSdk 36，versionCode 13 / versionName 8.1 |
| `build.gradle` | AGP 8.4.1 → 8.5.2，`jcenter()` → `mavenCentral()` |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 8.8 → 8.9 |
| `gradle.properties` | `android.suppressUnsupportedCompileSdk=36` |

**原生代码（子模块 `hev-socks5-tunnel`）零改动**。

## 拉取上游更新

```bash
git fetch upstream
git log --oneline HEAD..upstream/main      # 看上游有什么新东西
git merge upstream/main                      # 合并
```

合并后重点回归这几处（我们的改动与上游的 profile 机制耦合）：

- `Preferences.java` 的 `key(profile, name)` 命名空间与 `copyProfile()` / `removeProfile()`
- `TProxyService.startService()` 里 VPN 构建部分与 `writeConfigFile()` 的拆分
- `MainActivity.updateUI()` / `updateControlState()` / `savePrefs()`

## 构建环境版本锁定

| 组件 | 版本 | 备注 |
|---|---|---|
| JDK | 17 | |
| Gradle | 8.9 | wrapper 固定 |
| AGP | 8.5.2 | |
| compileSdk / targetSdk | 36 | |
| NDK | 29.0.14206865 | `app/build.gradle` 中 `ndkVersion` 固定 |
| minSdk | 24 | |

子模块需要递归克隆：

```bash
git clone --recursive <仓库地址>
```
