package io.github.isxlan0.miappxspace;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import static io.github.isxlan0.miappxspace.DexModel.*;

/** Semantic-identity scanner: reviewed method shapes plus a consistent reference graph. */
public final class JavaPrepare {
    static final String CREATE_USER="Lcom/android/server/pm/UserManagerService;->createUserInternalUnchecked(Ljava/lang/String;Ljava/lang/String;IIZ[Ljava/lang/String;Ljava/lang/Object;)Landroid/content/pm/UserInfo;";
    static Properties patterns()throws IOException {
        Properties p=new Properties();try(InputStream in=JavaPrepare.class.getClassLoader().getResourceAsStream("assets/java-patterns.properties")){
            need(in!=null,"Missing Java recognition patterns");p.load(in);
        }need("1".equals(p.getProperty("format")),"Unsupported Java patterns");return p;
    }
    static String required(Properties p,String key){String s=p.getProperty(key);need(s!=null,"Missing Java pattern: "+key);return s;}
    static String[] lines(Properties p,String key){String value=required(p,key);return value.isEmpty()?new String[0]:value.split("\n");}
    static final class Graph {
        final Map<String,String> values=new HashMap<>(),reverse=new HashMap<>();final Set<String> movable;
        Graph(Set<String> movable){this.movable=movable;}
        Graph(Graph g){movable=g.movable;values.putAll(g.values);reverse.putAll(g.reverse);}
        boolean put(String old,String actual){
            String before=values.get(old),other=reverse.get(actual);
            if(before!=null&&!before.equals(actual)||other!=null&&!other.equals(old))return false;
            values.put(old,actual);reverse.put(actual,old);return true;
        }
        boolean type(String old,String actual){if(!movable.contains(old)&&!old.equals(actual))return false;return put("T:"+old,"T:"+actual);}
        boolean descriptor(String old,String actual){
            int i=0,j=0;while(i<old.length()&&j<actual.length()){
                if(old.charAt(i)=='L'&&actual.charAt(j)=='L'){int a=old.indexOf(';',i),b=actual.indexOf(';',j);if(a<0||b<0||!type(old.substring(i,a+1),actual.substring(j,b+1)))return false;i=a+1;j=b+1;}
                else {if(old.charAt(i++)!=actual.charAt(j++))return false;}
            }return i==old.length()&&j==actual.length();
        }
        boolean bind(String old,String actual){
            if(!old.substring(0,2).equals(actual.substring(0,2)))return false;
            if(old.startsWith("T:"))return descriptor(old.substring(2),actual.substring(2));
            int a=old.indexOf("->"),b=actual.indexOf("->");String owner=old.substring(2,a);
            if(!type(owner,actual.substring(2,b)))return false;
            int x=old.indexOf(old.startsWith("M:")?'(':':',a),y=actual.indexOf(actual.startsWith("M:")?'(':':',b);
            if(!movable.contains(owner)&&!old.substring(a+2,x).equals(actual.substring(b+2,y)))return false;
            return descriptor(old.substring(x),actual.substring(y))&&put(old,actual);
        }
    }
    static final class Candidate {Method method;Shape shape;Candidate(Method m,Shape s){method=m;shape=s;}}
    static Graph bind(Graph prior,String id,String[] refs,Candidate candidate){
        if(refs.length!=candidate.shape.refs.size())return null;Graph next=new Graph(prior);
        if(!next.bind("M:"+id,"M:"+candidate.method.id()))return null;
        for(int i=0;i<refs.length;i++)if(!next.bind(refs[i],candidate.shape.refs.get(i)))return null;
        return next;
    }
    static Properties security(Archive apk,Properties p)throws Exception {
        int count=Integer.parseInt(required(p,"security.count"));Set<String> hashes=new HashSet<>(),protos=new HashSet<>();
        for(int i=0;i<count;i++)for(String alias:lines(p,"security."+i+".methods")){
            String key="security."+i+".method."+alias;hashes.add(required(p,key+".hash"));protos.add(required(p,key+".proto"));
        }
        Map<String,List<Candidate>> index=new HashMap<>();
        for(Method m:apk.methods.values())if(protos.contains(apk.normalizedType(m.proto,true))){
            Shape shape;try{shape=apk.shape(m,true);}catch(IllegalArgumentException unsupported){continue;}
            if(hashes.contains(shape.hash))index.computeIfAbsent(shape.hash,k->new ArrayList<>()).add(new Candidate(m,shape));
        }
        List<Properties> successes=new ArrayList<>();List<String> failures=new ArrayList<>();
        for(int i=0;i<count;i++)try{
            String prefix="security."+i+".";Set<String> movable=new HashSet<>(Arrays.asList(lines(p,prefix+"movable")));
            Graph graph=new Graph(movable);for(String token:lines(p,prefix+"fixed"))need(graph.bind(token,token),"Conflicting named contracts");
            List<String> pending=new ArrayList<>(Arrays.asList(lines(p,prefix+"methods")));
            while(!pending.isEmpty()){
                boolean progress=false;
                for(Iterator<String> it=pending.iterator();it.hasNext();){String alias=it.next(),key=prefix+"method."+alias,id=required(p,key+".id");String[] refs=lines(p,key+".refs");
                    Graph match=null;int matches=0;
                    for(Candidate c:index.getOrDefault(required(p,key+".hash"),Collections.emptyList())){Graph g=bind(graph,id,refs,c);if(g!=null){match=g;matches++;}}
                    need(matches>0,"No compatible Java method: "+alias);
                    if(matches==1){graph=match;it.remove();progress=true;}
                }
                need(progress,"Ambiguous Java reference graph: "+pending);
            }
            Properties result=new Properties();
            for(String alias:lines(p,prefix+"aliases")){
                String token=required(p,prefix+"alias."+alias),actual=graph.values.get(token);need(actual!=null,"Unbound Java member: "+alias);
                if(actual.startsWith("F:")){Field f=apk.fields.get(actual.substring(2));need(f!=null,"Missing Java field definition: "+alias);need(Integer.toString(f.flags).equals(required(p,prefix+"flags."+alias)),"Java field flags changed: "+alias);}
                result.setProperty(alias,actual);
            }
            // Names used by Android manifests/XML and non-obfuscated reflective APIs stay contractual.
            for(String token:lines(p,prefix+"fixed"))need(token.equals(graph.values.get(token)),"Named Java contract changed: "+token);
            for(String name:lines(p,"resources")){Integer id=apk.resources.ids.get(name);need(id!=null,"Missing Java resource: "+name);result.setProperty("resource."+name,Integer.toUnsignedString(id));}
            successes.add(result);
        }catch(IllegalArgumentException error){failures.add(error.getMessage());}
        need(!successes.isEmpty(),"Java recognition refused: "+failures);Properties found=successes.get(0);
        for(Properties result:successes)need(found.equals(result),"Conflicting Java pattern results");return found;
    }
    static void framework(File system,File ext,Properties p,Properties report)throws Exception {
        for(String name:lines(p,"framework.archives")){
            File file=new File(name.startsWith("miui-")?ext:system,"framework/"+name);Archive normalizer=new Archive();
            Set<String> methods=new HashSet<>(Arrays.asList(lines(p,"framework."+name+".methods"))),fields=new HashSet<>(Arrays.asList(lines(p,"framework."+name+".fields")));
            Set<String> seenMethods=new HashSet<>(),seenFields=new HashSet<>(),entries=new HashSet<>();Set<String> missingClasses=new HashSet<>(Arrays.asList(lines(p,"framework."+name+".classes")));
            Map<String,SortedSet<String>> candidates=new TreeMap<>();
            for(String id:methods)candidates.put(methodName(id),new TreeSet<>());
            try {
            // Read one DEX at a time. Keeping an entire framework's object graph
            // alive exceeds app_process's ordinary 256 MiB heap on this device.
            try(ZipFile zip=new ZipFile(file)){Enumeration<? extends ZipEntry> all=zip.entries();int total=0;
                while(all.hasMoreElements()){ZipEntry entry=all.nextElement();if(!entry.getName().matches(DEX_NAME))continue;need(entries.add(entry.getName()),"Duplicate framework DEX");
                    byte[] data;try(InputStream in=zip.getInputStream(entry)){data=bytes(in,64*1024*1024);}total+=data.length;need(total<=256*1024*1024,"Framework DEX budget exceeded");
                    verifyDex(data,normalizer,p,methods,fields,seenMethods,seenFields,missingClasses,candidates,report);
                }
            }
            Set<String> missing=new HashSet<>(methods);missing.removeAll(seenMethods);Set<String> missingFields=new HashSet<>(fields);missingFields.removeAll(seenFields);
            need(missing.isEmpty()&&missingFields.isEmpty()&&missingClasses.isEmpty(),missingMembers(missing,missingFields,missingClasses,candidates));
            report.setProperty(name+".sha256",shaFile(file));report.setProperty(name+".methods",Integer.toString(methods.size()));System.out.println("PASS: "+name+" "+methods.size()+" required method shapes");
            } catch(Exception error) {
                String digest;try{digest=shaFile(file);}catch(Exception unreadable){digest="unavailable";}
                throw new IllegalArgumentException("Framework archive: "+file+"\narchive.sha256="+digest+"\n"+error.getMessage(),error);
            }
        }
    }
    static String methodName(String id){return id.substring(0,id.indexOf('('));}
    static Map<String,String> frameworkContracts(Properties p,Set<String> methods){
        Map<String,String> contracts=new HashMap<>();
        for(String id:methods){
            List<String> signatures=new ArrayList<>();signatures.add(id);
            String variants=p.getProperty("framework.alternatives."+id);
            if(variants!=null)signatures.addAll(Arrays.asList(variants.split("\n")));
            for(String variant:signatures){
                need(methodName(id).equals(methodName(variant)),"Framework alternative must keep its declaring class and name: "+variant);
                required(p,"framework.method."+variant);
                need(contracts.put(variant,id)==null,"Overlapping framework alternatives: "+variant);
            }
        }return contracts;
    }
    static String missingMembers(Set<String> methods,Set<String> fields,Set<String> classes,Map<String,SortedSet<String>> candidates){
        StringBuilder message=new StringBuilder("Missing framework members:");
        for(String id:new TreeSet<>(methods)){
            message.append("\nexpected method: ").append(id);
            Set<String> actual=candidates.get(methodName(id));
            if(actual==null||actual.isEmpty())message.append("\nactual same-name method: <none>");
            else for(String found:actual)message.append("\nactual same-name method: ").append(found);
        }
        for(String id:new TreeSet<>(fields))message.append("\nmissing field: ").append(id);
        for(String id:new TreeSet<>(classes))message.append("\nmissing class: ").append(id);
        return message.toString();
    }
    static void verifyDex(byte[] data,Archive normalizer,Properties p,Set<String> methods,Set<String> fields,Set<String> seenMethods,Set<String> seenFields,Set<String> missingClasses)throws Exception {
        verifyDex(data,normalizer,p,methods,fields,seenMethods,seenFields,missingClasses,Collections.emptyMap(),new Properties());
    }
    static void verifyDex(byte[] data,Archive normalizer,Properties p,Set<String> methods,Set<String> fields,Set<String> seenMethods,Set<String> seenFields,Set<String> missingClasses,Map<String,SortedSet<String>> candidates,Properties report)throws Exception {
        Map<String,String> contracts=frameworkContracts(p,methods);
        for(Cls cls:new Dex(data).classes){
            missingClasses.remove(cls.name);
            String parent=p.getProperty("framework.parent."+cls.name);if(parent!=null)need(parent.equals(cls.parent),"Framework inheritance changed: "+cls.name);
            String inherited=p.getProperty("framework.inherited."+cls.name);if(inherited!=null)for(Method m:cls.methods)need(!Arrays.asList(inherited.split("\n")).contains(m.name),"Framework method declaration moved: "+m.id());
            for(Method m:cls.methods){String id=m.id();
                Set<String> actual=candidates.get(methodName(id));
                if(actual!=null&&!methods.contains(id)){
                    String shape;try{shape=normalizer.shape(m,false).hash;}catch(IllegalArgumentException unsupported){shape="unavailable ("+unsupported.getMessage()+")";}
                    actual.add(id+"\n  flags=0x"+Integer.toHexString(m.flags)+"; shape="+shape);
                }
                String contract=contracts.get(id);
                if(contract!=null){
                need(seenMethods.add(contract),"Duplicate or ambiguous framework method: "+contract+"; candidate="+id);String shape=normalizer.shape(m,false).hash;
                need(Arrays.asList(lines(p,"framework.method."+id)).contains(shape),"Framework method logic changed: "+id+"\nexpected shape: "+String.join(", ",lines(p,"framework.method."+id))+"\nactual shape: "+shape);
                report.setProperty("framework.binding."+contract,id);
            }}
            for(Field f:cls.fields){String id=f.id();if(fields.contains(id))need(seenFields.add(id)&&Integer.toString(f.flags).equals(required(p,"framework.field."+id)),"Framework field changed: "+id);}
        }
    }
    public static void main(String[] args){
        try{
            need(args.length==4,"Usage: JavaPrepare <system-root> <system-ext-root> <SecurityCore.apk> <report.properties>");Properties p=patterns(),report=new Properties();
            framework(new File(args[0]),new File(args[1]),p,report);File apk=new File(args[2]);report.putAll(security(new Archive(apk),p));
            report.setProperty("format","1");report.setProperty("security.sha256",shaFile(apk));
            try(OutputStream out=new FileOutputStream(args[3])){report.store(out,"Discovered Java bindings; method shape checks do not prove full behavior");}
            System.out.println("PASS: Java method shapes, reference graph and resource identities verified");
        }catch(Throwable error){System.err.println("Java adaptation refused: "+error.getMessage());System.exit(1);}
    }
}
