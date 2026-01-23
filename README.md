# Nichirin

## 项目简介
Nichirin 是一个基于 Jetpack Compose 和 Material3 的 Android BLE 工具，它围绕华为/小米等通用的 FFE0/FFE1/FFE2 服务构建，向蓝牙设备以 400Hz 的 12 带能量谱数据发送帧，同时支持使用麦克风或本地音频文件生成波形。主界面集合了扫描、连接、调试与文件选择功能，目标是为需要实时频谱传输的外设提供可视化与控制入口。

## 主要功能
- 对 BLE 设备进行扫描（可选只显示 FFE0 服务）并建立 GATT 连接。
- 自动启用 FFE2 通知并向 FFE1 特征写入 16 字节帧，帧内容固定了地址和函数码，与 CRC16 校验码组合成 400Hz 刷新率的动态谱数据。
- 提供三种 400Hz 数据源：测试锯齿、实时麦克风、已选音频文件，切换时会自动停止当前流和谱分析器。
- 内置麦克风（MicSpectrumEngine）和文件（FileSpectrumEngine）频谱处理器，生成 12 带幅度数据，并支持自定义采样率、块大小与地板/动态范围。
- 通过动态快捷方式（watching_1/2/3）保持常驻入口，使用 `ForegroundKeepAliveService` 保持 TX 运行时的前台服务。
- Debug 模式下显示所有扫描设备详细信息、状态文本与 RSSI，方便调试硬件。

## 使用指南
1. `minSdk 31`，需要在支持的 Android 实机/模拟器上运行并打开蓝牙。
2. 第一次启动会请求 `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`、`RECORD_AUDIO` 权限；没有权限时会提示。
3. 在主界面点击“开始扫描”，选中设备后进入 Detail 页面。
4. 在 Detail 页面可以逐个启用 `Saw`、`Mic`、`File` 模式，并在文件模式下通过文档选择器载入音频。
5. 如果需要自定义 `sampleRate/blockSize/floorDb`，在 Detail 页面输入新的参数并保存。
6. 通过文件进度条可以拖动播放位置，同时可以暂停 TX 或断开连接释放资源。

## 代码生成
- 本项目中的部分代码、说明或文档由 AI 工具辅助生成，开发者在使用时可根据实际需求调整实现细节和风格。

## 权限说明
- `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`：扫描 BLE 设备、建立 GATT 连接并收发数据。
- `RECORD_AUDIO`：启动麦克风频谱引擎，实时采集声音用于驱动 TX 帧。
- 读写文件（Storage）通过系统的 `OpenDocument` API 授权处理音频。

## 架构概览
- `MainActivity`：单 Activity + Compose，集中管理 UI 状态、扫描/连接逻辑、频谱引擎与 400Hz TX。
- `BleConstants/BleProtocol/BleUtils/BleModels`：提取 BLE UUID、帧协议、共用工具与数据模型。
- `MicSpectrumEngine` / `Spectrum12Processor` / `FileSpectrumEngine`：负责 PCM 解码、FFT、带宽映射与回调，最终由 `latestBandsRef` 提供给 TX 线程。
- `ForegroundKeepAliveService`：在 TX 运行时保持前台服务、避免系统过早杀死 BLE 连接。
- Compose UI 通过 `ScanScreen`、`DetailScreen`、`ModeBtn` 等组件组织界面，并结合 `MutableState` 保持状态同步。

## 构建与调试
```
./gradlew lint
./gradlew assembleDebug
./gradlew installDebug   # 需要设备/模拟器
```
- 依赖版本集中在 `gradle/libs.versions.toml`，使用 Compose BOM 保持 UI 组件一致。
- 采用 Java 11 编译，Compose / Kotlin 插件与 AGP 均在 `gradle/libs.versions.toml` 里声明。
- 可在 Android Studio 中打开项目，直接运行 `app` 模块或使用命令行构建。

## 资源与风格
- 主要背景图片 `R.drawable.background` 与图标资源（shortcut、多份 `ic_launcher`）在 `res` 目录，UI 主题在 `ui/theme` 中统一定义颜色与排版。
- Material3 容器、`ElevatedCard`、`Scaffold` 被用来构建层次分明的扫描/详细页。

## 贡献
如果要扩展：
1. 在 `dsp` 包引入更复杂的处理器或支持更多频段。
2. 扩展 BLE 协议（添加新的 Service/Characteristic 或解析 notify 数据）。
3. 补充单元/UI 测试、结合工作流自动打包。

## 关于
Nichirin 是一个面向实验性音频+BLE 硬件的谱线发送器，致力于让开发者在 Android 端可视化采样、调试频谱源，并以 400Hz 频率向目标设备发送 12 带帧数据。
