package streaming.compiler;

import deal.compiler.CompilerProtocol.ChangeSetPrecondition;
import deal.compiler.CompilerProtocol;
import deal.compiler.CompilerProtocol.OperationDescriptor;
import deal.compiler.CompilerProtocol.RepairScope;
import deal.compiler.CompilerProtocol.SemanticId;
import deal.compiler.CompilerProtocol.SemanticSlice;
import deal.compiler.CompilerProtocol.StructuredDiagnostic;
import deal.compiler.CompilerProtocol.SymbolSnapshot;
import deal.compiler.CompilerProtocolJson;
import deal.compiler.DealCompilerWorkspace;
import deal.semantic.ir.CanonicalJson;
import deal.ui.CanonicalCompiler;
import deal.ui.UiCompilerWorkspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;

/** Provider-neutral LLM-facing refinement session owned by streaming-compiler. */
public final class CanonicalRefinementSession {
    private static final String REFINEMENT_SYSTEM_PROMPT = """
            You modernize one canonical DEAL application through a compact compiler agent surface.
            DEAL owns state and behavior. Deal UI owns declarative presentation. Inspect a short
            target alias, then submit one small atomic transaction using only operations unlocked by
            that query. Never regenerate an unrelated unit. Compiler diagnostics and writable repair
            scopes are authoritative. Use no scenario templates. Set final to false only when another
            behavior or visual transaction is required. Emit exactly one tool call.
            replaceFunctionBody and replaceBlockBody accept only statements inside the existing
            braces. Never include a function signature, declaration, or the outer braces in body.
            """.strip();
    private static final String DEAL_GENERATION_SYSTEM_PROMPT = """
            Create the behavior of one complete canonical DEAL application through the compact
            compiler surface. Query the DEAL module and bootstrap declarations, then submit one
            cohesive atomic transaction. Emit exactly one write tool call; read-only queries may
            be batched. Set final=true only when behavior is complete. For a complex application,
            set final=false, inspect the new revision, and continue with another small transaction.
            Never return prose.
            Obey generationStage. In bootstrap, replace AppState and initialState and add any
            field-only nominal record types referenced by AppState in the same atomic ChangeSet.
            Query the module together with both bootstrap units before that write. Every collection
            rendered as repeated UI must contain nominal items with a stable int or string id/key;
            shape nested visual data as keyed record collections, not primitive nested arrays. In
            app-state or initial-state, complete only the named missing bootstrap unit. In declarations, both
            bootstrap units are committed and immutable: use only one addDeclaration operation per
            new action, helper or handler, and never emit replaceDeclaration or replaceFunctionBody.
            Each declaration operation contains exactly one top-level class or function. Replace
            bootstrap AppState once, replace only the statements inside initialState, and add every
            other class or function with a separate addDeclaration operation in the same ChangeSet.
            Never put several declarations in one string and never replace a function declaration
            and its body together. Example shape: replaceDeclaration(AppState),
            replaceFunctionBody(initialState body), addDeclaration(Action), addDeclaration(handler).
            In declarations, call finish_deal as soon as the existing accepted declarations satisfy
            the request. Never resubmit an existing declaration merely to transition to Deal UI.

            DEAL is a mutable TypeScript-shaped subset. Use exported nominal classes, initialState,
            nominal actions ending in Action, and @ui-update handlers that return a complete new
            AppState. Classes are field-only nominal records: use fields with defaults, never
            constructors or methods. Use typed let locals, semicolons, ===, !==, ordinary loops and
            array indexing. Only the empty array literal [] is supported. Do not use non-empty array
            literals, const, var, interfaces, arrow functions, ternaries, switch, postfix !, ++,
            compound assignment, JavaScript methods, map/filter/reduce or implicit number/string
            conversion. Arrays have no push or concat methods. Append to a fresh local array only
            with items[items.length] = value; build nested arrays by appending each completed row the
            same way. State and action parameters are borrowed: construct a new
            state and mutate only fresh local arrays or records. Keep visible strings English.
            Use int for integral values and defaults; a number default requires 0.0. Construct
            records with context-typed object literals such as {count: 0}; DEAL has no new operator.
            Presentation-ready labels, glyphs, tones, counters and chart arrays belong in AppState.
            Use integer platform helpers only when listed by the host contract.
            Every state/action-to-state handler must include // @ui-update immediately before its
            export function declaration inside the same declaration string. Such a handler has
            exactly two parameters, (state: AppState, action: SomeAction). Never annotate a
            one-parameter helper; helpers are ordinary unannotated functions.
            """.strip();

    private static final String DEAL_UI_GENERATION_SYSTEM_PROMPT = """
            Create the complete Deal UI presentation for the supplied compiler-extracted
            AppInterface. Query the bootstrap root view, then replace its body in one atomic UI
            transaction and mark it final. Emit exactly one write tool call; read-only queries may
            be batched. The replacement body contains only statements inside the existing view:
            omit the view signature and outer braces. Use only operations in the current tool
            schema. Never return prose.
            Deal UI is declarative and read-only. Use only components and tokens in componentPack,
            field paths from interface, action constructors, literals, When and ForEach. It has no
            indexing, array/object literals, assignments, arbitrary calls, length, methods or
            string-number coercion or ternary expressions. Dynamic collections use exactly
            ForEach(state.items, item: app.Item, key: item.id) { ui.Text(value: item.label) }.
            Do not use ui.ForEach, item in, lambdas or a body parameter line. Use === and !== for
            equality and When(condition) { ... } Else { ... } for visual branches. Bind all
            reachable input actions. Use semantic native components, one app-owned AppTheme, an
            adaptive Root, accessible labels, and Canvas/PointerSurface only for spatial content.
            Make the result polished and responsive without scenario-specific native components.
            The exact syntax is ui.Component(property: expression, spacing: ui.spaceMd) { ... }.
            Properties use colon, never equals. Qualify every component and token with ui. Bind an
            action as onClick: action app.SomeAction { field: expression }, never SomeAction().
            Runtime state values always start with the root parameter state, for example
            state.score. The app alias qualifies action types only; never read app.someField.
            """.strip();

    private final String previousDeal;
    private final String previousDealUi;
    private final String pack;
    private final String packSpecifier;
    private final String instruction;
    private final boolean generation;
    private final List<Map<String, Object>> transcript = new ArrayList<>();
    private String deal;
    private String dealUi;
    private CanonicalCompiler.Inspection inspection;
    private List<RepairScope> repairScopes = List.of();
    private List<StructuredDiagnostic> repairDiagnostics = List.of();
    private String forcedArtifact = "";
    private final Map<String, SemanticId> aliases = new LinkedHashMap<>();
    private final Map<String, String> aliasesById = new LinkedHashMap<>();
    private final Map<String, OperationDescriptor> dealGrants = new LinkedHashMap<>();
    private final Map<String, OperationDescriptor> dealUiGrants = new LinkedHashMap<>();
    private final Set<String> queriedAliases = new LinkedHashSet<>();
    private Status status = Status.REQUEST;
    private int rounds;
    private int writeRounds;
    private int semanticRepairs;
    private int dealSemanticRepairs;
    private int dealUiSemanticRepairs;
    private boolean appStateBootstrapReplaced;
    private boolean initialStateBootstrapReplaced;
    private boolean repairMustFinishDeal;
    private final int maxRounds;
    private final int maxSemanticRepairs;

