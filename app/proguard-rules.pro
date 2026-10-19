# ============ 保留必要信息 ============
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# ============ 数据模型 ============
# 这些 data class 由 org.json 手工填充, R8 若判定某些属性「未被读取」会删掉
# 对应字段的初始化逻辑, 导致解析结果为空 (收藏歌单/歌单详情取不到内容)。
-keep class com.portable.qqmusic.data.api.** { *; }
-keep class com.portable.qqmusic.data.** { *; }
-keep class com.portable.qqmusic.core.** { *; }

# 枚举的 values()/valueOf() 与 entries
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public static **[] getEntries();
}

# ============ 第三方 ============
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class com.bumptech.glide.** { *; }
-keep class com.google.zxing.** { *; }
-keep class androidx.media.** { *; }

# ============ 移除日志 ============
# 注意: 只移除 android.util.Log。应用自己的 core.Diag 必须保留 ——
# 部分 ROM (Flyme) 关闭了 logcat, Diag 是唯一能看到启动/接口问题的通道。
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
