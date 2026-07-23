# Minify disabled in P1.1; rules added later if/when enabled.

# When minify is enabled later, eAlvaTag needs keep rules for ealvatag.tag.** frame
# classes (reflection). Add: -keep class ealvatag.** { *; }  and verify against the
# artifact's own consumer rules before shipping a minified build.
