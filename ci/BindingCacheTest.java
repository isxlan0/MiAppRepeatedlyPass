package io.github.isxlan0.miappxspace;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Uses a real, unexecuted ROM APK to exercise cache invalidation and split input. */
public final class BindingCacheTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static Properties load(File file)throws Exception{Properties p=new Properties();try(InputStream in=new FileInputStream(file)){p.load(in);}return p;}
    static void split(File apk,File base,File dex)throws Exception{
        try(ZipFile zip=new ZipFile(apk);ZipOutputStream a=new ZipOutputStream(new FileOutputStream(base));ZipOutputStream b=new ZipOutputStream(new FileOutputStream(dex))){
            Enumeration<? extends ZipEntry> entries=zip.entries();while(entries.hasMoreElements()){
                ZipEntry e=entries.nextElement();if(!e.getName().matches(DexModel.DEX_NAME)&&!e.getName().equals("resources.arsc"))continue;
                ZipOutputStream out=e.getName().endsWith(".dex")?b:a;out.putNextEntry(new ZipEntry(e.getName()));
                try(InputStream in=zip.getInputStream(e)){byte[] buf=new byte[65536];for(int n;(n=in.read(buf))!=-1;)out.write(buf,0,n);}out.closeEntry();
            }
        }
    }
    public static void main(String[] args)throws Exception{
        File dir=new File(args[1]);check(dir.mkdirs()||dir.isDirectory(),"cache directory");
        File apk=new File(dir,"target.apk"),cache=new File(dir,"bindings.properties");Files.copy(new File(args[0]).toPath(),apk.toPath(),StandardCopyOption.REPLACE_EXISTING);
        List<File> files=Collections.singletonList(apk);Properties rules=JavaPrepare.patterns();Properties expected=JavaPrepare.security(new DexModel.Archive(apk),rules);
        BindingCache.Validator validator=p->check(p.equals(expected),"bindings differ from fresh recognition");
        BindingCache.resolve(files,rules,cache,"rom-a:module-a",validator);String initial=load(cache).getProperty("cache.identity");
        check(cache.setLastModified(1000),"mark cache");BindingCache.resolve(files,rules,cache,"rom-a:module-a",validator);check(cache.lastModified()==1000,"unchanged inputs should reuse cache");
        BindingCache.resolve(files,rules,cache,"rom-b:module-a",validator);check(!initial.equals(load(cache).getProperty("cache.identity")),"OTA must invalidate");
        String ota=load(cache).getProperty("cache.identity");BindingCache.resolve(files,rules,cache,"rom-b:module-b",validator);check(!ota.equals(load(cache).getProperty("cache.identity")),"module update must invalidate");
        String module=load(cache).getProperty("cache.identity");rules.setProperty("test.rule-revision","2");BindingCache.resolve(files,rules,cache,"rom-b:module-b",validator);check(!module.equals(load(cache).getProperty("cache.identity")),"recognition rule change must invalidate");
        String old=load(cache).getProperty("cache.identity");long time=apk.lastModified();
        // ZIP trailing data preserves members but changes content without any version metadata.
        Files.write(apk.toPath(),new byte[]{1,2,3,4},StandardOpenOption.APPEND);check(apk.setLastModified(time),"preserve timestamp");
        BindingCache.resolve(files,rules,cache,"rom-b:module-b",validator);check(!old.equals(load(cache).getProperty("cache.identity")),"APK content change must invalidate");
        Files.write(cache.toPath(),"broken=cache".getBytes("UTF-8"));BindingCache.resolve(files,rules,cache,"rom-b:module-b",validator);check(load(cache).containsKey("cache.digest"),"corrupt cache must be replaced");
        final int[] calls={0};BindingCache.resolve(files,rules,cache,"rom-b:module-b",p->{if(calls[0]++==0)throw new IOException("cached reflection no longer resolves");validator.check(p);});check(calls[0]==2,"reflection failure must trigger fresh recognition");
        File blocked=new File(dir,"not-a-directory");Files.write(blocked.toPath(),new byte[]{0});BindingCache.resolve(files,rules,new File(blocked,"cache"),"rom-a",validator);
        File base=new File(dir,"base.apk"),dex=new File(dir,"split.apk");split(apk,base,dex);List<File> splits=Arrays.asList(base,dex);
        check(JavaPrepare.security(new DexModel.Archive(splits),rules).equals(expected),"base plus split must resolve the same members");
        BindingCache.resolve(splits,rules,cache,"rom-a",validator);old=load(cache).getProperty("cache.identity");Files.write(dex.toPath(),new byte[]{9},StandardOpenOption.APPEND);
        BindingCache.resolve(splits,rules,cache,"rom-a",validator);check(!old.equals(load(cache).getProperty("cache.identity")),"split content change must invalidate");
        System.out.println("PASS: cache hit, OTA/module/rule/APK/split invalidation, corrupt cache, reflection retry and unavailable storage");
    }
}
