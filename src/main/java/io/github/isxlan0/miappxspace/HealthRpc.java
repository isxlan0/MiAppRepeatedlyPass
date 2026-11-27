package io.github.isxlan0.miappxspace;

import android.os.*;
import java.util.Properties;

/** Separate diagnostic transaction; it never grants profile mutation access. */
final class HealthRpc {
    static final int TRANSACTION=0x4d584302;
    static final String TOKEN="io.github.isxlan0.miappxspace.health.v1";
    static IBinder userService()throws Exception {
        return (IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"user");
    }
    static Bundle call(IBinder service,Bundle report)throws Exception {
        if(service==null)throw new IllegalStateException("System service unavailable");
        Parcel in=Parcel.obtain(),out=Parcel.obtain();
        try {
            in.writeInterfaceToken(TOKEN);in.writeBundle(report);
            if(!service.transact(TRANSACTION,in,out,0))throw new IllegalStateException("本次开机未加载 API 102 状态接口");
            out.readException();Bundle result=out.readBundle(HealthRpc.class.getClassLoader());
            if(result==null)throw new IllegalStateException("Empty health response");return result;
        }finally{in.recycle();out.recycle();}
    }
    static Bundle bundle(Properties p) {
        Bundle result=new Bundle();for(String key:p.stringPropertyNames())result.putString(key,p.getProperty(key));return result;
    }
    static Properties properties(Bundle b) {
        Properties p=new Properties();for(String key:b.keySet()){Object value=b.get(key);if(value instanceof String)p.setProperty(key,(String)value);}return p;
    }
}
