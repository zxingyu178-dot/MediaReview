# MediaReview App 混淆规则(默认关闭 minify,此项仅为开启时预留)
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class **$$serializer { *; }