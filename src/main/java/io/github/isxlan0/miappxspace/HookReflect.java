package io.github.isxlan0.miappxspace;

import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;

/** Reflection helpers used by both system and app hooks; independent of legacy APIs. */
final class HookReflect {
    static Class<?> findClass(String name,ClassLoader loader)throws ClassNotFoundException { return Class.forName(name,false,loader); }
    static Method findMethodExact(Class<?> type,String name,Class<?>... parameters)throws NoSuchMethodException {
        Method method=type.getDeclaredMethod(name,parameters); method.setAccessible(true); return method;
    }
    static void findAndHookMethod(String name,ClassLoader loader,String method,Object... args)throws Exception {
        findAndHookMethod(findClass(name,loader),method,args);
    }
    static void findAndHookMethod(Class<?> type,String name,Object... args)throws Exception {
        Class<?>[] parameters=new Class<?>[args.length-1];
        for(int i=0;i<parameters.length;i++)parameters[i]=(Class<?>)args[i];
        Hooks.hookMethod(findMethodExact(type,name,parameters),(HookCallback)args[args.length-1]);
    }
    static Field field(Class<?> type,String name)throws NoSuchFieldException {
        for(Class<?> c=type;c!=null;c=c.getSuperclass())try {
            Field field=c.getDeclaredField(name); field.setAccessible(true); return field;
        }catch(NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(type.getName()+"."+name);
    }
    static Object getObjectField(Object target,String name)throws Exception { return field(target.getClass(),name).get(target); }
    static Object getStaticObjectField(Class<?> type,String name)throws Exception { return field(type,name).get(null); }
    static int getIntField(Object target,String name)throws Exception { return field(target.getClass(),name).getInt(target); }
    static void setObjectField(Object target,String name,Object value)throws Exception { field(target.getClass(),name).set(target,value); }
    static void setIntField(Object target,String name,int value)throws Exception { field(target.getClass(),name).setInt(target,value); }
    static Object callMethod(Object target,String name,Object... args)throws Exception { return Reflect.call(target,name,args); }
    static boolean matches(Class<?>[] types,Object[] args) {
        if(types.length!=args.length)return false;
        for(int i=0;i<types.length;i++)if(args[i]==null?types[i].isPrimitive():!Reflect.box(types[i]).isInstance(args[i]))return false;
        return true;
    }
    static Object callStaticMethod(Class<?> type,String name,Object... args)throws Exception {
        for(Class<?> c=type;c!=null;c=c.getSuperclass()) {
            Method selected=null;
            for(Method method:c.getDeclaredMethods())if(Modifier.isStatic(method.getModifiers())&&method.getName().equals(name)&&matches(method.getParameterTypes(),args)) {
                if(selected!=null)throw new NoSuchMethodException("Ambiguous static method: "+type.getName()+"."+name);
                selected=method;
            }
            if(selected!=null){selected.setAccessible(true);return selected.invoke(null,args);}
        }
        throw new NoSuchMethodException(type.getName()+"."+name);
    }
    static Object newInstance(Class<?> type,Object... args)throws Exception {
        Constructor<?> selected=null;
        for(Constructor<?> constructor:type.getDeclaredConstructors())if(matches(constructor.getParameterTypes(),args)) {
            if(selected!=null)throw new NoSuchMethodException("Ambiguous constructor: "+type.getName());
            selected=constructor;
        }
        if(selected==null)throw new NoSuchMethodException(type.getName());
        selected.setAccessible(true);return selected.newInstance(args);
    }
    // Weak keys and values avoid keeping an Activity alive via its preferences.
    private static final Map<Object,Map<String,WeakReference<Object>>> extras=new WeakHashMap<>();
    static synchronized Object getAdditionalInstanceField(Object target,String key) {
        Map<String,WeakReference<Object>> values=extras.get(target);
        WeakReference<Object> value=values==null?null:values.get(key);return value==null?null:value.get();
    }
    static synchronized void setAdditionalInstanceField(Object target,String key,Object value) {
        Map<String,WeakReference<Object>> values=extras.get(target);
        if(values==null){values=new HashMap<>();extras.put(target,values);}
        values.put(key,new WeakReference<>(value));
    }
}
