package com.deskbuddy.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `explicit requests are allowed`() {
        listOf(
            "Remember that I'm working on this",
            "remember this",
            "Please remember I have a call at four",
            "Hey buddy, remember to water the plant",
            "Hey Desku, remember to water the plant",
            "Can you remember that the wifi password is on the fridge",
            "Could you make a note that Sam owes me lunch",
            "Don't forget my dentist is Thursday",
            "Save that for me",
            "Okay, note that I moved the meeting",
            "I want you to remember my sister's birthday is May 3",
            "Write this down: buy stamps",
            "Remember that I’m working on this", // curly apostrophe from some keyboards
        ).forEach { assertTrue(it, MemoryPolicy.allowsSave(it, null)) }
    }

    @Test fun `questions and ambient talk are not`() {
        listOf(
            "What was I supposed to focus on?",
            "Do you remember what I said earlier?",
            "Did you remember my list?",
            "What do you remember about me?",
            "Remember when we talked about Sam?",
            "I don't remember where I put my keys",
            "Help me plan my afternoon",
            "I'm working on the grant intro",
            "Ugh, the invoices are stressing me out",
            "",
        ).forEach { assertFalse(it, MemoryPolicy.allowsSave(it, null)) }
    }

    @Test fun `a yes counts only right after an offer`() {
        val offer = "Want me to remember that you're on the grant intro?"
        assertTrue(MemoryPolicy.allowsSave("Yes please", offer))
        assertTrue(MemoryPolicy.allowsSave("sure", offer))
        assertFalse(MemoryPolicy.allowsSave("Yes please", "Want a 25 minute timer?"))
        assertFalse(MemoryPolicy.allowsSave("Yes please", null))
        assertFalse(MemoryPolicy.allowsSave("No thanks", offer))
    }

    @Test fun `store persists, numbers and deletes`() {
        val file = tmp.newFile("m.json").also { it.delete() }
        val store = MemoryStore(file) { 42L }
        val a = store.add("  Focus: grant intro  ")
        val b = store.add("Dentist Thursday")
        assertEquals("m1", a.id)
        assertEquals("Focus: grant intro", a.text)
        assertEquals("m2", b.id)

        val reopened = MemoryStore(file)
        assertEquals(listOf(a, b), reopened.all())
        assertTrue(reopened.delete("m1"))
        assertFalse(reopened.delete("m1"))
        assertEquals("m3", reopened.add("Next").id)
        reopened.clear()
        assertTrue(MemoryStore(file).all().isEmpty())
    }
}
