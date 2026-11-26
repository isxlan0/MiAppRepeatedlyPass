package io.github.isxlan0.miappxspace;

import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import java.io.*;
import java.util.*;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Narrow extension of native CLONE profiles; no APK or PackageSetting rewrites. */
public final class ProfileHook implements IXposedHookLoadPackage {
    static final String TAG="MiAppXSpace";
    private static final ThreadLocal<Integer> requested=new ThreadLocal<>();
    private static volatile Properties cached=new Properties();
    private static volatile long checked;
    static boolean extra(int id){
        if(id<CloneRegistry.MIN||id>CloneRegistry.MAX)return false;
        long now=android.os.SystemClock.elapsedRealtime();
        if(now-checked>500){
            try{cached=CloneRegistry.read();checked=now;}catch(IOException e){Log.e(TAG,"Registry unavailable",e);return false;}
        }
        return CloneRegistry.extra(id,cached)||(requested.get()!=null&&requested.get()==id);
    }
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p)throws Throwable{
        if(Build.VERSION.SDK_INT!=37){
            XposedBridge.log(TAG+": unsupported Android API");return;
        }
        if(!"android".equals(p.packageName)){
            if("com.miui.home".equals(p.packageName)||"com.miui.securitycore".equals(p.packageName)||"com.miui.securitycenter".equals(p.packageName)||"com.android.systemui".equals(p.packageName)){
                if("com.miui.securitycore".equals(p.packageName))try{JavaBindings.prepare(p.appInfo.sourceDir,p.classLoader);}
                catch(Exception error){XposedBridge.log(TAG+": Java adaptation refused: "+error);return;}
                ClientHook.install(p);
                if("com.miui.securitycore".equals(p.packageName))SecurityUi.install(p.classLoader);
                if("com.miui.securitycenter".equals(p.packageName))try{ManagementHooks.install(p.appInfo.sourceDir,p.classLoader);}
                catch(Exception error){XposedBridge.log(TAG+": App management adaptation refused: "+error);}
            }
            return;
        }
        if(!("android".equals(p.processName)||"system_server".equals(p.processName)))return;
        try{JavaPrepare.framework(new File("/system"),new File("/system_ext"),JavaPrepare.patterns(),new Properties());}
        catch(Exception error){XposedBridge.log(TAG+": Framework adaptation refused: "+error);return;}
        Class<?> ums=XposedHelpers.findClass("com.android.server.pm.UserManagerService",p.classLoader);
        Class<?> details=XposedHelpers.findClass("com.android.server.pm.UserTypeDetails",p.classLoader);
        Class<?> xspace=XposedHelpers.findClass("miui.securityspace.XSpaceUserHandle",p.classLoader);
        // The stock SecurityCore selector uses the first XSpace entry; preserve its 999 target.
        if(XposedBridge.hookAllMethods(ums,"getProfiles",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam m)throws Throwable{
                if(m.hasThrowable()||!(m.getResult() instanceof List))return;
                List<?> result=(List<?>)m.getResult();Object stock=null;
                for(Object user:result)if(user.getClass().getField("id").getInt(user)==999){stock=user;break;}
                if(stock==null)return;
                List<Object> ordered=new ArrayList<>(result);ordered.remove(stock);
                int at=0;if(!ordered.isEmpty()&&ordered.get(0).getClass().getField("id").getInt(ordered.get(0))==0)at=1;
                ordered.add(at,stock);m.setResult(ordered);
            }
        }).isEmpty())throw new IllegalStateException("Profile query method missing");
        XC_MethodHook quota=new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam m)throws Throwable{
                if(CloneRegistry.TYPE.equals(Reflect.call(m.thisObject,"getName"))){
                    // Existing legacy spaces remain usable without consuming the three new slots.
                    int legacy=0;Properties p=CloneRegistry.read();
                    for(int id=CloneRegistry.MIN;id<997;id++)if(CloneRegistry.extra(id,p)){
                        Object u=Reflect.call(XposedHelpers.getStaticObjectField(ums,"sInstance"),"getUserInfo",id);
                        if(u!=null&&CloneRegistry.TYPE.equals(u.getClass().getField("userType").get(u))&&Integer.valueOf(0).equals(u.getClass().getField("profileGroupId").get(u)))legacy++;
                    }
                    m.setResult(CloneRegistry.LIMIT+legacy);
                }
            }
        };
        if(XposedBridge.hookAllMethods(details,"getMaxAllowed",quota).isEmpty()||XposedBridge.hookAllMethods(details,"getMaxAllowedPerParent",quota).isEmpty())throw new IllegalStateException("CLONE quota methods missing");
        // Only a root request matching the protected registry may select a reserved ID.
        if(XposedBridge.hookAllMethods(ums,"createUserInternalUnchecked",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam m)throws Throwable{
                m.setObjectExtra("previous",requested.get());
                if(!CloneRegistry.TYPE.equals(m.args[1])||!Integer.valueOf(0).equals(m.args[3])||(Binder.getCallingUid()!=0&&Binder.getCallingUid()!=1000))return;
                int target=-1;
                for(int id=CloneRegistry.MAX;id>=CloneRegistry.MIN;id--)if(CloneRegistry.name(id).equals(m.args[0])){target=id;break;}
                if(target<0)return;
                try{
                    int id=target;Properties registry=CloneRegistry.read();
                    if(!CloneSlots.allowed(id))throw new SecurityException("Only 998 and 997 may be created by this module");
                    if(!registry.containsKey("pending."+id)||!CloneRegistry.extra(id,registry))throw new SecurityException("No protected creation request for "+id);
                        Object existing=Reflect.call(m.thisObject,"getUserInfo",id);
                        if(existing!=null)throw new IllegalStateException("Requested ID already exists: "+id);
                        synchronized(XposedHelpers.getObjectField(m.thisObject,"mUsersLock")){
                            android.util.SparseBooleanArray removing=(android.util.SparseBooleanArray)XposedHelpers.getObjectField(m.thisObject,"mRemovingUserIds");
                            if(removing.get(id))throw new IllegalStateException("ID "+id+" is reserved after removal until reboot; use another ID or reboot before reusing it");
                        }
                        requested.set(id);m.args[2]=((Integer)m.args[2])|CloneRegistry.FLAG;
                        cached=registry;checked=android.os.SystemClock.elapsedRealtime();
                        Log.i(TAG,"Approved native CLONE creation id="+id);return;
                }catch(Throwable error){
                    // Xposed swallows callback exceptions; explicitly abort to prevent fallback ID allocation.
                    Log.e(TAG,"Refused module profile request "+target,error);m.setThrowable(error);
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam m){
                Integer previous=(Integer)m.getObjectExtra("previous");if(previous==null)requested.remove();else requested.set(previous);
            }
        }).isEmpty())throw new IllegalStateException("Native creation method missing");
        XposedHelpers.findAndHookMethod(xspace,"checkAndGetXSpaceUserId",int.class,int.class,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam m){
                Integer id=requested.get();if(id!=null&&(((Integer)m.args[0])&CloneRegistry.FLAG)!=0)m.setResult(id);
            }
        });
        XposedHelpers.findAndHookMethod(xspace,"isXSpaceUserId",int.class,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam m){if(extra((Integer)m.args[0]))m.setResult(true);}
        });
        XposedHelpers.findAndHookMethod(xspace,"canCrossUser",int.class,int.class,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam m){
                int from=(Integer)m.args[0],to=(Integer)m.args[1];
                if((from==0&&extra(to))||(to==0&&extra(from)))m.setResult(true);
            }
        });
        Class<?> manager=XposedHelpers.findClass("com.miui.server.xspace.XSpaceManagerServiceImpl",p.classLoader);
        ProfileBackend.install(p.classLoader);
        DisplayHooks.install(p.classLoader);
        ManagementRouting.install(p.classLoader);
        // Extend the existing MIUI resolver using actual installed native instances.
        if(XposedBridge.hookAllMethods(manager,"checkXSpaceControl",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam m)throws Throwable{
                ProfileRouting.check(m);
            }
        }).isEmpty())throw new IllegalStateException("XSpace launch control missing");
        if(XposedBridge.hookAllMethods(manager,"resolveXSpaceIntent",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam m){
                if(extra((Integer)m.args[6])&&!((android.content.Intent)m.args[1]).hasExtra("android.intent.extra.xspace_cached_uid"))m.setResult(m.args[0]);
            }
        }).isEmpty())throw new IllegalStateException("XSpace intent resolver missing");
        XposedBridge.hookAllMethods(manager,"getUserTypeCount",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam m){
                int count=(Integer)m.getResult();for(int id:(int[])m.args[0])if(extra(id))count--;m.setResult(count);
            }
        });
        Registry.DIR.mkdirs();Properties status=new Properties();
        status.setProperty("pid",Integer.toString(Process.myPid()));status.setProperty("rom",Build.VERSION.INCREMENTAL);
        status.setProperty("mode","native-clone-profiles");status.setProperty("version",BuildConfig.VERSION_NAME);
        try(FileOutputStream out=new FileOutputStream(new File(Registry.DIR,"hook.status"))){status.store(out,TAG);}
        XposedBridge.log(TAG+": native CLONE hooks ready pid="+Process.myPid());
    }
}
