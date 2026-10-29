package net.minecraftforge.fml.loading;

/** Test-only public path API retained by early NeoForge. */
public enum FMLPaths {
    GAMEDIR;
    public java.nio.file.Path get() {
        return java.nio.file.Paths.get(System.getProperty("tailcat.fixture.gameDir", System.getProperty("user.dir")));
    }
}
