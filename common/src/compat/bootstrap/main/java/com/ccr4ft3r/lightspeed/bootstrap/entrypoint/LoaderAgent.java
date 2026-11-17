package com.ccr4ft3r.lightspeed.bootstrap.entrypoint;

import com.ccr4ft3r.lightspeed.bootstrap.LightspeedAgent;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;

public final class LoaderAgent {
    private LoaderAgent() { }

    public static void agentmain(String arguments, Instrumentation instrumentation) {
        String archive = System.getProperty("lightspeed.bootstrapAgent.loaderArchive");
        if (archive == null || archive.isBlank()) {
            throw new IllegalStateException("The early loader did not supply its payload archive");
        }
        LightspeedAgent.startFromLoader(instrumentation, Path.of(archive));
    }
}
