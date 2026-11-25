package io.github.isxlan0.miappxspace;

import android.os.Bundle;

/** Real Binder regression on the pinned device; the invalid package cannot mutate profiles. */
public final class ProfileRpcCompatibilityTest {
    public static void main(String[] args)throws Exception{
        Bundle status=ProfileRpc.call("spaces",null,-1);
        System.out.println("Loaded system Hook: "+status.getString("version"));
        try{ProfileRpc.call("add","__miapp_invalid_package__",-1);}
        catch(IllegalStateException expected){
            if(expected.getMessage().contains("Invalid package")){
                ProfileControl.ready();
                System.out.println("PASS: add reached backend validation instead of false reboot gate; no clone created");
                System.out.println("PASS: CLI accepts the same loaded system Hook");
                return;
            }
            System.err.println("FAIL: creation blocked before backend validation: "+expected.getMessage());
            System.exit(1);
            return;
        }
        throw new AssertionError("Backend unexpectedly accepted an invalid package");
    }
}
