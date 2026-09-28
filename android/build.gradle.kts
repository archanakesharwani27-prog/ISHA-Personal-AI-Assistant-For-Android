allprojects {
    repositories {
        google()
        mavenCentral()
    }
}


subprojects {
    configurations.all {
        resolutionStrategy {
            force("androidx.concurrent:concurrent-futures:1.2.0")
            force("androidx.concurrent:concurrent-futures-ktx:1.2.0")
        }
    }
    plugins.withId("com.android.library") {
        dependencies {
            add("compileOnly", "androidx.concurrent:concurrent-futures:1.2.0")
            add("implementation", "androidx.concurrent:concurrent-futures:1.2.0")
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
