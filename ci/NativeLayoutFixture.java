package io.github.isxlan0.miappxspace;
import java.io.File;
import java.nio.file.*;
import java.util.Properties;

/** Independent Java recognizer supplies expected addresses for native parity tests. */
public final class NativeLayoutFixture {
    public static void main(String[] args)throws Exception{
        Files.write(Paths.get(args[2]),NativePrepare.scan(new File(args[0]),new File(args[1]),new Properties()));
    }
}
