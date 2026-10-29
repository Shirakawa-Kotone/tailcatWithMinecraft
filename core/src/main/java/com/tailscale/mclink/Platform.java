package com.tailscale.mclink;

import java.util.Locale;

public final class Platform {
    private final String os;
    private final String arch;

    public Platform(String os, String arch) {
        this.os = os;
        this.arch = arch;
    }
    public String os() { return os; }
    public String arch() { return arch; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Platform)) return false;
        Platform that = (Platform) other;
        return java.util.Objects.equals(os, that.os) &&
                java.util.Objects.equals(arch, that.arch);
    }
    @Override public int hashCode() {
        int result = 0;
        result = 31 * result + java.util.Objects.hashCode(os);
        result = 31 * result + java.util.Objects.hashCode(arch);
        return result;
    }
    @Override public String toString() { return "Platform[os=" + os + ", arch=" + arch + "]"; }

    public String resourceDirectory() { return os + "-" + arch; }
    public String executableName() { return os.equals("windows") ? "mclink-helper.exe" : "mclink-helper"; }

    public static Platform current() { return parse(System.getProperty("os.name"), System.getProperty("os.arch")); }

    public static Platform parse(String osName, String archName) {
        String osValue = osName.toLowerCase(Locale.ROOT);
        String os = osValue.contains("mac") || osValue.contains("darwin") ? "darwin"
                : osValue.contains("win") ? "windows"
                : osValue.contains("linux") ? "linux" : null;
        String archValue = archName.toLowerCase(Locale.ROOT);
        String arch;
        switch (archValue) {
            case "x86_64": case "amd64": case "x64": arch = "amd64"; break;
            case "aarch64": case "arm64": arch = "arm64"; break;
            default: arch = null;
        }
        if (os == null || arch == null) throw new IllegalStateException("Unsupported platform: " + osName + "/" + archName);
        return new Platform(os, arch);
    }
}
