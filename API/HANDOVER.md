# 交接文档 · 安卓平台网易云音乐第三方 App

> 交接给下一位 agent / 开发者。读完这一份就能直接接手，不用回头翻对话。
> 最后更新：2026-10-04 17:50

---

## 1. 项目目标

做一款 **Android 平台的网易云音乐第三方客户端**。
用户明确要求：**没有服务器，端上直连**（不要自建后端/中间层）。

当前进度：**技术可行性已完整验证，Rust 原生库已编译成 Android .so。Android 端代码尚未开始。**

---

## 2. 状态总览

| 阶段 | 状态 |
|---|---|
| 选型调研（GitHub API 项目） | ✅ 完成 |
| 选库决策 | ✅ 完成（ncm-api-rs） |
| 桌面端编译 + 接口实测 | ✅ 完成 |
| 扫码登录实测 | ✅ 完成 |
| 播放链接实测（含无损） | ✅ 完成 |
| 交叉编译 Android .so | ✅ 完成（arm64-v8a + x86_64） |
| JNI桥接层 | ⬜ 未开始（下一步） |
| Gradle 工程骨架 | ⬜ 未开始 |
| Compose UI + 播放器 | ⬜ 未开始 |

---

## 3. 选型决策（重要，别推翻重来）

最终选定：**SPlayer-Dev/ncm-api-rs**（MIT→实际 **WTFPL** 协议，无传染）

已 clone 到 `ncm-api-rs/`，382 个接口文件，weapi/eapi/linuxapi 加密齐全。

### 为什么选它

| 项目 | 结论 |
|---|---|
| **ncm-api-rs**（58⭐, 2026-04） | ✅ **选定**。纯 Rust（reqwest+rustls+纯 Rust crypto），**零 C 依赖**，cargo-ndk 直出 .so，WTFPL 无协议风险 |
| gmg137/netease-cloud-music-api | ❌ 依赖 isahc（底层 libcurl）+ openssl，**双重 C 依赖**；**GPLv3 传染**；仅~25 接口。价值仅在 `src/encrypt.rs` 可作加密参考 |
| chaunsin/netease-cloud-music | ❌ 是 **CLI 工具套件 ncmctl**（刷歌/签到/云盘），不是播放器 API 库；Go 进 Android 需 gomobile（官方 experimental，且 AAR 无法设 minSdkVersion，issue #27023 至今未解）。价值仅在 `api/` 分层与 `pkg/crypto`（含 XEAPI）参考 |

**关键上游信息**：ncm-api-rs 移植自 **NeteaseCloudMusicApiEnhanced/api-enhanced**（1912⭐, MIT, 仍在活跃更新）。
注意：**不要再用 Binaryify/NeteaseCloudMusicApi 原版**——它最后提交停在 2024-02-28，已废弃两年。

---

## 4. 环境（已全部核实可用）

| 项 | 值 |
|---|---|
| Rust | 1.97.1 |
| cargo-ndk | 4.1.2（已装） |
| Android NDK | `D:\Android\Sdk\ndk\28.2.13676358`（另有27.0 / 30.0，**用 28.2**） |
| SDK 根目录 | `D:\Android\Sdk` |
| cmake | `D:\Android\Sdk\cmake\3.22.1\bin`（**不在 PATH，需手动加**） |
| Java | 25.0.3 LTS |
| rust target | `aarch64-linux-android`、`x86_64-linux-android`（均已装） |

⚠️ **`ANDROID_HOME` 环境变量未设置**，所以脚本里查不到 SDK。**不要据此以为 NDK 没装**——NDK 一直在。编译时需手动 export：

```bash
export ANDROID_NDK_HOME="D:/Android/Sdk/ndk/28.2.13676358"
export PATH="/d/Android/Sdk/cmake/3.22.1/bin:$PATH"
```

编译命令：
```bash
cd ncm-android
cargo ndk -t arm64-v8a build --release   # 真机，约 3m46s（冷编译）
cargo ndk -t x86_64  build --release   # 模拟器，约 2m45s
```

---

## 5. 工程结构

```
2026-10-03-21-56-59/
├── HANDOVER.md              ← 你正在读的
├── ncm-api-rs/              ← 上游第三方库（clone，勿大改）
│   ├── src/api/*.rs         ← 382 个接口，一文件一接口
│   ├── examples/            ← 我加的验证程序（见下）
│   ├── cookie.txt           ← ⚠️ 用户真实凭证，已 gitignore
│   └── *.log, qr*.html, login_qr.svg  ← 临时产物，已 gitignore
└── ncm-android/             ← 我建的桥接层（主要产出）
    ├── Cargo.toml
    ├── src/lib.rs           ← 导出 C ABI
    └── jniLibs/
        ├── arm64-v8a/libncm_android.so   (2.5 MB)
        └── x86_64/libncm_android.so      (2.8 MB)
```

