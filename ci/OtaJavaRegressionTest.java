package io.github.isxlan0.miappxspace;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import static io.github.isxlan0.miappxspace.DexModel.*;

/** Real-ROM parser/recognizer regression tests; fixtures are never installed or executed. */
public final class OtaJavaRegressionTest {
    interface Work {void run()throws Exception;}
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static void refuses(String name,Work work)throws Exception {try{work.run();throw new AssertionError("Accepted "+name);}catch(IllegalArgumentException expected){System.out.println("PASS: refused "+name);}}
    static void put2(byte[] b,int p,int n){ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putShort(p,(short)n);}
    static void put4(byte[] b,int p,int n){ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(p,n);}
    static byte[] entry(File apk,String name)throws Exception {try(ZipFile zip=new ZipFile(apk);InputStream in=zip.getInputStream(zip.getEntry(name))){return bytes(in,64*1024*1024);}}
    static void checksum(byte[] b)throws Exception {byte[] hash=MessageDigest.getInstance("SHA-1").digest(Arrays.copyOfRange(b,32,b.length));System.arraycopy(hash,0,b,12,20);Adler32 adler=new Adler32();adler.update(b,12,b.length-12);put4(b,8,(int)adler.getValue());}
    static File fixture(File original,File dir,String name,byte[] dex)throws Exception {
        checksum(dex);File result=new File(dir,name+".apk");try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(result))){for(String part:new String[]{"classes.dex","resources.arsc"}){zip.putNextEntry(new ZipEntry(part));zip.write(part.endsWith(".dex")?dex:entry(original,part));zip.closeEntry();}}return result;
    }
    static int methodIndex(Dex dex,String id){for(int i=0;i<dex.methods.length;i++)if(dex.methods[i].id().equals(id))return i;throw new AssertionError(id);}
    static int fieldIndex(Dex dex,String id){for(int i=0;i<dex.fields.length;i++)if(dex.fields[i].id().equals(id))return i;throw new AssertionError(id);}
    static int stringIndex(Dex dex,String s){for(int i=0;i<dex.strings.length;i++)if(dex.strings[i].equals(s))return i;throw new AssertionError(s);}
    static void renameString(Dex d,String from,String to){check(from.length()==to.length(),"fixture length");int index=stringIndex(d,from);Cursor c=new Cursor(d.b,u4(d.b,u4(d.b,60)+index*4));c.leb();byte[] bytes=to.getBytes(java.nio.charset.StandardCharsets.US_ASCII);System.arraycopy(bytes,0,d.b,c.p,bytes.length);}
    public static void main(String[] args)throws Exception {
        File apk=new File(args[0]),work=new File(args[1]);check(work.mkdirs()||work.isDirectory(),"output");Properties patterns=JavaPrepare.patterns();
        Properties baseline=JavaPrepare.security(new Archive(apk),patterns);check(baseline.size()==82,"76 members + 6 resources");
        byte[] renamed=entry(apk,"classes.dex");Dex d=new Dex(renamed);int launch=methodIndex(d,"Ln3/d;->a(I)V"),field=fieldIndex(d,"Ln3/d;->d:Landroid/content/Intent;");
        int z=stringIndex(d,"z");put4(renamed,u4(renamed,92)+launch*8+4,z);put4(renamed,u4(renamed,84)+field*8+4,z);
        int service=methodIndex(d,"Lcom/miui/xspace/service/XSpaceService;->a(Lcom/miui/xspace/service/XSpaceService;Ljava/lang/String;Z)V");put4(renamed,u4(renamed,92)+service*8+4,z);renameString(d,"Ln3/d;","Ln3/z;");
        Properties moved=JavaPrepare.security(new Archive(fixture(apk,work,"renamed",renamed)),patterns);
        check(moved.getProperty("ui.launch").equals("M:Ln3/z;->z(I)V"),"method rename");check(moved.getProperty("resolver.original").equals("F:Ln3/z;->z:Landroid/content/Intent;"),"field rename");
        check(moved.getProperty("stock.change").contains(";->z("),"private method rename in a manifest component");
        System.out.println("PASS: discovers renamed class, method and field from rewritten DEX fixture");
        byte[] bad=entry(apk,"classes.dex");Dex broken=new Dex(bad);Method selector=broken.methods[methodIndex(broken,"LF3/a;->p(Landroid/os/UserManager;)Landroid/content/pm/UserInfo;")];bad[selector.code+16]^=1;
        refuses("changed required bytecode",()->JavaPrepare.security(new Archive(fixture(apk,work,"changed",bad)),patterns));
        byte[] swapped=entry(apk,"classes.dex");Dex swap=new Dex(swapped);Method method=swap.methods[methodIndex(swap,"Ln3/d;->a(I)V")];int before=fieldIndex(swap,"Ln3/d;->d:Landroid/content/Intent;"),after=fieldIndex(swap,"Ln3/d;->c:Landroid/content/Intent;");boolean changed=false;
        int count=u4(swapped,method.code+12);for(int pc=0;pc<count;){int pos=method.code+16+pc*2,op=u2(swapped,pos)&255;if(op>=0x52&&op<=0x6d&&u2(swapped,pos+2)==before){put2(swapped,pos+2,after);changed=true;break;}pc+=width(op);}check(changed,"field-swap fixture");
        // Both fields have exactly the same type, so instruction hashes alone agree.
        check(new Archive(apk).shape(method,true).hash.equals(new Archive(apk).shape(new Dex(entry(apk,"classes.dex")).methods[methodIndex(swap,"Ln3/d;->a(I)V")],true).hash),"same shape with swapped equal-type field");
        refuses("inconsistent same-type field relationship",()->JavaPrepare.security(new Archive(fixture(apk,work,"swapped",swapped)),patterns));
        Archive ambiguous=new Archive(apk);Method original=ambiguous.methods.get("Lmiuix/appcompat/app/l;->j(Ljava/lang/CharSequence;)V"),copy=new Method();copy.dex=original.dex;copy.owner=original.owner;copy.name="duplicateMessage";copy.proto=original.proto;copy.flags=original.flags;copy.code=original.code;copy.defined=true;ambiguous.methods.put(copy.id(),copy);
        refuses("ambiguous method candidates",()->JavaPrepare.security(ambiguous,patterns));
        Archive resources=new Archive(apk);String resource="com.miui.securitycore:id/title";int oldId=resources.resources.ids.get(resource),newId=oldId^0x01000000;
        resources.resources.ids.put(resource,newId);resources.resources.names.remove(oldId);resources.resources.names.put(newId,resource);Set<Dex> dexes=new HashSet<>();for(Method m:resources.methods.values())dexes.add(m.dex);int replacements=0;
        for(Dex dex:dexes)for(int i=0;i<=dex.b.length-4;i+=2)if(i4(dex.b,i)==oldId){put4(dex.b,i,newId);replacements++;}
        check(replacements>0,"resource references");Properties remapped=JavaPrepare.security(resources,patterns);check(remapped.getProperty("resource."+resource).equals(Integer.toString(newId)),"resource ID discovery");System.out.println("PASS: resource IDs can change while identities and code shapes remain");
        byte[] unrelated=entry(apk,"classes.dex");Dex extra=new Dex(unrelated);Method unused=extra.methods[methodIndex(extra,"LF3/a;->n(J)Ljava/lang/StringBuffer;")];boolean literal=false;int end=u4(unrelated,unused.code+12);
        for(int pc=0;pc<end;){int pos=unused.code+16+pc*2,op=u2(unrelated,pos)&255;if(op==0x12){unrelated[pos+1]^=0x10;literal=true;break;}pc+=width(op);}check(literal,"unrelated constant fixture");
        check(JavaPrepare.security(new Archive(fixture(apk,work,"unrelated",unrelated)),patterns).equals(baseline),"unrelated body must not be a whole-APK hash gate");System.out.println("PASS: unrelated method edit accepted");
        refuses("truncated DEX",()->new Dex(Arrays.copyOf(entry(apk,"classes.dex"),128)));
        if(args.length==3){
            byte[] framework=entry(new File(args[2]),"classes.dex");Dex fd=new Dex(framework);String id="Lmiui/securityspace/XSpaceUserHandle;->isXSpaceUserId(I)Z";Method predicate=fd.methods[methodIndex(fd,id)];boolean modified=false;
            for(int pc=0;pc<u4(framework,predicate.code+12);){int pos=predicate.code+16+pc*2,op=u2(framework,pos)&255;if(op==0x13&&u2(framework,pos+2)==999){put2(framework,pos+2,998);modified=true;break;}int step=width(op);check(step>0,"framework fixture opcode");pc+=step;}
            check(modified,"framework predicate constant fixture");
            refuses("changed framework predicate semantics",()->JavaPrepare.verifyDex(framework,new Archive(),patterns,Collections.singleton(id),Collections.emptySet(),new HashSet<>(),new HashSet<>(),new HashSet<>()));
        }
        System.out.println("PASS: all Java scanner regression checks");
    }
}
