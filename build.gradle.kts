// 顶层构建文件：只声明插件版本，不引入业务模块
plugins {
    id("com.android.application") version "8.1.4" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
