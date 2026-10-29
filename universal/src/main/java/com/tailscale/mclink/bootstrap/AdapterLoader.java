package com.tailscale.mclink.bootstrap;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** Adapter classes stay out of loader scanners and share the outer core via their parent. */
final class AdapterLoader implements Closeable {
    private final URLClassLoader loader;
    final Object instance;
    private AdapterLoader(URLClassLoader loader, Object instance) { this.loader = loader; this.instance = instance; }
    static AdapterLoader launch(Path jar, AdapterIndex.Entry entry, ClassLoader parent) throws IOException {
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, parent);
        try {
            Class<?> type = Class.forName(entry.entrypoint, false, loader);
            if (type.getClassLoader() != loader) throw new IOException("Tailcat adapter entrypoint must be isolated from the parent classpath");
            Object instance = type.getConstructor().newInstance();
            if ("fabric".equals(entry.loader)) type.getMethod("onInitializeClient").invoke(instance);
            return new AdapterLoader(loader, instance);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            loader.close();
            Throwable cause = error instanceof InvocationTargetException ? ((InvocationTargetException) error).getCause() : error;
            throw new IOException("Cannot initialize bundled Tailcat adapter " + entry.id, cause);
        } catch (IOException error) { loader.close(); throw error; }
    }
    @Override public void close() throws IOException { loader.close(); }
}
