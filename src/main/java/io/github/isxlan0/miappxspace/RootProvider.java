package io.github.isxlan0.miappxspace;

import android.content.AttributionSource;
import android.os.*;

/** app_process has no registered ApplicationThread; use the external provider handle. */
final class RootProvider {
    static Bundle call(String authority,String method,String arg,Bundle extras)throws Exception {
        if(android.os.Process.myUid()!=0)throw new SecurityException("Root required");
        Object activity=ProfileBackend.service("activity","android.app.IActivityManager");
        IBinder token=new Binder();Object holder=null;
        try {
            holder=Reflect.call(activity,"getContentProviderExternal",authority,0,token,"MiApp XSpace health");
            if(holder==null)throw new IllegalStateException("Provider not installed: "+authority);
            Object provider=holder.getClass().getField("provider").get(holder);
            return (Bundle)Reflect.call(provider,"call",new AttributionSource.Builder(0).build(),authority,method,arg,extras);
        }finally {
            if(holder!=null)Reflect.call(activity,"removeContentProviderExternalAsUser",authority,token,0);
        }
    }
}
