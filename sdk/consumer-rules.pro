# JNI and SWIG directors look up these classes and members by name.
-keep class nz.mega.sdk.** { *; }

# The native SDK finds the file access classes, their methods and fields by name (JNI_OnLoad, androidFileSystem.cpp).
-keep class mega.privacy.android.data.filewrapper.FileWrapper { *; }
-keep class mega.privacy.android.data.filewrapper.ChildMetadata { *; }
