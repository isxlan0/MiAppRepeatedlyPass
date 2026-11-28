package io.github.isxlan0.miappxspace;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Install-time, read-only analysis followed by publication into the staging module.
 * Runs both under Android app_process and a desktop JDK for ROM regression checks.
 * Unknown or ambiguous structures are errors. No device/model/version address table.
 */
public final class NativePrepare {
    private static final String PRIVATE="_RNvMs4_NtNtCs2Z0Otu41e4o_23hyper_os_bundle_private2pm15package_managerNtB5_14PackageManager20get_user_badged_icon";
    private static final String[] NAMES={"libhyper_os_security_public.so","libhyper_os_bundle_public.so","libhyper_os_bundle_private.dylib.so"};
    private static final String[] ORIGINALS={"libmiapp_security_original.so","libmiapp_bundle_original.so","libmiapp_bundle_private_orig.so"};
    private static final byte[] MAGIC="MIAPP_LAYOUT_V1!".getBytes(StandardCharsets.US_ASCII);
    private static final int LAYOUT_SIZE=240;
    private static final int[][] BADGES={
        {0xf8473001,0xf10f9c3f,0x54000160,0xb84c3003,0x8b1c8063},
        {0xf8473001,0xf10f9c3f,0x54000381,0xf85f03a3,0xb84c3002},
        {0xf8473022,0xf10f9c5f,0x910082d0,0x9100c2d1,0x9a910200},
        {0xf8473022,0xf10f9c5f,0x910082d0,0x9100c2d1,0x9a910201},
        {0x540000e0,0xf10f9c3f,0x540000a0,0x910082c0,0xaa1d03ef}
    };
    private static void require(boolean value,String message){if(!value)throw new IllegalArgumentException(message);}
    private static int offset(long value){require(value>=0&&value<=Integer.MAX_VALUE,"Offset out of range");return (int)value;}
    private static void range(byte[] data,long at,long size){require(at>=0&&size>=0&&at<=data.length&&size<=data.length-at,"Truncated binary");}
    private static int word(byte[] data,long at){range(data,at,4);int p=(int)at;return (data[p]&255)|((data[p+1]&255)<<8)|((data[p+2]&255)<<16)|(data[p+3]<<24);}
    private static long u32(byte[] data,long at){return Integer.toUnsignedLong(word(data,at));}
    private static long u64(byte[] data,long at){range(data,at,8);long n=ByteBuffer.wrap(data,(int)at,8).order(ByteOrder.LITTLE_ENDIAN).getLong();require(n>=0,"Oversized ELF value");return n;}
    private static int u16(byte[] data,long at){range(data,at,2);return (data[(int)at]&255)|((data[(int)at+1]&255)<<8);}
    private static String cstring(byte[] data,long at,long end){range(data,at,end-at);int e=offset(at);while(e<end&&data[e]!=0)e++;require(e<end,"Unterminated string");return new String(data,offset(at),e-offset(at),StandardCharsets.UTF_8);}
    private static byte[] read(File file)throws IOException{require(file.isFile()&&file.length()>0&&file.length()<256L*1024*1024,"Missing or oversized input: "+file);return Files.readAllBytes(file.toPath());}
    private static String sha(byte[] data)throws Exception{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    private static long hash(byte[] data,int at,int size){range(data,at,size);long h=0xcbf29ce484222325L;for(int i=at;i<at+size;i++){h^=data[i]&255;h*=0x100000001b3L;}return h;}

    private static final class Segment {
        long file,address,size;int flags;
        Segment(long f,long a,long s,int p){file=f;address=a;size=s;flags=p;}
    }
    private static final class Symbol {
        long address,size;int index;
        Symbol(long a,long s,int i){address=a;size=s;index=i;}
    }
    private static final class Elf {
        final byte[] data;final List<Segment> segments=new ArrayList<>();
        final Map<String,Symbol> exports=new HashMap<>();final Map<Long,String> imports=new HashMap<>();
        long dynamic, dynamicSize;
        Elf(byte[] bytes){
            data=bytes;range(data,0,64);
            require(word(data,0)==0x464c457f&&data[4]==2&&data[5]==1&&u16(data,18)==183,"Expected AArch64 little-endian ELF64");
            require(u16(data,16)==3,"Expected shared ELF library");
            long ph=u64(data,32);int count=u16(data,56);require(u16(data,54)==56&&count>0&&count<128,"Bad ELF program table");
            range(data,ph,count*56L);
            for(int i=0;i<count;i++){
                long p=ph+i*56L,kind=u32(data,p),f=u64(data,p+8),a=u64(data,p+16),s=u64(data,p+32);
                range(data,f,s);
                if(kind==1){require(s<=u64(data,p+40),"ELF load size mismatch");segments.add(new Segment(f,a,s,word(data,p+4)));}
                if(kind==2){dynamic=f;dynamicSize=s;}
            }
            require(!segments.isEmpty()&&segments.get(0).file==0&&segments.get(0).address==0,"Unsupported ELF load bias");
            long sh=u64(data,40);int sn=u16(data,60);require(u16(data,58)==64&&sn>0,"Missing ELF section table");range(data,sh,sn*64L);
            List<String> names=new ArrayList<>();long symSection=-1;
            for(int i=0;i<sn;i++)if(u32(data,sh+i*64L+4)==11){require(symSection<0,"Multiple dynamic symbol tables");symSection=sh+i*64L;}
            require(symSection>=0,"Missing dynamic symbols");
            long table=u64(data,symSection+24),size=u64(data,symSection+32),link=u32(data,symSection+40);
            require(link<sn&&u64(data,symSection+56)==24&&size%24==0,"Bad dynamic symbols");range(data,table,size);
            long st=sh+link*64,strings=u64(data,st+24),stringSize=u64(data,st+32);range(data,strings,stringSize);
            for(int i=0;i<size/24;i++){
                long p=table+i*24L,index=u32(data,p);require(index<stringSize,"Bad symbol name");
                String name=cstring(data,strings+index,strings+stringSize);names.add(name);
                if(u16(data,p+6)!=0&&!name.isEmpty())exports.put(name,new Symbol(u64(data,p+8),u64(data,p+16),i));
            }
            // Use relocation GOT slots rather than assumptions about PLT entry order.
            for(int i=0;i<sn;i++){
                long p=sh+i*64L;if(u32(data,p+4)!=4)continue;
                long off=u64(data,p+24),n=u64(data,p+32);require(u64(data,p+56)==24&&n%24==0,"Bad RELA table");range(data,off,n);
                for(long at=off;at<off+n;at+=24){long info=u64(data,at+8);if((info&0xffffffffL)==1026){int idx=offset(info>>>32);require(idx<names.size(),"Bad relocation symbol");imports.put(u64(data,at),names.get(idx));}}
            }
        }
        long file(long va,int size){for(Segment s:segments)if(va>=s.address&&size>=0&&va-s.address<=s.size-size)return s.file+va-s.address;throw new IllegalArgumentException("ELF virtual address outside file: "+Long.toHexString(va));}
        int instruction(long va){return word(data,file(va,4));}
        Segment executable(){Segment found=null;for(Segment s:segments)if((s.flags&1)!=0){require(found==null,"Multiple executable segments are not supported");found=s;}require(found!=null,"No executable segment");return found;}
        void code(long va,int size){Segment s=executable();require(va>=s.address&&va-s.address<=s.size-size,"Code address outside executable segment");}
        Symbol symbol(String name){Symbol s=exports.get(name);require(s!=null,"Missing export: "+name);code(s.address,offset(s.size));return s;}
        boolean matches(long va,int[] pattern,boolean relocations){
            long f;try{code(va,pattern.length*4);f=file(va,pattern.length*4);}catch(IllegalArgumentException ex){return false;}
            for(int i=0;i<pattern.length;i++){int mask=relocations?mask(pattern[i],i,pattern.length):-1;if((word(data,f+4L*i)&mask)!=(pattern[i]&mask))return false;}
            return true;
        }
        long unique(String label,int[]... alternatives){
            Set<Long> found=new LinkedHashSet<>();Segment s=executable();
            for(int[] pattern:alternatives){
                // All reviewed patterns start with stable instructions. Check two
                // words first, then the complete shape including structure offsets.
                for(long va=s.address;va<=s.address+s.size-pattern.length*4L;va+=4){
                    long f=s.file+va-s.address;
                    if(word(data,f)!=pattern[0]||word(data,f+4)!=pattern[1])continue;
                    if(matches(va,pattern,true))found.add(va);
                }
            }
            require(found.size()==1,label+": expected one complete match, found "+found.size());return found.iterator().next();
        }
        long branchTarget(long va,boolean link){int op=instruction(va);require((op&0xfc000000)==(link?0x94000000:0x14000000),"Expected direct branch");long target=va+((long)(op<<6)>>4);code(target,4);return target;}
        long plt(String name){
            Segment s=executable();List<Long> found=new ArrayList<>();
            for(long va=s.address;va+16<=s.address+s.size;va+=4){
                int a=instruction(va),b=instruction(va+4),c=instruction(va+8),d=instruction(va+12);
                if((a&0x9f00001f)!=0x90000010||(b&0xffc003ff)!=0xf9400211||(c&0xffc003ff)!=0x91000210||d!=0xd61f0220)continue;
                long imm=((a>>>29)&3)|(((long)(a>>>5)&0x7ffff)<<2);imm=(imm<<43)>>31;
                long got=(va&~4095L)+imm+((b>>>10)&4095)*8L;
                if(name.equals(imports.get(got))&&((c>>>10)&4095)==((b>>>10)&4095)*8)found.add(va);
            }
            require(found.size()==1,"PLT relocation mismatch: "+name);return found.get(0);
        }
        byte[] renamed(String old,String replacement){
            require(dynamic>0&&dynamicSize%16==0,"Missing dynamic table");long strings=-1,length=-1,soname=-1;
            for(long p=dynamic;p<dynamic+dynamicSize;p+=16){long tag=u64(data,p),value=u64(data,p+8);if(tag==0)break;if(tag==5)strings=value;if(tag==10)length=value;if(tag==14)soname=value;}
            require(strings>=0&&length>0&&soname>=0&&soname<length,"Missing ELF SONAME");long at=file(strings,offset(length))+soname;
            require(cstring(data,at,at+length-soname).equals(old),"Unexpected SONAME (possibly a mounted wrapper): "+old);
            byte[] name=replacement.getBytes(StandardCharsets.US_ASCII);require(name.length<=old.length(),"SONAME too long");
            byte[] out=data.clone();Arrays.fill(out,offset(at),offset(at)+old.length()+1,(byte)0);System.arraycopy(name,0,out,offset(at),name.length);return out;
        }
    }
    private static int mask(int op,int index,int length){
        if((op&0x7c000000)==0x14000000){int target=index+((op<<6)>>6);return target>=0&&target<length?-1:0xfc000000;}
        if((op&0x1f000000)==0x10000000)return 0x9f00001f; // ADR/ADRP: preserve destination and opcode
        if((op&0x3b000000)==0x18000000)return 0xff00001f; // literal load
        return -1;
    }
    private static final class ApkLibrary {
        Elf elf;long zipOffset;
        ApkLibrary(File path,String name)throws Exception{
            String entryName="lib/arm64-v8a/"+name;
            byte[] apk=read(path);
            // The APKs are below 256 MiB. Locate the central directory, then use
            // the exact local header offset. Searching for ZIP magic is unsafe.
            int eocd=-1;for(int i=apk.length-22;i>=Math.max(0,apk.length-65557);i--)if(word(apk,i)==0x06054b50&&i+22+u16(apk,i+20)==apk.length){eocd=i;break;}
            require(eocd>=0&&u16(apk,eocd+4)==0&&u16(apk,eocd+6)==0,"Unsupported APK ZIP layout");
            long cd=u32(apk,eocd+16);int count=u16(apk,eocd+10),found=0;long local=-1;
            for(int i=0;i<count;i++){
                range(apk,cd,46);require(word(apk,cd)==0x02014b50,"Bad APK directory");int n=u16(apk,cd+28),x=u16(apk,cd+30),c=u16(apk,cd+32);range(apk,cd+46,n+x+c);
                if(entryName.equals(new String(apk,offset(cd+46),n,StandardCharsets.UTF_8))){found++;local=u32(apk,cd+42);require(u16(apk,cd+10)==0&&(u16(apk,cd+8)&1)==0,"Launcher library must be uncompressed and unencrypted");}
                cd+=46+n+x+c;
            }
            require(found==1,"Missing/duplicate APK library: "+name);range(apk,local,30);require(word(apk,local)==0x04034b50,"Bad local APK header");
            zipOffset=local+30+u16(apk,local+26)+u16(apk,local+28);require(zipOffset%4096==0,"Unaligned APK library");
            try(ZipFile zip=new ZipFile(path);InputStream in=zip.getInputStream(zip.getEntry(entryName))){
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buf=new byte[65536];for(int n;(n=in.read(buf))>=0;){bytes.write(buf,0,n);require(bytes.size()<128*1024*1024,"Oversized APK library");}
                byte[] data=bytes.toByteArray();range(apk,zipOffset,data.length);require(Arrays.equals(data,Arrays.copyOfRange(apk,offset(zipOffset),offset(zipOffset)+data.length)),"Local ZIP data mismatch");elf=new Elf(data);
            }
        }
        void layout(ByteBuffer out){Segment s=elf.executable();out.putLong(zipOffset).putLong(s.address).putLong(s.size).putLong(hash(elf.data,offset(s.file),offset(s.size))).putLong(s.file);}
    }
    private static void log(Properties report,String name,long value){report.setProperty(name,"0x"+Long.toHexString(value));System.out.println(name+"=0x"+Long.toHexString(value));}

    static byte[] scan(File apk,File libraries,Properties report)throws Exception{
        ApkLibrary dart=new ApkLibrary(apk,"libapp.so"),nativeLib=new ApkLibrary(apk,"libapp_launcher.so");
        Elf d=dart.elf,n=nativeLib.elf;
        ByteBuffer out=ByteBuffer.allocate(LAYOUT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        out.put(MAGIC).putLong(1).putLong(1);dart.layout(out);nativeLib.layout(out);
        long[] badges=new long[5];
        for(int i=0;i<5;i++){badges[i]=d.unique("Dart badge "+i,BADGES[i]);require(d.matches(badges[i],BADGES[i],false),"Dart instruction mismatch");out.putLong(badges[i]);log(report,"dart.badge."+i,badges[i]);}
        require(d.matches(badges[0],NativePatterns.BADGE_FIRST_BODY,true)&&d.matches(badges[1],NativePatterns.BADGE_SECOND_BODY,true)
                &&d.branchTarget(badges[0]+0x20,true)==d.branchTarget(badges[1]+0x64,true)
                &&d.branchTarget(badges[0]+0x30,true)==d.branchTarget(badges[1]+0x74,true),"Dart paired predicate control flow changed");
        long label=d.unique("Dart label",NativePatterns.LABEL_HEAD);
        long target=d.branchTarget(label+56,false);
        require(target==label+176&&d.matches(target,NativePatterns.LABEL_TARGET,false),"Dart fallback label control flow changed");
        out.putLong(label).putLong(target);log(report,"dart.label",label);log(report,"dart.label_target",target);
        long call=n.unique("Activity label call",NativePatterns.TITLE_CALL)+20;
        long text=n.branchTarget(call,true);
        require(n.matches(text,NativePatterns.TEXT_EARLY,true)||n.matches(text,NativePatterns.TEXT_CURRENT,true),"get_text ABI changed");
        long encode=n.unique("Item serializer",NativePatterns.SERIALIZER_EARLY,NativePatterns.SERIALIZER_CURRENT);
        long user=n.plt("LauncherActivityInfoInternal_get_user"),id=n.plt("UserHandle_get_handle");
        out.putLong(call).putLong(text).putLong(encode).putLong(user).putLong(id);
        for(long address:new long[]{call,encode})for(int i=0;i<4;i++)out.putInt(n.instruction(address+i*4));
        require(out.position()==LAYOUT_SIZE,"Internal layout size mismatch");
        log(report,"native.title_call",call);log(report,"native.get_text",text);log(report,"native.serializer",encode);log(report,"native.get_user",user);log(report,"native.get_id",id);
        log(report,"dart.zip_offset",dart.zipOffset);log(report,"native.zip_offset",nativeLib.zipOffset);
        report.setProperty("launcher.sha256",sha(read(apk)));report.setProperty("dart.sha256",sha(d.data));report.setProperty("native.sha256",sha(n.data));
        for(int i=0;i<NAMES.length;i++){
            Elf e=new Elf(read(new File(libraries,NAMES[i])));
            // Also rejects extracting a previous module's forwarding library.
            e.renamed(NAMES[i],ORIGINALS[i]);
            if(i==0){
                Symbol predicate=e.symbol("Security_is_xspace_user_id"),object=e.symbol("Security_is_xspace_user");
                int[] idCode={0xd503233f,0xa9bf7bfd,0x910003fd,0x710f9c1f,0x1a9f17e0,0xa8c17bfd,0xd50323bf,0xd65f03c0};
                int[] tailCode={0xd503233f,0xa9bf7bfd,0x910003fd,0xa8c17bfd,0xd50323bf,0x14000187};
                int[] plainId={0xa9bf7bfd,0x910003fd,0x710f9c1f,0x1a9f17e0,0xa8c17bfd,0xd65f03c0};
                int[] plainTail={0xa9bf7bfd,0x910003fd,0xa8c17bfd,0x14000187};
                boolean pac=predicate.size==32&&e.matches(predicate.address,idCode,false);
                require(pac||(predicate.size==24&&e.matches(predicate.address,plainId,false)),"Security user-id predicate ABI changed");
                Symbol current=e.symbol("Security_get_current_user_id");
                int[] tail=pac?tailCode:plainTail;
                require(object.size==tail.length*4&&e.matches(object.address,tail,true)&&current.size==tail.length*4&&e.matches(current.address,tail,true),"Security user object ABI changed");
            }else if(i==1){require(function(e,"PackageManager_get_user_badged_icon",NativePatterns.PUBLIC_BADGE,NativePatterns.PUBLIC_BADGE_STACK),"Public badge ABI changed");}
            else {require(function(e,PRIVATE,NativePatterns.RUST_EARLY,NativePatterns.RUST_CURRENT,NativePatterns.RUST_EARLY_STACK,NativePatterns.RUST_CURRENT_STACK),"Rust badge ABI changed");log(report,"rust.badge",e.symbol(PRIVATE).address);}
            report.setProperty(NAMES[i]+".sha256",sha(e.data));
        }
        return out.array();
    }
    private static boolean function(Elf e,String name,int[]... patterns){
        Symbol s=e.symbol(name);for(int[] p:patterns)if(s.size==p.length*4&&e.matches(s.address,p,true))return true;return false;
    }
    private static void publish(File module,File libraries,byte[] layout,Properties report)throws Exception{
        File dir=new File(module,"system/system_ext/lib64");require(dir.isDirectory(),"Missing staged wrappers");
        File security=new File(dir,NAMES[0]);byte[] wrapper=read(security);int at=-1;
        for(int i=0;i<=wrapper.length-MAGIC.length;i++)if(wrapper[i]==MAGIC[0]){
            boolean match=true;for(int j=1;j<MAGIC.length;j++)match&=wrapper[i+j]==MAGIC[j];
            if(match){require(at<0,"Duplicate native layout marker");at=i;}
        }
        require(at>=0,"Wrapper has no install-time layout");range(wrapper,at,LAYOUT_SIZE);
        require(u64(wrapper,at+16)==1&&u64(wrapper,at+24)==0,"Wrapper already configured; use a fresh ZIP");
        byte[][] originals=new byte[NAMES.length][];
        for(int i=0;i<NAMES.length;i++){
            byte[] input=read(new File(libraries,NAMES[i]));
            require(sha(input).equals(report.getProperty(NAMES[i]+".sha256")),"Original library changed during scan");
            originals[i]=new Elf(input).renamed(NAMES[i],ORIGINALS[i]);
        }
        // All recognition and output validation completes before the first write.
        System.arraycopy(layout,0,wrapper,at,layout.length);
        for(int i=0;i<NAMES.length;i++)Files.write(new File(dir,ORIGINALS[i]).toPath(),originals[i]);
        Files.write(security.toPath(),wrapper);
        try(OutputStream stream=new FileOutputStream(new File(module,"native-layout.properties"))){report.store(stream,"Generated from target ROM; static recognition is not a runtime test");}
    }
    public static void main(String[] args){
        try{
            require(args.length==3||args.length==4,"Usage: NativePrepare <MiuiHome.apk> <original-lib64-dir> <report.properties> [staged-module-dir]");
            Properties report=new Properties();report.setProperty("format","1");
            byte[] layout=scan(new File(args[0]),new File(args[1]),report);
            if(args.length==4)publish(new File(args[3]),new File(args[1]),layout,report);
            try(OutputStream stream=new FileOutputStream(args[2])){report.store(stream,"Static native recognition; no runtime compatibility claim");}
            System.out.println("PASS: unique native signatures, control flow, ELF ABI and ZIP mappings verified");
        }catch(Throwable error){System.err.println("Native adaptation refused: "+error.getMessage());System.exit(1);}
    }
}
