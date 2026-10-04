package com.deskbuddy.brain

import com.deskbuddy.memory.MemoryStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Drives the real Brain (official SDK, real HTTP) against a scripted Messages API, walking the
 * demo: plan the afternoon → look at a handwritten list → pick one → "remember that I'm working
 * on this" → later "what was I supposed to focus on?".
 */
class BrainDemoTest {

    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private var now = 1_759_600_000_000L // a Saturday afternoon
    private lateinit var store: MemoryStore
    private lateinit var host: FakeHost
    private lateinit var brain: Brain

    class FakeHost : BrainHost {
        var looks = 0
        var cameraIsOn = true
        var timer: String? = null
        val preToolLines = mutableListOf<String>()
        override suspend fun look(reason: String): LookResult {
            looks++
            return if (cameraIsOn) LookResult.Photo(byteArrayOf(-1, -40, -1, 0x11)) else LookResult.Unavailable("the camera is turned off")
        }
        override fun startFocusTimer(minutes: Int, task: String): String {
            timer = "focus timer: $minutes min left on '$task'"
            return "Timer set for $minutes minutes on '$task'."
        }
        override fun cancelFocusTimer(): String { timer = null; return "Timer cancelled." }
        override fun cameraOn() = cameraIsOn
        override fun timerStatus() = timer
        override suspend fun sayBeforeTool(words: String) { preToolLines += words }
    }

