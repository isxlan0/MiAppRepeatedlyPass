package io.github.isxlan0.miappxspace;

public final class LauncherCleanupPolicyTest {
    static void check(boolean condition){if(!condition)throw new AssertionError();}
    public static void main(String[] args){
        check(!LauncherCleanupPolicy.stale(0,0,null,true,false));
        check(!LauncherCleanupPolicy.stale(10,10,null,true,false));
        check(LauncherCleanupPolicy.stale(998,1001,null,false,false));
        check(LauncherCleanupPolicy.stale(999,1010,null,false,false));
        check(!LauncherCleanupPolicy.stale(998,1001,1001L,true,true));
        check(LauncherCleanupPolicy.stale(998,1001,1001L,true,false));
        check(LauncherCleanupPolicy.stale(998,1001,1033L,true,true));
        check(!LauncherCleanupPolicy.stale(998,1001,1033L,false,false));
        check(!LauncherCleanupPolicy.stale(1001,998,null,true,false));
        System.out.println("PASS: owner/private-space protection, missing clone, serial reuse, uninstall, unowned profile");
    }
}
