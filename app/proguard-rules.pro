# kotlinx.serialization ships its own consumer rules, which keep the generated
# serializers of every @Serializable class. Nothing to add for it — but the
# release build is checked against the API fixtures in A8, because "it decoded
# in debug" is the classic way an Android release breaks.

# OkHttp's optional platforms (Conscrypt, BouncyCastle, OpenJSSE) are not here.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
