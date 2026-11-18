# 深度链接/JS 桥不使用反射，无需额外 keep。
# org.json 随 Android 平台提供，不参与混淆。
# androidx.webkit 通过 Consumer 规则自带 keep 规则；此处仅保留崩溃可读性。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 上游 chat-demo 的构建产物在 assets 内，不参与混淆。
