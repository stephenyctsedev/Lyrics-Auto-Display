package com.stephen.autolyrics.data

import com.stephen.autolyrics.lyrics.TrackKey
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class LrclibSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: LrclibSource

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        source = LrclibSource(
            baseUrl = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient.Builder()
                .callTimeout(1, TimeUnit.SECONDS)
                .build(),
            userAgent = "AutoLyrics/0.1.0 (https://github.com/stephenyctsedev/auto-lyrics)",
        )
    }

    @After fun tearDown() = server.shutdown()

    private val syncedBody = """
        {"id":1,"trackName":"Song","artistName":"Artist","albumName":"Album",
         "duration":100.0,"instrumental":false,
         "plainLyrics":"line one\nline two",
         "syncedLyrics":"[00:01.00]line one\n[00:05.00]line two"}
    """.trimIndent()

    @Test
    fun `returns Found with parsed synced lyrics on 200`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(syncedBody))
        val result = source.lookup(TrackKey("Song", "Artist"))
        assertTrue(result is LyricsResult.Found)
        val found = result as LyricsResult.Found
        assertEquals(2, found.lyrics.lines.size)
        assertEquals("line one", found.lyrics.lines[0].text)
        assertEquals(LyricsOrigin.NETWORK, found.origin)
    }

    @Test
    fun `sends artist and track as query params with the user agent`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(syncedBody))
        source.lookup(TrackKey("Song Name", "Artist Name"))
        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals("/api/get", url.encodedPath)
        assertEquals("Song Name", url.queryParameter("track_name"))
        assertEquals("Artist Name", url.queryParameter("artist_name"))
        assertTrue(request.getHeader("User-Agent")!!.contains("AutoLyrics"))
    }

    @Test
    fun `returns NotFound on 404 TrackNotFound`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody(
            """{"message":"Failed to find specified track","name":"TrackNotFound","statusCode":404}"""
        ))
        assertEquals(LyricsResult.NotFound, source.lookup(TrackKey("Song", "Artist")))
    }

    @Test
    fun `returns Error not NotFound on 503 ServerOverloaded`() = runBlocking {
        // 關鍵：503 唔可以當成「冇呢首歌」，否則會被 negative cache 封 7 日
        server.enqueue(MockResponse().setResponseCode(503).setBody(
            """{"message":"The server is busy, please retry in a moment","name":"ServerOverloaded","statusCode":503}"""
        ))
        assertTrue(source.lookup(TrackKey("Song", "Artist")) is LyricsResult.Error)
    }

    @Test
    fun `returns Error on 500`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertTrue(source.lookup(TrackKey("Song", "Artist")) is LyricsResult.Error)
    }

    @Test
    fun `returns Error on malformed json`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{not json"))
        assertTrue(source.lookup(TrackKey("Song", "Artist")) is LyricsResult.Error)
    }

    @Test
    fun `returns NotFound when syncedLyrics is null`() = runBlocking {
        // 只有純文字歌詞 → 第一版當搵唔到（冇時間戳跟唔到秒數）
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"id":1,"trackName":"Song","artistName":"Artist","instrumental":false,
                "plainLyrics":"line one","syncedLyrics":null}"""
        ))
        assertEquals(LyricsResult.NotFound, source.lookup(TrackKey("Song", "Artist")))
    }

    @Test
    fun `returns NotFound for instrumental tracks`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"id":1,"trackName":"Song","artistName":"Artist","instrumental":true,
                "plainLyrics":null,"syncedLyrics":null}"""
        ))
        assertEquals(LyricsResult.NotFound, source.lookup(TrackKey("Song", "Artist")))
    }

    @Test
    fun `returns NotFound when syncedLyrics parses to zero lines`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"id":1,"trackName":"Song","artistName":"Artist","instrumental":false,
                "plainLyrics":"x","syncedLyrics":"no timestamps here"}"""
        ))
        assertEquals(LyricsResult.NotFound, source.lookup(TrackKey("Song", "Artist")))
    }

    @Test
    fun `returns Error on timeout`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(
            okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        assertTrue(source.lookup(TrackKey("Song", "Artist")) is LyricsResult.Error)
    }
}
