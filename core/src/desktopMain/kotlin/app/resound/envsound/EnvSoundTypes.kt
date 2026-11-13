package app.resound.envsound

/*
 * TODO(迁移 envsound 包时并回 EnvSoundContract.kt / EnvSoundAggregation.kt)：
 * context.LocalSituationLookup 需要这些类型，envsound 包尚未迁移（定义与安卓版一致）。
 */

/** 已验证的 9 类环境标签（title 即给用户看的写法） */
enum class EnvTag(val title: String) {
    Music("音乐"), Keyboard("键盘声"), Rain("雨"), Pet("宠物"), Water("流水"),
    Appliance("家电"), Footsteps("脚步"), AlarmClock("闹钟"), Traffic("附近有车流"),
}

/** 对话中出现的特别声音：只记录、不提醒 */
enum class SpecialSound(val title: String) { BabyCry("婴儿哭声"), Knock("敲门"), DogBark("狗叫"), Siren("警笛") }

internal inline fun <reified T : Enum<T>> names(value: String): Set<T> =
    value.split(',').mapNotNull { name -> enumValues<T>().firstOrNull { it.name == name } }.toSet()
