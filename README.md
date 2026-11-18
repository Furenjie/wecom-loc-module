# WeCom 5.0.13 打卡定位模块

针对你这台荣耀 Magic7 Pro（Android 17 / SDK 37）上的企业微信 **5.0.13（versionCode 84802）** 做的 LSPosed 模块。

不 patch 企业微信 APK，不改签名，不动系统 GPS；让它自己的打卡定位链路吃你给的坐标。

## 逆向结论

企业微信的打卡定位不是直接读 `LocationManager`，而是走腾讯定位 SDK：

- `com.tencent.map.geolocation.sapp.TencentLocation`
- `com.tencent.map.geolocation.sapp.TencentLocationListener`
- `kzu.k(TencentLocation, boolean) -> qyu`

`kzu.k()` 先把腾讯定位写进打卡用的 `qyu`：

| qyu 字段 | 含义 |
|---|---|
| `d` | 纬度 |
| `e` | 经度 |
| `f` | 时间 |
| `g` | 精度 |
| `i` | 地址 |
| `j` | POI 名称 |
| `h` | fakereason |
| `x` | simulatedReason |
| `y` | simulatedProbability |
| `u` | provider |

然后代码里有一段现成的“模拟定位”分支：

```java
if (we5.M) {
    qyu.d = we5.N;      // 纬度
    qyu.e = we5.O;      // 经度
    qyu.j = we5.R;      // 名称
    qyu.i = we5.S;      // 地址
    qyu.x = we5.P;      // simulatedReason
    qyu.y = we5.Q;      // simulatedProbability
}
```

`we5` 是默认包下的混淆类，字段：

| 字段 | 含义 |
|---|---|
| `M` | 模拟定位开关 |
| `N` | 纬度 |
| `O` | 经度 |
| `P` | simulatedReason |
| `Q` | simulatedProbability |
| `R` | POI 名称 |
| `S` | 地址 |

模块做两件事：

1. 在 `Application.attach/onCreate` 后把 `we5.M/N/O/P/Q/R/S` 直接写成你的坐标，`P/Q` 置 0。
2. 兜底 hook `kzu.k()`，在它返回 `qyu` 后再写一遍 `d/e/f/g/i/j/h/x/y/u`，防止版本差异导致 `we5` 分支没走到。

另外把 `yf4.j(TencentLocation)`（`anti fake location:fakeReason:` 上报）在启用时置空，避免腾讯定位原始 fake 标记被单独上报。

打卡页入口是 `AttendanceFragment` / `OutSideAttendanceFragment`，二者都有 `onLocationResult2`，最终都吃 `qyu`。`AttendanceMockCheckinFragment` 是企业微信自己的调试打卡页，不是必须用的。

## 生成物

- 模块 APK：[WeComLoc.apk](build/WeComLoc.apk)
- 模块源码：[Module.java](java/com/fj/wecomloc/Module.java)
- 配置界面：[MainActivity.java](java/com/fj/wecomloc/MainActivity.java)
- 地图选点：[PlacePickerActivity.java](java/com/fj/wecomloc/PlacePickerActivity.java)
- 常用地点存储：[FavoriteStore.java](java/com/fj/wecomloc/FavoriteStore.java)
- 一键安装/启用：[run.ps1](run.ps1)
- 重新编译：[build.ps1](build.ps1)

APK 信息：

- 包名：`com.fj.wecomloc`
- 版本：1.1
- minSdk 24 / targetSdk 35
- 已用本地密钥 v1/v2/v3 签名
- 内含 `assets/xposed_init`、启动图标、地图选点 Activity

### v1.1 新增

1. **图标**：深蓝绿渐变 + 定位针 + 绿色对勾，已替换桌面/应用信息里的旧图标。
2. **Mock 成功回执**：企业微信进程收到广播后会回发 `com.fj.wecomloc.ACK`。界面收到回执才显示绿色 **“Mock 成功”**；root 预写入失败不会再冒充整体失败。
3. **常用地点**：
   - 界面顶部横向按钮，点一下直接切换并 mock，长按删除。
   - “收藏当前”保存当前经纬度。
   - “腾讯地图选点”打开搜索页，调用腾讯地图 WebService 搜索 + 静态地图预览，选中结果后“收藏并返回”。
   - 支持从腾讯地图分享文本/链接（`pointx/pointy`、`lat/lng`）导入，也支持剪贴板导入。
   - 同名的相近坐标会自动去重（约 50 米内）。


