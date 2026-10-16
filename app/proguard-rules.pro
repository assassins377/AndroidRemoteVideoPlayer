# --- Release / debugMin R8 rules ---
# Libraries with optional deps
-dontwarn org.bouncycastle.**
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# smbj / mbassador / dcerpc use reflection (event bus handlers, packet factories, ASN.1/SPNEGO)
-keep class com.hierynomus.** { *; }
-keep class com.rapid7.client.dcerpc.** { *; }
-keep class net.engio.mbassy.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
# BouncyCastle crypto used by smbj (MD4 for NTLM, AES-CMAC for SMB3 signing)
-keep class org.bouncycastle.** { *; }

# App: kotlinx.serialization models are persisted/exported as JSON — keep names stable.
-keep @kotlinx.serialization.Serializable class com.assassins377.remoteplayer.** { *; }
-keepclassmembers class com.assassins377.remoteplayer.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# Enums stored by name in DataStore
-keepclassmembers enum com.assassins377.remoteplayer.** { *; }
# MediaSessionService referenced from the manifest is kept by AAPT rules automatically.
-dontwarn java.rmi.**
