package nz.personal.checkpointwatch.collect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CollectorMessage.decode] is the only part of the collector that can run on the JVM, so it
 * carries the tests for the whole JS -> Kotlin channel. It must never throw: anything the page
 * can push through the bridge is untrusted input.
 */
class CollectorMessageTest {

    @Test
    fun decode_jsonMessage_returnsBody() {
        val message = CollectorMessage.decode("""{"t":"json","body":"{\"post_id\":\"1\"}"}""")

        assertEquals(CollectorMessage.JsonChunk("""{"post_id":"1"}"""), message)
    }

    @Test
    fun decode_jsonMessage_saysWhetherItCameFromTheFeed() {
        // The whole starvation signal: on a VPN the initial script blocks arrive and the feed
        // never answers, so "a chunk came in" is not the same fact as "the feed is working".
        val feed = CollectorMessage.decode("""{"t":"json","body":"{}","src":"graphql"}""")
        val script = CollectorMessage.decode("""{"t":"json","body":"{}","src":"script"}""")

        assertEquals(CollectorMessage.JsonChunk("{}", fromFeed = true), feed)
        assertEquals(CollectorMessage.JsonChunk("{}", fromFeed = false), script)
    }

    @Test
    fun decode_jsonMessageWithoutSource_isNotCountedAsFeedTraffic() {
        assertEquals(CollectorMessage.JsonChunk("{}", fromFeed = false), CollectorMessage.decode("""{"t":"json","body":"{}"}"""))
        assertEquals(
            CollectorMessage.JsonChunk("{}", fromFeed = false),
            CollectorMessage.decode("""{"t":"json","body":"{}","src":7}"""),
        )
    }

    @Test
    fun decode_pluginMessage_returnsPosts() {
        val raw = """
            {"t":"plugin","posts":[
              {"utime":1789677637,"link":"https://www.facebook.com/CheckpointNZ/posts/pfbid02","text":"CHECKPOINT - Cavendish Drive","image":"https://scontent.test.fbcdn.net/photo.jpg"},
              {"utime":1789600000,"link":null,"text":"HAZARD - SH1","image":null}
            ]}
        """.trimIndent()

        val message = CollectorMessage.decode(raw)

        assertEquals(
            CollectorMessage.Plugin(
                listOf(
                    PluginPost(
                        utime = 1789677637,
                        link = "https://www.facebook.com/CheckpointNZ/posts/pfbid02",
                        text = "CHECKPOINT - Cavendish Drive",
                        image = "https://scontent.test.fbcdn.net/photo.jpg",
                    ),
                    PluginPost(utime = 1789600000, link = null, text = "HAZARD - SH1", image = null),
                ),
            ),
            message,
        )
    }

    @Test
    fun decode_pluginMessage_skipsEntriesWithoutTextOrTime() {
        val raw = """
            {"t":"plugin","posts":[
              {"utime":1789677637},
              {"text":"no time at all"},
              {"utime":"1789677637","text":"a time that is not a number"},
              "not an object",
              {"utime":1789677637,"text":"  "},
              {"utime":1789677637,"text":"CHECKPOINT - Trig Road"}
            ]}
        """.trimIndent()

        val message = CollectorMessage.decode(raw)

        assertEquals(
            CollectorMessage.Plugin(
                listOf(PluginPost(utime = 1789677637, link = null, text = "CHECKPOINT - Trig Road", image = null)),
            ),
            message,
        )
    }

    @Test
    fun decode_pluginMessage_withoutPostsArray_returnsNull() {
        assertNull(CollectorMessage.decode("""{"t":"plugin"}"""))
    }

    @Test
    fun decode_pluginMessage_withEmptyPostsArray_returnsEmptyList() {
        assertEquals(CollectorMessage.Plugin(emptyList()), CollectorMessage.decode("""{"t":"plugin","posts":[]}"""))
    }

    @Test
    fun decode_jsonMessageWithoutBody_returnsNull() {
        assertNull(CollectorMessage.decode("""{"t":"json"}"""))
    }

    @Test
    fun decode_jsonMessageWithNonStringBody_returnsNull() {
        assertNull(CollectorMessage.decode("""{"t":"json","body":{"nested":true}}"""))
    }

    @Test
    fun decode_domMessage_returnsPosts() {
        val raw = """
            {"t":"dom","posts":[
              {"text":"CHECKPOINT - Lincoln Road","age":"22m","link":"https://www.facebook.com/CheckpointNZ/posts/1"},
              {"text":"CRASH - Trig Road","age":"2h","link":null}
            ]}
        """.trimIndent()

        val message = CollectorMessage.decode(raw)

        assertEquals(
            CollectorMessage.Dom(
                listOf(
                    DomPost(
                        text = "CHECKPOINT - Lincoln Road",
                        age = "22m",
                        link = "https://www.facebook.com/CheckpointNZ/posts/1",
                    ),
                    DomPost(text = "CRASH - Trig Road", age = "2h", link = null),
                ),
            ),
            message,
        )
    }

