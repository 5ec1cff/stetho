plugins {
    id("com.android.library")
    id("maven-publish")
}

android {
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    namespace = "com.facebook.stetho"

    defaultConfig {
        minSdk = 24
    }

    publishing {
        multipleVariants {
            allVariants()
        }
        singleVariant("release") {
            withSourcesJar()
            // withJavadocJar()
        }
    }
}

dependencies {
    implementation(project(":stetho"))
    implementation("com.google.code.findbugs:jsr305:3.0.2")
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = project.properties["GROUP"] as String
                artifactId = project.properties["POM_ARTIFACT_ID"] as String
                version = project.properties["VERSION_NAME"] as String
                from(components["release"])

                pom {
                    name.set(project.properties["POM_NAME"] as String)
                    description.set("Stetho Debugging Platform for Android")
                    url.set("https://github.com/5ec1cff/stetho")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://github.com/5ec1cff/stetho/blob/master/LICENSE")
                        }
                    }
                    developers {
                        developer {
                            id.set("facebook")
                            name.set("facebook")
                        }
                        developer {
                            id.set("a13e300")
                            name.set("5ec1cff")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/5ec1cff/stetho.git")
                        developerConnection.set("scm:git:git@github.com:5ec1cff/stetho.git")
                        url.set("https://github.com/5ec1cff/stetho.git")
                    }
                }
            }
        }
        repositories {
            mavenLocal()
        }
    }
}
