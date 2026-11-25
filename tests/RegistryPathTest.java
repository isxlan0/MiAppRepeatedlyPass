package io.github.isxlan0.miappxspace;

import java.io.File;
import java.nio.file.Files;

/** Upgrades must retain the same registry and lock directory as the loaded old Hook. */
public final class RegistryPathTest {
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("miapp-registry-test-").toFile();
        File current = new File(root, "miapp_xspace"), legacy = new File(root, "miapp_instances");
        try {
            check(Registry.directory(current, legacy).equals(current));
            check(!current.exists());
            check(legacy.mkdir());
            check(Registry.directory(current, legacy).equals(legacy));
            check(current.mkdir());
            check(Registry.directory(current, legacy).equals(legacy));
            check(legacy.delete());
            check(Registry.directory(current, legacy).equals(current));
            System.out.println("PASS: fresh registry path and unchanged legacy registry/lock ownership");
        } finally {
            current.delete();
            legacy.delete();
            root.delete();
        }
    }
}
