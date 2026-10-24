# 手动采集 Baseline Profile

使用带窗口的 Android 模拟器，登录、点击、滚动和播放全部由人操作。
采集器只负责启动应用、记录执行路径和导出规则，不会自动操作页面。
使用 Android API 34 及以上镜像，或可执行 `adb root` 的 Google APIs 镜像；
低于 API 34 的非 root 镜像会被拒绝，避免采集框架通过卸载重装重置编译状态而丢失登录数据。

在仓库根目录执行，Python 环境和 JDK 要求与下方性能测试相同：

```text
python tools/manual_baseline_profile.py build
python tools/manual_baseline_profile.py install
```

打开模拟器中的 Cirro，手动登录并确认能正常加载歌曲。安装使用覆盖安装，
签名不兼容时直接失败，不卸载应用或清除数据。连接的手机不会被选中；
多个模拟器时通过 `--serial` 指定。

然后开始采集：

```text
python tools/manual_baseline_profile.py record
```

等终端显示「采集已开始」后，手动访问常用路径，例如资料库、歌单、滚动歌曲列表、
搜索、播放、展开播放器、歌词和播放队列。采集仅进行一轮，不会重复重启应用；
开始和结束时框架会停止应用进程，保留登录数据。最长等待 30 分钟，超时按失败处理。
操作完成后，在另一个终端执行：

```text
python tools/manual_baseline_profile.py finish
```

等待 `record` 终端成功结束，它会保留原始日志和规则于
`macrobenchmark/build/manual-profile/`，并更新 `app/src/main/baseline-prof.txt`。
只有检测到测试成功和包含应用源符号的非空规则才会更新该文件。
采集失败时先查看 `instrumentation.txt` 的具体报错；若主机进程被强制结束，
确认对应 instrumentation 已停止后再删除该目录中的残留 `session.json`。

`build` 显式传入 `-PbaselineProfile=true`：仅此模式启用 x86_64 原生库，
并关闭 benchmark 的 R8 优化，避免把混淆后的名称当作源符号规则。
日常构建继续使用 ARM64 和既有优化配置。AGP 会在后续正式构建时读取
`app/src/main/baseline-prof.txt`，由 R8 重写并打包；不需要把此参数传给正式构建。
这里生成的是 Baseline Profile，手动浏览的全部页面不会被声明为 Startup Profile。
生成规则不等于已证明性能收益，启动及交互改善仍需在真机验证。

# 喜爱歌曲 TrackRow 性能测试

测试真实的「资料库 → 喜爱歌曲」页面，不替换数据源，不修改 TrackRow 行为。
`benchmark` 变体继承 release 优化，关闭 debuggable，允许 shell 性能分析；Compose tracing
及其 native 跟踪库只加入该变体，避免新系统拒绝从可写目录加载注入的跟踪库。
测试包独立运行 UI Automator，并使用 debug 签名；每个场景预热 3 次，再测量 25 次。
使用 WARM 模式，在正式首轮采样前额外运行一次场景，重新暖好编译后进程的缓存；
每轮重建 Activity 并从资料库入口开始，避免沿用上一轮的滚动位置。

## 真机准备

- 使用同一台真机，解锁并保持亮屏。关闭 HWUI 柱状图、布局检查器和录屏。
- 固定刷新率、亮度、电量模式与播放状态。每组测试前让设备温度回落到相近水平。
- 先登录，打开「资料库 → 喜爱歌曲」，确认歌曲已经加载且足够向下滚动数屏。
- 本组测试使用预热后的进程／图片缓存。首次网络加载需要单独设计实验，不能混入这组对比。

测试构建沿用应用的包名和已配置签名；未配置签名时使用 Android debug 签名。
如果设备上原安装包的签名不一致，安装会失败。不要卸载或清除数据；先使用相同签名构建。
脚本明确关闭不兼容安装包的自动卸载，并设置测试结束后保留安装包。
安装测试构建后仍应确认登录及资料库状态。

