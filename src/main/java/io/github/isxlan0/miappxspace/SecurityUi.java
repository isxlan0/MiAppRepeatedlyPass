package io.github.isxlan0.miappxspace;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;


/** Extends the ROM's existing SecurityCore screens and MIUI resolver dialog. */
final class SecurityUi {
    static final String SETTING="com.miui.xspace.ui.activity.XSpaceSettingActivity";
    static void install(ClassLoader loader)throws Throwable{
        // The stock service must never adopt an extra CLONE when 999 is absent.
        Hooks.hookMethod(JavaBindings.method("stock.user"),new HookCallback(){
            @Override protected void beforeHookedMethod(Param m)throws Throwable{
                if(m.args.length==1&&m.args[0] instanceof UserManager)m.setResult(Reflect.call(m.args[0],"getUserInfo",999));
            }
        });
        AccountResetUi.install(loader);
        Hooks.hookMethod(JavaBindings.method("stock.change"),new HookCallback(){
            @Override protected void beforeHookedMethod(Param m)throws Throwable{
                if(m.args.length!=3||!(m.args[1] instanceof String)||!(m.args[2] instanceof Boolean))return;
                String pkg=(String)m.args[1];boolean enable=(Boolean)m.args[2];
                int[] ids=ProfileRpc.call("instances",pkg,-1).getIntArray("ids");
                if(enable&&!CloneSlots.contains(ids,999)){
                    try{CloneSlots.requireCreate(ids,999);}catch(Exception e){m.setThrowable(e);}
                }else if(!enable){
                    for(int id:ids)if(id!=0&&id!=999){m.setThrowable(new IllegalStateException("请先删除其余分身，再删除系统分身。"));break;}
                }
            }
        });
        Hooks.hookMethod(JavaBindings.method("ui.bind"),new HookCallback(){
            @Override protected void afterHookedMethod(Param m)throws Throwable{
                if(m.args.length!=2||!(m.args[1] instanceof Integer))return;
                List<?> data=(List<?>)JavaBindings.get("ui.data",m.thisObject);
                int position=(Integer)m.args[1];if(position<0||position>=data.size())return;
                Object item=data.get(position);String pkg=(String)JavaBindings.get("ui.package",item);
                if(pkg==null||!Registry.PACKAGE.matcher(pkg).matches())return;
                View row=(View)JavaBindings.get("ui.row",m.args[0]);Context context=row.getContext();
                CharSequence label=(CharSequence)JavaBindings.get("ui.label",item);
                row.setOnClickListener(v->NativePages.open(context,pkg,-1));
                TextView title=(TextView)row.findViewById(JavaBindings.resource("id/title"));
                if(title!=null){title.setText(label);title.setContentDescription(label+"，点击管理分身");}
                View toggle=row.findViewById(JavaBindings.resource("id/sliding_button"));
                if(toggle!=null){toggle.setVisibility(View.GONE);toggle.setEnabled(false);toggle.setClickable(false);}
                TextView summary=(TextView)row.findViewById(JavaBindings.resource("id/enable"));
                if(summary!=null){summary.setText("管理分身");summary.setVisibility(View.VISIBLE);}
            }
        });
        NativePages.install(loader);
        Hooks.hookMethod(JavaBindings.method("ui.resolve"),new HookCallback(){
            @Override protected void beforeHookedMethod(Param m)throws Throwable{
                Intent outer=(Intent)JavaBindings.get("resolver.outer",m.thisObject);
                String pkg=outer.getStringExtra("android.intent.extra.xspace_resolver_activity_aim_package");
                if(pkg==null)return;
                int[] ids=ProfileRpc.call("instances",pkg,-1).getIntArray("ids");
                if(ids==null||ids.length<2)return;
                chooser(m.thisObject,outer,pkg,ids);m.setResult(null);
            }
        });
    }
    static int dp(Context c,int n){return (int)(n*c.getResources().getDisplayMetrics().density+.5f);}
    static String name(int id){return CloneSlots.description(id);}
    static void message(Context c,Throwable e){Toast.makeText(c,e.getMessage(),Toast.LENGTH_LONG).show();}
    interface Work{void run()throws Exception;}
    static boolean valid(Context c){
        Context current=c;for(int i=0;i<8;i++){
            if(current instanceof Activity)return !((Activity)current).isFinishing()&&!((Activity)current).isDestroyed();
            if(!(current instanceof ContextWrapper))break;current=((ContextWrapper)current).getBaseContext();
        }
        return true;
    }
    static void async(Context c,String text,Work work,Runnable after){
        Toast.makeText(c,text,Toast.LENGTH_SHORT).show();
        new Thread(()->{
            Throwable error=null;try{work.run();}catch(Throwable e){error=e;}
            Throwable failure=error;new Handler(Looper.getMainLooper()).post(()->{
                if(!valid(c))return;
                if(failure!=null)message(c,failure);else after.run();
            });
        },"XSpace-management").start();
    }
    static void start(Context c,String pkg,int id){
        async(c,"正在启动分身…",()->{ProfileRpc.call("start",pkg,id);prepareClone(id);},()->{
            try{
                Intent launch=c.getPackageManager().getLaunchIntentForPackage(pkg);
                if(launch==null)throw new IllegalStateException("应用没有启动入口");
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);launch.putExtra("android.intent.extra.xspace_userid_selected",true);
                launch.putExtra("android.intent.extra.picked_user_id",id);
                Reflect.call(c,"startActivityAsUser",launch,HookReflect.newInstance(UserHandle.class,id));
            }catch(Exception e){message(c,e);}
        });
    }
    static void prepareClone(int id)throws Exception{
        // Also repairs visibility when the currently loaded system Hook predates this fix.
        ProfileStartup.boot(ProfileBackend.service("activity","android.app.IActivityManager"),
                            ProfileBackend.service("user","android.os.IUserManager"),id);
    }
    static void chooser(Object runner,Intent outer,String pkg,int[] ids)throws Exception{
        Activity activity=(Activity)JavaBindings.get("resolver.activity",runner);Context context=activity;
        Intent original=outer.getParcelableExtra("android.intent.extra.xspace_resolver_activity_original_intent");
        if(original==null)throw new IllegalArgumentException("Missing original Intent");
        JavaBindings.set("resolver.original",runner,original);JavaBindings.set("resolver.package",runner,pkg);
        String key=original.getComponent()==null?pkg:original.getComponent().getClassName();
        String calling=original.getStringExtra("miui.intent.extra.xspace_resolver_activity_calling_package");
        if("com.miui.voiceassist".equals(calling)||"com.google.android.googlequicksearchbox".equals(calling))key=calling+"-"+pkg;
        final String savedKey=pkg+"|"+key;
        SharedPreferences prefs=context.getSharedPreferences("miapp_xspace_defaults",0);
        int saved=prefs.getInt(savedKey,prefs.getInt(pkg+"|*",-1));
        Class<?> settings=HookReflect.findClass("android.provider.MiuiSettings$XSpace",context.getClassLoader());
        if(saved<0){int ask=(Integer)HookReflect.callStaticMethod(settings,"getAskType",context,key);if(ask==1)saved=0;else if(ask==2)saved=999;}
        for(int id:ids)if(id==saved){launch(runner,original,outer,id);return;}
        if(saved>=0)prefs.edit().remove(savedKey).apply();
        Object builder=JavaBindings.get("resolver.builder",runner);
        JavaBindings.call("dialog.cancelable",builder,false);JavaBindings.call("dialog.icon",builder);JavaBindings.call("dialog.title",builder,"选择要使用的应用");
        LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(context,20),dp(context,6),dp(context,20),dp(context,8));
        CheckBox remember=new CheckBox(context);remember.setText("下次默认选择此项，不再提示");
        LinearLayout list=new LinearLayout(context);list.setOrientation(LinearLayout.VERTICAL);
        CharSequence label=context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(pkg,0));
        for(int id:ids){
            LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(context,8),dp(context,12),dp(context,8),dp(context,12));
            ImageView image=new ImageView(context);
            android.graphics.drawable.Drawable fresh=context.getPackageManager().getApplicationIcon(pkg);
            image.setImageDrawable(id==0?fresh:context.getPackageManager().getUserBadgedIcon(fresh,(UserHandle)HookReflect.newInstance(UserHandle.class,id)));
            row.addView(image,new LinearLayout.LayoutParams(dp(context,48),dp(context,48)));
            TextView text=new TextView(context);text.setText(CloneSlots.label(label,id));text.setTextSize(17);text.setPadding(dp(context,16),0,0,0);row.addView(text);
            row.setContentDescription(label+"，"+name(id));row.setOnClickListener(v->{
                try{
                    if(remember.isChecked())prefs.edit().putInt(savedKey,id).apply();
                    launch(runner,original,outer,id);
                }catch(Exception e){message(context,e);}
            });list.addView(row);
        }
        ScrollView scroll=new ScrollView(context);scroll.addView(list);content.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(dp(context,360),dp(context,76)*ids.length)));
        content.addView(remember);JavaBindings.call("dialog.view",builder,content);
        JavaBindings.call("dialog.cancel",builder,android.R.string.cancel,(DialogInterface.OnClickListener)(d,n)->activity.finish());
    }
    static void launch(Object runner,Intent original,Intent outer,int id)throws Exception{
        Class<?> compat=HookReflect.findClass("miui.securityspace.XSpaceIntentCompat",runner.getClass().getClassLoader());
        HookReflect.callStaticMethod(compat,"prepareToLeaveUser",original,outer.getIntExtra("io.github.isxlan0.miappxspace.source_user",0));
        original.putExtra("android.intent.extra.xspace_userid_selected",true);
        original.putExtra("android.intent.extra.picked_user_id",id);
        android.util.Log.i(ProfileHook.TAG,"Selected package="+original.getComponent()+" actualUser="+id+" displayNumber="+(id==0?1:1001-id));
        JavaBindings.call("ui.launch",runner,id);
    }
}
