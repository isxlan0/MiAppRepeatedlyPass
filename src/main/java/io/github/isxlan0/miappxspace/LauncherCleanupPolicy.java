package io.github.isxlan0.miappxspace;

/** profileId is a serial number; the Intent's profile extra is the user ID. */
final class LauncherCleanupPolicy {
    static boolean stale(int user,long savedSerial,Long currentSerial,boolean verified,boolean installed){
        if(user<900||user>999)return false;
        if(currentSerial==null)return true;
        if(!verified)return false;
        return savedSerial!=currentSerial.longValue()||!installed;
    }
}
