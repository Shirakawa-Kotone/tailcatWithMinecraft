package net.minecraftforge.versions.forge;

/** Test-only SDK signature: NeoForge 47.1.106 retains this old package and MOD_ID. */
public final class ForgeVersion {
    public static final String MOD_ID = "forge";
    public static String getGroup() { return System.getProperty("tailcat.fixture.forgeGroup", "net.minecraftforge"); }
}
