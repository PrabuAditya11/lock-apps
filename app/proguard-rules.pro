# The AccessibilityService and both Activities are referenced only from the manifest.
-keep class com.prabu.voicelock.service.AppWatchService { *; }
-keep class com.prabu.voicelock.lockscreen.LockScreenActivity { *; }
-keep class com.prabu.voicelock.ui.MainActivity { *; }
