-keep class org.ejml.** { *; }
-keep class org.osmdroid.** { *; }
-dontwarn org.ejml.**
-dontwarn org.osmdroid.**

# Proj4j loads EPSG definitions from classpath resources — keep the data files and class loader hooks
-keep class org.locationtech.proj4j.** { *; }
-keepresources META-INF/services/org.locationtech.proj4j.**
-dontwarn org.locationtech.proj4j.**

# NGA TIFF library (GeoTIFF decode, Slice G)
-keep class mil.nga.tiff.** { *; }
-dontwarn mil.nga.tiff.**
