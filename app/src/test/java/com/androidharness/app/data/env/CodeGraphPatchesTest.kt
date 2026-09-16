package com.androidharness.app.data.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundle patches edit upstream's shipped JavaScript by exact match. What
 * has to hold is that they land once on the pristine release, heal the hybrid
 * states older patch generations left on devices, do nothing the second time,
 * and never touch a file whose shape they do not recognise.
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
        "    // If only one match, use it — but penalize cross-language matches",
        "    if (candidates.length === 1) {",
        "        const isCrossLanguage = candidates[0].language !== ref.language;",
        "        return {",
        "            original: ref,",
        "            targetNodeId: candidates[0].id,",
        "            confidence: isCrossLanguage ? 0.5 : 0.9,",
        "            resolvedBy: 'exact-match',",
        "        };",
        "    }",
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

    /** Generation 6/7 hybrid as found on devices: family line present, gates strict but unguarded. */
    private val matcherStaleSource = lines(
        "const LANGUAGE_FAMILY = {",
        "    typescript: 'web', tsx: 'web', javascript: 'web', jsx: 'web', arkts: 'web',",
        "    vue: 'web', svelte: 'web', astro: 'web',",
        "    c: 'c', cpp: 'c',",
        "};",
        "    if (ref.referenceKind === 'imports') {",
        "        return candidates.filter((c) => sameLanguageFamily(c.language, ref.language));",
        "    }",
        "    // Harness patch: a coincidental same-named symbol in another language is",
        "    // not a call, extends, or instantiation.",
        "    if (ref.referenceKind === 'calls' || ref.referenceKind === 'extends' || ref.referenceKind === 'instantiates') {",
        "        return candidates.filter((c) => sameLanguageFamily(c.language, ref.language));",
        "    }",
        "    return candidates;",
        "    // Harness patch: if only one match, strictly require same language family",
        "    if (candidates.length === 1) {",
        "        if (!sameLanguageFamily(candidates[0].language, ref.language)) {",
        "            return null;",
        "        }",
        "        return {",
        "            original: ref,",
        "            targetNodeId: candidates[0].id,",
        "            confidence: 0.9,",
        "            resolvedBy: 'exact-match',",
        "        };",
        "    }",
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
        "    return null;",
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
        "    createEdges(resolved) {",
        "        return resolved.map((ref) => {",
        "            let kind = ref.original.referenceKind;",
        "            return {",
        "                source: ref.original.fromNodeId,",
        "                target: ref.targetNodeId,",
        "                kind,",
        "                line: ref.original.line,",
        "                metadata: {",
        "                    confidence: ref.confidence,",
        "                },",
        "            };",
        "        });",
        "    }",
    )

    /** Generation 2..6 hybrid gateLanguage as found on devices. */
    private val gateStaleSource = lines(
        "    gateLanguage(result, ref) {",
        "        if (!result)",
        "            return result;",
        "        const tgt = this.getLanguageFromNodeId(result.targetNodeId);",
        "        if (!tgt || !ref.language)",
        "            return result;",
        "        if ((ref.referenceKind === 'references' || ref.referenceKind === 'function_ref') && !(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
        "            return null;",
        "        if (ref.referenceKind === 'imports' && !(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
        "            return null;",
        "        // Harness patch: the candidate filter's rule, restated for the case",
        "        // where the foreign match was the only candidate there was.",
        "        if ((ref.referenceKind === 'calls' || ref.referenceKind === 'extends' || ref.referenceKind === 'instantiates') &&",
        "            !(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
        "            return null;",
        "        return result;",
        "    }",
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

    /**
     * The hybrid createEdges a v7 generation left on devices: the loop
     * conversion landed but the pristine `return { … }` stayed inside the
     * loop, so the function returns a single edge object and every resolution
     * edge silently vanishes. The repair patch must heal exactly this shape.
     */
    private val createEdgesStaleSource = lines(
        "    createEdges(resolved) {",
        "        const out = [];",
        "        for (const ref of resolved) {",
        "            if (ref.original.fromNodeId === ref.targetNodeId) continue;",
        "            let kind = ref.original.referenceKind;",
        "            return {",
        "                source: ref.original.fromNodeId,",
        "                target: ref.targetNodeId,",
        "                kind,",
        "                line: ref.original.line,",
        "                metadata: {",
        "                    confidence: ref.confidence,",
        "                },",
        "            };",
        "            out.push(edge);",
        "            if (kind === 'imports') {",
        "                const fileNodes = [];",
        "                const fileNode = fileNodes.find((n) => n.kind === 'file');",
        "                if (fileNode) {",
        "                    out.push({ ...edge, source: fileNode.id });",
        "                }",
        "            }",
        "        }",
        "        return out;",
        "    }",
    )

    private val indexSource = lines(
        "        const db = db_1.DatabaseConnection.open(dbPath);",
        "        const queries = new queries_1.QueryBuilder(db.getDb());",
        "        const instance = new CodeGraph(db, queries, resolvedRoot);",
    )

    private val extractionVersionSource = "exports.EXTRACTION_VERSION = 25;"

    private val importResolverSource = lines(
        "function resolveModuleImportToFile(ref, imports, context) {",
        "    if (ref.referenceKind !== 'imports')",
        "        return null;",
        "    if (ref.referenceName.includes('.'))",
        "        return null;",
        "    for (const imp of imports) {",
        "        if (imp.localName !== ref.referenceName)",
        "            continue;",
        "        let modulePath;",
        "        if (imp.isNamespace || imp.isDefault) {",
        "            modulePath = imp.source;",
        "        }",
        "        return null;",
        "    }",
        "    return null;",
        "}",
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
        "        if (this.extractor.extractImport) {",
        "            const info = this.extractor.extractImport(node, this.source);",
        "            if (info) {",
        "                this.createNode('import', info.moduleName, node, {",
        "                    signature: info.signature,",
        "                });",
        "                // Create unresolved reference unless the hook handled it",
        "                if (!info.handledRefs && info.moduleName && this.nodeStack.length > 0) {",
        "                    const parentId = this.nodeStack[this.nodeStack.length - 1];",
        "                    if (parentId) {",
        "                        this.unresolvedReferences.push({",
        "                            fromNodeId: parentId,",
        "                            referenceName: info.moduleName,",
        "                            referenceKind: 'imports',",
        "                            line: node.startPosition.row + 1,",
        "                            column: node.startPosition.column,",
        "                        });",
        "                    }",
        "                }",
        "                if (child?.type === 'dotted_name') {",
        "                    this.createNode('import', (0, tree_sitter_helpers_1.getNodeText)(child, this.source), node, {",
        "                        signature: importText,",
        "                    });",
        "                    pushModuleRef(child);",
        "                }",
    )

    /** Generation 7 hybrid: junk filter and impNode ref already applied; only the hook is stale. */
    private val treeSitterStaleSource = lines(
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
        "        if (this.extractor.extractImport) {",
        "            const info = this.extractor.extractImport(node, this.source);",
        "            if (info) {",
        "                const importNode = this.createNode('import', info.moduleName, node, {",
        "                    signature: info.signature,",
        "                });",
        "                // Create unresolved reference attached to the import node",
        "                if (!info.handledRefs && info.moduleName && this.nodeStack.length > 0) {",
        "                    const parentId = this.nodeStack[this.nodeStack.length - 1];",
        "                    const fromId = importNode ? importNode.id : parentId;",
        "                    if (fromId) {",
        "                        this.unresolvedReferences.push({",
        "                            fromNodeId: fromId,",
        "                            referenceName: info.moduleName,",
        "                            referenceKind: 'imports',",
        "                            line: node.startPosition.row + 1,",
        "                            column: node.startPosition.column,",
        "                        });",
        "                    }",
        "                }",
        "                if (child?.type === 'dotted_name') {",
        "                    const impNode = this.createNode('import', (0, tree_sitter_helpers_1.getNodeText)(child, this.source), node, {",
        "                        signature: importText,",
        "                    });",
        "                    pushModuleRef(child);",
        "                    if (impNode) {",
        "                        this.unresolvedReferences.push({",
        "                            fromNodeId: impNode.id,",
        "                            referenceName: (0, tree_sitter_helpers_1.getNodeText)(child, this.source),",
        "                            referenceKind: 'imports',",
        "                            line: child.startPosition.row + 1,",
        "                            column: child.startPosition.column,",
        "                        });",
        "                    }",
        "                }",
    )

    private val toolsSource = lines(
        "        .replace(/\\b([A-Za-z_][\\w@]*)\\/(\\d{1,3})(?=$|[\\s,()[\\]/])/g, '$1')",
        "    CLIFF_FRACTION: 0.15,",
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
        "        const hardCeiling = Math.min(Math.round(budget.maxOutputChars * 1.5), 25000);",
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

    /** Generation 3/4 hybrid: normalize, cliff, summary already applied; budget and searchLimit stale. */
    private val toolsStaleSource = lines(
        "        .replace(/(?<![\\w/])([A-Za-z_][\\w@]*)\\/(\\d{1,3})(?=$|[\\s,()[\\]])/g, '$1')",
        "    CLIFF_FRACTION: 0.05,",
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
        "        const hardCeiling = Math.min(Math.round(budget.maxOutputChars * 1.5), 25000);",
        "        const subgraph = await cg.findRelevantContext(matchQuery, {",
        "            searchLimit: Math.max(24, maxFiles * 2),",
        "            traversalDepth: 3,",
        "            maxNodes: 200,",
        "            minScore: 0.2,",
        "        });",
        "        const totalFound = subgraph.nodes.size;",
        "        const countNote = (survivors.length > 0 && totalFound > shownSymbols) ? ` (showing \${shownSymbols} of \${totalFound})` : '';",
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

    /** Generation 4 hybrid maintenance as found on devices. */
    private val dbStaleSource = lines(
        "        await this.runPragmasOffThread(['PRAGMA analysis_limit=1000', 'PRAGMA optimize', 'ANALYZE', 'PRAGMA wal_checkpoint(PASSIVE)'], ",
        "        // Worker threads unavailable — bounded in-line fallback, no checkpoint.",
        "        ['PRAGMA analysis_limit=1000', 'PRAGMA optimize', 'ANALYZE']);",
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

    /** Generation 4..8 hybrid: migration 10 without the metadata update, no migration 11. */
    private val migrationsStaleSource = lines(
        "exports.CURRENT_SCHEMA_VERSION = 11;",
        "const migrations = [",
        "    {",
        "        version: 10,",
        "        description: 'Prune cross-language edges, unique unresolved refs index, and prune vocab orphans',",
        "        up: (db) => {",
        "            db.exec(`",
        "        DELETE FROM name_segment_vocab WHERE name NOT IN (SELECT name FROM nodes);",
        "      `);",
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

    /** Generation 6/7 hybrid: INSERT OR IGNORE and vocab prune applied; method missing. */
    private val queriesStaleSource = lines(
        "this.runBatched('insertUnresolvedRefs', 'INSERT OR IGNORE INTO unresolved_refs (from_node_id, reference_name, reference_kind, line, col, candidates, file_path, language) VALUES ', '(?,?,?,?,?,?,?,?)', rows);",
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

    private fun tempDir(): File {
        val dir = File.createTempFile("cgpatch", "")
        dir.delete()
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }

    private fun bundle(
        matcher: String = matcherSource,
        resolver: String = resolverSource,
        treeSitter: String = treeSitterSource,
        tools: String = toolsSource,
        db: String = dbSource,
        migrations: String = migrationsSource,
        queries: String = queriesSource,
    ): File {
        val dist = tempDir()
        File(dist, "resolution").mkdirs()
        File(dist, "extraction").mkdirs()
        File(dist, "mcp").mkdirs()
        File(dist, "bin").mkdirs()
        File(dist, "db").mkdirs()
        File(dist, "index.js").writeText(indexSource)
        File(dist, "resolution/name-matcher.js").writeText(matcher)
        File(dist, "resolution/index.js").writeText(resolver)
        File(dist, "resolution/import-resolver.js").writeText(importResolverSource)
        File(dist, "extraction/tree-sitter.js").writeText(treeSitter)
        File(dist, "extraction/extraction-version.js").writeText(extractionVersionSource)
        File(dist, "mcp/tools.js").writeText(tools)
        File(dist, "bin/codegraph.js").writeText(binSource)
        File(dist, "db/index.js").writeText(db)
        File(dist, "db/migrations.js").writeText(migrations)
        File(dist, "db/queries.js").writeText(queries)
        return dist
    }

    @Test
    fun `applies all bundle patches to a pristine release`() {
        val dist = bundle()

        val result = CodeGraphBundlePatches.apply(dist)

        assertTrue("anchors must match the shipped shape: ${result.unresolved}", result.unresolved.isEmpty())
        assertEquals("every patched file should be touched", CodeGraphBundlePatches.patchedFiles.size, result.applied.toSet().size)

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
        assertTrue("name-matcher gates candidates by family with guard", matcher.contains("if (!ref.language) return candidates;"))
        assertTrue("name-matcher single exact match guarded", matcher.contains("ref.language && !sameLanguageFamily"))
        assertTrue("name-matcher fuzzy guarded", matcher.contains("sameLanguageCandidates = ref.language"))
        assertTrue("resolver drops self-loops and falls back to node language", resolver.contains("const refLang = ref.language || this.getLanguageFromNodeId(ref.fromNodeId);"))
        assertTrue("framework gate rejects cross-language decorates", resolver.contains("ref.referenceKind === 'decorates'"))
        assertTrue("createEdges wrapper adds self-loop drop and dual import edge", resolver.contains("createEdgesBase(resolved) {") && resolver.contains("edge.source === edge.target") && resolver.contains("out.push({ ...edge, source: fileNode.id });"))
        assertTrue("index.js performs retroactive prune on open", index.contains("pruneCrossLanguageEdges()"))
        assertTrue("import resolver supports bare python module import", importResolver.contains("bare single module imports"))
        assertTrue("import resolver matches TS relative imports", importResolver.contains("imp.source === ref.referenceName"))
        assertTrue("tree-sitter rejects junk AST names", treeSitter.contains("name.startsWith('from ')"))
        assertTrue("tree-sitter emits refs from file AND import node", treeSitter.contains("importNode.id !== parentId"))
        assertTrue("extraction version bumped to 26", extractionVersion.contains("EXTRACTION_VERSION = 26;"))
        assertTrue("normalizeQuery preserves storage path", tools.contains("(?<![\\w/])"))
        assertTrue("mcp explore shows truncation note", tools.contains("showing \${shownSymbols} of \${totalFound}"))
        assertTrue("mcp explore relaxes cliff fraction", tools.contains("CLIFF_FRACTION: 0.05"))
        assertTrue("mcp explore raises searchLimit", tools.contains("Math.max(30, maxFiles * 3)"))
        assertTrue("mcp explore raises base output budget", tools.contains("maxOutputChars: 40000"))
        assertTrue("mcp explore raises hard ceiling", tools.contains("40000);"))
        assertTrue("bin impact shows multi-def note", bin.contains("definitions named"))
        assertTrue("db maintenance excludes virtual FTS table", db.contains("ANALYZE nodes"))
        assertTrue("migrations bumps version to 11", migrations.contains("CURRENT_SCHEMA_VERSION = 11;"))
        assertTrue("migrations includes version 11", migrations.contains("version: 11,"))
        assertTrue("migrations 11 vacuums freelist", migrations.contains("PRAGMA auto_vacuum = INCREMENTAL;"))
        assertTrue("queries uses INSERT OR IGNORE", queries.contains("INSERT OR IGNORE INTO unresolved_refs"))
        assertTrue("queries has pruneCrossLanguageEdges method", queries.contains("pruneCrossLanguageEdges()"))
        assertTrue("queries prunes vocab on file deletion", queries.contains("DELETE FROM name_segment_vocab WHERE name NOT IN"))
    }

    @Test
    fun `heals the hybrid states older generations left on devices`() {
        val dist = bundle(
            matcher = matcherStaleSource,
            resolver = gateStaleSource + "\n" + createEdgesStaleSource,
            treeSitter = treeSitterStaleSource,
            tools = toolsStaleSource,
            db = dbStaleSource,
            migrations = migrationsStaleSource,
            queries = queriesStaleSource,
        )

        val result = CodeGraphBundlePatches.apply(dist)

        assertTrue("every hybrid state must heal: ${result.unresolved}", result.unresolved.isEmpty())

        val matcher = File(dist, "resolution/name-matcher.js").readText()
        val resolver = File(dist, "resolution/index.js").readText()
        val treeSitter = File(dist, "extraction/tree-sitter.js").readText()
        val tools = File(dist, "mcp/tools.js").readText()
        val db = File(dist, "db/index.js").readText()
        val migrations = File(dist, "db/migrations.js").readText()
        val queries = File(dist, "db/queries.js").readText()

        assertTrue("matcher healed to guarded gate", matcher.contains("ref.referenceKind === 'decorates'") && matcher.contains("if (!ref.language) return candidates;"))
        assertTrue("matcher exact single guarded", matcher.contains("ref.language && !sameLanguageFamily"))
        assertTrue("matcher fuzzy guarded", matcher.contains("sameLanguageCandidates = ref.language"))
        assertTrue("gateLanguage healed to self-loop + language fallback", resolver.contains("const refLang = ref.language || this.getLanguageFromNodeId(ref.fromNodeId);"))
        assertTrue("createEdges repair converted the stray return", resolver.contains("const edge = {") && resolver.contains("out.push(edge);") && !resolver.contains("            return {\n                source: ref.original.fromNodeId,"))
        assertTrue("createEdges repair keeps the loop returning the array", resolver.contains("return out;"))
        assertTrue("tree-sitter healed to dual refs", treeSitter.contains("importNode.id !== parentId"))
        assertTrue("tools budget healed", tools.contains("maxOutputChars: 40000"))
        assertTrue("tools searchLimit healed", tools.contains("Math.max(30, maxFiles * 3)"))
        assertTrue("db maintenance healed", db.contains("ANALYZE nodes"))
        assertTrue("migrations healed with migration 11", migrations.contains("version: 11,") && migrations.contains("PRAGMA auto_vacuum = INCREMENTAL;"))
        assertTrue("queries healed with prune method", queries.contains("pruneCrossLanguageEdges() {"))
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
