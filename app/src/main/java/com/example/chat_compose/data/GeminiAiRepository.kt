package com.example.chat_compose.data

import com.example.chat_compose.model.Message
import com.google.firebase.Firebase
import com.google.firebase.vertexai.type.content
import com.google.firebase.vertexai.type.generationConfig
import com.google.firebase.vertexai.vertexAI

class GeminiAiRepository {

    // 1. Khởi tạo model từ Firebase.vertexAI
    private val model = Firebase.vertexAI.generativeModel(
        // 2. Sử dụng tên model chính xác hiện tại
        modelName = "gemini-2.5-flash",

        generationConfig = generationConfig {
            temperature = 0.4f
            maxOutputTokens = 1024
        },
        // 3. System Instruction giúp định hình tính cách AI
        systemInstruction = content {
            text(
                """
                Bạn là trợ lý AI trong ứng dụng chat.
                Trả lời ngắn gọn, đúng trọng tâm, tiếng Việt.
                Nếu thiếu dữ kiện thì hỏi lại 1 câu ngắn.
                """.trimIndent()
            )
        }
    )

    /**
     * history: danh sách message cũ để AI hiểu ngữ cảnh
     * userText: câu hỏi mới của user
     */
    suspend fun reply(history: List<Message>, userText: String): String {
        // 4. Chuyển đổi List<Message> của app thành List<Content> của Gemini
        val histContents = history
            .takeLast(20) // Chỉ lấy 20 tin gần nhất để tiết kiệm token
            .mapNotNull { m ->
                val t = m.text.trim()
                if (t.isBlank()) return@mapNotNull null

                // QUAN TRỌNG: Cần khớp ID này với ID bạn gán cho Bot trong code ChatViewModel/Repository
                // Ví dụ: trong ChatRepository bạn quy định botId là "AI_BOT"
                val role = if (m.fromId == "AI_BOT") "model" else "user"

                content(role = role) { text(t) }
            }

        return try {
            // 5. Bắt đầu phiên chat với lịch sử
            val chat = model.startChat(history = histContents)

            // 6. Gửi tin nhắn mới và đợi phản hồi
            val response = chat.sendMessage(userText)

            response.text?.trim() ?: "Xin lỗi, tôi không thể trả lời lúc này."
        } catch (e: Exception) {
            e.printStackTrace()
            "Lỗi kết nối AI: ${e.localizedMessage}"
        }
    }
}