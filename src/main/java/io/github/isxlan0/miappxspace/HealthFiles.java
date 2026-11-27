package io.github.isxlan0.miappxspace;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

final class HealthFiles {
    static String read(File file)throws IOException { return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8).trim(); }
    static String bootId()throws IOException { return read(new File("/proc/sys/kernel/random/boot_id")); }
    static String startTime(int pid)throws IOException {
        String stat=read(new File("/proc/"+pid+"/stat"));
        return stat.substring(stat.lastIndexOf(')')+2).split(" ")[19];
    }
    static Properties load(File file)throws IOException {
        Properties p=new Properties();try(Reader in=new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8)){p.load(in);}return p;
    }
    static void write(File file,Properties values)throws IOException {
        File temp=new File(file.getParentFile(),file.getName()+".tmp");
        try(FileOutputStream out=new FileOutputStream(temp)){values.store(out,"MiApp XSpace runtime");out.getFD().sync();}
        Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    static String clean(String text,int max) {
        String value=text==null?"":text.replaceAll("[\\p{Cntrl}]+"," ").trim();
        return value.length()>max?value.substring(0,max)+"…":value;
    }
    static boolean sameProcess(Properties p,String boot,String start,int pid) {
        return !boot.isEmpty()&&!start.isEmpty()&&boot.equals(p.getProperty("boot"))&&start.equals(p.getProperty("start"))&&Integer.toString(pid).equals(p.getProperty("pid"));
    }
}
