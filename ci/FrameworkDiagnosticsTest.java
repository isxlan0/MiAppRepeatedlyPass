package io.github.isxlan0.miappxspace;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static io.github.isxlan0.miappxspace.DexModel.*;

/** Real ROM baselines and synthetic incompatible signatures; never executes ROM code. */
public final class FrameworkDiagnosticsTest {
    static final String OWNER="Lcom/android/server/pm/UserManagerService;";
    static final String NAME="createUserInternalUnchecked";
    static final String OLD=OWNER+"->"+NAME+"(Ljava/lang/String;Ljava/lang/String;IIZ[Ljava/lang/String;Ljava/lang/Object;)Landroid/content/pm/UserInfo;";
    static final String EXTRA=OLD.replace("[Ljava/lang/String;","[Ljava/lang/String;[Ljava/lang/String;");
    static final String REVIEWED=OLD.replace("Ljava/lang/Object;)","Ljava/lang/Object;[Ljava/lang/String;)");
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static void put4(byte[] data,int offset,int value){ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(offset,value);}
    static void put2(byte[] data,int offset,int value){ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putShort(offset,(short)value);}
    static Properties onlyCreation(Properties original){
        Properties p=new Properties();p.putAll(original);
        p.setProperty("framework.archives","services.jar");
        p.setProperty("framework.services.jar.methods",OLD);
        p.setProperty("framework.services.jar.fields","");
        p.setProperty("framework.services.jar.classes",OWNER);
        return p;
    }
    static byte[] addArrayParameter(Dex dex,Method target){
        int methodIndex=Arrays.asList(dex.methods).indexOf(target);
        int protoIndex=u2(dex.b,u4(dex.b,92)+methodIndex*8+2);
        int proto=u4(dex.b,76)+protoIndex*12,oldArgs=u4(dex.b,proto+8),count=u4(dex.b,oldArgs);
        check(count==7,"baseline parameter count");
        int offset=(dex.b.length+3)&~3;
        byte[] data=Arrays.copyOf(dex.b,offset+4+(count+1)*2);
        put4(data,32,data.length);put4(data,proto+8,offset);put4(data,offset,count+1);
        for(int i=0;i<6;i++)put2(data,offset+4+i*2,u2(dex.b,oldArgs+4+i*2));
        put2(data,offset+4+6*2,u2(dex.b,oldArgs+4+5*2));
        put2(data,offset+4+7*2,u2(dex.b,oldArgs+4+6*2));
        // Parser-only negative fixture, not an executable DEX or an adapted ROM sample.
        return data;
    }
    static String refused(File root,Properties p)throws Exception {
        try{JavaPrepare.framework(root,root,p,new Properties());throw new AssertionError("Accepted incompatible framework");}
        catch(IllegalArgumentException expected){return expected.getMessage();}
    }
    public static void main(String[] args)throws Exception {
        File fixtures=new File(args[0]),work=new File(args[1]);Properties patterns=JavaPrepare.patterns();
        for(String version:new String[]{"4.0.0.44","4.0.4.0","4.0.6.0"}){
            File root=new File(fixtures,version+"/files");
            Properties report=new Properties();
            JavaPrepare.framework(new File(root,"system_a/system"),new File(root,"system_ext_a"),patterns,report);
            check(OLD.equals(report.getProperty("framework.binding."+OLD)),"baseline runtime binding");
            System.out.println("PASS: complete framework baseline "+version);
        }
        File source=new File(fixtures,"4.0.6.0/files/system_a/system/framework/services.jar");
        File framework=new File(work,"framework");check(framework.mkdirs()||framework.isDirectory(),"test output");
        File jar=new File(framework,"services.jar");
        byte[] changed=null;String expectedHash=null;
        try(ZipFile zip=new ZipFile(source)){
            Enumeration<? extends ZipEntry> all=zip.entries();
            while(all.hasMoreElements()){
                ZipEntry entry=all.nextElement();if(!entry.getName().matches(DEX_NAME))continue;
                byte[] data;try(InputStream in=zip.getInputStream(entry)){data=bytes(in,64*1024*1024);}
                Dex dex=new Dex(data);
                for(Cls c:dex.classes)for(Method m:c.methods)if(m.id().equals(OLD)){
                    changed=addArrayParameter(dex,m);
                    for(Cls actual:new Dex(changed).classes)for(Method candidate:actual.methods)if(candidate.id().equals(EXTRA))expectedHash=new Archive().shape(candidate,false).hash;
                }
            }
        }
        check(changed!=null&&expectedHash!=null,"fixture variant found");
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(jar))){zip.putNextEntry(new ZipEntry("classes.dex"));zip.write(changed);zip.closeEntry();}
        String failure=refused(work,onlyCreation(patterns));
        check(failure.contains("Framework archive: "+jar),"archive path missing");
        check(failure.contains("archive.sha256="+shaFile(jar)),"archive hash missing");
        check(failure.contains("expected method: "+OLD),"old descriptor truncated");
        check(failure.contains("actual same-name method: "+EXTRA),"new descriptor missing");
        check(failure.contains("shape="+expectedHash),"candidate fingerprint missing");
        Files.write(new File(work,"extra-array-refusal.log").toPath(),failure.getBytes("UTF-8"));
        System.out.println("PASS: additional String[] rejected with full expected/actual signature and fingerprints");
        Properties missing=onlyCreation(patterns);
        missing.setProperty("framework.services.jar.methods",OLD.replace(NAME,"missingCreationMethod"));
        missing.setProperty("framework.method."+OLD.replace(NAME,"missingCreationMethod"),patterns.getProperty("framework.method."+OLD));
        check(refused(work,missing).contains("actual same-name method: <none>"),"absent name diagnostics");
        System.out.println("PASS: truly absent method distinguished from changed overload");
        Files.copy(source.toPath(),jar.toPath(),StandardCopyOption.REPLACE_EXISTING);
        Properties mismatch=onlyCreation(patterns);mismatch.setProperty("framework.method."+OLD,"unreviewed-shape");
        String changedShape=refused(work,mismatch);
        check(changedShape.contains("Framework method logic changed: "+OLD),"logic mismatch label");
        check(changedShape.contains("expected shape: unreviewed-shape"),"expected shape missing");
        check(changedShape.contains("actual shape: "+patterns.getProperty("framework.method."+OLD)),"actual shape missing");
        System.out.println("PASS: signature match does not bypass method shape validation");
        if(args.length>2){
            File xuanyuan=new File(args[2]);Properties report=new Properties();
            JavaPrepare.framework(new File(xuanyuan,"files/system/system"),new File(xuanyuan,"files/system_ext"),patterns,report);
            check(REVIEWED.equals(report.getProperty("framework.binding."+OLD)),"reviewed eight-argument runtime binding");
            System.out.println("PASS: complete xuanyuan OS4.0.4.0.XOACNXM framework and exact eight-argument binding");
            Set<String> seen=new HashSet<>();boolean mutated=false;
            try(ZipFile zip=new ZipFile(new File(xuanyuan,"services.jar"))){
                Enumeration<? extends ZipEntry> all=zip.entries();
                while(all.hasMoreElements()){
                    ZipEntry entry=all.nextElement();if(!entry.getName().matches(DEX_NAME))continue;
                    byte[] data;try(InputStream in=zip.getInputStream(entry)){data=bytes(in,64*1024*1024);}
                    for(Cls cls:new Dex(data).classes)for(Method m:cls.methods)if(m.id().equals(REVIEWED)){
                        JavaPrepare.verifyDex(data,new Archive(),patterns,Collections.singleton(OLD),Collections.emptySet(),seen,new HashSet<>(),new HashSet<>());
                        check(seen.contains(OLD),"alternative satisfies original contract");
                        try{
                            JavaPrepare.verifyDex(data,new Archive(),patterns,Collections.singleton(OLD),Collections.emptySet(),seen,new HashSet<>(),new HashSet<>());
                            throw new AssertionError("Accepted duplicate reviewed candidates");
                        }catch(IllegalArgumentException expected){check(expected.getMessage().contains("ambiguous"),"ambiguity error");}
                        data[m.code+17]^=0x10;mutated=true;
                        try{
                            JavaPrepare.verifyDex(data,new Archive(),patterns,Collections.singleton(OLD),Collections.emptySet(),new HashSet<>(),new HashSet<>(),new HashSet<>());
                            throw new AssertionError("Accepted changed new implementation");
                        }catch(IllegalArgumentException expected){check(expected.getMessage().contains("Framework method logic changed"),"changed logic diagnostics");}
                    }
                }
            }check(mutated,"new implementation mutation fixture");
            System.out.println("PASS: reviewed alternative rejects duplicate candidates and changed bytecode");
        }
    }
}
