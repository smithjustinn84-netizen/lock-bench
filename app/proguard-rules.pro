# Filament, gltfio, filament-utils and SceneView ship their own consumer keep rules for the
# classes their native code reaches over JNI. Koin is wired with its DSL, not reflection, and
# the .glb extras are read by key with org.json, which is part of the platform.
#
# Add a keep here only for something that is shown to break in the minified build.
