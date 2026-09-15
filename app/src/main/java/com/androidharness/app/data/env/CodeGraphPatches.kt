package com.androidharness.app.data.env

import java.io.File

/**
 * Fixes for the CodeGraph release Harness vendors, applied to the extracted
 * bundle. Upstream ships these bugs in v1.6.0, which is still the newest
 * release, so there is nothing to upgrade to and the app carries the fix
 * itself for every device it installs on.
 *
 * Every patch is an exact-match edit on the JavaScript Harness unpacked, and
 * every patch is written to be idempotent: the text it inserts is also the
 * marker that says the work is already done. A patch whose anchor does not
 * appear is reported as unresolved and the file is left exactly as it was, so
 * a future release that reorganises this code degrades to plain upstream
 * behaviour instead of being corrupted.
 *
 * The anchors below are written as explicit line lists rather than indented
 * Kotlin strings, because `trimIndent()` would strip the very leading spaces
 * that have to match the file byte for byte.
 */
internal object CodeGraphBundlePatches {

    /** Bumped whenever [patches] changes, so an install can tell old from new. */
    const val VERSION = 2

    internal data class Patch(
        val relativePath: String,
        val anchor: String,
        val replacement: String,
    )

    internal data class Result(val applied: List<String>, val unresolved: List<String>) {
        val changed: Boolean get() = applied.isNotEmpty()
    }

    private fun lines(vararg lines: String): String = lines.joinToString("\n")

    // B3: Add single-file-component languages to the web family
    private val nameMatcherFamilyAnchor = "    typescript: 'web', tsx: 'web', javascript: 'web', jsx: 'web', arkts: 'web',"
    private val nameMatcherFamilyReplacement = lines(
        "    typescript: 'web', tsx: 'web', javascript: 'web', jsx: 'web', arkts: 'web',",
        "    vue: 'web', svelte: 'web', astro: 'web',",
    )

    // B1, B2, B3: Language gate on candidates in name matcher
    private val matcherAnchor = lines(
        "    if (ref.referenceKind === 'imports') {",
        "        return candidates.filter((c) => !crossesKnownFamily(c.language, ref.language));",
        "    }",
        "    return candidates;",
    )

    private val matcherReplacement = lines(
        "    if (ref.referenceKind === 'imports') {",
        "        return candidates.filter((c) => sameLanguageFamily(c.language, ref.language));",
        "    }",
        "    // Harness patch: a coincidental same-named symbol in another language is",
        "    // not a call, extends, or instantiation.",
        "    if (ref.referenceKind === 'calls' || ref.referenceKind === 'extends' || ref.referenceKind === 'instantiates') {",
        "        return candidates.filter((c) => sameLanguageFamily(c.language, ref.language));",
        "    }",
        "    return candidates;",
    )

    // B1, B2, B3: Language gate in resolver for solitary candidates
    private val resolverAnchor = lines(
        "        if (ref.referenceKind === 'imports' && (0, name_matcher_1.crossesKnownFamily)(tgt, ref.language))",
        "            return null;",
        "        return result;",
    )

    private val resolverReplacement = lines(
        "        if (ref.referenceKind === 'imports' && !(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
        "            return null;",
        "        // Harness patch: the candidate filter's rule, restated for the case",
        "        // where the foreign match was the only candidate there was.",
        "        if ((ref.referenceKind === 'calls' || ref.referenceKind === 'extends' || ref.referenceKind === 'instantiates') &&",
        "            !(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
        "            return null;",
        "        return result;",
    )

    // B4: Framework language gate rejecting cross-language instantiations/extends
    private val frameworkAnchor = lines(
        "    gateFrameworkLanguage(result, ref) {",
        "        if (!result) ",
        "            return result;",
        "        if (ref.referenceKind !== 'references' && ref.referenceKind !== 'imports')",
        "            return result;",
        "        const tgt = this.getLanguageFromNodeId(result.targetNodeId);",
        "        if (tgt && ref.language && (0, name_matcher_1.crossesKnownFamily)(tgt, ref.language))",
        "            return null;",
        "        return result;",
        "    }",
    ).replace("if (!result) \n", "if (!result)\n")

    private val frameworkReplacement = lines(
        "    gateFrameworkLanguage(result, ref) {",
        "        if (!result)",
        "            return result;",
        "        const tgt = this.getLanguageFromNodeId(result.targetNodeId);",
        "        if (!tgt || !ref.language)",
        "            return result;",
        "        // Harness patch: framework resolution must not bridge disparate languages for",
        "        // instantiations, extensions, references or imports.",
        "        if (ref.referenceKind === 'instantiates' || ref.referenceKind === 'extends') {",
        "            if (!(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
        "                return null;",
        "        }",
        "        if (ref.referenceKind === 'references' || ref.referenceKind === 'imports') {",
        "            if (!(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
        "                return null;",
        "        }",
        "        return result;",
        "    }",
    )

    // B9: Tree-sitter createNode rejects non-symbol junk
    private val treeSitterAnchor = lines(
        "    createNode(kind, name, node, extra) {",
        "        // Skip nodes with empty/missing names — they are not meaningful symbols",
        "        // and would cause FK violations when edges reference them (see issue #42)",
        "        if (!name) {",
        "            return null;",
        "        }",
    )

