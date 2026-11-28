package io.github.isxlan0.miappxspace;
import java.io.*;import java.util.*;import static io.github.isxlan0.miappxspace.DexModel.*;

/** Negative regressions for independently moved R8 static helper methods. */
public final class OtaAdaptationTest {
    interface Work {void run()throws Exception;}
    static void refuses(String label,Work work)throws Exception {
        try{work.run();throw new AssertionError("Accepted "+label);}catch(IllegalArgumentException expected){System.out.println("PASS: refused "+label);}
    }
    public static void main(String[] args)throws Exception {
        File apk=new File(args[0]);Properties patterns=JavaPrepare.patterns();Archive original=new Archive(apk);
        Properties bindings=JavaPrepare.security(original,patterns);
        if(bindings.size()!=82)throw new AssertionError("Missing public bindings");
        String helper="LJ3/g;->e(ILandroid/content/Context;)Z";
        Archive body=new Archive(apk);Method m=body.methods.get(helper);if(m==null)throw new AssertionError("Expected relocated helper fixture");m.dex.b[m.code+17]^=0x10;
        refuses("changed relocated helper body",()->JavaPrepare.security(body,patterns));
        Archive instance=new Archive(apk);instance.methods.get(helper).flags&=~8;
        refuses("nonstatic relocated helper",()->JavaPrepare.security(instance,patterns));
        Archive wrongTarget=new Archive(apk);Method detail=wrongTarget.methods.get(bindings.getProperty("ui.detail.create").substring(2));
        Dex d=detail.dex;int from=-1,to=-1;
        for(int i=0;i<d.methods.length;i++){Method candidate=d.methods[i];if(candidate.id().equals(helper))from=i;else if(candidate.proto.equals("(ILandroid/content/Context;)Z"))to=i;}
        if(from<0||to<0)throw new AssertionError("Missing same-prototype call fixture");boolean changed=false;
        for(int pc=0;pc<u4(d.b,detail.code+12);){int p=detail.code+16+2*pc,op=u2(d.b,p)&255;
            if(op==0x71&&u2(d.b,p+2)==from){d.b[p+2]=(byte)to;d.b[p+3]=(byte)(to>>>8);changed=true;break;}pc+=width(op);
        }
        if(!changed)throw new AssertionError("Missing helper call");
        refuses("redirected same-prototype helper call",()->JavaPrepare.security(wrongTarget,patterns));
        System.out.println("PASS: relocated helper accepted with all 82 bindings; body, static ABI and call identity enforced");
    }
}
