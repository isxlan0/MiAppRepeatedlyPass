package io.github.isxlan0.miappxspace;

import android.content.pm.ApplicationInfo;

final class PackageContext {
    final String packageName,processName;
    final ClassLoader classLoader;
    final ApplicationInfo appInfo;
    PackageContext(String pkg,String process,ClassLoader loader,ApplicationInfo info) {
        packageName=pkg; processName=process; classLoader=loader; appInfo=info;
    }
}
