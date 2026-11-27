package io.github.isxlan0.miappxspace;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static io.github.isxlan0.miappxspace.DexModel.*;

/** Optional, per-app cache. Content and recognition rules, not version labels, identify an APK. */
final class BindingCache {
    interface Validator {void check(Properties result)throws Exception;}
    static String propertiesHash(Properties p)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(bytes)){
            for(String key:new TreeSet<>(p.stringPropertyNames())){
                for(String value:new String[]{key,p.getProperty(key)}){
                    byte[] text=value.getBytes(StandardCharsets.UTF_8);out.writeInt(text.length);out.write(text);
                }
            }
        }return sha(bytes.toByteArray());
    }
    static String identity(List<File> apks,Properties rules,String runtime)throws Exception {
        Properties key=new Properties();key.setProperty("schema","1");key.setProperty("runtime",runtime);
        key.setProperty("rules",propertiesHash(rules));
        for(int i=0;i<apks.size();i++){
            File apk=apks.get(i);key.setProperty("path."+i,apk.getCanonicalPath());
            key.setProperty("sha256."+i,shaFile(apk));
        }return propertiesHash(key);
    }
    static Properties resolve(List<File> apks,Properties rules,File cache,String runtime,Validator validator)throws Exception {
        String identity=identity(apks,rules,runtime);
        if(cache!=null)try{
            need(cache.length()>0&&cache.length()<1024*1024,"Invalid binding cache size");
            Properties saved=new Properties();try(InputStream in=new FileInputStream(cache)){saved.load(in);}
            need(identity.equals(saved.remove("cache.identity")),"Binding cache belongs to different inputs");
            Object digest=saved.remove("cache.digest");need(propertiesHash(saved).equals(digest),"Incomplete binding cache");
            validator.check(saved);return saved;
        }catch(Exception ignored){/* Corrupt, unreadable or unresolved entries are a cache miss. */}
        Properties found=JavaPrepare.security(new Archive(apks),rules);
        validator.check(found);
        need(identity.equals(identity(apks,rules,runtime)),"APK changed during recognition; retry in a new process");
        if(cache!=null)save(cache,identity,found);
        return found;
    }
    private static void save(File cache,String identity,Properties found){
        Path temporary=null;
        try{
            File dir=cache.getParentFile();need(dir.isDirectory()||dir.mkdirs(),"Cannot create binding cache");
            Properties saved=new Properties();saved.putAll(found);saved.setProperty("cache.digest",propertiesHash(found));saved.setProperty("cache.identity",identity);
            temporary=Files.createTempFile(dir.toPath(),"bindings-",".tmp");
            try(FileOutputStream out=new FileOutputStream(temporary.toFile())){saved.store(out,"MiApp runtime bindings");out.getFD().sync();}
            Files.move(temporary,cache.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception ignored){/* Locked CE storage or a full cache must not prevent hooking. */}
        finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
    }
}
