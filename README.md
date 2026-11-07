# SocksTun for Android

把手机上**指定 App** 的 **TCP + UDP** 流量，通过 SOCKS5 转发到局域网里的 Clash。

> 基于 [`heiher/sockstun`](https://github.com/heiher/sockstun) v8.0 的二次开发，
> 增加 **Wi-Fi 自动切换**、**MTU 可调**、**中文界面**，并把 `targetSdk` 升到 36。

---

## 一、为什么需要它

手机 Wi-Fi 里的「代理」设置走的是 HTTP `CONNECT`，**只代理 TCP**。
而 TikTok 等 App 的视频播放走 **UDP/QUIC**，这些流量根本进不了代理 → 视频播不了。

Android 没有系统级的「UDP 代理」开关。能在**不 Root** 的前提下接管指定 App 的
UDP 流量的唯一办法是 **`VpnService` + TUN 虚拟网卡**，把系统发出的原始 IP 包拦下来，
再通过 Socket 转发出去。本项目做的就是这件事。

## 二、功能

| 功能 | 说明 |
|---|---|
| **应用级转发** | per-App 白名单，只有勾选的 App 进隧道，其余 App 照常走物理网卡 |
| **TCP + UDP** | TUN 抓到的 IP 包全部转发：TCP 走 SOCKS5 `CONNECT`，UDP 走 `UDP ASSOCIATE` |
| **Wi-Fi 自动切换** | 按 Wi-Fi 名称（SSID）自动切到对应配置——不同网络下电脑 IP 不同 |
| **自动启动** | 隧道关闭时，连上**已绑定**的 Wi-Fi 自动拉起隧道 |
| **无缝切换** | 两个配置仅服务器地址不同时，复用同一个 TUN fd 重启原生隧道，**连接不中断** |
| **远端 DNS** | 域名原样转发给对端 Clash，对端的域名规则能正常命中（不退化成 IP） |
| **MTU 可调** | 上游硬编码 8500，本项目改为 per-profile 可配（QUIC 场景需要 1400） |
| **中文界面** | 完整汉化 |

## 三、架构

```
手机上的 App (TikTok)
   │  TCP + UDP(QUIC)
   ▼
VpnService 建立的 TUN 虚拟网卡
   │  原始 IP 包
   ▼
hev-socks5-tunnel (C / lwIP 用户态协议栈)
   │  SOCKS5 CONNECT(TCP)  +  SOCKS5 UDP ASSOCIATE(UDP)
   ▼
Mac 上的 Clash Verge  (mixed-port, allow-lan)
   │  按对端自己的规则分流
   ▼
机场节点 ──▶ TikTok CDN
```

关键设计：

- **TUN 交给 App 的是原始 IP 包，不是字节流**。UDP 好办（一个 `DatagramSocket` 一对一映射），
  TCP 必须在用户态实现协议栈——这就是 `hev-socks5-tunnel` 里 lwIP 的存在理由。
- **域名必须原样送到对端**。用 mapdns 假 IP + 反查，把 SOCKS5 请求的 ATYP 置为 domain，
  对端 Clash 才能按域名规则分流（否则只能按 IP 匹配，很容易漏）。
- **VPN 会遮蔽默认网络**。隧道运行时默认网络就是 VPN，所以监听 Wi-Fi 必须用
  Wi-Fi 专用的 `NetworkRequest`，不能 `registerDefaultNetworkCallback`。

## 四、与其他客户端的区别

| | 本项目 / SocksTun | Clash Meta for Android | sing-box for Android |
|---|---|---|---|
| 定位 | **纯转发器**，不做任何判断 | 客户端 + 完整规则引擎 | 客户端 + 完整规则引擎 |
| 分流逻辑在哪 | **全部在对端 Clash** | 手机上跑一套 | 手机上跑一套 |
| 许可 | MIT | GPL-3.0 | GPL-3.0 |
| 内核体积 | C，几百 KB | Go，~20MB/ABI | Go，~20MB/ABI |
| 域名保留 | mapdns 自动反查 | fake-ip + sniffer（需调教） | fake-ip + sniff |

> 「纯转发器」这点很重要：手机上不再有第二套规则引擎，
> 也就不会出现「手机上的策略组」和「电脑上的策略组」互相打架的情况。

## 五、安装

### F-Droid

搜 `SocksTun`（包名 `hev.sockstun`）安装官方版。

### 自己构建（本仓库）

```bash
git clone --recursive <本仓库地址>
cd <仓库目录>
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/hev.sockstun-8.1-debug.apk
```

**依赖**：JDK 17、Android SDK Platform 36、NDK 29.0.14206865。
Gradle 与 AGP 版本已锁定，缺失的 SDK 组件 Gradle 会自动下载。

安装：

```bash
adb install -r app/build/outputs/apk/debug/hev.sockstun-8.1-debug.apk
```

## 六、使用

### 1. 配置服务器

| 字段 | 值 | 说明 |
|---|---|---|
| Socks Address | 电脑的局域网 IP | 如 `192.168.2.25` |
| Socks UDP Address | 同上 | 覆盖服务器返回的 UDP 中继地址；只填 IP，**不要带端口** |
| Socks Port | `7897` | Clash Verge 的 mixed-port。**⚠️ 默认是 1080，必须改** |
| Remote DNS | ✅ 勾选 | 域名交给对端解析 |
| UDP relay over TCP | ❌ **不勾** | 私有协议，对端 Clash 不支持，UDP 必挂 |
| Global | ❌ 不勾 | 用 per-App 模式 |
| Apps | 勾选目标 App | 至少勾一个，否则退化成全局模式 |

Mac 侧需要 Clash Verge 打开「允许局域网」并放行防火墙。

### 2. Wi-Fi 自动切换（多网络场景）

先建多套配置（顶部 `◀ 配置名 ▶` 切换，长按可新建/重命名/删除），
每套填各自网络下电脑的 IP。然后：

```
「Wi-Fi 自动切换」区域
  ├─ 启用 Wi-Fi 自动切换          ← 总开关
  ├─ 连上已知 Wi-Fi 时自动启动     ← 自动激活
  ├─ 当前 Wi-Fi：xxx              ← 实时状态 + 失败原因提示
  ├─ 绑定管理 → 绑定当前 Wi-Fi     ← 把当前 SSID 绑到当前选中的配置
  └─ 默认配置                      ← 未绑定的 Wi-Fi 用哪套（可选「不切换」）
```

> 绑定时记录的是**当前选中的配置**，所以绑定前先切到目标配置。

详细说明见 [`docs/design.md`](docs/design.md)。

## 七、已知限制

- Android 8+ 读取 SSID 需要**位置权限**，且**系统定位总开关必须打开**
  （SSID 被 Android 当作位置信息）。应用会明确提示卡在哪一层。
- 「自动启动」只对**有显式绑定**的 Wi-Fi 生效，避免在移动数据上误启动。
- 切换到移动数据时**不切换配置**，保持最后一个 Wi-Fi 的配置。
- 两个配置的 VPN 层设置（IP 族 / 全局模式 / 应用白名单）不同时，
  切换会有约 1 秒中断。
- `x86`/`x86_64` 的 `.so` 已打包，但模拟器未验证。

## 八、开发状态

| 项 | 状态 |
|---|---|
| APK 构建 | ✅ `BUILD SUCCESSFUL` |
| TCP/UDP 转发（真机） | ✅ 已验证 |
| 中文界面 / MTU 可调 | ✅ 已实现 |
| Wi-Fi 自动切换 | 🚧 代码完成，**待多网络环境实测** |
| targetSdk 36 | ✅ 已升级 |

## 九、文档

| 文档 | 内容 |
|---|---|
| [`docs/design.md`](docs/design.md) | Wi-Fi 自动切换的设计与实现、权限三层条件、踩过的坑 |
| [`docs/evaluation.md`](docs/evaluation.md) | 开源方案评估：为什么选 SocksTun |
| [`docs/cmfa-alternative.yaml`](docs/cmfa-alternative.yaml) | 备选方案：Clash Meta for Android 的配置 |
| [`docs/upstream-readme.md`](docs/upstream-readme.md) | 上游原始 README |

## 十、许可与致谢

- 本项目基于 [`heiher/sockstun`](https://github.com/heiher/sockstun)，**MIT 许可**
- 底层隧道基于 [`heiher/hev-socks5-tunnel`](https://github.com/heiher/hev-socks5-tunnel)，MIT 许可
- 内核 lwIP / yaml / hev-task-system / hev-socks5-core 均为上游的子模块

详细的上游关系与同步方式见 [`UPSTREAM.md`](UPSTREAM.md)。
