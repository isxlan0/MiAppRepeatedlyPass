package io.github.isxlan0.miappxspace;

import android.os.UserHandle;



final class ClientHook {
    static void install(PackageContext p)throws Throwable{
        Class<?> xspace=HookReflect.findClass("miui.securityspace.XSpaceUserHandle",p.classLoader);
        HookReflect.findAndHookMethod(xspace,"isXSpaceUserId",int.class,new HookCallback(){
            @Override protected void afterHookedMethod(Param m){if(ProfileRpc.extra((Integer)m.args[0]))m.setResult(true);}
        });
        // Clone numbers identify instances; return plain icons without changing users.
        Class<?> pm=HookReflect.findClass("android.app.ApplicationPackageManager",p.classLoader);
        Hooks.hookAllMethods(pm,"getUserBadgedIcon",new HookCallback(){
            @Override protected void beforeHookedMethod(Param m)throws Throwable{
                if(m.args.length>1&&m.args[1] instanceof UserHandle){
                    int id=(Integer)Reflect.call(m.args[1],"getIdentifier");
                    if(id==999||ProfileRpc.extra(id))m.setResult(m.args[0]);
                }
            }
        });
        Class<?> factory=HookReflect.findClass("android.util.IconDrawableFactory",p.classLoader);
        Hooks.hookAllMethods(factory,"getBadgedIcon",new HookCallback(){
            @Override protected void beforeHookedMethod(Param m)throws Throwable{
                if(m.args.length!=3||!(m.args[1] instanceof android.content.pm.ApplicationInfo)||!(m.args[2] instanceof Integer))return;
                int id=(Integer)m.args[2];if(id!=999&&!ProfileRpc.extra(id))return;
                Object packageManager=HookReflect.getObjectField(m.thisObject,"mPm");
                Object icon=Reflect.call(packageManager,"loadUnbadgedItemIcon",m.args[0],m.args[1]);
                m.setResult(icon);
            }
        });
        Hooks.hookAllMethods(pm,"getUserBadgedLabel",new HookCallback(){
            @Override protected void beforeHookedMethod(Param m)throws Throwable{
                int id=(Integer)Reflect.call(m.args[1],"getIdentifier");
                if(id==999||ProfileRpc.extra(id))m.setResult(m.args[0]);
            }
        });
        Hooks.log(ProfileHook.TAG+": client hooks ready "+p.packageName+"/"+p.processName);
    }
}
