package app.quanwen

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** 接口格式：OpenAI Responses、OpenAI 兼容的 Chat Completions（大多数厂商）、Anthropic Messages。 */
enum class Protocol { RESPONSES, CHAT, ANTHROPIC }

/** 「推理强度」在各家接口里的写法。 */
enum class EffortStyle {
    NONE,
    /** OpenAI Responses：reasoning.effort */
    RESPONSES,
    /** Chat 接口的 reasoning_effort（DeepSeek、Gemini） */
    REASONING_EFFORT,
    /** thinking.type = enabled / disabled（智谱、豆包） */
    THINKING_TYPE,
    /** enable_thinking = true / false（通义千问） */
    ENABLE_THINKING,
}

/** 一家 AI 服务。内置的几家写在 [Providers.BUILT_IN]；用户自己加的接口是 custom，存在 Prefs 里。 */
class Provider(
    val id: String,
    val nameZh: String,
    val nameEn: String,
    val protocol: Protocol,
    val baseUrl: String,
    val defaultModel: String,
    /** 推荐的模型（都能看图），拉不到模型列表时也能从这里选。 */
    val models: List<String>,
    /** 去哪里申请密钥。 */
    val keyUrl: String,
    /** 图标：底色（渐变的两端）和上面的字或图形（见 [ProviderIcon]）。 */
    val color: Int,
    val color2: Int,
    val glyph: String,
    val effortStyle: EffortStyle = EffortStyle.NONE,
    /** 推理强度的档位；ENABLE_THINKING / THINKING_TYPE 用 off、on。 */
    val efforts: List<String> = emptyList(),
    val defaultEffort: String = "",
    /** 图片带 detail=high（OpenAI 一家的写法；别家不认这个字段时可能报错，所以默认不带）。 */
    val imageDetail: Boolean = false,
    /** 一句说明：哪个模型能看图、国际站地址等。 */
    val noteZh: String = "",
    val noteEn: String = "",
    val custom: Boolean = false,
) {
    fun name(lang: String) = if (lang == "en") nameEn else nameZh
    fun note(lang: String) = if (lang == "en") noteEn else noteZh
}

/** 某一家此刻的设置：地址、密钥、模型、推理强度。 */
class AiConfig(val provider: Provider, val baseUrl: String, val key: String, val model: String, val effort: String)

object Providers {
    const val OPENAI = "openai"
    const val CUSTOM_PREFIX = "custom_"

    private fun c(v: Long) = v.toInt()

