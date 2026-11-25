package io.github.isxlan0.miappxspace;

import android.os.UserHandle;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

final class ClientHook {
    static void install(XC_LoadPackage.LoadPackageParam p)throws Throwable{
        Class<?> xspace=XposedHelpers.findClass("miui.securityspace.XSpaceUserHandle",p.classLoader);
        XposedHelpers.findAndHookMethod(xspace,"isXSpaceUserId",int.class,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam m){if(ProfileRpc.extra((Integer)m.args[0]))m.setResult(true);}
        });
        // Clone numbers identify instances; return plain icons without changing users.
        Class<?> pm=XposedHelpers.findClass("android.app.ApplicationPackageManager",p.classLoader);
        XposedBridge.hookAllMethods(pm,"getUserBadgedIcon",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam m)throws Throwable{
                if(m.args.length>1&&m.args[1] instanceof UserHandle){
                    int id=(Integer)Reflect.call(m.args[1],"getIdentifier");
                    if(id==999||ProfileRpc.extra(id))m.setResult(m.args[0]);
                }
            }
        });
        Class<?> factory=XposedHelpers.findClass("android.util.IconDrawableFactory",p.classLoader);
        XposedBridge.hookAllMethods(factory,"getBadgedIcon",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam m)throws Throwable{
                if(m.args.length!=3||!(m.args[1] instanceof android.content.pm.ApplicationInfo)||!(m.args[2] instanceof Integer))return;
                int id=(Integer)m.args[2];if(id!=999&&!ProfileRpc.extra(id))return;
                Object packageManager=XposedHelpers.getObjectField(m.thisObject,"mPm");
                Object icon=Reflect.call(packageManager,"loadUnbadgedItemIcon",m.args[0],m.args[1]);
                m.setResult(icon);
            }
        });
        XposedBridge.hookAllMethods(pm,"getUserBadgedLabel",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam m)throws Throwable{
                int id=(Integer)Reflect.call(m.args[1],"getIdentifier");
                if(id==999||ProfileRpc.extra(id))m.setResult(m.args[0]);
            }
        });
        XposedBridge.log(ProfileHook.TAG+": client hooks ready "+p.packageName+"/"+p.processName);
    }
}
