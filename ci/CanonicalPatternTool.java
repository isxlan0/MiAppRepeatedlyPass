package io.github.isxlan0.miappxspace;

import java.io.*;import java.nio.file.*;import java.nio.charset.*;import java.util.*;import java.util.zip.*;
import static io.github.isxlan0.miappxspace.DexModel.*;

/** Development-only migration. Records canonical hashes ONLY for bodies already
 * accepted by the existing exact rules. Never invoked by production scanning. */
public final class CanonicalPatternTool {
    static void record(Properties p,Map<String,SortedSet<String>> found,String key,String hash){found.computeIfAbsent(key,k->new TreeSet<>()).add(hash);}
    public static void main(String[] args)throws Exception {
        Path file=Paths.get(args[0]);Properties p=new Properties();try(InputStream in=Files.newInputStream(file)){p.load(in);}
        Map<String,SortedSet<String>> found=new TreeMap<>();Map<String,List<String>> security=new HashMap<>();Set<String> protos=new HashSet<>();
        for(String key:p.stringPropertyNames())if(key.startsWith("security.")&&key.endsWith(".hash")){
            String base=key.substring(0,key.length()-5);security.computeIfAbsent(p.getProperty(key),k->new ArrayList<>()).add(base);protos.add(p.getProperty(base+".proto"));
        }
        for(int i=1;i<args.length;i++){
            File input=new File(args[i]);
            if(input.getName().endsWith(".apk")){
                Archive a=new Archive(input);
                for(Method m:a.methods.values())if(protos.contains(a.normalizedType(m.proto,true))){
                    Shape old;try{old=a.shape(m,true);}catch(IllegalArgumentException unsupported){continue;}
                    List<String> keys=security.get(old.hash);if(keys==null)continue;
                    Shape canonical=a.canonicalShape(m,true);need(old.refs.equals(canonical.refs),"Canonical reference order changed");
                    for(String key:keys)record(p,found,key+".canonical",canonical.hash);
                }
            }else try(ZipFile zip=new ZipFile(input)){
                Archive normalizer=new Archive();Enumeration<? extends ZipEntry> entries=zip.entries();
                while(entries.hasMoreElements()){
                    ZipEntry e=entries.nextElement();if(!e.getName().matches(DEX_NAME))continue;byte[] data;try(InputStream in=zip.getInputStream(e)){data=bytes(in,64*1024*1024);}
                    for(Cls cls:new Dex(data).classes)for(Method m:cls.methods){String accepted=p.getProperty("framework.method."+m.id());if(accepted==null)continue;
                        Shape old=normalizer.shape(m,false);if(!Arrays.asList(accepted.split("\n")).contains(old.hash))continue;
                        Shape canonical=normalizer.canonicalShape(m,false);need(old.refs.equals(canonical.refs),"Canonical reference order changed");
                        record(p,found,"framework.canonical."+m.id(),canonical.hash);
                    }
                }
            }
            System.out.println("Migrated reviewed bodies: "+input);
        }
        for(String key:p.stringPropertyNames())if(key.startsWith("framework.method."))need(found.containsKey(key.replace("framework.method.","framework.canonical.")),"Missing reviewed fixture "+key);
        for(List<String> keys:security.values())for(String key:keys)need(found.containsKey(key+".canonical"),"Missing reviewed fixture "+key);
        String text=new String(Files.readAllBytes(file),StandardCharsets.UTF_8);StringBuilder extra=new StringBuilder("\n# Canonical DEX v2: encoding widths normalized; logical targets and operands retained.\n");
        for(Map.Entry<String,SortedSet<String>> e:found.entrySet()){
            if(e.getKey().startsWith("security."))need(e.getValue().size()==1,"Exact security shape has conflicting canonical meanings");
            need(!p.containsKey(e.getKey()),"Migration must not overwrite existing canonical rules");
            extra.append(e.getKey().replace(":","\\:").replace("=","\\=")).append('=').append(String.join("\\n",e.getValue())).append('\n');
        }
        Files.write(file,(text+extra).getBytes(StandardCharsets.UTF_8));System.out.println("Recorded "+found.size()+" reviewed canonical rules");
    }
}