    private val treeSitterReplacement = lines(
        "    createNode(kind, name, node, extra) {",
        "        // Skip nodes with empty/missing names — they are not meaningful symbols",
        "        // and would cause FK violations when edges reference them (see issue #42)",
        "        if (!name) {",
        "            return null;",
        "        }",
        "        // Harness patch: filter out junk AST tokens that pollute symbol queries",
        "        if (name === '.' || name === '..' || name === '...' || name.startsWith('from ') || name.startsWith('import ')) {",
        "            return null;",
        "        }",
    )

    // B10: MCP explore summary line shows total count when truncated
    private val exploreAnchor = lines(
        "        let summaryLine = survivors.length > 0",
        "            ? `Found \${shownSymbols} symbol\${shownSymbols === 1 ? '' : 's'} across \${survivors.length} file\${survivors.length === 1 ? '' : 's'}.`",
        "            : `Found \${subgraph.nodes.size} symbol\${subgraph.nodes.size === 1 ? '' : 's'} across \${fileGroups.size} file\${fileGroups.size === 1 ? '' : 's'}.`;",
    )

    private val exploreReplacement = lines(
        "        const totalFound = subgraph.nodes.size;",
        "        const countNote = (survivors.length > 0 && totalFound > shownSymbols) ? ` (showing \${shownSymbols} of \${totalFound})` : '';",
        "        let summaryLine = survivors.length > 0",
        "            ? `Found \${totalFound} symbol\${totalFound === 1 ? '' : 's'}\${countNote} across \${survivors.length} file\${survivors.length === 1 ? '' : 's'}.`",
        "            : `Found \${subgraph.nodes.size} symbol\${subgraph.nodes.size === 1 ? '' : 's'} across \${fileGroups.size} file\${fileGroups.size === 1 ? '' : 's'}.`;",
    )

    // B5: CLI impact multi-definition note
    private val impactAnchor = lines(
        "            else {",
        "                console.log(chalk.bold(`\\nImpact of changing \"\${symbol}\" — \${mergedNodes.size} affected symbols:\\n`));",
    )

    private val impactReplacement = lines(
        "            else {",
        "                const exactMatches = matches.filter((m) => m.node.name === symbol || m.node.name.endsWith(`.\${symbol}`) || m.node.name.endsWith(`::\${symbol}`));",
        "                const multiNote = exactMatches.length > 1 ? ` (\${exactMatches.length} definitions named \"\${symbol}\")` : '';",
        "                console.log(chalk.bold(`\\nImpact of changing \"\${symbol}\"\${multiNote} — \${mergedNodes.size} affected symbols:\\n`));",
    )

    // B8: Database maintenance includes ANALYZE
    private val maintenanceAnchor = lines(
        "        await this.runPragmasOffThread(['PRAGMA analysis_limit=1000', 'PRAGMA optimize', 'PRAGMA wal_checkpoint(PASSIVE)'], ",
        "        // Worker threads unavailable — bounded in-line fallback, no checkpoint.",
        "        ['PRAGMA analysis_limit=1000', 'PRAGMA optimize']);",
    )

    private val maintenanceReplacement = lines(
        "        await this.runPragmasOffThread(['PRAGMA analysis_limit=1000', 'PRAGMA optimize', 'ANALYZE', 'PRAGMA wal_checkpoint(PASSIVE)'], ",
        "        // Worker threads unavailable — bounded in-line fallback, no checkpoint.",
        "        ['PRAGMA analysis_limit=1000', 'PRAGMA optimize', 'ANALYZE']);",
    )

    private val patches = listOf(
        Patch("resolution/name-matcher.js", nameMatcherFamilyAnchor, nameMatcherFamilyReplacement),
        Patch("resolution/name-matcher.js", matcherAnchor, matcherReplacement),
        Patch("resolution/index.js", resolverAnchor, resolverReplacement),
        Patch("resolution/index.js", frameworkAnchor, frameworkReplacement),
        Patch("extraction/tree-sitter.js", treeSitterAnchor, treeSitterReplacement),
        Patch("mcp/tools.js", exploreAnchor, exploreReplacement),
        Patch("bin/codegraph.js", impactAnchor, impactReplacement),
        Patch("db/index.js", maintenanceAnchor, maintenanceReplacement),
    )

    /**
     * Applies every patch that is not already present. [distDir] is the
     * bundle's `lib/dist`.
     */
    fun apply(distDir: File): Result {
        val applied = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        for (patch in patches) {
            val file = File(distDir, patch.relativePath)
            val text = if (file.isFile) runCatching { file.readText() }.getOrNull() else null
            if (text == null) {
                unresolved += patch.relativePath
                continue
            }
            // The inserted text doubles as the record that this patch is in
            // place, which is what keeps a second run from nesting copies.
            if (text.contains(patch.replacement)) continue
            if (!text.contains(patch.anchor)) {
                unresolved += patch.relativePath
                continue
            }
            runCatching { file.writeText(text.replace(patch.anchor, patch.replacement)) }
                .onSuccess { applied += patch.relativePath }
                .onFailure { unresolved += patch.relativePath }
        }
        return Result(applied, unresolved)
    }
}
