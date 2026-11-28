package io.github.isxlan0.miappxspace;

import java.io.*;import java.util.*;import java.util.zip.*;import static io.github.isxlan0.miappxspace.DexModel.*;

/** New resolver checks and graph invariants on the actual annibale APK. */
public final class Os4RulesTest {
    interface Work {void run()throws Exception;}
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static void refuses(String why,Work work)throws Exception {
        try{work.run();throw new AssertionError("Accepted "+why);}catch(IllegalArgumentException expected){System.out.println("PASS: refused "+why);}
    }
    static int find(Method m,int opcode){for(int pc=0;pc<u4(m.dex.b,m.code+12);){int p=m.code+16+pc*2,op=u2(m.dex.b,p)&255;if(op==opcode)return p;int n=width(op);check(n>0,"unsupported fixture instruction");pc+=n;}throw new AssertionError("Missing opcode");}
    static Method frameworkMethod(File file,String id)throws Exception {
        try(ZipFile zip=new ZipFile(file)){
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()){
                ZipEntry entry=entries.nextElement();if(!entry.getName().matches(DEX_NAME))continue;
                byte[] data;try(InputStream in=zip.getInputStream(entry)){data=bytes(in,64*1024*1024);}
                for(Cls cls:new Dex(data).classes)for(Method m:cls.methods)if(m.id().equals(id))return m;
            }
        }throw new AssertionError("Missing framework fixture "+id);
    }
    static void frameworkEncoding(File baseline,File current,Properties patterns)throws Exception {
        String id="Lcom/miui/server/xspace/XSpaceManagerServiceImpl;->putCachedCallingRelation(Ljava/lang/String;Ljava/lang/String;I)V";
        Archive normalizer=new Archive();Method before=frameworkMethod(baseline,id),after=frameworkMethod(current,id);
        check(!normalizer.shape(before,false).hash.equals(normalizer.shape(after,false).hash),"actual ROM encodings differ");
        check(normalizer.canonicalShape(before,false).hash.equals(normalizer.canonicalShape(after,false).hash),"actual ROM logic remains identical");
        check(!Arrays.asList(JavaPrepare.lines(patterns,"framework.method."+id)).contains(normalizer.shape(after,false).hash),"new ROM must exercise canonical fallback");
        Set<String> seen=new HashSet<>();
        JavaPrepare.verifyDex(after.dex.b,new Archive(),patterns,Collections.singleton(id),Collections.emptySet(),seen,new HashSet<>(),new HashSet<>());
        check(seen.contains(id),"canonical-only framework method bound");
        int call=find(after,0x6e);
        check(u2(after.dex.b,call+4)==0x61,"framework calling-package argument fixture");after.dex.b[call+4]^=0x10;
        refuses("wrong calling-package argument through canonical fallback",()->JavaPrepare.verifyDex(after.dex.b,new Archive(),patterns,Collections.singleton(id),Collections.emptySet(),new HashSet<>(),new HashSet<>(),new HashSet<>()));
        System.out.println("PASS: real ROM string-width change accepted; altered framework behavior rejected");
    }
    public static void main(String[] args)throws Exception {
        if(args[0].equals("--framework")){frameworkEncoding(new File(args[1]),new File(args[2]),JavaPrepare.patterns());return;}
        File file=new File(args[0]);Properties patterns=JavaPrepare.patterns();Archive a=new Archive(file);Properties result=JavaPrepare.security(a,patterns);
        check(result.size()==82,"82 complete bindings");String launch=result.getProperty("ui.launch").substring(2),bind=result.getProperty("ui.bind").substring(2);
        check(bind.startsWith("Lj3/g;"),"new adapter discovered");check(result.getProperty("ui.detail.create").contains("Ll3/l;"),"other graph bindings discovered");
        Archive condition=new Archive(file);Method m=condition.methods.get(launch);int p=find(m,0x38);m.dex.b[p]=0x39;
        refuses("reversed trusted-target branch",()->JavaPrepare.security(condition,patterns));
        Archive fields=new Archive(file);m=fields.methods.get(launch);p=find(m,0x54);int other=-1;
        for(int i=0;i<m.dex.fields.length;i++)if(m.dex.fields[i].id().equals("Ln3/d;->c:Landroid/content/Intent;"))other=i;
        check(other>=0,"same-type Intent fixture");m.dex.b[p+2]=(byte)other;m.dex.b[p+3]=(byte)(other>>>8);
        refuses("wrong same-type resolver Intent",()->JavaPrepare.security(fields,patterns));
        Archive listener=new Archive(file);m=listener.methods.get(bind);boolean changed=false;
        for(int pc=0;pc<u4(m.dex.b,m.code+12);){p=m.code+16+pc*2;int w=u2(m.dex.b,p),op=w&255;if(w==0x1412){m.dex.b[p+1]=0x24;changed=true;break;}pc+=width(op);}
        check(changed,"shared-listener discriminator fixture");refuses("wrong shared-listener discriminator",()->JavaPrepare.security(listener,patterns));
        Archive ambiguous=new Archive(file);Method src=ambiguous.methods.get(launch),copy=new Method();copy.dex=src.dex;copy.owner=src.owner;copy.name="ambiguousLaunch";copy.proto=src.proto;copy.flags=src.flags;copy.code=src.code;copy.defined=true;ambiguous.methods.put(copy.id(),copy);
        check(JavaPrepare.security(ambiguous,patterns).getProperty("ui.launch").equals(result.getProperty("ui.launch")),"call graph must select the actually referenced resolver");
        // Rename the moved adapter again without changing any method body.
        byte[] dex=OtaJavaRegressionTest.entry(file,"classes.dex");Dex parsed=new Dex(dex);OtaJavaRegressionTest.renameString(parsed,"Lj3/g;","Lj3/z;");
        File dir=new File(args[1]);check(dir.mkdirs()||dir.isDirectory(),"output");
        Properties renamed=JavaPrepare.security(new Archive(OtaJavaRegressionTest.fixture(file,dir,"renamed-adapter",dex)),patterns);
        check(renamed.getProperty("ui.bind").startsWith("M:Lj3/z;"),"new variant must not pin obfuscated owner");
        System.out.println("PASS: complete new resolver and listener variants; branch/field/discriminator checks, duplicate resolved by caller, independent renaming");
    }
}
