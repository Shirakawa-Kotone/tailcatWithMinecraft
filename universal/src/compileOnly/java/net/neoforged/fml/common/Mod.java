package net.neoforged.fml.common;

/** Compile-only public SDK signature; excluded from distributed JARs. */
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
public @interface Mod { String value(); }
