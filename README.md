<div align="center">
<h1>MiApp XSpace</h1>

![Version](https://img.shields.io/github/v/release/isxlan0/MiAppRepeatedlyPass?style=flat-square)
![Java](https://img.shields.io/badge/Java-17-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
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
* **运行状态**：查看激活状态、系统 Hook 状态和失败原因。
* **使用方式**：从桌面、LSPosed 模块设置或模块操作入口打开，查看关于信息不需要 root。

# 技术栈

### 💻 核心语言与架构

* **Java**：系统 Hook、分身管理和关于界面，按 Java 17 字节码编译。
* **C++17 / ARM64 汇编**：原生桌面角标、名称和 ABI 适配。

### 🎨 界面与交互

* **Android 原生 View**：提供关于页面，支持浅色和深色模式。
* **系统应用双开界面**：在原系统页面中接入额外分身的创建与管理。

### 🛠 Hook 与系统适配

* **libxposed API 102**：通过 modern 入口、生命周期与 Chain 拦截器接入系统框架、桌面、安全核心组件、手机管家、SystemUI 和设置。此版本不再提供 legacy 入口。
* **LSPosed**：负责 Hook 模块加载与作用域管理。
* **Android 原生 CLONE profile**：为分身提供独立空间与数据，保留应用原包名和签名。

### 📦 构建与运行

* **Python / Android SDK / NDK**：编译 Java、原生代码、签名 APK，并打包安装 ZIP。
* **Magisk / KernelSU 模块结构**：安装 Hook APK、挂载原生库并运行后台服务。

# 适配与运行环境

### Android 系统

* **Hook 与模块安装要求**：当前代码使用 SDK 37 系统接口。
* **模块 APK 最低版本**：Android 8.0（API 26），测试和使用均为OS4系统，其他系统不保证。
* **原生架构**：`arm64-v8a`。
* **系统原生库适配**：ZIP 安装时从当前 ROM 提取；每次开机在模块挂载前校验内容，变化后自动重新提取和检查。
* **应用 Java 自动适配**：Xposed 在目标进程加载时识别实际 APK 的方法、字段和资源；缓存随 APK 内容、拆分包、系统指纹和模块识别规则变化失效。
* **桌面原生自动适配**：在桌面原生进程中识别当前实际映射的 Dart / Rust 库，不依赖固定 APK 路径或安装时保存的地址。

### 已测试设备

本人使用以下设备进行测试，其他版本或设备不保证可用：

| 型号 | 系统版本 | 备注 |
| :--- | :--- | :--- |
| 小米 Pad 8 Pro | `OS4.0.6.0` | 环境：SukiSu V4.3.0 + LSP V2.2.1实测可用 |
| 小米 17 Pro | `OS4.0.6.0` | 环境：KernelSU V3.3.1 + LSP V2.2.1实测可用 |


### 框架支持

| 环境类型 | 所需组件 | 说明 |
| :--- | :--- | :--- |
| **Root 环境** | Magisk / KernelSU | 安装模块并运行 root 后台服务 |
| **Hook 框架** | 支持 libxposed API 102 的 LSPosed + Zygisk | 加载模块并配置系统作用域；legacy 或 API 100/101 的框架无法使用此模块 |
| **系统文件映射** | Magisk 内置挂载或支持系统文件映射的 KernelSU 元模块 | 重启后，桌面需能从 `/system_ext/lib64` 加载模块 `system/system_ext/lib64` 中替换和新增的库。Hybrid Mount 已实测。 |

# 从源码构建

当前构建脚本使用 **Windows x64**，需要准备：

1. **Python 3 与 JDK**：已在 JDK 21 环境构建，确保 `python`、`javac`、`keytool` 可从命令行调用。
2. **Android SDK**：默认路径为 `%LOCALAPPDATA%\Android\Sdk`，包含 Build Tools `37.0.0`、平台 `platforms/android-37.0/android.jar` 和 NDK `30.0.16248370`。
3. **libxposed 102.0.0**：构建通过 `prepare_dependencies.py` 下载 Maven Central 的 API、service 和 interface AAR，校验固定 SHA-256。API 只参与编译，服务库打入 APK。

在项目根目录运行：

```powershell
python build.py
```

* **Hook APK**：`dist/bridge.apk`
* **模块安装包**：`dist/MiAppXSpace-<版本号>.zip`
* **本地签名**：首次构建自动生成 `.local/signing.p12`，后续构建请保留以复用签名。

> 💡 SDK 不在默认位置时可设置 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME`。`ci` 目录保存构建检查与回归测试，不会打入模块 ZIP。

开发回归：构建后运行 `python -m unittest discover -s ci -p 'test_*.py' -v` 和 `python ci/verify_package.py`。具备三版已提取 ROM 样本与 root 测试设备时，可运行 `python ci/check_dynamic_device.py --serial <设备序列号> --fixtures <ROM样本目录> --archive dist/MiAppXSpace-1.0.5.zip`。后者自动编译测试工具，在独立临时目录验证缓存失效、原生识别、私有映射补丁和挂载前准备，不安装模块或重启设备。

API 102 回归依次运行 `python ci/check_modern.py` 和 `python ci/check_modern_device.py --serial <设备序列号>`，覆盖状态判定、拦截器语义及 `process=system` 的真实 ROM 方法注册；设备测试仅记录注册请求，不安装 Hook。`python ci/check_runtime_live.py --serial <设备序列号>` 可只读检查正在运行的桌面文件映射与目标指令，不注入代码或重启桌面。

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

### 应用与 OTA 更新

应用内连接 LSPosed 后即显示“✓ 正常运行中”；未连接时提示启用模块。KSU / Magisk 模块简介默认显示“✓ 正常运行中”，仅在收到实际错误时显示原因，不再等待系统 Hook 或桌面进程完成检查。

识别规则更新，更新安全核心、手机管家或桌面后，下次目标进程启动会自动识别新文件，无需重新刷 ZIP。普通应用的更新不需要扫描其 APK。

OTA 后，`post-fs-data.sh` 在系统库挂载前检查当前 ROM 的三个原始库；内容变化时，用独立原生工具重新验证 ABI 并生成转发库。系统框架的 Java 规则由 Xposed 在 `system_server` 安装 Hook 前检查。

动态识别可以应对地址、混淆名、资源 ID 和文件路径变化；若关键实现、ABI 或 Android API 超出现有规则，仍需要更新模块适配。原生系统库准备失败或超过时限时，当次启动会跳过模块的原生库挂载，并在下一次开机重试。

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
* **开机系统库适配**：同目录下的 `system-prepare.log`。出现 `skip_mount` 表示本次校验未通过，原生库挂载已跳过；修复原因或更新识别规则后下次开机会重试。
* **安装环境与日志**：安装开始时输出 ZIP 版本、已安装模块版本、设备型号/代号、完整 ROM 版本、Android/SDK、内核、Root 安装器变量、`su` 版本及 LSPosed/Zygisk Next 的已安装版本和启用标记。无法读取的项目标记为 `unknown`，模块启用标记不代表已经成功注入。成功安装后查看模块目录的 `install.log`。
* **安装失败**：环境检查或扫描等步骤失败时，完整安装日志另存到 `/data/adb/miapp_xspace-prepare.log`，避免安装器删除暂存目录时一起丢失。该文件记录最近一次失败，包含安装环境、具体失败原因以及扫描退出码（如有）。
* **Hook 日志**：提供 LSPosed 中与 `MiAppXSpace` 有关的完整日志，其中会记录当前 Hook APK 版本、实际 Xposed API、ROM 指纹，以及校验失败的框架 JAR、预期/实际同名方法签名和方法指纹。安装器仅能读取 LSPosed 模块元数据，实际运行 API 以 Hook 日志为准。分享前请自行移除个人信息。

### 从完整卡刷包按需提取框架文件

开发调试可使用仓库根目录的 `extract_ota_file.py`。需要 Python 3.10+、`curl` 和 erofs-utils 的 `dump.erofs`（Windows 为 `dump.erofs.exe`）。工具通过 HTTP Range 读取 ZIP/payload 索引，再下载目标文件和文件系统元数据对应的操作块；不会连接设备或刷写分区。

```powershell
python extract_ota_file.py '完整卡刷ZIP链接' --dump-erofs 'D:\Tools\erofs\dump.erofs.exe' --output 'build\services.jar'
```

默认提取 `system` 分区内的 `/system/framework/services.jar`，可通过 `--partition`、`--path` 指定其他文件。下载数据会缓存，默认每次最多下载 512 MiB（`--max-download-mib` 可调整）；结果旁的 JSON 报告记录 ROM 来源、操作块数量、文件大小及 SHA-256。已取到的操作块会按 payload 中的 SHA-256 校验，JAR/APK 还会检查 ZIP CRC；这不等于验证完整 OTA 的签名。

当前支持未压缩存储 `payload.bin` 的完整 OTA ZIP、EROFS 文件系统，以及 REPLACE/BZ/XZ/ZERO 操作。不支持增量包、线刷 TGZ 或非 EROFS 分区；服务器不支持分段读取时直接报错。工作目录中的 `*.INCOMPLETE.img` 是只填入所需数据的临时稀疏镜像，不能刷入设备。

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
| [![libxposed](https://img.shields.io/badge/libxposed-API_102-green?style=flat-square&logo=android)](https://github.com/libxposed/api) | **Hook 接口**
使用 libxposed API 102 接入系统与应用进程，并获取框架状态。 |
| [![LSPosed](https://img.shields.io/badge/LSPosed-Framework-blue?style=flat-square&logo=android)](https://github.com/LSPosed/LSPosed) | **Xposed 运行框架**
提供模块加载与作用域管理。 |
| [![KernelSU](https://img.shields.io/badge/KernelSU-Module-orange?style=flat-square&logo=github)](https://github.com/tiann/KernelSU) | **模块运行环境**
模块安装、启动脚本与挂载机制参考。 |
| [![Magisk](https://img.shields.io/badge/Magisk-Module-00AF9C?style=flat-square&logo=github)](https://github.com/topjohnwu/Magisk) | **模块规范参考**
模块结构及安装、卸载脚本规范。 |
| [![AOSP](https://img.shields.io/badge/AOSP-Android-3DDC84?style=flat-square&logo=android)](https://android.googlesource.com/platform/frameworks/base/) | **系统实现参考**
用户与 profile 管理、启动可见性和 Binder 调用。 |

</div>

安装和 OTA 后开机生成的三个 `libmiapp_*` 原始转发库来自设备自身的 HyperOS ROM，不是本项目原创代码，权利归相应权利人；本项目安装包不附带这些 ROM 原件。原创代码采用 [MIT License](LICENSE)。

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