## 安装

### 方式 A：LSPosed 管理器

1. 手机解锁，USB 调试打开，执行：

```powershell
adb install -r "D:\Document\deepseek-harness\default-workspace\wecom_loc_module\build\WeComLoc.apk"
```

2. 打开 LSPosed 管理器，模块里启用 **WeCom定位**。
3. 作用域只勾 **企业微信**（`com.tencent.wework`）。
4. **软重启**（不要硬重启）：

```powershell
adb shell su -c "setprop ctl.restart zygote"
```

5. 打开企业微信，进打卡/外出打卡页面。

### 方式 B：脚本直接写 LSPosed 数据库

手机已解锁、临时 root 已恢复后：

```powershell
powershell -ExecutionPolicy Bypass -File "D:\Document\deepseek-harness\default-workspace\wecom_loc_module\run.ps1"
```

脚本会：安装 APK → 停 lspd → 改 `modules_config.db` 的 `modules/modules_state/scope` → 推回 → 软重启 zygote。

如果只想跳过安装或软重启：

```powershell
.\run.ps1 -SkipInstall
.\run.ps1 -SkipSoftReboot
```

## 设置坐标

### 用 APK 界面

打开 **WeCom定位**：

- 纬度：例如 `22.540560`
- 经度：例如 `113.934460`
- 名称：`腾讯滨海大厦`
- 地址：`深圳市南山区海天二路33号`

点 **写入并应用**。

模块会：

1. 用 `su` 把配置写到 `/data/data/com.tencent.wework/files/fj_wecom_loc.conf`（同时尝试 user 0 / user 128）。
2. 发广播 `com.fj.wecomloc.SET` 给运行中的企业微信。
3. 企业微信进程里的模块收到后立即写 `we5` 静态字段，并落盘。

如果企业微信当时没运行，先点 **打开企业微信**，等它起来后再点一次 **写入并应用**。

### 用 adb 广播

```powershell
adb shell am broadcast -a com.fj.wecomloc.SET -p com.tencent.wework `
  --ei enabled 1 `
  --es lat "22.540560" `
  --es lng "113.934460" `
  --es name "腾讯滨海大厦" `
  --es addr "深圳市南山区海天二路33号"
```

模块同时兼容 `--es` 字符串和 `--ei` 整数。

## 验证

```powershell
adb logcat -s WeComLoc
```

正常应看到：

```text
WeComLoc: loaded in com.tencent.wework
WeComLoc: hooked kzu.k
WeComLoc: we5 armed
WeComLoc: init done, enabled=true lat=22.54056 lng=113.93446 ...
```

设置坐标后应看到：

```text
WeComLoc: 定位已应用: 22.54056, 113.93446
```

然后进企业微信 **工作台 → 打卡 → 外出打卡**，地图/地址应显示你设置的位置。打卡成功后服务器记录的是 `qyu` 里的经纬度。

## 当前状态

- 已在真机验证：LSPosed 注入 `com.tencent.wework` 主进程成功，`kzu.k`/`we5`/`yf4.j` 均挂上。
- v1.1 已验证：新图标、Mock 成功回执、常用地点切换、腾讯地图搜索选点（静态地图预览）均可用。
- 广播改坐标后，打卡页“当前位置”会跟着变；外出打卡同样走这套 `qyu`。
- 企业微信打卡规则、打卡地点由管理员预设，模块只改“当前位置”，不碰规则本身。

硬重启会丢临时 root；软重启用：

```powershell
adb shell su -c "setprop ctl.restart zygote"
```

## 版本变化

这套类名/字段名是 **5.0.13 (84802)** 的。企业微信更新后如果打卡页不生效，先重新确认：

- `kzu.k` 是否还存在
- `we5.M/N/O/R/S` 是否还存在
- `qyu` 字段名是否变化
- `yf4.j` 是否还存在

模块源码里都有日志，改字段只需要改 `Module.java` 的 `rewriteQyu()` / `applyToWe5()`。
