package com.deskbuddy.brain

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUnion
import com.anthropic.models.messages.ToolUseBlock
import com.deskbuddy.memory.Memory
import com.deskbuddy.memory.MemoryPolicy
import com.deskbuddy.memory.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.ZoneId
import java.util.Base64

data class BrainSettings(
    val apiKey: String,
    val model: String = DEFAULT_MODEL,
    /** Only tests override this (MockWebServer). */
    val baseUrl: String? = null,
) {
    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
    }
}

sealed interface LookResult {
    class Photo(val jpeg: ByteArray) : LookResult
    data class Unavailable(val reason: String) : LookResult
}

/** What the brain may ask of the phone. Implemented by the view model. */
interface BrainHost {
    /** Counts down on screen, takes one photo, turns the camera off again. */
    suspend fun look(reason: String): LookResult
    fun startFocusTimer(minutes: Int, task: String): String
    fun cancelFocusTimer(): String
    fun cameraOn(): Boolean
    /** e.g. "focus timer: 18 min left on 'grant intro'", or null when none is running. */
    fun timerStatus(): String?
    /** Speak a short line before a tool runs ("Sure, hold it up to the camera."). */
    suspend fun sayBeforeTool(words: String) {}
}

sealed interface Turn {
    /** Something the user said, optionally with a photo they chose to show (camera button). */
    data class Spoken(val words: String, val photo: ByteArray? = null) : Turn
    /** Something that happened on its own, e.g. a focus timer ending. Not the user's words. */
    data class Event(val note: String) : Turn
}

/** Something shown next to the spoken reply: a list read from a photo, and/or tap targets. */
data class ScreenCard(
    val heading: String,
    val items: List<CardItem>,
    /** Index into [items] of the suggested one, or -1. */
    val highlight: Int,
    val choices: List<Choice>,
)

data class CardItem(val text: String, val note: String)

data class Choice(val label: String, val action: Action) {
    /** SAY sends the label as the user's words; CAMERA opens the camera; LISTEN opens the mic. */
    enum class Action { SAY, CAMERA, LISTEN }
}

data class Reply(val speech: String, val actions: List<String> = emptyList(), val card: ScreenCard? = null)

class BrainException(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause)

/**
 * The conversation loop: Claude Messages API with six client-side tools. Conversations live in
 * memory only and expire after [idleMillis] of quiet; nothing but explicitly saved memories
 * survives them.
 */
