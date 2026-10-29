package com.memeget.nativeapp.storage

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class ImgflipLinkResolverTest {
    @Test
    fun generatorShareSavesFullSizeTemplateInsteadOfToolbarOrTrendingImages() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/memetemplate/129385783" -> MockResponse().setBody("""
                        <img src="https://i.imgflip.com/2x0jn0.png" alt="Add Image">
                        <img class="im um" src="//i.imgflip.com/2/2516pj.jpg">
                        <img id="mtm-img" src="//i.imgflip.com/2516pj.jpg" alt="Do you see">
                        <img src="//i.imgflip.com/2/b4hz4f.jpg" alt="Trending">
                    """.trimIndent())
                    "/2516pj.jpg" -> MockResponse().setHeader("Content-Type", "image/jpeg")
                        .setBody("full-size template bytes")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val localUrl = server.url(chain.request().url.encodedPath)
                chain.proceed(chain.request().newBuilder().url(localUrl).build())
            }.build()
            val cache = Files.createTempDirectory("imgflip-link-test").toFile()
            try {
                val ref = LinkResolver.resolveMediaRef(
                    "https://imgflip.com/memegenerator/129385783/Do-you-see", client
                )
                assertEquals("https://i.imgflip.com/2516pj.jpg", ref.url)
                val saved = LinkResolver.download(ref, cache, client)
                assertEquals("imgflip_129385783.jpg", saved.fileName)
                assertEquals("image/jpeg", saved.mimeType)
                assertEquals("full-size template bytes", saved.file.readText())
                assertEquals("/memetemplate/129385783", server.takeRequest().path)
                assertEquals("/2516pj.jpg", server.takeRequest().path)
                assertEquals(2, server.requestCount)
            } finally {
                cache.listFiles()?.forEach { it.delete() }
                cache.delete()
            }
        }
    }

    @Test
    fun generatorWithoutTemplateImageDoesNotSaveAnotherImage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<img src='https://i.imgflip.com/other.jpg'>"))
            server.start()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/memetemplate/129385783")).build())
            }.build()
            try {
                LinkResolver.resolveMediaRef("https://imgflip.com/memegenerator/129385783/Do-you-see", client)
                throw AssertionError("Missing template should not resolve to an unrelated image")
            } catch (error: IOException) {
                assertTrue(error.message.orEmpty().contains("No template image"))
            }
            assertEquals(1, server.requestCount)
        }
    }
}
