package io.github.isxlan0.miappxspace;

import android.app.Activity;
import android.content.DialogInterface;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.*;

/** Keeps the original MIUI confirmation and handles its confirmed whole-account action. */
final class AccountResetUi {
    static final AtomicBoolean running=new AtomicBoolean();
    static void install(ClassLoader loader){
        XposedBridge.hookMethod(JavaBindings.method("dialog.messageId"),new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam m)throws Throwable{
                // Resolve the account-delete prompt by its resource identity.
                if(!Integer.valueOf(JavaBindings.resource("string/stop_xspace_alert_content")).equals(m.args[0]))return;
                JavaBindings.call("dialog.message",m.thisObject,"确认后将删除系统分身、全部系统以及模块提供的新增分身及其数据，请谨慎删除！！！");
                m.setResult(null);
            }
        });
        XposedBridge.hookMethod(JavaBindings.method("reset.preferences"),new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam m)throws Throwable{
                int[] ids=ProfileRpc.call("reset-plan",null,-1).getIntArray("ids");
                if(ids.length==0)return;
                Object category=JavaBindings.get("reset.category",m.thisObject),row=JavaBindings.get("reset.row",m.thisObject);
                JavaBindings.call("pref.add",category,row);Reflect.call(row,"setSummary","删除系统提供分身及全部模块所新增分身。");
            }
        });
        XposedBridge.hookMethod(JavaBindings.method("reset.click"),new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam m)throws Throwable{
                if(((Integer)JavaBindings.get("reset.kind",m.thisObject))!=0||!Integer.valueOf(-1).equals(m.args[1]))return;
                Activity activity=(Activity)JavaBindings.get("reset.activity",m.thisObject);
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
