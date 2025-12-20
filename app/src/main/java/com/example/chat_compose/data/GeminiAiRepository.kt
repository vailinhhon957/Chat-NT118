package com.example.chat_compose.data

import android.graphics.BitmapFactory
import com.example.chat_compose.model.Message
import com.google.firebase.Firebase
import com.google.firebase.vertexai.type.content
import com.google.firebase.vertexai.type.generationConfig
import com.google.firebase.vertexai.vertexAI

class GeminiAiRepository {

    private companion object {
        // Giới hạn để tránh đẩy quá nhiều tokens
        const val MAX_HISTORY_MESSAGES = 10
        const val MAX_HISTORY_MESSAGES_RETRY = 6
        const val MAX_CHARS_PER_HISTORY_MESSAGE = 350   // cắt mỗi tin
        const val MAX_TOTAL_HISTORY_CHARS = 1800        // cắt tổng lịch sử
    }

    // Model multimodal (Text + Image + Audio)
    private val model = Firebase.vertexAI.generativeModel(
        modelName = "gemini-2.5-flash",
        generationConfig = generationConfig {
            temperature = 0.4f
            // tăng lên để đỡ bị cắt giữa chừng, nhưng vẫn nên ép trả lời ngắn
            maxOutputTokens = 2048
        },
        systemInstruction = content {
            text(
                """
                Bạn là trợ lý AI thông minh tên là HuyAn AI.
                - Luôn trả lời TIẾNG VIỆT.
                - Trả lời ngắn gọn, rõ ràng, ưu tiên gạch đầu dòng.
                - Nếu câu hỏi có thể dài: tóm tắt trước, rồi trả lời.
                - Nếu là ảnh: mô tả hoặc trả lời câu hỏi về ảnh.
                - Nếu là âm thanh: tóm tắt hoặc trả lời nội dung âm thanh.
                """.trimIndent()
            )
        }
    )

    private fun buildHistoryContents(history: List<Message>, limit: Int): List<com.google.firebase.vertexai.type.Content> {
        val picked = history.takeLast(limit)

        val trimmed = mutableListOf<com.google.firebase.vertexai.type.Content>()
        var total = 0

        for (m in picked) {
            val t0 = m.text.trim()
            if (t0.isBlank()) continue

            val t = if (t0.length > MAX_CHARS_PER_HISTORY_MESSAGE) t0.take(MAX_CHARS_PER_HISTORY_MESSAGE) else t0
            if (total + t.length > MAX_TOTAL_HISTORY_CHARS) break
            total += t.length

            val role = if (m.fromId == ChatRepository.AI_BOT_ID) "model" else "user"
            trimmed += content(role = role) { text(t) }
        }
        return trimmed
    }

    /**
     * Trả lời hỗ trợ Text + Ảnh + Audio
     */
    suspend fun replyWithMedia(
        history: List<Message>,
        userText: String,
        imageBytes: ByteArray? = null,
        audioBytes: ByteArray? = null
    ): String {
        val histContents = buildHistoryContents(history, MAX_HISTORY_MESSAGES)

        fun buildInput(prompt: String) = content {
            val finalText =
                if (prompt.isNotBlank()) {
                    // ✅ Ép model trả lời ngắn để tránh MAX_TOKENS
                    "$prompt\n\nYêu cầu: trả lời tối đa ~120 từ, 3-6 gạch đầu dòng. Không lan man."
                } else {
                    "Phân tích nội dung này giúp tôi.\n\nYêu cầu: trả lời tối đa ~120 từ, gạch đầu dòng."
                }

            text(finalText)

            if (imageBytes != null) {
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                if (bitmap != null) image(bitmap)
            }

            if (audioBytes != null) {
                // đổi mimeType nếu bạn ghi âm m4a/aac
                inlineData(audioBytes, "audio/mp3")
            }
        }

        // ---- Lần gọi chính ----
        try {
            val chat = model.startChat(history = histContents)
            val response = chat.sendMessage(buildInput(userText))
            return response.text?.trim() ?: "Mình chưa hiểu lắm, bạn nói rõ hơn được không?"
        } catch (e: Exception) {
            val msg = e.localizedMessage.orEmpty()

            // ---- Nếu bị MAX_TOKENS: retry 1 lần với lịch sử ít hơn + prompt siêu ngắn ----
            if (msg.contains("MAX_TOKENS", ignoreCase = true)) {
                return try {
                    val retryHistory = buildHistoryContents(history, MAX_HISTORY_MESSAGES_RETRY)
                    val chat2 = model.startChat(history = retryHistory)

                    val shortPrompt =
                        if (userText.isBlank()) "Trả lời ngắn gọn giúp tôi."
                        else userText

                    val response2 = chat2.sendMessage(
                        buildInput(
                            "$shortPrompt\n\nTrả lời CỰC NGẮN: tối đa 60 từ, 3 gạch đầu dòng."
                        )
                    )
                    response2.text?.trim()
                        ?: "Mình bị giới hạn độ dài trả lời. Bạn hỏi lại ngắn hơn giúp mình nhé."
                } catch (e2: Exception) {
                    "Mình bị giới hạn độ dài trả lời. Bạn thử hỏi ngắn hơn (1–2 câu) nhé."
                }
            }

            e.printStackTrace()
            return "Lỗi AI: ${e.localizedMessage}"
        }
    }

    suspend fun reply(history: List<Message>, userText: String): String {
        return replyWithMedia(history, userText, null, null)
    }
    suspend fun analyzeSentiment(text: String): String {
        val prompt = """
            Phân tích cảm xúc của câu văn sau: "$text".
            Hãy trả về duy nhất 1 từ khóa chính xác nhất trong danh sách sau:
            [HAPPY, SAD, ANGRY, LOVE, NEUTRAL]
            
            Ví dụ:
            - "Anh yêu em" -> LOVE
            - "Mệt quá đi mất" -> SAD
            - "Cười chết mất" -> HAPPY
            - "Đồ tồi" -> ANGRY
            - "Đang làm gì đó" -> NEUTRAL
            
            Chỉ trả về từ khóa, không giải thích.
        """.trimIndent()

        return try {
            val response = model.generateContent(prompt)
            // Chuẩn hóa text trả về (viết hoa, bỏ khoảng trắng thừa)
            response.text?.trim()?.uppercase() ?: "NEUTRAL"
        } catch (e: Exception) {
            e.printStackTrace()
            "NEUTRAL"
        }
    }
}
