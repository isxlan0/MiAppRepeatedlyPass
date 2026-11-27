package io.github.isxlan0.miappxspace;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class HealthStateTest {
    static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    public static void main(String[] args)throws Exception {
        Properties p=new Properties();p.setProperty("version","1.0.4");p.setProperty("build","build-a");p.setProperty("state","ready");
        p.setProperty("pid","123");p.setProperty("start","456");p.setProperty("boot","boot-a");
        p.setProperty("hooks","27");
        check(HealthFiles.sameProcess(p,"boot-a","456",123),"live identity rejected");
        check(!HealthFiles.sameProcess(p,"boot-b","456",123),"previous boot accepted");
        check(!HealthFiles.sameProcess(p,"boot-a","999",123),"reused pid accepted");
        check(!HealthFiles.sameProcess(p,"boot-a","456",124),"different process accepted");
        check(ModuleHealth.evaluate(new Properties(),"","","").equals("✓ 正常运行中"),"missing reports must not delay normal status");
        check(ModuleHealth.evaluate(p,"ready","","").equals("✓ 正常运行中"),"ready");
        check(ModuleHealth.evaluate(p,"error","native failed","").contains("native failed"),"native error lost");
        p.setProperty("state","starting");check(ModuleHealth.evaluate(p,"starting","","").equals(ModuleHealth.NORMAL),"starting reports must not delay normal status");
        p.setProperty("state","error");p.setProperty("detail","missing String[]");check(ModuleHealth.evaluate(p,"ready","","").contains("missing String[]"),"hook error lost");
        p.setProperty("state","ready");p.remove("hooks");
        check(ModuleHealth.evaluate(p,"ready","","").equals(ModuleHealth.NORMAL),"hook count must not gate status");
        check(ModuleHealth.launcherIssue(new Properties()).isEmpty(),"missing desktop report blocks status");
        Properties desktop=new Properties();desktop.setProperty("state","starting");
        check(ModuleHealth.launcherIssue(desktop).isEmpty(),"starting desktop blocks status");
        desktop.setProperty("state","error");desktop.setProperty("pid","2147483647");
        check(ModuleHealth.launcherIssue(desktop).isEmpty(),"dead desktop error survives restart");
        check(ModuleHealth.evaluate(p,"ready","","桌面原生异常：target modified").contains("target modified"),"reported launcher failure lost");
        p.setProperty("app.com.miui.securitycore.state","ready");p.setProperty("app.com.miui.securitycore.build","old");
        check(ModuleHealth.appIssues(p).isEmpty(),"build mismatch alone must not be an error");
        p.setProperty("app.com.miui.securitycore.state","error");p.setProperty("app.com.miui.securitycore.detail","signature changed");
        check(ModuleHealth.appIssues(p).contains("signature changed"),"app failure summary");
        check(ModuleHealth.evaluate(p,"ready","","").contains("signature changed"),"app failure lost");
        Path folder=Files.createTempDirectory(Paths.get(args[0]),"health-state-");
        try {
            File prop=folder.resolve("module.prop").toFile();Files.write(prop.toPath(),"id=miapp_xspace\nversion=1.0.4\ndescription=old\nupdateJson=keep\n".getBytes(StandardCharsets.UTF_8));
            ModuleHealth.writeDescription(folder.toFile(),"[异常] a=$b \\ 原因");
            String changed=HealthFiles.read(prop);check(changed.contains("description=[异常] a=$b \\ 原因")&&changed.contains("updateJson=keep")&&changed.contains("id=miapp_xspace"),"description corrupted metadata");
            HealthFiles.write(folder.resolve("status").toFile(),p);check(HealthFiles.load(folder.resolve("status").toFile()).equals(p),"atomic status roundtrip");
            File report=folder.resolve("status").toFile();
            check(ModuleHealth.currentReport(report,"boot-a","build-a").equals(p),"current error report ignored");
            check(ModuleHealth.currentReport(report,"boot-b","build-a").isEmpty(),"previous boot error retained");
            check(ModuleHealth.currentReport(report,"boot-a","build-b").isEmpty(),"previous build error retained");
            check(ModuleHealth.currentReport(folder.resolve("missing").toFile(),"boot-a","build-a").isEmpty(),"missing report not optional");
        }finally{try(java.util.stream.Stream<Path> files=Files.list(folder)){for(Path f:(Iterable<Path>)files::iterator)Files.delete(f);}Files.delete(folder);}
        System.out.println("PASS: no-error default, optional startup reports, real failures, stale-report filtering and atomic description");
    }
}
