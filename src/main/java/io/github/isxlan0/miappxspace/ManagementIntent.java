package io.github.isxlan0.miappxspace;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;

/** The settings process runs as owner; the managed application keeps its real user and UID. */
final class ManagementIntent {
    static final String USER="miui.intent.extra.USER_ID", ARGS=":settings:show_fragment_args";
    static final String DETAILS="android.settings.APPLICATION_DETAILS_SETTINGS";
    static final String NOTIFICATIONS="android.settings.APP_NOTIFICATION_SETTINGS";

    static boolean caller(String pkg){
        return "com.miui.home".equals(pkg)||"com.miui.securitycenter".equals(pkg)
            ||"com.android.settings".equals(pkg)||"com.android.systemui".equals(pkg);
    }

    static boolean supported(Intent intent){
        if(intent==null)return false;
        ComponentName component=intent.getComponent();
        String host=component!=null?component.getPackageName():intent.getPackage();
        if(host!=null&&!host.equals("com.android.settings")&&!host.equals("com.miui.securitycenter"))return false;
        String action=intent.getAction();
        if(DETAILS.equals(action)||NOTIFICATIONS.equals(action)
            ||"android.settings.CHANNEL_NOTIFICATION_SETTINGS".equals(action)
            ||"miui.intent.action.APP_MANAGER_APPLICATION_DETAIL".equals(action)
            ||"miui.intent.action.APP_PERM_EDITOR".equals(action)
            ||"miui.intent.action.APP_PERM_EDITOR_PRIVATE".equals(action))return true;
        if(component==null)return false;
        String name=component.getClassName();
        return name.equals("com.android.settings.Settings$NotificationFilterActivity")
            ||name.equals("com.android.settings.Settings$AppNotificationSettingsActivity")
            ||name.equals("com.miui.appmanager.ApplicationsDetailsActivity")
            ||name.equals("com.miui.permcenter.permissions.PermissionsEditorActivity")
            ||name.equals("com.miui.permcenter.permissions.OtherPermissionsActivity");
    }

    static String packageName(Intent intent){
        if(DETAILS.equals(intent.getAction())&&intent.getData()!=null&&"package".equals(intent.getData().getScheme()))
            return intent.getData().getSchemeSpecificPart();
        for(String key:new String[]{"package_name","extra_pkgname","android.provider.extra.APP_PACKAGE","packageName"}){
            String pkg=intent.getStringExtra(key);if(pkg!=null&&!pkg.isEmpty())return pkg;
        }
        Bundle args=intent.getBundleExtra(ARGS);
        if(args!=null)for(String key:new String[]{"package","packageName"}){
            String pkg=args.getString(key);if(pkg!=null&&!pkg.isEmpty())return pkg;
        }
        return null;
    }

    static int user(Intent intent,int launchUser){
        // LauncherApps supplies the correct launch user but an owner uId on this ROM.
        if(launchUser!=0)return launchUser;
        if(intent.hasExtra(USER))return intent.getIntExtra(USER,-1);
        if(intent.hasExtra("userId"))return intent.getIntExtra("userId",-1);
        Object handle=intent.getParcelableExtra("android.intent.extra.USER");
        if(handle instanceof UserHandle)return ((UserHandle)handle).hashCode();
        Bundle args=intent.getBundleExtra(ARGS);
        if(args!=null&&args.containsKey("userId"))return args.getInt("userId",-1);
        if(intent.getBooleanExtra("is_xspace_app",false))return CloneSlots.STOCK;
        if(args!=null&&args.containsKey("uid"))return userOfUid(args.getInt("uid",-1));
        for(String key:new String[]{"app_uid","android.provider.extra.APP_UID","uId"})
            if(intent.hasExtra(key))return userOfUid(intent.getIntExtra(key,-1));
        return 0;
    }

    static int userOfUid(int uid){return uid<0?-1:uid/100000;}

    static Intent withIdentity(Intent original,int user,int uid){
        if(userOfUid(uid)!=user)throw new IllegalArgumentException("Application UID does not belong to requested user");
        Intent result=new Intent(original);
        result.putExtra(USER,user).putExtra("userId",user).putExtra("uId",uid)
            .putExtra("app_uid",uid).putExtra("android.provider.extra.APP_UID",uid)
            .putExtra("android.intent.extra.UID",uid).putExtra("android.intent.extra.USER",UserHandle.getUserHandleForUid(uid));
        // A boolean XSpace flag cannot distinguish 999, 998 and 997. Use explicit identity.
        result.removeExtra("is_xspace_app");
        result.removeExtra("android.intent.extra.xspace_cached_uid");
        Bundle args=original.getBundleExtra(ARGS);
        if(args!=null){
            args=new Bundle(args);args.putInt("uid",uid);args.putInt("userId",user);args.putInt(USER,user);
            result.putExtra(ARGS,args);
        }
        return result;
    }
}
