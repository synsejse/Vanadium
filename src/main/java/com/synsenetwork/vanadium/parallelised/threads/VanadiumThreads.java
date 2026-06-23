package com.synsenetwork.vanadium.parallelised.threads;

import net.openhft.affinity.AffinityLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ThreadFactory;

public class VanadiumThreads {
    public static ThreadFactory createNamedVirtualThreadFactory(String name) {
        return Thread.ofVirtual()
                .name(name, 0)
                .factory();
    }

    public static ThreadFactory createNamedPlatformThreadFactory(String name) {
        return Thread.ofPlatform()
                .name(name, 0)
                .factory();
    }
}
