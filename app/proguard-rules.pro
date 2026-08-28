# ---- Hilt / Dagger ----
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.lifecycle.HiltViewModelFactory$ViewModelFactoriesEntryPoint { *; }
-keepclassmembers class * { @javax.inject.Inject *; @dagger.hilt.android.lifecycle.HiltViewModel *; }
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.ContinuationInterceptor
# Keep all Hilt ViewModels (their class names are looked up by HiltViewModelFactory)
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class com.x.client.app.**Hilt* { *; }
-keep class **_HiltModules { *; }
-keep class **_HiltViewModels { *; }

# ---- Coroutines ----
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ---- Compose runtime ----
-keep class androidx.compose.runtime.** { *; }

# ---- ML Kit barcode scanning (bundled model) ----
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.vision.barcode.** { *; }
-keep class com.google.android.gms.vision.barcode.internal.** { *; }
-keep class com.google.mlkit.vision.barcode.** { *; }
-keepclassmembers class com.google.mlkit.vision.barcode.** { *; }

# ---- Coil ----
-keep class coil.** { *; }

# ---- ZXing (QR 生成) ----
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ---- gomobile AAR (xclient.Xclient) ----
# Go 编译产物类名被 TProxyService 反射/直接调用，必须保留。
-keep class xclient.** { *; }
-keepclassmembers class xclient.** { *; }
-keep class com.x.client.app.TProxyService { *; }
-keepclasseswithmembernames class * { native <methods>; }

# ---- Kotlin metadata ----
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*, SourceFile, LineNumberTable, Signature, InnerClasses, EnclosingMethod, Deprecated
