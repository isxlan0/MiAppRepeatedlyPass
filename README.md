# **Mi App XSpace 多分身模块**

本人使用以下设备进行测试,其他版本或设备不保证可用

| 型号| 系统版本|
| --- | --- |
|小米Pad8Pro|`OS4.0.6.0`|
|  |  |


**从源码构建**

当前构建脚本使用 Windows x64，需要先准备以下环境：

- Python 3 和 JDK（已在 JDK 21 环境构建），确保 `python`、`javac`、`keytool` 可从命令行调用。
- Android SDK 安装在 `%LOCALAPPDATA%\Android\Sdk`，包含 Build Tools `37.0.0`、平台文件 `platforms/android-37.0/android.jar` 和 NDK `30.0.16248370`。
- Xposed API 82（`de.robv.android.xposed:api:82`）。脚本从 `%USERPROFILE%\.gradle\caches\modules-2\files-2.1\de.robv.android.xposed\api\82\*\api-82.jar` 读取，请先准备该依赖。

在项目根目录运行：

```powershell
python build.py
```

脚本生成的构建在 `dist/bridge.apk` 和 `dist/MiAppXSpace-*.zip`。首次构建会自动生成所需签名文件；

**安装与使用**

通过 KernelSU 安装 ZIP后，在 LSPosed 启用模块，作用域需勾选系统框架、系统桌面、安全核心组件、手机管家、SystemUI。全部完成后请手动重启设备，模块最大支持系统外的额外两个分身(主APP+系统自带分身+本模块提供第三，第四分身APP)。

默认限制最多4个APP,您可以自行修改源代码解锁限制,详细说明：

| 显示名称（以X信为例） | 用户ID | 类型 |
| --- | --- | --- |
| X信 | 0 | 主应用 |
| X信2 | 999 | X米系统提供分身 |
| X信3 | 998 | 模块提供额外分身 |
| X信4 | 997 | 模块提供额外分身 |

模块测试命令(普通用户不要看,不要乱执行)：

```sh
M=/data/adb/modules/miapp_instances/bin/miapp
$M status
$M launcher-plan      # 只检查失效分身图标
$M repair-launcher    # 修复并重新加载桌面
$M create mark.via 999
$M create mark.via 998
$M create mark.via 997
```

```

```
