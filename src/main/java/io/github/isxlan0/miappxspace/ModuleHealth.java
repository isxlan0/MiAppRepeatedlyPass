package io.github.isxlan0.miappxspace;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Manager description: normal unless a current report contains an actual error. */
final class ModuleHealth {
    static final String NORMAL="✓ 正常运行中";
    static final String DESCRIPTION="额外增加系统分身数量,使系统最多支持3个分身(系统自带一个+模块提供两个位置) Github:isxlan0/MiAppRepeatedlyPass";
    static String evaluate(Properties hook,String nativeState,String nativeDetail,String launcher) {
        if("error".equals(nativeState))return "系统库异常："+nativeDetail;
        if("error".equals(hook.getProperty("state")))return "适配/Hook 异常："+hook.getProperty("detail","原因见 LSPosed 日志");
        String apps=appIssues(hook);
        if(!apps.isEmpty())return apps;
        if(!launcher.isEmpty())return launcher;
        return NORMAL;
    }
    static boolean live(Properties p,String boot)throws Exception {
        int pid=Integer.parseInt(p.getProperty("pid","0"));
        if(pid<=0)return false;
        return HealthFiles.sameProcess(p,boot,HealthFiles.startTime(pid),pid)&&HealthFiles.read(new File("/proc/"+pid+"/cmdline")).split("\u0000",2)[0].equals("system_server");
    }
    static String appIssues(Properties p) {
        for(String key:new TreeSet<>(p.stringPropertyNames()))if(key.startsWith("app.")&&key.endsWith(".state")) {
            String prefix=key.substring(0,key.length()-5),name=prefix.substring(4,prefix.length()-1);
            if("error".equals(p.getProperty(key)))return "应用 Hook 异常："+name+" "+p.getProperty(prefix+"detail","");
        }
        return "";
    }
    static Properties currentReport(File file,String boot,String build) {
        try {
            Properties p=HealthFiles.load(file);
            // Ignore old reports; their absence never delays a normal status.
            if(boot.equals(p.getProperty("boot"))&&(build==null||build.equals(p.getProperty("build"))))return p;
        }catch(IOException ignored){}
        return new Properties();
    }
    static String launcherIssue(Properties status) {
        if(!"error".equals(status.getProperty("state")))return "";
        // Check identity only for a reported failure, so a dead desktop's error
        // cannot survive its restart. No PID/mapping/readiness polling is needed.
        try {
            int pid=Integer.parseInt(status.getProperty("pid","0"));
            if(pid>0&&status.getProperty("start","").equals(HealthFiles.startTime(pid)))
                return "桌面原生异常："+status.getProperty("detail","请查看 MiAppDartBadge 日志");
        }catch(Exception ignored){}
        return "";
    }
    static void update(File module)throws Exception {
        String build=HealthFiles.read(new File(module,"build-id")),boot=HealthFiles.bootId();
        Properties hook=currentReport(new File(Registry.DIR,"hook.status"),boot,build);
        if("error".equals(hook.getProperty("state"))||!appIssues(hook).isEmpty())try {
            if(!live(hook,boot))hook=new Properties();
        }catch(Exception ignored){hook=new Properties();}
        Properties nativeStatus=currentReport(new File(module,"native-boot.status"),boot,null);
        String nativeState=nativeStatus.getProperty("state",""),nativeDetail=nativeStatus.getProperty("detail","");
        if(new File(module,"skip_mount").exists()){
            nativeState="error";nativeDetail="系统库挂载已跳过；查看 system-prepare.log";
        }
        Properties desktop=currentReport(new File("/data/user/0/com.miui.home/files/miapp-native.status"),boot,build);
        String message=evaluate(hook,nativeState,nativeDetail,launcherIssue(desktop));
        if(new File(module,"disable").exists()||new File(module,"remove").exists())message="已禁用/待卸载：重启后生效";
        writeDescription(module,"["+HealthFiles.clean(message,230)+"] "+DESCRIPTION);
        System.out.println(message);
    }
    static void writeDescription(File module,String description)throws IOException {
        File file=new File(module,"module.prop");String source=HealthFiles.read(file);
        String updated=source.replaceAll("(?m)^description=.*$",java.util.regex.Matcher.quoteReplacement("description="+description))+"\n";
        if(updated.trim().equals(source))return;
        File temp=new File(module,"module.prop.health.tmp");Files.write(temp.toPath(),updated.getBytes(StandardCharsets.UTF_8));
        Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
}
