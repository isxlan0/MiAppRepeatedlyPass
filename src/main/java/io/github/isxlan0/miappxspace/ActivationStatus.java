package io.github.isxlan0.miappxspace;

import io.github.libxposed.service.XposedService;

/** The app's status reflects only its connection to LSPosed. */
final class ActivationStatus {
    String title="未连接 LSPosed";
    String detail="请在 LSPosed 中启用模块。";
    String framework="框架：未连接";
    boolean healthy,problem;

    static ActivationStatus evaluate(boolean connected) {
        ActivationStatus result=new ActivationStatus();
        result.healthy=connected;result.problem=!connected;
        if(connected){result.title="✓ 正常运行中";result.detail="";result.framework="框架：已连接";}
        return result;
    }
    static ActivationStatus read() {
        XposedService[] services=ModuleApplication.services.toArray(new XposedService[0]);
        ActivationStatus result=evaluate(services.length>0);
        StringBuilder frameworks=new StringBuilder();
        for(XposedService service:services)try{
            String info=service.getFrameworkName()+" "+service.getFrameworkVersion()
                    +" ("+service.getFrameworkVersionCode()+"), API "+service.getApiVersion();
            if(frameworks.length()>0)frameworks.append('\n');
            frameworks.append(info);
        }catch(RuntimeException ignored){}
        if(frameworks.length()>0)result.framework="框架："+frameworks;
        return result;
    }
}
