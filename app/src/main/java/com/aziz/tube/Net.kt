package com.aziz.tube

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// One client for YouTube and the pictures, with YouTube's cookies kept for the session (as a browser keeps them).
object Net {
    private val jar = object : CookieJar {
        private val store = HashMap<String, MutableList<Cookie>>()
        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = store.getOrPut(url.topPrivateDomain() ?: url.host) { mutableListOf() }
            for (c in cookies) {
                list.removeAll { it.name == c.name }
                list.add(c)
            }
        }
        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store[url.topPrivateDomain() ?: url.host]?.filter { it.matches(url) } ?: emptyList()
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    const val BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    fun get(url: String, agent: String = BROWSER): String? = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", agent).header("Accept-Language", "en-US,en;q=0.9").build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    fun post(url: String, json: String, headers: Map<String, String>): Pair<Int, String?> = try {
        val b = Request.Builder().url(url).post(json.toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use { it.code to it.body?.string() }
    } catch (e: Exception) {
        0 to null
    }
}
