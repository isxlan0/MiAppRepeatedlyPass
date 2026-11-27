package io.github.isxlan0.miappxspace;

import android.content.Context;
import android.content.pm.*;
import android.os.Process;
import android.util.SparseArray;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Extends SecurityCenter's own list/search models without ever substituting a clone UID. */
final class ManagementHooks {
    static final class Bindings {
        final Map<String,AccessibleObject> members=new HashMap<>();
        Bindings(String apk,ClassLoader loader)throws Exception{
            this(Collections.singletonList(new File(apk)),null,loader);
        }
        Bindings(ApplicationInfo app,ClassLoader loader)throws Exception{
            this(JavaBindings.apks(app),JavaBindings.cache(app,"management"),loader);
        }
        Bindings(List<File> apks,File cache,ClassLoader loader)throws Exception{
            Properties patterns=new Properties();
            try(InputStream in=ManagementHooks.class.getClassLoader().getResourceAsStream("assets/management-patterns.properties")){
                if(in==null)throw new IOException("Missing management patterns");patterns.load(in);
            }
            BindingCache.resolve(apks,patterns,cache,JavaBindings.runtime(),found->{
                members.clear();for(String key:found.stringPropertyNames())members.put(key,JavaBindings.resolve(found.getProperty(key),loader));
            });
        }
        Method method(String key){return (Method)members.get(key);}
        Object get(String key,Object target)throws Exception{return ((Field)members.get(key)).get(target);}
    }

    static boolean cloneUser(int id){return id==CloneSlots.STOCK||ProfileRpc.extra(id);}

    static PackageInfo labeled(PackageInfo original,PackageManager pm)throws Exception{
        int user=original.applicationInfo.uid/100000;
        if(!cloneUser(user))return original;
        PackageInfo copy=copy(original);
        ApplicationInfo owner=(ApplicationInfo)Reflect.call(pm,"getApplicationInfoAsUser",original.packageName,0,0);
        copy.applicationInfo.nonLocalizedLabel=CloneSlots.label(owner.loadLabel(pm),user);
        return copy;
    }

    static PackageInfo copy(PackageInfo original){
        android.os.Parcel parcel=android.os.Parcel.obtain();
        try{original.writeToParcel(parcel,0);parcel.setDataPosition(0);return PackageInfo.CREATOR.createFromParcel(parcel);}
        finally{parcel.recycle();}
    }

    static List<PackageInfo> packages(List<?> original,PackageManager pm,int[] users)throws Exception{
        LinkedHashMap<String,PackageInfo> result=new LinkedHashMap<>();
        for(Object item:original){PackageInfo p=(PackageInfo)item;if(p.applicationInfo!=null)result.put(key(p),labeled(p,pm));}
        for(int user:users){
            List<?> installed=(List<?>)Reflect.call(pm,"getInstalledPackagesAsUser",PackageManager.GET_SIGNATURES,user);
            for(Object item:installed){
                PackageInfo p=(PackageInfo)item;ApplicationInfo app=p.applicationInfo;
                if(app==null||app.uid/100000!=user||(app.flags&ApplicationInfo.FLAG_INSTALLED)==0||(app.flags&ApplicationInfo.FLAG_SYSTEM)!=0)continue;
                result.put(key(p),labeled(p,pm));
            }
        }
        return new ArrayList<>(result.values());
    }

    static String key(PackageInfo p){return p.applicationInfo.uid+":"+p.packageName;}

    @SuppressWarnings("unchecked")
    static void moveClones(Bindings bindings,Object model,String from,String to)throws Exception{
        List<Object> source=(List<Object>)bindings.get(from,model),target=(List<Object>)bindings.get(to,model);
        SparseArray<Map<String,Object>> byUser=(SparseArray<Map<String,Object>>)bindings.get("byUser",model);
        for(Iterator<Object> it=source.iterator();it.hasNext();){
            Object row=it.next();ApplicationInfo app=(ApplicationInfo)bindings.get("row.info",row);
            int user=app.uid/100000;if(!cloneUser(user))continue;
            if(!target.contains(row))target.add(row);it.remove();
            Map<String,Object> index=byUser.get(user);
            if(index==null){index=new HashMap<>();byUser.put(user,index);}
            index.put(app.packageName,row);
        }
    }

    static void install(ApplicationInfo app,ClassLoader loader)throws Exception{
        if(Process.myUid()/100000!=0)return;
        // Resolve the whole graph before installing any partial app-list changes.
        Bindings bindings=new Bindings(app,loader);
        Hooks.hookMethod(bindings.method("packages"),new HookCallback(){
            @Override protected void afterHookedMethod(Param m)throws Throwable{
                if(m.hasThrowable()||!(m.getResult() instanceof List))return;
                int[] extra=ProfileRpc.call("spaces",null,-1).getIntArray("ids");
                if(extra==null)throw new IllegalStateException("Missing registered profile list");
                int[] users=new int[extra.length+1];users[0]=CloneSlots.STOCK;System.arraycopy(extra,0,users,1,extra.length);
                m.setResult(packages((List<?>)m.getResult(),(PackageManager)m.args[0],users));
            }
        });
        Hooks.hookMethod(bindings.method("load"),new HookCallback(){
            @Override protected void afterHookedMethod(Param m)throws Throwable{
                if(m.hasThrowable()||m.getResult()==null)return;
                moveClones(bindings,m.getResult(),"work.visible","personal.visible");
                moveClones(bindings,m.getResult(),"work.all","personal.all");
            }
        });
        Hooks.log(ProfileHook.TAG+": app management list/search hooks ready");
    }
}
