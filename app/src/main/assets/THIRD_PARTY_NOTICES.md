# Лицензии сторонних компонентов

Собственный код SplitBox распространяется под GPL-3.0-only (см. LICENSE). Библиотеки и инструменты сборки сохраняют свои лицензии; SplitBox не заявляет авторство этих компонентов.

| Компонент | Лицензия | Исходный проект |
| --- | --- | --- |
| Стандартная библиотека и компилятор Kotlin | Apache-2.0 | https://github.com/JetBrains/kotlin |
| Аннотации JetBrains | Apache-2.0 | https://github.com/JetBrains/java-annotations |
| kotlinx.serialization, транзитивная зависимость | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| Guava ListenableFuture, транзитивная зависимость | Apache-2.0 | https://github.com/google/guava |
| kotlinx.coroutines | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| AndroidX Activity, Core, Lifecycle, SavedState, ProfileInstaller, Graphics Path, Compose UI/Foundation/Material 3 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Иконки Compose Material | Apache-2.0 | https://fonts.google.com/icons |
| Shizuku API и provider 13.1.5 | Apache-2.0 | https://github.com/RikkaApps/Shizuku-API |
| Android apksig | Apache-2.0 | https://android.googlesource.com/platform/tools/apksig/ |
| JUnit 4, только тесты | EPL-1.0 | https://github.com/junit-team/junit4 |
| Android Gradle Plugin / SDK build tools, только сборка | Компоненты Apache-2.0; действуют условия загрузки Android SDK | https://android.googlesource.com/ |
| Gradle, только сборка | Apache-2.0 | https://github.com/gradle/gradle |
| Eclipse Temurin OpenJDK, только сборка | GPL-2.0 с Classpath Exception | https://adoptium.net/ |
| bundletool, только создание тестовых пакетов | Apache-2.0 | https://github.com/google/bundletool |

В приложение не включены Google Play Services, Firebase, рекламные и аналитические SDK, закрытые установщики или сервер Shizuku. Shizuku — отдельный устанавливаемый инструмент с открытым исходным кодом. Разбор ZIP, Android binary XML и protobuf в core — собственный код SplitBox, основанный на открытых спецификациях Android и bundletool.

Прямые зависимости и версии указаны в `app/build.gradle.kts` и `core/build.gradle.kts`. Текущее дерево зависимостей получается командой `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`. Транзитивные зависимости сохраняют собственные лицензии; сведения о них доступны в этом дереве и в исходных проектах библиотек. Канонические тексты Apache-2.0 и GPL-3.0 включены в приложение для чтения без интернета.
