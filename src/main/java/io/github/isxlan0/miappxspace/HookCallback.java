package io.github.isxlan0.miappxspace;

import java.lang.reflect.Executable;
import java.util.HashMap;
import java.util.Map;

/** Local before/after adapter. No legacy Xposed classes are loaded. */
abstract class HookCallback {
    protected void beforeHookedMethod(Param param) throws Throwable { }
    protected void afterHookedMethod(Param param) throws Throwable { }

    static final class Param {
        Executable method;
        Object thisObject;
        Object[] args;
        private Object result;
        private Throwable throwable;
        boolean skip;
        private final Map<String,Object> extras = new HashMap<>();
        Object getResult() { return result; }
        Throwable getThrowable() { return throwable; }
        boolean hasThrowable() { return throwable != null; }
        void setResult(Object value) { result=value; throwable=null; skip=true; }
        void setThrowable(Throwable value) { throwable=value; result=null; skip=true; }
        void setObjectExtra(String key,Object value) { extras.put(key,value); }
        Object getObjectExtra(String key) { return extras.get(key); }
        void restore(Object value,Throwable error,boolean early) { result=value; throwable=error; skip=early; }
    }
}
