package com.soundcue.babycare.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
// Note: Contents/Content.AudioBytes still used by classifyAudioWithMetrics (legacy path)
import com.soundcue.babycare.domain.AcousticFeatures
import com.soundcue.babycare.domain.AlertPayload
import com.soundcue.babycare.domain.BabyEventType
import com.soundcue.babycare.domain.BabySoundLabel
import com.soundcue.babycare.domain.BabySoundResult
import com.soundcue.babycare.domain.CareEventResult
import com.soundcue.babycare.domain.CareTrack
import com.soundcue.babycare.domain.EventSeverity
import com.soundcue.babycare.domain.NonSpeechCandidate
import com.soundcue.babycare.domain.OutputLang
import com.soundcue.babycare.domain.SoundEvent
import com.soundcue.babycare.domain.SoundPrediction
import com.soundcue.babycare.domain.ToolAction
import com.soundcue.babycare.domain.Urgency
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class InitResult(
    val success: Boolean,
    val backend: String,
    val modelPath: String,
    val error: String? = null
)

data class InferenceResult(
    val payload: AlertPayload,
    val latencyMs: Long,
    val rawResponse: String
)

/**
 * Gemma 4 E2B 를 LiteRT-LM 으로 구동. 텍스트 추론 전용.
 * - 오디오 직접 입력은 현재 Kotlin API 미지원 (GitHub LiteRT-LM issue #1874)
 * - 마이크 경로는 당분간 보류. 워치 제스처+TTS 등 다른 축으로 Gemma 4 활용 확장.
 */
