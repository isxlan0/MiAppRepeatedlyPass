package io.github.isxlan0.miappxspace;

final class AppInfo {
    static final String SOURCE = "https://github.com/isxlan0/MiAppRepeatedlyPass";
    static final String DESCRIPTION = "模块额外增加系统分身数量，使系统最多支持 3 个分身（系统自带一个，模块提供两个位置）。";
    static final String UNINSTALL = "卸载之前请先在「应用设置 → 应用双开 → 设置」中选择「删除双开账户」，然后卸载 LSP 模块 APK，最后卸载 Magisk / KernelSU 模块，否则可能导致系统异常。删除双开账户会清除分身数据，请先备份。模块卸载脚本不会自动清理分身或卸载 APK。";
    static final String DISCLAIMER = "1. 仅供学习交流：本项目用于 Android 系统分身机制与 Xposed 技术的学习、交流和研究。\n\n2. 风险自担：使用本模块可能造成分身数据丢失、应用异常、系统无法启动或其他损失。使用者应自行评估并承担风险；在适用法律允许的范围内，开发者不对使用本模块导致的数据丢失、系统异常或其他直接、间接损失承担责任。\n\n3. 自行备份：安装、升级、使用和卸载前，请备份重要数据并确认恢复方法。删除双开账户会删除分身数据，卸载模块不能恢复已删除的数据。\n\n4. 不作保证：项目按现状提供，不保证适配所有设备、系统版本或应用。本项目与小米及上述参考项目不存在官方合作或背书关系。\n\n请在下载、安装或使用本模块前，阅读并理解上述说明；无法接受相关风险时，请勿使用。";
    static final String[][] CREDITS = {
        {"libxposed", "现代 Hook API、模块生命周期和框架状态服务（API 102）", "https://github.com/libxposed/api"},
        {"LSPosed", "Xposed 兼容运行框架与作用域管理", "https://github.com/LSPosed/LSPosed"},
        {"KernelSU", "模块安装、启动脚本及挂载机制参考", "https://github.com/tiann/KernelSU"},
        {"Magisk", "模块结构、安装与卸载脚本规范参考", "https://github.com/topjohnwu/Magisk"},
        {"Android Open Source Project", "用户与 profile 管理、启动可见性和 Binder 实现参考", "https://android.googlesource.com/platform/frameworks/base/"}
    };
}
