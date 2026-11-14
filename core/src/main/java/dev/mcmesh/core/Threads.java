package dev.mcmesh.core;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cheap named background threads. On Java 21 they are virtual threads; on Java 17 (the older Minecraft versions) they are daemon
 * platform threads. The core is compiled for Java 17, so the Java 21 calls are looked up at run time.
 */
public final class Threads {
    private static final Method OF_VIRTUAL;
    private static final Method NAME;
    private static final Method START;
    private static final Method VIRTUAL_EXECUTOR;

    static {
        Method ofVirtual = null;
        Method name = null;
        Method start = null;
        Method exec = null;
        try {
            ofVirtual = Thread.class.getMethod("ofVirtual");
            Class<?> builder = Class.forName("java.lang.Thread$Builder");
            name = builder.getMethod("name", String.class);
            start = builder.getMethod("start", Runnable.class);
            exec = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
        } catch (ReflectiveOperationException e) {
            ofVirtual = null;
            exec = null;
        }
        OF_VIRTUAL = ofVirtual;
        NAME = name;
        START = start;
        VIRTUAL_EXECUTOR = exec;
    }

    private Threads() { }

    /** Starts a named thread that does not keep the JVM alive. */
    public static Thread start(String name, Runnable body) {
        if (OF_VIRTUAL != null) {
            try {
                Object b = NAME.invoke(OF_VIRTUAL.invoke(null), name);
                return (Thread) START.invoke(b, body);
            } catch (ReflectiveOperationException e) {
                // fall through to a platform thread
            }
        }
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** One task per thread, like newVirtualThreadPerTaskExecutor on Java 21; a cached pool of daemon threads on 17. */
    public static ExecutorService perTaskExecutor() {
        if (VIRTUAL_EXECUTOR != null) {
            try {
                return (ExecutorService) VIRTUAL_EXECUTOR.invoke(null);
            } catch (ReflectiveOperationException e) {
                // fall through
            }
        }
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
    }
}
