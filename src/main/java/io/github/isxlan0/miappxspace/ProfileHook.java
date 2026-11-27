package io.github.isxlan0.miappxspace;

import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import java.io.*;
import java.util.*;



/** Narrow extension of native CLONE profiles; no APK or PackageSetting rewrites. */
public final class ProfileHook extends io.github.libxposed.api.XposedModule {
    static final String TAG="MiAppXSpace";
    private static final ThreadLocal<Integer> requested=new ThreadLocal<>();
    private static volatile Properties cached=new Properties();
    private static volatile long checked;
    private String processName;
    private boolean systemServer;
    private final Set<String> loaded=new HashSet<>();
    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        Hooks.api=this; processName=param.getProcessName();systemServer=param.isSystemServer();
        RuntimeHealth.initialize(this,processName,param.isSystemServer());
    }
    @Override public void onSystemServerStarting(SystemServerStartingParam param) {
        load(new PackageContext("android",processName,param.getClassLoader(),null));
    }
    @Override public void onPackageReady(PackageReadyParam param) {
        if(!systemServer&&!"android".equals(param.getPackageName()))
            load(new PackageContext(param.getPackageName(),processName,param.getClassLoader(),param.getApplicationInfo()));
    }
    private synchronized void load(PackageContext p) {
        if(!RuntimeHealth.scoped(p.packageName)||!loaded.add(p.packageName))return;
        RuntimeHealth.begin(p.packageName);
        try {
            if("android".equals(p.packageName))RuntimeHealth.installEndpoint(p.classLoader);
            Hooks.begin();
            handleLoadPackage(p);
            RuntimeHealth.ready(Hooks.commit());
        }catch(Throwable error) {
            Hooks.rollback();
            RuntimeHealth.failure("加载/适配失败",error);
            Hooks.log(error);
        }
    }
    static void logEnvironment(PackageContext p){
        Hooks.log(TAG+": Runtime environment: module="+BuildConfig.VERSION_NAME+" ("+BuildConfig.VERSION_CODE+")"+
                "; libxposed API="+Hooks.api.getApiVersion()+"; package="+p.packageName+"; process="+p.processName);
        Hooks.log(TAG+": device="+Build.MANUFACTURER+" "+Build.MODEL+" ("+Build.DEVICE+")"+
                "; Android="+Build.VERSION.RELEASE+"; SDK="+Build.VERSION.SDK_INT+"; incremental="+Build.VERSION.INCREMENTAL);
        Hooks.log(TAG+": fingerprint="+Build.FINGERPRINT);
    }
    static void logRefusal(String stage,Exception error){
        // A single giant exception line is truncated by Android's log buffer.
        for(String line:(stage+": "+error).split("\n"))Hooks.log(TAG+": "+line);
    }
    static boolean extra(int id){
        if(id<CloneRegistry.MIN||id>CloneRegistry.MAX)return false;
        long now=android.os.SystemClock.elapsedRealtime();
        if(now-checked>500){
            try{cached=CloneRegistry.read();checked=now;}catch(IOException e){Log.e(TAG,"Registry unavailable",e);return false;}
        }
        return CloneRegistry.extra(id,cached)||(requested.get()!=null&&requested.get()==id);
    }
    private void handleLoadPackage(PackageContext p)throws Throwable{
        if("android".equals(p.packageName)||"com.miui.home".equals(p.packageName)||"com.miui.securitycore".equals(p.packageName)||"com.miui.securitycenter".equals(p.packageName)||"com.android.systemui".equals(p.packageName)||"com.android.settings".equals(p.packageName))logEnvironment(p);
        if(Build.VERSION.SDK_INT!=37){
            throw new IllegalStateException("Unsupported Android SDK: "+Build.VERSION.SDK_INT+" (requires 37)");
        }
        if(!"android".equals(p.packageName)){
            if("com.miui.home".equals(p.packageName)||"com.miui.securitycore".equals(p.packageName)||"com.miui.securitycenter".equals(p.packageName)||"com.android.systemui".equals(p.packageName)||"com.android.settings".equals(p.packageName)){
                if("com.miui.securitycore".equals(p.packageName))try{JavaBindings.prepare(p.appInfo,p.classLoader);}
                catch(Exception error){logRefusal("Java adaptation refused",error);throw error;}
                ClientHook.install(p);
                if("com.miui.securitycore".equals(p.packageName))SecurityUi.install(p.classLoader);
                if("com.miui.securitycenter".equals(p.packageName))try{ManagementHooks.install(p.appInfo,p.classLoader);}
                catch(Exception error){logRefusal("App management adaptation refused",error);throw error;}
                if("com.android.settings".equals(p.packageName))NotificationHooks.install(p.classLoader);
            }
            return;
        }
        // API 102 identifies this process through its lifecycle. LSPosed reports
        // its name as "system"; legacy process-name filters skip every hook here.
        if(!systemServer)throw new IllegalStateException("System hooks require the system-server lifecycle");
        final java.lang.reflect.Method creation;
        try{
            Properties framework=new Properties();
            JavaPrepare.framework(new File("/system"),new File("/system_ext"),JavaPrepare.patterns(),framework);
            String descriptor=JavaPrepare.required(framework,"framework.binding."+JavaPrepare.CREATE_USER);
            creation=(java.lang.reflect.Method)JavaBindings.resolve("M:"+descriptor,p.classLoader);
            Hooks.log(TAG+": Verified user creation method: "+descriptor);
        }
        catch(Exception error){logRefusal("Framework adaptation refused",error);throw error;}
        Class<?> ums=HookReflect.findClass("com.android.server.pm.UserManagerService",p.classLoader);
        Class<?> details=HookReflect.findClass("com.android.server.pm.UserTypeDetails",p.classLoader);
        Class<?> xspace=HookReflect.findClass("miui.securityspace.XSpaceUserHandle",p.classLoader);
        // The stock SecurityCore selector uses the first XSpace entry; preserve its 999 target.
        if(Hooks.hookAllMethods(ums,"getProfiles",new HookCallback(){
            @Override protected void afterHookedMethod(Param m)throws Throwable{
                if(m.hasThrowable()||!(m.getResult() instanceof List))return;
                List<?> result=(List<?>)m.getResult();Object stock=null;
                for(Object user:result)if(user.getClass().getField("id").getInt(user)==999){stock=user;break;}
                if(stock==null)return;
                List<Object> ordered=new ArrayList<>(result);ordered.remove(stock);
                int at=0;if(!ordered.isEmpty()&&ordered.get(0).getClass().getField("id").getInt(ordered.get(0))==0)at=1;
                ordered.add(at,stock);m.setResult(ordered);
            }
        }).isEmpty())throw new IllegalStateException("Profile query method missing");
        HookCallback quota=new HookCallback(){
            @Override protected void afterHookedMethod(Param m)throws Throwable{
                if(CloneRegistry.TYPE.equals(Reflect.call(m.thisObject,"getName"))){
                    // Existing legacy spaces remain usable without consuming the three new slots.
                    int legacy=0;Properties p=CloneRegistry.read();
                    for(int id=CloneRegistry.MIN;id<997;id++)if(CloneRegistry.extra(id,p)){
                        Object u=Reflect.call(HookReflect.getStaticObjectField(ums,"sInstance"),"getUserInfo",id);
                        if(u!=null&&CloneRegistry.TYPE.equals(u.getClass().getField("userType").get(u))&&Integer.valueOf(0).equals(u.getClass().getField("profileGroupId").get(u)))legacy++;
                    }
                    m.setResult(CloneRegistry.LIMIT+legacy);
                }
            }
        };
        if(Hooks.hookAllMethods(details,"getMaxAllowed",quota).isEmpty()||Hooks.hookAllMethods(details,"getMaxAllowedPerParent",quota).isEmpty())throw new IllegalStateException("CLONE quota methods missing");
        // Only a root request matching the protected registry may select a reserved ID.
        // Hook exactly the reviewed signature selected from this ROM, including trailing parameters.
        Hooks.hookMethod(creation,new HookCallback(){
            @Override protected void beforeHookedMethod(Param m)throws Throwable{
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
                        synchronized(HookReflect.getObjectField(m.thisObject,"mUsersLock")){
                            android.util.SparseBooleanArray removing=(android.util.SparseBooleanArray)HookReflect.getObjectField(m.thisObject,"mRemovingUserIds");
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
            @Override protected void afterHookedMethod(Param m){
                Integer previous=(Integer)m.getObjectExtra("previous");if(previous==null)requested.remove();else requested.set(previous);
            }
        });
        HookReflect.findAndHookMethod(xspace,"checkAndGetXSpaceUserId",int.class,int.class,new HookCallback(){
            @Override protected void beforeHookedMethod(Param m){
                Integer id=requested.get();if(id!=null&&(((Integer)m.args[0])&CloneRegistry.FLAG)!=0)m.setResult(id);
            }
        });
        HookReflect.findAndHookMethod(xspace,"isXSpaceUserId",int.class,new HookCallback(){
            @Override protected void afterHookedMethod(Param m){if(extra((Integer)m.args[0]))m.setResult(true);}
        });
        HookReflect.findAndHookMethod(xspace,"canCrossUser",int.class,int.class,new HookCallback(){
            @Override protected void afterHookedMethod(Param m){
                int from=(Integer)m.args[0],to=(Integer)m.args[1];
                if((from==0&&extra(to))||(to==0&&extra(from)))m.setResult(true);
            }
        });
        Class<?> manager=HookReflect.findClass("com.miui.server.xspace.XSpaceManagerServiceImpl",p.classLoader);
        ProfileBackend.install(p.classLoader);
        DisplayHooks.install(p.classLoader);
        ManagementRouting.install(p.classLoader);
        // Extend the existing MIUI resolver using actual installed native instances.
        if(Hooks.hookAllMethods(manager,"checkXSpaceControl",new HookCallback(){
            @Override protected void beforeHookedMethod(Param m)throws Throwable{
                ProfileRouting.check(m);
            }
        }).isEmpty())throw new IllegalStateException("XSpace launch control missing");
        if(Hooks.hookAllMethods(manager,"resolveXSpaceIntent",new HookCallback(){
            @Override protected void beforeHookedMethod(Param m){
                if(extra((Integer)m.args[6])&&!((android.content.Intent)m.args[1]).hasExtra("android.intent.extra.xspace_cached_uid"))m.setResult(m.args[0]);
            }
        }).isEmpty())throw new IllegalStateException("XSpace intent resolver missing");
        Hooks.hookAllMethods(manager,"getUserTypeCount",new HookCallback(){
            @Override protected void afterHookedMethod(Param m){
                int count=(Integer)m.getResult();for(int id:(int[])m.args[0])if(extra(id))count--;m.setResult(count);
            }
        });
        Hooks.log(TAG+": native CLONE hooks ready pid="+Process.myPid());
    }
}
