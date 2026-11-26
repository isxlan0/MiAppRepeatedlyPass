package io.github.isxlan0.miappxspace;

import android.app.Activity;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.UserHandle;
import de.robv.android.xposed.*;
import java.lang.reflect.Method;

final class NotificationHooks {
    private static final ThreadLocal<Boolean> singleInstance=new ThreadLocal<>();

    static Activity activity(Context context){
        for(int i=0;i<16&&context!=null;i++){
            if(context instanceof Activity)return (Activity)context;
            if(!(context instanceof ContextWrapper))break;
            Context next=((ContextWrapper)context).getBaseContext();if(next==context)break;context=next;
        }
        return null;
    }

    static int uid(Context context,String pkg,int user)throws Exception{
        ApplicationInfo app=(ApplicationInfo)Reflect.call(context.getPackageManager(),"getApplicationInfoAsUser",pkg,0,user);
        if(app==null||app.uid/100000!=user||(app.flags&ApplicationInfo.FLAG_INSTALLED)==0)
            throw new IllegalStateException("Missing clone "+pkg+" user="+user);
        return app.uid;
    }

    static void install(ClassLoader loader){
        Class<?> page=XposedHelpers.findClass("com.android.settings.notification.BaseNotificationSettings",loader);
        Class<?> helper=XposedHelpers.findClass("miui.util.NotificationFilterHelper",loader);
        Method create=XposedHelpers.findMethodExact(page,"onCreate",Bundle.class);
        Method associated=XposedHelpers.findMethodExact(helper,"resolveAssociatedUid",Context.class,UserHandle.class,String.class,boolean.class);
        Method enable=XposedHelpers.findMethodExact(helper,"enableNotifications",Context.class,String.class,boolean.class);
        final Method perUid=XposedHelpers.findMethodExact(helper,"enableNotifications",Context.class,String.class,int.class,boolean.class);
        XposedBridge.hookMethod(create,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam m)throws Throwable{
                    Activity activity=(Activity)Reflect.call(m.thisObject,"getActivity");
                    if(activity==null)return;
                    Intent intent=activity.getIntent();if(intent==null)return;
                    Bundle args=(Bundle)Reflect.call(m.thisObject,"getArguments");
                    Intent request=new Intent(intent);
                    if(args!=null)request.putExtra(ManagementIntent.ARGS,new Bundle(args));
                    int user=ManagementIntent.user(request,0);
                    if(!ManagementHooks.cloneUser(user))return;
                    String pkg=ManagementIntent.packageName(request);
                    // A channel subpage may belong to a different package (e.g. Xiaomi push).
                    if(args!=null){String target=args.getString("package",args.getString("packageName"));
                        if(target!=null&&!target.equals(pkg))return;}
                    if(pkg==null)return;
                    try{
                        int uid=uid(activity,pkg,user);
                        activity.setIntent(ManagementIntent.withIdentity(request,user,uid));
                        if(args!=null){args.putInt("uid",uid);args.putInt("userId",user);}
                    }catch(Exception error){m.setThrowable(new IllegalStateException("Cannot open clone notification settings",error));}
                }
            });
        // Keep the ROM's enterprise/forced-notification checks, but suppress its owner/999 mirroring.
        XposedBridge.hookMethod(associated,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam m){if(Boolean.TRUE.equals(singleInstance.get()))m.setResult(null);}
        });
        XposedBridge.hookMethod(enable,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam m){
                Context context=(Context)m.args[0];Activity activity=activity(context);
                if(activity==null)return;
                Intent intent=activity.getIntent();if(intent==null)return;
                String pkg=(String)m.args[1];
                if(!pkg.equals(ManagementIntent.packageName(intent)))return;
                int user=ManagementIntent.user(intent,0);
                if(!ManagementHooks.cloneUser(user))return;
                Boolean previous=singleInstance.get();singleInstance.set(true);
                try{
                    int uid=uid(context,pkg,user);
                    perUid.invoke(null,context,pkg,uid,m.args[2]);
                    m.setResult(null);
                }catch(Throwable error){m.setThrowable(error);}
                finally{if(previous==null)singleInstance.remove();else singleInstance.set(previous);}
            }
        });
    }
}