    val BUILT_IN: List<Provider> = listOf(
        Provider(
            OPENAI, "OpenAI", "OpenAI", Protocol.RESPONSES, BuildConfig.OPENAI_BASE_URL, Prefs.DEFAULT_MODEL,
            listOf(Prefs.DEFAULT_MODEL, "gpt-6-sol", "gpt-5.4-mini"),
            "https://platform.openai.com/api-keys", c(0xFF1A1A1A), c(0xFF3A3A3A), "GPT",
            EffortStyle.RESPONSES, listOf("low", "medium", "high"), "low", imageDetail = true,
            noteZh = "语音追问的转文字也用 OpenAI 的密钥。",
            noteEn = "Voice follow-ups are transcribed with the OpenAI key.",
        ),
        Provider(
            "deepseek", "DeepSeek", "DeepSeek", Protocol.CHAT, "https://api.deepseek.com", "deepseek-flash",
            listOf("deepseek-flash"),
            "https://platform.deepseek.com/api_keys", c(0xFF4D6BFE), c(0xFF2F4BE0), "DS",
            EffortStyle.REASONING_EFFORT, listOf("low", "high", "max"), "low", imageDetail = true,
            noteZh = "deepseek-flash 能看图；deepseek-v4-pro 只认文字，圈问用不了。",
            noteEn = "deepseek-flash reads images; deepseek-v4-pro is text-only and won't work for circling.",
        ),
        Provider(
            "anthropic", "Claude", "Claude", Protocol.ANTHROPIC, "https://api.anthropic.com/v1", "claude-sonnet-5-5",
            listOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5"),
            "https://console.anthropic.com/settings/keys", c(0xFFD97757), c(0xFFC2603F), "*",
            noteZh = "Anthropic 的 Claude，全系列都能看图。",
            noteEn = "Anthropic's Claude; every model reads images.",
        ),
        Provider(
            "gemini", "Gemini", "Gemini", Protocol.CHAT, "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-flash-latest", listOf("gemini-flash-latest", "gemini-pro-latest"),
            "https://aistudio.google.com/apikey", c(0xFF4C8DF6), c(0xFF9B6BD8), "✦",
            EffortStyle.REASONING_EFFORT, listOf("low", "medium", "high"), "low",
            noteZh = "Google AI Studio 的密钥（AIza 开头）。",
            noteEn = "A Google AI Studio key (starts with AIza).",
        ),
        Provider(
            "qwen", "通义千问", "Qwen", Protocol.CHAT, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-vl-plus",
            listOf("qwen3-vl-plus", "qwen-vl-max", "qwen3-vl-flash"),
            "https://bailian.console.aliyun.com/?tab=model#/api-key", c(0xFF615CED), c(0xFF8B5CF6), "千",
            EffortStyle.ENABLE_THINKING, listOf("off", "on"), "off",
            noteZh = "阿里云百炼。要看图的模型名带 vl；国际站把地址改成 https://dashscope-intl.aliyuncs.com/compatible-mode/v1。",
            noteEn = "Alibaba Model Studio. Use a model with \"vl\" in its name; outside China use https://dashscope-intl.aliyuncs.com/compatible-mode/v1.",
        ),
        Provider(
            "zhipu", "智谱 GLM", "Zhipu GLM", Protocol.CHAT, "https://open.bigmodel.cn/api/paas/v4", "glm-4.6v",
            listOf("glm-4.6v", "glm-4.6v-flash"),
            "https://open.bigmodel.cn/usercenter/apikeys", c(0xFF2B5BFF), c(0xFF1C3FB8), "智",
            EffortStyle.THINKING_TYPE, listOf("off", "on"), "off",
            noteZh = "要看图选带 v 的模型；glm-4.6v-flash 免费。",
            noteEn = "Pick a model ending in v to read images; glm-4.6v-flash is free.",
        ),
        Provider(
            "moonshot", "Kimi", "Kimi", Protocol.CHAT, "https://api.moonshot.cn/v1", "kimi-k2.5",
            listOf("kimi-k2.5", "kimi-k2.6"),
            "https://platform.moonshot.cn/console/api-keys", c(0xFF16191F), c(0xFF2C3340), "K",
            noteZh = "月之暗面。国际站把地址改成 https://api.moonshot.ai/v1。",
            noteEn = "Moonshot AI. Outside China use https://api.moonshot.ai/v1.",
        ),
        Provider(
            "doubao", "豆包", "Doubao", Protocol.CHAT, "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-1-6-vision-250815",
            listOf("doubao-seed-1-6-vision-250815"),
            "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey", c(0xFF1E6FFF), c(0xFF19B5C8), "豆",
            EffortStyle.THINKING_TYPE, listOf("off", "on"), "off",
            noteZh = "火山方舟。模型名以控制台里开通的为准，也可以填推理接入点 ID（ep- 开头）。",
            noteEn = "Volcengine Ark. Use a model you've enabled in the console, or an endpoint ID (starts with ep-).",
        ),
    )

    fun builtIn(id: String) = BUILT_IN.firstOrNull { it.id == id }

    /** 内置的加上用户自己加的。 */
    fun all(prefs: Prefs): List<Provider> = BUILT_IN + customs(prefs)

    fun find(prefs: Prefs, id: String): Provider? = all(prefs).firstOrNull { it.id == id }

    // ---------- 自定义接口（中转站、本地模型等） ----------

