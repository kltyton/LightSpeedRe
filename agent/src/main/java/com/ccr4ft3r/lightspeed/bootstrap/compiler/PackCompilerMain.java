package com.ccr4ft3r.lightspeed.bootstrap.compiler;

import java.io.PrintStream;

public final class PackCompilerMain {
    private PackCompilerMain() {
    }

    public static void main(String[] args) {
        ScanPackCompiler.main(args);
    }

    public static int run(String[] args, PrintStream output, PrintStream error) {
        return ScanPackCompiler.run(args, output, error);
    }
}
