# Keep defaults; add rules here if minify is enabled later.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**

# Optional, compile-only neighbours of libraries we do ship: commons-compress
# links its zstd/brotli compressors against these, and jsch pulls the errorprone
# annotations. They never load at runtime, so R8 only needs to stop reporting
# them as missing classes.
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn com.google.errorprone.annotations.**