class GemmaLiteRtProvider(
    private val context: Context,
    private val modelPath: String,
    private val judgeMode: Boolean = true,
    private val fallback: FakeLocalProvider? = null
) : LocalAiProvider {

    private var engine: Engine? = null
    private var backendName: String = "uninitialized"
    private var lastError: String? = null

    val backend: String get() = backendName
    val error: String? get() = lastError
    val resolvedModelPath: String get() = modelPath

    suspend fun initialize(): InitResult = withContext(Dispatchers.IO) {
        val file = File(modelPath)
        if (!file.exists()) {
            lastError = "Model not found at $modelPath"
            Log.e(TAG, lastError!!)
            return@withContext InitResult(false, "-", modelPath, lastError)
        }

        tryStart(Backend.GPU(), "GPU")?.let { return@withContext it }
        tryStart(Backend.CPU(), "CPU")?.let { return@withContext it }

        lastError = "Both GPU and CPU backends failed"
        InitResult(false, "-", modelPath, lastError)
    }

    private suspend fun tryStart(backend: Backend, label: String): InitResult? {
        return try {
            // audioBackend 를 같이 CPU 로 고정 (Exynos Flip 6 안정성)
            val config = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                audioBackend = Backend.CPU()
            )
            val e = Engine(config)
            e.initialize()
            engine = e
            backendName = label
            lastError = null
            Log.i(TAG, "Gemma 4 E2B initialized on $label (audio=CPU)")
            InitResult(true, label, modelPath, null)
        } catch (t: Throwable) {
            Log.w(TAG, "Backend $label failed: ${t.message}", t)
            lastError = "$label init failed: ${t.message}"
            null
        }
    }

    override suspend fun isAvailable(): Boolean = engine != null

    override fun providerName(): String =
        if (engine != null) "Gemma 4 E2B · $backendName" else "Gemma 4 (uninitialized)"

    override suspend fun summarize(event: SoundEvent, lang: OutputLang): AlertPayload =
        summarizeWithMetrics(event, lang, emptyList()).payload

    suspend fun summarizeWithMetrics(
        event: SoundEvent,
        lang: OutputLang = OutputLang.KO,
        recentHistory: List<String> = emptyList()
    ): InferenceResult = withContext(Dispatchers.IO) {
        val active = engine ?: return@withContext onFailure(
            "Engine not initialized", event, 0L, lang
        )

        val start = SystemClock.elapsedRealtime()
        val raw = runCatching { generate(active, buildPrompt(event, lang, recentHistory)) }
            .getOrElse { t ->
                Log.e(TAG, "Inference failed", t)
                return@withContext onFailure(
                    "Inference error: ${t.message}",
                    event,
                    SystemClock.elapsedRealtime() - start,
                    lang
                )
            }
        val latency = SystemClock.elapsedRealtime() - start

        val payload = parseJsonOrFail(raw, event, latency, lang)
        InferenceResult(payload, latency, raw)
    }

    private suspend fun generate(active: Engine, prompt: String): String {
        val buffer = StringBuilder()
        active.createConversation().use { conversation ->
            conversation.sendMessageAsync(prompt)
                .catch { throw it }
                .onEach { chunk -> buffer.append(extractText(chunk)) }
                .collect()
        }
        return buffer.toString()
    }

    /**
     * 워치 제스처 → 영유아 말투 한 줄 문장 생성.
     * Gemma 4 가 부모 의도(라벨)를 받아 아이가 들었을 때 자연스러운 문장을 만든다.
     */
    suspend fun generateGesturePhrase(
        gestureLabel: String,
        lang: OutputLang = OutputLang.KO,
        recentHistory: List<String> = emptyList(),
        profileContext: String = ""
    ): String = withContext(Dispatchers.IO) {
        val active = engine ?: return@withContext defaultGestureFallback(gestureLabel, lang)
        val prompt = buildGesturePrompt(gestureLabel, lang, recentHistory, profileContext)
        val raw = runCatching {
            val buf = StringBuilder()
            active.createConversation().use { c ->
                c.sendMessageAsync(prompt)
                    .catch { throw it }
                    .onEach { chunk -> buf.append(extractText(chunk)) }
                    .collect()
            }
            buf.toString()
        }.getOrElse { t ->
            Log.e(TAG, "gesture phrase failed: ${t.message}", t)
            return@withContext defaultGestureFallback(gestureLabel, lang)
        }

        // 따옴표·코드펜스 제거. 한 줄만 추출.
        raw.trim()
            .removePrefix("```").removeSuffix("```")
            .lines().firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.removePrefix("\"")?.removeSuffix("\"")
            ?.removePrefix("'")?.removeSuffix("'")
            ?: defaultGestureFallback(gestureLabel, lang)
    }

    private fun buildGesturePrompt(
        gestureLabel: String,
        lang: OutputLang,
        recent: List<String>,
        profileContext: String = ""
    ): String {
        val historyBlock = if (recent.isEmpty())
            (if (lang == OutputLang.EN) "no recent baby event" else "최근 아기 이벤트 없음")
        else recent.joinToString("\n") { "- $it" }

        val persona = profileContext.ifBlank { "no profile set" }

        return if (lang == OutputLang.EN) """
            You speak as a Deaf parent who just made a sign-language gesture meaning "$gestureLabel".
            Your phone will TTS this aloud to a 0-3 year old baby.
            Your response MUST reflect what the baby was just doing (see recent events).

            Personalization context:
            $persona

            Output ONE short sentence in English only. No quotes, no JSON, no explanation.

            Style:
            - Warm, baby-friendly, ≤2 short sentences, ≤80 chars total.
            - Address the baby directly ("honey", "baby").

            Gesture meanings:
            - "call_name" → call the baby by name, sweetly, with variations ("Haeun-ah~ Haeun~")
            - "name_here" → tell baby that parent is here, using baby's name ("Haeun, mom is here")
            - "hungry"    → ask about feeding or say food is coming
            - "awake"     → check if baby woke up or greet after sleep
            - "hurt"      → comforting, "where does it hurt"
            - "well_done" → praise
            - "love"      → express affection
            - "mom_here"  → reassure "Mom is here"
            - "dad_here"  → reassure "Dad is here"
            - "wait"      → reassure "I'm coming soon"

            Recent baby events (use as context — e.g. if baby was crying, respond soothingly):
            $historyBlock

            Sentence for "$gestureLabel":
        """.trimIndent() else """
            너는 방금 "$gestureLabel" 의미의 수화 제스처를 한 청각장애 부모다.
            폰이 이 문장을 TTS 로 0~3세 아이에게 들려줄 것이다.
            응답은 아기가 방금 무엇을 하고 있었는지(최근 이벤트)를 반드시 고려해서 자연스럽게 이어져야 한다.

            개인화 컨텍스트:
            $persona

            오직 한국어로 짧은 문장 1~2개만 출력. 따옴표·JSON·설명 금지.

            스타일:
            - 따뜻하고 영유아 친화적, 총 40자 이내.
            - "우리 아기", "아가", "엄마가", "아빠가" 같은 표현 자연스럽게.

            제스처 의미:
            - "call_name" → 아기 이름을 다정하게 부르기. 변형해서 불러 ("하은아~", "하은이~", "우리 하은아~" 등)
            - "name_here" → 아기 이름 + 부모가 여기 왔다 알리기 ("하은아, 엄마 여깄어. 하은아, 엄마 왔어.")
            - "hungry"    → 배고픈지 묻거나 맘마 준비를 알림 (예: "하은아 배고프니?", "맘마 먹자")
            - "awake"     → 잠에서 깼는지 확인 또는 잘 잤는지 인사 (예: "하은아 일어났니?", "잘 잤어?")
            - "hurt"      → 어디 아픈지 걱정하며 위로 (예: "왜 그래, 어디 아파?")
            - "well_done" → 칭찬 (예: "하은아 잘했어!", "우리 하은이 최고야!")
            - "love"      → 사랑 표현 (예: "사랑해.", "엄마가 우리 하은이 너무 사랑해.")
            - "mom_here"  → 안심 시키기 (예: "엄마야. 엄마 여깄어.")
            - "dad_here"  → 안심 시키기 (예: "아빠야. 아빠 여깄어.")
            - "wait"      → 기다리라는 안심 (예: "잠깐만, 엄마 바로 갈게.")

            상황에 맞춰:
            - 아기가 울고 있었다면 → 부드럽고 달래는 말투
            - 아기가 웃고 있었다면 → 같이 기뻐하는 말투
            - 이벤트가 없으면 → 기본 친근한 말투

            최근 아기 이벤트:
            $historyBlock

            "$gestureLabel" 에 대한 한 문장 (한국어):
        """.trimIndent()
    }

    private fun defaultGestureFallback(label: String, lang: OutputLang): String {
        return if (lang == OutputLang.EN) when (label) {
            "call_name" -> "Hey baby! Baby!"
            "name_here" -> "Baby, mom is here. Mom came for you."
            "hungry" -> "Are you hungry, baby? Mom will feed you."
            "awake" -> "Did you wake up, honey? How was your nap?"
            "hurt" -> "What's wrong, baby? Where does it hurt?"
            "well_done" -> "You did so well, baby!"
            "mom_here" -> "Mom is here. Mom is right here."
            "dad_here" -> "Dad is here. Dad is right here."
            "love" -> "Mommy loves you so much."
            "wait" -> "Just a moment, mom is coming."
            else -> "Mom is here."
        } else when (label) {
            "call_name" -> "아가, 아가야"
            "name_here" -> "아가, 엄마 여깄어. 엄마 왔어."
            "hungry" -> "아가 배고프니? 우리 아기 맘마 먹자."
            "awake" -> "아가 일어났니? 우리 아기 잘 잤어?"
            "hurt" -> "왜 그래, 어디 아파?"
            "well_done" -> "아이 잘했어! 우리 아기 최고야."
            "mom_here" -> "엄마야. 엄마 여깄어."
            "dad_here" -> "아빠야. 아빠 여깄어."
            "love" -> "사랑해. 엄마가 우리 아기 너무 사랑해."
            "wait" -> "잠깐만, 엄마 바로 갈게."
            else -> "엄마 여기 있어."
        }
    }

    /**
     * Gemma Audio Input으로 babble 오디오에서 mama/baba를 구분한다.
     * Content.AudioBytes를 사용하므로 LiteRT-LM이 오디오 멀티모달을 지원해야 동작한다.
     * 미지원 시 예외가 발생하며, BabySoundPipeline이 fallback 처리한다.
     */
    suspend fun detectBabbleContent(wavBytes: ByteArray): SoundPrediction? =
        withContext(Dispatchers.IO) {
            val active = engine ?: return@withContext null

            val raw = runCatching {
                val buf = StringBuilder()
                active.createConversation().use { c ->
                    val contents = Contents.of(
                        Content.AudioBytes(wavBytes),
                        Content.Text(BABBLE_DETECT_PROMPT)
                    )
                    c.sendMessageAsync(contents)
                        .catch { throw it }
                        .onEach { chunk -> buf.append(extractText(chunk)) }
                        .collect()
                }
                buf.toString().trim().lowercase()
            }.getOrElse { t ->
                Log.w(TAG, "Babble AudioBytes failed: ${t.message}")
                return@withContext null
            }

            Log.i(TAG, "Gemma babble raw: '$raw'")

            // 응답 파싱: "mama", "baba/papa", "unknown"
            when {
                raw.contains("mama") || raw.contains("엄마") || raw.contains("맘마") ->
                    SoundPrediction(BabySoundLabel.BABBLE_MAMA, 0.70f, "gemma_audio:mama")
                raw.contains("baba") || raw.contains("papa") ||
                    raw.contains("아빠") || raw.contains("빠빠") ->
                    SoundPrediction(BabySoundLabel.BABBLE_BABA, 0.70f, "gemma_audio:baba")
                else -> null // 구분 못하면 BABBLE_GENERIC 유지
            }
        }

    /**
     * ML 분류 결과(BabySoundResult)를 받아 보호자용 케어 메시지를 생성한다.
     * 분류는 YAMNet이 했고, Gemma는 설명/안내문만 생성한다.
     */
    suspend fun generateCareMessage(
        classifyResult: BabySoundResult,
        lang: OutputLang = OutputLang.KO,
        priorEvents: List<String> = emptyList()
    ): CareEventResult = withContext(Dispatchers.IO) {
        val label = classifyResult.primary.label
        val conf = classifyResult.primary.confidence

        if (label == BabySoundLabel.SILENCE) {
            return@withContext buildSilenceResult(lang)
        }

        val active = engine ?: return@withContext buildMlFallbackCareResult(label, conf, lang)

        val raw = runCatching {
            val buf = StringBuilder()
            active.createConversation().use { c ->
                c.sendMessageAsync(
                    buildMlCarePrompt(label, conf, classifyResult.clipDurationMs, lang, priorEvents)
                )
                    .catch { throw it }
                    .onEach { chunk -> buf.append(extractText(chunk)) }
                    .collect()
            }
            buf.toString()
        }.getOrElse { t ->
            Log.e(TAG, "care text gen failed: ${t.message}", t)
            return@withContext buildMlFallbackCareResult(label, conf, lang)
        }

        val parsed = parseCareResult(raw, lang)
        parsed.copy(
            selectedEvent = label.tag,
            selectedConfidence = conf,
            topCandidates = classifyResult.topK.map {
                NonSpeechCandidate(it.label.tag, it.confidence)
            },
            rawJson = raw
        )
    }

    private fun buildMlCarePrompt(
        label: BabySoundLabel,
        confidence: Float,
        durationMs: Long,
        lang: OutputLang,
        priorEvents: List<String>
    ): String {
        val prior = if (priorEvents.isEmpty())
            (if (lang == OutputLang.EN) "none" else "없음")
        else priorEvents.joinToString("; ")

        val durDesc = when {
            durationMs < 400  -> if (lang == OutputLang.EN) "very brief"    else "아주 짧은"
            durationMs < 1500 -> if (lang == OutputLang.EN) "brief"         else "짧은"
            durationMs < 4000 -> if (lang == OutputLang.EN) "a few seconds" else "몇 초간"
            else              -> if (lang == OutputLang.EN) "sustained"     else "지속적인"
        }
        val urgency = label.defaultUrgency.name.lowercase()
        val action = if (label.defaultUrgency != Urgency.LOW) "notify_watch" else "none"
        val confStr = "%.2f".format(confidence)
        val labelDisplay = label.display(lang)

        return if (lang == OutputLang.EN) """
            You are a warm care assistant helping a Deaf parent with their 0-3yr old baby.
            Audio ML classifier detected: $durDesc "$labelDisplay" sound (confidence ${(confidence * 100).toInt()}%).
            Recent events: $prior

            Fill ONLY the "heard" and "care_hint" fields with warm, natural language.
            Return ONE strict JSON - no prose, no code fences:
            {"track":"non_speech","heard":"[1 warm natural sentence about the sound]","top_candidates":[{"event":"${label.tag}","confidence":$confStr}],"selected_event":"${label.tag}","selected_confidence":$confStr,"urgency":"$urgency","care_hint":"[1 gentle parent action hint, under 80 chars]","action":"$action"}
        """.trimIndent() else """
            청각장애 부모를 위한 따뜻한 베이비 모니터.
            오디오 ML 분류기 감지: $durDesc "$labelDisplay" 소리 (신뢰도 ${(confidence * 100).toInt()}%).
            최근 이벤트: $prior

            "heard" 와 "care_hint" 만 따뜻한 한국어로 채워주세요.
            JSON 하나만 출력, 설명/코드펜스 완전 금지:
            {"track":"non_speech","heard":"[자연스러운 한국어 한 문장]","top_candidates":[{"event":"${label.tag}","confidence":$confStr}],"selected_event":"${label.tag}","selected_confidence":$confStr,"urgency":"$urgency","care_hint":"[부드러운 안내 한 문장, 40자 이내, 존댓말]","action":"$action"}
        """.trimIndent()
    }

    private fun buildMlFallbackCareResult(
        label: BabySoundLabel,
        confidence: Float,
        lang: OutputLang
    ): CareEventResult {
        val heard = if (lang == OutputLang.EN) mlFallbackHeardEn(label) else mlFallbackHeardKo(label)
        val hint = if (lang == OutputLang.EN) mlFallbackHintEn(label) else mlFallbackHintKo(label)
        return CareEventResult(
            track = CareTrack.NON_SPEECH,
            topCandidates = listOf(NonSpeechCandidate(label.tag, confidence)),
            selectedEvent = label.tag,
            selectedConfidence = confidence,
            urgency = label.defaultUrgency,
            heard = heard,
            careHint = hint,
            action = if (label.defaultUrgency != Urgency.LOW) ToolAction.NOTIFY_WATCH else ToolAction.NONE
        )
    }

    private fun mlFallbackHeardKo(label: BabySoundLabel) = when (label) {
        BabySoundLabel.BABY_CRY       -> "아기가 울고 있는 것 같아요."
        BabySoundLabel.BABY_LAUGH     -> "아기가 웃고 있는 것 같아요!"
        BabySoundLabel.BABBLE_MAMA    -> "아기가 '맘마' 하고 부르는 것 같아요!"
        BabySoundLabel.BABBLE_BABA    -> "아기가 '빠빠' 하고 부르는 것 같아요!"
        BabySoundLabel.BABBLE_GENERIC -> "아기가 옹알이를 하고 있어요."
        BabySoundLabel.CRASH_CLANG    -> "쩅그랑! 무언가 깨지거나 부딪히는 소리가 났어요."
        BabySoundLabel.COUGH          -> "아기 기침 소리가 들렸어요."
        BabySoundLabel.BURP           -> "아기 트림 소리가 났어요."
        BabySoundLabel.HICCUP         -> "아기 딸꾹질 소리인 것 같아요."
        BabySoundLabel.FART           -> "아기 방귀 소리가 났어요!"
        BabySoundLabel.ADULT_SPEECH   -> "근처에서 말소리가 들렸어요."
        BabySoundLabel.LOUD_NOISE     -> "큰 소리가 감지됐어요."
        BabySoundLabel.SILENCE        -> "조용해요, 아기 소리가 들리지 않아요."
        BabySoundLabel.OTHER          -> "무슨 소리가 감지됐어요."
    }

    private fun mlFallbackHeardEn(label: BabySoundLabel) = when (label) {
        BabySoundLabel.BABY_CRY       -> "Your baby seems to be crying."
        BabySoundLabel.BABY_LAUGH     -> "Your baby seems to be laughing!"
        BabySoundLabel.BABBLE_MAMA    -> "Your baby seems to be saying 'mama'!"
        BabySoundLabel.BABBLE_BABA    -> "Your baby seems to be saying 'papa/baba'!"
        BabySoundLabel.BABBLE_GENERIC -> "Your baby is babbling."
        BabySoundLabel.CRASH_CLANG    -> "A crash or breaking sound was detected!"
        BabySoundLabel.COUGH          -> "A cough was detected."
        BabySoundLabel.BURP           -> "Your baby just burped."
        BabySoundLabel.HICCUP         -> "Your baby seems to have hiccups."
        BabySoundLabel.FART           -> "A little toot from your baby!"
        BabySoundLabel.ADULT_SPEECH   -> "Someone nearby is talking."
        BabySoundLabel.LOUD_NOISE     -> "A loud noise was detected."
        BabySoundLabel.SILENCE        -> "It's quiet - no baby sounds."
        BabySoundLabel.OTHER          -> "A sound was detected."
    }

    private fun mlFallbackHintKo(label: BabySoundLabel) = when (label) {
        BabySoundLabel.BABY_CRY       -> "아기가 신경 쓰이면 지금 바로 확인해 보세요."
        BabySoundLabel.BABY_LAUGH     -> "아기가 즐거운 것 같아요, 함께 웃어봐요!"
        BabySoundLabel.BABBLE_MAMA    -> "아기가 엄마를 찾고 있어요! 가까이 가보세요."
        BabySoundLabel.BABBLE_BABA    -> "아기가 아빠를 찾고 있어요! 가까이 가보세요."
        BabySoundLabel.BABBLE_GENERIC -> "아기가 말을 걸고 있어요, 눈 맞추며 반응해 주세요!"
        BabySoundLabel.CRASH_CLANG    -> "주변이 안전한지 바로 확인해 주세요!"
        BabySoundLabel.COUGH          -> "호흡에 변화가 없는지 살펴봐 주세요."
        BabySoundLabel.BURP           -> "수유 후 트림은 자연스러운 일이에요."
        BabySoundLabel.HICCUP         -> "딸꾹질은 금방 지나가요, 조금 기다려 보세요."
        BabySoundLabel.FART           -> "기저귀를 확인해 보시는 게 좋겠어요."
        BabySoundLabel.ADULT_SPEECH   -> "주변 사람과 대화 중인 것 같아요."
        BabySoundLabel.LOUD_NOISE     -> "주변이 안전한지 한 번 확인해 주세요."
        BabySoundLabel.SILENCE        -> "조용히 지켜보고 있을게요."
        BabySoundLabel.OTHER          -> "계속 지켜보고 있을게요."
    }

    private fun mlFallbackHintEn(label: BabySoundLabel) = when (label) {
        BabySoundLabel.BABY_CRY       -> "Check on your baby soon."
        BabySoundLabel.BABY_LAUGH     -> "Your baby is happy - enjoy the moment!"
        BabySoundLabel.BABBLE_MAMA    -> "Baby is calling for mama! Go say hi."
        BabySoundLabel.BABBLE_BABA    -> "Baby is calling for papa! Go say hi."
        BabySoundLabel.BABBLE_GENERIC -> "Talk back - baby loves hearing you!"
        BabySoundLabel.CRASH_CLANG    -> "Check that everything nearby is safe!"
        BabySoundLabel.COUGH          -> "Watch for breathing changes."
        BabySoundLabel.BURP           -> "A burp after feeding is perfectly normal."
        BabySoundLabel.HICCUP         -> "Hiccups are harmless - they'll pass soon."
        BabySoundLabel.FART           -> "A diaper check might be a good idea."
        BabySoundLabel.ADULT_SPEECH   -> "Someone nearby is talking."
        BabySoundLabel.LOUD_NOISE     -> "Check that everything is safe."
        BabySoundLabel.SILENCE        -> "All quiet. Keep monitoring."
        BabySoundLabel.OTHER          -> "Keep monitoring."
    }

    /**
     * 비음성 오디오 → care reasoning.
     * (레거시 경로 - 새 ML 파이프라인 사용 시 generateCareMessage()로 대체됨)
     */
    suspend fun reasonNonSpeechCare(
        wavBytes: ByteArray,
        features: AcousticFeatures,
        lang: OutputLang = OutputLang.KO,
        priorEvents: List<String> = emptyList(),
        environmentMode: String = "default",
        timeOfDay: String = "day"
    ): Pair<CareEventResult, Long> = withContext(Dispatchers.IO) {
        val start = SystemClock.elapsedRealtime()

        // Step 1: Rule-based acoustic classification (reliable)
        val (primaryLabel, primaryConf) = classifyByAcoustics(features)
        Log.i(TAG, "Acoustic: $primaryLabel @ ${(primaryConf * 100).toInt()}% " +
            "(dur=${features.durationMs}ms rms=${features.rmsEnergy} zcr=${features.zeroCrossingRate})")

        if (primaryLabel == "silence") {
            return@withContext buildSilenceResult(lang) to (SystemClock.elapsedRealtime() - start)
        }

        // Step 2: Gemma TEXT-ONLY for warm heard/care_hint (no audio bytes sent)
        val active = engine ?: return@withContext buildFallbackCareResult(primaryLabel, primaryConf, lang) to
            (SystemClock.elapsedRealtime() - start)

        val raw = runCatching {
            val buf = StringBuilder()
            active.createConversation().use { c ->
                c.sendMessageAsync(
                    buildTextOnlyCarePrompt(primaryLabel, primaryConf, features, lang, priorEvents)
                )
                    .catch { throw it }
                    .onEach { chunk -> buf.append(extractText(chunk)) }
                    .collect()
            }
            buf.toString()
        }.getOrElse { t ->
            Log.e(TAG, "care text gen failed: ${t.message}", t)
            return@withContext buildFallbackCareResult(primaryLabel, primaryConf, lang) to
                (SystemClock.elapsedRealtime() - start)
        }

        val latency = SystemClock.elapsedRealtime() - start
        // Parse JSON from Gemma, then force acoustic label/confidence (model must not override them)
        val parsed = parseCareResult(raw, lang)
        parsed.copy(
            selectedEvent = primaryLabel,
            selectedConfidence = primaryConf,
            topCandidates = listOf(NonSpeechCandidate(primaryLabel, primaryConf)) +
                parsed.topCandidates.filter { it.event != primaryLabel }.take(2),
            rawJson = raw
        ) to latency
    }

    /** Rule-based acoustic classifier using ZCR, RMS, duration, and peak amplitude.
     *
     * 한국어 특성: 모음 비중이 높아 ZCR이 영어보다 낮게 나옴.
     * 따라서 adult_speech 감지 임계값을 낮추고, baby_cry는 ZCR을 더 엄격히 제한.
     */
    private fun classifyByAcoustics(f: AcousticFeatures): Pair<String, Float> {
        val dur  = f.durationMs
        val rms  = f.rmsEnergy
        val zcr  = f.zeroCrossingRate
        val peak = f.peakAmplitude

        // Silence
        if (rms < 0.008f || peak < 0.01f) return "silence" to 0.90f

        // Very short burst (< 300ms): cough, burp, hiccup
        if (dur < 300) return when {
            zcr > 0.22f && peak > 0.15f -> "cough"  to 0.72f  // turbulent air = cough
            peak > 0.35f && zcr < 0.12f -> "burp"   to 0.75f  // loud low-freq pop = burp
            rms  < 0.02f                -> "hiccup" to 0.62f  // quiet tonal = hiccup
            zcr  < 0.10f                -> "burp"   to 0.58f
            else                        -> "cough"  to 0.55f
        }

        // Short burst (300–800ms)
        // 주의: 짧은 말소리("엄마!")도 여기 해당 → adult_speech 우선 체크
        if (dur < 800) return when {
            zcr > 0.20f && peak > 0.20f  -> "cough"        to 0.68f  // 탁 터지는 기침
            zcr > 0.17f && rms  > 0.03f  -> "adult_speech" to 0.63f  // 짧은 말소리
            zcr < 0.08f && rms  > 0.04f  -> "burp"         to 0.70f
            zcr < 0.08f && rms  < 0.04f  -> "fart"         to 0.62f
            rms  < 0.02f                 -> "hiccup"       to 0.60f
            else                         -> "fart"         to 0.48f
        }

        // Medium (800ms–3s): cry, laugh, babble, speech
        // 핵심 수정: baby_cry_general ZCR 상한 0.12→0.09 (더 엄격)
        //           adult_speech ZCR 하한 0.30→0.18 (한국어 모음 감안)
        if (dur < 3000) return when {
            rms  > 0.06f && zcr < 0.09f             -> "baby_cry_general"    to 0.75f // 크고 음정 있음 = 울음
            zcr  > 0.18f && rms  > 0.03f            -> "adult_speech"        to 0.70f // 말소리 (한국어 포함)
            rms  > 0.04f && zcr in 0.09f..0.18f     -> "baby_laugh"          to 0.62f // 중간 ZCR = 웃음
            rms  > 0.05f && zcr < 0.14f             -> "baby_cry_discomfort" to 0.58f
            zcr  > 0.13f && rms > 0.02f             -> "baby_babble"         to 0.52f
            rms  < 0.015f                            -> "room_noise"          to 0.65f
            else                                     -> "baby_babble"         to 0.45f
        }

        // Long (> 3s): sustained cry, extended speech, background
        return when {
            rms > 0.05f && zcr < 0.12f  -> "baby_cry_general"    to 0.78f // ZCR 상한 0.18→0.12
            zcr > 0.20f && rms > 0.03f  -> "adult_speech"        to 0.72f // ZCR 하한 0.28→0.20
            rms > 0.03f && zcr < 0.20f  -> "baby_cry_discomfort" to 0.62f
            rms > 0.02f                 -> "room_noise"          to 0.58f
            else                        -> "silence"             to 0.55f
        }
    }

    private fun urgencyForLabel(label: String): Urgency = when {
        label.startsWith("baby_cry") -> Urgency.MEDIUM
        label == "loud_noise"        -> Urgency.HIGH
        label == "cough"             -> Urgency.MEDIUM
        else                         -> Urgency.LOW
    }

    private fun buildTextOnlyCarePrompt(
        label: String,
        confidence: Float,
        f: AcousticFeatures,
        lang: OutputLang,
        priorEvents: List<String>
    ): String {
        val prior = if (priorEvents.isEmpty())
            (if (lang == OutputLang.EN) "none" else "없음")
        else priorEvents.joinToString("; ")

        val durDesc = when {
            f.durationMs < 400  -> if (lang == OutputLang.EN) "very brief"     else "아주 짧은"
            f.durationMs < 1500 -> if (lang == OutputLang.EN) "brief"          else "짧은"
            f.durationMs < 4000 -> if (lang == OutputLang.EN) "a few seconds"  else "몇 초간"
            else                -> if (lang == OutputLang.EN) "sustained"      else "지속적인"
        }
        val urgency = urgencyForLabel(label).name.lowercase()
        val action  = if (urgency != "low") "notify_watch" else "none"
        val confStr = "%.2f".format(confidence)

        return if (lang == OutputLang.EN) """
            You are a warm care assistant helping a Deaf parent with their 0-3yr old baby.
            Audio sensor detected: $durDesc "$label" sound (confidence ${(confidence * 100).toInt()}%).
            Recent events: $prior

            Fill ONLY the "heard" and "care_hint" fields with warm, natural language.
            Return ONE strict JSON — no prose, no code fences:
            {"track":"non_speech","heard":"[1 warm natural sentence about the sound]","top_candidates":[{"event":"$label","confidence":$confStr}],"selected_event":"$label","selected_confidence":$confStr,"urgency":"$urgency","care_hint":"[1 gentle parent action hint, under 80 chars]","action":"$action"}
        """.trimIndent() else """
            청각장애 부모를 위한 따뜻한 베이비 모니터.
            음향 센서 감지: $durDesc "$label" 소리 (신뢰도 ${(confidence * 100).toInt()}%).
            최근 이벤트: $prior

            "heard" 와 "care_hint" 만 따뜻한 한국어로 채워주세요.
            JSON 하나만 출력, 설명·코드펜스 완전 금지:
            {"track":"non_speech","heard":"[자연스러운 한국어 한 문장]","top_candidates":[{"event":"$label","confidence":$confStr}],"selected_event":"$label","selected_confidence":$confStr,"urgency":"$urgency","care_hint":"[부드러운 안내 한 문장, 40자 이내, 존댓말]","action":"$action"}
        """.trimIndent()
    }

    private fun buildSilenceResult(lang: OutputLang) = CareEventResult(
        track = CareTrack.NON_SPEECH,
        topCandidates = listOf(NonSpeechCandidate("silence", 0.85f)),
        selectedEvent = "silence",
        selectedConfidence = 0.85f,
        urgency = Urgency.LOW,
        heard = if (lang == OutputLang.EN) "It's quiet — no baby sounds detected."
                else "조용해요, 아기 소리가 들리지 않아요.",
        careHint = if (lang == OutputLang.EN) "All quiet. Keep monitoring."
                   else "아직 조용해요. 편안하게 지켜보고 있을게요.",
        action = ToolAction.NONE
    )

    private fun buildFallbackCareResult(label: String, confidence: Float, lang: OutputLang) =
        CareEventResult(
            track = CareTrack.NON_SPEECH,
            topCandidates = listOf(NonSpeechCandidate(label, confidence)),
            selectedEvent = label,
            selectedConfidence = confidence,
            urgency = urgencyForLabel(label),
            heard = if (lang == OutputLang.EN) fallbackHeardEn(label) else fallbackHeardKo(label),
            careHint = if (lang == OutputLang.EN) fallbackHintEn(label) else fallbackHintKo(label),
            action = if (urgencyForLabel(label) != Urgency.LOW) ToolAction.NOTIFY_WATCH else ToolAction.NONE
        )

    private fun fallbackHeardEn(label: String) = when (label) {
        "baby_cry_general"    -> "Your baby seems to be crying."
        "baby_cry_hunger"     -> "Your baby might be crying from hunger."
        "baby_cry_discomfort" -> "Your baby sounds uncomfortable."
        "baby_laugh"          -> "Your baby seems to be laughing — how sweet!"
        "baby_babble"         -> "Your baby is babbling and cooing."
        "burp"                -> "Sounds like your baby just burped!"
        "cough"               -> "Your baby may have coughed."
        "hiccup"              -> "Your baby seems to have the hiccups."
        "fart"                -> "Sounds like a little toot from your baby!"
        "adult_speech"        -> "Someone nearby seems to be talking."
        "loud_noise"          -> "A loud noise was detected nearby."
        else                  -> "A sound was detected nearby."
    }

    private fun fallbackHeardKo(label: String) = when (label) {
        "baby_cry_general"    -> "아기가 울고 있는 것 같아요."
        "baby_cry_hunger"     -> "아기가 배고파 우는 것 같아요."
        "baby_cry_discomfort" -> "아기가 불편해 우는 것 같아요."
        "baby_laugh"          -> "아기가 웃고 있는 것 같아요!"
        "baby_babble"         -> "아기가 옹알이를 하고 있어요."
        "burp"                -> "아기 트림 소리가 났어요."
        "cough"               -> "아기 기침 소리가 들렸어요."
        "hiccup"              -> "아기 딸꾹질 소리인 것 같아요."
        "fart"                -> "아기 방귀 소리가 났어요!"
        "adult_speech"        -> "근처에서 말소리가 들렸어요."
        "loud_noise"          -> "큰 소리가 감지됐어요."
        else                  -> "무슨 소리가 감지됐어요."
    }

    private fun fallbackHintEn(label: String) = when (label) {
        "baby_cry_general", "baby_cry_discomfort" -> "Your baby needs attention — check on them soon."
        "baby_cry_hunger"  -> "Your baby might be hungry — time for a feed?"
        "baby_laugh"       -> "Your baby is happy — enjoy this sweet moment!"
        "baby_babble"      -> "Your baby is talking to you — say hi back!"
        "burp"             -> "A burp after feeding is perfectly normal!"
        "cough"            -> "Keep an eye on your baby's breathing."
        "hiccup"           -> "Hiccups are harmless — they'll pass soon."
        "fart"             -> "A diaper check might be a good idea."
        "loud_noise"       -> "Check that everything nearby is safe."
        else               -> "Keep monitoring — all seems fine."
    }

    private fun fallbackHintKo(label: String) = when (label) {
        "baby_cry_general", "baby_cry_discomfort" -> "아기가 신경 쓰이면 지금 바로 확인해 보세요."
        "baby_cry_hunger"  -> "마지막 수유 시간을 확인해 보세요."
        "baby_laugh"       -> "아기가 즐거운 것 같아요, 함께 웃어봐요!"
        "baby_babble"      -> "아기가 말을 걸고 있어요, 눈 맞추며 반응해 주세요!"
        "burp"             -> "수유 후 트림은 자연스러운 일이에요."
        "cough"            -> "호흡에 변화가 없는지 살펴봐 주세요."
        "hiccup"           -> "딸꾹질은 금방 지나가요, 조금 기다려 보세요."
        "fart"             -> "기저귀를 확인해 보시는 게 좋겠어요."
        "loud_noise"       -> "주변이 안전한지 한 번 확인해 주세요."
        else               -> "조용히 지켜보고 있을게요."
    }

    private fun parseCareResult(raw: String, lang: OutputLang): CareEventResult {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
            .let { t ->
                val s = t.indexOf('{'); val e = t.lastIndexOf('}')
                if (s >= 0 && e > s) t.substring(s, e + 1) else t
            }
        return runCatching {
            val obj = JSONObject(cleaned)
            val candidatesJson: JSONArray = obj.optJSONArray("top_candidates") ?: JSONArray()
            val candidates = buildList {
                for (i in 0 until candidatesJson.length()) {
                    val c = candidatesJson.optJSONObject(i) ?: continue
                    val evt = c.optString("event").takeIf { it.isNotBlank() } ?: continue
                    val conf = c.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f)
                    add(NonSpeechCandidate(evt, conf))
                }
            }
            val selected = obj.optString("selected_event", "unknown_non_speech")
            val selectedConf = obj.optDouble("selected_confidence", 0.0).toFloat().coerceIn(0f, 1f)
            val urgency = Urgency.parse(obj.optString("urgency"))
            val heard = obj.optString("heard",
                if (lang == OutputLang.EN) "no description" else "설명 없음")
            val careHint = obj.optString("care_hint",
                if (lang == OutputLang.EN) "Keep monitoring." else "계속 지켜보세요.")
            val action = ToolAction.parse(obj.optString("action"))

            CareEventResult(
                track = CareTrack.NON_SPEECH,
                topCandidates = candidates.ifEmpty {
                    listOf(NonSpeechCandidate(selected, selectedConf))
                },
                selectedEvent = selected,
                selectedConfidence = selectedConf,
                urgency = urgency,
                heard = heard,
                careHint = careHint,
                action = action,
                rawJson = raw
            )
        }.getOrElse {
            Log.w(TAG, "parseCareResult failed. raw='$raw'")
            buildAbstainResult(lang, "parse_failed").copy(rawJson = raw)
        }
    }

    private fun buildAbstainResult(lang: OutputLang, reason: String): CareEventResult {
        val heard = if (lang == OutputLang.EN) "something, but I couldn't tell clearly"
        else "뭔가 들렸는데 잘 모르겠어요"
        val hint = if (lang == OutputLang.EN) "Still watching quietly — I'll let you know."
        else "조용히 지켜보고 있을게요. 확실해지면 알려드릴게요."
        return CareEventResult(
            track = CareTrack.UNKNOWN,
            topCandidates = listOf(NonSpeechCandidate("unknown_non_speech", 0f)),
            selectedEvent = "unknown_non_speech",
            selectedConfidence = 0f,
            urgency = Urgency.LOW,
            heard = heard,
            careHint = hint,
            action = ToolAction.REQUEST_RELISTEN
        )
    }

    /**
     * 과거 호환용 (기존 UI 경로). 내부적으로는 reasonNonSpeechCare 호출 후 AlertPayload 매핑.
     * 아래 `classifyAudioWithMetrics` 는 유지하되 이제 deprecated 경로임.
     */
    suspend fun classifyAudioWithMetrics(
        wavBytes: ByteArray,
        lang: OutputLang = OutputLang.KO,
        recentHistory: List<String> = emptyList()
    ): InferenceResult = withContext(Dispatchers.IO) {
        val active = engine ?: return@withContext onFailureAudio(
            "Engine not initialized", 0L, lang
        )
        val start = SystemClock.elapsedRealtime()
        val raw = runCatching {
            val buffer = StringBuilder()
            active.createConversation().use { conversation ->
                val contents = Contents.of(
                    Content.AudioBytes(wavBytes),
                    Content.Text(buildAudioPrompt(lang, recentHistory))
                )
                conversation.sendMessageAsync(contents)
                    .catch { throw it }
                    .onEach { chunk -> buffer.append(extractText(chunk)) }
                    .collect()
            }
            buffer.toString()
        }.getOrElse { t ->
            Log.e(TAG, "Audio inference failed", t)
            return@withContext onFailureAudio(
                "Audio inference error: ${t.message}",
                SystemClock.elapsedRealtime() - start,
                lang
            )
        }
        val latency = SystemClock.elapsedRealtime() - start
        val payload = parseAudioJsonOrFail(raw, latency, lang)
        InferenceResult(payload, latency, raw)
    }

    private fun buildAudioPrompt(lang: OutputLang, recentHistory: List<String>): String {
        val history = if (recentHistory.isEmpty())
            (if (lang == OutputLang.EN) "No recent events" else "최근 이벤트 없음")
        else recentHistory.joinToString("\n") { "- $it" }

        return if (lang == OutputLang.EN) """
            You are assisting a Deaf parent. Listen to the attached audio clip and do TWO things:

            1) First, in the "heard" field, **literally describe what you heard in one short English sentence**.
               Examples: "a baby crying softly", "a single hand clap", "a person saying 'hello'",
               "a short cough", "background music only", "no clear sound".

            2) Then pick the best label from this list (or "silence" if none fits):
               baby_cry, baby_laugh, cough, parent_call, first_word,
               burp, fart, hiccup, loud_noise, silence

            If what you heard is NOT a baby-origin sound (adult voice, clap, music, bang, ambient noise) →
            label MUST be "silence" even if it was loud.

            Return ONE JSON only (no prose, no code fences):
            {
              "heard": "literal description of the sound",
              "label": "<one of the labels above>",
              "title": "short headline",
              "body": "one-sentence situation",
              "watchText": "ultra-short watch label",
              "subtype": "inferred sub-state or 'routine'",
              "reasoning": "why you picked this label, based on 'heard'",
              "suggestion": "one-line parent action",
              "severity": "LOW|MEDIUM|HIGH"
            }

            If label=silence: title="Quiet", body="No baby sound detected.", watchText="Quiet", subtype="routine", reasoning=heard, suggestion="Keep monitoring", severity="LOW".

            English only. Hedged tone. title<40, body<90, watchText<16, subtype<20, reasoning<80, suggestion<60 chars.

            Recent baby events (context):
            $history

            Classify the audio now:
        """.trimIndent() else """
            너는 청각장애 부모를 돕는 베이비 모니터다. 첨부된 오디오를 듣고 **두 가지**를 해라.

            1) 먼저 "heard" 필드에 **들은 소리를 한 줄로 솔직히 묘사**하라.
               예시: "아기 울음이 약하게", "박수 소리 한 번", "어른이 '안녕' 말함",
               "짧은 기침 한 번", "배경 음악만", "분명한 소리 없음".

            2) 그다음 아래 라벨 중 가장 맞는 것을 고르라(없으면 "silence"):
               baby_cry, baby_laugh, cough, parent_call, first_word,
               burp, fart, hiccup, loud_noise, silence

            아기에게서 난 소리가 아니면(어른 목소리·박수·음악·쾅 소리·일반 소음)
            → 아무리 커도 label="silence" 로.

            JSON 한 개만 출력 (설명·코드펜스 금지):
            {
              "heard": "들은 소리 묘사",
              "label": "<위 라벨 중 하나>",
              "title": "짧은 한국어 제목",
              "body": "한국어 한 문장",
              "watchText": "워치용 초단문",
              "subtype": "세부 추정 또는 '일상'",
              "reasoning": "heard 기반 라벨 선택 이유",
              "suggestion": "부모 행동 제안",
              "severity": "LOW|MEDIUM|HIGH"
            }

            label=silence 일 때: title="조용함", body="아기 소리가 감지되지 않아요.", watchText="조용함", subtype="일상", reasoning=heard, suggestion="계속 모니터링 중이에요", severity="LOW".

            label 외 모든 문자열 100% 한국어. title 15자, body 40자, watchText 8자, subtype 8자, reasoning 40자, suggestion 30자 이내.

            최근 이벤트 (맥락):
            $history

            이제 분류:
        """.trimIndent()
    }

    private fun parseAudioJsonOrFail(
        raw: String,
        latency: Long,
        lang: OutputLang
    ): AlertPayload {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
            .let { text ->
                val start = text.indexOf('{')
                val end = text.lastIndexOf('}')
                if (start >= 0 && end > start) text.substring(start, end + 1) else text
            }
        return runCatching {
            val obj = JSONObject(cleaned)
            AlertPayload(
                title = obj.getString("title"),
                body = obj.getString("body"),
                watchText = obj.getString("watchText"),
                severity = EventSeverity.valueOf(obj.getString("severity")),
                inferenceSource = providerName(),
                subtype = obj.optString("subtype").takeIf { it.isNotBlank() },
                reasoning = obj.optString("reasoning").takeIf { it.isNotBlank() },
                suggestion = obj.optString("suggestion").takeIf { it.isNotBlank() }
            )
        }.getOrElse {
            Log.w(TAG, "Audio JSON parse failed. raw='$raw'")
            onFailureAudio("JSON parse failed", latency, lang).payload
        }
    }

    private fun onFailureAudio(
        reason: String,
        latency: Long,
        lang: OutputLang
    ): InferenceResult {
        lastError = reason
        val errTitle = if (lang == OutputLang.EN) "Gemma 4 audio failed" else "Gemma 4 오디오 실패"
        val errWatch = if (lang == OutputLang.EN) "Error" else "오류"
        return InferenceResult(
            payload = AlertPayload(
                title = errTitle,
                body = reason,
                watchText = errWatch,
                severity = EventSeverity.MEDIUM,
                inferenceSource = "ERROR"
            ),
            latencyMs = latency,
            rawResponse = ""
        )
    }

    private fun extractText(emission: Any?): String {
        if (emission == null) return ""
        if (emission is CharSequence) return emission.toString()
        val clazz = emission.javaClass
        for (field in listOf("text", "content", "response", "message")) {
            runCatching {
                val getter = clazz.methods.firstOrNull {
                    it.name == "get${field.replaceFirstChar { c -> c.uppercase() }}" &&
                        it.parameterCount == 0
                } ?: return@runCatching
                val value = getter.invoke(emission)
                if (value is CharSequence) return value.toString()
            }
        }
        return emission.toString()
    }

    private fun buildPrompt(
        event: SoundEvent,
        lang: OutputLang,
        recentHistory: List<String>
    ): String {
        val severity = when (event.type) {
            BabyEventType.BABY_CRY,
            BabyEventType.PARENT_CALL,
            BabyEventType.LOUD_NOISE -> "HIGH"
            BabyEventType.COUGH,
            BabyEventType.FIRST_WORD -> "MEDIUM"
            BabyEventType.BABY_LAUGH,
            BabyEventType.BURP,
            BabyEventType.FART,
            BabyEventType.HICCUP -> "LOW"
        }
        val confidence = "%.2f".format(event.confidence)
        return if (lang == OutputLang.EN) buildPromptEn(event, severity, confidence, recentHistory)
        else buildPromptKo(event, severity, confidence, recentHistory)
    }

    private fun buildPromptKo(
        event: SoundEvent,
        severity: String,
        confidence: String,
        recentHistory: List<String>
    ): String {
        val label = event.type.labelKo
        val historyBlock = if (recentHistory.isEmpty()) "최근 이벤트 없음"
        else recentHistory.joinToString("\n") { "- $it" }
        return """
            너는 청각장애 부모를 위한 육아 해석 엔진이다.
            단순 번역이 아니라 **아기 상태를 추론**해야 한다.
            오직 JSON 한 개만 출력하라. 설명·주석·코드펜스 금지.

            출력 스키마(반드시 7개 키 모두):
            {"title":"...","body":"...","watchText":"...","subtype":"...","reasoning":"...","suggestion":"...","severity":"LOW|MEDIUM|HIGH"}

            절대 규칙:
            - 모든 값은 100% 한국어. 영어 단어·영문 태그 절대 금지.
            - title 15자, body 40자, watchText 8자, subtype 8자, reasoning 40자, suggestion 30자 이내.
            - "가능성", "같아요", "보여요" 같은 부드러운 표현 사용. 과장·확정 금지.
            - severity 는 LOW/MEDIUM/HIGH 중 하나.

            감지된 이벤트: $label
            신뢰도: $confidence
            권장 severity: $severity

            최근 이벤트 (최신순):
            $historyBlock

            추론 지침:
            - 울음·부름 반복이면 누적 패턴 반영 (배고픔·기저귀·피로).
            - "아기 첫 말 순간"은 감동적·축하 어투. 부모가 그 순간을 놓치지 않게.
            - 트림=수유 긍정 신호. 방귀=기저귀 점검. 딸꾹질=수분 제공.

            예시:
            baby_cry, 3분 내 2회 → {"title":"연속 울음","body":"아기가 3분 사이 3번째 울고 있어요.","watchText":"연속 울음","subtype":"배고픔 가능","reasoning":"단시간 반복 패턴","suggestion":"마지막 수유 시간을 확인하세요","severity":"HIGH"}
            cough, 단발 → {"title":"기침 소리","body":"짧은 기침 소리가 한 번 들렸어요.","watchText":"기침","subtype":"일상","reasoning":"단발성, 반복 없음","suggestion":"호흡 변화가 있는지 살펴보세요","severity":"MEDIUM"}

            이제 "$label" 에 대한 JSON 한 개만 출력:
        """.trimIndent()
    }

    private fun buildPromptEn(
        event: SoundEvent,
        severity: String,
        confidence: String,
        recentHistory: List<String>
    ): String {
        val label = event.type.labelEn
        val historyBlock = if (recentHistory.isEmpty()) "No recent events"
        else recentHistory.joinToString("\n") { "- $it" }
        return """
            You are a baby-care reasoning engine for Deaf parents.
            You must infer the baby's state, not just translate the tag.
            Output exactly one JSON object. No prose, no comments, no code fences.

            Schema (all 7 keys required):
            {"title":"...","body":"...","watchText":"...","subtype":"...","reasoning":"...","suggestion":"...","severity":"LOW|MEDIUM|HIGH"}

            Hard rules:
            - English only values.
            - title <40, body <90, watchText <16, subtype <20, reasoning <80, suggestion <60 chars.
            - Hedged tone ("likely", "may", "possibly"). No alarmism.
            - severity ∈ {LOW, MEDIUM, HIGH}.

            Detected event: $label
            Confidence: $confidence
            Recommended severity: $severity

            Recent events (newest first):
            $historyBlock

            Guidance:
            - Repeated crying/calling → consider cumulative pattern (hunger/diaper/tired).
            - "Baby first words" → warm celebratory tone; help the Deaf parent catch the moment.
            - Burp = positive feeding cue. Fart = diaper check. Hiccup = small sips.

            Examples:
            baby_cry with 2 recent cries → {"title":"Repeated crying","body":"Your baby has cried 3 times in 3 minutes.","watchText":"Crying x3","subtype":"hunger likely","reasoning":"Short-interval pattern","suggestion":"Check last feeding time","severity":"HIGH"}
            cough, isolated → {"title":"Cough detected","body":"A single cough was heard.","watchText":"Cough","subtype":"routine","reasoning":"Isolated, no repeats","suggestion":"Watch for breathing changes","severity":"MEDIUM"}

            Now output ONE JSON object for "$label":
        """.trimIndent()
    }

    private fun parseJsonOrFail(
        raw: String,
        event: SoundEvent,
        latency: Long,
        lang: OutputLang = OutputLang.KO
    ): AlertPayload {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
            .let { text ->
                val start = text.indexOf('{')
                val end = text.lastIndexOf('}')
                if (start >= 0 && end > start) text.substring(start, end + 1) else text
            }

        return runCatching {
            val obj = JSONObject(cleaned)
            AlertPayload(
                title = obj.getString("title"),
                body = obj.getString("body"),
                watchText = obj.getString("watchText"),
                severity = EventSeverity.valueOf(obj.getString("severity")),
                inferenceSource = providerName(),
                subtype = obj.optString("subtype").takeIf { it.isNotBlank() },
                reasoning = obj.optString("reasoning").takeIf { it.isNotBlank() },
                suggestion = obj.optString("suggestion").takeIf { it.isNotBlank() }
            )
        }.getOrElse {
            Log.w(TAG, "JSON parse failed. raw='$raw'")
            onFailure("JSON parse failed", event, latency, lang).payload
        }
    }

    private fun onFailure(
        reason: String,
        event: SoundEvent,
        latency: Long,
        lang: OutputLang = OutputLang.KO
    ): InferenceResult {
        lastError = reason
        val errTitle = if (lang == OutputLang.EN) "Gemma 4 failed" else "Gemma 4 동작 실패"
        val errWatch = if (lang == OutputLang.EN) "Error" else "오류"
        if (judgeMode || fallback == null) {
            return InferenceResult(
                payload = AlertPayload(
                    title = errTitle,
                    body = reason,
                    watchText = errWatch,
                    severity = EventSeverity.MEDIUM,
                    inferenceSource = "ERROR"
                ),
                latencyMs = latency,
                rawResponse = ""
            )
        }
        return InferenceResult(
            payload = fallback.runCatching {
                kotlinx.coroutines.runBlocking { summarize(event, lang) }
            }.getOrElse {
                AlertPayload(errTitle, reason, errWatch, EventSeverity.LOW, "ERROR")
            }.copy(inferenceSource = "Fake fallback"),
            latencyMs = latency,
            rawResponse = ""
        )
    }

    fun close() {
        runCatching { engine?.close() }
        engine = null
    }

    companion object {
        private const val TAG = "GemmaLiteRt"
        const val MODEL_SUBDIR = "models"
        const val MODEL_FILE = "gemma4-e2b.litertlm"

        fun defaultModelPath(context: Context): String =
            File(context.getExternalFilesDir(null), "$MODEL_SUBDIR/$MODEL_FILE").absolutePath

        /**
         * Gemma Audio babble 감지 프롬프트.
         * 아기 옹알이 오디오를 듣고 mama/baba/unknown 중 하나만 출력하도록 유도.
         */
        private const val BABBLE_DETECT_PROMPT = """Listen to this baby babbling audio clip.
Determine if the baby is trying to say a specific word.
Answer with EXACTLY ONE word only:
- "mama" if the baby sounds like saying mama/엄마/맘마
- "baba" if the baby sounds like saying papa/baba/아빠/빠빠
- "unknown" if unclear or just random babbling
ONE WORD ONLY:"""
    }
}
