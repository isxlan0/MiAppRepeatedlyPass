package io.github.isxlan0.miappxspace;

import java.io.File;
import android.content.pm.ApplicationInfo;
import java.lang.reflect.*;
import java.util.*;
import static io.github.isxlan0.miappxspace.DexModel.need;

/** Bindings follow the APKs loaded by this process; disk entries are only an optimization. */
final class JavaBindings {
    private static Map<String,AccessibleObject> members=Collections.emptyMap();
    private static Properties bindings=new Properties();
    static String descriptor(Class<?> c){if(c.isArray())return c.getName().replace('.','/');if(!c.isPrimitive())return "L"+c.getName().replace('.','/')+";";if(c==void.class)return "V";if(c==boolean.class)return "Z";if(c==byte.class)return "B";if(c==short.class)return "S";if(c==char.class)return "C";if(c==int.class)return "I";if(c==long.class)return "J";if(c==float.class)return "F";return "D";}
    static String proto(Class<?>[] args,Class<?> result){StringBuilder s=new StringBuilder("(");for(Class<?> a:args)s.append(descriptor(a));return s.append(')').append(descriptor(result)).toString();}
    static Class<?> owner(String token,ClassLoader loader)throws Exception {int end=token.indexOf("->");return Class.forName(token.substring(3,end-1).replace('/','.'),false,loader);}
    static AccessibleObject resolve(String token,ClassLoader loader)throws Exception {
        Class<?> c=owner(token,loader);int arrow=token.indexOf("->");AccessibleObject found=null;
        if(token.startsWith("F:")){
            int colon=token.indexOf(':',arrow);Field f=c.getDeclaredField(token.substring(arrow+2,colon));need(descriptor(f.getType()).equals(token.substring(colon+1)),"Runtime Java field type changed: "+token);found=f;
        }else {
            int open=token.indexOf('(',arrow);String name=token.substring(arrow+2,open),signature=token.substring(open);
            if(name.equals("<init>")){for(Constructor<?> ctor:c.getDeclaredConstructors())if(proto(ctor.getParameterTypes(),void.class).equals(signature)){need(found==null,"Ambiguous constructor");found=ctor;}}
            else for(Method m:c.getDeclaredMethods())if(m.getName().equals(name)&&proto(m.getParameterTypes(),m.getReturnType()).equals(signature)){need(found==null,"Ambiguous method");found=m;}
            need(found!=null,"Runtime Java method missing: "+token);
        }found.setAccessible(true);return found;
    }
    static void prepare(String apk,ClassLoader loader)throws Exception {
        prepare(Collections.singletonList(new File(apk)),null,loader);
    }
    static List<File> apks(ApplicationInfo app){
        List<File> files=new ArrayList<>();files.add(new File(app.sourceDir));
        if(app.splitSourceDirs!=null)for(String path:app.splitSourceDirs)files.add(new File(path));
        return files;
    }
    static File cache(ApplicationInfo app,String name){
        String data=app.deviceProtectedDataDir!=null?app.deviceProtectedDataDir:app.dataDir;
        return data==null?null:new File(data,"cache/miapp-bindings/"+name+".properties");
    }
    static String runtime(){return BuildConfig.VERSION_CODE+":"+android.os.Build.FINGERPRINT;}
    static void prepare(ApplicationInfo app,ClassLoader loader)throws Exception {prepare(apks(app),cache(app,"security"),loader);}
    private static void prepare(List<File> apks,File cache,ClassLoader loader)throws Exception {
        Map<String,AccessibleObject> next=new HashMap<>();
        Properties scanned=BindingCache.resolve(apks,JavaPrepare.patterns(),cache,runtime(),result->{
            next.clear();for(String key:result.stringPropertyNames())if(!key.startsWith("resource."))next.put(key,resolve(result.getProperty(key),loader));
        });
        // Publish only after the entire scan and every reflection lookup succeeds.
        bindings=scanned;members=Collections.unmodifiableMap(next);
    }
    static Method method(String key){AccessibleObject m=members.get(key);need(m instanceof Method,"Missing resolved method: "+key);return (Method)m;}
    static Object call(String key,Object target,Object... args)throws Exception {return method(key).invoke(target,args);}
    static Object get(String key,Object target)throws Exception {AccessibleObject f=members.get(key);need(f instanceof Field,"Missing resolved field: "+key);return ((Field)f).get(target);}
    static void set(String key,Object target,Object value)throws Exception {AccessibleObject f=members.get(key);need(f instanceof Field,"Missing resolved field: "+key);((Field)f).set(target,value);}
    static int resource(String name){String value=bindings.getProperty("resource.com.miui.securitycore:"+name);need(value!=null,"Missing resolved resource: "+name);return (int)Long.parseLong(value);}
    /** Read-only device probe: class resolution only, no callbacks or target methods invoked. */
    public static void main(String[] args)throws Exception {
        need(args.length==1,"Usage: JavaBindings <SecurityCore.apk>");
        ClassLoader loader=(ClassLoader)Class.forName("dalvik.system.PathClassLoader").getConstructor(String.class,ClassLoader.class).newInstance(args[0],ClassLoader.getSystemClassLoader());
        prepare(args[0],loader);System.out.println("PASS: "+members.size()+" Java members resolved without executing target methods");
    }
}
