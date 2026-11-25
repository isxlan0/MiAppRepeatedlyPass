package io.github.isxlan0.miappxspace;

import android.os.*;

/** Versioned extension on the existing user Binder; no exported app component or su grant. */
final class ProfileRpc {
    static final int TRANSACTION=0x4d584301;
    static final String TOKEN="io.github.isxlan0.miappxspace.rpc.v1";
    static Bundle call(String operation,String pkg,int user)throws Exception{
        IBinder service=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"user");
        Parcel in=Parcel.obtain(),out=Parcel.obtain();
        try{
            in.writeInterfaceToken(TOKEN);in.writeString(operation);in.writeString(pkg);in.writeInt(user);
            if(!service.transact(TRANSACTION,in,out,0))throw new IllegalStateException("XSpace system extension unavailable; reboot required");
            out.readException();Bundle result=out.readBundle(ProfileRpc.class.getClassLoader());
            if(result==null||result.containsKey("error"))throw new IllegalStateException(result==null?"Empty system reply":result.getString("error"));
            return result;
        }finally{in.recycle();out.recycle();}
    }
    private static int[] ids=new int[0];
    private static long checked;
    static synchronized boolean extra(int id){
        if(id<CloneRegistry.MIN||id>CloneRegistry.MAX)return false;
        long now=SystemClock.elapsedRealtime();
        if(now-checked>1500)try{ids=call("spaces",null,-1).getIntArray("ids");checked=now;}catch(Exception e){return false;}
        if(ids!=null)for(int item:ids)if(item==id)return true;
        return false;
    }
}
