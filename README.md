# Mini 3 Pilot

面向 **DJI Mini 3 + RC-N1（RC231）+ Android 手机** 的第三方控制端。基于用户提供的 Sky Patrol 工程调整，复用其图传视图，界面参考 DJI Fly 的横屏信息布局。

## 功能

- DJI Mobile SDK V5.18.0 初始化、联网注册、USB 连接提示与注册重试。
- 主相机实时图传、拍照、录像启停、拍照/录像模式切换。
- 电量、GNSS 卫星数、相对高度、水平速度、位置、距返航点距离和机头方位。
- 起飞、返航、降落及取消返航/降落；每次操作需在手机确认，未知或过期状态会阻止新飞行指令。
- 连接诊断与版本页。未连接时显示空值，不使用模拟遥测。

### 1.0.2 高度与拍摄修订

高度与位置改用官方三维位置接口；高度不依赖有效 GPS 经纬度。拍摄前读取录像状态，确认模式设置成功后发送指令。连接诊断显示 SD 卡状态，并保留上次指令错误。

已通过 10 项单元测试，并在 vivo X90s / Android 16 上完成安装、启动和 DJI 注册。高度显示及拍照录像修订仍待接入飞机复测。

### 1.0.1 遥测修正

关键遥测使用每秒一次的异步硬件读取，并保留变化监听。即使飞机静止、读数不变，也能通过成功的读取确认数据仍然有效。高度、电量、飞行状态、定位和返航点分别记录数据时间；某一项失败时，提示具体缺失项目，读取错误可在“连接诊断”中查看。过期数据不能为飞行指令提供授权。

此版本不包含 DJI Fly 的账号、固件升级、完整地图、相册下载、全套相机参数或智能拍摄功能。没有把原企业机的 V3 航线任务、热成像或 QGC 自动控制带入 Mini 3 控制端。

## 构建

Android Studio 中打开本目录，使用 JDK 17 或 21、Android SDK Platform 35 和 Build Tools 35.0.0。

根目录 `local.properties` 中配置：

```properties
sdk.dir=C:/你的路径/Android/Sdk
DJI_APP_KEY=DJI开发者后台中匹配本包名的AppKey
```

应用包名固定为 `com.skypatrol.groundstation`。DJI 开发者后台的 **Package Name 必须完全一致**。`local.properties` 已加入忽略规则，请勿公开其中的 Key 或本机路径。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

APK 生成于 `app/build/outputs/apk/debug/app-debug.apk`。
可选本机配置 `DEBUG_KEYSTORE=...` 用于沿用原调试签名；普通开发环境不设置时使用 Android 默认调试签名。

## 连接

1. 开启 Mini 3 和 RC-N1，确认两者已对频。
2. 用遥控器顶部手机接口连接手机，关闭占用 USB 的 DJI Fly。
3. USB 应用选择 Mini 3 Pilot，打开应用后点击“连接飞行器”。
4. 同意连接提示并授予 SDK 所需权限，首次注册保持手机联网。
5. 顶栏显示已连接 Mini 3 后，检查实时图传与遥测。运行库/注册失败会显示错误说明。

## 启动修复

`PilotApplication` 仅加载 DJI 的 `com.cySdkyc.clx.Helper`，不提前声明 DJI 监听器或 SDK 回调类型。保护库安装后，通过反射加载 `DjiMini3Session`，该类统一使用 V5 接口。Manifest 显式启用原生库提取，构建保留 JNI 符号。避免把之前异常中的 V4 `DJISDKManager` 初始化代码重新混入工程。

## 验证范围

构建、单元测试、安装及真机结果以同目录交付的验证说明为准。起飞/返航/降落代码不等于已经完成实飞验证；应在确认飞机状态及环境适合后，由操作者进行受控验证。
