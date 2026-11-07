# heiher/sockstun 评估报告

> 目标：Android App / 应用白名单转发 TCP+UDP / 简单的局域网代理服务器配置
> 结论：**三条要求全部满足，且我事先担心的两个问题都不成立。可直接使用，无需自研。**
> 评估方式：clone 源码逐文件阅读（HEAD `df7b2a0`，submodule hev-socks5-tunnel `1711a44` / `2.19.3-23-g1711a44`）

---

## 一、三条要求逐条对照

| 要求 | 结论 | 源码依据 |
|---|---|---|
| 1. Android App | ✅ | F-Droid 收录 `hev.sockstun`，v8.0（2026-07-20）；Material 3 UI + 快捷设置磁贴 + 开机自启；ABI: armeabi-v7a / arm64-v8a |
| 2. 应用白名单转发 TCP/UDP | ✅ | `TProxyService.java` 用 **`addAllowedApplication(appName)`** —— 是**白名单**，非选中的 App 完全绕过隧道 |
| 3. 配置局域网代理服务器 | ✅ | Socks Address 是**自由文本**（无 `android:digits` 限制），可直接填 `192.168.2.25`；端口 / 用户名 / 密码齐全；8.0 起支持**多套命名配置**（最多 13 个） |

**许可：MIT**（不同于 mihomo / sing-box 的 GPL-3.0，闭源分发无障碍）。

---

## 二、两个原先的担心，都被推翻了

### 担心 1：域名会退化成 IP，Mac 的域名规则失效 → **不成立，且比 CMFA 更彻底**

`Remote DNS` 复选框**默认勾选**（`Preferences.getRemoteDns()` 默认 `true`）。实现方式：

1. `TProxyService` 给 TUN 加 DNS server `198.18.0.2`（`getMappedDns()` 常量）
2. hev 配置生成 `mapdns:` 块：
   ```yaml
   mapdns:
     address: 198.18.0.2
     port: 53
     network: 240.0.0.0
     netmask: 240.0.0.0
     cache-size: 10000
   ```
3. `hev-mapped-dns.c` 本地应答 DNS 查询，返回合成 A 记录（假 IP），并把域名存进 tree
4. `hev-utils.c: hev_socks5_addr_from_lwip()` 把假 IP **反查回域名**：
   ```c
   const char *name = hev_mapped_dns_lookup (dns, ntohl (ip_2_ip4 (ip)->addr));
   if (name)
       hev_socks5_addr_from_name (addr, name, htons (port));
   else
       hev_socks5_addr_from_ipv4 (addr, ip, htons (port));
   ```

→ **发给 SOCKS5 服务器的是 ATYP=domain，域名由 Mac 那边的 Clash 解析。** 域名规则命中不受影响。

**副作用（都是好的）**：只合成 A 记录（**不返回 AAAA**）→ 不会走 IPv6，不会 IPv6 泄漏。

### 担心 2：UDP 模式可能撞上 Clash 不支持的私有协议 → **默认就是兼容的**

`socks5.udp` 由 **"UDP relay over TCP"** 复选框驱动，**默认不勾**：

```java
"  udp: '" + (prefs.getUdpInTcp() ? "tcp" : "udp") + "'\n"
```

→ 默认 `udp: 'udp'` = 标准 RFC1928 **UDP ASSOCIATE**，mihomo / Clash 支持。
只有勾上那个复选框才会变成 hev 私有协议（需要 `hev-socks5-server`，Clash 不认）。

---

## 三、三个必须避开的坑

| 坑 | 后果 | 做法 |
|---|---|---|
| **Per-App 模式一个 App 都没勾** | 代码只调 `addDisallowedApplication(self)` → 等价于**全局模式**，所有 App 都被抓 | 至少勾一个（TikTok） |
| **勾了 "UDP relay over TCP"** | `udp: 'tcp'` → Clash 完全不认，UDP 必挂 | **不要勾** |
| **取消勾选 "Remote DNS"** | 域名退化，Mac 侧域名规则失效 | **保持勾选**（默认就是） |

另外：手机上的 **Wi-Fi 代理必须清空**，且同一时刻只能有一个 VPN 生效（CMFA 需先停）。

---

## 四、真正的局限（源码可见，README 未写）

