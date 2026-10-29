import com.tailscale.mclink.NativeHelper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/** Run against the final JAR, on Java 8 as well as the current runtime. */
public final class NativeBundleSmoke {
    public static void main(String[] args) throws Exception {
        Class.forName("com.tailscale.mclink.Compatibility");
        Class.forName("com.tailscale.mclink.HostAuthentication");
        Path game = Paths.get(args[0]);
        String version = args.length > 1 ? args[1] : "0.3.1";
        long start = System.nanoTime();
        Path helper = NativeHelper.extract(game, version, false);
        System.out.println("Extracted " + Files.size(helper) + " bytes in " + (System.nanoTime() - start) / 1_000_000 + " ms");
        if (!helper.equals(NativeHelper.extract(game, version, false))) throw new AssertionError("Cache path changed");
        Process child = new ProcessBuilder(helper.toString(), "version").redirectErrorStream(true).start();
        String line;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), "UTF-8"))) {
            line = reader.readLine();
        }
        if (!child.waitFor(10, TimeUnit.SECONDS) || child.exitValue() != 0 || line == null || !line.contains("protocol"))
            throw new AssertionError("Bundled helper version failed: " + line);
        System.out.println("PASS " + line);
    }
}
