package com.androidharness.app.data.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeGraphCommandsTest {
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

    @Test
    fun `launcher resolves its prefix from its own location`() {
        val script = CodeGraphCommands.launcher()
        // $0 relative on purpose: the same file is used from the app-private
        // prefix and from the deployed shell-tier copy under /data/local/tmp,
        // and only one of those paths is known when it is written.
        assertTrue(script.contains("""DIR="${'$'}{0%/*}""""))
        assertTrue(script.contains("""PREFIX="${'$'}(cd "${'$'}DIR/.." && pwd)""""))
        assertTrue(script.contains("""NODE="${'$'}PREFIX/codegraph/node/bin/node""""))
        assertTrue(script.contains("""CLI="${'$'}PREFIX/codegraph/bundle/lib/dist/bin/codegraph.js""""))
    }

    @Test
    fun `launcher starts the runtime through the linker, never through the prefix node`() {
        val script = CodeGraphCommands.launcher()
        // App-private files cannot be exec'd directly on W^X devices, so the
        // dynamic linker starts the runtime exactly like the shell shims do.
        assertTrue(script.contains("exec \"${'$'}LINKER\" \"${'$'}NODE\""))
        assertTrue(script.contains("--liftoff-only"))
        // A "node" shebang would make the harness shim route this script
        // through the prefix's Node 25+, which CodeGraph refuses to start on.
        assertFalse(script.lineSequence().first().contains("node"))
    }

    @Test
    fun `release tag is read from the redirect or the api`() {
        assertEquals(
            "v1.6.0",
            CodeGraphProvision.tagFromRedirect("https://github.com/colbymchenry/codegraph/releases/tag/v1.6.0"),
        )
        assertNull(CodeGraphProvision.tagFromRedirect("https://github.com/colbymchenry/codegraph/releases/latest"))
        assertNull(CodeGraphProvision.tagFromRedirect(null))
        assertEquals("v1.6.0", CodeGraphProvision.tagFromApi("""{"tag_name": "v1.6.0", "name": "x"}"""))
        assertNull(CodeGraphProvision.tagFromApi("{}"))
    }

    @Test
    fun `tags normalize to the v prefix used by release urls`() {
        assertEquals("v1.6.0", CodeGraphProvision.normalizeTag("1.6.0"))
        assertEquals("v1.6.0", CodeGraphProvision.normalizeTag("v1.6.0"))
        assertEquals("v1.6.0", CodeGraphProvision.normalizeTag(" v1.6.0 "))
        assertNull(CodeGraphProvision.normalizeTag("  "))
        assertNull(CodeGraphProvision.normalizeTag(null))
    }

    @Test
    fun `checksum lookup matches the published sums file`() {
        val sums = listOf(
            "${"a".repeat(64)}  codegraph-darwin-arm64.tar.gz",
            "${"B".repeat(64)}  *codegraph-linux-arm64.tar.gz",
        ).joinToString("\n")
        assertEquals("b".repeat(64), CodeGraphProvision.sha256For(sums, CodeGraphProvision.BUNDLE_ASSET))
        assertNull(CodeGraphProvision.sha256For(sums, "codegraph-win32-x64.zip"))
    }

    @Test
    fun `only a later release counts as an update`() {
        assertTrue(CodeGraphProvision.isNewer("1.7.0", "1.6.0"))
        assertTrue(CodeGraphProvision.isNewer("1.6.1", "1.6.0"))
        assertTrue(CodeGraphProvision.isNewer("2.0.0", "1.9.9"))
        assertTrue(CodeGraphProvision.isNewer("1.10.0", "1.9.0"))
        // The installed build IS the release the check just found.
        assertFalse(CodeGraphProvision.isNewer("1.6.0", "1.6.0"))
        assertFalse(CodeGraphProvision.isNewer("1.5.9", "1.6.0"))
        assertFalse(CodeGraphProvision.isNewer("1.6.0", "1.6.0-rc.1"))
    }

    @Test
    fun `only the platform independent payload survives unpacking`() {
        // Kept: the CLI and its grammars.
        assertEquals(
            "lib/dist/bin/codegraph.js",
            CodeGraphProvision.portablePath("codegraph-linux-arm64/lib/dist/bin/codegraph.js"),
        )
        assertEquals(
            "lib/node_modules/tree-sitter-wasms/out/tree-sitter-java.wasm",
            CodeGraphProvision.portablePath(
                "codegraph-linux-arm64/lib/node_modules/tree-sitter-wasms/out/tree-sitter-java.wasm",
            ),
        )
        // Dropped: the archive's glibc Node and native kernel, and dev files.
        assertNull(CodeGraphProvision.portablePath("codegraph-linux-arm64/node"))
        assertNull(CodeGraphProvision.portablePath("codegraph-linux-arm64/lib/kernel/codegraph-kernel.node"))
        assertNull(CodeGraphProvision.portablePath("codegraph-linux-arm64/lib/dist/index.js.map"))
        assertNull(CodeGraphProvision.portablePath("codegraph-linux-arm64/lib/dist/index.d.ts"))
        // Refused: entries trying to escape the destination.
        assertNull(CodeGraphProvision.portablePath("codegraph-linux-arm64/lib/../../etc/passwd"))
        assertNull(CodeGraphProvision.portablePath("./../lib/dist/index.js"))
    }
}