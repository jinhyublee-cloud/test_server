package com.mydaylog.app.ask

import android.content.Context
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.mydaylog.app.Prefs
import com.mydaylog.app.data.Repo
import com.mydaylog.app.insight.Insights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

enum class AiState(val label: String) {
    CHECKING("확인 중"), READY("기기 AI 사용 중"), DOWNLOADABLE("기기 AI 모델 받기 필요"),
    DOWNLOADING("기기 AI 모델 받는 중"), UNAVAILABLE("이 폰에서 기기 AI를 쓸 수 없음"), OFF("기기 AI 꺼짐"),
}

/**
 * 폰에 내장된 Gemini Nano (Android AICore) 연결
 * - 추론은 AICore 안에서 폰 안에서만 실행 (이 앱은 인터넷 권한이 없음)
 * - 모델 파일은 AICore(시스템)가 관리. 이 앱의 기록은 모델 다운로드와 무관
 * - 쓸 수 없으면 코드가 계산한 기본 답변만 보여 줌
 */
object Nano {
    @Volatile private var model: GenerativeModel? = null

    private fun client(): GenerativeModel = model ?: synchronized(this) { model ?: Generation.getClient().also { model = it } }

    suspend fun state(ctx: Context): AiState {
        if (!Prefs.askAi(ctx)) return AiState.OFF
        return when (runCatching { client().checkStatus() }.getOrDefault(FeatureStatus.UNAVAILABLE)) {
            FeatureStatus.AVAILABLE -> AiState.READY
            FeatureStatus.DOWNLOADABLE -> AiState.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> AiState.DOWNLOADING
            else -> AiState.UNAVAILABLE
        }
    }

    fun download(): Flow<DownloadStatus> = client().download()

    /** 근거만 주고 문장을 다듬게 함. 실패·시간 초과면 null */
    suspend fun phrase(prompt: String): String? = withTimeoutOrNull(25_000) {
        runCatching {
            client().generateContent(generateContentRequest(TextPart(prompt)) {
                temperature = 0.2f
                topK = 10
                maxOutputTokens = 300
            }).candidates.firstOrNull()?.text?.trim()
        }.getOrNull()
    }

    fun close() {
        runCatching { model?.close() }
        model = null
    }
}

data class AskResult(val facts: Facts, val text: String, val byAi: Boolean)

/** 질문 → 해석 → 암호화된 기록에서 찾기 → 계산 → (가능하면) 기기 AI로 문장 다듬기 */
object Ask {
    suspend fun run(ctx: Context, question: String, useAi: Boolean): AskResult {
        val today = LocalDate.now()
        val facts = withContext(Dispatchers.IO) {
            val q = QueryParser.parse(question, today)
            // 검색어가 기록에 없어 빠질 때를 대비해 기본 기간까지 함께 읽어 둠
            val alt = QueryParser.withKeywords(q, emptyList(), today)
            val from = minOf(q.from, alt.from)
            val to = maxOf(q.to, alt.to)
            val entries = if (q.topic == Topic.DEADLINE) emptyList() else Repo.entriesBetween(ctx, from, to)
            val plans = if (q.topic == Topic.PLAN) Insights.plans(ctx).filter { it.status != Insights.DELETED }.map { it.plan } else emptyList()
            val dls = if (q.topic == Topic.PLAN || q.topic == Topic.DEADLINE)
                Insights.deadlines(ctx).filter { it.open }.map { it.dl } else emptyList()
            AskEngine.answer(q, entries, plans, dls, today, com.mydaylog.app.data.MoneyExclude.excludedFn(ctx))
        }
        val base = (listOf(facts.headline) + facts.details).joinToString("\n")
        if (!useAi || !facts.found) return AskResult(facts, base, false)
        val ai = Nano.phrase(AskEngine.prompt(facts))
        return if (AskEngine.aiAnswerOk(ai, facts)) AskResult(facts, ai!!, true) else AskResult(facts, base, false)
    }
}
