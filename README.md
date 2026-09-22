# 4MA

4MA 是面向微信 **7MA 出行**小程序的 libxposed API 102 模块。当前 `0.1.12`
是只读诊断版，用来确认小程序所在进程、AppID、页面路由、宿主 Activity、视图类型和
少量业务界面标记，为后续稳定实现体验增强功能提供实机证据。探针会记录 Intent 键名、
AppBrand 对象类型，以及符合微信 AppID、用户名或页面路由格式的值；URL 查询参数会被丢弃。

实机已确认 7MA AppID 为 `wx9a6a1a8407b04c5d`，页面由 XWeb Pinus 渲染。当前版本会在匹配
该 AppID 后，通过 XWeb 执行只读脚本，读取页面路径和固定业务标记；页面点击后的复查最短
间隔为 1.5 秒。脚本不会回传完整页面正文。

当前版本不会自动借车、锁车或还车，也不会修改小程序行为。它不 hook 网络正文，不记录
Token、Cookie、请求头、手机号或普通界面文本。完整车辆编号不会进入报告；带星号的编号
只记录脱敏后的字符形状。已知界面变化“地图车辆编号部分使用星号替代”已纳入探针标记。

## 使用

1. 安装 Release 中的 APK。
2. 在支持 libxposed API 102 的框架中启用 4MA，作用域只勾选 **微信**。
3. 首次启用后强制停止并重新打开微信，再进入 7MA 出行小程序，浏览地图、车辆详情和用车页面。
   从 `0.1.8` 或更新版本覆盖安装时，可先直接测试热重载；导出报告出现 `lastHotReload.*`
   时间戳后无需重启微信。
4. 打开 4MA 应用，点击“导出诊断报告”。若隐藏了桌面图标，从框架的 4MA 模块详情页进入
   “模块设置”。

模块只在 `com.tencent.mm:appbrand*` 子进程安装界面探针。`com.tencent.mm` 主进程仅记录一次
作用域状态，以便判断框架是否正确加载。

## 设置

- **启用诊断采集**：总开关，默认开启。
- **记录视图类名**：增加视图类信息，默认关闭，仍不会保存普通文本。
- **隐藏桌面图标**：隐藏 Launcher 入口；设置 Activity 本身继续保留。
- **导出/清空报告**：配置通过 libxposed RemotePreferences 下发；微信进程经一个只写
  ContentProvider 把脱敏事件保存到模块的私有存储。Provider 会校验调用 UID 属于微信，
  不提供报告读取接口。

模块不创建通知，也未声明通知权限。

## API 102 热重载

模块启用了 `autoHotReload=true`。更新前会取消模块创建的延迟任务并允许框架回收旧代代码；
更新后会移除旧 HookHandle，并在微信主进程或 `appbrand*` 进程中重新安装对应 Hook。
热重载成功时，模块会为相应进程固定保存 `lastHotReload.*` 时间戳。清空普通诊断日志不会删除
这个状态，后续导出报告时仍会显示。首次从不支持热重载的旧版本升级时，需要让微信重新加载
模块一次。

## GitHub Actions

推送到 `main` 或手动运行工作流会构建并上传 Actions artifact。推送 `v*` 标签还会创建
预发布 Release，并附带 APK 与 SHA-256 校验文件。

未配置签名密钥时，工作流使用 Android debug key 生成可安装测试包。为了让以后版本可以直接
覆盖安装，应在首次正式发布前配置以下 Actions secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

`ANDROID_KEYSTORE_BASE64` 是 keystore 文件经过单行 Base64 编码后的内容。签名文件及密码不得
提交到仓库。

## 构建约束

- Android Gradle Plugin 9.2.1
- Gradle 9.5.1
- Java 17
- compileSdk 36 / targetSdk 35
- `io.github.libxposed:api:102.0.0`
- `io.github.libxposed:service:102.0.0`

本仓库不提交 Gradle Wrapper 二进制文件；GitHub Actions 使用 `setup-gradle` 提供固定版本。
libxposed service 102 的 AAR 元数据声明 `minCompileSdk=37`，但 API 37 仍是预览平台；工程使用
AGP 的 `android.experimental.disableCompileSdkChecks=true` 在稳定 API 36 上编译。该设置会放宽
所有依赖的 AAR 元数据检查，实际 API 不兼容仍会在 Java 编译或运行时暴露。

## 下一阶段

诊断报告确认 7MA 的稳定调用边界后，再分阶段实现：跨运营区提示自动继续、用车计时提醒、
还车后按原编号重借，以及地图车辆详情的一键借车入口。任何会产生真实订单的功能都需要
状态校验、幂等保护、冷却时间和失败停止策略。
