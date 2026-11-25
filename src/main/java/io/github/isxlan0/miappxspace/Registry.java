package io.github.isxlan0.miappxspace;

import java.io.File;
import java.util.regex.Pattern;

final class Registry {
    // Keep existing registries in place: a legacy system Hook may still be running until reboot.
    static final File DIR = directory(new File("/data/system/miapp_xspace"), new File("/data/system/miapp_instances"));
    static File directory(File current, File legacy) { return legacy.isDirectory() ? legacy : current; }
    static final Pattern PACKAGE = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+");
}
