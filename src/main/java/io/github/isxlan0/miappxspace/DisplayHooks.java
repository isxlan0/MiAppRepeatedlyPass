package io.github.isxlan0.miappxspace;

import android.app.ActivityManager;
import android.content.*;
import android.content.pm.*;
import android.os.Binder;
import java.util.*;
import de.robv.android.xposed.*;

/** Labels only copies returned to launcher/UI callers; stored package identity is unchanged. */
final class DisplayHooks {
    static Context context;
    static boolean isClone(int id){return id==999||ProfileHook.extra(id);}
    static boolean uiCaller(){
        try{
            if(ProfileBackend.pm==null)ProfileBackend.pm=ProfileBackend.service("package","android.content.pm.IPackageManager");
            String[] names=(String[])Reflect.call(ProfileBackend.pm,"getPackagesForUid",Binder.getCallingUid());
            if(names!=null)for(String name:names)if(name.equals("com.miui.home")||name.equals("com.android.systemui")||name.equals("com.miui.securitycore"))return true;
        }catch(Exception ignored){}
        return false;
    }
    static CharSequence baseLabel(String pkg)throws Exception{
        if(ProfileBackend.pm==null)ProfileBackend.pm=ProfileBackend.service("package","android.content.pm.IPackageManager");
        ApplicationInfo owner=(ApplicationInfo)Reflect.call(ProfileBackend.pm,"getApplicationInfo",pkg,0L,0);
        if(owner==null)return pkg;
        if(context==null)context=(Context)XposedHelpers.callMethod(XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityThread"),"currentActivityThread"),"getSystemContext");
        return owner.loadLabel(context.getPackageManager());
    }
    static ApplicationInfo app(ApplicationInfo original,int id)throws Exception{
        ApplicationInfo copy=new ApplicationInfo(original);copy.nonLocalizedLabel=CloneSlots.label(baseLabel(copy.packageName),id);return copy;
    }
    static ActivityInfo activity(ActivityInfo original,int id)throws Exception{
        ActivityInfo copy=new ActivityInfo(original);copy.nonLocalizedLabel=CloneSlots.label(baseLabel(copy.packageName),id);
        copy.applicationInfo=app(original.applicationInfo,id);return copy;
    }
    static void install(ClassLoader loader)throws Throwable{
        Class<?> users=XposedHelpers.findClass("com.android.server.pm.UserManagerService",loader);
        XposedBridge.hookAllMethods(users,"hasBadge",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam m){if(!m.hasThrowable()&&isClone((Integer)m.args[0]))m.setResult(false);}
        });
        Class<?> packages=XposedHelpers.findClass("com.android.server.pm.PackageManagerService$IPackageManagerImpl",loader);
        for(String method:new String[]{"getApplicationInfo","getActivityInfo"})XposedBridge.hookAllMethods(Reflect.declaringClass(packages,method),method,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam m)throws Throwable{
                if(m.hasThrowable()||m.getResult()==null||!uiCaller())return;
                Object value=m.getResult();ApplicationInfo info=value instanceof ActivityInfo?((ActivityInfo)value).applicationInfo:value instanceof ApplicationInfo?(ApplicationInfo)value:null;
                if(info==null||!isClone(info.uid/100000))return;
                int id=info.uid/100000;m.setResult(value instanceof ActivityInfo?activity((ActivityInfo)value,id):app(info,id));
            }
        });
        Class<?> launcher=XposedHelpers.findClass("com.android.server.pm.LauncherAppsService$LauncherAppsImpl",loader);
        XC_MethodHook launcherLabels=new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam m)throws Throwable{
                if(m.hasThrowable()||m.getResult()==null)return;
                Object result=m.getResult();List<?> list=(List<?>)Reflect.call(result,"getList");
                for(Object entry:list){
                    ActivityInfo info=(ActivityInfo)Reflect.call(entry,"getActivityInfo");int id=info.applicationInfo.uid/100000;
                    if(isClone(id))Reflect.field(entry,"mActivityInfo",activity(info,id));
                }
            }
        };
        XposedBridge.hookAllMethods(launcher,"getLauncherActivities",launcherLabels);
        Class<?> tasks=XposedHelpers.findClass("com.android.server.wm.ActivityTaskManagerService",loader);
        XC_MethodHook taskLabels=new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam m)throws Throwable{
                if(m.hasThrowable()||m.getResult()==null||m.args.length==0)return;
                Object result=m.getResult();List<?> list=result instanceof List?(List<?>)result:(List<?>)Reflect.call(result,"getList");
                for(Object task:list){
                    int id=XposedHelpers.getIntField(task,"userId");if(!isClone(id))continue;
                    Intent base=(Intent)XposedHelpers.getObjectField(task,"baseIntent");
                    if(base==null||base.getComponent()==null)continue;
                    ActivityManager.TaskDescription old=(ActivityManager.TaskDescription)XposedHelpers.getObjectField(task,"taskDescription");
                    if(old==null)continue;
                    ActivityManager.TaskDescription copy=new ActivityManager.TaskDescription(old);
                    XposedHelpers.callMethod(copy,"setLabel",CloneSlots.label(baseLabel(base.getComponent().getPackageName()),id));
                    XposedHelpers.setObjectField(task,"taskDescription",copy);
                }
            }
        };
        XposedBridge.hookAllMethods(tasks,"getRecentTasks",taskLabels);XposedBridge.hookAllMethods(tasks,"getTasks",taskLabels);
    }
}
