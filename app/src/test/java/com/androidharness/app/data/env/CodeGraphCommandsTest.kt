package com.androidharness.app.data.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CodeGraphCommandsTest {
    @Test
    fun `install pins npm global prefix to the Harness environment`() {
        val command = CodeGraphCommands.install(File("/data/user/0/app/files/linux"))
        assertTrue(command.contains("npm install --global --prefix '/data/user/0/app/files/linux'"))
        assertTrue(command.endsWith("@colbymchenry/codegraph@latest"))
    }

    @Test
    fun `explore shell quotes model supplied queries`() {
        assertEquals(
            """codegraph --no-color explore 'who calls '\''save'\'' and $(touch /tmp/nope)'""",
            CodeGraphCommands.explore("who calls 'save' and $(touch /tmp/nope)"),
        )
    }

    @Test
    fun `node bounds line controls and quotes paths`() {
        assertEquals(
            """codegraph --no-color node --file 'src/it'\''s.kt' --offset 1 --limit 2000 'Thing'""",
            CodeGraphCommands.node("Thing", "src/it's.kt", 0, 9000),
        )
    }

    @Test
    fun `impact and affected clamp traversal depth`() {
        assertEquals(
            "codegraph --no-color impact --depth 10 'render'",
            CodeGraphCommands.impact("render", 99),
        )
        assertEquals(
            "codegraph --no-color affected --depth 1 'a.kt' 'space name.kt'",
            CodeGraphCommands.affected(listOf("a.kt", "space name.kt"), 0),
        )
    }
}
