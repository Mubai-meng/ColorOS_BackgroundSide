# ColorOS 小窗后台挂机

Zygisk 模块（基于 [AndroidVMTools](https://github.com/aerath-stuff/AndroidVMTools) / PanamaPort 注入 `system_server`），
在 ColorOS 上恢复旧版 realme 的“小窗缩成边缘气泡后后台挂机”：应用保持前台、亮屏状态继续运行。

本分支在原项目基础上完成了 **ColorOS 17 / Android 17 适配**与性能、续航、稳定性优化，
当前版本 `v0.2.2`（versionCode 10），模块作者为原作者 `People-11` 与本分支维护者 `Mubai-meng`。

## 效果

- 小窗最小化成边缘气泡（菜单、标题栏、手势、打开别的小窗时自动收起，任何方式都算）即挂机：
  应用被移到自己的保活虚拟屏上继续运行（RESUMED、可见，不暂停、不停止）。
- 挂机期间降到 **10 fps** 省电（不静音，系统可单独调应用音量）；主屏灭屏后虚拟屏不休眠；没有超时退出。
- 退出挂机：点击气泡恢复成小窗；从桌面或最近任务打开该应用；关掉气泡；划掉任务。

## 适配与验证状态

| 环境 | 状态 |
| --- | --- |
| ColorOS 16 / Android 16（上游原项目） | 原始支持 |
| ColorOS 17 / Android 17（本分支） | 已适配，真机日志验证：挂机/恢复/全屏按钮/降帧/虚拟屏释放均正常 |
| 架构 | arm64（`arm64-v8a`） |
| Root / Zygisk | Magisk 自带 Zygisk，或 KernelSU / APatch + Zygisk Next |

ColorOS 17 上系统已删除该功能的入口和状态逻辑，但底层 Mirage 虚拟屏
（`OplusMirageWindowManager`，castMode 7 保活屏，GameBounce 也用它）仍在，本模块直接复用。

> `custom` 分支另有个人小窗行为改动（`CustomHooks`），未合入 `main`。

## 实现

模块通过 PanamaPort 在 `system_server` 内挂钩 13 处方法，每条 hook 独立容错（某条失败只记日志，不影响其余）。
相关反射成员、服务代理、类对象全部缓存，解析放在 WM 全局锁之外，热路径上无字符串分配。

| Hook（`system_server`） | 作用 |
| --- | --- |
| `ExitFlexibleTaskToFloatStrategy.handleEvent` | 任何最小化的起点：为该任务创建虚拟屏 |
| `TaskFragmentExtImpl.shouldSkipPauseInRecentsAnim` | 最小化动画期间不暂停 |
| `FlexibleTaskController.exitFlexibleTask`（7 参实现） | 原生最小化收尾时，在同一个推迟可见性区间内把任务移到虚拟屏；保留小窗状态，焦点留在主屏 |
| `OplusMirageKeepAlivePolicy.shouldSleep` | 我们的虚拟屏不随主屏休眠 |
| `DisplayContent.computeScreenConfiguration` | 虚拟屏的触屏/色彩模式与主屏一致，避免 Chrome 等重建 Activity |
| `FloatHandleController.startActivityByFloatInfo` | 点气泡：原生恢复把任务移回主屏，随后解除挂机 |
| `OplusFlexibleInfoManager.isSkipRecordSnapshot` | 恢复动画不盖最小化时的旧快照（否则画面冻结 1~2 秒） |
| `FlexibleFloatHandleAnimationSpec.defaultCallAnimationStart` | 恢复动画第一帧裁空动画层，避免挂机应用画面在气泡下方露出一帧 |
| `ActivityStarter.execute` / `ActivityTaskSupervisor.startActivityFromRecents` | 从桌面/最近任务打开：启动前先移回主屏，原生启动动画才正常 |
| `FloatHandleController.removeFloatHandleInner`（3 参） | 气泡被关掉：解除挂机。ColorOS 17 原 `dispatchViewRemoved` 已被内联删除，改 hook 此稳定入口 |
| `ActivityTaskManagerService.setLastResumedActivityUncheckLocked` | 降帧是按 uid 生效的，会连带压低同一应用在别处的界面（如 Chrome 的 Custom Tab）；该应用在别的屏成为顶层 resumed 时临时抬掉帧率上限，回到虚拟屏再恢复 |
| `TaskChangeNotificationController.notifyTaskRemoved` | 任务被移除：解除挂机 |

从桌面或最近任务启动时会先把任务移回主屏并隐藏其 activity（避免恢复首帧在气泡位置闪一下旧画面），
并在移动前后清除 binder identity，让 WM 按调用者真实身份判定；已自行离开虚拟屏的任务不再重复搬回，
否则会把正在主屏使用的小窗再隐藏一次。解除挂机前会清理 Mirage 任务表中已经离开虚拟屏的任务，
避免系统把正在主屏使用的小窗误判为“要搬回主屏”而隐藏/失焦（这会导致点全屏按钮没反应）。降帧使用 SurfaceFlinger 按 uid 帧率覆盖后门（transaction 1039，按 uid 引用计数，
恢复后成对释放）；GameManager 的覆盖会被 1035 强制刷新率忽略，1039 不会。

## 安装

1. 手机需 root，并启用 Zygisk（Magisk 自带或 Zygisk Next）。
2. 在 RELEASE 下载 `ColorOS_BackgroundSide.zip`，或从源码自行构建。
3. 在 Magisk / KernelSU / APatch 管理器中刷入，重启。
4. 模块 id 为 `coloros_background_side`，覆盖安装不会留下多份。

## 卸载

在管理器中删除模块并**重启手机**。模块只在 `/data/adb/modules/coloros_background_side/` 下放文件，
不写系统属性、不写设置、不安装 companion APK；虚拟屏、任务归属、帧率覆盖都是进程内存态，重启即清空。
如果只软重启 `system_server` 而未重启手机，SurfaceFlinger 中的帧率覆盖可能仍残留到该应用所有 layer 销毁。

## 构建

需要 JDK 21、Android SDK Platform 36、Gradle 8.14.3（CI 使用），并递归拉取子模块：

```bash
git clone --recurse-submodules https://github.com/Mubai-meng/ColorOS_BackgroundSide.git
cd ColorOS_BackgroundSide
gradle --no-daemon :module:assembleRelease
```

产物：`module/build/outputs/magisk/release/ColorOS_BackgroundSide.zip`。
GitHub Actions（`.github/workflows/build.yml`）每次构建会先把 `external/AndroidVMTools` 子模块更新到上游最新版本。

## 日志与排障

```bash
adb logcat -d | grep -E "BgSide|should belong to display"
```

| 日志 | 含义 |
| --- | --- |
| `hook failed: xxx` | 对应 hook 在当前系统上不兼容；其余 hook 仍工作 |
| `display 10000: dropped N task(s) that already left` | 恢复时已清掉 Mirage 任务表残留，全屏按钮应可点 |
| `frameRateOverrides=[{uid=... frameRateHz=10.0}]` | 降帧生效；恢复后应变回 `frameRateOverrides=[]` |
| `SurfaceFlinger rejected frame rate override` | 帧率覆盖被拒，挂机会跑满帧耗电 |
| `does not own the focus after restore` | 恢复期间焦点由动画接管，属正常诊断信息。`focused task` 现在能真正取到值，若恒为 `-1` 说明取焦点的路径又断了 |
| `hook failed: lift cap when app in front elsewhere` | 帧率抬升 hook 不兼容；挂机本身仍工作，只是别处的同 uid 界面可能仍被限到 10 fps |

恢复瞬间若看到 `InputDispatcher: ... should belong to display`，说明窗口输入归属没跟上，点击会被丢弃。

## 已知限制

- PanamaPort 通过修改 ArtMethod 入口实现注入，能否在新版 ART 上运行需真机验证，旧 ROM 不保证可用。
- 恢复时主屏可能触发 60↔120 Hz 面板重配（触摸升频 + 系统动画两次请求），产生短暂闪屏；
  两个请求均非模块发出，固定刷新率可用来验证该现象。
- 其它模块 hook 同一批柔性窗口方法可能互相干扰（例如 ShortX）；出现行为不一致时建议先关闭对照。
- 本分支只验证过 ColorOS 17 / Android 17，其它机型与版本请按日志排查。
