package io.github.isxlan0.miappxspace;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class Reflect {
    static Class<?> declaringClass(Class<?> type,String name)throws NoSuchMethodException {
        for(Class<?> c=type;c!=null;c=c.getSuperclass())for(Method method:c.getDeclaredMethods())if(method.getName().equals(name))return c;
        throw new NoSuchMethodException(type.getName()+"."+name);
    }
    static Object call(Object target, String name, Object... args) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterTypes().length != args.length) continue;
                boolean matches = true;
                Class<?>[] types = method.getParameterTypes();
                for (int i = 0; i < types.length; i++) {
                    if (args[i] != null && !box(types[i]).isInstance(args[i])) { matches = false; break; }
                }
                if (matches) { method.setAccessible(true); return method.invoke(target, args); }
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    static Object optional(Object target, String name) {
        try { return call(target, name); } catch (Exception ignored) { return null; }
    }

    static void field(Object target, String name, Object value) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); f.set(target, value); return; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    static Class<?> box(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == boolean.class) return Boolean.class;
        if (type == long.class) return Long.class;
        return type;
    }
}
