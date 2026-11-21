# Переход на Compose Multiplatform для поддержки ПК

Этот план описывает шаги по созданию десктопного модуля и рефакторингу проекта для совместного использования кода между Android и ПК.

## User Review Required

> [!IMPORTANT]
> Мы будем использовать **Compose Multiplatform** от JetBrains. Это потребует реструктуризации проекта: создание общего модуля (`shared` или `composeApp`), куда переедет большая часть кода из текущего модуля `:app`.

## Proposed Changes

### Настройка зависимостей

#### [MODIFY] [libs.versions.toml](file:///home/user/and/AiMusic/gradle/libs.versions.toml)
Добавление плагинов для Compose Multiplatform и Kotlin Multiplatform.

#### [MODIFY] [build.gradle.kts](file:///home/user/and/AiMusic/build.gradle.kts) (root)
Подключение новых плагинов на уровне проекта.

### Реструктуризация проекта

#### [NEW] `composeApp` (директория)
Создание нового модуля, который будет содержать:
- `commonMain`: Общий UI и бизнес-логика.
- `androidMain`: Специфичный для Android код (интеграция с Media3).
- `desktopMain`: Точка входа для ПК (JVM).

#### [MODIFY] [settings.gradle.kts](file:///home/user/and/AiMusic/settings.gradle.kts)
Включение нового модуля `:composeApp`.

### Перенос кода

1.  **Модели и ViewModel:** Перенос `Song`, `Folder`, `Playlist` и базовой логики `MusicViewModel` в `commonMain`.
2.  **UI:** Перенос всех Composable экранов в `commonMain`.
3.  **Адаптация:** Использование `expect/actual` для функционала, который отличается (плеер, файловый сканер).

## Verification Plan

### Automated Tests
- Сборка проекта: `./gradlew assemble`
- Проверка запуска десктопной версии: `./gradlew :composeApp:run`

### Manual Verification
- Запуск приложения на Android для проверки регрессий.
- Запуск на ПК для проверки отображения интерфейса.
