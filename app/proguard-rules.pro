# jaudiotagger instantiates frame bodies by reflection and references desktop-only classes.
# Its bundled command-line test programs (org.jaudiotagger.Test, Test2, test.*) are left out so R8 strips them.
-keep class !org.jaudiotagger.test.**,!org.jaudiotagger.Test,!org.jaudiotagger.Test2,org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
