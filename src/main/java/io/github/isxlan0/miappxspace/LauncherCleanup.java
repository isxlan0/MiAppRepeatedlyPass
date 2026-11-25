package io.github.isxlan0.miappxspace;

import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import java.io.*;
import java.util.*;

/** Root-only, narrowly scoped repair for native Runtime's persisted clone icons. */
final class LauncherCleanup {
    static final File DATABASES=new File("/data/user_de/0/com.miui.home/databases");
    static final File BACKUPS=new File(Registry.DIR,"launcher-backup");
    static final class Row {
        final long id,serial;final int user;final String intent;
        Row(long id,long serial,int user,String intent){this.id=id;this.serial=serial;this.user=user;this.intent=intent;}
    }
    static void databases(File directory,List<File> result)throws IOException{
        File[] files=directory.listFiles();if(files==null)return;
        for(File file:files){
            if(!file.getCanonicalPath().startsWith(DATABASES.getCanonicalPath()+File.separator))throw new IOException("Database path escaped launcher directory");
            if(file.isDirectory())databases(file,result);
            else if(file.getName().startsWith("launcher_")&&file.getName().endsWith(".db"))result.add(file);
        }
    }
    static List<Row> stale(SQLiteDatabase db,Properties registry)throws Exception{
        ArrayList<Row> rows=new ArrayList<>();
        try(Cursor cursor=db.query("favorites",new String[]{"_id","profileId","intent"},"itemType=0 AND intent IS NOT NULL",null,null,null,null)){
            while(cursor.moveToNext()){
                String value=cursor.getString(2);Intent intent;
                try{intent=Intent.parseUri(value,0);}catch(Exception malformed){continue;}
                int user=intent.getIntExtra("profile",-1);
                if(user<900||user>999||intent.getComponent()==null)continue;
                String pkg=intent.getComponent().getPackageName();
                Object info=ProfileControl.info(user);Long serial=null;boolean verified=false,installed=true;
                if(info!=null){
                    serial=((Integer)ProfileControl.field(info,"serialNumber")).longValue();
                    // An existing profile with unknown ownership or incomplete state is retained.
                    try{ProfileControl.verify(user,registry,user!=999);verified=true;}catch(Exception invalid){continue;}
                    installed=ProfileControl.installed(pkg,user);
                }
                if(LauncherCleanupPolicy.stale(user,cursor.getLong(1),serial,verified,installed))rows.add(new Row(cursor.getLong(0),cursor.getLong(1),user,value));
            }
        }
        return rows;
    }
    static Map<File,List<Row>> plan(Properties registry)throws Exception{
        LinkedHashMap<File,List<Row>> result=new LinkedHashMap<>();ArrayList<File> files=new ArrayList<>();
        databases(DATABASES,files);Collections.sort(files);
        for(File file:files)try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY)){
            List<Row> rows=stale(db,registry);if(!rows.isEmpty())result.put(file,rows);
        }
        return result;
    }
    static int cleanDatabase(File file,File backup,Properties registry)throws Exception{
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READWRITE)){
            List<Row> rows=stale(db,registry);if(rows.isEmpty())return 0;
            // One full consistent row backup per layout, bounded instead of accumulating snapshots.
            db.execSQL("ATTACH DATABASE ? AS miapp_backup",new Object[]{backup.getPath()});
            try{
                db.execSQL("DROP TABLE IF EXISTS miapp_backup.favorites");
                db.execSQL("CREATE TABLE miapp_backup.favorites AS SELECT * FROM main.favorites");
                db.execSQL("DROP TABLE IF EXISTS miapp_backup.screens");
                db.execSQL("CREATE TABLE miapp_backup.screens AS SELECT * FROM main.screens");
            }finally{db.execSQL("DETACH DATABASE miapp_backup");}
            android.system.Os.chmod(backup.getPath(),0600);
            int removed=0;db.beginTransaction();
            try{
                for(Row row:rows)removed+=db.delete("favorites","_id=? AND profileId=? AND intent=? AND itemType=0",new String[]{Long.toString(row.id),Long.toString(row.serial),row.intent});
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            return removed;
        }
    }
    static int repair(boolean apply)throws Exception{
        if(android.os.Process.myUid()!=0)throw new SecurityException("Root required for launcher repair");
        Properties registry=CloneRegistry.read();Map<File,List<Row>> initial=plan(registry);int count=0;
        for(Map.Entry<File,List<Row>> entry:initial.entrySet())for(Row row:entry.getValue()){
            System.out.println(entry.getKey().getName()+": icon="+row.id+" user="+row.user+" serial="+row.serial);count++;
        }
        if(!apply||count==0){System.out.println("Stale clone icons: "+count);return count;}
        // Stop the native desktop before writing: its in-memory model otherwise restores rows.
        Control.run("/system/bin/am","force-stop","--user","0","com.miui.home");
        boolean runtimeStopped=false;
        try{
            // HyperOS Runtime is native; ActivityManager's force-stop alone leaves it alive.
            if("running".equals(Control.run("/system/bin/getprop","init.svc.hyos_spawner"))){
                Control.run("/system/bin/setprop","ctl.stop","hyos_spawner");runtimeStopped=true;
            }
            for(int i=0;i<40;i++){
                java.lang.Process child=new ProcessBuilder("/system/bin/pidof","com.miui.home").start();
                if(child.waitFor()!=0)break;
                if(i==39)throw new IOException("Launcher still running; refusing database writes");
                SystemClock.sleep(100);
            }
            BACKUPS.mkdirs();android.system.Os.chmod(BACKUPS.getPath(),0700);
            int removed=0;
            for(File file:initial.keySet()){
                String relative=DATABASES.toPath().relativize(file.toPath()).toString().replace(File.separatorChar,'_');
                File backup=new File(BACKUPS,relative);
                // Check again after stopping the desktop, including current package/user state.
                removed+=cleanDatabase(file,backup,CloneRegistry.read());
            }
            System.out.println("Removed stale clone icons: "+removed);return removed;
        }finally{
            if(runtimeStopped)Control.run("/system/bin/setprop","ctl.start","hyos_spawner");
            Control.run("/system/bin/am","start","--user","0","-a","android.intent.action.MAIN","-c","android.intent.category.HOME");
        }
    }
}
