buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.8.0")
    }
    extra.apply {
        set("compileSdkVersion", 35)
        set("targetSdkVersion", 35)
        set("aaa", 11)
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}
