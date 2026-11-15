package app.quanwen

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** 所有设置都存在一个 SharedPreferences 里。 */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("quanwen", Context.MODE_PRIVATE)

    var lang: String
        get() = sp.getString("lang", "zh") ?: "zh"
        set(v) = sp.edit().putString("lang", v).apply()

    init {
        // 第 15 版去掉了全局的「当前科目」：它会被带进每一道题（新会话、别的笔记本也一样），限制了会话管理
        if (sp.contains("subject")) sp.edit().remove("subject").apply()
    }

    var model: String
        get() = (sp.getString("model", null) ?: "").ifBlank { DEFAULT_MODEL }
        set(v) = sp.edit().putString("model", v.trim()).apply()

    var effort: String
        get() = sp.getString("effort", "low") ?: "low"
        set(v) = sp.edit().putString("effort", v).apply()

    var baseUrl: String
        get() = (sp.getString("baseUrl", null) ?: "").ifBlank { BuildConfig.OPENAI_BASE_URL }.trimEnd('/')
        set(v) = sp.edit().putString("baseUrl", v.trim()).apply()

    /**
     * OpenAI 密钥存在本机。发到 GitHub 的安装包里没有密钥：
     * 手动安装的那一版带着密钥，第一次运行就存到这里（[persistBuiltInKey]），之后的更新包接着用。
     */
    var apiKey: String
        get() = (sp.getString("apiKey", "") ?: "").ifBlank { BuildConfig.OPENAI_API_KEY }
        set(v) = sp.edit().putString("apiKey", v.trim()).apply()

    /** App 启动时调用：把安装包里带的密钥存到本机。 */
    fun persistBuiltInKey() {
        if ((sp.getString("apiKey", "") ?: "").isBlank() && BuildConfig.OPENAI_API_KEY.isNotBlank()) {
            sp.edit().putString("apiKey", BuildConfig.OPENAI_API_KEY).commit()
        }
    }

    /** 悬浮条位置，按比例存（0..1），旋转屏幕后保持在同一侧。默认贴右边、偏上。 */
    var barFx: Float
        get() = sp.getFloat("barFx", 1f)
        set(v) = sp.edit().putFloat("barFx", v.coerceIn(0f, 1f)).apply()

    var barFy: Float
        get() = sp.getFloat("barFy", 0.4f)
        set(v) = sp.edit().putFloat("barFy", v.coerceIn(0f, 1f)).apply()

    /** 用过一次触控笔之后，圈选时就只认笔，避免手掌误触。 */
    var stylusSeen: Boolean
        get() = sp.getBoolean("stylusSeen", false)
        set(v) = sp.edit().putBoolean("stylusSeen", v).apply()

    /** 当前会话。新圈的题都放进它，AI 也只参考它里面的问答。会话由用户手动新建、切换。 */
    var currentSessionId: String
        get() = sp.getString("currentSessionId", "") ?: ""
        set(v) = sp.edit().putString("currentSessionId", v).apply()

    /** 用户开着悬浮窗方式（[FloatService]）。打开 App 时如果它没在运行，就自动重新开起来。 */
    var floatMode: Boolean
        get() = sp.getBoolean("floatMode", false)
        set(v) = sp.edit().putBoolean("floatMode", v).apply()

    /** 点过「不再提示」的提示（笔记本里的圈、移动图片、留下）。 */
    fun tipOff(key: String): Boolean = sp.getStringSet("tipsOff", emptySet())?.contains(key) == true

    fun turnTipOff(key: String) {
        val s = HashSet(sp.getStringSet("tipsOff", emptySet()) ?: emptySet())
        s.add(key)
        sp.edit().putStringSet("tipsOff", s).apply()
    }

    /** 模式记号（[Marks]）的 JSON；null 表示没改过，用默认的 gg、j。 */
    var marksJson: String?
        get() = sp.getString("marks", null)
        set(v) = sp.edit().apply { if (v == null) remove("marks") else putString("marks", v) }.apply()

    /** 测试功能「一同书写」：笔记本里写问号就问 AI，AI 把回答写在纸上。 */
    var writeTogether: Boolean
        get() = sp.getBoolean("writeTogether", false)
        set(v) = sp.edit().putBoolean("writeTogether", v).apply()

    /** 「一同书写」里 AI 的字迹：[AiInk.STANDARD] 标准体，[AiInk.SONG] 宋体。 */
    var aiHand: Int
        get() = sp.getInt("aiHand", AiInk.SONG).let { if (it == AiInk.LEGACY_HAND) AiInk.SONG else it }   // 第 17 版存的是龙藏体
        set(v) = sp.edit().putInt("aiHand", v).apply()

    /** 笔记本里橡皮擦的大小档位（0..2）。 */
    var noteEraser: Int
        get() = sp.getInt("noteEraser", 1)
        set(v) = sp.edit().putInt("noteEraser", v).apply()

    /** 笔记本里上次用的笔：颜色和粗细的档位（0..2）。 */
    var noteColor: Int
        get() = sp.getInt("noteColor", 0)
        set(v) = sp.edit().putInt("noteColor", v).apply()

    var noteWidth: Int
        get() = sp.getInt("noteWidth", 1)
        set(v) = sp.edit().putInt("noteWidth", v).apply()

    /** 这台设备用哪种方式截屏（[ScreenGrabber.Mode] 的名字），试出来以后记住。 */
    var captureMode: String
        get() = sp.getString("captureMode", "") ?: ""
        set(v) = sp.edit().putString("captureMode", v).apply()

    companion object {
        const val DEFAULT_MODEL = "gpt-6.1-terra"
    }
}

object Lang {
    fun locale(lang: String): Locale = if (lang == "en") Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE

    /** 按 App 内设置的语言包一层 Context，用于取字符串。 */
    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale(Prefs(base).lang))
        return base.createConfigurationContext(config)
    }
}
