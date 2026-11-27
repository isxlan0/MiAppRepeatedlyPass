package io.github.isxlan0.miappxspace;

import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

final class Hooks {
    static XposedInterface api;
    private static final ThreadLocal<java.util.List<XposedInterface.HookHandle>> installing=new ThreadLocal<>();
    static void begin(){installing.set(new java.util.ArrayList<>());}
    static int commit(){
        java.util.List<XposedInterface.HookHandle> handles=installing.get();
        if(handles==null||handles.isEmpty())throw new IllegalStateException("No functional hooks installed");
        int count=handles.size();installing.remove();return count;
    }
    static void rollback(){
        java.util.List<XposedInterface.HookHandle> handles=installing.get();installing.remove();
        if(handles!=null)for(int i=handles.size()-1;i>=0;i--)try{handles.get(i).unhook();}catch(Throwable error){log(error);}
    }
    static void log(String message) { api.log(Log.INFO, "MiAppXSpace", message); }
    static void log(Throwable error) { api.log(Log.ERROR, "MiAppXSpace", "Hook failure", error); }

    static Object intercept(XposedInterface.Chain chain, HookCallback callback) throws Throwable {
        HookCallback.Param p=new HookCallback.Param();
        p.method=chain.getExecutable(); p.thisObject=chain.getThisObject(); p.args=chain.getArgs().toArray();
        try { callback.beforeHookedMethod(p); }
        catch(Throwable error) {
            RuntimeHealth.callbackFailure(p.method,error);
            p.restore(null,null,false);
        }
        if(!p.skip) {
            try { p.restore(chain.proceed(p.args),null,false); }
            catch(Throwable original) { p.restore(null,original,false); }
        }
        Object result=p.getResult(); Throwable failure=p.getThrowable(); boolean early=p.skip;
        try { callback.afterHookedMethod(p); }
        catch(Throwable error) {
            RuntimeHealth.callbackFailure(p.method,error);
            p.restore(result,failure,early);
        }
        if(p.hasThrowable())throw p.getThrowable();
        return p.getResult();
    }

    static XposedInterface.HookHandle hookMethod(Executable method,HookCallback callback) {
        // Explicit setThrowable refusals must reach the caller. Accidental callback
        // exceptions are handled above, without swallowing the original exception.
        XposedInterface.HookHandle handle=api.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> intercept(chain,callback));
        if(installing.get()!=null)installing.get().add(handle);return handle;
    }
    static Set<XposedInterface.HookHandle> hookAllMethods(Class<?> type,String name,HookCallback callback) {
        Set<XposedInterface.HookHandle> result=new HashSet<>();
        for(Method method:type.getDeclaredMethods())if(method.getName().equals(name))result.add(hookMethod(method,callback));
        return result;
    }
}
