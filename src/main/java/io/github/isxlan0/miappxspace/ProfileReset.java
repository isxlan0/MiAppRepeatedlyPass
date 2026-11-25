package io.github.isxlan0.miappxspace;

import android.os.SystemClock;
import java.util.*;

/** Shared whole-account cleanup: validate every target before removing any profile. */
final class ProfileReset {
    static int[] plan(Properties p)throws Exception{
        ArrayList<Integer> targets=new ArrayList<>();
        if(ProfileControl.info(999)!=null){ProfileControl.verify(999,p,false);targets.add(999);}
        for(int id=CloneRegistry.MAX;id>=CloneRegistry.MIN;id--)if(CloneRegistry.extra(id,p)&&ProfileControl.info(id)!=null){
            ProfileControl.verify(id,p,true);targets.add(id);
        }
        int[] ids=new int[targets.size()];for(int i=0;i<ids.length;i++)ids[i]=targets.get(i);return ids;
    }
    static void forget(Properties p,int id)throws Exception{
        p.remove("space."+id);p.remove("pending."+id);
        for(String key:new HashSet<>(p.stringPropertyNames()))if(key.startsWith("app."+id+"."))p.remove(key);
        CloneRegistry.write(p);
    }
    static void remove(Properties p)throws Exception{
        int[] ids=plan(p);
        // Module spaces first; the stock profile is removed only after these finish.
        for(int pass=0;pass<2;pass++)for(int id:ids)if((id==999)==(pass==1)){
            ProfileControl.verify(id,p,id!=999);
            if(!Boolean.TRUE.equals(Reflect.call(ProfileControl.users,"removeUser",id)))throw new IllegalStateException("无法删除分身空间 "+id+"；已停止清理，请重新打开设置查看。");
            for(int attempt=0;attempt<240&&ProfileControl.info(id)!=null;attempt++)SystemClock.sleep(250);
            if(ProfileControl.info(id)!=null)throw new IllegalStateException("分身空间 "+id+" 仍在清理，记录已保留，请稍后再试。");
            forget(p,id);
        }
        for(int id=CloneRegistry.MAX;id>=CloneRegistry.MIN;id--)if(CloneRegistry.extra(id,p)&&ProfileControl.info(id)==null)forget(p,id);
        if(ProfileControl.info(999)==null)forget(p,999);
    }
}
