package io.github.isxlan0.miappxspace;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import java.lang.reflect.*;
import java.util.*;
import de.robv.android.xposed.*;

/** Builds on SecurityCore's own activity, MIUI preference cells and dropdowns. */
final class NativePages {
    static final String DETAIL="com.miui.xspace.ui.activity.XSpaceDefaultAppActivity", USER="io.github.isxlan0.miappxspace.instance_user", STOCK="io.github.isxlan0.miappxspace.stock_enable";
    static void open(Context context,String pkg,int user){
        Intent intent=new Intent().setClassName("com.miui.securitycore",DETAIL).putExtra("package_name",pkg);
        if(user>=0)intent.putExtra(USER,user);
        context.startActivity(intent);
    }
    static void install(ClassLoader loader){
        Class<?> fragment=XposedHelpers.findClass("l3.k",loader);
        XposedBridge.hookAllMethods(fragment,"onCreatePreferences",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam m)throws Throwable{if(!m.hasThrowable())setup(m.thisObject);}
        });
        XposedBridge.hookAllMethods(fragment,"onResume",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam m)throws Throwable{if(XposedHelpers.getAdditionalInstanceField(m.thisObject,"miapp.page")!=null)refresh(m.thisObject);}
        });
        XposedBridge.hookAllMethods(XposedHelpers.findClass(DETAIL,loader),"onCreate",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam m){
                Activity activity=(Activity)m.thisObject;int id=activity.getIntent().getIntExtra(USER,-1);
                if(id>=0){android.widget.TextView title=(android.widget.TextView)activity.findViewById(2131362608);if(title!=null)title.setText(CloneSlots.label(title.getText(),id));}
                if(activity.getIntent().hasExtra(STOCK))try{
                    if(!Integer.valueOf(0).equals(Reflect.call(activity,"getLaunchedFromUid")))throw new SecurityException("仅 root 命令可调用系统分身创建入口。");
                    activity.getIntent().removeExtra(STOCK);
                    String pkg=activity.getIntent().getStringExtra("package_name");
                    SecurityUi.async(activity,"正在创建系统分身…",()->StockClone.change(activity,pkg,true),activity::finish);
                }catch(Exception e){SecurityUi.message(activity,e);}
            }
        });
    }
    static Object pref(Context c,String type,String key,String title)throws Exception{
        Class<?> cls=XposedHelpers.findClass(type,c.getClassLoader());Object value;
        try{value=cls.getConstructor(Context.class).newInstance(c);}
        catch(NoSuchMethodException missing){value=cls.getConstructor(Context.class,android.util.AttributeSet.class).newInstance(c,null);}
        Reflect.call(value,"setKey",key);Reflect.call(value,"setTitle",title);Reflect.call(value,"setPersistent",false);return value;
    }
    interface Change{boolean run(Object value)throws Exception;}
    static void listener(Object pref,String setter,Change change)throws Exception{
        Method found=null;for(Method method:pref.getClass().getMethods())if(method.getName().equals(setter)){found=method;break;}
        if(found==null)throw new NoSuchMethodException(setter);
        Class<?> type=found.getParameterTypes()[0];
        Object proxy=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(object,method,args)->{
            if(method.getName().equals("toString"))return "MiApp native preference listener";
            if(method.getName().equals("hashCode"))return System.identityHashCode(object);
            if(method.getName().equals("equals"))return object==args[0];
            try{return change.run(args.length>1?args[1]:args[0]);}catch(Exception e){SecurityUi.message((Context)Reflect.call(pref,"getContext"),e);return false;}
        });
        found.invoke(pref,proxy);
    }
    static void setup(Object fragment)throws Exception{
        Context c=(Context)Reflect.call(fragment,"getContext");Activity activity=(Activity)Reflect.call(fragment,"getActivity");
        String pkg=activity.getIntent().getStringExtra("package_name");if(pkg==null)return;
        Object screen=Reflect.call(fragment,"getPreferenceScreen");
        Object checkbox=Reflect.call(fragment,"findPreference","checkbox"),personal=Reflect.call(fragment,"findPreference","xspace_setting");
        int selected=activity.getIntent().getIntExtra(USER,-1);
        if(selected>=0){Reflect.call(screen,"j");}
        else{
            Reflect.call(checkbox,"setTitle","系统分身");Reflect.call(checkbox,"setSummary","使用系统提供分身,用户ID999,应用位ID为2");
            Reflect.call(checkbox,"setOrder",0);Reflect.call(personal,"setOrder",20);
        }
        Object category=pref(c,"miuix.preference.PreferenceCategory","miapp.instances",selected<0?"应用分身":"分身管理");
        Reflect.call(category,"setOrder",10);Reflect.call(screen,"g",category);
        XposedHelpers.setAdditionalInstanceField(fragment,"miapp.page",category);
        if(selected<0){
            Object note=pref(c,"miuix.preference.PreferenceCategory","miapp.limit","最多创建 3 个分身(系统提供1个+模块额外提供2个)");Reflect.call(note,"setOrder",11);Reflect.call(screen,"g",note);
            Object defaults=pref(c,"miuix.preference.DropDownPreference","miapp.default","默认打开应用");
            Reflect.call(personal,"g",defaults);XposedHelpers.setAdditionalInstanceField(fragment,"miapp.default",defaults);
            // Preserve WeChat/QQ's native payment/login/share rows; unknown apps use a general default row.
            for(String field:new String[]{"d","e","f"}){
                Object row=XposedHelpers.getObjectField(fragment,field);String key=(String)Reflect.call(row,"getKey");
                if(key.equals("send")||key.equals("login")||key.equals("open"))Reflect.call(row,"setVisible",false);
            }
            if(!pkg.equals("com.tencent.mm"))Reflect.call(XposedHelpers.getObjectField(fragment,"g"),"setVisible",false);
        }
        refresh(fragment);
    }
    static void dropdown(Object fragment,Object row,String resolverKey,int[] ids,CharSequence label)throws Exception{
        Context c=(Context)Reflect.call(fragment,"getContext");Activity activity=(Activity)Reflect.call(fragment,"getActivity");String pkg=activity.getIntent().getStringExtra("package_name");
        String[] entries=new String[ids.length+1],values=new String[ids.length+1];entries[0]="每次询问";values[0]="-1";
        for(int i=0;i<ids.length;i++){entries[i+1]=CloneSlots.label(label,ids[i]);values[i+1]=Integer.toString(ids[i]);}
        Reflect.call(row,"h",(Object)entries);Reflect.call(row,"i",(Object)values);
        Reflect.field(row,"p",values);
        SharedPreferences prefs=c.getSharedPreferences("miapp_xspace_defaults",0);String savedKey=pkg+"|"+resolverKey;
        int saved=prefs.getInt(savedKey,-1);
        if(saved>=0&&!CloneSlots.contains(ids,saved)){prefs.edit().remove(savedKey).apply();saved=-1;}
        Reflect.call(row,"k",Integer.toString(saved));Reflect.call(row,"setEnabled",ids.length>1);
        listener(row,"setOnPreferenceChangeListener",value->{
            int user=Integer.parseInt(value.toString());
            if(user<0)prefs.edit().remove(savedKey).apply();else prefs.edit().putInt(savedKey,user).apply();
            Class<?> settings=XposedHelpers.findClass("android.provider.MiuiSettings$XSpace",c.getClassLoader());
            XposedHelpers.callStaticMethod(settings,"setAskType",c,resolverKey,user==0?1:user==999?2:0);
            return true;
        });
    }
    static void refresh(Object fragment)throws Exception{
        Context c=(Context)Reflect.call(fragment,"getContext");Activity activity=(Activity)Reflect.call(fragment,"getActivity");
        if(activity==null||!SecurityUi.valid(c))return;
        String pkg=activity.getIntent().getStringExtra("package_name");int selected=activity.getIntent().getIntExtra(USER,-1);
        int[] ids=ProfileRpc.call("instances",pkg,-1).getIntArray("ids");
        CharSequence label=c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg,0));
        Object category=XposedHelpers.getAdditionalInstanceField(fragment,"miapp.page");Reflect.call(category,"j");
        if(selected>=0){
            Object start=pref(c,"miuix.preference.TextPreference","miapp.start","启动 "+CloneSlots.label(label,selected));Reflect.call(category,"g",start);
            listener(start,"setOnPreferenceClickListener",value->{SecurityUi.start(c,pkg,selected);return true;});
            Object remove=pref(c,"miuix.preference.TextPreference","miapp.remove","删除这个分身");Reflect.call(category,"g",remove);
            listener(remove,"setOnPreferenceClickListener",value->{
                Reflect.call(remove,"setTitle","确认删除 "+CloneSlots.label(label,selected));Reflect.call(remove,"setSummary","删除会清除这个分身的全部应用数据，再次点击确认。");
                listener(remove,"setOnPreferenceClickListener",again->{SecurityUi.async(c,"正在删除分身…",()->{
                    Bundle result=ProfileRpc.call("remove",pkg,selected);if(result.getBoolean("stock"))StockClone.change(c,pkg,false);
                },activity::finish);return true;});return true;
            });return;
        }
        int next=-1;try{next=CloneSlots.next(ids);}catch(IllegalStateException ignored){}
        for(int id:CloneSlots.USERS){
            boolean exists=CloneSlots.contains(ids,id);
            Object row=pref(c,"miuix.preference.TextPreference","miapp.user."+id,CloneSlots.label(label,id));
            android.graphics.drawable.Drawable icon=c.getPackageManager().getApplicationIcon(pkg);
            int size=c.getResources().getDimensionPixelSize(2131167671);
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(size,size,android.graphics.Bitmap.Config.ARGB_8888);
            icon.setBounds(0,0,size,size);icon.draw(new android.graphics.Canvas(bitmap));
            Reflect.call(row,"setIcon",new android.graphics.drawable.BitmapDrawable(c.getResources(),bitmap));
            Reflect.call(row,"h",exists?"已创建":"点击创建");Reflect.call(row,"setEnabled",exists||id==next);
            Reflect.call(category,"g",row);
            listener(row,"setOnPreferenceClickListener",value->{
                if(exists)open(c,pkg,id);
                else SecurityUi.async(c,"正在创建 "+CloneSlots.label(label,id)+"…",()->{
                    Bundle result=ProfileRpc.call("add",pkg,-1);
                    if(result.getBoolean("stock"))StockClone.change(c,pkg,true);
                    else SecurityUi.prepareClone(result.getInt("user"));
                },()->{try{refresh(fragment);}catch(Exception e){SecurityUi.message(c,e);}});
                return true;
            });
        }
        // Existing legacy data remains reachable for explicit cleanup, with its real fixed identity.
        for(int id:ids)if(id!=0&&!CloneSlots.allowed(id)){
            Object row=pref(c,"miuix.preference.TextPreference","miapp.legacy."+id,CloneSlots.label(label,id));
            Reflect.call(row,"setSummary","旧版本分身，可进入清理");Reflect.call(category,"g",row);listener(row,"setOnPreferenceClickListener",value->{open(c,pkg,id);return true;});
        }
        Object personal=Reflect.call(fragment,"findPreference","xspace_setting");Reflect.call(personal,"setEnabled",ids.length>1);
        Object defaults=XposedHelpers.getAdditionalInstanceField(fragment,"miapp.default");dropdown(fragment,defaults,"*",ids,label);
        Class<?> settings=XposedHelpers.findClass("android.provider.MiuiSettings$XSpace",c.getClassLoader());
        for(String field:new String[]{"d","e","f"}){
            Object row=XposedHelpers.getObjectField(fragment,field);String key=(String)Reflect.call(row,"getKey");
            String resolverKey=(String)XposedHelpers.callStaticMethod(settings,"getSettingAppType",key);
            if(!resolverKey.isEmpty())dropdown(fragment,row,resolverKey,ids,label);
        }
        Object checkbox=Reflect.call(fragment,"findPreference","checkbox");Reflect.call(checkbox,"setChecked",CloneSlots.contains(ids,999));
    }
}
