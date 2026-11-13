package app.resound.skills

/**
 * 读出任务卡时的装饰钩子。
 * 安卓版这里直接调用 `EnvSoundPresentation.decorate`，而它依赖运行时的 `AppGraph`，数据层不应反向依赖业务层。
 * 默认直通；波次 5 迁移 `envsound` 时由装配代码把 `EnvSoundPresentation::decorate` 装进来。
 */
object SkillCardDecorator {
    @Volatile var hook: (SkillCard) -> SkillCard = { it }
    fun decorate(card: SkillCard): SkillCard = hook(card)
}