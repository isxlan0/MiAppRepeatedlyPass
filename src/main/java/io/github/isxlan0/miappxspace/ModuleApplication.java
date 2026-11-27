package io.github.isxlan0.miappxspace;

import android.app.Application;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public final class ModuleApplication extends Application {
    static final Set<XposedService> services=new CopyOnWriteArraySet<>();
    static final Set<Runnable> statusListeners=new CopyOnWriteArraySet<>();
    static void statusChanged(){for(Runnable listener:statusListeners)listener.run();}
    @Override public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener(){
            @Override public void onServiceBind(XposedService service){services.add(service);statusChanged();}
            @Override public void onServiceDied(XposedService service){services.remove(service);statusChanged();}
        });
    }
}
