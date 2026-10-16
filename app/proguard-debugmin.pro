# Small debug build: shrink unused code (mostly material-icons-extended) but keep libraries that use reflection.
-dontobfuscate
-keep class com.hierynomus.** { *; }
-keep class com.rapid7.** { *; }
-keep class net.engio.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class com.assassins377.remoteplayer.** { *; }
-dontwarn **
