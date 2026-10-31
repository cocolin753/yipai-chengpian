plugins {
    id("com.android.application") version "9.5.0" apply false
    // AGP 9 内置 Kotlin 支持；这里声明版本是为了让 Kotlin 编译器与 JDK 25 兼容
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
