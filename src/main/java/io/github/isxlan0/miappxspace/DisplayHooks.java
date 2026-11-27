package io.github.isxlan0.miappxspace;

import android.app.ActivityManager;
import android.content.*;
import android.content.pm.*;
import android.os.Binder;
import java.util.*;


/** Labels only copies returned to launcher/UI callers; stored package identity is unchanged. */
final class DisplayHooks {
    static Context context;
    static boolean isClone(int id){return id==999||ProfileHook.extra(id);}
    static boolean uiCaller(){
        try{
            if(ProfileBackend.pm==null)ProfileBackend.pm=ProfileBackend.service("package","android.content.pm.IPackageManager");
            String[] names=(String[])Reflect.call(ProfileBackend.pm,"getPackagesForUid",Binder.getCallingUid());
            if(names!=null)for(String name:names)if(name.equals("com.miui.home")||name.equals("com.android.systemui")||name.equals("com.miui.securitycore")||name.equals("com.miui.securitycenter")||name.equals("com.android.settings"))return true;
        }catch(Exception ignored){}
        return false;
    }
    static CharSequence baseLabel(String pkg)throws Exception{
        if(ProfileBackend.pm==null)ProfileBackend.pm=ProfileBackend.service("package","android.content.pm.IPackageManager");
        ApplicationInfo owner=(ApplicationInfo)Reflect.call(ProfileBackend.pm,"getApplicationInfo",pkg,0L,0);
        if(owner==null)return pkg;
        if(context==null)context=(Context)HookReflect.callMethod(HookReflect.callStaticMethod(Class.forName("android.app.ActivityThread"),"currentActivityThread"),"getSystemContext");
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
        Class<?> users=HookReflect.findClass("com.android.server.pm.UserManagerService",loader);
        Hooks.hookAllMethods(users,"hasBadge",new HookCallback(){
            @Override protected void afterHookedMethod(Param m){if(!m.hasThrowable()&&isClone((Integer)m.args[0]))m.setResult(false);}
        });
        Class<?> packages=HookReflect.findClass("com.android.server.pm.PackageManagerService$IPackageManagerImpl",loader);
        for(String method:new String[]{"getApplicationInfo","getActivityInfo","getPackageInfo"})Hooks.hookAllMethods(Reflect.declaringClass(packages,method),method,new HookCallback(){
            @Override protected void afterHookedMethod(Param m)throws Throwable{
                if(m.hasThrowable()||m.getResult()==null||!uiCaller())return;
                Object value=m.getResult();ApplicationInfo info=value instanceof ActivityInfo?((ActivityInfo)value).applicationInfo:value instanceof PackageInfo?((PackageInfo)value).applicationInfo:value instanceof ApplicationInfo?(ApplicationInfo)value:null;
                if(info==null||!isClone(info.uid/100000))return;
                int id=info.uid/100000;
                if(value instanceof PackageInfo){PackageInfo copy=ManagementHooks.copy((PackageInfo)value);copy.applicationInfo=app(info,id);m.setResult(copy);}
                else m.setResult(value instanceof ActivityInfo?activity((ActivityInfo)value,id):app(info,id));
            }
        });
        Class<?> launcher=HookReflect.findClass("com.android.server.pm.LauncherAppsService$LauncherAppsImpl",loader);
        HookCallback launcherLabels=new HookCallback(){
            @Override protected void afterHookedMethod(Param m)throws Throwable{
                if(m.hasThrowable()||m.getResult()==null)return;
                Object result=m.getResult();List<?> list=(List<?>)Reflect.call(result,"getList");
                for(Object entry:list){
                    ActivityInfo info=(ActivityInfo)Reflect.call(entry,"getActivityInfo");int id=info.applicationInfo.uid/100000;
                    if(isClone(id))Reflect.field(entry,"mActivityInfo",activity(info,id));
                }
            }
        };
        Hooks.hookAllMethods(launcher,"getLauncherActivities",launcherLabels);
        Class<?> tasks=HookReflect.findClass("com.android.server.wm.ActivityTaskManagerService",loader);
        HookCallback taskLabels=new HookCallback(){
            @Override protected void afterHookedMethod(Param m)throws Throwable{
                if(m.hasThrowable()||m.getResult()==null||m.args.length==0)return;
                Object result=m.getResult();List<?> list=result instanceof List?(List<?>)result:(List<?>)Reflect.call(result,"getList");
                for(Object task:list){
                    int id=HookReflect.getIntField(task,"userId");if(!isClone(id))continue;
                    Intent base=(Intent)HookReflect.getObjectField(task,"baseIntent");
                    if(base==null||base.getComponent()==null)continue;
                    ActivityManager.TaskDescription old=(ActivityManager.TaskDescription)HookReflect.getObjectField(task,"taskDescription");
                    if(old==null)continue;
                    ActivityManager.TaskDescription copy=new ActivityManager.TaskDescription(old);
                    HookReflect.callMethod(copy,"setLabel",CloneSlots.label(baseLabel(base.getComponent().getPackageName()),id));
                    HookReflect.setObjectField(task,"taskDescription",copy);
                }
            }
        };
        Hooks.hookAllMethods(tasks,"getRecentTasks",taskLabels);Hooks.hookAllMethods(tasks,"getTasks",taskLabels);
    }
}
