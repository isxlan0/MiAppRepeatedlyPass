package io.github.isxlan0.miappxspace;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import de.robv.android.xposed.*;
import java.util.Properties;

/** Normalize management launches before resolving an activity in a clone with no settings APK. */
final class ManagementRouting {
    static Intent normalize(Intent intent,int launchUser)throws Exception{
        if(!ManagementIntent.supported(intent))return null;
        if(launchUser!=0&&!DisplayHooks.isClone(launchUser))return null;
        int user=ManagementIntent.user(intent,launchUser);
        if(!DisplayHooks.isClone(user))return null;
        String pkg=ManagementIntent.packageName(intent);
        if(pkg==null||!Registry.PACKAGE.matcher(pkg).matches())throw new IllegalArgumentException("Missing managed package");
        if(ProfileBackend.pm==null)ProfileBackend.pm=ProfileBackend.service("package","android.content.pm.IPackageManager");
        if(ProfileBackend.ums==null)ProfileBackend.ums=ProfileBackend.service("user","android.os.IUserManager");
        Properties registry=CloneRegistry.read();ProfileBackend.verify(user,registry);
        ApplicationInfo app=(ApplicationInfo)Reflect.call(ProfileBackend.pm,"getApplicationInfo",pkg,0L,user);
        if(app==null||(app.flags&ApplicationInfo.FLAG_INSTALLED)==0)
            throw new IllegalStateException("Application is not installed for user "+user);
        return ManagementIntent.withIdentity(intent,user,app.uid);
    }

    static void install(ClassLoader loader){
        Class<?> supervisor=XposedHelpers.findClass("com.android.server.wm.ActivityTaskSupervisor",loader);
        XposedHelpers.findAndHookMethod("com.android.server.wm.ActivityStarter$Request",loader,
            "resolveActivity",supervisor,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam m){
                    Intent intent=(Intent)XposedHelpers.getObjectField(m.thisObject,"intent");
                    String caller=(String)XposedHelpers.getObjectField(m.thisObject,"callingPackage");
                    if(!ManagementIntent.caller(caller)||!ManagementIntent.supported(intent))return;
                    int launchUser=XposedHelpers.getIntField(m.thisObject,"userId");
                    long token=Binder.clearCallingIdentity();
                    try{
                        Intent corrected=normalize(intent,launchUser);if(corrected==null)return;
                        XposedHelpers.setObjectField(m.thisObject,"intent",corrected);
                        XposedHelpers.setIntField(m.thisObject,"userId",0);
                    }catch(Exception error){
                        // Abort a stale clone request; falling back would edit the owner application.
                        android.util.Log.e(ProfileHook.TAG,"Clone settings launch refused",error);
                        m.setThrowable(new IllegalStateException("Cannot resolve clone settings identity",error));
                    }finally{Binder.restoreCallingIdentity(token);}
                }
            });
    }
}
