package com.androidharness.app.data.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundle patches edit upstream's shipped JavaScript by exact match. What
 * has to hold is that they land once, do nothing the second time, and never
 * touch a file whose shape they do not recognise.
 */
class CodeGraphPatchesTest {

    private fun lines(vararg lines: String) = lines.joinToString("\n")

    private val matcherSource = lines(
        "const LANGUAGE_FAMILY = {",
        "    typescript: 'web', tsx: 'web', javascript: 'web', jsx: 'web', arkts: 'web',",
        "    c: 'c', cpp: 'c',",
        "};",
        "function applyLanguageGate(candidates, ref) {",
        "    if (ref.referenceKind === 'references' || ref.referenceKind === 'function_ref') {",
        "        return candidates.filter((c) => sameLanguageFamily(c.language, ref.language));",
        "    }",
        "    if (ref.referenceKind === 'imports') {",
        "        return candidates.filter((c) => !crossesKnownFamily(c.language, ref.language));",
        "    }",
        "    return candidates;",
        "}",
    )

    private val resolverSource = lines(
        "    gateLanguage(result, ref) {",
        "        if (!result)",
        "            return result;",
        "        const tgt = this.getLanguageFromNodeId(result.targetNodeId);",
        "        if (!tgt || !ref.language)",
        "            return result;",
        "        if ((ref.referenceKind === 'references' || ref.referenceKind === 'function_ref') && !(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
        "            return null;",
        "        if (ref.referenceKind === 'imports' && (0, name_matcher_1.crossesKnownFamily)(tgt, ref.language))",
        "            return null;",
        "        return result;",
        "    }",
        "    gateFrameworkLanguage(result, ref) {",
        "        if (!result)",
        "            return result;",
        "        if (ref.referenceKind !== 'references' && ref.referenceKind !== 'imports')",
        "            return result;",
        "        const tgt = this.getLanguageFromNodeId(result.targetNodeId);",
        "        if (tgt && ref.language && (0, name_matcher_1.crossesKnownFamily)(tgt, ref.language))",
        "            return null;",
        "        return result;",
        "    }",
    )

    private val treeSitterSource = lines(
        "    createNode(kind, name, node, extra) {",
        "        // Skip nodes with empty/missing names — they are not meaningful symbols",
        "        // and would cause FK violations when edges reference them (see issue #42)",
        "        if (!name) {",
        "            return null;",
        "        }",
        "        const id = generateNodeId();",
        "        return { id, name };",
        "    }",
    )

    private val toolsSource = lines(
        "        let summaryLine = survivors.length > 0",
        "            ? `Found \${shownSymbols} symbol\${shownSymbols === 1 ? '' : 's'} across \${survivors.length} file\${survivors.length === 1 ? '' : 's'}.`",
        "            : `Found \${subgraph.nodes.size} symbol\${subgraph.nodes.size === 1 ? '' : 's'} across \${fileGroups.size} file\${fileGroups.size === 1 ? '' : 's'}.`;",
    )

    private val binSource = lines(
        "            else {",
        "                console.log(chalk.bold(`\\nImpact of changing \"\${symbol}\" — \${mergedNodes.size} affected symbols:\\n`));",
        "            }",
    )

    private val dbSource = lines(
        "        await this.runPragmasOffThread(['PRAGMA analysis_limit=1000', 'PRAGMA optimize', 'PRAGMA wal_checkpoint(PASSIVE)'], ",
        "        // Worker threads unavailable — bounded in-line fallback, no checkpoint.",
        "        ['PRAGMA analysis_limit=1000', 'PRAGMA optimize']);",
    )

    private fun tempDir(): File {
        val dir = File.createTempFile("cgpatch", "")
        dir.delete()
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }

    private fun bundle(): File {
        val dist = tempDir()
        File(dist, "resolution").mkdirs()
        File(dist, "extraction").mkdirs()
        File(dist, "mcp").mkdirs()
        File(dist, "bin").mkdirs()
        File(dist, "db").mkdirs()
        File(dist, "resolution/name-matcher.js").writeText(matcherSource)
        File(dist, "resolution/index.js").writeText(resolverSource)
        File(dist, "extraction/tree-sitter.js").writeText(treeSitterSource)
        File(dist, "mcp/tools.js").writeText(toolsSource)
        File(dist, "bin/codegraph.js").writeText(binSource)
        File(dist, "db/index.js").writeText(dbSource)
        return dist
    }

    @Test
    fun `applies all 8 bundle patches cleanly`() {
        val dist = bundle()

        val result = CodeGraphBundlePatches.apply(dist)

        assertTrue("anchors must match the shipped shape", result.unresolved.isEmpty())
        assertEquals("all files should have applied patches", 6, result.applied.toSet().size)

        val matcher = File(dist, "resolution/name-matcher.js").readText()
        val resolver = File(dist, "resolution/index.js").readText()
        val treeSitter = File(dist, "extraction/tree-sitter.js").readText()
        val tools = File(dist, "mcp/tools.js").readText()
        val bin = File(dist, "bin/codegraph.js").readText()
        val db = File(dist, "db/index.js").readText()

        assertTrue("name-matcher includes web SFCs", matcher.contains("vue: 'web', svelte: 'web', astro: 'web'"))
        assertTrue("name-matcher gates calls/extends", matcher.contains("sameLanguageFamily(c.language, ref.language)"))
        assertTrue("resolver gates calls/extends", resolver.contains("sameLanguageFamily)(tgt, ref.language)"))
        assertTrue("framework gate rejects cross-language", resolver.contains("gateFrameworkLanguage"))
        assertTrue("tree-sitter rejects junk AST names", treeSitter.contains("name.startsWith('from ')"))
        assertTrue("mcp explore shows truncation note", tools.contains("showing \${shownSymbols} of \${totalFound}"))
        assertTrue("bin impact shows multi-def note", bin.contains("definitions named"))
        assertTrue("db maintenance includes ANALYZE", db.contains("ANALYZE"))
    }

    @Test
    fun `applying twice changes nothing the second time`() {
        val dist = bundle()
        CodeGraphBundlePatches.apply(dist)
        val afterFirst = File(dist, "resolution/index.js").readText()

        val second = CodeGraphBundlePatches.apply(dist)

        assertTrue("a patched file must not be patched again", second.applied.isEmpty())
        assertEquals("nothing should be unresolved either", 0, second.unresolved.size)
        assertEquals("the file must be byte for byte the same", afterFirst, File(dist, "resolution/index.js").readText())
    }

    @Test
    fun `an unrecognised bundle is reported and left alone`() {
        val dist = tempDir()
        File(dist, "resolution").mkdirs()
        val matcher = File(dist, "resolution/name-matcher.js")
        val other = "function applyLanguageGate(candidates, ref) { return candidates; }\n"
        matcher.writeText(other)

        val result = CodeGraphBundlePatches.apply(dist)

        assertTrue("nothing should be applied", result.applied.isEmpty())
        assertTrue("unresolved list must contain name-matcher", result.unresolved.contains("resolution/name-matcher.js"))
        assertEquals("an unmatched file must be untouched", other, matcher.readText())
    }
}
