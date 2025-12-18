package com.example.chat_compose.data

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun startRecording() {
        // Tạo file tạm trong cache
        outputFile = File(context.cacheDir, "audiorecord.mp3")

        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(FileOutputStream(outputFile).fd)

            try {
                prepare()
                start()
            } catch (e: Exception) {
                Log.e("AudioRecorder", "Lỗi ghi âm: ${e.message}")
            }
        }
    }

    fun stopRecording(): File? {
        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            outputFile
        } catch (e: Exception) {
            null
        }
    }
}