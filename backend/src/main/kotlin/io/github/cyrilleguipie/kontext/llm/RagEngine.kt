package io.github.cyrilleguipie.kontext.llm

import arrow.core.raise.either
import io.github.cyrilleguipie.kontext.config.LlmConfig
import io.github.cyrilleguipie.kontext.domain.KontextError
import io.github.cyrilleguipie.kontext.domain.SearchHit
import io.github.cyrilleguipie.kontext.store.HybridSearch
import kotlinx.coroutines.flow.Flow

data class RagAnswer(
    val tokens: Flow<String>,
    val sources: List<SearchHit>,
)

/** Retrieves relevant chunks and streams a response grounded in those chunks. */
class RagEngine(
    private val config: LlmConfig,
    private val embeddingClient: EmbeddingClient,
    private val hybridSearch: HybridSearch,
    private val chatClient: ChatClient,
) {
    suspend fun answer(
        question: String,
        history: List<ChatMessage> = emptyList(),
    ): RagAnswer {
        val embedding =
            either {
                with(embeddingClient) { embed(question) }
            }.fold(
                ifLeft = { throw KontextError.Exception(it) },
                ifRight = { it },
            )
        val sources = hybridSearch.search(question, embedding, config.maxContextChunks)
        val context =
            sources.mapIndexed { index, hit ->
                "[${index + 1}] ${hit.title}\n${hit.content}\nSource: ${hit.url}"
            }.joinToString("\n\n")
        val systemPrompt =
            if (sources.isEmpty()) {
                "You are Kontext, an engineering knowledge assistant. No matching documents were found. " +
                    "Say that you cannot find supporting information and do not invent an answer."
            } else {
                "You are Kontext, an engineering knowledge assistant. Answer using only the supplied sources. " +
                    "Cite claims with source numbers such as [1]. " +
                    "If the sources do not answer the question, say so.\n\n" +
                    "Sources:\n$context"
            }
        val messages =
            listOf(ChatMessage("system", systemPrompt)) +
                history.takeLast(20).filter { it.role == "user" || it.role == "assistant" } +
                ChatMessage("user", question)
        return RagAnswer(chatClient.streamChat(messages), sources)
    }
}
