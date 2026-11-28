package io.github.isxlan0.miappxspace;

import java.io.*;import java.nio.*;import java.util.*;
import static io.github.isxlan0.miappxspace.DexModel.*;

/** Equivalent DEX encodings plus negative control-flow fixtures. Never executed. */
public final class DexCanonicalTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static void put2(byte[] b,int p,int n){ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putShort(p,(short)n);}
    static void put4(byte[] b,int p,int n){ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(p,n);}
    static Method fixture(Method template,int[] code,int first,int length,int handler){
        byte[] original=template.dex.b;int at=(original.length+3)&~3;
        int handlers=at+16+code.length*2+((code.length&1)*2)+8;
        byte[] bytes=Arrays.copyOf(original,handlers+3);put4(bytes,32,bytes.length);
        put2(bytes,at,16);put2(bytes,at+2,2);put2(bytes,at+4,5);put2(bytes,at+6,1);put4(bytes,at+12,code.length);
        for(int i=0;i<code.length;i++)put2(bytes,at+16+i*2,code[i]);
        int tries=handlers-8;put4(bytes,tries,first);put2(bytes,tries+4,length);put2(bytes,tries+6,1);
        bytes[handlers]=1;bytes[handlers+1]=0;bytes[handlers+2]=(byte)handler;
        Method m=new Method();m.dex=new Dex(bytes);m.owner=template.owner;m.name=template.name;m.proto=template.proto;m.flags=template.flags;m.code=at;m.defined=true;return m;
    }
    static void refuses(Archive a,Method m,String why)throws Exception {
        try{a.canonicalShape(m,true);throw new AssertionError("Accepted "+why);}catch(IllegalArgumentException expected){System.out.println("PASS: refused "+why);}
    }
    static String hash(Archive a,Method m)throws Exception{return a.canonicalShape(m,true).hash;}
    public static void main(String[] args)throws Exception {
        Archive a=new Archive(new File(args[0]));Method base=a.methods.get("Ln3/d;->a(I)V");check(base!=null,"fixture template");
        int string=0;while(string<base.dex.strings.length&&!base.dex.strings[string].equals("android.intent.extra.picked_user_id"))string++;
        check(string<65536,"short string fixture");
        // string; goto; const; if-eqz; return; catch move-exception; return.
        int[] shortCode={0x001a,string,0x0128,0x1112,0x0138,2,0x000e,0x020d,0x000e};
        int[] wideCode={0x001b,string,0,0x002a,3,0,0x0114,1,0,0x0138,2,0x000e,0x020d,0x000e};
        Method small=fixture(base,shortCode,0,7,7),wide=fixture(base,wideCode,0,12,12);
        check(!a.shape(small,true).hash.equals(a.shape(wide,true).hash),"raw encodings must differ");
        check(hash(a,small).equals(hash(a,wide)),"string/goto/constant/try relocation equivalence");
        check(a.shape(small,true).refs.equals(a.canonicalShape(small,true).refs),"reference order");
        int[] wrong=wideCode.clone();wrong[9]=0x0139;check(!hash(a,small).equals(hash(a,fixture(base,wrong,0,12,12))),"branch condition must matter");
        wrong=wideCode.clone();wrong[4]=6;check(!hash(a,small).equals(hash(a,fixture(base,wrong,0,12,12))),"branch destination must matter");
        wrong=wideCode.clone();wrong[7]=2;check(!hash(a,small).equals(hash(a,fixture(base,wrong,0,12,12))),"constant must matter");
        wrong=wideCode.clone();wrong[4]=1;refuses(a,fixture(base,wrong,0,12,12),"branch into instruction operand");
        refuses(a,fixture(base,wideCode,0,12,1),"catch into jumbo string operand");
        check(!hash(a,small).equals(hash(a,fixture(base,wideCode,0,11,12))),"exception region must matter");
        // packed/sparse switch payload offsets and case targets move with widths.
        for(boolean sparse:new boolean[]{false,true}){
            int op=sparse?0x002c:0x002b,tag=sparse?0x200:0x100;
            int[] d={0x001b,string,0,op,7,0,0x000e,0x020d,0x000e,0,tag,1,7,0,3,0};
            // Keep a deliberate nop in both methods so logical positions agree.
            int[] c2={0x001a,string,op,7,0,0x000e,0x020d,0x000e,0,tag,1,7,0,3,0};
            check(hash(a,fixture(base,c2,0,6,6)).equals(hash(a,fixture(base,d,0,7,7))),"switch targets "+sparse);
            d[14]=4;check(!hash(a,fixture(base,c2,0,6,6)).equals(hash(a,fixture(base,d,0,7,7))),"switch case destination matters");
        }
        // Same invoke register list encoded with 35c and 3rc; same move encoded 12x/32x.
        int method=0;
        int[] invoke={0x1071,method,3,0x3201,0x000e,0x020d,0x000e};
        int[] range={0x0177,method,3,0x0003,2,3,0x000e,0x020d,0x000e};
        check(hash(a,fixture(base,invoke,0,5,5)).equals(hash(a,fixture(base,range,0,7,7))),"invoke/move encoding equivalence");
        range[2]=4;check(!hash(a,fixture(base,invoke,0,5,5)).equals(hash(a,fixture(base,range,0,7,7))),"invoke argument register matters");
        System.out.println("PASS: DEX widths normalize; constants, registers, branches, switch cases and exception targets remain enforced");
    }
}
