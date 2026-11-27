package io.github.isxlan0.miappxspace;

import dalvik.system.PathClassLoader;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;

/** Resolves actual ROM methods in a separate app_process; records registrations,
 * never installs hooks, invokes callbacks, writes health state or creates users. */
public final class SystemHookInstallTest {
    static final List<Executable> methods=new ArrayList<>();
    static Object proxy(Class<?> type,InvocationHandler handler){return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    static Object identity(Object p,Method m,Object[] a){
        if(m.getName().equals("hashCode"))return System.identityHashCode(p);
        if(m.getName().equals("equals"))return p==a[0];return null;
    }
    public static void main(String[] args)throws Throwable{
        XposedInterface api=(XposedInterface)proxy(XposedInterface.class,(p,m,a)->{
            switch(m.getName()){
                case "getApiVersion":return 102;
                case "getFrameworkName":return "API 102 installation recorder";
                case "getFrameworkVersion":return "test";
                case "getFrameworkVersionCode":return 1L;
                case "hook":
                    Executable target=(Executable)a[0];
                    return proxy(XposedInterface.HookBuilder.class,(b,operation,values)->{
                        if(operation.getName().equals("intercept")){
                            methods.add(target);
                            return proxy(XposedInterface.HookHandle.class,(h,call,params)->call.getName().equals("getExecutable")?target:identity(h,call,params));
                        }
                        return b;
                    });
                case "log":if(a.length>=3)System.out.println(a[2]);return null;
                default:return identity(p,m,a);
            }
        });
        ProfileHook module=new ProfileHook();module.attachFramework(api,()->{});
        module.onModuleLoaded(new io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam(){
            public boolean isSystemServer(){return true;}
            public String getProcessName(){return "system";}
        });
        ClassLoader loader=new PathClassLoader("/system/framework/services.jar:/system_ext/framework/miui-services.jar",SystemHookInstallTest.class.getClassLoader());
        Method entry=ProfileHook.class.getDeclaredMethod("handleLoadPackage",PackageContext.class);entry.setAccessible(true);
        Hooks.begin();
        try{entry.invoke(module,new PackageContext("android","system",loader,null));}
        catch(InvocationTargetException error){Hooks.rollback();throw error.getCause();}
        int count=Hooks.commit();
        for(String required:new String[]{"createUserInternalUnchecked","getMaxAllowed","getMaxAllowedPerParent","onTransact","checkXSpaceControl","resolveXSpaceIntent","isXSpaceUserId"}){
            if(methods.stream().noneMatch(m->m.getName().equals(required)))throw new AssertionError("Missing functional hook: "+required);
        }
        if(count<20)throw new AssertionError("Incomplete functional installation: "+count);
        System.out.println("PASS: API 102 process=system resolves "+count+" functional hooks on the current ROM; no real hooks installed");
    }
}
