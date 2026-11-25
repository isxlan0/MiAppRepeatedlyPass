package io.github.isxlan0.miappxspace;

import android.content.*;
import android.os.*;
import java.util.concurrent.*;

/** Calls the ROM's original IXSpaceManager, including its profile/XMSF initialization. */
final class StockClone {
    static void change(Context context,String pkg,boolean enable)throws Exception{
        CountDownLatch connected=new CountDownLatch(1);IBinder[] binder={null};
        ServiceConnection connection=new ServiceConnection(){
            public void onServiceConnected(ComponentName name,IBinder service){binder[0]=service;connected.countDown();}
            public void onServiceDisconnected(ComponentName name){binder[0]=null;}
            public void onNullBinding(ComponentName name){connected.countDown();}
        };
        Intent intent=new Intent().setComponent(new ComponentName("com.miui.securitycore","com.miui.xspace.service.XSpaceService"));
        HandlerThread callbacks=new HandlerThread("XSpace-stock-callback");callbacks.start();
        boolean bound=false;
        try{
            bound=context.bindService(intent,Context.BIND_AUTO_CREATE,command->new Handler(callbacks.getLooper()).post(command),connection);
            if(!bound||!connected.await(10,TimeUnit.SECONDS)||binder[0]==null)throw new IllegalStateException("无法连接系统双开服务。");
            Parcel in=Parcel.obtain(),out=Parcel.obtain();
            try{
                in.writeInterfaceToken("com.miui.xspace.service.IXSpaceManager");in.writeString(pkg);in.writeInt(enable?1:0);
                if(!binder[0].transact(3,in,out,0))throw new IllegalStateException("系统双开接口不可用。");
                out.readException();
            }finally{in.recycle();out.recycle();}
            for(int i=0;i<250;i++){
                int[] ids=ProfileRpc.call("instances",pkg,-1).getIntArray("ids");
                if(CloneSlots.contains(ids,999)==enable)return;
                SystemClock.sleep(100);
            }
            throw new IllegalStateException("系统双开操作尚未完成，请稍后重新打开管理页查看。");
        }finally{if(bound)context.unbindService(connection);callbacks.quitSafely();}
    }
}
