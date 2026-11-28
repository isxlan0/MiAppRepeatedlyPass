package io.github.isxlan0.miappxspace;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static io.github.isxlan0.miappxspace.DexModel.*;

/** Host-only entry points into production recognizers; never updates patterns. */
public final class OtaCompatibility {
    static void namedMethods(File file,String[] expected,Properties report)throws Exception {
        Set<String> missing=new TreeSet<>(Arrays.asList(expected));
        Archive normalizer=new Archive();
        try(ZipFile zip=new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();if(!entry.getName().matches(DEX_NAME))continue;
                byte[] data;try(InputStream in=zip.getInputStream(entry)){data=bytes(in,64*1024*1024);}
                for(Cls cls:new Dex(data).classes)for(Method method:cls.methods)if(missing.remove(method.id().substring(0,method.id().indexOf(')')+1))) {
                    report.setProperty(method.id(),"flags="+method.flags+";shape="+normalizer.shape(method,false).hash);
                    System.out.println("FOUND: "+method.id());
                }
            }
        }
        need(missing.isEmpty(),"Missing notification hook signatures: "+missing);
        report.setProperty("scope","Exact method presence only; recorded shapes are not reviewed behavior assertions");
    }

    public static void main(String[] args)throws Exception {
        need(args.length>=3,"Usage: OtaCompatibility <mode> <output.properties> <inputs...>");
        Properties report=new Properties();String mode=args[0];
        if(mode.equals("framework")) {
            Properties patterns=JavaPrepare.patterns();patterns.setProperty("framework.archives",args[4]);
            JavaPrepare.framework(new File(args[2]),new File(args[3]),patterns,report);
        }else if(mode.equals("security")||mode.equals("management")) {
            Properties patterns=JavaPrepare.patterns();
            if(mode.equals("management")) {
                patterns=new Properties();
                try(InputStream in=OtaCompatibility.class.getClassLoader().getResourceAsStream("assets/management-patterns.properties")) {
                    need(in!=null,"Missing management patterns");patterns.load(in);
                }
            }
            report.putAll(JavaPrepare.security(new Archive(new File(args[2])),patterns));
            System.out.println("PASS: "+mode+" "+report.size()+" bindings");
        }else if(mode.equals("native")) {
            byte[] layout=NativePrepare.scan(new File(args[2]),new File(args[3]),report);
            Files.write(Paths.get(args[1]+".layout"),layout);
            System.out.println("PASS: native signatures, control flow, ELF ABI and APK offsets");
        }else if(mode.equals("settings")) {
            namedMethods(new File(args[2]),new String[]{
                "Lcom/android/settings/notification/BaseNotificationSettings;->onCreate(Landroid/os/Bundle;)"
            },report);
        }else if(mode.equals("notification-helper")) {
            namedMethods(new File(args[2]),new String[]{
                "Lmiui/util/NotificationFilterHelper;->resolveAssociatedUid(Landroid/content/Context;Landroid/os/UserHandle;Ljava/lang/String;Z)",
                "Lmiui/util/NotificationFilterHelper;->enableNotifications(Landroid/content/Context;Ljava/lang/String;Z)",
                "Lmiui/util/NotificationFilterHelper;->enableNotifications(Landroid/content/Context;Ljava/lang/String;IZ)"
            },report);
        }else throw new IllegalArgumentException("Unknown mode: "+mode);
        try(OutputStream out=new FileOutputStream(args[1])) {
            report.store(out,"OTA static compatibility evidence; no device behavior claim");
        }
    }
}
