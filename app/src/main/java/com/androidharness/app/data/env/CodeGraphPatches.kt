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
 * marker that says the work is already done. Because patch generations
 * evolved while devices kept their extracted copy, a bundle on a device can
 * be a mix of generations, so every patch also carries [Patch.fallbackAnchors]
 * for the intermediate states older generations left behind. A patch whose
 * anchors all miss is reported as unresolved and the file is left exactly as
 * it was; [CodeGraphManager] then restores the pristine snapshot and retries,
 * so an unknown stale state still converges instead of persisting forever.
 *
 * The anchors below are written as explicit line lists rather than indented
 * Kotlin strings, because `trimIndent()` would strip the very leading spaces
 * that have to match the file byte for byte.
 */
internal object CodeGraphBundlePatches {

    /** Bumped whenever [patches] changes, so an install can tell old from new. */
    const val VERSION = 9

    /** Paths (relative to `lib/dist`) that [patches] may rewrite. */
    internal val patchedFiles = listOf(
        "resolution/name-matcher.js",
        "resolution/index.js",
        "resolution/import-resolver.js",
        "extraction/tree-sitter.js",
        "extraction/extraction-version.js",
        "mcp/tools.js",
        "bin/codegraph.js",
        "db/index.js",
        "db/migrations.js",
        "db/queries.js",
        "index.js",
    )

    internal data class Patch(
        val relativePath: String,
        val anchor: String,
        val replacement: String,
        val fallbackAnchors: List<String> = emptyList(),
        /** Replacements paired with [fallbackAnchors]; falls back to [replacement]. */
        val fallbackReplacements: List<String> = emptyList(),
        /**
         * When set, the patch is skipped (treated as applied) once the file
         * contains any of these texts, even if no anchor matches. Marks the
         * end states — including repaired hybrids — whose text no longer
         * matches this patch's own replacement.
         */
        val satisfiedMarkers: List<String> = emptyList(),
        /**
         * When set, the patch only applies to a file that already contains
         * this text. Used for repairs of a specific hybrid state that must
         * not fire on the pristine bundle.
         */
        val requiresMarker: String? = null,
    )

    internal data class Result(val applied: List<String>, val unresolved: List<String>) {
        val changed: Boolean get() = applied.isNotEmpty()
    }

    private fun lines(vararg lines: String): String = lines.joinToString("\n")

    // ------------------------------------------------------------------
    // resolution/name-matcher.js
    // ------------------------------------------------------------------

    // Add single-file-component languages to the web family
    private val nameMatcherFamilyAnchor = "    typescript: 'web', tsx: 'web', javascript: 'web', jsx: 'web', arkts: 'web',"
    private val nameMatcherFamilyReplacement = lines(
        "    typescript: 'web', tsx: 'web', javascript: 'web', jsx: 'web', arkts: 'web',",
        "    vue: 'web', svelte: 'web', astro: 'web',",
    )

    // Candidate filter: strict same-family, guarded against refs without a
    // language. Fallbacks cover the intermediate generations that gated only
    // some reference kinds or lacked the guard.
    private val matcherAnchor = lines(
        "    if (ref.referenceKind === 'imports') {",
        "        return candidates.filter((c) => !crossesKnownFamily(c.language, ref.language));",
        "    }",
        "    return candidates;",
    )

    private val matcherReplacement = lines(
        "    if (ref.referenceKind === 'imports') {",
        "        if (!ref.language) return candidates;",
        "        return candidates.filter((c) => sameLanguageFamily(c.language, ref.language));",
        "    }",
        "    if (ref.referenceKind === 'calls' || ref.referenceKind === 'extends' || ref.referenceKind === 'instantiates' || ref.referenceKind === 'decorates') {",
        "        if (!ref.language) return candidates;",
        "        return candidates.filter((c) => sameLanguageFamily(c.language, ref.language));",
        "    }",
        "    return candidates;",
    )

