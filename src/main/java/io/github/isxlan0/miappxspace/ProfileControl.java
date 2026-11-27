package io.github.isxlan0.miappxspace;

import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.channels.FileLock;
import java.util.*;

public final class ProfileControl {
    static final String CMD="/system/bin/cmd";
    static Object users;
    static Object field(Object o,String name)throws Exception{return o.getClass().getField(name).get(o);}
    static Object info(int id)throws Exception{
        if(users==null){
            Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"user");
            users=Class.forName("android.os.IUserManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        }
        return Reflect.call(users,"getUserInfo",id);
    }
    static int id(String arg){int id=Integer.parseInt(arg);if(id<CloneRegistry.MIN||id>999)throw new IllegalArgumentException("Use CLONE profile ID 900..999");return id;}
    static void ready()throws Exception{
        if(!Control.ready())throw new IllegalStateException("System Hook not active; enable System Framework (system) scope and reboot");
        Properties s=new Properties();try(FileInputStream in=new FileInputStream(new File(Registry.DIR,"hook.status"))){s.load(in);}
        if(!"native-clone-profiles".equals(s.getProperty("mode")))throw new IllegalStateException("Previous alias Hook still loaded; reboot to load native CLONE Hook");
    }
    static Object verify(int id,Properties p,boolean own)throws Exception{
        Object user=info(id);if(user==null)throw new IllegalStateException("Profile not found: "+id);
        if(!CloneRegistry.TYPE.equals(field(user,"userType"))||((Integer)field(user,"profileGroupId"))!=0||(((Integer)field(user,"flags"))&CloneRegistry.FLAG)==0||Boolean.TRUE.equals(field(user,"partial")))throw new IllegalStateException("Not a complete parent-0 XSpace CLONE profile: "+user);
        if(own&&id!=999){
            String serial=p.getProperty("space."+id);
            if(!Integer.toString((Integer)field(user,"serialNumber")).equals(serial))throw new IllegalStateException("Profile ownership/serial mismatch; refusing operation on "+id);
        }
        return user;
    }
    static boolean installed(String pkg,int id)throws Exception{
        for(String line:Control.run(CMD,"package","list","packages","--user",Integer.toString(id),pkg).split("\n"))if(line.trim().equals("package:"+pkg))return true;return false;
    }
    static void pkg(String name){if(!Registry.PACKAGE.matcher(name).matches())throw new IllegalArgumentException("Invalid package name");}
    static void boot(int id)throws Exception{
        if(info(id)==null)throw new IllegalStateException("Profile not found: "+id);
        ProfileStartup.boot(ProfileBackend.service("activity","android.app.IActivityManager"),users,id);
        System.out.println("XSpace "+id+" ready: unlocked and visible on owner display");
    }
    static void space(int id,Properties p)throws Exception{
        ready();if(id!=998&&id!=997)throw new IllegalArgumentException("新增空间只允许 998、997；999 由系统双开服务创建。");
        if(info(id)!=null){verify(id,p,true);System.out.println("Existing owned CLONE profile "+id);return;}
        String token=UUID.randomUUID().toString();p.setProperty("space."+id,"pending:"+token);p.setProperty("pending."+id,token);CloneRegistry.write(p);
        try{
            String result=Control.run(CMD,"package","create-user","--profileOf","0","--user-type",CloneRegistry.TYPE,CloneRegistry.name(id));
            if(!result.matches("(?s).*Success: created user id "+id+"\\b.*"))throw new IOException("Unexpected profile result: "+result);
            Object user=verify(id,p,false);p.setProperty("space."+id,field(user,"serialNumber").toString());p.remove("pending."+id);CloneRegistry.write(p);
            System.out.println("Created native XSpace CLONE profile "+id+", serial="+field(user,"serialNumber"));
            boot(id);
        }catch(Exception e){
            if(info(id)==null){p.remove("space."+id);p.remove("pending."+id);CloneRegistry.write(p);}
            else System.err.println("Creation interrupted; profile registry retained. Use recover "+id+" after inspecting it.");
            throw e;
        }
    }
    static void recover(int id,Properties p)throws Exception{
        if(id==999||!p.containsKey("pending."+id))throw new IllegalArgumentException("No module creation pending for this ID");
        Object u=verify(id,p,false);if(!CloneRegistry.name(id).equals(field(u,"name")))throw new IllegalStateException("Profile name mismatch");
        p.setProperty("space."+id,field(u,"serialNumber").toString());p.remove("pending."+id);CloneRegistry.write(p);System.out.println("Recovered "+id);
    }
    static void create(String packageName,int id,Properties p)throws Exception{
        pkg(packageName);ready();if(!installed(packageName,0))throw new IllegalStateException("Original app must be installed in owner 0");
        ArrayList<Integer> existing=new ArrayList<>();
        if(info(999)!=null&&installed(packageName,999))existing.add(999);
        for(int other=CloneRegistry.MAX;other>=CloneRegistry.MIN;other--)if(CloneRegistry.extra(other,p)&&info(other)!=null&&installed(packageName,other))existing.add(other);
        int[] ids=new int[existing.size()];for(int i=0;i<ids.length;i++)ids[i]=existing.get(i);
        CloneSlots.requireCreate(ids,id);
        if(info(id)!=null&&installed(packageName,id))throw new IllegalStateException("App already installed in this profile; existing installations are not adopted");
        if(id==999){
            System.out.println(Control.run("/system/bin/am","start","-W","--user","0","-n","com.miui.securitycore/"+NativePages.DETAIL,"--es","package_name",packageName,"--ez",NativePages.STOCK,"true"));
            boolean complete=false;
            for(int attempt=0;attempt<350;attempt++){
                Object stock=info(999);
                if(stock!=null&&!Boolean.TRUE.equals(field(stock,"partial"))&&installed(packageName,999)&&Boolean.TRUE.equals(Reflect.call(users,"isUserUnlocked",999))){verify(999,p,false);complete=true;break;}
                SystemClock.sleep(100);
            }
            if(!complete)throw new IllegalStateException("系统分身未创建成功，请查看系统页面提示和日志。");
            System.out.println("系统分身创建成功："+packageName+" user=999 (应用2)");return;
        }
        if(id!=999&&info(id)==null)space(id,p);verify(id,p,id!=999);boot(id);
        Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"package");
        Object pm=Class.forName("android.content.pm.IPackageManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        // Match SecurityCore's native install flags and INSTALL_REASON_USER (4), preventing boot cleanup.
        Object result=Reflect.call(pm,"installExistingPackageAsUser",packageName,id,4210688,4,null);
        if(!Integer.valueOf(1).equals(result)||!installed(packageName,id))throw new IOException("Native install failed: "+result);
        Object u=info(id);p.setProperty("app."+id+"."+packageName,field(u,"serialNumber").toString());CloneRegistry.write(p);
        System.out.println("Native installExistingPackageAsUser succeeded: "+packageName+" user="+id+" reason=4");System.out.println(Control.run(CMD,"package","list","packages","-U","--user",Integer.toString(id),packageName));
    }
    static void remove(String packageName,int id,Properties p)throws Exception{
        pkg(packageName);Object u=verify(id,p,id!=999);String key="app."+id+"."+packageName;
        if(!field(u,"serialNumber").toString().equals(p.getProperty(key)))throw new IllegalStateException("App installation not owned by this module; refusing removal");
        if(installed(packageName,id))System.out.println(Control.run(CMD,"package","uninstall","--user",Integer.toString(id),packageName));
        if(installed(packageName,id))throw new IOException("App remains installed");p.remove(key);CloneRegistry.write(p);
        LauncherCleanup.repair(true);
    }
    static void removeSpace(int id,Properties p)throws Exception{
        if(id==999)throw new IllegalArgumentException("Never removes the stock profile 999");
        if(!p.getProperty("space."+id,"").matches("[0-9]+"))throw new IllegalStateException("No completed module-owned profile record");
        if(info(id)!=null){
            verify(id,p,true);
            System.out.println(Control.run(CMD,"package","remove-user","--wait",Integer.toString(id)));
            // The shell's removal callback precedes asynchronous user-state cleanup on this ROM.
            for(int attempts=0;attempts<80&&info(id)!=null;attempts++)SystemClock.sleep(250);
            if(info(id)!=null)throw new IOException("Profile cleanup still pending; registry retained, retry remove-space later");
        }
        p.remove("space."+id);p.remove("pending."+id);
        for(String k:new HashSet<>(p.stringPropertyNames()))if(k.startsWith("app."+id+"."))p.remove(k);CloneRegistry.write(p);
        LauncherCleanup.repair(true);
    }
    public static void main(String[] a){
        try{
            if(android.os.Process.myUid()!=0)throw new SecurityException("Root required");
            if(a.length==2&&a[0].equals("health")){ModuleHealth.update(new File(a[1]));System.exit(0);return;}
            if(a.length==0||a[0].equals("help")){System.out.println("MiApp XSpace "+BuildConfig.VERSION_NAME+"\nstatus | list | create-space ID | create PACKAGE ID | start PACKAGE ID | remove PACKAGE ID | remove-space ID | recover ID | boot-spaces | reset-plan | remove-all | launcher-plan | repair-launcher\n顺序为 999（应用2，系统分身）、998（应用3）、997（应用4）；每个应用最多三个分身。删除会清除对应数据。");return;}
            if(a[0].equals("status")){System.out.println("System hook active: "+Control.ready());try{ready();System.out.println("Mode: native-clone-profiles");}catch(Exception e){System.out.println(e.getMessage());}System.out.println(Control.run(CMD,"user","list","-v","--all"));return;}
            if(a[0].equals("list")){Properties p=CloneRegistry.read();for(String k:new TreeSet<>(p.stringPropertyNames()))System.out.println(k+"="+p.getProperty(k));return;}
            if(a[0].equals("refresh-titles")){LauncherTitles.refresh();return;}
            Registry.DIR.mkdirs();
            try(FileOutputStream f=new FileOutputStream(new File(Registry.DIR,"profile-control.lock"),true);FileLock l=f.getChannel().lock()){
                Properties p=CloneRegistry.read();String cmd=a[0];
                if(cmd.equals("launcher-plan"))LauncherCleanup.repair(false);
                else if(cmd.equals("repair-launcher"))LauncherCleanup.repair(true);
                else if(cmd.equals("create-space"))space(id(a[1]),p);
                else if(cmd.equals("create"))create(a[1],id(a[2]),p);
                else if(cmd.equals("remove"))remove(a[1],id(a[2]),p);
                else if(cmd.equals("remove-space"))removeSpace(id(a[1]),p);
                else if(cmd.equals("recover"))recover(id(a[1]),p);
                else if(cmd.equals("start")){
                    pkg(a[1]);int id=id(a[2]);verify(id,p,id!=999);if(!installed(a[1],id))throw new IllegalStateException("App not installed");boot(id);
                    String resolved=Control.run(CMD,"package","resolve-activity","--brief","--user",Integer.toString(id),"-a","android.intent.action.MAIN","-c","android.intent.category.LAUNCHER","-p",a[1]);
                    String component=null;for(String line:resolved.split("\n"))if(line.startsWith(a[1]+"/"))component=line.trim();
                    if(component==null)throw new IllegalStateException("No launcher: "+resolved);
                    System.out.println(Control.run("/system/bin/am","start","-W","--user",Integer.toString(id),"-n",component));
                }else if(cmd.equals("boot-spaces")){
                    for(String k:new TreeSet<>(p.stringPropertyNames()))if(k.startsWith("space."))try{int id=id(k.substring(6));verify(id,p,true);boot(id);}catch(Exception e){System.err.println(k+": "+e);}
                }else if(cmd.equals("reset-plan")){System.out.println(Arrays.toString(ProfileReset.plan(p)));
                }else if(cmd.equals("remove-module-spaces")){
                    for(String k:new TreeSet<>(p.stringPropertyNames()))if(k.startsWith("space."))removeSpace(id(k.substring(6)),p);
                }else if(cmd.equals("remove-all")){
                    ProfileReset.remove(p);
                    LauncherCleanup.repair(true);
                }else throw new IllegalArgumentException("Unknown command or wrong arguments; use help");
            }
        }catch(Throwable e){e.printStackTrace(System.err);System.exit(1);}
    }
}