**为什么要单独建 `ncm-android` 而不直接改上游**：桥接代码（JNI、tokio runtime、cookie 管理）会频繁变动，跟上游混一起以后无法干净同步上游更新。依赖方向是 `ncm-android → ncm-api-rs`（path 依赖）。

---

## 6. 关键文件

### `ncm-android/Cargo.toml`
```toml
[lib]
name = "ncm_android"
crate-type = ["cdylib", "rlib"]

[profile.release]
opt-level = "z"   # 体积优先
lto = true
codegen-units = 1
strip = true
panic = "abort"   # 避免 unwind 跨 FFI 炸掉 JVM
```

### `ncm-android/src/lib.rs` 导出的 C ABI

```c
ncm_version()                        // 库版本字符串
ncm_set_cookie(ptr, len) -> i32      // 设置登录态，0成功
ncm_search(ptr, len) -> *mut c_char  // 搜索歌曲，返回 JSON
ncm_free(ptr)                        // 释放（必须调！）
ncm_selftest() -> i32                // 连通性自检
```

**内存约定**：所有返回的 `*mut c_char` 必须由调用方用 `ncm_free` 释放，否则内存泄漏。

**实现要点**（改动时别破坏）：
- 全局 `Lazy<tokio::Runtime>`（multi_thread），用 `block_on` 把异步拉平成同步调用以适配 JNI
- 全局 `Lazy<Mutex<ApiClient>>` 持 cookie；请求前先 `.clone()` 出 client 再 `block_on`，**绝不在 await 期间持锁**

### 上游 API 调用方式
```rust
let client = create_client(Some(cookie));   // cookie: Option<String>
let q = Query::new().param("keywords", "起风了").param("type","1").param("limit","10");
let resp = client.cloudsearch(&q).await?;  // resp.body 是 serde_json::Value
client.song_url_v1(&Query::new().param("id","1330348068").param("level","lossless")).await?;
```

### 我加的验证程序（在 `ncm-api-rs/examples/`）
- `basic.rs`（上游自带）— 搜索/详情/歌词/播放链接
- `probe.rs` — 登录链路探测
- `qrlogin.rs` — 扫码登录 + 终端二维码
- `diag.rs` — **最有用的**：登录 + 存 cookie + 多歌曲多音质实测。cookie 存 `cookie.txt`，重跑直接复用不用再扫码

跑法：`cd ncm-api-rs && cargo run --example diag`

---

## 7. 已验证结论（真实实测数据，非推测）

### 免登录可用
`cloudsearch` / `song_detail` / `lyric` 正常返回。

### 必须登录才能播放
- `song_url_v1` 免登录返回空 URL
- `register_anonimous`（匿名注册）**已失效**，返回 code 400 → **App 必须做登录流程**

### 扫码登录链路完整可用
`login_qr_key`（取 unikey）→ `login_qr_create`（**本地纯生成，不联网**，返回 `qrimg` 为空字符串）→ 轮询 `login_qr_check`（801 等扫码/ 802 待确认 / 803 成功 / 800 过期）

⚠️ `login_qr_create` **不返回二维码图片**，只给 URL：`https://music.163.com/login?codekey={key}`。**二维码需App 端自行渲染**（ZXing）。这反而是好事，不依赖服务端出图。

⚠️ **unikey 从生成那刻开始倒计时，约 3 分钟**，必须给用户留足扫码时间（调试时踩过这个坑）。

### 播放链接实测（登录态）

| 歌曲 | id | standard | exhigh | lossless |
|---|---|---|---|---|
| 起风了 | 1330348068 | 128kbps ✓ | 320kbps ✓ | **986kbps FLAC** ✓ |
| 孤勇者 | 1901371647 | 128kbps ✓ | — | — |
| 海阔天空 | 1357375695 | 128kbps ✓ | — | — |

**无损 FLAC 能拿到**，会员权益可透传。URL 是标准 `m801/m701.music.126.net` CDN 地址，ExoPlayer 直接喂即可播。

### .so 产物验证
| 检查项 | arm64-v8a | x86_64 |
|---|---|---|
| 架构 | ELF64 / AArch64 | ELF64 / x86-64 |
| 16KB 页对齐 | ✅ `0x4000` | ✅ |
| 导出符号 | 5 个全在 | ✅ |

