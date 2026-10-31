
# Keep real class names so the wildcard baseline profile (src/main/baseline-prof.txt) matches the dex.
# R8 still shrinks and optimizes; only renaming is off (costs a few KB).
-dontobfuscate