    // Generation 6/7 state: imports gated but no guard and no decorates.
    private val matcherFallback1 = lines(
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

    // Single exact match must never cross a language family, and a ref with no
    // language must not be dropped by the check.
    private val exactSingleAnchor = lines(
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
    )

    private val exactSingleReplacement = lines(
        "    // Harness patch: if only one match, strictly require same language family",
        "    if (candidates.length === 1) {",
        "        if (ref.language && !sameLanguageFamily(candidates[0].language, ref.language)) {",
        "            return null;",
        "        }",
        "        return {",
        "            original: ref,",
        "            targetNodeId: candidates[0].id,",
        "            confidence: 0.9,",
        "            resolvedBy: 'exact-match',",
        "        };",
        "    }",
    )

    // Generation 5/6 state: unconditional check without the ref.language guard.
    private val exactSingleFallback1 = lines(
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
    )

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
        "    const sameLanguageCandidates = ref.language",
        "        ? callableCandidates.filter((n) => sameLanguageFamily(n.language, ref.language))",
        "        : callableCandidates;",
        "    if (sameLanguageCandidates.length === 1) {",
        "        return {",
        "            original: ref,",
        "            targetNodeId: sameLanguageCandidates[0].id,",
        "            confidence: 0.5,",
        "            resolvedBy: 'fuzzy',",
        "        };",
        "    }",
    )

