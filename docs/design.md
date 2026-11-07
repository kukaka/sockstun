# SocksTun Fork — Wi-Fi 自动切换

基于 [`heiher/sockstun`](https://github.com/heiher/sockstun) v8.0（`df7b2a0`）的二次开发。
上游 MIT 许可，本 fork 同样 MIT。

**要解决的问题**：不同 Wi-Fi 下 Mac 的局域网 IP 不同，需要按 Wi-Fi 自动切换代理配置并自动激活。

---

## 一、功能

### 1. Wi-Fi 自动切换（核心）

按当前连接的 **Wi-Fi 名称（SSID）** 自动选择配置：

```
手机连上「Home」    → 自动切到配置 A（192.168.2.25:7897）
手机连上「Office」  → 自动切到配置 B（192.168.3.10:7897）
手机连上未绑定的网  → 切到「默认配置」（可选，默认不切）
```

- **自动切换**：隧道运行中，SSID 变了就自动换配置
- **自动启动**：隧道关闭时，连上**已绑定**的 Wi-Fi 就自动拉起隧道
- 两种行为都可以单独开关

实现要点：
- **无缝切换**：两个配置的 VPN 层设置（IP 族 / 全局模式 / 应用白名单）相同时，
  只重启原生隧道并**复用同一个 TUN fd** → 连接不中断
- VPN 层设置不同（如应用白名单不同）时，退化为「断开 → 800ms → 重连」
- SSID 抖动防抖：同一网络不同 AP 之间漫游不会误触发
- 离开 Wi-Fi（切到移动数据）时**不切换**，保持当前配置

### 2. MTU 可调

上游把 MTU 硬编码为 8500（`Preferences.getTunnelMtu()` 直接 `return 8500;`）。
本 fork 改为 per-profile 可配，主界面新增输入框。

参考值：8500（上游默认）→ 1500 → 1400 → 1280。
QUIC 套 QUIC（Hysteria2 / TUIC 节点）时建议 1400 以下。

### 3. 中文界面

新增 `res/values-zh-rCN/strings.xml`，全量汉化。

### 4. targetSdk / compileSdk 升级

`compileSdk` / `targetSdk` 34 → 36（Android 16，满足 2026-08 起的 Google Play 上架要求）。

---

## 二、怎么用

### 1. 准备多套配置

主界面顶部 `◀ 配置名 ▶` 切换 profile，长按 profile 名可**新建 / 重命名 / 删除**。
每套配置各自有独立的 SOCKS 地址、端口、账密、MTU。

新建完先在每套配置里填好对应的 Mac IP。

### 2. 绑定 Wi-Fi

主界面 → **「Wi-Fi 自动切换」** 区域：

| 控件 | 说明 |
|---|---|
| `启用 Wi-Fi 自动切换` | 总开关。首次开启会申请位置权限（读 SSID 必需） |
| `连上已知 Wi-Fi 时自动启动` | 隧道关闭时是否自动拉起 |
| `当前 Wi-Fi：xxx` | 实时显示当前 SSID |
| `绑定管理` | 打开绑定列表 |
| `默认配置` | 未绑定的 Wi-Fi 使用哪套配置（可选「不切换」） |

在**绑定管理**页：

- 顶部显示当前 Wi-Fi，点 **「绑定当前 Wi-Fi」** → 把当前 SSID 绑到**当前选中的配置**
- 点列表某项 → 改绑到别的配置
- 长按某项 → 删除绑定

### 3. 典型流程

```
① 建配置 Home   → 填 192.168.2.25:7897
② 建配置 Office → 填 192.168.3.10:7897
③ 在 Home 网络下：选中配置 Home → 绑定管理 → 绑定当前 Wi-Fi
④ 在 Office 网络下：选中配置 Office → 绑定管理 → 绑定当前 Wi-Fi
⑤ 打开「启用 Wi-Fi 自动切换」
⑥ 以后换网络就自动切了
```

> 提示：绑定时**当前选中的配置**才会被记录，所以绑定前先切到目标配置。

### 4. 权限说明（踩过坑，这段很重要）

读取 SSID 在 Android 上有**三层**门槛，缺一不可：

| # | 条件 | 说明 |
|---|---|---|
| 1 | **`ACCESS_FINE_LOCATION` 已授权** | SSID 被当作位置信息。Android 13 的 `NEARBY_WIFI_DEVICES` 只覆盖「按已知 SSID 连接设备」，**读取当前 SSID 仍受位置权限管控** |
| 2 | **系统定位开关已打开** | 用户关了「定位」→ 照样脱敏 |
| 3 | **App 处于前台** | 有可见 Activity，**或运行 `foregroundServiceType="location"` 的前台服务** |

第 3 条是后台监听的关键：普通 `specialUse` 前台服务**不算**前台位置访问，
SSID 会被脱敏成 `<unknown ssid>`。所以 `WifiWatchService` 必须声明为：

```xml
<service android:name=".WifiWatchService"
    android:exported="false"
    android:foregroundServiceType="location"/>
```

并配 `FOREGROUND_SERVICE_LOCATION` 权限。

Manifest：

```xml
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION"
    android:minSdkVersion="29"/>
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
```

> **运行时只申请 `ACCESS_FINE_LOCATION`**（一个弹窗）。
> `NEARBY_WIFI_DEVICES` 保留在 manifest 里作为兼容性保险——部分 ROM 会用
> 它来把守 `getConnectionInfo()`，但官方语义里它覆盖的是「按*已知* SSID 连接设备」，
> 读取当前 SSID 靠的仍是位置权限。
>
> `NEARBY_WIFI_DEVICES` **没有**加 `neverForLocation` —— 该断言等于声明
> 「不使用 Wi-Fi 推导位置」，与读 SSID 自相矛盾，加上反而可能导致脱敏。

**权限交互流程**（`MainActivity`）：

1. 用户勾选「启用 Wi-Fi 自动切换」
2. 已授权 → 直接启用
3. 未授权且之前拒绝过 → 先弹**自绘的用途说明**对话框（`wifi_permission_rationale`）
4. 再弹系统权限框
5. 被永久拒绝 → 弹窗引导去「设置 → 应用 → SocksTun → 权限」手动开启

**状态自诊断**：主界面的「当前 Wi-Fi」会直接说明卡在哪一层：

| 显示 | 原因 |
|---|---|
| `当前 Wi-Fi：xxx` | ✅ 正常 |
| `当前 Wi-Fi：未知（未授予位置权限）` | 权限没给 |
| `当前 Wi-Fi：未知（系统定位服务未开启）` | 系统定位总开关关了，App 无能为力 |

### 5. VPN 会遮蔽默认网络（另一个坑）

隧道运行时，**默认网络就是 VPN**：

- `ConnectivityManager.getActiveNetwork()` → 返回 VPN，不是 Wi-Fi
- `registerDefaultNetworkCallback()` → 只监听默认网络 → **收不到 Wi-Fi 的回调**

所以本 fork 用的是 **Wi-Fi 专用的 NetworkRequest**：

```java
NetworkRequest request = new NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .build();
cm.registerNetworkCallback(request, callback);
```

以及 `WifiUtils.fromNetworks()` 里遍历 `cm.getAllNetworks()` 找带
`TRANSPORT_WIFI` 的那个，而不是 `getActiveNetwork()`。

### 6. 诊断

App 会用 `SocksTun` tag 打日志，直接看：

```bash
adb logcat -s SocksTun:V
```

会输出原始值，例如：

```
D SocksTun: transportInfo.getSSID() = "<unknown ssid>"
D SocksTun: WifiManager.getSSID() = "<unknown ssid>"
D SocksTun: onCapabilitiesChanged ssid = null
```

看到 `<unknown ssid>` → 权限或前台条件没满足（对照上面三层）。
看到真实名字但界面显示「未知」→ 逻辑问题。

---

## 三、代码结构

### 新增文件

| 文件 | 职责 |
|---|---|
| `WifiUtils.java` | SSID 读取与归一化（去引号、过滤 `<unknown ssid>`）、权限检查 |
| `WifiWatchService.java` | 前台服务，注册 `NetworkCallback`，检测 SSID 变化并应用配置 |
| `WifiBindingActivity.java` | SSID↔配置 绑定管理界面 |
| `res/layout/wifilist.xml`、`wifiitem.xml` | 绑定管理界面布局 |
| `res/values-zh-rCN/strings.xml` | 中文翻译 |

### 修改文件

| 文件 | 改动 |
|---|---|
| `Preferences.java` | 新增 `WifiMap` / `WifiDefault` / `WifiAutoStart` / `WifiEnabled`；MTU 改为 per-profile 可配；新增 profile 索引访问器与 `sameVpnScope()` |
| `TProxyService.java` | 抽出 `writeConfigFile()`；新增 `ACTION_RELOAD`，在同一 TUN fd 上重启原生隧道 |
| `MainActivity.java` | Wi-Fi 区域 UI、权限申请、MTU 输入框、默认配置选择 |
| `ServiceReceiver.java` | 开机时一并拉起 Wi-Fi 监听服务 |
| `AndroidManifest.xml` | 新增权限、`WifiWatchService`、`WifiBindingActivity` |
| `app/build.gradle` | compileSdk/targetSdk 36，versionCode 13 / versionName 8.1 |
| `build.gradle` | AGP 8.4.1 → 8.5.2，`jcenter()` → `mavenCentral()` |
| `gradle/wrapper/*` | Gradle 8.8 → 8.9（含 sha256 校验） |
| `gradle.properties` | 新增 `android.suppressUnsupportedCompileSdk=36` |

### 关键实现点

**无缝切换**依赖 JNI 层的能力（`hev-jni.c`）：

```c
native_stop_service:   hev_socks5_tunnel_quit(); pthread_join(...);   /* 干净停止 */
native_start_service:  if (is_running) goto exit;                    /* 已在跑则拒绝 */
                       if (thread_joinable) pthread_join(...);       /* 旧线程已 join 则跳过 */
```

所以 `TProxyStopService()` + `TProxyStartService(同一 fd)` 是安全的，
**不需要重新 `establish()`**，因此没有 VPN 重建的中断，也没有双 VPN 竞态。

**数据流**：

```
ConnectivityManager.NetworkCallback.onCapabilitiesChanged
    → WifiUtils.ssidFromCapabilities()        取 SSID
    → WifiWatchService.onSsidChanged()        去抖
    → applyProfile()                           查表 → 目标 profile
        ├─ VPN scope 相同 → TProxyService.ACTION_RELOAD   （复用 fd，无中断）
        └─ VPN scope 不同 → ACTION_DISCONNECT + 800ms + ACTION_CONNECT
```

---

## 四、构建

### 依赖

| 组件 | 版本 | 状态 |
|---|---|---|
| JDK | 17 | ✅ 已有 |
| Android SDK Platform | android-36 | ✅ **构建时自动下载** |
| Build Tools | 34.0.0 | ✅ 已有 |
| NDK | 29.0.14206865 | ✅ **构建时自动下载** |
| Gradle | 8.9 | ✅ 已在 wrapper 缓存 |
| AGP | 8.5.2 | ✅ 已在 Gradle 缓存 |

> Gradle / AGP 的自动下载依赖 `~/Library/Android/sdk/licenses` 里已接受 licenses。

### 编译

```bash
cd sockstun
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/hev.sockstun-8.1-debug.apk`

**注意**：

- 是 `./gradlew`（wrapper 脚本），不是 `./gradle`
- 需要 `local.properties` 指定 SDK 路径（已生成）：
  ```properties
  sdk.dir=/Users/<用户名>/Library/Android/sdk
  ```
- `local.properties` 不应提交到 git

### 验证状态

- ✅ **APK 构建成功**（`BUILD SUCCESSFUL`，2m41s，42 个任务）
- ✅ 4 个 ABI 的 `libhev-socks5-tunnel.so` 均已产出（arm64-v8a / armeabi-v7a / x86 / x86_64）
- ✅ 中文资源已打包（`zh-rCN` 变体存在）
- ✅ 权限清单符合预期（`NEARBY_WIFI_DEVICES` 带 `neverForLocation`，`ACCESS_FINE_LOCATION` 上限 32）
- ✅ Java 源码 `javac` 全量编译零 error；全部 XML 合法；`R.id`/`R.layout`/`R.string` 引用零缺失
- ⏳ **功能未在真机验证**

---

## 五、已知限制

1. **只支持 SSID 匹配**，没有网关 IP 匹配（那需要免权限方案，可后续加）
2. **用户可能拒绝运行时权限** → 功能静默失效（主界面显示「当前 Wi-Fi：未知」）。
   Android 13+ 只需「附近的设备」权限（弹窗比位置权限友好得多）；
   Android 12 及以下只能申请位置权限
3. **切到移动数据时不切换配置**，保持最后一个 Wi-Fi 的配置（此时该配置的服务器通常已不可达）
4. **「自动启动」只对有显式绑定的 Wi-Fi 生效**，避免在移动数据上误启动
5. 切换配置时**最多中断约 1 秒**（仅 VPN scope 不同时）
6. `Preferences` 仍用已废弃的 `Context.MODE_MULTI_PROCESS`（上游遗留）
7. `x86` / `x86_64` 的 `.so` 会被打包进 APK，但模拟器上能否跑通未验证
