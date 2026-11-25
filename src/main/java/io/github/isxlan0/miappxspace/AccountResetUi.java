package io.github.isxlan0.miappxspace;

import android.app.Activity;
import android.content.DialogInterface;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.*;

/** Keeps the original MIUI confirmation and handles its confirmed whole-account action. */
final class AccountResetUi {
    static final AtomicBoolean running=new AtomicBoolean();
    static void install(ClassLoader loader){
        XposedHelpers.findAndHookMethod("miuix.appcompat.app.l",loader,"i",int.class,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam m)throws Throwable{
                // Resource ID is pinned to this SecurityCore build's account-delete prompt.
                if(!Integer.valueOf(2131953200).equals(m.args[0]))return;
                Reflect.call(m.thisObject,"j","确认后将删除系统分身、全部系统以及模块提供的新增分身及其数据，请谨慎删除！！！");
                m.setResult(null);
            }
        });
        XposedBridge.hookAllMethods(XposedHelpers.findClass("l3.b",loader),"onCreatePreferences",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam m)throws Throwable{
                int[] ids=ProfileRpc.call("reset-plan",null,-1).getIntArray("ids");
                if(ids.length==0)return;
                Object category=XposedHelpers.getObjectField(m.thisObject,"a"),row=XposedHelpers.getObjectField(m.thisObject,"b");
                Reflect.call(category,"g",row);Reflect.call(row,"setSummary","删除系统提供分身及全部模块所新增分身。");
            }
        });
        XposedBridge.hookAllMethods(XposedHelpers.findClass("i3.c",loader),"onClick",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam m)throws Throwable{
                if(XposedHelpers.getIntField(m.thisObject,"a")!=0||!Integer.valueOf(-1).equals(m.args[1]))return;
                Activity activity=(Activity)XposedHelpers.getObjectField(m.thisObject,"b");
                if(!activity.getClass().getName().equals("com.miui.xspace.ui.activity.XSpaceUninstallActivity"))return;
                m.setResult(null);if(!running.compareAndSet(false,true))return;
                ((DialogInterface)m.args[0]).dismiss();
                SecurityUi.async(activity,"正在删除全部分身及数据…",()->{
                    try{ProfileRpc.call("reset",null,-1);}
                    finally{running.set(false);}
                },()->{
                    activity.getSharedPreferences("miapp_xspace_defaults",0).edit().clear().commit();
                    android.provider.Settings.Secure.putInt(activity.getContentResolver(),"xSpace_used",0);
                    android.widget.Toast.makeText(activity,"已删除多开账户及全部分身,请重启设备。",android.widget.Toast.LENGTH_LONG).show();
                    activity.finish();
                });
            }
        });
    }
}