| # | 局限 | 影响 | 严重度 |
|---|---|---|---|
| 1 | **MTU 硬编码 8500**，UI 不可调（`Preferences.getTunnelMtu()` 直接 `return 8500;`） | 若 QUIC 大包被丢导致视频卡顿，**没有旋钮可调** | ⭐⭐⭐ |
| 2 | Remote DNS 只合成 **A 记录**，**TTL 硬编码 1 秒**，假 IP 池 `240.0.0.0/4` 上限 10000 | 每次查询都要重解析；不影响正确性 | ⭐⭐ |
| 3 | `targetSdk 34` / `compileSdk 34`，AGP 8.4.1，Gradle 8.8，NDK 29，Java 17，**纯 Java 无 Kotlin** | 自建要升 SDK；Google Play 新上架要求 targetSdk 35+ | ⭐⭐ |
| 4 | `build.gradle` 声明 4 个 ABI，但顶层 `jni/Application.mk` 只列 armeabi-v7a / arm64-v8a | x86/x86_64 实际不产出（模拟器用不了） | ⭐ |
| 5 | `Preferences` 用已废弃的 `MODE_MULTI_PROCESS`；`stopService()` 调 `System.exit(0)` | 代码卫生问题 | ⭐ |
| 6 | 界面仅英文 / 俄文 | 中文用户要自己改 | ⭐ |
| 7 | 只列出申请了 `INTERNET` 权限的 App | 正常 | — |

### 关于 MTU 8500 的判断

TUN MTU 8500 **不会**主动造成分片（TikTok 的 QUIC 负载本来就 ≤1472）。
真正的分片风险在 **Mac 侧**：QUIC → SOCKS5 UDP(+10B) → 节点协议（若节点是 Hysteria2/TUIC 等 QUIC-based，再 +30~50B）≈ 1520+ > 1500。
**这个风险 CMFA 配置（mtu 1400）能规避一部分，但 SocksTun 改不了 MTU。**
→ 若实测视频卡顿/时播时不播，**解法在 Mac 侧**：给 Mac 的 Clash 换非 QUIC-based 的节点，或在 Mac 上降低出站 MTU。

---

## 五、结论与两条路

### 路线 A：直接用（推荐）

装 SocksTun → 填 `192.168.2.25:7897` → 不勾 "UDP relay over TCP" → 保持 "Remote DNS" 勾选 → 选 per-App 模式勾 TikTok → 启动。

三条要求全部满足，MIT 许可，活跃维护（8.0 / 2026-07），有快捷磁贴和开机自启。

### 路线 B：fork 改造（MIT 允许闭源分发）

从 `heiher/sockstun` fork，需要改的话：

| 想改什么 | 改哪 |
|---|---|
| MTU 可调 | `Preferences.getTunnelMtu()` + `main.xml` 加输入框 |
| 中文界面 | `app/src/main/res/values-zh-rCN/strings.xml` |
| 升级 targetSdk | `app/build.gradle`（targetSdk 34 → 35/36） |
| Compose 化 | 全部 UI（`MainActivity` / `AppListActivity`） |

构建：`git clone --recursive` → `./gradlew assembleDebug`（需要 NDK 29.0.14206865 + JDK 17）。

---

## 六、与 CMFA 方案的对比

| | CMFA（Clash Meta for Android） | SocksTun |
|---|---|---|
| 许可 | GPL-3.0 | **MIT** |
| 内核 | mihomo（Go，~20MB/ABI） | hev-socks5-tunnel（C，几百 KB） |
| 域名保留 | fake-ip + sniffer（需要 `force-domain` 等配置调教） | **Remote DNS 默认开，mapdns 自动回查** |
| MTU | **可调** | 硬编码 8500 |
| 配置方式 | yaml | 图形界面 |
| 分流能力 | 完整规则集 / 分组 | **无**，全部交给对端 |

> **关键差异**：CMFA 是「客户端 + 规则引擎」，规则跑在**手机上**，最终出口由手机侧的策略组决定；
> SocksTun 是**纯转发器**，不做任何判断，全部交给 **Mac 上的 Clash** 决定。
> 对你的场景（Mac 已有完整 Clash）来说，**SocksTun 更干净**——它不会在手机上引入第二套规则导致的冲突
> （你之前踩的「Mac 侧代理组选错」那个坑，正是两套规则互相干扰的典型症状）。
