package com.example.chat_compose.call

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.tasks.await

class SensitiveContentDetector {

    // Các từ khóa nghi ngờ nhạy cảm (Bạn có thể thêm tùy chỉnh)
    private val sensitiveKeywords = listOf(
        "Swimwear", "Undergarment", "Brassiere", "Underpants", "Bikini",
        "Lingerie", "Barechested", "Trunk","Smile" // Trunk: thân mình trần
    )

    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)

    suspend fun isSensitive(bitmap: Bitmap): Boolean {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val labels = labeler.process(image).await()

            for (label in labels) {
                val text = label.text
                val confidence = label.confidence
                Log.d("AI_CHECK", "Label: $text ($confidence)")

                // Nếu AI tìm thấy từ khóa nhạy cảm với độ tin cậy > 70%
                if (sensitiveKeywords.contains(text) && confidence > 0.7f) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}