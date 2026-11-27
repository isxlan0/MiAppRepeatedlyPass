package io.github.isxlan0.miappxspace;

import android.os.*;
import android.os.Process;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.io.*;
import java.lang.reflect.Executable;
import java.util.*;

final class RuntimeHealth {
    private static final Properties status=new Properties();
    private static final Map<String,Properties> applications=new TreeMap<>();
    private static boolean system;
    private static String process,pkg;
    static boolean scoped(String pkg) {
        return Arrays.asList("android","com.miui.home","com.miui.securitycore","com.miui.securitycenter","com.android.systemui","com.android.settings").contains(pkg);
    }
    static synchronized void initialize(XposedInterface api,String name,boolean server) {
        system=server; process=name;
        status.setProperty("version",BuildConfig.VERSION_NAME);
        status.setProperty("versionCode",Integer.toString(BuildConfig.VERSION_CODE));
        status.setProperty("build",BuildConfig.BUILD_ID);
        status.setProperty("framework",api.getFrameworkName());
        status.setProperty("frameworkVersion",api.getFrameworkVersion());
        status.setProperty("frameworkCode",Long.toString(api.getFrameworkVersionCode()));
        status.setProperty("api",Integer.toString(api.getApiVersion()));
        status.setProperty("connection","libxposed API 102");
        status.setProperty("pid",Integer.toString(Process.myPid()));
        status.setProperty("process",name);
        status.setProperty("mode","native-clone-profiles");
        status.setProperty("rom",Build.VERSION.INCREMENTAL);
        try { status.setProperty("boot",HealthFiles.bootId());status.setProperty("start",HealthFiles.startTime(Process.myPid())); }
        catch(Exception error){Log.w("MiAppXSpace","Process identity unavailable",error);}
    }
    static synchronized void begin(String name) { pkg=name;status.setProperty("package",name);status.setProperty("state","starting");publish(); }
    static synchronized void ready(int hooks) {
        if(hooks<=0)throw new IllegalStateException("No functional hooks installed");
        status.setProperty("hooks",Integer.toString(hooks));
        if(!"error".equals(status.getProperty("state"))){status.setProperty("state","ready");status.remove("detail");}publish();
    }
    static synchronized void failure(String stage,Throwable error) {
        status.setProperty("state","error");status.setProperty("detail",HealthFiles.clean(stage+": "+error,1800));publish();
    }
    static void callbackFailure(Executable method,Throwable error) {
        try {
            Hooks.log(error);
            synchronized(RuntimeHealth.class){if("error".equals(status.getProperty("state")))return;}
            failure("Hook 回调失败 "+method.getDeclaringClass().getName()+"."+method.getName(),error);
        }catch(Throwable ignored){Log.e("MiAppXSpace","Health reporting failed",error);}
    }
    private static synchronized void publish() {
        if(system) {
            try { Registry.DIR.mkdirs();HealthFiles.write(new File(Registry.DIR,"hook.status"),snapshot()); }
            catch(Exception error){Log.e("MiAppXSpace","Cannot persist runtime health",error);}
        }else if(pkg!=null&&!"starting".equals(status.getProperty("state"))) {
            Bundle report=HealthRpc.bundle(status);
            Thread thread=new Thread(()->{
                for(int attempt=0;attempt<3;attempt++)try {HealthRpc.call(HealthRpc.userService(),report);return;}
                catch(Exception error){if(attempt==2)Log.w("MiAppXSpace","Cannot report app hook state",error);else SystemClock.sleep(1000);}
            },"MiApp-health-report");thread.setDaemon(true);thread.start();
        }
    }
    static synchronized Properties snapshot() {
        Properties result=new Properties();result.putAll(status);
        for(Map.Entry<String,Properties> entry:applications.entrySet())for(String key:entry.getValue().stringPropertyNames())
            result.setProperty("app."+entry.getKey()+"."+key,entry.getValue().getProperty(key));
        return result;
    }
    static synchronized void requireReady() {
        if(!"ready".equals(status.getProperty("state")))throw new IllegalStateException("System Hook is not ready: "+status.getProperty("detail",status.getProperty("state")));
    }
    static void installEndpoint(ClassLoader loader)throws Exception {
        Class<?> stub=HookReflect.findClass("android.os.IUserManager$Stub",loader);
        if(Hooks.hookAllMethods(stub,"onTransact",new HookCallback(){
            @Override protected void beforeHookedMethod(Param hook)throws Throwable {
                if(!Integer.valueOf(HealthRpc.TRANSACTION).equals(hook.args[0]))return;
                Parcel data=(Parcel)hook.args[1],reply=(Parcel)hook.args[2];
                if(reply==null){hook.setResult(true);return;}
                try {
                    data.enforceInterface(HealthRpc.TOKEN);Bundle report=data.readBundle(RuntimeHealth.class.getClassLoader());data.enforceNoDataAvail();
                    if(report!=null)accept(report,Binder.getCallingUid(),Binder.getCallingPid());
                    reply.writeNoException();reply.writeBundle(HealthRpc.bundle(snapshot()));
                }catch(Exception error){reply.writeException(new SecurityException(error.toString()));}
                hook.setResult(true);
            }
        }).isEmpty())throw new IllegalStateException("System health endpoint missing");
    }
    private static void accept(Bundle report,int uid,int pid)throws Exception {
        String name=report.getString("package"),proc=report.getString("process");
        if(!scoped(name)||"android".equals(name)||uid/100000!=0||proc==null||
                !(proc.equals(name)||proc.startsWith(name+":")||name.equals("com.miui.securitycore")&&proc.equals(name+".remote")))throw new SecurityException("Invalid reporter");
        String actual=HealthFiles.read(new File("/proc/"+pid+"/cmdline")).split("\u0000",2)[0];
        if(!proc.equals(actual))throw new SecurityException("Reporter process mismatch");
        Object manager=ProfileBackend.service("package","android.content.pm.IPackageManager");
        String[] names=(String[])Reflect.call(manager,"getPackagesForUid",uid);
        if(names==null||!Arrays.asList(names).contains(name))throw new SecurityException("Reporter UID mismatch");
        Properties values=new Properties();
        for(String key:new String[]{"state","detail","version","versionCode","build"}) {
            String value=report.getString(key);if(value!=null)values.setProperty(key,HealthFiles.clean(value,1800));
        }
        if(!Arrays.asList("ready","error").contains(values.getProperty("state")))throw new SecurityException("Invalid report state");
        values.setProperty("pid",Integer.toString(pid));values.setProperty("start",HealthFiles.startTime(pid));
        synchronized(RuntimeHealth.class){applications.put(proc,values);publish();}
    }
}
