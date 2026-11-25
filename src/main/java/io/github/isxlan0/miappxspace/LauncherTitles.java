package io.github.isxlan0.miappxspace;

import android.content.Context;
import android.content.pm.*;
import android.os.*;
import android.system.Os;
import java.io.*;
import java.util.*;

/** Publishes owner labels for native Runtime DTOs without touching launcher DB. */
final class LauncherTitles {
    static final File DIRECTORY=new File("/data/user_de/0/com.miui.home/files");
    static final File LABELS=new File(DIRECTORY,"miapp-owner-labels.tsv");
    static int refresh()throws Exception{
        if(android.os.Process.myUid()!=0)throw new SecurityException("Root required for owner labels");
        if(Looper.myLooper()==null)Looper.prepare();
        Object thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context context=(Context)Reflect.call(thread,"getSystemContext");
        PackageManager pm=context.getPackageManager();
        int homeUid=pm.getApplicationInfo("com.miui.home",0).uid;
        TreeMap<String,String> labels=new TreeMap<>();
        for(ApplicationInfo app:pm.getInstalledApplications(0)){
            CharSequence label=app.loadLabel(pm);if(label==null)continue;
            String safe=label.toString().replace('\t',' ').replace('\r',' ').replace('\n',' ');
            if(!safe.isEmpty()&&safe.getBytes("UTF-8").length<4096)labels.put(app.packageName,safe);
        }
        if(labels.isEmpty())throw new IOException("No owner labels resolved");
        if(!DIRECTORY.isDirectory())throw new IOException("Launcher files directory unavailable");
        File temp=File.createTempFile("miapp-labels-",".tmp",DIRECTORY);
        try{
            try(FileOutputStream out=new FileOutputStream(temp)){
                for(Map.Entry<String,String> entry:labels.entrySet())out.write((entry.getKey()+"\t"+entry.getValue()+"\n").getBytes("UTF-8"));
                out.getFD().sync();
            }
            Os.chown(temp.getPath(),homeUid,homeUid);Os.chmod(temp.getPath(),0600);
            Control.run("/system/bin/restorecon",temp.getPath());
            Os.rename(temp.getPath(),LABELS.getPath());
        }finally{temp.delete();}
        System.out.println("Published "+labels.size()+" owner labels for Runtime desktop");return labels.size();
    }
}