    @Test
    fun decode_domMessage_skipsEntriesWithoutText_andToleratesMissingFields() {
        val raw = """
            {"t":"dom","posts":[
              {"age":"22m"},
              {"text":"  "},
              "not an object",
              {"text":"CHECKPOINT - Trig Road"}
            ]}
        """.trimIndent()

        val message = CollectorMessage.decode(raw)

        assertEquals(
            CollectorMessage.Dom(listOf(DomPost(text = "CHECKPOINT - Trig Road", age = "", link = null))),
            message,
        )
    }

    @Test
    fun decode_domMessage_withoutPostsArray_returnsNull() {
        assertNull(CollectorMessage.decode("""{"t":"dom"}"""))
    }

    @Test
    fun decode_domMessage_withEmptyPostsArray_returnsEmptyList() {
        assertEquals(CollectorMessage.Dom(emptyList()), CollectorMessage.decode("""{"t":"dom","posts":[]}"""))
    }

    @Test
    fun decode_endMessage_mapsEveryReason() {
        EndReason.entries.forEach { reason ->
            assertEquals(
                CollectorMessage.End(reason),
                CollectorMessage.decode("""{"t":"end","reason":"${reason.name}"}"""),
            )
        }
    }

    @Test
    fun decode_endMessage_unknownReason_fallsBackToNoMorePosts() {
        assertEquals(
            CollectorMessage.End(EndReason.NO_MORE_POSTS),
            CollectorMessage.decode("""{"t":"end","reason":"WHO_KNOWS"}"""),
        )
    }

    @Test
    fun decode_endMessage_missingReason_fallsBackToNoMorePosts() {
        assertEquals(CollectorMessage.End(EndReason.NO_MORE_POSTS), CollectorMessage.decode("""{"t":"end"}"""))
    }

    @Test
    fun decode_endMessage_lowerCaseReason_isAccepted() {
        assertEquals(
            CollectorMessage.End(EndReason.LOGIN_WALL),
            CollectorMessage.decode("""{"t":"end","reason":"login_wall"}"""),
        )
    }

    @Test
    fun decode_diagMessage_returnsBody() {
        val body = """{"install":{"href":"https://www.facebook.com/CheckpointNZ"},"rounds":[],"end":"LOGIN_WALL"}"""

        val message = CollectorMessage.decode("""{"t":"diag","body":${quoted(body)}}""")

        assertEquals(CollectorMessage.Diag(body), message)
    }

    @Test
    fun decode_diagMessageWithoutBody_returnsNull() {
        assertNull(CollectorMessage.decode("""{"t":"diag"}"""))
        assertNull(CollectorMessage.decode("""{"t":"diag","body":{"rounds":[]}}"""))
    }

    @Test
    fun decode_unknownType_returnsNull() {
        assertNull(CollectorMessage.decode("""{"t":"screenshot","body":"x"}"""))
    }

    @Test
    fun decode_missingType_returnsNull() {
        assertNull(CollectorMessage.decode("""{"body":"x"}"""))
    }

    @Test
    fun decode_nonStringType_returnsNull() {
        assertNull(CollectorMessage.decode("""{"t":7,"body":"x"}"""))
    }

    @Test
    fun decode_malformedJson_returnsNull() {
        assertNull(CollectorMessage.decode("""{"t":"json","body":"""))
        assertNull(CollectorMessage.decode("not json at all"))
        assertNull(CollectorMessage.decode(""))
        assertNull(CollectorMessage.decode("   "))
    }

    @Test
    fun decode_nonObjectJson_returnsNull() {
        assertNull(CollectorMessage.decode("""["t","json"]"""))
        assertNull(CollectorMessage.decode(""""just a string""""))
        assertNull(CollectorMessage.decode("null"))
    }

    @Test
    fun decode_hugeBody_isReturnedUnchanged() {
        val body = """{"post_id":"1","filler":"${"x".repeat(200_000)}"}"""
        val message = CollectorMessage.decode("""{"t":"json","body":${quoted(body)}}""")

        assertTrue(message is CollectorMessage.JsonChunk)
        assertEquals(body, (message as CollectorMessage.JsonChunk).body)
    }

    private fun quoted(value: String): String =
        buildString {
            append('"')
            value.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    else -> append(c)
                }
            }
            append('"')
        }
}
