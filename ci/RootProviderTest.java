package io.github.isxlan0.miappxspace;

import android.os.Bundle;

/** Reads an existing global setting, proving the app_process external-provider path. */
public final class RootProviderTest {
    public static void main(String[] args)throws Exception {
        Bundle result=RootProvider.call("settings","GET_global","device_provisioned",null);
        if(result==null||!"1".equals(result.getString("value")))throw new AssertionError("Cannot read settings through external provider: "+result);
        System.out.println("PASS: root app_process external Provider call and handle release (read-only)");
        System.exit(0);
    }
}
