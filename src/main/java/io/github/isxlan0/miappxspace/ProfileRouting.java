package io.github.isxlan0.miappxspace;

import android.content.*;
import android.content.pm.ActivityInfo;
import android.os.Binder;
import de.robv.android.xposed.*;
import java.util.*;

final class ProfileRouting {
    static boolean listed(Class<?> c,String field,String value){
        Object set=XposedHelpers.getStaticObjectField(c,field);return set instanceof Collection&&((Collection<?>)set).contains(value);
    }
    static void check(XC_MethodHook.MethodHookParam m)throws Throwable{
        Intent intent=(Intent)m.args[2];ActivityInfo info=(ActivityInfo)m.args[1];
        int target=(Integer)m.args[5],caller=(Integer)m.args[7];String calling=(String)m.args[6];
        boolean targetExtra=ProfileHook.extra(target),callerExtra=ProfileHook.extra(caller);
        if(caller!=0&&caller!=999&&!callerExtra)return;
        if(intent==null)return;
        String pkg=info!=null?info.packageName:intent.getComponent()!=null?intent.getComponent().getPackageName():intent.getPackage();
        if(pkg==null||pkg.equals(calling)||!Boolean.TRUE.equals(Reflect.call(m.thisObject,"isPublicIntent",intent,calling,info))){if(targetExtra||callerExtra)m.setResult(intent);return;}
        long token=Binder.clearCallingIdentity();int[] ids;
        try{
            if(ProfileBackend.pm==null)ProfileBackend.pm=ProfileBackend.service("package","android.content.pm.IPackageManager");
            if(ProfileBackend.ums==null)ProfileBackend.ums=ProfileBackend.service("user","android.os.IUserManager");
            ids=ProfileBackend.instances(pkg,CloneRegistry.read());
        }finally{Binder.restoreCallingIdentity(token);}
        boolean hasExtra=false;for(int id:ids)if(ProfileHook.extra(id))hasExtra=true;
        if(!hasExtra&&!callerExtra&&!targetExtra)return;
        if(intent.hasExtra("android.intent.extra.xspace_userid_selected")){
            if(targetExtra||callerExtra){
                intent.removeExtra("android.intent.extra.xspace_resolve_intent_again");
                Class<?> compat=XposedHelpers.findClass("miui.securityspace.XSpaceIntentCompat",m.thisObject.getClass().getClassLoader());
                XposedHelpers.callStaticMethod(compat,"prepareToLeaveUser",intent,caller);
                Reflect.call(m.thisObject,"putCachedCallingRelation",pkg,calling,caller);m.setResult(intent);
            }
            return;
        }
        if((intent.getFlags()&1048576)!=0||Intent.ACTION_MAIN.equals(intent.getAction())&&intent.hasCategory(Intent.CATEGORY_LAUNCHER)){
            if(targetExtra||callerExtra)m.setResult(intent);return;
        }
        Class<?> manager=m.thisObject.getClass();String action=intent.getAction();
        if((listed(manager,"sCrossUserCallingPackagesWhiteList",calling)&&!listed(manager,"sPublicActionList",action))||listed(manager,"sCrossUserAimPackagesWhiteList",pkg)){
            if(targetExtra||callerExtra)m.setResult(intent);return;
        }
        int cached=(Integer)Reflect.call(m.thisObject,"getToUserIdFromCachedCallingRelation",pkg,calling);
        if(cached!=-10000){intent.putExtra("android.intent.extra.xspace_cached_uid",cached);m.setResult(intent);return;}
        if(ids.length>1&&hasExtra){
            boolean auth=intent.hasExtra("android.intent.extra.auth_to_call_xspace")&&Boolean.TRUE.equals(Reflect.call(m.thisObject,"checkCallXSpacePermission",calling));
            if(auth||"com.eg.android.AlipayGphone".equals(pkg)||"com.xiaomi.gamecenter.sdk.service".equals(pkg)||"com.xiaomi.xmsf".equals(calling)||"com.sina.weibo.sdk.action.ACTION_SDK_REQ_ACTIVITY".equals(action)||(pkg+".wxapi.WXEntryActivity").equals(intent.getComponent()==null?"":intent.getComponent().getClassName())||Boolean.TRUE.equals(Reflect.call(m.thisObject,"isHideApp",pkg))){m.setResult(intent);return;}
            Intent resolver=(Intent)Reflect.call(m.thisObject,"getResolverActivity",intent,pkg,(Integer)m.args[4],calling);
            resolver.putExtra("io.github.isxlan0.miappxspace.source_user",caller);
            resolver.putExtra("android.intent.extra.xspace_cached_uid",0);
            android.util.Log.i(ProfileHook.TAG,"Native resolver for "+pkg+" instances="+Arrays.toString(ids)+" from="+caller);
            m.setResult(resolver);return;
        }
        if(callerExtra&&ids.length==1&&ids[0]==0){
            Class<?> compat=XposedHelpers.findClass("miui.securityspace.XSpaceIntentCompat",manager.getClassLoader());
            XposedHelpers.callStaticMethod(compat,"prepareToLeaveUser",intent,caller);
            intent.putExtra("android.intent.extra.xspace_cached_uid",0);intent.putExtra("calling_relation",true);m.setResult(intent);
        }else if(targetExtra||callerExtra)m.setResult(intent);
    }
}
