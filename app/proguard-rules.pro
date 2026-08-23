# Keep generic signatures for libraries that rely on reflection.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# Hilt generated components
# Hilt/Dagger generated component graph.
#
# Deliberately kept, though Hilt ships its own consumer rules and nothing in
# this app reflects over `dagger.hilt`. Dropping it saves ~50 KB but changes
# what R8 full mode strips: `…SingletonC$ActivityRetainedCBuilder` goes from
# member-pruned to fully removed, and `ActivityCImpl`/`ActivityRetainedCImpl`
# lose members. Those are on the live injection path (MainActivity is
# @AndroidEntryPoint, the ViewModels are @HiltViewModel), and a wrong guess
# there is a release-only crash at Activity creation, not a build failure.
# Not worth 50 KB without an on-device check.
-keep class dagger.hilt.** { *; }

# Optional Markwon image-plugin transitive deps we don't pull in.
# We use ImagesPlugin with only the OkHttp scheme handler — no SVG, no GIF —
# so these classes are referenced by Markwon but never reachable at runtime.
-dontwarn com.caverock.androidsvg.SVG
-dontwarn com.caverock.androidsvg.SVGParseException
-dontwarn pl.droidsonroids.gif.GifDrawable

# Tink references errorprone annotations at compile time only; they're not on
# the runtime classpath.
-dontwarn com.google.errorprone.annotations.**

# Nextcloud Single Sign-On.
#
# Deliberately narrow. The library's own README says `-dontobfuscate`, and
# keeping the whole `com.nextcloud.android.sso.**` package would work — but it
# would also pin `NextcloudAPI`'s RxJava-returning methods, and the library
# pulls in *both* RxJava 2 and RxJava 3 as transitive deps. This app never
# touches those methods (it calls `performNetworkRequestV2` directly), so
# leaving them shrinkable is what keeps both Rx copies out of the APK.
#
# What must survive obfuscation is anything Java serialisation resolves by
# fully-qualified class name, for two different reasons:
#
#  1. Cross-process. `NextcloudRequest` is written with `ObjectOutputStream`
#     and handed to the *Nextcloud Files app*, which deserialises it against
#     its own copy of the class. A renamed class breaks the AIDL channel at
#     runtime — silently, and only in release builds. The pinned
#     `serialVersionUID` doesn't help once the name has changed. The AIDL
#     interface itself lives in the same package.
#  2. Cross-upgrade. `SingleSignOnAccount` is serialised into the library's
#     own SharedPreferences and read back on later launches. If R8 picks a
#     different name in the next release, every SSO session on the device
#     stops resolving after an in-place update.
-keep class com.nextcloud.android.sso.aidl.** { *; }
-keep class com.nextcloud.android.sso.model.** { *; }
-keep class com.nextcloud.android.sso.QueryParam { *; }
-keep class com.nextcloud.android.sso.api.AidlNetworkRequest$PlainHeader { *; }

# RxJava reaches the classpath only through the SSO library's unused reactive
# entry points; R8 removes it, so silence the references left behind.
-dontwarn io.reactivex.**
