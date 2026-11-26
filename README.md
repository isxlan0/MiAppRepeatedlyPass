<div align="center">
<h1>MiApp XSpace</h1>

![Version](https://img.shields.io/github/v/release/isxlan0/MiAppRepeatedlyPass?style=flat-square)
![Java](https://img.shields.io/badge/Java-8-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![C++](https://img.shields.io/badge/Native-C%2B%2B17-00599C?style=flat-square&logo=cplusplus&logoColor=white)
![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)
![GitHub Stars](https://img.shields.io/github/stars/isxlan0/MiAppRepeatedlyPass?style=social)

模块开源地址：[https://github.com/isxlan0/MiAppRepeatedlyPass](https://github.com/isxlan0/MiAppRepeatedlyPass)

</div>

# 简介

一款基于 Xposed 框架开发的小米 / HyperOS4 应用多分身模块。
模块额外增加系统分身数量，使系统最多支持 **3 个分身**（系统自带一个，模块提供两个位置），连同主应用共 **4 份**。

使用系统原生分身空间，保留应用原包名和签名，各分身使用独立的用户空间与数据。

# 功能列表

### 模块功能

- [x] 在系统自带分身之外，额外提供两个分身位置
- [x] 可在系统「应用双开」页面创建和管理分身

> 💡 如有功能建议或使用问题，欢迎通过 [GitHub Issues](https://github.com/isxlan0/MiAppRepeatedlyPass/issues) 反馈。

### 模块内页面

* **关于模块**：查看版本、作者、GitHub 源码、致谢、卸载说明和免责声明。
* **使用方式**：从桌面、LSPosed 模块设置或模块操作入口打开，查看关于信息不需要 root。

# 技术栈

### 💻 核心语言与架构

* **Java**：系统 Hook、分身管理和关于界面，按 Java 8 字节码编译。
* **C++17 / ARM64 汇编**：原生桌面角标、名称和 ABI 适配。

### 🎨 界面与交互

* **Android 原生 View**：提供关于页面，支持浅色和深色模式。
* **系统应用双开界面**：在原系统页面中接入额外分身的创建与管理。

### 🛠 Hook 与系统适配

* **Xposed API 82**：接入系统框架、桌面、安全核心组件、手机管家、SystemUI 和设置。
* **LSPosed**：负责 Hook 模块加载与作用域管理。
* **Android 原生 CLONE profile**：为分身提供独立空间与数据，保留应用原包名和签名。

### 📦 构建与运行

* **Python / Android SDK / NDK**：编译 Java、原生代码、签名 APK，并打包安装 ZIP。
* **Magisk / KernelSU 模块结构**：安装 Hook APK、挂载原生库并运行后台服务。
* **Hybrid Mount**：当前原生库挂载流程依赖的外部模块。

# 适配与运行环境

### Android 系统

* **Hook 与模块安装要求**：当前代码使用 SDK 37 系统接口。
* **模块 APK 最低版本**：Android 8.0（API 26），测试和使用均为OS4系统，其他系统不保证。
* **原生架构**：`arm64-v8a`。
* **原生自动适配**：安装 ZIP 时从当前 ROM 提取并生成补丁。
* **Java 自动适配**：安装 ZIP 时从当前系统 APK 提取并生成补丁

### 已测试设备

本人使用以下设备进行测试，其他版本或设备不保证可用：

| 型号 | 系统版本 | 备注 |
| :--- | :--- | :--- |
| 小米 Pad 8 Pro | `OS4.0.6.0` | 环境：SukiSu V4.3.0 + LSP V2.2.1实测可用 |


### 框架支持

| 环境类型 | 所需组件 | 说明 |
| :--- | :--- | :--- |
| **Root 环境** | Magisk / KernelSU | 安装模块并运行 root 后台服务 |
| **Hook 框架** | LSPosed + Zygisk | 加载模块并配置系统作用域 |
| **元模块挂载** | Hybrid Mount | 要求安装元模块,编译挂载system |

# 从源码构建

当前构建脚本使用 **Windows x64**，需要准备：

1. **Python 3 与 JDK**：已在 JDK 21 环境构建，确保 `python`、`javac`、`keytool` 可从命令行调用。
2. **Android SDK**：默认路径为 `%LOCALAPPDATA%\Android\Sdk`，包含 Build Tools `37.0.0`、平台 `platforms/android-37.0/android.jar` 和 NDK `30.0.16248370`。
3. **Xposed API 82**：使用 `de.robv.android.xposed:api:82`，从 `%USERPROFILE%\.gradle\caches\modules-2\files-2.1\de.robv.android.xposed\api\82\*\api-82.jar` 读取。

在项目根目录运行：

```powershell
python build.py
```

* **Hook APK**：`dist/bridge.apk`
* **模块安装包**：`dist/MiAppXSpace-<版本号>.zip`
* **本地签名**：首次构建自动生成 `.local/signing.p12`，后续构建请保留以复用签名。

> 💡 SDK 不在默认位置时可设置 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME`；Xposed API 不在 Gradle 缓存中时，可通过 `XPOSED_API_JAR` 指定完整路径。

# 安装与使用

### 安装步骤

1. 在 Magisk / KernelSU 中安装模块 ZIP。
2. 在 LSPosed 中启用 **MiApp XSpace**，作用域勾选系统框架、系统桌面、安全核心组件、手机管家、SystemUI、设置。
3. 手动重启设备，在系统「应用设置 → 应用双开」中创建与管理分身。

| 项目 | 标识 |
| :--- | :--- |
| **模块版本** | 见 [更新日志](CHANGELOG.md)；已发布版本见 [Releases](https://github.com/isxlan0/MiAppRepeatedlyPass/releases) |
| **模块 ID** | `miapp_xspace` |
| **LSP 包名** | `io.github.isxlan0.miappxspace` |

### 分身编号

默认每个应用最多三个分身，连同主应用共四份：

| 显示名称（以 X 信为例） | 用户 ID | 类型 |
| :--- | :--- | :--- |
| X 信 | `0` | 主应用 |
| X 信 2 | `999` | 系统自带分身 |
| X 信 3 | `998` | 模块提供的额外分身 |
| X 信 4 | `997` | 模块提供的额外分身 |

### 卸载顺序

1. **先备份**需要保留的分身应用数据。
2. 在「**应用设置 → 应用双开 → 设置**」中选择「**删除双开账户**」。
3. 卸载 **LSP 模块 APK**。
4. 最后卸载 **Magisk / KernelSU 模块**。

> ⚠️ 未按上述顺序操作可能导致系统异常。删除双开账户会删除分身数据；模块卸载脚本不会自动清理分身、删除注册表或卸载 APK，请自行完成上述步骤。

# 反馈与日志

反馈时请注明：

1. **设备型号与完整系统版本**
2. **模块版本与当前系统 Hook 版本**
3. **Magisk / KernelSU、LSPosed 版本**
4. **复现步骤与涉及的分身编号**

* **问题反馈**：[GitHub Issues](https://github.com/isxlan0/MiAppRepeatedlyPass/issues)
* **模块日志**：`/data/adb/modules/miapp_xspace/` 下的 `status.log`、`boot-spaces.log`、`titles.log`、`launcher-repair.log`。
* **安装扫描失败**：安装界面会显示具体失败阶段与退出码；失败日志另存到 `/data/adb/miapp_xspace-prepare.log`，避免安装器删除暂存目录时一起丢失。该文件记录最近一次失败，成功安装的报告请查看模块目录。
* **Hook 日志**：可提供 LSPosed 中与 `MiAppXSpace` 有关的日志；分享前请自行移除个人信息。

### 调试命令

以下为 root 调试命令，普通用户无需执行；`repair-launcher` 会修复并重新加载桌面，`create` 会创建对应分身。

```sh
M=/data/adb/modules/miapp_xspace/bin/miapp
$M status
$M launcher-plan      # 只检查失效分身图标
$M repair-launcher    # 修复并重新加载桌面
$M create mark.via 999
$M create mark.via 998
$M create mark.via 997
```

<br/>

<div align="center">

### 致谢

感谢以下开源项目提供的接口支持、运行框架与实现参考：

| Open Source Project | Role & Description |
| :--- | :--- |
| [![Xposed](https://img.shields.io/badge/Xposed-Hook_API-green?style=flat-square&logo=android)](https://github.com/rovo89/XposedBridge) | **Hook 接口**
使用 Xposed API 82 接入系统与应用进程。 |
| [![LSPosed](https://img.shields.io/badge/LSPosed-Framework-blue?style=flat-square&logo=android)](https://github.com/LSPosed/LSPosed) | **Xposed 运行框架**
提供模块加载与作用域管理。 |
| [![KernelSU](https://img.shields.io/badge/KernelSU-Module-orange?style=flat-square&logo=github)](https://github.com/tiann/KernelSU) | **模块运行环境**
模块安装、启动脚本与挂载机制参考。 |
| [![Magisk](https://img.shields.io/badge/Magisk-Module-00AF9C?style=flat-square&logo=github)](https://github.com/topjohnwu/Magisk) | **模块规范参考**
模块结构及安装、卸载脚本规范。 |
| [![AOSP](https://img.shields.io/badge/AOSP-Android-3DDC84?style=flat-square&logo=android)](https://android.googlesource.com/platform/frameworks/base/) | **系统实现参考**
用户与 profile 管理、启动可见性和 Binder 调用。 |

</div>

安装时生成的三个 `libmiapp_*` 原始转发库来自安装设备自身的 HyperOS ROM，不是本项目原创代码，权利归相应权利人；本项目安装包不附带这些 ROM 原件。Hybrid Mount 是原生库挂载流程的外部依赖。原创代码采用 [MIT License](LICENSE)。

<br/>

# 免责声明

1. **仅供学习交流**：本项目用于 Android 系统分身机制与 Xposed 技术的学习、交流和研究。
2. **风险自担**：使用本模块可能造成分身数据丢失、应用异常、系统无法启动或其他损失。**使用者应自行评估并承担风险；在适用法律允许的范围内，开发者不对使用本模块导致的数据丢失、系统异常或其他直接、间接损失承担责任。**
3. **自行备份**：安装、升级、使用和卸载前，请备份重要数据并确认恢复方法。删除双开账户会删除分身数据，卸载模块不能恢复已删除的数据。
4. **不作保证**：项目按现状提供，不保证适配所有设备、系统版本或应用。本项目与小米及上述参考项目不存在官方合作或背书关系。

**请在下载、安装或使用本模块前，阅读并理解上述说明；无法接受相关风险时，请勿使用。**

<br/>

<div align="center">

Made with ❤️ by [isxlan0](https://github.com/isxlan0)

</div>

```

```