    // Generation 6/7 state: strict filter but no ref.language guard.
    private val fuzzyFallback1 = lines(
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

    // ------------------------------------------------------------------
    // resolution/index.js
    // ------------------------------------------------------------------

    // gateLanguage: drop self-loops, resolve the ref's language from its node
    // when missing, and drop every cross-family resolution.
    private val resolverAnchor = lines(
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
    )

    private val resolverReplacement = lines(
        "    gateLanguage(result, ref) {",
        "        if (!result)",
        "            return result;",
        "        if (result.targetNodeId === ref.fromNodeId)",
        "            return null;",
        "        const tgt = this.getLanguageFromNodeId(result.targetNodeId);",
        "        const refLang = ref.language || this.getLanguageFromNodeId(ref.fromNodeId);",
        "        if (tgt && refLang && !(0, name_matcher_1.sameLanguageFamily)(tgt, refLang))",
        "            return null;",
        "        return result;",
        "    }",
    )

    // Generation 2..6 states: per-kind family gates without the self-loop
    // check or the language fallback. All of them share the same opening.
    private val gateCommonHead = lines(
        "    gateLanguage(result, ref) {",
        "        if (!result)",
        "            return result;",
        "        const tgt = this.getLanguageFromNodeId(result.targetNodeId);",
        "        if (!tgt || !ref.language)",
        "            return result;",
    )

    private val gateCommonTail = lines(
        "        return result;",
        "    }",
    )

    private val gateFallbackBodies = listOf(
        // Generation 4/5: imports + calls/extends/instantiates blocks.
        lines(
            "        if ((ref.referenceKind === 'references' || ref.referenceKind === 'function_ref') && !(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
            "            return null;",
            "        if (ref.referenceKind === 'imports' && !(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
            "            return null;",
            "        // Harness patch: the candidate filter's rule, restated for the case",
            "        // where the foreign match was the only candidate there was.",
            "        if ((ref.referenceKind === 'calls' || ref.referenceKind === 'extends' || ref.referenceKind === 'instantiates') &&",
            "            !(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
            "            return null;",
        ),
        // Generation 5/6: imports + calls/extends/instantiates with a Harness comment.
        lines(
            "        if ((ref.referenceKind === 'references' || ref.referenceKind === 'function_ref') && !(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
            "            return null;",
            "        if (ref.referenceKind === 'imports' && !(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
            "            return null;",
            "        // Harness patch: the candidate filter's rule, restated for the case",
            "        // where the foreign match was the only candidate there was.",
            "        if ((ref.referenceKind === 'calls' || ref.referenceKind === 'extends' || ref.referenceKind === 'instantiates' || ref.referenceKind === 'decorates') &&",
            "            !(0, name_matcher_1.sameLanguageFamily)(tgt, ref.language))",
            "            return null;",
        ),
    )

    // gateFrameworkLanguage: keep config↔code and calls bridges, gate the rest.
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

    private val frameworkFallback1 = lines(
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
     * Repairs the hybrid createEdges a patch generation left on some devices:
     * the loop conversion landed but the pristine `return { … }` stayed inside
     * the loop body, so the function returns a single edge object on the first
     * reference — `.length` is undefined, insertEdges is never called, and
     * every resolution edge silently vanishes while the refs are deleted as
     * resolved. Converting that stray `return {` into `const edge = {` makes
     * the loop whole again. Guarded by requiresMarker so it never fires on a
     * pristine bundle.
     */
    private val createEdgesRepair = Patch(
        "resolution/index.js",
        lines(
            "            return {",
            "                source: ref.original.fromNodeId,",
        ),
        lines(
            "            const edge = {",
            "                source: ref.original.fromNodeId,",
        ),
        requiresMarker = "out.push(edge);",
        satisfiedMarkers = listOf("const edge = {", "createEdgesBase(resolved) {"),
    )

    /**
     * The pristine release keeps upstream's single-expression `createEdges`.
     * Rather than rewriting the whole body (a partial conversion is exactly
     * what stranded devices on a broken hybrid), the original body is renamed
     * to `createEdgesBase` and a wrapper adds the two invariants upstream
     * lacks: no edge whose source is its own target, and every import that
     * resolves from an import node is mirrored by an edge from the importing
     * file, so the module graph traverses from either end. One atomic replace,
     * so the file is never left between two shapes.
     */
    private val createEdgesWrapper = Patch(
        "resolution/index.js",
        lines(
            "    createEdges(resolved) {",
            "        return resolved.map((ref) => {",
        ),
        lines(
            "    createEdges(resolved) {",
            "        const mapped = this.createEdgesBase(resolved);",
            "        const out = [];",
            "        for (const edge of mapped) {",
            "            if (!edge || edge.source === edge.target) continue;",
            "            out.push(edge);",
            "            if (edge.kind === 'imports') {",
            "                const srcNode = this.queries.getNodeById(edge.source);",
            "                if (srcNode && srcNode.kind === 'import') {",
            "                    const fileNodes = this.context.getNodesInFile(srcNode.filePath);",
            "                    const fileNode = fileNodes ? fileNodes.find((n) => n.kind === 'file') : null;",
            "                    if (fileNode && fileNode.id !== edge.target) {",
            "                        out.push({ ...edge, source: fileNode.id });",
            "                    }",
            "                }",
            "            }",
            "        }",
            "        return out;",
            "    }",
            "    createEdgesBase(resolved) {",
            "        return resolved.map((ref) => {",
        ),
        satisfiedMarkers = listOf("createEdgesBase(resolved) {", "out.push(edge);"),
    )

    // ------------------------------------------------------------------
    // resolution/import-resolver.js
    // ------------------------------------------------------------------

    // Relative module paths (./dep_mod) resolve through the import source.
    private val resolveModuleImportAnchor = lines(
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
    )

    private val resolveModuleImportReplacement = lines(
        "function resolveModuleImportToFile(ref, imports, context) {",
        "    if (ref.referenceKind !== 'imports')",
        "        return null;",
        "    if (ref.referenceName.includes('.') && !ref.referenceName.startsWith('.'))",
        "        return null;",
        "    for (const imp of imports) {",
        "        if (imp.localName !== ref.referenceName && imp.source !== ref.referenceName)",
        "            continue;",
        "        let modulePath;",
        "        if (imp.isNamespace || imp.isDefault || imp.source === ref.referenceName) {",
    )

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

    // ------------------------------------------------------------------
    // extraction/tree-sitter.js
    // ------------------------------------------------------------------

    // Import statements produce references from BOTH the enclosing file and
    // the import node, so traversal works from either end of the module graph.
    private val importHookNodeAnchor = lines(
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
    )

    private val importHookNodeReplacement = lines(
        "        if (this.extractor.extractImport) {",
        "            const info = this.extractor.extractImport(node, this.source);",
        "            if (info) {",
        "                const importNode = this.createNode('import', info.moduleName, node, {",
        "                    signature: info.signature,",
        "                });",
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
        "                    if (importNode && importNode.id !== parentId) {",
        "                        this.unresolvedReferences.push({",
        "                            fromNodeId: importNode.id,",
        "                            referenceName: info.moduleName,",
        "                            referenceKind: 'imports',",
        "                            line: node.startPosition.row + 1,",
        "                            column: node.startPosition.column,",
        "                        });",
        "                    }",
        "                }",
    )

    // Generation 7 state: the import node REPLACED the file as the ref source.
    private val importHookNodeFallback1 = lines(
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
    )

    private val importHookNodeFallback1Replacement = lines(
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
        "                    if (importNode && importNode.id !== parentId) {",
        "                        this.unresolvedReferences.push({",
        "                            fromNodeId: importNode.id,",
        "                            referenceName: info.moduleName,",
        "                            referenceKind: 'imports',",
        "                            line: node.startPosition.row + 1,",
        "                            column: node.startPosition.column,",
        "                        });",
        "                    }",
        "                }",
    )

    private val pythonImportStmtAnchor = lines(
        "                if (child?.type === 'dotted_name') {",
        "                    this.createNode('import', (0, tree_sitter_helpers_1.getNodeText)(child, this.source), node, {",
        "                        signature: importText,",
        "                    });",
        "                    pushModuleRef(child);",
        "                }",
    )

    private val pythonImportStmtReplacement = lines(
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

    // Junk AST tokens must not become queryable symbols.
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

    // ------------------------------------------------------------------
    // extraction/extraction-version.js
    // ------------------------------------------------------------------

    private val extractionVersionAnchor = "exports.EXTRACTION_VERSION = 25;"
    private val extractionVersionReplacement = "exports.EXTRACTION_VERSION = 26;"

    // ------------------------------------------------------------------
    // mcp/tools.js
    // ------------------------------------------------------------------

    // Preserve /storage/emulated/0/… in query path normalization: the old
    // word-boundary form ate the "/0" segment of Android shared-storage paths.
    private val normalizeQueryAnchor = "        .replace(/\\b([A-Za-z_][\\w@]*)\\/(\\d{1,3})(?=\$|[\\s,()[\\]/])/g, '\$1')"
    private val normalizeQueryReplacement = "        .replace(/(?<![\\w/])([A-Za-z_][\\w@]*)\\/(\\d{1,3})(?=\$|[\\s,()[\\]])/g, '\$1')"

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

    private val exploreCliffAnchor = "    CLIFF_FRACTION: 0.15,"
    private val exploreCliffReplacement = "    CLIFF_FRACTION: 0.05,"

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
        "            maxOutputChars: 40000,",
        "            defaultMaxFiles: 20,",
        "            maxCharsPerFile: 8000,",
        "            gapThreshold: 7,",
        "            maxSymbolsInFileHeader: 30,",
        "            maxEdgesPerRelationshipKind: 4,",
        "            includeRelationships: false,",
        "            includeAdditionalFiles: false,",
        "            includeCompletenessSignal: false,",
        "            includeBudgetNote: false,",
        "        };",
        "    }",
    )

    // Generation 3 state: 24000/8/6500/12 without the comment block.
    private val exploreBudgetFallback1 = lines(
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

    // Generation 5 state: 32000/12/7500/20.
    private val exploreBudgetFallback2 = lines(
        "    if (fileCount < 150) {",
        "        return {",
        "            maxOutputChars: 32000,",
        "            defaultMaxFiles: 12,",
        "            maxCharsPerFile: 7500,",
        "            gapThreshold: 7,",
        "            maxSymbolsInFileHeader: 20,",
        "            maxEdgesPerRelationshipKind: 4,",
        "            includeRelationships: false,",
        "            includeAdditionalFiles: false,",
        "            includeCompletenessSignal: false,",
        "            includeBudgetNote: false,",
        "        };",
        "    }",
    )

    // The hard ceiling caps the whole response regardless of the budget.
    private val exploreHardCeilingAnchor = "        const hardCeiling = Math.min(Math.round(budget.maxOutputChars * 1.5), 25000);"
    private val exploreHardCeilingReplacement = "        const hardCeiling = Math.min(Math.round(budget.maxOutputChars * 1.5), 40000);"

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
        "            searchLimit: Math.max(30, maxFiles * 3),",
        "            traversalDepth: 3,",
        "            maxNodes: 200,",
        "            minScore: 0.05,",
        "        });",
    )

    // Generation 4 state: 24/2 scaling without the minScore change.
    private val exploreSearchLimitFallback1 = lines(
        "            searchLimit: Math.max(24, maxFiles * 2),",
        "            traversalDepth: 3,",
        "            maxNodes: 200,",
        "            minScore: 0.2,",
    )

    // ------------------------------------------------------------------
    // bin/codegraph.js
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // db/index.js
    // ------------------------------------------------------------------

    // Maintenance: analyze the real tables (not the FTS shadows) and keep the
    // freelist drained. Fallback covers the intermediate plain-ANALYZE state.
    private val maintenanceAnchor = lines(
        "        await this.runPragmasOffThread(['PRAGMA analysis_limit=1000', 'PRAGMA optimize', 'PRAGMA wal_checkpoint(PASSIVE)'], ",
        "        // Worker threads unavailable — bounded in-line fallback, no checkpoint.",
        "        ['PRAGMA analysis_limit=1000', 'PRAGMA optimize']);",
    )

    private val maintenanceReplacement = lines(
        "        await this.runPragmasOffThread(['PRAGMA analysis_limit=1000', 'PRAGMA optimize', 'ANALYZE nodes', 'ANALYZE edges', 'ANALYZE files', 'ANALYZE unresolved_refs', 'PRAGMA incremental_vacuum', 'PRAGMA wal_checkpoint(PASSIVE)'], ",
        "        // Worker threads unavailable — bounded in-line fallback, no checkpoint.",
        "        ['PRAGMA analysis_limit=1000', 'PRAGMA optimize', 'ANALYZE nodes', 'ANALYZE edges', 'ANALYZE files', 'ANALYZE unresolved_refs', 'PRAGMA incremental_vacuum']);",
    )

    private val maintenanceFallback1 = lines(
        "        await this.runPragmasOffThread(['PRAGMA analysis_limit=1000', 'PRAGMA optimize', 'ANALYZE', 'PRAGMA wal_checkpoint(PASSIVE)'], ",
        "        // Worker threads unavailable — bounded in-line fallback, no checkpoint.",
        "        ['PRAGMA analysis_limit=1000', 'PRAGMA optimize', 'ANALYZE']);",
    )

    // ------------------------------------------------------------------
    // db/migrations.js
    // ------------------------------------------------------------------

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
        "          WHERE s.language != t.language",
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
        "        UPDATE project_metadata SET value = '26' WHERE key = 'indexed_with_extraction_version';",
        "      `);",
        "        },",
        "    },",
        "    {",
        "        version: 11,",
        "        description: 'Compact the database freelist after the cross-language prune',",
        "        up: (db) => {",
        "            db.exec(`",
        "        DELETE FROM edges WHERE id IN (",
        "          SELECT e.id FROM edges e",
        "          JOIN nodes s ON e.source = s.id",
        "          JOIN nodes t ON e.target = t.id",
        "          WHERE s.language != t.language",
        "          AND NOT (",
        "            (s.language IN ('kotlin','java','scala','clojure','groovy') AND t.language IN ('kotlin','java','scala','clojure','groovy')) OR",
        "            (s.language IN ('swift','objc','objcpp') AND t.language IN ('swift','objc','objcpp')) OR",
        "            (s.language IN ('typescript','tsx','javascript','jsx','arkts','vue','svelte','astro') AND t.language IN ('typescript','tsx','javascript','jsx','arkts','vue','svelte','astro')) OR",
        "            (s.language IN ('c','cpp') AND t.language IN ('c','cpp')) OR",
        "            (s.language IN ('csharp','razor') AND t.language IN ('csharp','razor'))",
        "          )",
        "        );",
        "        DELETE FROM name_segment_vocab WHERE name NOT IN (SELECT name FROM nodes);",
        "        UPDATE project_metadata SET value = '26' WHERE key = 'indexed_with_extraction_version';",
        "        PRAGMA auto_vacuum = INCREMENTAL;",
        "        VACUUM;",
        "      `);",
        "        },",
        "    },",
        "];",
    )

    // Generation-4..8 devices already carry a migration 10 without the
    // metadata update and without migration 11. Rewrite its tail and append 11.
    private val migrationListFallback1 = lines(
        "        DELETE FROM name_segment_vocab WHERE name NOT IN (SELECT name FROM nodes);",
        "      `);",
        "        },",
        "    },",
        "];",
    )

    private val migrationListFallback1Replacement = lines(
        "        DELETE FROM name_segment_vocab WHERE name NOT IN (SELECT name FROM nodes);",
        "        UPDATE project_metadata SET value = '26' WHERE key = 'indexed_with_extraction_version';",
        "      `);",
        "        },",
        "    },",
        "    {",
        "        version: 11,",
        "        description: 'Compact the database freelist after the cross-language prune',",
        "        up: (db) => {",
        "            db.exec(`",
        "        DELETE FROM edges WHERE id IN (",
        "          SELECT e.id FROM edges e",
        "          JOIN nodes s ON e.source = s.id",
        "          JOIN nodes t ON e.target = t.id",
        "          WHERE s.language != t.language",
        "          AND NOT (",
        "            (s.language IN ('kotlin','java','scala','clojure','groovy') AND t.language IN ('kotlin','java','scala','clojure','groovy')) OR",
        "            (s.language IN ('swift','objc','objcpp') AND t.language IN ('swift','objc','objcpp')) OR",
        "            (s.language IN ('typescript','tsx','javascript','jsx','arkts','vue','svelte','astro') AND t.language IN ('typescript','tsx','javascript','jsx','arkts','vue','svelte','astro')) OR",
        "            (s.language IN ('c','cpp') AND t.language IN ('c','cpp')) OR",
        "            (s.language IN ('csharp','razor') AND t.language IN ('csharp','razor'))",
        "          )",
        "        );",
        "        DELETE FROM name_segment_vocab WHERE name NOT IN (SELECT name FROM nodes);",
        "        UPDATE project_metadata SET value = '26' WHERE key = 'indexed_with_extraction_version';",
        "        PRAGMA auto_vacuum = INCREMENTAL;",
        "        VACUUM;",
        "      `);",
        "        },",
        "    },",
        "];",
    )

    // ------------------------------------------------------------------
    // db/queries.js
    // ------------------------------------------------------------------

    private val insertUnresolvedAnchor = "this.runBatched('insertUnresolvedRefs', 'INSERT INTO unresolved_refs (from_node_id, reference_name, reference_kind, line, col, candidates, file_path, language) VALUES ', '(?,?,?,?,?,?,?,?)', rows);"
    private val insertUnresolvedReplacement = "this.runBatched('insertUnresolvedRefs', 'INSERT OR IGNORE INTO unresolved_refs (from_node_id, reference_name, reference_kind, line, col, candidates, file_path, language) VALUES ', '(?,?,?,?,?,?,?,?)', rows);"

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

    private val pruneMethod = lines(
        "    pruneCrossLanguageEdges() {",
        "        try {",
        "            this.db.exec(`",
        "        DELETE FROM edges WHERE id IN (",
        "          SELECT e.id FROM edges e",
        "          JOIN nodes s ON e.source = s.id",
        "          JOIN nodes t ON e.target = t.id",
        "          WHERE s.language != t.language",
        "          AND NOT (",
        "            (s.language IN ('kotlin','java','scala','clojure','groovy') AND t.language IN ('kotlin','java','scala','clojure','groovy')) OR",
        "            (s.language IN ('swift','objc','objcpp') AND t.language IN ('swift','objc','objcpp')) OR",
        "            (s.language IN ('typescript','tsx','javascript','jsx','arkts','vue','svelte','astro') AND t.language IN ('typescript','tsx','javascript','jsx','arkts','vue','svelte','astro')) OR",
        "            (s.language IN ('c','cpp') AND t.language IN ('c','cpp')) OR",
        "            (s.language IN ('csharp','razor') AND t.language IN ('csharp','razor'))",
        "          )",
        "        );",
        "        DELETE FROM name_segment_vocab WHERE name NOT IN (SELECT name FROM nodes);",
        "        UPDATE project_metadata SET value = '26' WHERE key = 'indexed_with_extraction_version';",
        "        PRAGMA incremental_vacuum;",
        "            `);",
        "        } catch { /* ignore */ }",
        "    }",
    )

    private val deleteNodesReplacement = pruneMethod + "\n" + lines(
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

    // Generation 6/7 state: the vocab prune exists but the method is missing.
    private val deleteNodesFallback1 = lines(
        "        this.stmts.deleteNodesByFile.run(filePath);",
        "        try {",
        "            this.db.exec('DELETE FROM name_segment_vocab WHERE name NOT IN (SELECT name FROM nodes)');",
        "        } catch { /* ignore */ }",
        "    }",
    )

    private val deleteNodesFallback1Replacement = lines(
        "        this.stmts.deleteNodesByFile.run(filePath);",
        "        try {",
        "            this.db.exec('DELETE FROM name_segment_vocab WHERE name NOT IN (SELECT name FROM nodes)');",
        "        } catch { /* ignore */ }",
        "    }",
    ) + "\n" + pruneMethod

    // ------------------------------------------------------------------
    // index.js
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------

    private val patches = listOf(
        Patch("resolution/name-matcher.js", nameMatcherFamilyAnchor, nameMatcherFamilyReplacement),
        Patch(
            "resolution/name-matcher.js", matcherAnchor, matcherReplacement,
            fallbackAnchors = listOf(matcherFallback1),
            satisfiedMarkers = listOf("ref.referenceKind === 'decorates') {\n        if (!ref.language) return candidates;"),
        ),
        Patch(
            "resolution/name-matcher.js", exactSingleAnchor, exactSingleReplacement,
            fallbackAnchors = listOf(exactSingleFallback1),
        ),
        Patch(
            "resolution/name-matcher.js", fuzzyAnchor, fuzzyReplacement,
            fallbackAnchors = listOf(fuzzyFallback1),
        ),
        Patch(
            "resolution/index.js", resolverAnchor, resolverReplacement,
            fallbackAnchors = gateFallbackBodies.map { body -> gateCommonHead + "\n" + body + "\n" + gateCommonTail },
            satisfiedMarkers = listOf("const refLang = ref.language || this.getLanguageFromNodeId(ref.fromNodeId);"),
        ),
        Patch(
            "resolution/index.js", frameworkAnchor, frameworkReplacement,
            fallbackAnchors = listOf(frameworkFallback1),
            satisfiedMarkers = listOf("ref.referenceKind === 'decorates') {"),
        ),
        createEdgesRepair,
        createEdgesWrapper,
        Patch("resolution/import-resolver.js", resolveModuleImportAnchor, resolveModuleImportReplacement),
        Patch("resolution/import-resolver.js", pythonModuleImportAnchor, pythonModuleImportReplacement),
        Patch(
            "extraction/tree-sitter.js", importHookNodeAnchor, importHookNodeReplacement,
            fallbackAnchors = listOf(importHookNodeFallback1),
            fallbackReplacements = listOf(importHookNodeFallback1Replacement),
        ),
        Patch("extraction/tree-sitter.js", pythonImportStmtAnchor, pythonImportStmtReplacement),
        Patch("extraction/tree-sitter.js", treeSitterAnchor, treeSitterReplacement),
        Patch("extraction/extraction-version.js", extractionVersionAnchor, extractionVersionReplacement),
        Patch("mcp/tools.js", normalizeQueryAnchor, normalizeQueryReplacement),
        Patch("mcp/tools.js", exploreAnchor, exploreReplacement),
        Patch("mcp/tools.js", exploreCliffAnchor, exploreCliffReplacement),
        Patch(
            "mcp/tools.js", exploreBudgetAnchor, exploreBudgetReplacement,
            fallbackAnchors = listOf(exploreBudgetFallback1, exploreBudgetFallback2),
            satisfiedMarkers = listOf("maxOutputChars: 40000,"),
        ),
        Patch("mcp/tools.js", exploreHardCeilingAnchor, exploreHardCeilingReplacement),
        Patch(
            "mcp/tools.js", exploreSearchLimitAnchor, exploreSearchLimitReplacement,
            fallbackAnchors = listOf(exploreSearchLimitFallback1),
            satisfiedMarkers = listOf("searchLimit: Math.max(30, maxFiles * 3),"),
        ),
        Patch("bin/codegraph.js", impactAnchor, impactReplacement),
        Patch(
            "db/index.js", maintenanceAnchor, maintenanceReplacement,
            fallbackAnchors = listOf(maintenanceFallback1),
            satisfiedMarkers = listOf("PRAGMA incremental_vacuum"),
        ),
        Patch(
            "db/migrations.js", migrationVersionAnchor, migrationVersionReplacement,
            satisfiedMarkers = listOf("CURRENT_SCHEMA_VERSION = 11;"),
        ),
        Patch(
            "db/migrations.js", migrationListAnchor, migrationListReplacement,
            fallbackAnchors = listOf(migrationListFallback1),
            fallbackReplacements = listOf(migrationListFallback1Replacement),
            satisfiedMarkers = listOf("version: 11,"),
        ),
        Patch("db/queries.js", insertUnresolvedAnchor, insertUnresolvedReplacement),
        Patch(
            "db/queries.js", deleteNodesAnchor, deleteNodesReplacement,
            fallbackAnchors = listOf(deleteNodesFallback1),
            fallbackReplacements = listOf(deleteNodesFallback1Replacement),
            satisfiedMarkers = listOf("pruneCrossLanguageEdges() {"),
        ),
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
            if (patch.satisfiedMarkers.any { text.contains(it) }) continue
            if (patch.requiresMarker != null && !text.contains(patch.requiresMarker)) continue
            var replaced = false
            if (text.contains(patch.anchor)) {
                runCatching { file.writeText(text.replace(patch.anchor, patch.replacement)) }
                    .onSuccess { replaced = true }
            }
            if (!replaced) {
                val fallbacks = patch.fallbackAnchors.withIndex()
                for ((i, fallback) in fallbacks) {
                    if (!text.contains(fallback)) continue
                    val replacement = patch.fallbackReplacements.getOrNull(i) ?: patch.replacement
                    runCatching { file.writeText(text.replace(fallback, replacement)) }
                        .onSuccess { replaced = true }
                    break
                }
            }
            if (replaced) {
                applied += patch.relativePath
            } else {
                unresolved += patch.relativePath
            }
        }
        return Result(applied, unresolved)
    }
}