    @Before fun setUp() {
        server.start()
        store = MemoryStore(tmp.newFile("memories.json").also { it.delete() }) { now }
        host = FakeHost()
        brain = Brain(
            settings = { BrainSettings(apiKey = "test-key", baseUrl = server.url("/").toString().trimEnd('/')) },
            memory = store,
            host = host,
            clock = { now },
            zone = ZoneId.of("America/New_York"),
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `the afternoon-planning demo end to end`() = runBlocking {
        // 1. "Help me plan my afternoon." → Claude asks to see the list and calls the camera.
        enqueue(
            "tool_use",
            thinking(),
            text("Sure, hold your list up to the camera."),
            toolUse("toolu_1", "look_through_camera", JSONObject().put("reason", "your task list")),
        )
        enqueue("end_turn", thinking(), text("I see three things: grant intro, invoices, and call Sam. I'd start with the grant intro since it needs the most focus. Which one feels right?"))
        val r1 = brain.respond(Turn.Spoken("Help me plan my afternoon."))
        assertEquals(1, host.looks)
        assertEquals(listOf("Sure, hold your list up to the camera."), host.preToolLines)
        assertTrue(r1.speech.contains("grant intro"))

        val first = server.takeRequest(5, TimeUnit.SECONDS)!!.json()
        val second = server.takeRequest(5, TimeUnit.SECONDS)!!.json()
        assertEquals("claude-opus-5-5", first.getString("model"))
        assertEquals("low", first.getJSONObject("output_config").getString("effort"))
        assertEquals("default", first.getString("fallbacks"))
        assertTrue(first.has("cache_control"))
        assertEquals(6, first.getJSONArray("tools").length())
        assertTrue(first.getJSONArray("tools").getJSONObject(0).getBoolean("strict"))
        // The photo goes back as an image inside the tool_result.
        val toolResult = second.getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", toolResult.getString("type"))
        assertEquals("toolu_1", toolResult.getString("tool_use_id"))
        val image = toolResult.getJSONArray("content").getJSONObject(1)
        assertEquals("image", image.getString("type"))
        assertEquals("image/jpeg", image.getJSONObject("source").getString("media_type"))
        assertAppendOnly(first, second)

        // 2. Choose one priority together. Plain chat must never be saved.
        enqueue("end_turn", text("Great choice. Want a 25 minute focus timer?"))
        brain.respond(Turn.Spoken("Let's go with the grant intro."))
        val third = server.takeRequest(5, TimeUnit.SECONDS)!!.json()
        assertAppendOnly(second, third)
        // The thinking block from turn 1 is echoed back unchanged (signature intact).
        val echoedThinking = third.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0)
        assertEquals("thinking", echoedThinking.getString("type"))
        assertEquals("sig-1", echoedThinking.getString("signature"))

        // 3. "Remember that I'm working on this" → save_memory with the concrete task.
        enqueue("tool_use", toolUse("toolu_2", "save_memory", JSONObject().put("text", "On Saturday afternoon the user chose to focus on drafting the grant intro.")))
        enqueue("end_turn", text("Got it. I'll remember you're on the grant intro."))
        val r3 = brain.respond(Turn.Spoken("Remember that I'm working on this."))
        assertEquals(1, store.all().size)
        assertTrue(store.all().single().text.contains("grant intro"))
        assertTrue(r3.actions.any { it.startsWith("Remembered") })
        val fourth = server.takeRequest(5, TimeUnit.SECONDS)!!.json()
        val fifth = server.takeRequest(5, TimeUnit.SECONDS)!!.json()
        assertAppendOnly(third, fourth)
        assertAppendOnly(fourth, fifth)

        // 4. Much later, a fresh conversation: the memory is in the new system prompt.
        now += 3 * 60 * 60_000L
        enqueue("end_turn", text("You were focusing on the grant intro."))
        val r4 = brain.respond(Turn.Spoken("What was I supposed to focus on?"))
        assertEquals("You were focusing on the grant intro.", r4.speech)
        val sixth = server.takeRequest(5, TimeUnit.SECONDS)!!.json()
        val system = sixth.getJSONArray("system").getJSONObject(0).getString("text")
        assertTrue(system.contains("m1"))
        assertTrue(system.contains("grant intro"))
        assertEquals("a new conversation starts with just the question", 1, sixth.getJSONArray("messages").length())
    }

    @Test fun `show_on_screen ends the turn without an extra round trip`() = runBlocking {
        // Desku reads the list and puts it on screen in the same response it speaks.
        val card = JSONObject()
            .put("heading", "I can see four things!")
            .put("items", JSONArray()
                .put(JSONObject().put("text", "Send Q3 deck to Priya").put("note", "Due today"))
                .put(JSONObject().put("text", "Book dentist").put("note", "")))
            .put("highlight", 0)
            .put("choices", JSONArray()
                .put(JSONObject().put("label", "Yes, that one").put("action", "say"))
                .put(JSONObject().put("label", "Pick another").put("action", "say")))
        enqueue("tool_use", text("The deck's due today. Want that to be your one thing?"), toolUse("toolu_s1", "show_on_screen", card))
        val reply = brain.respond(Turn.Spoken("Here's my list."))
        assertEquals("The deck's due today. Want that to be your one thing?", reply.speech)
        assertEquals("I can see four things!", reply.card!!.heading)
        assertEquals(listOf("Send Q3 deck to Priya", "Book dentist"), reply.card!!.items.map { it.text })
        assertEquals("Due today", reply.card!!.items[0].note)
        assertEquals(0, reply.card!!.highlight)
        assertEquals(listOf("Yes, that one", "Pick another"), reply.card!!.choices.map { it.label })
        assertEquals(Choice.Action.SAY, reply.card!!.choices[0].action)
        assertEquals("only one request for that turn", 1, server.requestCount)
        val first = server.takeRequest(5, TimeUnit.SECONDS)!!.json()

        // The pending tool_result leads the next user message, then the words.
        enqueue("end_turn", text("Great, the deck it is."))
        brain.respond(Turn.Spoken("Yes, that one"))
        val second = server.takeRequest(5, TimeUnit.SECONDS)!!.json()
        assertAppendOnly(first, second)
        val next = second.getJSONArray("messages").getJSONObject(2).getJSONArray("content")
        assertEquals("tool_result", next.getJSONObject(0).getString("type"))
        assertEquals("toolu_s1", next.getJSONObject(0).getString("tool_use_id"))
        assertEquals("Yes, that one", next.getJSONObject(next.length() - 1).getString("text"))
    }

    @Test fun `a failed turn keeps the pending screen result for the retry`() = runBlocking {
        enqueue("tool_use", text("Show me or say it?"), toolUse("toolu_s2", "show_on_screen",
            JSONObject().put("heading", "").put("items", JSONArray()).put("highlight", -1)
                .put("choices", JSONArray().put(JSONObject().put("label", "Show Desku").put("action", "camera")))))
        val r = brain.respond(Turn.Spoken("Help me plan my afternoon."))
        assertEquals(Choice.Action.CAMERA, r.card!!.choices.single().action)
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"type":"error","error":{"type":"api_error","message":"boom"}}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"type":"error","error":{"type":"api_error","message":"boom"}}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"type":"error","error":{"type":"api_error","message":"boom"}}"""))
        assertTrue(runCatching { brain.respond(Turn.Spoken("I'll say it")) }.exceptionOrNull() is BrainException)
        enqueue("end_turn", text("Go ahead."))
        brain.respond(Turn.Spoken("I'll say it"))
        repeat(4) { server.takeRequest(5, TimeUnit.SECONDS) }
        val retried = server.takeRequest(5, TimeUnit.SECONDS)!!.json()
        val content = retried.getJSONArray("messages").getJSONObject(2).getJSONArray("content")
        assertEquals("toolu_s2", content.getJSONObject(0).getString("tool_use_id"))
    }

    @Test fun `memory is refused when the user did not ask`() = runBlocking {
        enqueue("tool_use", toolUse("toolu_9", "save_memory", JSONObject().put("text", "User is stressed about invoices.")))
        enqueue("end_turn", text("Invoices can wait until tomorrow."))
        brain.respond(Turn.Spoken("Ugh, the invoices are stressing me out."))
        assertTrue(store.all().isEmpty())
        server.takeRequest(5, TimeUnit.SECONDS)
        val toolResult = server.takeRequest(5, TimeUnit.SECONDS)!!.json()
            .getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertTrue(toolResult.getBoolean("is_error"))
    }

    @Test fun `a yes to an offer to remember is enough`() = runBlocking {
        enqueue("end_turn", text("Want me to remember that you're on the grant intro?"))
        brain.respond(Turn.Spoken("I'm going to do the grant intro."))
        enqueue("tool_use", toolUse("toolu_3", "save_memory", JSONObject().put("text", "The user is working on the grant intro.")))
        enqueue("end_turn", text("Done."))
        brain.respond(Turn.Spoken("Yes please."))
        assertEquals(1, store.all().size)
    }

    @Test fun `camera off is reported, not faked`() = runBlocking {
        host.cameraIsOn = false
        enqueue("tool_use", toolUse("toolu_4", "look_through_camera", JSONObject().put("reason", "your list")))
        enqueue("end_turn", text("The camera's off. Flip the camera switch at the top and show me again."))
        brain.respond(Turn.Spoken("Read this to-do list."))
        server.takeRequest(5, TimeUnit.SECONDS)
        val content = server.takeRequest(5, TimeUnit.SECONDS)!!.json()
            .getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("No photo: the camera is turned off", content.getString("content"))
    }

    @Test fun `timer end arrives as an automatic event`() = runBlocking {
        enqueue("tool_use", toolUse("toolu_5", "start_focus_timer", JSONObject().put("minutes", 25).put("task", "grant intro")))
        enqueue("end_turn", text("Timer's running. Go get it."))
        brain.respond(Turn.Spoken("Yes, start a timer."))
        assertEquals("focus timer: 25 min left on 'grant intro'", host.timer)
        server.takeRequest(5, TimeUnit.SECONDS); server.takeRequest(5, TimeUnit.SECONDS)

        enqueue("end_turn", text("Time's up on the grant intro. How did it go?"))
        brain.respond(Turn.Event("Focus timer finished: 25 minutes on 'grant intro'."))
        val last = server.takeRequest(5, TimeUnit.SECONDS)!!.json().getJSONArray("messages")
        val blocks = last.getJSONObject(last.length() - 1).getJSONArray("content")
        assertTrue(blocks.getJSONObject(1).getString("text").startsWith("[Automatic event, not said by the user]"))
    }

    @Test fun `a failed call rolls back and the next turn still works`() = runBlocking {
        enqueue("end_turn", text("Hi there."))
        brain.respond(Turn.Spoken("Hello."))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"type":"error","error":{"type":"authentication_error","message":"bad key"}}"""))
        val error = runCatching { brain.respond(Turn.Spoken("Are you there?")) }.exceptionOrNull()
        assertTrue(error is BrainException)
        assertEquals("My API key was rejected. Check it in settings.", (error as BrainException).userMessage)
        enqueue("end_turn", text("Yes, I'm here."))
        brain.respond(Turn.Spoken("Are you there?"))
        server.takeRequest(5, TimeUnit.SECONDS)
        server.takeRequest(5, TimeUnit.SECONDS)
        val retried = server.takeRequest(5, TimeUnit.SECONDS)!!.json()
        assertEquals("history is hello / hi / are you there", 3, retried.getJSONArray("messages").length())
    }

    @Test fun `no api key gives an honest message without calling out`() = runBlocking {
        val keyless = Brain({ BrainSettings(apiKey = "") }, store, host)
        val error = runCatching { keyless.respond(Turn.Spoken("Hello")) }.exceptionOrNull() as BrainException
        assertTrue(error.userMessage.contains("API key"))
        assertEquals(0, server.requestCount)
    }

    // --- helpers ---

    /** The prefix check preserved thinking relies on: system, tools and earlier messages unchanged. */
    private fun assertAppendOnly(before: JSONObject, after: JSONObject) {
        assertEquals(before.getJSONArray("system").toString(), after.getJSONArray("system").toString())
        assertEquals(before.getJSONArray("tools").toString(), after.getJSONArray("tools").toString())
        val a = before.getJSONArray("messages")
        val b = after.getJSONArray("messages")
        assertTrue(b.length() > a.length())
        for (i in 0 until a.length()) assertEquals("message $i changed", a.get(i).toString(), b.get(i).toString())
        assertFalse(after.toString().contains("\"role\":\"system\""))
    }

    private var msgCounter = 0
    private var sigCounter = 0

    private fun enqueue(stopReason: String, vararg content: JSONObject) {
        val body = JSONObject()
            .put("id", "msg_${++msgCounter}")
            .put("type", "message")
            .put("role", "assistant")
            .put("model", "claude-opus-5-5")
            .put("content", JSONArray(content.toList()))
            .put("stop_reason", stopReason)
            .put("stop_sequence", JSONObject.NULL)
            .put("usage", JSONObject().put("input_tokens", 10).put("output_tokens", 5))
        server.enqueue(MockResponse().setHeader("content-type", "application/json").setBody(body.toString()))
    }

    private fun text(t: String) = JSONObject().put("type", "text").put("text", t)
    private fun thinking() = JSONObject().put("type", "thinking").put("thinking", "").put("signature", "sig-${++sigCounter}")
    private fun toolUse(id: String, name: String, input: JSONObject) =
        JSONObject().put("type", "tool_use").put("id", id).put("name", name).put("input", input)

    private fun RecordedRequest.json() = JSONObject(body.readUtf8())
}
