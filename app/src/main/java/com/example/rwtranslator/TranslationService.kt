package com.example.rwtranslator

import com.google.gson.JsonParser
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.logging.Logger

interface TranslationService {
    @GET("translate_a/single")
    suspend fun translate(
        @Query("client") client: String = "gtx",
        @Query("sl") sourceLanguage: String,
        @Query("tl") targetLanguage: String,
        @Query("dt") dt: String = "t",
        @Query("q") text: String
    ): String

    companion object {
        private const val BASE_URL = "https://translate.googleapis.com/"

        fun create(): TranslationService {
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(ScalarsConverterFactory.create())
                .build()
                .create(TranslationService::class.java)
        }
    }
}

class Translator {
    private val service = TranslationService.create()
    private val logger = Logger.getLogger("TranslationService")

    suspend fun translate(text: String, src: String, dest: String): String {
        val response = service.translate(sourceLanguage = src, targetLanguage = dest, text = text)

        return try {
            val jsonArray = JsonParser.parseString(response).asJsonArray
            val translationArray = jsonArray[0].asJsonArray
            translationArray.joinToString("") { it.asJsonArray[0].asString }
        } catch (e: Exception) {
            logger.warning("Error parsing translation response: ${e.message}")
            "Translation error: ${e.message}"
        }
    }
}