# PanamaPort / AndroidVMTools are built for full R8 optimization: their R8Annotations rules keep what is needed
# (including Main via @DoNotShrinkType) and -checkdiscard what optimization must remove, so optimization stays on.
# Names are kept only for readable stack traces in logcat.
-dontobfuscate
