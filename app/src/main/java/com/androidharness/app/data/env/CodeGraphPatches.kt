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
    const val VERSION = 4

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

    // B1, B2, B3, 1 (decorates): Language gate on candidates in name matcher
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
        "    // not a call, extends, instantiation, or decorator.",
        "    if (ref.referenceKind === 'calls' || ref.referenceKind === 'extends' || ref.referenceKind === 'instantiates' || ref.referenceKind === 'decorates') {",
        "        return candidates.filter((c) => sameLanguageFamily(c.language, ref.language));",
        "    }",
        "    return candidates;",
    )

    // 1 (decorates & fuzzy): Eliminate cross-language fuzzy resolution fallback
    private val fuzzyAnchor = lines(
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

    private val fuzzyReplacement = lines(
        "    // Harness patch: strictly same language family for fuzzy matching, never cross-language",
        "    const sameLanguageCandidates = callableCandidates.filter((n) => sameLanguageFamily(n.language, ref.language));",
        "    if (sameLanguageCandidates.length === 1) {",
        "        return {",
        "            original: ref,",
        "            targetNodeId: sameLanguageCandidates[0].id,",
        "            confidence: 0.5,",
        "            resolvedBy: 'fuzzy',",
        "        };",
        "    }",
    )

    // B1, B2, B3, 1 (decorates): Language gate in resolver for solitary candidates
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
        "        if ((ref.referenceKind === 'calls' || ref.referenceKind === 'extends' || ref.referenceKind === 'instantiates' || ref.referenceKind === 'decorates') &&",
        "            !(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
        "            return null;",
        "        return result;",
    )

    // B4, 1 (decorates): Framework language gate rejecting cross-language instantiations/extends/decorates
    private val frameworkAnchor = lines(
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

    private val frameworkReplacement = lines(
        "    gateFrameworkLanguage(result, ref) {",
        "        if (!result)",
        "            return result;",
        "        const tgt = this.getLanguageFromNodeId(result.targetNodeId);",
        "        if (!tgt || !ref.language)",
        "            return result;",
        "        // Harness patch: framework resolution must not bridge disparate languages for",
        "        // instantiations, extensions, decorators, references or imports.",
        "        if (ref.referenceKind === 'instantiates' || ref.referenceKind === 'extends' || ref.referenceKind === 'decorates') {",
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

    // 2: Retroactive prune of cross-language edges on CodeGraph.open
    private val openPruneAnchor = lines(
        "        const db = db_1.DatabaseConnection.open(dbPath);",
        "        const queries = new queries_1.QueryBuilder(db.getDb());",
        "        const instance = new CodeGraph(db, queries, resolvedRoot);",
    )

    private val openPruneReplacement = lines(
        "        const db = db_1.DatabaseConnection.open(dbPath);",
        "        const queries = new queries_1.QueryBuilder(db.getDb());",
        "        try {",
        "            queries.pruneCrossLanguageEdges();",
        "        } catch { /* ignore */ }",
        "        const instance = new CodeGraph(db, queries, resolvedRoot);",
    )

    // 2: Extraction version bump to 26
    private val extractionVersionAnchor = "exports.EXTRACTION_VERSION = 25;"
    private val extractionVersionReplacement = "exports.EXTRACTION_VERSION = 26;"

    // B6: Python single-level module import (import b)
    private val pythonModuleImportAnchor = lines(
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

    private val pythonModuleImportReplacement = lines(
        "function resolvePythonAbsoluteModule(ref, context) {",
        "    if (ref.referenceKind !== 'imports')",
        "        return null;",
        "    // Harness patch: allow bare single module imports (import b) as well as dotted paths",
        "    const hit = findPythonModuleFile(ref.referenceName, context, ref.filePath);",
        "    return hit ? { original: ref, targetNodeId: hit.id, confidence: 0.9, resolvedBy: 'import' } : null;",
        "}",
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

    // 4 & Minor 11: Raise explore output budget and search limit for small repos / natural queries
    private val exploreBudgetAnchor = lines(
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
    )

    private val exploreBudgetReplacement = lines(
        "    if (fileCount < 150) {",
        "        return {",
        "            maxOutputChars: 24000,",
        "            defaultMaxFiles: 8,",
        "            maxCharsPerFile: 6500,",
        "            gapThreshold: 7,",
        "            maxSymbolsInFileHeader: 12,",
        "            maxEdgesPerRelationshipKind: 4,",
        "            includeRelationships: false,",
        "            includeAdditionalFiles: false,",
        "            includeCompletenessSignal: false,",
        "            includeBudgetNote: false,",
        "        };",
        "    }",
    )

    private val exploreSearchLimitAnchor = lines(
        "        const subgraph = await cg.findRelevantContext(matchQuery, {",
        "            searchLimit: 8,",
        "            traversalDepth: 3,",
        "            maxNodes: 200,",
        "            minScore: 0.2,",
        "        });",
    )

    private val exploreSearchLimitReplacement = lines(
        "        const subgraph = await cg.findRelevantContext(matchQuery, {",
        "            searchLimit: Math.max(24, maxFiles * 2),",
        "            traversalDepth: 3,",
        "            maxNodes: 200,",
        "            minScore: 0.1,",
        "        });",
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

    // B8 & Minor 12: Database maintenance analyzes real tables only, excluding virtual FTS tables
    private val maintenanceAnchor = lines(
        "        await this.runPragmasOffThread(['PRAGMA analysis_limit=1000', 'PRAGMA optimize', 'PRAGMA wal_checkpoint(PASSIVE)'], ",
        "        // Worker threads unavailable — bounded in-line fallback, no checkpoint.",
        "        ['PRAGMA analysis_limit=1000', 'PRAGMA optimize']);",
    )

    private val maintenanceReplacement = lines(
        "        await this.runPragmasOffThread(['PRAGMA analysis_limit=1000', 'PRAGMA optimize', 'ANALYZE nodes', 'ANALYZE edges', 'ANALYZE files', 'ANALYZE unresolved_refs', 'PRAGMA wal_checkpoint(PASSIVE)'], ",
        "        // Worker threads unavailable — bounded in-line fallback, no checkpoint.",
        "        ['PRAGMA analysis_limit=1000', 'PRAGMA optimize', 'ANALYZE nodes', 'ANALYZE edges', 'ANALYZE files', 'ANALYZE unresolved_refs']);",
    )

    // B3, B7, B9, 1, 2: Migration 10 - clean existing bad edges incl. decorates, unique unresolved_refs, prune vocab orphans
    private val migrationVersionAnchor = "exports.CURRENT_SCHEMA_VERSION = 9;"
    private val migrationVersionReplacement = "exports.CURRENT_SCHEMA_VERSION = 11;"

    private val migrationListAnchor = lines(
        "            db.exec('CREATE INDEX IF NOT EXISTS idx_files_generated ON files(path) WHERE generated = 1');",
        "        },",
        "    },",
        "];",
    )

    private val migrationListReplacement = lines(
        "            db.exec('CREATE INDEX IF NOT EXISTS idx_files_generated ON files(path) WHERE generated = 1');",
        "        },",
        "    },",
        "    {",
        "        version: 10,",
        "        description: 'Prune cross-language edges, unique unresolved refs index, and prune vocab orphans',",
        "        up: (db) => {",
        "            db.exec(`",
        "        DELETE FROM edges WHERE id IN (",
        "          SELECT e.id FROM edges e",
        "          JOIN nodes s ON e.source = s.id",
        "          JOIN nodes t ON e.target = t.id",
        "          WHERE e.kind IN ('imports', 'calls', 'extends', 'instantiates', 'decorates')",
        "          AND s.language != t.language",
        "          AND NOT (",
        "            (s.language IN ('kotlin','java','scala','clojure','groovy') AND t.language IN ('kotlin','java','scala','clojure','groovy')) OR",
        "            (s.language IN ('swift','objc','objcpp') AND t.language IN ('swift','objc','objcpp')) OR",
        "            (s.language IN ('typescript','tsx','javascript','jsx','arkts','vue','svelte','astro') AND t.language IN ('typescript','tsx','javascript','jsx','arkts','vue','svelte','astro')) OR",
        "            (s.language IN ('c','cpp') AND t.language IN ('c','cpp')) OR",
        "            (s.language IN ('csharp','razor') AND t.language IN ('csharp','razor'))",
        "          )",
        "        );",
        "        DELETE FROM unresolved_refs WHERE id NOT IN (",
        "          SELECT MIN(id) FROM unresolved_refs",
        "          GROUP BY from_node_id, reference_name, reference_kind, line, col",
        "        );",
        "        CREATE UNIQUE INDEX IF NOT EXISTS idx_unresolved_identity",
        "          ON unresolved_refs(from_node_id, reference_name, reference_kind, line, col);",
        "        DELETE FROM name_segment_vocab WHERE name NOT IN (SELECT name FROM nodes);",
        "      `);",
        "        },",
        "    },",
        "    {",
        "        version: 11,",
        "        description: 'Prune cross-language decorates edges',",
        "        up: (db) => {",
        "            db.exec(`",
        "        DELETE FROM edges WHERE id IN (",
        "          SELECT e.id FROM edges e",
        "          JOIN nodes s ON e.source = s.id",
        "          JOIN nodes t ON e.target = t.id",
        "          WHERE e.kind = 'decorates'",
        "          AND s.language != t.language",
        "          AND NOT (",
        "            (s.language IN ('kotlin','java','scala','clojure','groovy') AND t.language IN ('kotlin','java','scala','clojure','groovy')) OR",
        "            (s.language IN ('swift','objc','objcpp') AND t.language IN ('swift','objc','objcpp')) OR",
        "            (s.language IN ('typescript','tsx','javascript','jsx','arkts','vue','svelte','astro') AND t.language IN ('typescript','tsx','javascript','jsx','arkts','vue','svelte','astro')) OR",
        "            (s.language IN ('c','cpp') AND t.language IN ('c','cpp')) OR",
        "            (s.language IN ('csharp','razor') AND t.language IN ('csharp','razor'))",
        "          )",
        "        );",
        "      `);",
        "        },",
        "    },",
        "];",
    )

    // B7: insertUnresolvedRefsBatch uses INSERT OR IGNORE
    private val insertUnresolvedAnchor = "this.runBatched('insertUnresolvedRefs', 'INSERT INTO unresolved_refs (from_node_id, reference_name, reference_kind, line, col, candidates, file_path, language) VALUES ', '(?,?,?,?,?,?,?,?)', rows);"
    private val insertUnresolvedReplacement = "this.runBatched('insertUnresolvedRefs', 'INSERT OR IGNORE INTO unresolved_refs (from_node_id, reference_name, reference_kind, line, col, candidates, file_path, language) VALUES ', '(?,?,?,?,?,?,?,?)', rows);"

    // B9, 2: deleteNodesByFile prunes name_segment_vocab orphans + pruneCrossLanguageEdges method
    private val deleteNodesAnchor = lines(
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

    private val deleteNodesReplacement = lines(
        "    pruneCrossLanguageEdges() {",
        "        try {",
        "            this.db.exec(`",
        "        DELETE FROM edges WHERE id IN (",
        "          SELECT e.id FROM edges e",
        "          JOIN nodes s ON e.source = s.id",
        "          JOIN nodes t ON e.target = t.id",
        "          WHERE e.kind IN ('imports', 'calls', 'extends', 'instantiates', 'decorates')",
        "          AND s.language != t.language",
        "          AND NOT (",
        "            (s.language IN ('kotlin','java','scala','clojure','groovy') AND t.language IN ('kotlin','java','scala','clojure','groovy')) OR",
        "            (s.language IN ('swift','objc','objcpp') AND t.language IN ('swift','objc','objcpp')) OR",
        "            (s.language IN ('typescript','tsx','javascript','jsx','arkts','vue','svelte','astro') AND t.language IN ('typescript','tsx','javascript','jsx','arkts','vue','svelte','astro')) OR",
        "            (s.language IN ('c','cpp') AND t.language IN ('c','cpp')) OR",
        "            (s.language IN ('csharp','razor') AND t.language IN ('csharp','razor'))",
        "          )",
        "        );",
        "        DELETE FROM name_segment_vocab WHERE name NOT IN (SELECT name FROM nodes);",
        "            `);",
        "        } catch { /* ignore */ }",
        "    }",
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
        "        try {",
        "            this.db.exec('DELETE FROM name_segment_vocab WHERE name NOT IN (SELECT name FROM nodes)');",
        "        } catch { /* ignore */ }",
        "    }",
    )

    private val patches = listOf(
        Patch("resolution/name-matcher.js", nameMatcherFamilyAnchor, nameMatcherFamilyReplacement),
        Patch("resolution/name-matcher.js", matcherAnchor, matcherReplacement),
        Patch("resolution/name-matcher.js", fuzzyAnchor, fuzzyReplacement),
        Patch("resolution/index.js", resolverAnchor, resolverReplacement),
        Patch("resolution/index.js", frameworkAnchor, frameworkReplacement),
        Patch("resolution/import-resolver.js", pythonModuleImportAnchor, pythonModuleImportReplacement),
        Patch("extraction/tree-sitter.js", treeSitterAnchor, treeSitterReplacement),
        Patch("extraction/extraction-version.js", extractionVersionAnchor, extractionVersionReplacement),
        Patch("mcp/tools.js", exploreAnchor, exploreReplacement),
        Patch("mcp/tools.js", exploreBudgetAnchor, exploreBudgetReplacement),
        Patch("mcp/tools.js", exploreSearchLimitAnchor, exploreSearchLimitReplacement),
        Patch("bin/codegraph.js", impactAnchor, impactReplacement),
        Patch("db/index.js", maintenanceAnchor, maintenanceReplacement),
        Patch("db/migrations.js", migrationVersionAnchor, migrationVersionReplacement),
        Patch("db/migrations.js", migrationListAnchor, migrationListReplacement),
        Patch("db/queries.js", insertUnresolvedAnchor, insertUnresolvedReplacement),
        Patch("db/queries.js", deleteNodesAnchor, deleteNodesReplacement),
        Patch("index.js", openPruneAnchor, openPruneReplacement),
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
