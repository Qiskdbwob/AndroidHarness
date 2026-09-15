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
        "    // Prefer same-language matches",
        "    const sameLanguageCandidates = callableCandidates.filter(n => n.language === ref.language);",
        "    const finalCandidates = sameLanguageCandidates.length > 0 ? sameLanguageCandidates : callableCandidates;",
        "    if (finalCandidates.length === 1) {",
        "        const isCrossLanguage = finalCandidates[0].language !== ref.language;",
        "        return {",
        "            original: ref,",
        "            targetNodeId: finalCandidates[0].id,",
        "            confidence: isCrossLanguage ? 0.3 : 0.5,",
        "            resolvedBy: 'fuzzy',",
        "        };",
        "    }",
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

    private val indexSource = lines(
        "        const db = db_1.DatabaseConnection.open(dbPath);",
        "        const queries = new queries_1.QueryBuilder(db.getDb());",
        "        const instance = new CodeGraph(db, queries, resolvedRoot);",
    )

    private val extractionVersionSource = "exports.EXTRACTION_VERSION = 25;"

    private val importResolverSource = lines(
        "function resolvePythonAbsoluteModule(ref, context) {",
        "    if (ref.referenceKind !== 'imports')",
        "        return null;",
        "    // Only a DOTTED `import a.b.c` ref carries its full module path. A bare leaf",
        "    // (`from app.api.routes import authentication`) is ambiguous on its own — three",
        "    // `authentication.py` files may exist — so leave it to resolveModuleImportToFile,",
        "    // which uses the import's source (`app.api.routes`) to build the full path.",
        "    if (!ref.referenceName.includes('.'))",
        "        return null;",
        "    const hit = findPythonModuleFile(ref.referenceName, context, ref.filePath);",
        "    return hit ? { original: ref, targetNodeId: hit.id, confidence: 0.9, resolvedBy: 'import' } : null;",
        "}",
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
        "    if (fileCount < 150) {",
        "        return {",
        "            // ITER3: revert iter2's aggressive body shrink (forced Read fallback —",
        "            // the per-file 2.5K cap pushed the agent to Read instead of node).",
        "            // Back to the iter1 shape (13K/4/3.8K) but keep the test-file",
        "            // hard-exclude. The cost lever for this tier lives in steering the",
        "            // agent to stop after 1-2 calls, not in this budget.",
        "            maxOutputChars: 13000,",
        "            defaultMaxFiles: 4,",
        "            maxCharsPerFile: 3800,",
        "            gapThreshold: 7,",
        "            maxSymbolsInFileHeader: 5,",
        "            maxEdgesPerRelationshipKind: 4,",
        "            includeRelationships: false,",
        "            includeAdditionalFiles: false,",
        "            includeCompletenessSignal: false,",
        "            includeBudgetNote: false,",
        "        };",
        "    }",
        "        const subgraph = await cg.findRelevantContext(matchQuery, {",
        "            searchLimit: 8,",
        "            traversalDepth: 3,",
        "            maxNodes: 200,",
        "            minScore: 0.2,",
        "        });",
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

    private val migrationsSource = lines(
        "exports.CURRENT_SCHEMA_VERSION = 9;",
        "const migrations = [",
        "    {",
        "        version: 9,",
        "        description: 'test',",
        "        up: (db) => {",
        "            db.exec('CREATE INDEX IF NOT EXISTS idx_files_generated ON files(path) WHERE generated = 1');",
        "        },",
        "    },",
        "];",
    )

    private val queriesSource = lines(
        "this.runBatched('insertUnresolvedRefs', 'INSERT INTO unresolved_refs (from_node_id, reference_name, reference_kind, line, col, candidates, file_path, language) VALUES ', '(?,?,?,?,?,?,?,?)', rows);",
        "    deleteNodesByFile(filePath) {",
        "        if (!this.stmts.deleteNodesByFile) {",
        "            this.stmts.deleteNodesByFile = this.db.prepare('DELETE FROM nodes WHERE file_path = ?');",
        "        }",
        "        // Invalidate cache for nodes in this file",
        "        for (const [id, node] of this.nodeCache) {",
        "            if (node.filePath === filePath) {",
        "                this.nodeCache.delete(id);",
        "            }",
        "        }",
        "        this.stmts.deleteNodesByFile.run(filePath);",
        "    }",
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
        File(dist, "index.js").writeText(indexSource)
        File(dist, "resolution/name-matcher.js").writeText(matcherSource)
        File(dist, "resolution/index.js").writeText(resolverSource)
        File(dist, "resolution/import-resolver.js").writeText(importResolverSource)
        File(dist, "extraction/tree-sitter.js").writeText(treeSitterSource)
        File(dist, "extraction/extraction-version.js").writeText(extractionVersionSource)
        File(dist, "mcp/tools.js").writeText(toolsSource)
        File(dist, "bin/codegraph.js").writeText(binSource)
        File(dist, "db/index.js").writeText(dbSource)
        File(dist, "db/migrations.js").writeText(migrationsSource)
        File(dist, "db/queries.js").writeText(queriesSource)
        return dist
    }

    @Test
    fun `applies all bundle patches cleanly`() {
        val dist = bundle()

        val result = CodeGraphBundlePatches.apply(dist)

        assertTrue("anchors must match the shipped shape", result.unresolved.isEmpty())
        assertEquals("all files should have applied patches", 11, result.applied.toSet().size)

        val matcher = File(dist, "resolution/name-matcher.js").readText()
        val resolver = File(dist, "resolution/index.js").readText()
        val index = File(dist, "index.js").readText()
        val importResolver = File(dist, "resolution/import-resolver.js").readText()
        val treeSitter = File(dist, "extraction/tree-sitter.js").readText()
        val extractionVersion = File(dist, "extraction/extraction-version.js").readText()
        val tools = File(dist, "mcp/tools.js").readText()
        val bin = File(dist, "bin/codegraph.js").readText()
        val db = File(dist, "db/index.js").readText()
        val migrations = File(dist, "db/migrations.js").readText()
        val queries = File(dist, "db/queries.js").readText()

        assertTrue("name-matcher includes web SFCs", matcher.contains("vue: 'web', svelte: 'web', astro: 'web'"))
        assertTrue("name-matcher gates calls/extends/decorates", matcher.contains("ref.referenceKind === 'decorates'"))
        assertTrue("name-matcher fuzzy rejects cross-language", matcher.contains("strictly same language family for fuzzy matching"))
        assertTrue("resolver gates calls/extends/decorates", resolver.contains("ref.referenceKind === 'decorates'"))
        assertTrue("framework gate rejects cross-language decorates", resolver.contains("ref.referenceKind === 'decorates'"))
        assertTrue("index.js performs retroactive prune on open", index.contains("pruneCrossLanguageEdges()"))
        assertTrue("import resolver supports bare python module import", importResolver.contains("bare single module imports"))
        assertTrue("tree-sitter rejects junk AST names", treeSitter.contains("name.startsWith('from ')"))
        assertTrue("extraction version bumped to 26", extractionVersion.contains("EXTRACTION_VERSION = 26;"))
        assertTrue("mcp explore shows truncation note", tools.contains("showing \${shownSymbols} of \${totalFound}"))
        assertTrue("mcp explore raises searchLimit", tools.contains("Math.max(24, maxFiles * 2)"))
        assertTrue("mcp explore raises base output budget", tools.contains("maxOutputChars: 24000"))
        assertTrue("bin impact shows multi-def note", bin.contains("definitions named"))
        assertTrue("db maintenance excludes virtual FTS table", db.contains("ANALYZE nodes"))
        assertTrue("migrations bumps version to 11", migrations.contains("CURRENT_SCHEMA_VERSION = 11;"))
        assertTrue("migrations includes version 11", migrations.contains("version: 11,"))
        assertTrue("queries uses INSERT OR IGNORE", queries.contains("INSERT OR IGNORE INTO unresolved_refs"))
        assertTrue("queries has pruneCrossLanguageEdges method", queries.contains("pruneCrossLanguageEdges()"))
        assertTrue("queries prunes vocab on file deletion", queries.contains("DELETE FROM name_segment_vocab WHERE name NOT IN"))
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
