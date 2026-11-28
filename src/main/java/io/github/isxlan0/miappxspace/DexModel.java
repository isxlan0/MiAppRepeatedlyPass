package io.github.isxlan0.miappxspace;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Bounded, read-only standard DEX reader. Never loads or executes inspected classes. */
final class DexModel {
    static final String DEX_NAME="classes(?:[2-9]|[1-9][0-9]+)?\\.dex";
    static void need(boolean ok,String why){if(!ok)throw new IllegalArgumentException(why);}
    static int at(long value){need(value>=0&&value<=Integer.MAX_VALUE,"DEX offset overflow");return (int)value;}
    static void range(byte[] b,long p,long n){need(p>=0&&n>=0&&p<=b.length&&n<=b.length-p,"Truncated DEX/resource data");}
    static int u2(byte[] b,int p){range(b,p,2);return (b[p]&255)|((b[p+1]&255)<<8);}
    static int i4(byte[] b,int p){range(b,p,4);return u2(b,p)|(u2(b,p+2)<<16);}
    static int u4(byte[] b,int p){int n=i4(b,p);need(n>=0,"Oversized DEX value");return n;}
    static final class Cursor {
        final byte[] b;int p;Cursor(byte[] b,int p){this.b=b;this.p=p;}
        int leb(){int v=0;for(int i=0;i<5;i++){range(b,p,1);int c=b[p++]&255;need(i!=4||(c&0xf0)==0,"Invalid ULEB128");v|=(c&127)<<(i*7);if((c&128)==0){need(v>=0,"Oversized ULEB128");return v;}}throw new IllegalArgumentException("Invalid ULEB128");}
        int sleb(){int v=0,s=0,c;do{range(b,p,1);c=b[p++]&255;v|=(c&127)<<s;s+=7;need(s<=35,"Invalid SLEB128");}while((c&128)!=0);if(s<32&&(c&64)!=0)v|=-1<<s;return v;}
    }
    static byte[] bytes(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;){need(n<=limit-out.size(),"Input too large");out.write(b,0,n);}return out.toByteArray();}
    static String sha(byte[] b)throws Exception {StringBuilder s=new StringBuilder();for(byte n:MessageDigest.getInstance("SHA-256").digest(b))s.append(String.format(Locale.ROOT,"%02x",n&255));return s.toString();}
    static String shaFile(File file)throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)digest.update(buffer,0,n);}StringBuilder s=new StringBuilder();for(byte n:digest.digest())s.append(String.format(Locale.ROOT,"%02x",n&255));return s.toString();}
    static final class Field {String owner,name,type;int flags;boolean defined;String id(){return owner+"->"+name+":"+type;}}
    static final class Method {
        Dex dex;String owner,name,proto;int flags,code;boolean defined;
        String id(){return owner+"->"+name+proto;}
    }
    static final class Cls {String name,parent;int flags;List<String> interfaces=new ArrayList<>();List<Method> methods=new ArrayList<>();List<Field> fields=new ArrayList<>();}
    static final class Dex {
        final byte[] b;String[] strings,types,protos;Field[] fields;Method[] methods;List<Cls> classes=new ArrayList<>();
        int count(int p,int width){int n=u4(b,p),off=u4(b,p+4);need(n<2000000,"Oversized DEX table");range(b,off,(long)n*width);return n;}
        String string(int id){need(id>=0&&id<strings.length,"Invalid DEX string index");return strings[id];}
        String type(int id){need(id>=0&&id<types.length,"Invalid DEX type index");return types[id];}
        String proto(int id){need(id>=0&&id<protos.length,"Invalid DEX proto index");return protos[id];}
        Field field(int id){need(id>=0&&id<fields.length,"Invalid DEX field index");return fields[id];}
        Method method(int id){need(id>=0&&id<methods.length,"Invalid DEX method index");return methods[id];}
        Dex(byte[] data){
            b=data;range(b,0,112);String magic=new String(b,0,8,StandardCharsets.ISO_8859_1);
            need(magic.matches("dex\\n0(35|37|38|39|40)\u0000"),"Unsupported standard DEX version");
            need(u4(b,32)==b.length&&u4(b,36)==112&&i4(b,40)==0x12345678,"Invalid DEX header");
            strings=new String[count(56,4)];for(int i=0;i<strings.length;i++){
                Cursor c=new Cursor(b,u4(b,u4(b,60)+4*i));int length=c.leb();need(length<=b.length,"Oversized DEX string");StringBuilder s=new StringBuilder();
                while(true){range(b,c.p,1);int x=b[c.p++]&255;if(x==0)break;
                    if(x<128)s.append((char)x);
                    else if((x&224)==192){range(b,c.p,1);int y=b[c.p++]&255;need((y&192)==128,"Invalid MUTF8");s.append((char)(((x&31)<<6)|(y&63)));}
                    else {need((x&240)==224,"Invalid MUTF8");range(b,c.p,2);int y=b[c.p++]&255,z=b[c.p++]&255;need((y&192)==128&&(z&192)==128,"Invalid MUTF8");s.append((char)(((x&15)<<12)|((y&63)<<6)|(z&63)));}
                    need(s.length()<=length,"Invalid DEX string length");
                }need(s.length()==length,"Invalid DEX string length");strings[i]=s.toString();
            }
            types=new String[count(64,4)];for(int i=0;i<types.length;i++)types[i]=string(u4(b,u4(b,68)+4*i));
            protos=new String[count(72,12)];for(int i=0;i<protos.length;i++){
                int p=u4(b,76)+12*i,args=u4(b,p+8);StringBuilder s=new StringBuilder("(");
                if(args!=0){int n=u4(b,args);range(b,args+4,n*2L);for(int j=0;j<n;j++)s.append(type(u2(b,args+4+j*2)));}protos[i]=s.append(')').append(type(u4(b,p+4))).toString();
            }
            fields=new Field[count(80,8)];for(int i=0;i<fields.length;i++){int p=u4(b,84)+8*i;Field f=new Field();f.owner=type(u2(b,p));f.type=type(u2(b,p+2));f.name=string(u4(b,p+4));fields[i]=f;}
            methods=new Method[count(88,8)];for(int i=0;i<methods.length;i++){int p=u4(b,92)+8*i;Method m=new Method();m.dex=this;m.owner=type(u2(b,p));m.proto=proto(u2(b,p+2));m.name=string(u4(b,p+4));methods[i]=m;}
            int n=count(96,32);for(int i=0;i<n;i++){
                int p=u4(b,100)+32*i;Cls c=new Cls();c.name=type(u4(b,p));c.flags=i4(b,p+4);c.parent=i4(b,p+8)==-1?"":type(u4(b,p+8));classes.add(c);
                int interfaces=u4(b,p+12);if(interfaces!=0){int size=u4(b,interfaces);range(b,interfaces+4,size*2L);for(int j=0;j<size;j++)c.interfaces.add(type(u2(b,interfaces+4+j*2)));}
                int off=u4(b,p+24);if(off==0)continue;Cursor cur=new Cursor(b,off);int[] counts={cur.leb(),cur.leb(),cur.leb(),cur.leb()};
                for(int k=0;k<4;k++){int index=0;need(counts[k]<2000000,"Oversized class data");for(int j=0;j<counts[k];j++){
                    int delta=cur.leb();need(delta<=Integer.MAX_VALUE-index,"Class index overflow");index+=delta;int flags=cur.leb();
                    if(k<2){Field f=field(index);need(f.owner.equals(c.name)&&!f.defined,"Duplicate/misowned DEX field");f.flags=flags;f.defined=true;c.fields.add(f);}
                    else {Method m=method(index);need(m.owner.equals(c.name)&&!m.defined,"Duplicate/misowned DEX method");m.flags=flags;m.defined=true;m.code=cur.leb();c.methods.add(m);}
                }}
            }
        }
    }
    static final class Archive {
        final Map<String,Cls> classes=new LinkedHashMap<>();final Map<String,Method> methods=new LinkedHashMap<>();final Map<String,Field> fields=new LinkedHashMap<>();
        final ResourceTable resources;final File file;
        Archive(){resources=new ResourceTable();file=null;}
        Archive(File file)throws Exception {this(Collections.singletonList(file));}
        Archive(List<File> files)throws Exception {
            need(!files.isEmpty(),"Missing APK inputs");this.file=files.get(0);ResourceTable table=new ResourceTable();int total=0,dexCount=0;
            for(File file:files){Set<String> names=new HashSet<>();
            try(ZipFile zip=new ZipFile(file)){Enumeration<? extends ZipEntry> entries=zip.entries();while(entries.hasMoreElements()){
                ZipEntry e=entries.nextElement();if(!e.getName().matches(DEX_NAME)&&!e.getName().equals("resources.arsc"))continue;
                need(names.add(e.getName()),"Duplicate DEX/resource entry");byte[] data;try(InputStream in=zip.getInputStream(e)){data=bytes(in,64*1024*1024);}total+=data.length;need(total<256*1024*1024,"Archive DEX budget exceeded");
                if(e.getName().equals("resources.arsc")){
                    ResourceTable part=new ResourceTable(data);
                    for(Map.Entry<Integer,String> item:part.names.entrySet()){
                        String old=table.names.put(item.getKey(),item.getValue());need(old==null||old.equals(item.getValue()),"Conflicting split resource ID");
                    }
                    for(Map.Entry<String,Integer> item:part.ids.entrySet()){
                        Integer old=table.ids.put(item.getKey(),item.getValue());need(old==null||old.equals(item.getValue()),"Conflicting split resource name");
                    }continue;
                }dexCount++;
                for(Cls c:new Dex(data).classes){need(classes.put(c.name,c)==null,"Duplicate DEX class: "+c.name);for(Method m:c.methods)methods.put(m.id(),m);for(Field f:c.fields)fields.put(f.id(),f);}
            }}}need(dexCount>0,"No standard DEX in "+file);resources=table;
        }
        String normalizedType(String text,boolean obfuscated){
            if(!obfuscated)return text;StringBuilder s=new StringBuilder();for(int i=0;i<text.length();){
                if(text.charAt(i)=='L'){int e=text.indexOf(';',i);need(e>=i,"Invalid descriptor");String t=text.substring(i,e+1);s.append(classes.containsKey(t)?"L@;":t);i=e+1;}else s.append(text.charAt(i++));
            }return s.toString();
        }
        String normalizeReference(String ref,boolean obfuscated){
            if(ref.startsWith("T:"))return "T:"+normalizedType(ref.substring(2),obfuscated);
            int end=ref.indexOf("->");String owner=ref.substring(2,end);int sig=ref.startsWith("M:")?ref.indexOf('(',end):ref.indexOf(':',end);
            String name=ref.substring(end+2,sig);boolean own=obfuscated&&classes.containsKey(owner);
            return ref.substring(0,2)+normalizedType(owner,obfuscated)+"->"+(own&&!name.startsWith("<")?"*":name)+normalizedType(ref.substring(sig),obfuscated);
        }
        Shape shape(Method m,boolean obfuscated)throws Exception{return new Shape(this,m,obfuscated);}
        Shape canonicalShape(Method m,boolean obfuscated)throws Exception{return new Shape(this,m,obfuscated,true);}
    }
    static final class Shape {
        final String hash;final List<String> refs=new ArrayList<>();
        final StringBuilder text=new StringBuilder();final Archive archive;final boolean obfuscated;
        void ref(String s){refs.add(s);text.append('{').append(archive.normalizeReference(s,obfuscated)).append('}');}
        void value(int n){String r=archive.resources.names.get(n);text.append(r==null?Integer.toHexString(n):"@"+r).append(',');}
        Shape(Archive archive,Method m,boolean obfuscated)throws Exception {this(archive,m,obfuscated,false);}
        Shape(Archive archive,Method m,boolean obfuscated,boolean canonical)throws Exception {
            this.archive=archive;this.obfuscated=obfuscated;Dex d=m.dex;byte[] b=d.b;
            text.append(m.flags&~(0x1000|0x40)).append('/').append(archive.normalizedType(m.proto,obfuscated)).append('/');
            // Definition and prototype references bind owners/types as well as code references.
            ref("T:"+m.owner);descriptorRefs(m.proto);
            if(m.code!=0){
                range(b,m.code,16);int start=m.code+16,n=u4(b,m.code+12),tries=u2(b,m.code+6);range(b,start,n*2L);
                CodePositions positions=canonical?new CodePositions(b,start,n):null;
                text.append(u2(b,m.code)).append('/').append(u2(b,m.code+2)).append('/').append(u2(b,m.code+4)).append('/');
                for(int pc=0;pc<n;){int p=start+pc*2,w=u2(b,p),op=w&255,width=width(op);
                    if(op==0&&w!=0){
                        int size=u2(b,p+2);if(w==0x100)width=at(4+size*2L);else if(w==0x200)width=at(2+size*4L);
                        else if(w==0x300){long count=Integer.toUnsignedLong(i4(b,p+4));width=at(4+(count*size+1)/2);}else throw new IllegalArgumentException("Unknown DEX payload");
                    }
                    need(width>0&&width<=n-pc,"Unknown/truncated DEX instruction");
                    if(canonical&&canonicalInstruction(d,p,pc,w,width,positions)){text.append(';');pc+=width;continue;}
                    text.append(Integer.toHexString(w)).append(':');
                    if(op==0x1a||op==0x1b){int id=op==0x1a?u2(b,p+2):u4(b,p+2);String s=d.string(id);text.append('s').append(s.length()).append(':').append(s);}
                    else if(op==0x1c||op==0x1f||op==0x20||op==0x22||op==0x23||op==0x24||op==0x25){ref("T:"+d.type(u2(b,p+2)));for(int j=2;j<width;j++)value(u2(b,p+j*2));}
                    else if(op>=0x52&&op<=0x6d){ref("F:"+d.field(u2(b,p+2)).id());}
                    else if((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)){ref("M:"+d.method(u2(b,p+2)).id());for(int j=2;j<width;j++)value(u2(b,p+j*2));}
                    else if(op==0x14){value(i4(b,p+2));}
                    else if(op==0x15){value(u2(b,p+2)<<16);}
                    else {need(op<0xfa,"Unsupported DEX dynamic invocation");for(int j=1;j<width;j++)value(u2(b,p+j*2));}
                    text.append(';');pc+=width;
                }
                int t=start+n*2+((tries>0&&(n&1)!=0)?2:0);range(b,t,tries*8L);text.append("tries:").append(tries).append(':');
                int handlers=t+tries*8;Map<Integer,String> handlerText=new HashMap<>();
                if(tries>0){Cursor c=new Cursor(b,handlers);int count=c.leb();need(count<=b.length,"Invalid catch handlers");for(int i=0;i<count;i++){
                    int offset=c.p-handlers,size=c.sleb();need(size!=Integer.MIN_VALUE&&Math.abs(size)<=b.length,"Invalid catch count");StringBuilder h=new StringBuilder();
                    for(int j=0;j<Math.abs(size);j++){String type=d.type(c.leb());int target=c.leb();need(target<n,"Catch target outside code");ref("T:"+type);h.append(archive.normalizedType(type,obfuscated)).append('@').append(canonical?positions.target(target):target).append(',');}
                    if(size<=0){int target=c.leb();need(target<n,"Catch-all outside code");h.append('*').append(canonical?positions.target(target):target);}handlerText.put(offset,h.toString());
                }}
                for(int i=0;i<tries;i++){int p=t+i*8,first=u4(b,p),length=u2(b,p+4);need(first<=n&&length<=n-first,"Invalid try range");String h=handlerText.get(u2(b,p+6));need(h!=null,"Invalid catch offset");text.append(canonical?positions.target(first):first).append('+').append(canonical?positions.boundary(first+length)-positions.target(first):length).append('=').append(h).append(';');}
            }else text.append("no-code");
            hash=sha(text.toString().getBytes(StandardCharsets.UTF_8));
        }
        void descriptorRefs(String s){for(int i=0;i<s.length();i++)if(s.charAt(i)=='L'){int end=s.indexOf(';',i);need(end>=0,"Invalid type");ref("T:"+s.substring(i,end+1));i=end;}}
        // Canonicalize encodings, not behavior: registers, values, conditions,
        // referenced members and logical branch/exception targets stay exact.
        boolean canonicalInstruction(Dex d,int p,int pc,int w,int width,CodePositions positions){
            byte[] b=d.b;int op=w&255;
            if(op==0x1a||op==0x1b){String s=d.string(op==0x1a?u2(b,p+2):u4(b,p+2));text.append("string:").append(w>>>8).append(':').append(s.length()).append(':').append(s);}
            else if(op>=1&&op<=9){int kind=(op-1)/3,form=(op-1)%3;int dest=form==0?(w>>8)&15:form==1?w>>>8:u2(b,p+2);int src=form==0?w>>>12:u2(b,p+(form==1?2:4));text.append("move").append(kind).append(':').append(dest).append(',').append(src);}
            else if(op>=0x12&&op<=0x15){int dest=op==0x12?(w>>8)&15:w>>>8;int value=op==0x12?(short)w>>12:op==0x13?(short)u2(b,p+2):op==0x14?i4(b,p+2):u2(b,p+2)<<16;text.append("const:").append(dest).append(':');value(value);}
            else if(op>=0x28&&op<=0x2a){int delta=op==0x28?(byte)(w>>>8):op==0x29?(short)u2(b,p+2):i4(b,p+2);text.append("goto:").append(positions.target((long)pc+delta));}
            else if(op>=0x32&&op<=0x3d){text.append(Integer.toHexString(w)).append(":target=").append(positions.target((long)pc+(short)u2(b,p+2)));}
            else if(op==0x26||op==0x2b||op==0x2c){long target=(long)pc+i4(b,p+2);positions.payload(target,op==0x26?0x300:op==0x2b?0x100:0x200);text.append(Integer.toHexString(w)).append(":payload=").append(positions.boundary(target));}
            else if(w==0x100||w==0x200){int count=u2(b,p+2),owner=positions.owner(pc);text.append(Integer.toHexString(w)).append(':').append(count).append(':');
                for(int i=0;i<count;i++){int key=w==0x100?i4(b,p+4)+i:i4(b,p+4+4*i);int delta=i4(b,p+(w==0x100?8+4*i:4+4*count+4*i));text.append(key).append('@').append(positions.target((long)owner+delta)).append(',');}
            }else if((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)){
                boolean range=op>=0x74;int count=range?w>>>8:w>>>12;need(range||count<=5,"Invalid invoke register count");
                text.append("invoke:").append(range?op-6:op).append(':');ref("M:"+d.method(u2(b,p+2)).id());text.append(count).append(':');
                int regs=u2(b,p+4);for(int i=0;i<count;i++)text.append(range?regs+i:i==4?(w>>8)&15:(regs>>(4*i))&15).append(',');
            }else return false;
            return true;
        }
    }
    static final class CodePositions {
        final Map<Integer,Integer> positions=new HashMap<>(),payloads=new HashMap<>(),owners=new HashMap<>();final int size;
        CodePositions(byte[] b,int start,int n){size=n;int ordinal=0;
            for(int pc=0;pc<n;){int p=start+2*pc,w=u2(b,p),step=width(w&255);positions.put(pc,ordinal++);
                if((w&255)==0&&w!=0){int count=u2(b,p+2);payloads.put(pc,w);
                    if(w==0x100)step=at(4+2L*count);else if(w==0x200)step=at(2+4L*count);else if(w==0x300)step=at(4+(Integer.toUnsignedLong(i4(b,p+4))*count+1)/2);else throw new IllegalArgumentException("Unknown DEX payload");
                }
                need(step>0&&step<=n-pc,"Unknown/truncated DEX instruction");pc+=step;
            }positions.put(n,ordinal);
            for(int pc=0;pc<n;){int p=start+2*pc,w=u2(b,p),op=w&255;
                if(op==0x2b||op==0x2c){int target=at((long)pc+i4(b,p+2));payload(target,op==0x2b?0x100:0x200);need(owners.put(target,pc)==null,"Shared switch payload is unsupported");}
                // Walk using the already checked instruction boundaries.
                int step=width(op);if(payloads.containsKey(pc)){int count=u2(b,p+2);step=w==0x100?at(4+2L*count):w==0x200?at(2+4L*count):at(4+(Integer.toUnsignedLong(i4(b,p+4))*count+1)/2);}pc+=step;
            }
        }
        int boundary(long pc){Integer index=pc>=0&&pc<=size?positions.get((int)pc):null;need(index!=null,"DEX target is not an instruction boundary");return index;}
        int target(long pc){need(pc<size&&!payloads.containsKey((int)pc),"DEX branch/catch targets data or end");return boundary(pc);}
        void payload(long pc,int kind){need(pc>=0&&pc<size&&Objects.equals(payloads.get((int)pc),kind),"Wrong DEX payload target");}
        int owner(int pc){Integer owner=owners.get(pc);need(owner!=null,"Orphaned switch payload");return owner;}
    }
    static int width(int op){
        if(op==0x18)return 5;
        if(op==0xfa||op==0xfb)return 4;
        if(op==0x03||op==0x06||op==0x09||op==0x14||op==0x17||op==0x1b||op==0x24||op==0x25||op==0x26||op==0x2a||op==0x2b||op==0x2c||(op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)||op==0xfc||op==0xfd)return 3;
        if(op==0x02||op==0x05||op==0x08||op==0x13||op==0x15||op==0x16||op==0x19||op==0x1a||op==0x1c||op==0x1f||op==0x20||op==0x22||op==0x23||op==0x29||(op>=0x2d&&op<=0x3d)||(op>=0x44&&op<=0x6d)||(op>=0x90&&op<=0xaf)||(op>=0xd0&&op<=0xe2)||op==0xfe||op==0xff)return 2;
        if(op<=0x12||op==0x1d||op==0x1e||op==0x21||op==0x27||op==0x28||(op>=0x7b&&op<=0x8f)||(op>=0xb0&&op<=0xcf))return 1;
        return 0;
    }
}