## 执行

在仓库根目录运行 Python 脚本。脚本直接使用仓库 Gradle wrapper 的 Java 入口，
无需 shell；需要设置项目使用的 `JAVA_HOME`。

```text
python tools/trackrow_benchmark.py build
python tools/trackrow_benchmark.py run --label baseline-a --scenario open
python tools/trackrow_benchmark.py run --label baseline-a --scenario down
python tools/trackrow_benchmark.py run --label baseline-a --scenario back
```

省略 `--scenario` 会运行三个场景。可用 `--iterations 5` 检查测试流程，正式对比使用默认 25 次。
连接多个设备时用 `--serial` 选择；`--notes` 可以记录刷新率、播放状态和缓存条件。
运行期间不同时执行另一个 Gradle 命令。

## 测量边界

| 场景 | 测量内容 | 测量之前完成 |
| --- | --- | --- |
| openCollection | 点击喜爱歌曲，到歌曲数据就绪，再保留 350ms 覆盖出现动画 | 启动／重建 Activity、进入资料库、等待 500ms |
| scrollDown | 连续 10 次向下拖动，结束后保留 350ms | 进入喜爱歌曲并等待曲目加载 |
| scrollBack | 同样距离向回拖动 | 进入页面并完成一轮下滑 |

每次拖动范围是屏高的 68% 到 30%，避免顶部控件和底部 MiniPlayer／导航栏。
所有迭代保持相同坐标、步数、等待时间；不会执行歌曲点击。
歌曲数据就绪标记通过已有列表的 semantics 导出，不增加中间加载画面。
该标记不是屏幕呈现时间；首帧实际呈现和调度原因需要在 FrameTimeline 中核对。

这里的“回滑”只代表本轮下滑后返回：Lazy 列表可能已释放离屏行，并不保证所有行仍留在组合中。
大量歌曲主要增加滚动期间的新行创建；懒列表不会一次组合整份歌单。
喜爱歌曲原本就关闭每行喜欢星标及其订阅，所以这组结果不能用于证明喜欢订阅优化的收益。

## 报告与对比

每次执行保存到 `macrobenchmark/build/runs/<时间>-<label>/`：

- `run.json`：场景、编译模式、迭代数、Git 状态和测试备注。
- `*benchmarkData.json`：原始逐帧数据及设备信息。
- `*.perfetto-trace`：逐次系统跟踪，包含调度、CPU、帧时间线与 Compose tracing。
- `summary.json`：P50/P95/P99、每次迭代最慢帧的中位数／P95、超时帧比例。

```text
python tools/trackrow_benchmark.py analyze macrobenchmark/build/runs/<某次目录>
```

主要比较 `frameOverrunMs`，并同时看“每次进入最慢帧”的分布，避免首屏尖峰被其它帧平均掉。
`frameOverrunMs > 0` 表示超过截止时间；Android 版本或跟踪条件不支持时可能缺失。
`frameDurationCpuMs` 不等于线程纯 CPU 时间，不能独立据此排除调度因素。

在 Perfetto 中打开最慢与典型迭代，对齐 FrameTimeline、应用主线程、RenderThread、CPU frequency
和 Thread State：长时间 Running 表示实际执行；Runnable 表示等待调度；睡眠／阻塞则查等待源。
展开 TrackRow 的 Compose trace，分清组合、测量布局和绘制；不要把嵌套 trace 的时长直接相加。
帧指标仍包含真实调度影响，不能只删除“看起来异常”的慢帧。温控等明显异常应整组记录并重新测试。

优化前后按 A → B → B → A 分组交替测试，保持同一份歌曲、缓存与播放状态。
这套测试不会锁 CPU 频率或修改系统调度，也不能仅凭一次结果断言性能提升。
Compose tracing 本身会增加一些开销，A/B 必须保持相同配置；最终体验还需用关闭 tracing 的构建复核。
