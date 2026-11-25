package io.github.isxlan0.miappxspace;

import java.io.*;
import java.util.*;

final class CloneRegistry {
    static final String TYPE="android.os.usertype.profile.CLONE";
    // Recognize old owned profiles for routing and safe cleanup, but create only 998/997.
    static final int FLAG=0x800000, MIN=900, MAX=998, LIMIT=3;
    static final File FILE=new File(Registry.DIR,"clones.properties");
    static Properties read() throws IOException {
        Properties p=new Properties();
        if(FILE.isFile())try(FileInputStream in=new FileInputStream(FILE)){p.load(in);}
        return p;
    }
    static boolean extra(int id,Properties p){return id>=MIN&&id<=MAX&&p.containsKey("space."+id);}
    static String name(int id){return "MiApp XSpace "+id;}
    static void write(Properties p)throws Exception{
        File temp=new File(Registry.DIR,"clones.properties.new");
        try(FileOutputStream out=new FileOutputStream(temp)){p.store(out,"MiApp native CLONE profiles");out.getFD().sync();}
        android.system.Os.chmod(temp.getPath(),0660);
        if(android.os.Process.myUid()==0)android.system.Os.chown(temp.getPath(),0,1000);
        if(!temp.renameTo(FILE))throw new IOException("Clone registry rename failed");
        if(android.os.Process.myUid()==0)Control.run("/system/bin/restorecon",FILE.getPath());
    }
}
