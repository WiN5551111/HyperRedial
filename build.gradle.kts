// 使用 buildscript + classpath 方式引入 AGP 与 Kotlin 插件。
//
// 为什么不用 `plugins { id("com.android.application") version "..." }`？
// 那种写法需要 Gradle 解析"插件标记"（plugin marker）构件，
// 在某些网络/镜像环境下会失败并报：
//   Plugin [id: 'com.android.application', version: '8.7.3'] was not found
// 而 buildscript classpath 直接解析真实构件（com.android.tools.build:gradle），
// 只依赖普通 Maven 仓库，兼容性最好。
buildscript {
    repositories {
        // 阿里云镜像优先：国内快、不需要梯子
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        // 官方源兜底
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
