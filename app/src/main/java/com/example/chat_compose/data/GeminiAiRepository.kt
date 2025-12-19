package com.example.chat_compose.data

import android.graphics.BitmapFactory
import com.example.chat_compose.model.Message
import com.google.firebase.Firebase
import com.google.firebase.vertexai.type.content
import com.google.firebase.vertexai.type.generationConfig
import com.google.firebase.vertexai.vertexAI

class GeminiAiRepository {

    // Model multimodal (Text + Image + Audio)
    private val model = Firebase.vertexAI.generativeModel(
        modelName = "gemini-2.5-flash",
        generationConfig = generationConfig {
            temperature = 0.4f
            maxOutputTokens = 1024
        },
        systemInstruction = content {
            text(
                """
                Bạn là trợ lý AI thông minh tên là HuyAn AI.
                - Trả lời ngắn gọn, thân thiện, dùng tiếng Việt.
                - Nếu là ảnh: mô tả hoặc trả lời câu hỏi về ảnh.
                - Nếu là âm thanh: tóm tắt hoặc trả lời nội dung âm thanh.
                """.trimIndent()
            )
        }
    )

    /**
     * Trả lời hỗ trợ Text + Ảnh + Audio
     */
    suspend fun replyWithMedia(
        history: List<Message>,
        userText: String,
        imageBytes: ByteArray? = null,
        audioBytes: ByteArray? = null
    ): String {
        // Convert history to Vertex AI contents (text-only) to avoid token waste
        val histContents = history.takeLast(10).mapNotNull { m ->
            val t = m.text.trim()
            if (t.isBlank()) return@mapNotNull null
            val role = if (m.fromId == ChatRepository.AI_BOT_ID) "model" else "user"
            content(role = role) { text(t) }
        }

        return try {
            val chat = model.startChat(history = histContents)

            val inputContent = content {
                if (userText.isNotBlank()) text(userText)
                else if (imageBytes == null && audioBytes == null) text("Phân tích nội dung này giúp tôi.")

                if (imageBytes != null) {
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    if (bitmap != null) image(bitmap)
                }

                if (audioBytes != null) {
                    // Firebase VertexAI inlineData signature: inlineData(data, mimeType)
                    inlineData(audioBytes, "audio/mp3")
                }
            }

            val response = chat.sendMessage(inputContent)
            response.text?.trim() ?: "Em nghe/nhìn nhưng chưa hiểu lắm..."
        } catch (e: Exception) {
            e.printStackTrace()
            "Lỗi xử lý đa phương tiện: ${e.localizedMessage}"
        }
    }

    suspend fun reply(history: List<Message>, userText: String): String {
        return replyWithMedia(history, userText, null, null)
    }
}