    public CanonicalRefinementSession(
            String deal,
            String dealUi,
            String pack,
            String packSpecifier,
            String instruction,
            int maxRounds,
            int maxSemanticRepairs) {
        this(deal, dealUi, pack, packSpecifier, instruction, maxRounds, maxSemanticRepairs, false);
    }

    public static CanonicalRefinementSession greenfield(
            String pack,
            String packSpecifier,
            String instruction,
            int maxRounds,
            int maxSemanticRepairs) {
        var bootstrap = CanonicalCompiler.bootstrapCanonicalApp(pack, packSpecifier);
        return new CanonicalRefinementSession(
                bootstrap.deal(), bootstrap.dealUi(), pack, packSpecifier, instruction,
                maxRounds, maxSemanticRepairs, true);
    }

    private CanonicalRefinementSession(
            String deal,
            String dealUi,
            String pack,
            String packSpecifier,
            String instruction,
            int maxRounds,
            int maxSemanticRepairs,
            boolean generation) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("Refinement instruction is empty");
        }
        this.previousDeal = deal;
        this.previousDealUi = dealUi;
        this.deal = deal;
        this.dealUi = dealUi;
        this.pack = pack;
        this.packSpecifier = packSpecifier;
        this.instruction = instruction;
        this.generation = generation;
        this.maxRounds = maxRounds;
        this.maxSemanticRepairs = maxSemanticRepairs;
        this.inspection = CanonicalCompiler.inspectCanonicalApp(deal, dealUi, pack, packSpecifier);
        if (!inspection.valid()) {
            throw new IllegalArgumentException(
                    "Cannot refine an invalid canonical application: " + inspection.diagnostics());
        }
        refreshAliases();
        if (generation) forcedArtifact = "deal";
    }

    public String nextRequestJson() {
        if (status != Status.REQUEST) return resultJson();
        if (writeRounds >= maxRounds) {
            fail("SC1001", "Refinement round budget exhausted");
            return resultJson();
        }
        String input = input();
        List<Map<String, Object>> tools = tools();
        String encodedTools = CompilerProtocolJson.encode(tools);
        String surfaceDigest = DealCompilerWorkspace.digest(input + "\u0000" + encodedTools);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("status", "request");
        request.put("protocolVersion", CompilerProtocol.VERSION);
        request.put("surfaceVersion", CompilerProtocol.AGENT_SURFACE_VERSION);
        request.put("protocolMode", "v2-with-v1-shadow");
        request.put("surfaceDigest", surfaceDigest);
        int inputBytes = input.getBytes(StandardCharsets.UTF_8).length;
        int toolBytes = encodedTools.getBytes(StandardCharsets.UTF_8).length;
        request.put("surfaceMetrics", Map.of(
                "inputBytes", inputBytes,
                "toolSchemaBytes", toolBytes,
                "approxInputTokens", (inputBytes + toolBytes + 3) / 4));
        request.put("revision", Map.of(
                "deal", inspection.deal().sourceDigest(),
                "dealUi", inspection.dealUi() == null ? "" : inspection.dealUi().sourceDigest()));
        request.put("instructions", generation
                ? forcedArtifact.equals("dealui")
                        ? DEAL_UI_GENERATION_SYSTEM_PROMPT
                        : DEAL_GENERATION_SYSTEM_PROMPT
                : REFINEMENT_SYSTEM_PROMPT);
        request.put("input", input);
        request.put("tools", tools);
        request.put("round", rounds + 1);
        request.put("semanticRepairs", semanticRepairs);
        return CompilerProtocolJson.encode(request);
    }

    public String acceptToolCallJson(String name, String argumentsJson) {
        if (status != Status.REQUEST) throw new IllegalStateException("Refinement session is not requesting a tool");
        rounds++;
        if (!isReadOnlyQuery(name)) writeRounds++;
        CanonicalJson.Obj arguments = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.decode(argumentsJson), "tool arguments");
        acceptToolCall(name, arguments);
        return status == Status.REQUEST ? nextRequestJson() : resultJson();
    }

    /** Accepts one provider turn; multiple calls are permitted only for read-only context queries. */
    public String acceptToolCallsJson(String callsJson) {
        if (status != Status.REQUEST) throw new IllegalStateException("Refinement session is not requesting a tool");
        CanonicalJson.Arr calls = CompilerProtocolJson.requireArray(
                CompilerProtocolJson.decode(callsJson), "tool calls");
        if (calls.items().isEmpty()) throw new IllegalArgumentException("A provider turn requires at least one tool call");
        List<CanonicalJson.Obj> values = calls.items().stream()
                .map(value -> CompilerProtocolJson.requireObject(value, "tool call"))
                .toList();
        if (values.size() > 1 && values.stream().anyMatch(value -> !isReadOnlyQuery(string(value, "name")))) {
            throw new IllegalArgumentException("A provider turn may batch only read-only compiler queries");
        }
        rounds++;
        if (values.stream().anyMatch(value -> !isReadOnlyQuery(string(value, "name")))) writeRounds++;
        for (CanonicalJson.Obj value : values) {
            acceptToolCall(
                    string(value, "name"),
                    CompilerProtocolJson.requireObject(field(value, "arguments"), "tool arguments"));
        }
        return status == Status.REQUEST ? nextRequestJson() : resultJson();
    }

    private void acceptToolCall(String name, CanonicalJson.Obj arguments) {
        switch (name) {
            case "query_deal_module" -> queryDealModule(string(arguments, "target"));
            case "query_deal_symbol" -> queryDealSymbol(string(arguments, "target"));
            case "query_deal_node" -> queryDealNode(string(arguments, "target"));
            case "query_deal_ui_view" -> queryDealUiView(string(arguments, "target"));
            case "query_deal_ui_document" -> queryDealUiDocument(string(arguments, "target"));
            case "query_deal_ui_node" -> queryDealUiNode(string(arguments, "target"));
            case "apply_deal_foundation" -> applyDealFoundation(arguments);
            case "apply_deal_changes" -> applyDeal(arguments);
            case "finish_deal" -> finishDeal();
            case "apply_deal_ui_changes" -> applyDealUi(arguments);
            case "unchanged" -> unchanged();
            default -> throw new IllegalArgumentException("Unsupported streaming-compiler tool: " + name);
        }
    }

    private static boolean isReadOnlyQuery(String name) {
        return name.equals("query_deal_module")
                || name.equals("query_deal_symbol")
                || name.equals("query_deal_node")
                || name.equals("query_deal_ui_document")
                || name.equals("query_deal_ui_view")
                || name.equals("query_deal_ui_node");
    }

    public String resultJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status.name().toLowerCase());
        result.put("accepted", status == Status.COMPLETE);
        result.put("changed", !deal.equals(previousDeal) || !dealUi.equals(previousDealUi));
        result.put("deal", status == Status.COMPLETE ? deal : previousDeal);
        result.put("dealUi", status == Status.COMPLETE ? dealUi : previousDealUi);
        result.put("rounds", rounds);
        result.put("semanticRepairs", semanticRepairs);
        result.put("protocolVersion", CompilerProtocol.VERSION);
        result.put("surfaceVersion", CompilerProtocol.AGENT_SURFACE_VERSION);
        result.put("protocolMode", "v2-with-v1-shadow");
        result.put("inspection", inspection);
        result.put("transcript", transcript);
        return CompilerProtocolJson.encode(result);
    }

    private String input() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("request", instruction);
        context.put("deal", compactDealIndex());
        if (inspection.dealUi() != null) {
            context.put("dealUi", compactDealUiIndex());
        }
        context.put("packVersion", inspection.packVersion());
        context.put("packDigest", inspection.packDigest());
        if (generation && forcedArtifact.equals("dealui")) {
            context.put("componentPack", compactComponentPack());
        }
        context.put("previousToolResults", agentTranscript());
        if (!repairScopes.isEmpty()) context.put("repairScopes", compactRepairScopes());
        if (!repairDiagnostics.isEmpty()) {
            context.put("repairDirective", Map.of(
                    "instruction", "Change the rejected operation. Never resubmit an identical operation.",
                    "diagnostics", compactDiagnostics(repairDiagnostics)));
        }
        if (!forcedArtifact.isEmpty()) context.put("requiredArtifact", forcedArtifact);
        if (generation) {
            context.put("generationStage", generationStage());
            context.put("stageObjective", generationStageObjective());
        }
        return CompilerProtocolJson.encode(context);
    }

    private List<Map<String, Object>> tools() {
        List<Map<String, Object>> result = new ArrayList<>();
        boolean repairing = !repairScopes.isEmpty();
        if (!repairing && !forcedArtifact.equals("dealui")) {
            boolean bootstrapComplete = appStateBootstrapReplaced && initialStateBootstrapReplaced;
            if (!generation || bootstrapComplete || generationStage().equals("bootstrap")) {
                addQueryTool(result, "query_deal_module", "Unlock adding a new top-level DEAL declaration.", "M");
            }
            if (!generation) {
                addQueryTool(result, "query_deal_symbol", "Read one DEAL declaration and dependency summary.", "S");
                addQueryTool(result, "query_deal_node", "Read one DEAL function or block body.", "B");
            } else if (!bootstrapComplete) {
                List<String> symbolTargets = new ArrayList<>();
                if (!appStateBootstrapReplaced) symbolTargets.add(symbolAlias("AppState"));
                addQueryTool(result, "query_deal_symbol", "Read the missing bootstrap declaration.", symbolTargets);
                if (!initialStateBootstrapReplaced) {
                    addQueryTool(result, "query_deal_node", "Read the missing initialState body.",
                            nodeAliases("initialState"));
                }
            }
        }
        if (!repairing && !forcedArtifact.equals("deal") && inspection.dealUi() != null) {
            addQueryTool(result, "query_deal_ui_document", "Unlock adding a new Deal UI view.", "D");
            addQueryTool(result, "query_deal_ui_view", "Read one complete Deal UI view.", "V");
            addQueryTool(result, "query_deal_ui_node", "Read one Deal UI subtree and its bindings.", "U");
        }
        List<Map<String, Object>> dealOperations = repairMustFinishDeal ? List.of() : dealOperationSchemas();
        if (generation && generationStage().equals("bootstrap") && foundationReady()) {
            result.add(tool("apply_deal_foundation",
                    "Atomically declare supporting record types and replace the two bootstrap units.",
                    objectSchema(Map.of(
                            "supportingDeclarations", Map.of(
                                    "type", "array",
                                    "items", Map.of("type", "string", "description",
                                            "One complete unique field-only class declaration. Use int, not number, for integral fields and defaults")),
                            "appStateDeclaration", Map.of("type", "string", "description",
                                    "Complete export class AppState declaration. Use int, not number, for integral fields and defaults"),
                            "initialStateBody", Map.of("type", "string", "description",
                                    "Statements only; omit signature and outer braces. Use int locals for integer literals and loops; only [] array literals")))));
        } else if (!dealOperations.isEmpty()) {
            String description = generation && generationStage().equals("declarations")
                    ? "Bootstrap is committed. Add only new top-level action, helper or handler declarations."
                    : "Apply one atomic DEAL ChangeSet.";
            result.add(transactionTool("apply_deal_changes", description, dealOperations));
        }
        if (generation && generationStage().equals("declarations")) {
            result.add(tool("finish_deal",
                    repairMustFinishDeal
                            ? "All rejected declarations already exist. Drop the duplicate ChangeSet and transition to Deal UI."
                            : "Current checked DEAL behavior is complete. Transition to Deal UI without changing source.",
                    objectSchema(Map.of("reason", Map.of("type", "string")))));
        }
        List<Map<String, Object>> uiOperations = dealUiOperationSchemas();
        if (!uiOperations.isEmpty()) {
            result.add(transactionTool("apply_deal_ui_changes", "Apply one atomic Deal UI ChangeSet.", uiOperations));
        }
        if (!repairing && forcedArtifact.isEmpty()) {
            result.add(tool("unchanged", "The requested change is already present or needs no source edit.",
                    objectSchema(Map.of("reason", Map.of("type", "string")))));
        }
        return List.copyOf(result);
    }

    private boolean foundationReady() {
        SemanticId module = inspection.deal().moduleId();
        SemanticId appState = inspection.deal().symbols().stream()
                .filter(symbol -> symbol.name().equals("AppState"))
                .map(SymbolSnapshot::id).findFirst().orElse(null);
        SemanticId initialBody = inspection.deal().nodes().stream()
                .filter(node -> inspection.deal().symbols().stream().anyMatch(symbol ->
                        symbol.id().equals(node.ownerId()) && symbol.name().equals("initialState")))
                .map(node -> node.id()).findFirst().orElse(null);
        return appState != null && initialBody != null
                && hasGrant(DealCompilerWorkspace.ADD_DECLARATION, module)
                && hasGrant(DealCompilerWorkspace.REPLACE_DECLARATION, appState)
                && hasGrant(DealCompilerWorkspace.REPLACE_FUNCTION_BODY, initialBody);
    }

    private boolean hasGrant(String operation, SemanticId target) {
        return dealGrants.values().stream().anyMatch(grant ->
                grant.operation().equals(operation) && grant.targetId().equals(target));
    }

    private String generationStage() {
        if (forcedArtifact.equals("dealui")) return "ui";
        if (appStateBootstrapReplaced && initialStateBootstrapReplaced) return "declarations";
        if (appStateBootstrapReplaced) return "initial-state";
        if (initialStateBootstrapReplaced) return "app-state";
        return "bootstrap";
    }

    private String generationStageObjective() {
        return switch (generationStage()) {
            case "bootstrap" -> "Query the module and both bootstrap units. In one transaction add supporting field-only record types, replace AppState, and replace initialState; continue with final=false.";
            case "app-state" -> "Replace only the missing AppState declaration; do not add or redeclare it.";
            case "initial-state" -> "Replace only the missing initialState body; do not redeclare AppState.";
            case "declarations" -> "AppState and initialState are committed. Add only missing unique action, helper, and handler declarations. If existing behavior is complete, call finish_deal immediately.";
            case "ui" -> "Build Deal UI only against the checked AppInterface and component pack.";
            default -> throw new IllegalStateException("Unknown generation stage " + generationStage());
        };
    }

    private List<Map<String, Object>> dealOperationSchemas() {
        if (forcedArtifact.equals("dealui")) return List.of();
        List<Map<String, Object>> operations = new ArrayList<>();
        dealGrants.values().forEach(grant -> {
            if (generation && !allowedGreenfieldDealOperation(grant)) return;
            Map<String, Object> extra = switch (grant.operation()) {
                case DealCompilerWorkspace.ADD_DECLARATION ->
                        Map.of("declaration", Map.of(
                                "type", "string",
                                "description", "Exactly one complete top-level class or function declaration with a unique name. Existing names: "
                                        + existingDealSymbolNames()));
                case DealCompilerWorkspace.REPLACE_DECLARATION ->
                        Map.of("declaration", Map.of(
                                "type", "string",
                                "description", "Exactly one declaration with the same name and kind as the target"));
                case DealCompilerWorkspace.REPLACE_FUNCTION_BODY, DealCompilerWorkspace.REPLACE_BLOCK_BODY ->
                        Map.of("body", Map.of(
                                "type", "string",
                                "description", "Statements only; omit declaration signature and outer braces"));
                default -> Map.of();
            };
            addIfAllowed(operations, grant.operation(), grant.targetId(), extra);
        });
        return operations;
    }

    private boolean allowedGreenfieldDealOperation(OperationDescriptor grant) {
        if (grant.operation().equals(DealCompilerWorkspace.ADD_DECLARATION)) return true;
        if (grant.operation().equals(DealCompilerWorkspace.REPLACE_DECLARATION)) {
            return !appStateBootstrapReplaced && inspection.deal().symbols().stream().anyMatch(symbol ->
                    symbol.id().equals(grant.targetId())
                            && symbol.kind().equals("class")
                            && symbol.name().equals("AppState"));
        }
        if (grant.operation().equals(DealCompilerWorkspace.REPLACE_FUNCTION_BODY)) {
            return !initialStateBootstrapReplaced && inspection.deal().nodes().stream().anyMatch(node ->
                    node.id().equals(grant.targetId())
                            && inspection.deal().symbols().stream().anyMatch(symbol ->
                                    symbol.id().equals(node.ownerId())
                                            && symbol.name().equals("initialState")));
        }
        return false;
    }

    private List<Map<String, Object>> dealUiOperationSchemas() {
        if (forcedArtifact.equals("deal") || inspection.dealUi() == null) return List.of();
        List<Map<String, Object>> operations = new ArrayList<>();
        dealUiGrants.values().forEach(grant -> {
            UiCompilerWorkspace.UiNodeSnapshot node = inspection.dealUi().nodes().stream()
                    .filter(value -> value.id().equals(grant.targetId())).findFirst().orElse(null);
            Map<String, Object> extra = switch (grant.operation()) {
                case UiCompilerWorkspace.ADD_VIEW -> Map.of("source", Map.of("type", "string"));
                case UiCompilerWorkspace.REPLACE_VIEW_BODY -> Map.of("body", Map.of("type", "string"));
                case UiCompilerWorkspace.REPLACE_SUBTREE -> Map.of("source", Map.of("type", "string"));
                case UiCompilerWorkspace.INSERT_CHILD -> Map.of(
                        "index", Map.of("type", "integer", "minimum", 0,
                                "maximum", node == null ? 0 : node.children().size()),
                        "source", Map.of("type", "string"));
                case UiCompilerWorkspace.MOVE_NODE -> Map.of(
                        "newParent", enumSchema(queriedUiContainerAliases()),
                        "index", Map.of("type", "integer", "minimum", 0));
                case UiCompilerWorkspace.SET_PROPERTY -> Map.of(
                        "property", enumSchema(node == null ? List.of() : node.writableProperties()),
                        "expression", Map.of("type", "string"));
                default -> Map.of();
            };
            if (!grant.operation().equals(UiCompilerWorkspace.MOVE_NODE)
                    || !queriedUiContainerAliases().isEmpty()) {
                addIfAllowed(operations, grant.operation(), grant.targetId(), extra);
            }
        });
        return operations;
    }

    private void addIfAllowed(
            List<Map<String, Object>> target,
            String operation,
            SemanticId id,
            Map<String, Object> extra) {
        if (!repairScopes.isEmpty() && repairScopes.stream().noneMatch(scope ->
                scope.operation().equals(operation) && scope.ownerId().equals(id))) return;
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("operation", constantString(operation));
        properties.put("target", constantString(alias(id)));
        properties.putAll(extra);
        target.add(objectSchema(properties));
    }

    private void queryDealModule(String target) {
        SemanticId id = resolveAlias(target, "M");
        SemanticSlice slice = CanonicalCompiler.queryDealModule(deal);
        requireSameTarget(id, slice.ownerId());
        grant(dealGrants, slice.allowedOperations());
        queriedAliases.add(target);
        addTranscript("query_deal_module", compactDealSlice(slice));
        forcedArtifact = "deal";
    }

    private void queryDealSymbol(String target) {
        SemanticId id = resolveAlias(target, "S");
        SemanticSlice slice = CanonicalCompiler.queryDealSymbol(deal, id);
        grant(dealGrants, slice.allowedOperations());
        queriedAliases.add(target);
        addTranscript("query_deal_symbol", compactDealSlice(slice));
        forcedArtifact = "deal";
    }

    private void queryDealNode(String target) {
        SemanticId id = resolveAlias(target, "B");
        SemanticSlice slice = CanonicalCompiler.queryDealNode(deal, id);
        grant(dealGrants, slice.allowedOperations());
        queriedAliases.add(target);
        addTranscript("query_deal_node", compactDealSlice(slice));
        forcedArtifact = "deal";
    }

    private void queryDealUiView(String target) {
        if (inspection.dealUi() == null) throw new IllegalStateException("Deal UI inspection is unavailable");
        SemanticId id = resolveAlias(target, "V");
        var slice = CanonicalCompiler.queryDealUiView(deal, dealUi, pack, packSpecifier, id);
        grant(dealUiGrants, slice.allowedOperations());
        queriedAliases.add(target);
        addTranscript("query_deal_ui_view", compactUiSlice(slice));
        forcedArtifact = "dealui";
    }

    private void queryDealUiDocument(String target) {
        if (inspection.dealUi() == null) throw new IllegalStateException("Deal UI inspection is unavailable");
        SemanticId id = resolveAlias(target, "D");
        var slice = CanonicalCompiler.queryDealUiDocument(deal, dealUi, pack, packSpecifier);
        requireSameTarget(id, slice.ownerId());
        grant(dealUiGrants, slice.allowedOperations());
        queriedAliases.add(target);
        addTranscript("query_deal_ui_document", compactUiSlice(slice));
        forcedArtifact = "dealui";
    }

    private void queryDealUiNode(String target) {
        if (inspection.dealUi() == null) throw new IllegalStateException("Deal UI inspection is unavailable");
        SemanticId id = resolveAlias(target, "U");
        var slice = CanonicalCompiler.queryDealUiNode(deal, dealUi, pack, packSpecifier, id);
        grant(dealUiGrants, slice.allowedOperations());
        queriedAliases.add(target);
        addTranscript("query_deal_ui_node", compactUiSlice(slice));
        forcedArtifact = "dealui";
    }

    private void applyDeal(CanonicalJson.Obj arguments) {
        List<DealCompilerWorkspace.Operation> operations = dealOperations(field(arguments, "operations"));
        boolean finalChange = booleanField(arguments, "final");
        String beforeDigest = inspection.deal().sourceDigest();
        boolean replacesAppState = generation && operations.stream().anyMatch(this::replacesAppStateBootstrap);
        boolean replacesInitialState = generation && operations.stream().anyMatch(this::replacesInitialStateBootstrap);
        var result = CanonicalCompiler.applyDealChangeChecked(
                deal,
                new ChangeSetPrecondition(inspection.deal().sourceDigest(), fingerprints(dealGrants)),
                operations);
        if (!isPreconditionRejection(result.diagnostics())) {
            var shadow = CanonicalCompiler.applyDealChange(
                    deal, inspection.deal().sourceDigest(), operations);
            recordShadowParity("deal", shadow.accepted(), shadow.sourceDigest(), result.accepted(), result.sourceDigest());
        }
        if (!result.accepted()) {
            reject("apply_deal_changes", result.diagnostics(), "deal", operations);
            return;
        }
        boolean sourceChanged = !result.sourceDigest().equals(beforeDigest);
        if (generation && !sourceChanged && !finalChange) {
            SemanticId owner = operations.get(0).targetId();
            List<RepairScope> scopes = operations.stream()
                    .map(operation -> new RepairScope(operationName(operation), operation.targetId()))
                    .distinct()
                    .toList();
            reject("apply_deal_changes", List.of(new StructuredDiagnostic(
                    "SC1002",
                    "error",
                    "The accepted transaction made no source progress; change the operation or finish DEAL",
                    null,
                    owner,
                    "a source-changing operation or final=true",
                    "unchanged source",
                    operations.stream().map(DealCompilerWorkspace.Operation::targetId).distinct().toList(),
                    scopes,
                    "queryDealNode(" + owner.value() + ")")), "deal", operations);
            return;
        }
        deal = result.source();
        if (sourceChanged) {
            appStateBootstrapReplaced |= replacesAppState;
            initialStateBootstrapReplaced |= replacesInitialState;
        }
        dealSemanticRepairs = 0;
        repairMustFinishDeal = false;
        repairScopes = List.of();
        repairDiagnostics = List.of();
        forcedArtifact = generation
                ? finalChange ? "dealui" : "deal"
                : result.impact().interfaceChanged() ? "dealui" : "";
        inspection = CanonicalCompiler.inspectCanonicalApp(deal, dealUi, pack, packSpecifier);
        resetSurface();
        if (generation && finalChange) {
            // UI generation is a separate provider transaction. Its complete contract is the
            // freshly extracted AppInterface plus the component pack. Retaining DEAL tool calls
            // biases providers toward operation names that are no longer in the active surface.
            transcript.clear();
        } else {
            addTranscript("apply_deal_changes", Map.of("accepted", true, "impact", result.impact()));
        }
        if (inspection.valid() && finalChange && forcedArtifact.isEmpty()) status = Status.COMPLETE;
    }

    private void applyDealFoundation(CanonicalJson.Obj arguments) {
        List<Map<String, Object>> operations = new ArrayList<>();
        CanonicalJson.Arr declarations = CompilerProtocolJson.requireArray(
                field(arguments, "supportingDeclarations"), "supportingDeclarations");
        String module = alias(inspection.deal().moduleId());
        for (CanonicalJson.Value value : declarations.items()) {
            if (!(value instanceof CanonicalJson.Str declaration)) {
                throw new IllegalArgumentException("supportingDeclarations must contain strings");
            }
            operations.add(Map.of(
                    "operation", DealCompilerWorkspace.ADD_DECLARATION,
                    "target", module,
                    "declaration", declaration.value()));
        }
        operations.add(Map.of(
                "operation", DealCompilerWorkspace.REPLACE_DECLARATION,
                "target", symbolAlias("AppState"),
                "declaration", string(arguments, "appStateDeclaration")));
        operations.add(Map.of(
                "operation", DealCompilerWorkspace.REPLACE_FUNCTION_BODY,
                "target", nodeAliases("initialState").get(0),
                "body", string(arguments, "initialStateBody")));
        CanonicalJson.Obj transaction = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.decode(CompilerProtocolJson.encode(Map.of(
                        "operations", operations,
                        "final", false))),
                "foundation transaction");
        applyDeal(transaction);
    }

    private boolean replacesAppStateBootstrap(DealCompilerWorkspace.Operation operation) {
        if (!(operation instanceof DealCompilerWorkspace.ReplaceDeclaration replace)) return false;
        return inspection.deal().symbols().stream().anyMatch(symbol ->
                symbol.id().equals(replace.targetId()) && symbol.name().equals("AppState"));
    }

    private boolean replacesInitialStateBootstrap(DealCompilerWorkspace.Operation operation) {
        if (!(operation instanceof DealCompilerWorkspace.ReplaceFunctionBody replace)) return false;
        return inspection.deal().nodes().stream().anyMatch(node ->
                node.id().equals(replace.targetId())
                        && inspection.deal().symbols().stream().anyMatch(symbol ->
                                symbol.id().equals(node.ownerId()) && symbol.name().equals("initialState")));
    }

    private void finishDeal() {
        if (!generation || !generationStage().equals("declarations") || !dealIsValid()) {
            throw new IllegalArgumentException("finish_deal requires valid generated DEAL after bootstrap");
        }
        forcedArtifact = "dealui";
        repairScopes = List.of();
        repairDiagnostics = List.of();
        repairMustFinishDeal = false;
        transcript.clear();
        resetSurface();
    }

    private boolean dealIsValid() {
        return inspection.deal().diagnostics().stream().noneMatch(diagnostic ->
                diagnostic.severity().equalsIgnoreCase("error"));
    }

    private void applyDealUi(CanonicalJson.Obj arguments) {
        if (inspection.dealUi() == null) throw new IllegalStateException("Deal UI inspection is unavailable");
        List<UiCompilerWorkspace.Operation> operations = dealUiOperations(field(arguments, "operations"));
        boolean finalChange = booleanField(arguments, "final");
        var result = CanonicalCompiler.applyDealUiChangeChecked(
                deal, dealUi, pack, packSpecifier,
                new ChangeSetPrecondition(inspection.dealUi().sourceDigest(), fingerprints(dealUiGrants)),
                operations);
        if (!isPreconditionRejection(result.diagnostics())) {
            var shadow = CanonicalCompiler.applyDealUiChange(
                    deal, dealUi, pack, packSpecifier, inspection.dealUi().sourceDigest(), operations);
            recordShadowParity("dealui", shadow.accepted(), shadow.sourceDigest(), result.accepted(), result.sourceDigest());
        }
        if (!result.accepted()) {
            reject("apply_deal_ui_changes", result.diagnostics(), "dealui", operations);
            return;
        }
        dealUi = result.source();
        dealUiSemanticRepairs = 0;
        repairMustFinishDeal = false;
        repairScopes = List.of();
        repairDiagnostics = List.of();
        forcedArtifact = "";
        inspection = CanonicalCompiler.compileCanonicalApp(deal, dealUi, pack, packSpecifier);
        resetSurface();
        addTranscript("apply_deal_ui_changes", Map.of("accepted", true, "impact", result.impact()));
        if (inspection.valid() && finalChange) status = Status.COMPLETE;
    }

    private void reject(
            String tool,
            List<StructuredDiagnostic> diagnostics,
            String artifact,
            Object attemptedOperations) {
        semanticRepairs++;
        int artifactRepairs = artifact.equals("deal")
                ? ++dealSemanticRepairs
                : ++dealUiSemanticRepairs;
        addTranscript(tool, Map.of(
                "accepted", false,
                "diagnostics", compactDiagnostics(diagnostics),
                "attemptedOperations", attemptedOperations));
        if (artifactRepairs > maxSemanticRepairs) {
            status = Status.FAILED;
            deal = previousDeal;
            dealUi = previousDealUi;
            return;
        }
        repairScopes = diagnostics.stream().flatMap(value -> value.repairScopes().stream()).distinct().toList();
        repairDiagnostics = List.copyOf(diagnostics);
        repairMustFinishDeal = generation
                && generationStage().equals("declarations")
                && !diagnostics.isEmpty()
                && diagnostics.stream().allMatch(value -> value.code().equals("E2002"));
        forcedArtifact = artifact;
    }

    private static Map<String, Object> compactDiagnostics(List<StructuredDiagnostic> diagnostics) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Map<String, Object>> examples = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (StructuredDiagnostic diagnostic : diagnostics) {
            counts.merge(diagnostic.code(), 1, Integer::sum);
            String identity = diagnostic.code() + "\u0000" + diagnostic.message();
            if (!seen.add(identity) || examples.size() >= 6) continue;
            Map<String, Object> example = new LinkedHashMap<>();
            example.put("code", diagnostic.code());
            example.put("message", diagnostic.message());
            if (!diagnostic.expected().isBlank()) example.put("expected", diagnostic.expected());
            if (!diagnostic.actual().isBlank()) example.put("actual", diagnostic.actual());
            if (diagnostic.range() != null) example.put("range", diagnostic.range());
            examples.add(Map.copyOf(example));
        }
        return Map.of("counts", counts, "examples", examples);
    }

    private void unchanged() {
        if (!forcedArtifact.isEmpty() || !repairScopes.isEmpty()) {
            throw new IllegalArgumentException("unchanged is unavailable while a compiler repair is required");
        }
        status = Status.COMPLETE;
    }

    private void fail(String code, String message) {
        addTranscript("streaming_compiler", Map.of("code", code, "message", message));
        status = Status.FAILED;
        deal = previousDeal;
        dealUi = previousDealUi;
    }

    private void addTranscript(String tool, Map<String, Object> value) {
        transcript.add(Map.of("tool", tool, "result", value));
    }

    private List<Map<String, Object>> agentTranscript() {
        List<Map<String, Object>> relevant = transcript.stream()
                .filter(entry -> !entry.get("tool").equals("protocol_shadow"))
                .toList();
        return relevant.subList(Math.max(0, relevant.size() - 2), relevant.size());
    }

    private void recordShadowParity(
            String artifact,
            boolean shadowAccepted,
            String shadowDigest,
            boolean checkedAccepted,
            String checkedDigest) {
        boolean equal = shadowAccepted == checkedAccepted && shadowDigest.equals(checkedDigest);
        addTranscript("protocol_shadow", Map.of(
                "artifact", artifact,
                "v1Accepted", shadowAccepted,
                "v2Accepted", checkedAccepted,
                "sourceDigestEqual", shadowDigest.equals(checkedDigest),
                "equal", equal));
    }

    private static boolean isPreconditionRejection(List<StructuredDiagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(value ->
                value.code().equals("CP1001")
                        || value.code().equals("CP1010")
                        || value.code().equals("CP1011"));
    }

    private List<DealCompilerWorkspace.Operation> dealOperations(CanonicalJson.Value value) {
        List<DealCompilerWorkspace.Operation> result = new ArrayList<>();
        for (CanonicalJson.Value item : CompilerProtocolJson.requireArray(value, "DEAL operations").items()) {
            CanonicalJson.Obj operation = CompilerProtocolJson.requireObject(item, "DEAL operation");
            String name = string(operation, "operation");
            SemanticId target = resolveAlias(string(operation, "target"), null);
            result.add(switch (name) {
                case DealCompilerWorkspace.ADD_DECLARATION -> new DealCompilerWorkspace.AddDeclaration(
                        target, string(operation, "declaration"));
                case DealCompilerWorkspace.REMOVE_DECLARATION -> new DealCompilerWorkspace.RemoveDeclaration(target);
                case DealCompilerWorkspace.REPLACE_DECLARATION -> new DealCompilerWorkspace.ReplaceDeclaration(
                        target, string(operation, "declaration"));
                case DealCompilerWorkspace.REPLACE_FUNCTION_BODY -> new DealCompilerWorkspace.ReplaceFunctionBody(
                        target, string(operation, "body"));
                case DealCompilerWorkspace.REPLACE_BLOCK_BODY -> new DealCompilerWorkspace.ReplaceBlockBody(
                        target, string(operation, "body"));
                default -> throw new IllegalArgumentException("Unsupported DEAL operation " + name);
            });
        }
        return result;
    }

    private List<UiCompilerWorkspace.Operation> dealUiOperations(CanonicalJson.Value value) {
        List<UiCompilerWorkspace.Operation> result = new ArrayList<>();
        for (CanonicalJson.Value item : CompilerProtocolJson.requireArray(value, "Deal UI operations").items()) {
            CanonicalJson.Obj operation = CompilerProtocolJson.requireObject(item, "Deal UI operation");
            String name = string(operation, "operation");
            SemanticId target = resolveAlias(string(operation, "target"), null);
            result.add(switch (name) {
                case UiCompilerWorkspace.ADD_VIEW -> new UiCompilerWorkspace.AddView(
                        target, string(operation, "source"));
                case UiCompilerWorkspace.REMOVE_VIEW -> new UiCompilerWorkspace.RemoveView(target);
                case UiCompilerWorkspace.REPLACE_VIEW_BODY -> new UiCompilerWorkspace.ReplaceViewBody(
                        target, string(operation, "body"));
                case UiCompilerWorkspace.REPLACE_SUBTREE -> new UiCompilerWorkspace.ReplaceSubtree(
                        target, string(operation, "source"));
                case UiCompilerWorkspace.INSERT_CHILD -> new UiCompilerWorkspace.InsertChild(
                        target, integer(operation, "index"), string(operation, "source"));
                case UiCompilerWorkspace.REMOVE_NODE -> new UiCompilerWorkspace.RemoveNode(target);
                case UiCompilerWorkspace.MOVE_NODE -> new UiCompilerWorkspace.MoveNode(
                        target, resolveAlias(string(operation, "newParent"), "U"), integer(operation, "index"));
                case UiCompilerWorkspace.SET_PROPERTY -> new UiCompilerWorkspace.SetProperty(
                        target, string(operation, "property"), string(operation, "expression"));
                default -> throw new IllegalArgumentException("Unsupported Deal UI operation " + name);
            });
        }
        return result;
    }

    private void refreshAliases() {
        aliases.clear();
        aliasesById.clear();
        putAlias("M1", inspection.deal().moduleId());
        int index = 1;
        for (var symbol : inspection.deal().symbols().stream()
                .sorted(Comparator.comparing(SymbolSnapshot::name)
                        .thenComparing(SymbolSnapshot::kind))
                .toList()) {
            putAlias("S" + index++, symbol.id());
        }
        index = 1;
        for (var node : inspection.deal().nodes().stream()
                .sorted(Comparator.comparing(value -> value.id().value())).toList()) {
            putAlias("B" + index++, node.id());
        }
        if (inspection.dealUi() == null) return;
        putAlias("D1", inspection.dealUi().documentId());
        index = 1;
        for (var view : inspection.dealUi().views().stream()
                .sorted(Comparator.comparing(UiCompilerWorkspace.UiViewSnapshot::name)).toList()) {
            putAlias("V" + index++, view.id());
        }
        index = 1;
        for (var node : inspection.dealUi().nodes()) putAlias("U" + index++, node.id());
    }

    private void resetSurface() {
        dealGrants.clear();
        dealUiGrants.clear();
        queriedAliases.clear();
        refreshAliases();
    }

    private void putAlias(String alias, SemanticId id) {
        aliases.put(alias, id);
        aliasesById.put(id.value(), alias);
    }

    private SemanticId resolveAlias(String alias, String prefix) {
        if (prefix != null && !alias.startsWith(prefix)) {
            throw new IllegalArgumentException("Expected a " + prefix + " target alias, found " + alias);
        }
        SemanticId result = aliases.get(alias);
        if (result == null) throw new IllegalArgumentException("Unknown or stale target alias " + alias);
        return result;
    }

    private String alias(SemanticId id) {
        String result = aliasesById.get(id.value());
        if (result == null) throw new IllegalArgumentException("Target has no current agent alias " + id.value());
        return result;
    }

    private List<String> aliases(String prefix) {
        return aliases.keySet().stream().filter(value -> value.startsWith(prefix)).toList();
    }

    private String existingDealSymbolNames() {
        return inspection.deal().symbols().stream()
                .map(SymbolSnapshot::name)
                .sorted()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String operationName(DealCompilerWorkspace.Operation operation) {
        return switch (operation) {
            case DealCompilerWorkspace.AddDeclaration ignored -> DealCompilerWorkspace.ADD_DECLARATION;
            case DealCompilerWorkspace.RemoveDeclaration ignored -> DealCompilerWorkspace.REMOVE_DECLARATION;
            case DealCompilerWorkspace.ReplaceDeclaration ignored -> DealCompilerWorkspace.REPLACE_DECLARATION;
            case DealCompilerWorkspace.ReplaceFunctionBody ignored -> DealCompilerWorkspace.REPLACE_FUNCTION_BODY;
            case DealCompilerWorkspace.ReplaceBlockBody ignored -> DealCompilerWorkspace.REPLACE_BLOCK_BODY;
        };
    }

    private void addQueryTool(
            List<Map<String, Object>> tools, String name, String description, String prefix) {
        addQueryTool(tools, name, description, aliases(prefix));
    }

    private void addQueryTool(
            List<Map<String, Object>> tools, String name, String description, List<String> candidates) {
        List<String> values = candidates.stream()
                .filter(value -> !queriedAliases.contains(value)).toList();
        if (!values.isEmpty()) {
            tools.add(tool(name, description, objectSchema(Map.of("target", enumSchema(values)))));
        }
    }

    private String symbolAlias(String name) {
        return inspection.deal().symbols().stream()
                .filter(value -> value.name().equals(name))
                .map(value -> alias(value.id()))
                .findFirst().orElseThrow();
    }

    private List<String> nodeAliases(String ownerName) {
        SemanticId owner = inspection.deal().symbols().stream()
                .filter(value -> value.name().equals(ownerName))
                .map(SymbolSnapshot::id)
                .findFirst().orElseThrow();
        return inspection.deal().nodes().stream()
                .filter(value -> value.ownerId().equals(owner))
                .map(value -> alias(value.id()))
                .toList();
    }

    private Map<String, Object> compactDealIndex() {
        List<Map<String, Object>> symbols = inspection.deal().symbols().stream().map(symbol -> Map.<String, Object>of(
                "target", alias(symbol.id()),
                "kind", symbol.kind(),
                "name", symbol.name(),
                "signature", symbol.signature(),
                "callers", knownAliases(symbol.callers()),
                "callees", knownAliases(symbol.callees())))
                .toList();
        List<Map<String, Object>> nodes = inspection.deal().nodes().stream().map(node -> Map.<String, Object>of(
                "target", alias(node.id()),
                "owner", alias(node.ownerId()),
                "kind", node.kind()))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revision", inspection.deal().sourceDigest());
        result.put("module", "M1");
        result.put("interface", compactInterface());
        result.put("symbols", symbols);
        result.put("nodes", nodes);
        return result;
    }

    private Map<String, Object> compactInterface() {
        var value = inspection.deal().appInterface();
        if (value == null) return Map.of();
        return Map.of(
                "fingerprint", value.fingerprint(),
                "rootState", value.rootState(),
                "rootSchema", value.rootSchemaFingerprint(),
                "types", value.types().stream().map(type -> Map.of(
                        "name", type.name(), "fields", type.fields())).toList(),
                "actions", value.actions().stream().map(type -> Map.of(
                        "name", type.name(), "fields", type.fields())).toList(),
                "capabilities", value.capabilities());
    }

    private Map<String, Object> compactDealUiIndex() {
        List<Map<String, Object>> views = inspection.dealUi().views().stream().map(view -> Map.<String, Object>of(
                "target", alias(view.id()),
                "name", view.name(),
                "root", view.root(),
                "roots", knownAliases(view.rootNodes())))
                .toList();
        List<Map<String, Object>> nodes = inspection.dealUi().nodes().stream().map(node -> Map.<String, Object>of(
                "target", alias(node.id()),
                "view", alias(node.ownerViewId()),
                "kind", node.kind(),
                "component", node.component(),
                "state", node.statePaths(),
                "actions", node.actionBindings()))
                .toList();
        return Map.of(
                "revision", inspection.dealUi().sourceDigest(),
                "interfaceFingerprint", inspection.dealUi().appInterfaceFingerprint(),
                "views", views,
                "nodes", nodes);
    }

    private Map<String, Object> compactComponentPack() {
        var snapshot = inspection.componentPack();
        if (snapshot == null) return Map.of();
        return Map.of(
                "version", snapshot.version(),
                "components", snapshot.components().stream().map(component -> Map.of(
                        "name", component.name(),
                        "props", component.properties().stream().map(property -> Map.of(
                                "name", property.name(),
                                "type", property.type(),
                                "optional", property.optional())).toList(),
                        "children", component.children(),
                        "events", component.events(),
                        "capabilities", component.capabilities())).toList(),
                "tokens", snapshot.tokens());
    }

    private List<String> knownAliases(List<SemanticId> ids) {
        return ids.stream().map(value -> aliasesById.get(value.value()))
                .filter(java.util.Objects::nonNull).toList();
    }

    private Map<String, Object> compactDealSlice(SemanticSlice slice) {
        return Map.of(
                "target", alias(slice.ownerId()),
                "kind", slice.kind(),
                "source", slice.source(),
                "dependencies", knownAliases(slice.dependencies()),
                "nodes", slice.nodes().stream().map(node -> Map.of(
                        "target", alias(node.id()), "kind", node.kind())).toList(),
                "operations", compactOperations(slice.allowedOperations()));
    }

    private Map<String, Object> compactUiSlice(UiCompilerWorkspace.UiSemanticSlice slice) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", alias(slice.ownerId()));
        result.put("kind", slice.kind());
        result.put("source", slice.source());
        if (slice.node() != null) {
            result.put("component", slice.node().component());
            result.put("state", slice.node().statePaths());
            result.put("actions", slice.node().actionBindings());
            result.put("properties", slice.node().writableProperties());
        }
        result.put("children", slice.children().stream().map(value -> alias(value.id())).toList());
        result.put("operations", compactOperations(slice.allowedOperations()));
        return result;
    }

    private List<Map<String, Object>> compactOperations(List<OperationDescriptor> operations) {
        return operations.stream().map(value -> Map.<String, Object>of(
                "operation", value.operation(),
                "target", alias(value.targetId()),
                "fields", value.requiredFields())).toList();
    }

    private List<Map<String, Object>> compactRepairScopes() {
        return repairScopes.stream()
                .filter(value -> aliasesById.containsKey(value.ownerId().value()))
                .map(value -> Map.<String, Object>of(
                        "operation", value.operation(), "target", alias(value.ownerId())))
                .toList();
    }

    private static void grant(
            Map<String, OperationDescriptor> grants, List<OperationDescriptor> descriptors) {
        descriptors.forEach(value -> grants.put(grantKey(value.operation(), value.targetId()), value));
    }

    private static Map<String, String> fingerprints(Map<String, OperationDescriptor> grants) {
        Map<String, String> result = new LinkedHashMap<>();
        grants.values().forEach(value -> result.put(value.targetId().value(), value.targetFingerprint()));
        return Map.copyOf(result);
    }

    private static String grantKey(String operation, SemanticId id) {
        return operation + ":" + id.value();
    }

    private List<String> queriedUiContainerAliases() {
        return dealUiGrants.values().stream()
                .filter(value -> value.operation().equals(UiCompilerWorkspace.INSERT_CHILD))
                .map(value -> alias(value.targetId())).distinct().toList();
    }

    private static void requireSameTarget(SemanticId expected, SemanticId actual) {
        if (!expected.equals(actual)) throw new IllegalStateException("Compiler returned a different semantic target");
    }

    private static Map<String, Object> transactionTool(
            String name, String description, List<Map<String, Object>> variants) {
        return tool(name, description, objectSchema(Map.of(
                "operations", Map.of("type", "array", "minItems", 1, "items", Map.of("anyOf", variants)),
                "final", Map.of("type", "boolean"))));
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> parameters) {
        return Map.of("name", name, "description", description, "parameters", parameters, "strict", true);
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties) {
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.copyOf(properties.keySet()),
                "additionalProperties", false);
    }

    private static Map<String, Object> enumSchema(List<String> values) {
        return Map.of("type", "string", "enum", values);
    }

    private static Map<String, Object> constantString(String value) {
        return Map.of("type", "string", "const", value);
    }

    private static CanonicalJson.Value field(CanonicalJson.Obj object, String name) {
        return CompilerProtocolJson.field(object, name);
    }

    private static String string(CanonicalJson.Obj object, String name) {
        return CompilerProtocolJson.stringField(object, name);
    }

    private static int integer(CanonicalJson.Obj object, String name) {
        return CompilerProtocolJson.intField(object, name);
    }

    private static boolean booleanField(CanonicalJson.Obj object, String name) {
        CanonicalJson.Value value = field(object, name);
        if (value instanceof CanonicalJson.Bool flag) return flag.value();
        throw new IllegalArgumentException("Protocol field '" + name + "' must be a boolean");
    }

    private enum Status { REQUEST, COMPLETE, FAILED }
}
