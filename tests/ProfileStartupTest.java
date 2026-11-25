package io.github.isxlan0.miappxspace;

import java.util.Properties;

/** Readiness contract plus real Binder checks on existing, owned profiles; no data removal. */
public final class ProfileStartupTest {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static final class StartupState {
        boolean accepted=true,unlocked,visible;
        int startCalls,unlockChecks,visibilityChecks;
        int unlockAfter=1,visibleAfter=1;
        public boolean startProfile(int id){startCalls++;return accepted;}
        public boolean isUserUnlocked(int id){unlockChecks++;return unlocked=unlockChecks>=unlockAfter;}
        public boolean isUserVisible(int id){visibilityChecks++;return visible=visibilityChecks>=visibleAfter;}
    }
    static void readinessContract()throws Exception{
        StartupState delayedVisibility=new StartupState();delayedVisibility.visibleAfter=4;
        ProfileStartup.boot(delayedVisibility,delayedVisibility,998);
        check(delayedVisibility.unlocked&&delayedVisibility.visible,"Returned while unlocked but invisible");

        StartupState delayedUnlock=new StartupState();delayedUnlock.unlockAfter=4;
        ProfileStartup.boot(delayedUnlock,delayedUnlock,997);
        check(delayedUnlock.unlocked&&delayedUnlock.visible,"Returned before unlock");

        StartupState rejected=new StartupState();rejected.accepted=false;
        try{ProfileStartup.boot(rejected,rejected,998);throw new AssertionError("Rejected profile accepted");}
        catch(IllegalStateException expected){check(rejected.unlockChecks==0,"Polled after start was refused");}

        StartupState invalid=new StartupState();
        for(int id:new int[]{0,10,899,1000}){
            try{ProfileStartup.boot(invalid,invalid,id);throw new AssertionError("Invalid profile accepted: "+id);}
            catch(IllegalArgumentException expected){}
        }
        check(invalid.startCalls==0,"Started a user outside the clone range");

        StartupState invisible=new StartupState();invisible.visibleAfter=Integer.MAX_VALUE;
        try{ProfileStartup.boot(invisible,invisible,998);throw new AssertionError("Invisible profile accepted");}
        catch(IllegalStateException expected){
            check(invisible.unlocked&&!invisible.visible,"Timeout did not exercise unlocked/invisible state");
            check(expected.getMessage().contains("998"),"Timeout lost the affected profile ID");
        }
        System.out.println("PASS: delayed unlock/visibility, refusal, invalid IDs, and invisible-profile timeout");
    }
    public static void main(String[] args)throws Exception{
        try{
            readinessContract();
            Properties registry=CloneRegistry.read();
            for(int id:new int[]{998,997}){
                ProfileControl.verify(id,registry,true);
                // A background start cannot recreate the bug once this user is already visible.
                // Observe existing state; exercise that regression deterministically above.
                System.out.println("Before user="+id+" unlocked="+Reflect.call(ProfileControl.users,"isUserUnlocked",id)+
                                   " visible="+Reflect.call(ProfileControl.users,"isUserVisible",id));
                ProfileControl.boot(id);
                check(Boolean.TRUE.equals(Reflect.call(ProfileControl.users,"isUserUnlocked",id)),"Profile locked");
                check(Boolean.TRUE.equals(Reflect.call(ProfileControl.users,"isUserVisible",id)),"Profile invisible");
            }
            check(Boolean.TRUE.equals(Reflect.call(ProfileControl.users,"isUserVisible",998)),"Starting 997 hid 998");
            System.out.println("PASS: existing owned 998/997 unlocked and visible together; CLI uses repaired startup");
        }catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
