package io.github.isxlan0.miappxspace;

import android.os.*;
import android.content.pm.ApplicationInfo;
import java.io.*;
import java.nio.channels.FileLock;
import java.util.*;
import java.util.concurrent.*;
import de.robv.android.xposed.*;

/** Runs with system identity. Mutations are restricted to the original SecurityCore process. */
final class ProfileBackend {
    static Object ums,pm,am;
    static Object service(String name,String type)throws Exception{
        IBinder b=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,name);
        return Class.forName(type+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,b);
    }
    static Object user(int id)throws Exception{return Reflect.call(ums,"getUserInfo",id);}
    static Object field(Object o,String name)throws Exception{return o.getClass().getField(name).get(o);}
    static boolean installed(String pkg,int id)throws Exception{
        ApplicationInfo app=(ApplicationInfo)Reflect.call(pm,"getApplicationInfo",pkg,0L,id);
        return app!=null&&(app.flags&ApplicationInfo.FLAG_INSTALLED)!=0;
    }
    static void verify(int id,Properties p)throws Exception{
        Object u=user(id);
        if(u==null||!CloneRegistry.TYPE.equals(field(u,"userType"))||!Integer.valueOf(0).equals(field(u,"profileGroupId"))||(((Integer)field(u,"flags"))&CloneRegistry.FLAG)==0||Boolean.TRUE.equals(field(u,"partial")))throw new SecurityException("Invalid XSpace CLONE "+id);
        if(id!=999&&!field(u,"serialNumber").toString().equals(p.getProperty("space."+id)))throw new SecurityException("Unowned XSpace profile "+id);
    }
    static int[] spaces(Properties p)throws Exception{
        ArrayList<Integer> list=new ArrayList<>();
        for(int id=CloneRegistry.MAX;id>=CloneRegistry.MIN;id--)if(CloneRegistry.extra(id,p))try{verify(id,p);list.add(id);}catch(Exception ignored){}
        int[] result=new int[list.size()];for(int i=0;i<result.length;i++)result[i]=list.get(i);return result;
    }
    static int[] instances(String pkg,Properties p)throws Exception{
        ArrayList<Integer> list=new ArrayList<>();
        for(int id:new int[]{0,999})if(user(id)!=null&&installed(pkg,id))list.add(id);
        for(int id:spaces(p))if(installed(pkg,id))list.add(id);
        int[] result=new int[list.size()];for(int i=0;i<result.length;i++)result[i]=list.get(i);return result;
    }
    static void boot(int id)throws Exception{
        ProfileStartup.boot(am,ums,id);
    }
    static boolean trusted(int uid,int pid){
        if(uid==0)return true;
        if(uid!=1000)return false;
        try(FileInputStream in=new FileInputStream("/proc/"+pid+"/cmdline")){
            byte[] bytes=new byte[256];int n=in.read(bytes);int end=0;while(end<n&&bytes[end]!=0)end++;
            String process=new String(bytes,0,end,"UTF-8");
            return process.equals("com.miui.securitycore")||process.equals("com.miui.securitycore.remote")||process.startsWith("com.miui.securitycore:");
        }catch(IOException e){return false;}
    }
    static void install(ClassLoader loader)throws Exception{
        Class<?> stub=XposedHelpers.findClass("android.os.IUserManager$Stub",loader);
        XposedBridge.hookAllMethods(stub,"onTransact",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam hook)throws Throwable{
                if(Integer.valueOf(0x00584302).equals(hook.args[0])){
                    Parcel in=(Parcel)hook.args[1],reply=(Parcel)hook.args[2];
                    try{
                        in.enforceInterface("android.os.IUserManager");int id=in.readInt();in.enforceNoDataAvail();
                        long token=Binder.clearCallingIdentity();boolean owned=false;
                        try{ums=hook.thisObject;Properties p=CloneRegistry.read();if(CloneRegistry.extra(id,p)){verify(id,p);owned=true;}}finally{Binder.restoreCallingIdentity(token);}
                        reply.writeNoException();reply.writeInt(owned?1:0);
                    }catch(Throwable e){reply.writeException(new SecurityException(e.toString()));}
                    hook.setResult(true);return;
                }
                if(!Integer.valueOf(ProfileRpc.TRANSACTION).equals(hook.args[0]))return;
                Parcel data=(Parcel)hook.args[1],reply=(Parcel)hook.args[2];
                Bundle result=new Bundle();long identity=0;boolean cleared=false;
                try{
                    data.enforceInterface(ProfileRpc.TOKEN);
                    String op=data.readString(),pkg=data.readString();int id=data.readInt();data.enforceNoDataAvail();
                    boolean readonly="spaces".equals(op);
                    if(!readonly&&!trusted(Binder.getCallingUid(),Binder.getCallingPid()))throw new SecurityException("Only owner SecurityCore may manage XSpace");
                    ums=hook.thisObject;
                    identity=Binder.clearCallingIdentity();cleared=true;
                    if(pm==null)pm=service("package","android.content.pm.IPackageManager");
                    if(am==null)am=service("activity","android.app.IActivityManager");
                    synchronized(ProfileBackend.class){
                        if(readonly){
                            result.putIntArray("ids",spaces(CloneRegistry.read()));
                            result.putString("version",BuildConfig.VERSION_NAME);
                        }
                        else if("instances".equals(op))execute(op,pkg,id,CloneRegistry.read(),result);
                        else try(FileOutputStream out=new FileOutputStream(new File(Registry.DIR,"profile-control.lock"),true);FileLock lock=out.getChannel().lock()){
                            execute(op,pkg,id,CloneRegistry.read(),result);
                        }
                    }
                }catch(Throwable e){
                    Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();
                    result.putString("error",cause.toString());android.util.Log.e(ProfileHook.TAG,"System RPC refused/failed",e);
                }finally{if(cleared)Binder.restoreCallingIdentity(identity);}
                reply.writeNoException();reply.writeBundle(result);hook.setResult(true);
            }
        });
    }
    static void execute(String op,String pkg,int id,Properties p,Bundle out)throws Exception{
        if("reset-plan".equals(op)){out.putIntArray("ids",ProfileReset.plan(p));return;}
        if("reset".equals(op)){ProfileReset.remove(p);out.putBoolean("complete",true);return;}
        if(pkg==null||!Registry.PACKAGE.matcher(pkg).matches())throw new IllegalArgumentException("Invalid package");
        if("instances".equals(op)){out.putIntArray("ids",instances(pkg,p));return;}
        if("add".equals(op)){
            if(!installed(pkg,0))throw new IllegalStateException("Original application missing");
            ApplicationInfo original=(ApplicationInfo)Reflect.call(pm,"getApplicationInfo",pkg,0L,0);
            if((original.flags&ApplicationInfo.FLAG_SYSTEM)!=0)throw new SecurityException("System applications cannot be cloned here");
            id=CloneSlots.next(instances(pkg,p));
            if(id==CloneSlots.STOCK){out.putBoolean("stock",true);out.putInt("user",id);return;}
            android.util.SparseBooleanArray removing=(android.util.SparseBooleanArray)XposedHelpers.getObjectField(ums,"mRemovingUserIds");
            if(removing.get(id))throw new IllegalStateException("这个分身空间刚被删除，需要重启后才能重新创建。");
            if(user(id)!=null)verify(id,p);
            if(user(id)==null){
                String token=UUID.randomUUID().toString();p.setProperty("space."+id,"pending:"+token);p.setProperty("pending."+id,token);CloneRegistry.write(p);
                try{
                    Object u=Reflect.call(ums,"createProfileForUserWithThrow",CloneRegistry.name(id),CloneRegistry.TYPE,0,0,null);
                    if(!Integer.valueOf(id).equals(field(u,"id")))throw new IllegalStateException("Unexpected created profile ID");
                    p.setProperty("space."+id,field(u,"serialNumber").toString());p.remove("pending."+id);CloneRegistry.write(p);
                }catch(Exception e){if(user(id)==null){p.remove("space."+id);p.remove("pending."+id);CloneRegistry.write(p);}throw e;}
            }
            verify(id,p);boot(id);
            if(!Integer.valueOf(1).equals(Reflect.call(pm,"installExistingPackageAsUser",pkg,id,4210688,4,null))||!installed(pkg,id))throw new IllegalStateException("Native package install failed");
            p.setProperty("app."+id+"."+pkg,field(user(id),"serialNumber").toString());CloneRegistry.write(p);
            out.putInt("user",id);out.putIntArray("ids",instances(pkg,p));return;
        }
        if(id==999){
            verify(id,p);
            if("start".equals(op)){if(!installed(pkg,id))throw new IllegalStateException("Instance missing");boot(id);out.putInt("user",id);return;}
            if("remove".equals(op)){
                for(int other:instances(pkg,p))if(other!=0&&other!=999)throw new IllegalStateException("请先删除其余分身，再删除系统分身。");
                out.putBoolean("stock",true);out.putInt("user",999);return;
            }
        }
        if(id<CloneRegistry.MIN||id>CloneRegistry.MAX)throw new SecurityException("Invalid clone target");
        verify(id,p);
        if("start".equals(op)){if(!installed(pkg,id))throw new IllegalStateException("Instance missing");boot(id);out.putInt("user",id);return;}
        if("remove".equals(op)){
            String key="app."+id+"."+pkg;
            if(!field(user(id),"serialNumber").toString().equals(p.getProperty(key)))throw new SecurityException("Instance installation not owned");
            if(installed(pkg,id)){
                CountDownLatch done=new CountDownLatch(1);int[] status={Integer.MIN_VALUE};
                Binder callback=new Binder(){
                    @Override protected boolean onTransact(int code,Parcel in,Parcel reply,int flags){
                        if(code==1){in.enforceInterface("android.content.pm.IPackageDeleteObserver");in.readString();status[0]=in.readInt();done.countDown();if(reply!=null)reply.writeNoException();return true;}return false;
                    }
                };
                Object observer=Class.forName("android.content.pm.IPackageDeleteObserver$Stub").getMethod("asInterface",IBinder.class).invoke(null,callback);
                Reflect.call(pm,"deletePackageAsUser",pkg,-1,observer,id,0);
                if(!done.await(25,TimeUnit.SECONDS)||status[0]!=1||installed(pkg,id))throw new IllegalStateException("Instance removal failed "+status[0]);
            }
            p.remove(key);CloneRegistry.write(p);out.putIntArray("ids",instances(pkg,p));return;
        }
        throw new IllegalArgumentException("Unknown operation "+op);
    }
}
