# Room, Kotlin serialization and OkHttp keep their own consumer rules.
-dontwarn okhttp3.**
-dontwarn okio.**

# Lab command enum is looked up by name from server JSON; keep the values.
-keepclassmembers enum com.example.expensetracker.lab.** { *; }