NDK 28 默认就是 16KB 对准，已符合 Android 15+ / Google Play 要求，**无需额外链接参数**。

---

## 8. 踩过的坑（别再踩）

1. **周杰伦的歌在网易云没有版权**！用 `186016`（晴天）测播放链接必然返回空 URL。**测播放链接务必先用 `cloudsearch` 取真实曲库 id**。这个错误我犯过，被用户当场指出。

2. **二维码 3 分钟过期**，且从我生成到用户看到有延迟。调试时留足窗口，或延长轮询次数。

3. **`ANDROID_HOME` 未设置**，导致误判 NDK 不存在。

4. **cmake 不在 PATH**，SDK 内有 `D:\Android\Sdk\cmake\3.22.1\bin`，编译时需手动加（rustls/aws-lc-rs 链可能用到）。

5. **cookie 没保存会白扫一次码**。`diag.rs` 已改为持久化到 `cookie.txt`并自动复用。

---

## 9. 下一步任务（建议顺序）

### 步骤 1：JNI 桥接（最先做，风险最高）
现在底层是 C ABI，需要写 JNI 暴露给 Kotlin。两条路：
- **推荐**：用 `jni` crate 手写 JNI 包装现有 C 函数。依赖少、可控、行为可预测
- 备选：改用 uniffi-rs 自动生成 Kotlin 绑定（需重构 lib.rs 加 UDL，自动化程度高但引入新依赖）

同时把 `ncm-android/src/lib.rs` 里**尚未导出**的能力补上——目前只有 `search`，但 UI 至少还需要：
`login_qr_key` / `login_qr_create` / `login_qr_check` / `song_url_v1` / `song_detail` / `lyric` / `user_playlist`

### 步骤 2：最小可运行验证（务必在写 UI 之前）
搭一个只有「搜索框 + 结果列表」的 Activity，验证三件事：
1. `System.loadLibrary("ncm_android")` 成功
2. cookie 能恢复
3. 搜索能出结果

**这一步是整条链路最容易出岔子的地方**（ABI 兼容性、.so 加载），早暴露远好过晚暴露。

### 步骤 3：Gradle 工程 + Compose UI
- Kotlin + Compose + Material 3
- 播放器用 **Media3/ExoPlayer**（参考 `wangchenyan/ponymusic`，2853⭐，Kotlin + Media3 + ExoPlayer，是目前最值得学的 Android 第三方客户端实现）
- 把 `ncm-android/jniLibs/` 挪进 `app/src/main/jniLibs/`
- `.so` 编译接入 Gradle：加一个 task 跑 `cargo ndk build --release` 并复制产物

### 步骤 4：登录 + 凭据存储
- 二维码用 **ZXing** 渲染，轮询 `login_qr_check`
- cookie 存**`EncryptedSharedPreferences`**，**绝不能用普通 SharedPrefs**
- 处理 cookie 过期需重新扫码

---

## 10. 安全与合规（务必遵守）

🔴 **`ncm-api-rs/cookie.txt` 里是用户真实账号凭证**
- 已在 `.gitignore` 中（连同 `login_qr.svg`、`qr*.html`、`*.log`）
- **绝对不要提交到任何 git 仓库或云端**

⚠️ **合规红线**
- 所有接口均为逆向所得，**仅供个人学习研究**，不得商用
- **不要**做会员破解、批量下载、刷歌挂机等功能
- 网易云风控严格（参考 chaunsin 项目作者自曝：刷歌有较高封号风险）
- 曲库有版权限制（周杰伦等歌手在网易云无版权，属正常现象）
- 若将来要公开发布 APK，需重新评估协议（WTFPL 无传染风险，但逆向接口本身有法律风险）

---

## 11. 相关项目速查

| 用途 | 项目 | 备注 |
|---|---|---|
| API 库（已在用） | SPlayer-Dev/ncm-api-rs | 58⭐, WTFPL, 2026-04 |
| 上游（Node 版参考） | NeteaseCloudMusicApiEnhanced/api-enhanced | 1912⭐, MIT, 活跃 |
| Android 客户端参考 | wangchenyan/ponymusic | 2853⭐, Kotlin + Media3 |
| Android 客户端参考 | rcmiku/JetMelo | 144⭐, Compose + M3, **GPL-3.0 慎用** |
| 加密实现参考 | gmg137/.../src/encrypt.rs | 含 weapi/eapi/linuxapi/xeapi |
| XEAPI 参考 | chaunsin/.../pkg/crypto | 较新的加密方式 |

详细调研记录见 `.workbuddy/memory/2026-10-03.md` 与 `2026-10-04.md`。