class Brain(
    private val settings: () -> BrainSettings,
    private val memory: MemoryStore,
    private val host: BrainHost,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val idleMillis: Long = 10 * 60_000L,
) {
    private class Session(
        val model: String,
        val system: String,
        val memorySnapshot: List<Memory>,
        val messages: MutableList<MessageParam> = mutableListOf(),
        var lastUsed: Long,
        var lastAssistantWords: String? = null,
        var lastUserWords: String = "",
        /**
         * Results for show_on_screen calls that ended a turn. They're sent at the start of the
         * next user message instead of costing an extra round trip before Desku can speak.
         */
        val pendingResults: MutableList<ToolResultBlockParam> = mutableListOf(),
    )

    private val mutex = Mutex()
    private var session: Session? = null
    private var client: AnthropicClient? = null
    private var clientKey: Pair<String, String?>? = null

    /** Forget the current conversation (saved memories are untouched). */
    fun endConversation() {
        session = null
    }

    suspend fun respond(turn: Turn): Reply = mutex.withLock {
        withContext(Dispatchers.IO) { respondLocked(turn) }
    }

    private suspend fun respondLocked(turn: Turn): Reply {
        val s = settings()
        if (s.apiKey.isBlank()) throw BrainException("I don't have a brain yet. Add an Anthropic API key in settings.")
        val api = clientFor(s)
        val now = clock()
        val current = session?.takeIf { it.model == s.model && now - it.lastUsed < idleMillis }
            ?: newSession(s.model, now).also { session = it }

        // On failure, roll back to the history before this turn: a prefix the API has already
        // seen, so the conversation stays valid and the user can simply try again.
        val mark = current.messages.size
        val previousUserWords = current.lastUserWords
        val previousPending = current.pendingResults.toList()
        current.messages += userMessage(current, turn, now)
        if (turn is Turn.Spoken) current.lastUserWords = turn.words
        return try {
            runTurn(api, current)
        } catch (e: Throwable) {
            // Also on cancellation (the user tapped the mic mid-turn): never leave a dangling
            // user turn behind.
            while (current.messages.size > mark) current.messages.removeAt(current.messages.lastIndex)
            current.lastUserWords = previousUserWords
            current.pendingResults.clear()
            current.pendingResults += previousPending
            // A 400 may mean the history itself is the problem; start the next turn fresh.
            if (e is BrainException && e.cause is BadRequestException) session = null
            throw e
        }
    }

    private suspend fun runTurn(api: AnthropicClient, current: Session): Reply {
        val actions = mutableListOf<String>()
        var card: ScreenCard? = null
        repeat(MAX_STEPS) {
            val response = call(api, current)
            current.lastUsed = clock()

            if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
                // A declined turn can come back empty, which the next request would reject.
                // Start over rather than carry it.
                session = null
                return Reply("Sorry, I can't help with that one.", actions, card)
            }
            current.messages += assistantParam(response)

            val toolUses = response.content().mapNotNull { it.toolUse().orElse(null) }
            val words = spokenText(response)
            if (response.stopReason().orElse(null) != StopReason.TOOL_USE || toolUses.isEmpty()) {
                current.lastAssistantWords = words
                return Reply(words.ifBlank { "Okay." }, actions, card)
            }
            // Spoken reply plus only show_on_screen: show it and speak now; the tool results
            // ride along with the user's next message.
            if (words.isNotBlank() && toolUses.all { it.name() == SHOW_ON_SCREEN }) {
                toolUses.forEach { use ->
                    card = parseCard(use) ?: card
                    current.pendingResults += ToolResultBlockParam.builder().toolUseId(use.id()).content("Shown on screen.").build()
                }
                current.lastAssistantWords = words
                return Reply(words, actions, card)
            }
            if (words.isNotBlank()) host.sayBeforeTool(words)
            val results = toolUses.map { use ->
                if (use.name() == SHOW_ON_SCREEN) {
                    card = parseCard(use) ?: card
                    ToolResultBlockParam.builder().toolUseId(use.id()).content("Shown on screen.").build()
                } else {
                    runTool(use, current, actions)
                }
            }
            current.messages += MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(results.map(ContentBlockParam::ofToolResult))
                .build()
        }
        current.lastAssistantWords = null
        return Reply("Sorry, I got tangled up. Could you say that again?", actions, card)
    }

    private fun newSession(model: String, now: Long): Session {
        val snapshot = memory.all()
        return Session(model, Prompt.system(snapshot, now, zone), snapshot, lastUsed = now)
    }

    private fun userMessage(session: Session, turn: Turn, now: Long): MessageParam {
        val blocks = mutableListOf<ContentBlockParam>()
        // Tool results must lead the user message that follows their tool_use.
        session.pendingResults.forEach { blocks += ContentBlockParam.ofToolResult(it) }
        session.pendingResults.clear()
        val status = StringBuilder(Prompt.status(now, zone, host.cameraOn(), host.timerStatus()))
        if (memory.all() != session.memorySnapshot) {
            status.append("\n[Saved memories have changed since this conversation started. Current list — ")
            status.append(Prompt.memoryBlock(memory.all(), zone)).append("]")
        }
        blocks += ContentBlockParam.ofText(status.toString())
        when (turn) {
            is Turn.Spoken -> {
                turn.photo?.let {
                    blocks += ContentBlockParam.ofText("[The user pressed the camera button and is showing you this photo.]")
                    blocks += ContentBlockParam.ofImage(jpegBlock(it))
                }
                blocks += ContentBlockParam.ofText(turn.words.ifBlank { "(no words, just the photo)" })
            }
            is Turn.Event -> blocks += ContentBlockParam.ofText("[Automatic event, not said by the user] ${turn.note}")
        }
        return MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(blocks).build()
    }

    private fun call(api: AnthropicClient, session: Session): Message {
        val params = MessageCreateParams.builder()
            .model(session.model)
            .maxTokens(16_000L)
            .systemOfTextBlockParams(listOf(TextBlockParam.builder().text(session.system).build()))
            .tools(TOOLS.map(ToolUnion::ofTool))
            .messages(session.messages.toList())
            // Thinking stays adaptive (it can't be turned off on Opus 5.5); low effort keeps a
            // spoken turn snappy.
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            // Top-level automatic caching: the frozen system prompt, tools and the growing
            // conversation (photos included) are re-read from cache on each turn.
            .cacheControl(CacheControlEphemeral.builder().build())
            // If a safety classifier declines, the server retries on its recommended model.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
        return try {
            api.messages().create(params)
        } catch (e: UnauthorizedException) {
            throw BrainException("My API key was rejected. Check it in settings.", e)
        } catch (e: PermissionDeniedException) {
            throw BrainException("My API key isn't allowed to use ${session.model}. Check settings.", e)
        } catch (e: RateLimitException) {
            throw BrainException("I'm being rate limited. Give me a minute.", e)
        } catch (e: InternalServerException) {
            throw BrainException("My brain is overloaded right now. Try again in a moment.", e)
        } catch (e: BadRequestException) {
            throw BrainException("My brain didn't accept that request. Starting fresh might help.", e)
        } catch (e: AnthropicServiceException) {
            throw BrainException("Something went wrong talking to my brain (${e.statusCode()}).", e)
        } catch (e: AnthropicIoException) {
            throw BrainException("I can't reach the internet. Check the Wi-Fi.", e)
        } catch (e: AnthropicException) {
            throw BrainException("Something went wrong talking to my brain.", e)
        }
    }

    private suspend fun runTool(use: ToolUseBlock, session: Session, actions: MutableList<String>): ToolResultBlockParam {
        val input = runCatching { use._input().convert(Map::class.java) as Map<*, *> }.getOrDefault(emptyMap<String, Any>())
        fun str(key: String) = (input[key] as? String)?.trim().orEmpty()
        val result = ToolResultBlockParam.builder().toolUseId(use.id())
        when (use.name()) {
            "look_through_camera" -> when (val look = host.look(str("reason").ifBlank { "this" })) {
                is LookResult.Photo -> {
                    actions += "Took a photo"
                    result.contentOfBlocks(
                        listOf(
                            ToolResultBlockParam.Content.Block.ofText("Photo taken just now with the desk camera."),
                            ToolResultBlockParam.Content.Block.ofImage(jpegBlock(look.jpeg)),
                        ),
                    )
                }
                is LookResult.Unavailable -> result.content("No photo: ${look.reason}")
            }
            "save_memory" -> {
                val text = str("text")
                if (!MemoryPolicy.allowsSave(session.lastUserWords, session.lastAssistantWords)) {
                    result.content(
                        "Not saved. The user didn't explicitly ask you to remember this, and Desku only " +
                            "keeps what they ask it to keep. If it seems worth keeping, offer to remember it.",
                    ).isError(true)
                } else if (text.isEmpty()) {
                    result.content("Not saved: the memory text was empty.").isError(true)
                } else {
                    val saved = memory.add(text)
                    actions += "Remembered: ${saved.text}"
                    result.content("Saved as ${saved.id}.")
                }
            }
            "delete_memory" -> {
                val id = str("id")
                if (memory.delete(id)) {
                    actions += "Forgot $id"
                    result.content("Deleted $id.")
                } else {
                    result.content("There is no saved memory with id '$id'.").isError(true)
                }
            }
            "start_focus_timer" -> {
                val minutes = ((input["minutes"] as? Number)?.toInt() ?: 25).coerceIn(1, 240)
                val task = str("task").ifBlank { "focus" }
                actions += "Timer: $minutes min"
                result.content(host.startFocusTimer(minutes, task))
            }
            "cancel_focus_timer" -> result.content(host.cancelFocusTimer())
            else -> result.content("Unknown tool ${use.name()}.").isError(true)
        }
        return result.build()
    }

    private fun parseCard(use: ToolUseBlock): ScreenCard? {
        val input = runCatching { use._input().convert(Map::class.java) as Map<*, *> }.getOrNull() ?: return null
        val items = (input["items"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.map {
            CardItem((it["text"] as? String).orEmpty().trim(), (it["note"] as? String).orEmpty().trim())
        }.filter { it.text.isNotEmpty() }
        val choices = (input["choices"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.mapNotNull {
            val label = (it["label"] as? String)?.trim().orEmpty()
            val action = when ((it["action"] as? String)?.lowercase()) {
                "camera" -> Choice.Action.CAMERA
                "listen" -> Choice.Action.LISTEN
                else -> Choice.Action.SAY
            }
            if (label.isEmpty()) null else Choice(label, action)
        }.take(3)
        val highlight = ((input["highlight"] as? Number)?.toInt() ?: -1).takeIf { it in items.indices } ?: -1
        val heading = (input["heading"] as? String).orEmpty().trim()
        if (items.isEmpty() && choices.isEmpty() && heading.isEmpty()) return null
        return ScreenCard(heading, items, highlight, choices)
    }

    private fun assistantParam(response: Message): MessageParam =
        // Echo the response unchanged (thinking blocks included) so the conversation stays
        // append-only. Fall back to the blocks the SDK knows if a new block type can't convert.
        runCatching { response.toParam() }.getOrElse {
            MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(response.content().mapNotNull { b -> runCatching { b.toParam() }.getOrNull() })
                .build()
        }

    private fun spokenText(response: Message): String =
        SpeechText.clean(response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(" "))

    private fun clientFor(s: BrainSettings): AnthropicClient {
        val key = s.apiKey to s.baseUrl
        client?.let { if (clientKey == key) return it }
        client?.close()
        val builder = AnthropicOkHttpClient.builder()
            .apiKey(s.apiKey)
            .timeout(Duration.ofSeconds(60))
            .maxRetries(2)
        s.baseUrl?.let { builder.baseUrl(it) }
        return builder.build().also {
            client = it
            clientKey = key
        }
    }

    private fun jpegBlock(jpeg: ByteArray): ImageBlockParam = ImageBlockParam.builder()
        .source(
            Base64ImageSource.builder()
                .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                .data(Base64.getEncoder().encodeToString(jpeg))
                .build(),
        )
        .build()

    companion object {
        private const val MAX_STEPS = 6
        const val SHOW_ON_SCREEN = "show_on_screen"

        private fun tool(name: String, description: String, props: Map<String, Map<String, Any>>): Tool {
            val properties = Tool.InputSchema.Properties.builder()
            props.forEach { (k, v) -> properties.putAdditionalProperty(k, JsonValue.from(v)) }
            return Tool.builder()
                .name(name)
                .description(description)
                .strict(true)
                .inputSchema(
                    Tool.InputSchema.builder()
                        .properties(properties.build())
                        .required(props.keys.toList())
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build(),
                )
                .build()
        }

        val TOOLS: List<Tool> = listOf(
            tool(
                "look_through_camera",
                "Take one photo with the phone's camera and see it. Use when the user is showing you something " +
                    "or asks you to look at or read something. The user sees a short countdown to hold it up first.",
                mapOf("reason" to mapOf("type" to "string", "description" to "What you're about to look at, shown on screen while the user aims, e.g. 'your to-do list'.")),
            ),
            tool(
                "save_memory",
                "Save one detail the user explicitly asked you to remember. It is kept on this phone until the user deletes it.",
                mapOf("text" to mapOf("type" to "string", "description" to "One self-contained sentence that will make sense days later.")),
            ),
            tool(
                "delete_memory",
                "Delete a saved memory when the user asks you to forget it.",
                mapOf("id" to mapOf("type" to "string", "description" to "The memory id, e.g. 'm2'.")),
            ),
            tool(
                "start_focus_timer",
                "Start a focus timer for the task the user chose. It replaces any running timer. When it ends, " +
                    "the phone chimes and you get an automatic note so you can check in.",
                mapOf(
                    "minutes" to mapOf("type" to "integer", "description" to "Length in minutes, 1 to 240."),
                    "task" to mapOf("type" to "string", "description" to "Short name of the task, e.g. 'grant intro'."),
                ),
            ),
            tool("cancel_focus_timer", "Stop the running focus timer.", emptyMap()),
            tool(
                SHOW_ON_SCREEN,
                "Put a list and/or up to three tap targets on the phone's screen next to your spoken reply. " +
                    "Call it after your spoken text, as the last thing in the response.",
                mapOf(
                    "heading" to mapOf("type" to "string", "description" to "Short line above the list, e.g. 'I can see four things!'. Empty string for none."),
                    "items" to mapOf(
                        "type" to "array",
                        "description" to "List lines, e.g. each task read from a photo, in order. Empty array for none.",
                        "items" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "text" to mapOf("type" to "string", "description" to "The item as written."),
                                "note" to mapOf("type" to "string", "description" to "Short tag such as 'Due today', or empty string."),
                            ),
                            "required" to listOf("text", "note"),
                            "additionalProperties" to false,
                        ),
                    ),
                    "highlight" to mapOf("type" to "integer", "description" to "Index of the item you suggest, or -1."),
                    "choices" to mapOf(
                        "type" to "array",
                        "description" to "Tap targets, at most three. Empty array for none.",
                        "items" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "label" to mapOf("type" to "string", "description" to "Two to four words, e.g. 'Yes, that one'."),
                                "action" to mapOf(
                                    "type" to "string",
                                    "enum" to listOf("say", "camera", "listen"),
                                    "description" to "say: tapping sends the label as the user's words. camera: opens the camera. listen: opens the mic.",
                                ),
                            ),
                            "required" to listOf("label", "action"),
                            "additionalProperties" to false,
                        ),
                    ),
                ),
            ),
        )
    }
}
