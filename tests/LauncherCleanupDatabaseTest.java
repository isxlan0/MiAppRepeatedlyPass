package io.github.isxlan0.miappxspace;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.Properties;

/** Run through app_process on the pinned device; creates only temporary test databases. */
public final class LauncherCleanupDatabaseTest {
    static void check(boolean condition){if(!condition)throw new AssertionError();}
    static int count(SQLiteDatabase db,String table){
        try(Cursor c=db.rawQuery("SELECT count(*) FROM "+table,null)){c.moveToFirst();return c.getInt(0);}
    }
    static String intent(int user){return "#Intent;action=android.intent.action.MAIN;component=io.github.isxlan0.miappxspace.removed/.Main;i.profile="+user+";end";}
    public static void main(String[] args)throws Exception{
        check(ProfileControl.info(998)==null);check(ProfileControl.info(999)==null);
        File directory=new File("/data/local/tmp");
        File file=File.createTempFile("miapp-db-test-",".db",directory);
        File backup=File.createTempFile("miapp-db-backup-",".db",directory);
        try{
            try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(file,null)){
                db.execSQL("CREATE TABLE favorites(_id INTEGER PRIMARY KEY,profileId INTEGER,intent TEXT,itemType INTEGER,title TEXT,icon BLOB)");
                db.execSQL("CREATE TABLE screens(_id INTEGER PRIMARY KEY,screenOrder INTEGER)");
                db.execSQL("INSERT INTO screens VALUES(7,0)");
                db.execSQL("INSERT INTO favorites VALUES(1,1001,?,0,'clone3',X'1234')",new Object[]{intent(998)});
                db.execSQL("INSERT INTO favorites VALUES(2,1010,?,0,'clone2',X'5678')",new Object[]{intent(999)});
                db.execSQL("INSERT INTO favorites VALUES(3,0,?,0,'owner',X'9012')",new Object[]{intent(0)});
                db.execSQL("INSERT INTO favorites VALUES(4,10,?,0,'private',X'3456')",new Object[]{intent(10)});
                db.execSQL("INSERT INTO favorites VALUES(5,1001,?,4,'widget',NULL)",new Object[]{intent(998)});
                db.execSQL("INSERT INTO favorites VALUES(6,1001,'malformed',0,'unknown',NULL)");
                db.execSQL("INSERT INTO favorites VALUES(7,1001,NULL,22,'folder',NULL)");
            }
            check(LauncherCleanup.cleanDatabase(file,backup,new Properties())==2);
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY)){
                check(count(db,"favorites")==5);check(count(db,"screens")==1);
                try(Cursor c=db.rawQuery("SELECT hex(icon),title FROM favorites WHERE _id=3",null)){
                    check(c.moveToFirst());check("9012".equals(c.getString(0)));check("owner".equals(c.getString(1)));
                }
            }
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(backup.getPath(),null,SQLiteDatabase.OPEN_READONLY)){
                check(count(db,"favorites")==7);check(count(db,"screens")==1);
            }
            check(LauncherCleanup.cleanDatabase(file,backup,new Properties())==0);
            System.out.println("PASS: selective SQLite deletion, icon bytes and widgets preserved, full row backup, idempotence");
        }finally{
            SQLiteDatabase.deleteDatabase(file);SQLiteDatabase.deleteDatabase(backup);
        }
    }
}
