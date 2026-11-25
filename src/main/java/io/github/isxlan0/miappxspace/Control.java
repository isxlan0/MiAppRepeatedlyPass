package io.github.isxlan0.miappxspace;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Process execution and current system Hook readiness for the native CLONE backend. */
final class Control {
    static String run(String... argv) throws Exception {
        java.lang.Process child = new ProcessBuilder(argv).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = child.getInputStream()) { byte[] b = new byte[8192]; int n; while ((n=in.read(b))!=-1) output.write(b,0,n); }
            catch (IOException ignored) { }
        });
        reader.start();
        if (!child.waitFor(180, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new IOException("Command timed out: " + argv[0]); }
        reader.join();
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        if (child.exitValue() != 0) throw new IOException(Arrays.toString(argv) + ": " + text);
        return text;
    }

    static boolean ready() throws Exception {
        File marker = new File(Registry.DIR,"hook.status");
        if (!marker.isFile()) return false;
        Properties p = new Properties();
        try(FileInputStream in=new FileInputStream(marker)){p.load(in);}
        String pid = p.getProperty("pid", "");
        return pid.matches("[0-9]+") && Arrays.asList(run("/system/bin/pidof","system_server").split(" ")).contains(pid);
    }
}
