// 根项目配置：插件版本集中声明，子模块按需 apply。
// Kotlin DSL 重构（参照 SparePartsWarehouse）；CI 生成 gomobile AAR 后由 app 模块以 files() 引入。
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
}