    fun customs(prefs: Prefs): List<Provider> {
        val arr = try { JSONArray(prefs.customProvidersJson ?: "[]") } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            customProvider(o.optString("id"), o.optString("name"), o.optString("base"),
                runCatching { Protocol.valueOf(o.optString("protocol")) }.getOrDefault(Protocol.CHAT))
        }
    }

    fun customProvider(id: String, name: String, base: String, protocol: Protocol) = Provider(
        id, name.ifBlank { "自定义" }, name.ifBlank { "Custom" }, protocol, base, "", emptyList(), "",
        c(0xFF5B6475), c(0xFF3F4756), name.trim().firstOrNull()?.uppercase() ?: "+",
        effortStyle = if (protocol == Protocol.RESPONSES) EffortStyle.RESPONSES else EffortStyle.NONE,
        efforts = if (protocol == Protocol.RESPONSES) listOf("low", "medium", "high") else emptyList(),
        defaultEffort = if (protocol == Protocol.RESPONSES) "low" else "",
        imageDetail = protocol != Protocol.ANTHROPIC,
        noteZh = "任何 OpenAI 兼容的接口都行：中转站、本地模型（Ollama、LM Studio）等。模型要能看图。",
        noteEn = "Any OpenAI-compatible endpoint works: relays, local models (Ollama, LM Studio) and so on. The model must read images.",
        custom = true,
    )

    fun saveCustom(prefs: Prefs, p: Provider) {
        val list = customs(prefs).filter { it.id != p.id } + p
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.nameZh).put("base", it.baseUrl).put("protocol", it.protocol.name)) }
        prefs.customProvidersJson = arr.toString()
    }

    fun deleteCustom(prefs: Prefs, id: String) {
        val arr = JSONArray()
        customs(prefs).filter { it.id != id }.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.nameZh).put("base", it.baseUrl).put("protocol", it.protocol.name))
        }
        prefs.customProvidersJson = arr.toString()
        prefs.clearProvider(id)
        if (prefs.providerId == id) prefs.providerId = OPENAI
    }

    fun newCustomId() = CUSTOM_PREFIX + System.currentTimeMillis()

    // ---------- 模型 ----------

    private val NOT_CHAT = Regex("embed|tts|whisper|transcri|audio|dall-e|imagen|moderation|rerank|realtime|speech|gpt-image|image-gen|sora|veo|lyria|\\baqa\\b|search", RegexOption.IGNORE_CASE)
    private val VISUAL = Regex("vl|vision|\\dv\\b|\\dv-|gemini|gpt-4o|gpt-4\\.1|gpt-[5-9]|\\bo[34]\\b|claude|kimi-k2\\.[5-9]|kimi-k[3-9]|omni|seed-[2-9]|qvq|deepseek-flash|grok-4", RegexOption.IGNORE_CASE)

    fun isChatModel(id: String) = !NOT_CHAT.containsMatchIn(id)
    fun looksVisual(id: String) = VISUAL.containsMatchIn(id)

    /** 从拉到的模型列表里挑一个：原来选的还在就不动；否则先挑推荐的，再挑能看图的。 */
    fun pickModel(p: Provider, current: String, models: List<ModelInfo>): String {
        if (models.isEmpty()) return current
        val ids = models.map { it.id }
        if (current in ids) return current
        p.models.firstOrNull { it in ids }?.let { return it }
        return (models.firstOrNull { it.vision == true } ?: models.firstOrNull { it.vision == null && looksVisual(it.id) } ?: models.first()).id
    }

    // ---------- 导入：粘贴一段密钥，认出是哪一家 ----------

    class Detected(val provider: Provider, val key: String, val models: List<ModelInfo>, val custom: Provider? = null)

    class DetectError(val reason: Reason, val detail: String = "") : Exception(detail) {
        enum class Reason { NO_KEY, UNKNOWN_FORMAT, REJECTED, NETWORK }
    }

    /** 粘贴的内容里取出密钥和（可选的）接口地址。 */
    fun parsePaste(raw: String): Pair<String?, String?> {
        val url = Regex("https?://[^\\s\"'<>，。]+").find(raw)?.value?.trimEnd('/', ',', ';', '.')
        val rest = if (url != null) raw.replace(url, " ") else raw
        val key = Regex("[A-Za-z0-9][A-Za-z0-9._\\-]{15,}").findAll(rest).map { it.value }
            .filterNot { it.startsWith("http") }.maxByOrNull { it.length }
        return key to url
    }

    /**
     * 按密钥的样子猜是哪几家，再按可能性依次去拉一下模型列表，第一个认这个密钥的就是它（认出来就停，不会再发给别家）。
     * 拉模型列表不花钱。后台线程调用，失败抛 [DetectError]。
     */
    fun detect(prefs: Prefs, raw: String): Detected {
        val (key, url) = parsePaste(raw)
        if (key == null) throw DetectError(DetectError.Reason.NO_KEY)
        if (url != null) return detectCustom(key, url)
        val ids = when {
            key.startsWith("sk-ant-") -> listOf("anthropic")
            key.startsWith("AIza") -> listOf("gemini")
            key.startsWith("sk-proj-") || key.startsWith("sk-svcacct-") || key.startsWith("sk-admin-") -> listOf(OPENAI)
            Regex("^[0-9a-fA-F]{32}\\.[A-Za-z0-9]{8,}$").matches(key) -> listOf("zhipu")
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(key) -> listOf("doubao")
            Regex("^sk-[0-9a-f]{32}$").matches(key) -> listOf("deepseek", "qwen")
            Regex("^sk-[A-Za-z0-9]{40,}$").matches(key) -> listOf("moonshot", OPENAI)
            key.startsWith("sk-") -> listOf("deepseek", "qwen", "moonshot", OPENAI)
            else -> throw DetectError(DetectError.Reason.UNKNOWN_FORMAT)
        }
        var lastNetwork: String? = null
        var rejected = false
        for (id in ids) {
            val p = builtIn(id) ?: continue
            val cfg = prefs.config(p).let { AiConfig(p, it.baseUrl, key, it.model, it.effort) }
            try {
                return Detected(p, key, AiClient(cfg).models())
            } catch (e: AiClient.HttpError) {
                if (e.code == 401 || e.code == 403) rejected = true
                // 只有一个候选、但不是密钥被拒（比如这个地址不支持列模型）：也算认出来了
                else if (ids.size == 1) return Detected(p, key, emptyList())
            } catch (e: IOException) {
                lastNetwork = e.message ?: e.javaClass.simpleName
                if (ids.size == 1) return Detected(p, key, emptyList())   // 前缀已经说明是哪家，只是网络不通
            }
        }
        if (rejected) throw DetectError(DetectError.Reason.REJECTED)
        throw DetectError(DetectError.Reason.NETWORK, lastNetwork ?: "")
    }

    /** 粘贴里带了接口地址：当作自定义接口。地址没写 /v1 时两种都试一下。 */
    private fun detectCustom(key: String, url: String): Detected {
        val base0 = url.trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/responses").removeSuffix("/models")
        val anthropic = base0.contains("anthropic", ignoreCase = true)
        val protocol = if (anthropic) Protocol.ANTHROPIC else Protocol.CHAT
        val host = try { java.net.URI(base0).host ?: base0 } catch (_: Exception) { base0 }
        val bases = if (Regex("/v\\d+[a-z]*$").containsMatchIn(base0)) listOf(base0) else listOf(base0, "$base0/v1")
        var err: Exception? = null
        for (b in bases) {
            val p = customProvider(newCustomId(), host.removePrefix("api.").removePrefix("www."), b, protocol)
            try {
                return Detected(p, key, AiClient(AiConfig(p, b, key, "", "")).models(), custom = p)
            } catch (e: AiClient.HttpError) {
                if (e.code == 401 || e.code == 403) throw DetectError(DetectError.Reason.REJECTED)
                err = e
            } catch (e: IOException) { err = e }
        }
        // 列不出模型也先存下来，模型名让用户自己填
        val p = customProvider(newCustomId(), host.removePrefix("api.").removePrefix("www."), bases.first(), protocol)
        if (err is IOException && err !is AiClient.HttpError) throw DetectError(DetectError.Reason.NETWORK, err.message ?: "")
        return Detected(p, key, emptyList(), custom = p)
    }

    /** 语音追问转文字要用 OpenAI 的接口：当前就是 OpenAI，或者另外填过 OpenAI 的密钥。返回 (地址, 密钥)。 */
    fun transcriber(prefs: Prefs): Pair<String, String>? {
        val cur = prefs.ai()
        if (cur.provider.id == OPENAI && cur.key.isNotBlank()) return cur.baseUrl to cur.key
        val o = prefs.config(builtIn(OPENAI)!!)
        if (o.key.isNotBlank()) return o.baseUrl to o.key
        if (cur.provider.custom && cur.provider.protocol == Protocol.RESPONSES && cur.key.isNotBlank()) return cur.baseUrl to cur.key
        return null
    }
}
