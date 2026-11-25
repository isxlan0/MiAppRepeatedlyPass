package io.github.isxlan0.miappxspace;

import android.os.SystemClock;

/** A background CLONE must also be visible on the owner's display to show activities. */
final class ProfileStartup {
    static void boot(Object activityManager,Object userManager,int id)throws Exception{
        if(id<CloneRegistry.MIN||id>999)throw new IllegalArgumentException("Invalid XSpace profile: "+id);
        // startProfile selects visible mode when the profile's parent is current.
        // The generic visible-background-user API is disabled on this tablet.
        boolean started=Boolean.TRUE.equals(Reflect.call(activityManager,"startProfile",id));
        if(!started)throw new IllegalStateException("Cannot start XSpace "+id);
        for(int i=0;i<100;i++){
            if(Boolean.TRUE.equals(Reflect.call(userManager,"isUserUnlocked",id))&&
               Boolean.TRUE.equals(Reflect.call(userManager,"isUserVisible",id)))return;
            SystemClock.sleep(100);
        }
        throw new IllegalStateException("分身空间未解锁或不可显示："+id);
    }
}
