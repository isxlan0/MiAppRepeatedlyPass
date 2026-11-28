package io.github.isxlan0.miappxspace;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Real-ROM negative fixtures: no guessed offsets or successful no-op assertions. */
public final class OtaNativeTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static byte[] all(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[65536];for(int n;(n=in.read(b))>=0;)out.write(b,0,n);return out.toByteArray();}
    static File altered(File source,File output,String mode)throws Exception{
        try(ZipFile zip=new ZipFile(source);ZipOutputStream out=new ZipOutputStream(new FileOutputStream(output))){
            long position=0;
            for(String name:new String[]{"lib/arm64-v8a/libapp.so","lib/arm64-v8a/libapp_launcher.so"}){
                byte[] data;try(InputStream in=zip.getInputStream(zip.getEntry(name))){data=all(in);}
                if(name.endsWith("/libapp.so")){
                    byte[] pattern=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(0xf8473001).putInt(0xf10f9c3f).putInt(0x54000160).putInt(0xb84c3003).putInt(0x8b1c8063).array();
                    int found=-1;for(int i=0;i<=data.length-pattern.length;i+=4)if(data[i]==pattern[0]){
                        boolean equal=true;for(int j=1;j<pattern.length;j++)equal&=data[i+j]==pattern[j];if(equal){check(found<0,"fixture must be unique");found=i;}
                    }
                    check(found>=0,"missing fixture pattern");
                    if(mode.equals("changed"))data[found+4]^=1;
                    if(mode.equals("duplicate"))System.arraycopy(pattern,0,data,found+128,pattern.length);
                    // BL relocation is masked by shape matching, but both
                    // predicates must still call the same reviewed helpers.
                    if(mode.equals("flow"))data[found+0x20]^=1;
                }
                ZipEntry entry=new ZipEntry(name);entry.setMethod(ZipEntry.STORED);entry.setSize(data.length);entry.setCompressedSize(data.length);CRC32 crc=new CRC32();crc.update(data);entry.setCrc(crc.getValue());
                // Align the data to 16 KiB just as a directly mmap-able APK does.
                int padding=(int)((16384-(position+30+name.length())%16384)%16384);if(padding<4)padding+=16384;
                byte[] extra=new byte[padding];ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN).putShort((short)0x1234).putShort((short)(padding-4));entry.setExtra(extra);
                out.putNextEntry(entry);out.write(data);out.closeEntry();position+=30+name.length()+padding+data.length;
            }
        }
        return output;
    }
    public static void main(String[] args)throws Exception{
        File apk=new File(args[0]),libs=new File(args[1]),work=new File(args[2]);check(work.mkdirs()||work.isDirectory(),"test output");
        byte[] layout=NativePrepare.scan(apk,libs,new Properties());check(layout.length==240,"layout size");
        for(String kind:new String[]{"changed","duplicate","flow"}){
            File bad=altered(apk,new File(work,kind+".apk"),kind);boolean refused=false;
            try{NativePrepare.scan(bad,libs,new Properties());}catch(IllegalArgumentException ex){refused=ex.getMessage().startsWith(kind.equals("flow")?"Dart paired predicate control flow changed":"Dart badge 0: expected one complete match");System.out.println(kind+": "+ex.getMessage());}
            check(refused,"must reject "+kind+" instruction match");
        }
        File good=altered(apk,new File(work,"repacked.apk"),"same");
        byte[] moved=NativePrepare.scan(good,libs,new Properties());
        check(ByteBuffer.wrap(layout).order(ByteOrder.LITTLE_ENDIAN).getLong(32)!=ByteBuffer.wrap(moved).order(ByteOrder.LITTLE_ENDIAN).getLong(32),"must discover changed ZIP placement");
        File truncated=new File(work,"truncated.apk");Files.write(truncated.toPath(),new byte[64]);
        try{NativePrepare.scan(truncated,libs,new Properties());throw new AssertionError("accepted invalid ZIP");}catch(IllegalArgumentException expected){}
        System.out.println("PASS: changed/ambiguous code, mismatched call targets, malformed ZIP and relocated ZIP entries");
    }
}
