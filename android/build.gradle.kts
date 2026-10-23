// Raíz: solo declara las versiones de los plugins (AGP 8.9.1 + Kotlin 2.1.21,
// ambas presentes en la caché local de Gradle; ver docs de Fase 5).
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
}
