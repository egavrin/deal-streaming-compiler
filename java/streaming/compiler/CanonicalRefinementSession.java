package streaming.compiler;

import deal.compiler.CompilerProtocol.ChangeSetPrecondition;
import deal.compiler.CompilerProtocol.ChangeInspection;
import deal.compiler.CompilerProtocol;
import deal.compiler.CompilerProtocol.OperationDescriptor;
import deal.compiler.CompilerProtocol.RepairScope;
import deal.compiler.CompilerProtocol.RepairSlot;
import deal.compiler.CompilerProtocol.RepairSlotStatus;
import deal.compiler.CompilerProtocol.RepairWorkspaceSnapshot;
import deal.compiler.CompilerProtocol.SlotPatch;
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
            change through the artifact-specific inspect tool, then submit one small atomic transaction using only operations unlocked by
            the compiler-owned dependency cone. Never regenerate an unrelated unit. Compiler diagnostics and writable repair
            scopes are authoritative. Use no scenario templates. Set final to false only when another
            behavior or visual transaction is required. The surface has distinct inspect and edit
            phases. In the inspect phase call inspect_deal_change or inspect_deal_ui_change once with every required semantic anchor
            and operation kind. In the edit phase call the single write tool directly. Emit exactly
            one write tool call.
            replaceFunctionBody and replaceBlockBody accept only statements inside the existing
            braces. Never include a function signature, declaration, or the outer braces in body.
            Inspecting a DEAL symbol or body also unlocks adding a new sibling declaration when the
            requested change needs a new record, action, helper or handler.
            """.strip();
    private static final String DEAL_EDIT_CONTRACT = """

            Active artifact: DEAL. DEAL is a mutable TypeScript-shaped subset, not full TypeScript.
            Classes are field-only nominal records with typed defaulted fields; constructors, methods,
            parameter properties, interfaces and the new operator are invalid. Use int for integral values.
            Only [] array literals are valid; append to fresh arrays with items[items.length] = value.
            Do not use const, var, arrow functions, ternaries, switch, ++, compound assignment or JS methods.
            Every action handler declaration must literally start with // @ui-update on the line immediately
            before export function, take (state: AppState, action: SomeAction), and return a complete AppState.
            Mutation is allowed only on fresh locals; state and action parameters are borrowed.
            DEAL has no number-to-string conversion. Keep numeric state numeric and let Deal UI
            render it with typed numeric components such as IntText instead of concatenating labels.
            """;
    private static final String DEAL_UI_EDIT_CONTRACT = """

            Active artifact: Deal UI. It is declarative and read-only: no indexing, array/object literals,
            assignment, arbitrary calls, length, methods, coercion or ternaries. Render dynamic collections
            only with ForEach(state.items, item: app.Item, key: item.id) { ... }. Use only compiler-published
            components, state paths, action constructors and tokens. A view body has exactly one root node.
            Modernize an existing screen with replace_deal_ui_view. Never emulate view replacement by
            removing the current root view and adding another view.
            """;
    private static final String DEAL_GENERATION_SYSTEM_PROMPT = """
            Create the behavior of one complete canonical DEAL application through the compact
            compiler surface. Inspect the DEAL module and bootstrap declarations, then submit one
            cohesive atomic transaction. Emit exactly one write tool call; read-only queries may
            be batched. Set final=true only when behavior is complete. For a complex application,
            set final=false, inspect the new revision, and continue with another small transaction.
            Never return prose.
            Obey generationStage. In bootstrap, replace AppState and initialState and add any
            field-only nominal record types referenced by AppState in the same atomic ChangeSet.
            Inspect the module together with both bootstrap units before that write. Every collection
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
            Before finish_deal, compare the original request with the accepted AppInterface. Every explicitly
            requested user interaction, control, timer, pointer input or host effect must have a reachable nominal
            action and a checked handler or effect. Do not finish an interactive request with zero actions. This is a
            request-fidelity check, not permission to add scenario-specific behavior.

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
            Keep dynamic numeric values as int or number fields. DEAL has no number-to-string
            conversion; Deal UI formats numeric state with typed components such as ui.IntText.
            Use integer platform helpers only when listed by the host contract.
            Every state/action-to-state handler must include // @ui-update immediately before its
            export function declaration inside the same declaration string. Such a handler has
            exactly two parameters, (state: AppState, action: SomeAction). Never annotate a
            one-parameter helper; helpers are ordinary unannotated functions. The declaration string
            must literally start like `// @ui-update\nexport function updateName(...)`; never place the
            marker after the opening brace or anywhere inside the function body.
            """.strip();

    private static final String DEAL_UI_GENERATION_SYSTEM_PROMPT = """
            Create the complete Deal UI presentation for the supplied compiler-extracted
            AppInterface. Inspect the bootstrap root view, then replace its body in one atomic UI
            transaction and mark it final. Emit exactly one write tool call; read-only queries may
            be batched. The replacement body contains only statements inside the existing view:
            omit the view signature and outer braces. Use only operations in the current tool
            schema. Never return prose.
            Deal UI is declarative and read-only. Use only components and tokens in componentPack,
            field paths from interface, action constructors, literals, When and ForEach. It has no
            indexing, array/object literals, assignments, arbitrary calls, length, methods or
            string-number coercion or ternary expressions. Dynamic collections use exactly
            ForEach(state.items, item: app.Item, key: item.id) { ui.Text(value: item.label) }.
            The key expression must be a stable unique int or string field rooted at the item. Never
            use a boolean, display value, list index or mutable status as a key.
            Do not use ui.ForEach, item in, lambdas or a body parameter line. Use === and !== for
            equality and When(condition) { ... } Else { ... } for visual branches. Bind all
            reachable input actions. Use semantic native components, one app-owned AppTheme, an
            adaptive Root, accessible labels, and Canvas/PointerSurface only for spatial content.
            A view body produces exactly one root node. Use the shape
            ui.AppTheme(...) { ui.Root(...) { ... } }; AppTheme wraps Root. Never emit AppTheme and
            Root as siblings, and never leave AppTheme without its child block.
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
    private final Set<String> unchangedArtifacts = new LinkedHashSet<>();
    private String deal;
    private String dealUi;
    private CanonicalCompiler.Inspection inspection;
    private List<RepairScope> repairScopes = List.of();
    private List<StructuredDiagnostic> repairDiagnostics = List.of();
    private Map<String, String> rejectedPayloads = Map.of();
    private String rejectedAttemptFingerprint = "";
    private RepairWorkspaceSnapshot repairWorkspace;
    private String repairArtifact = "";
    private boolean repairFinal;
    private boolean repairReplacesAppState;
    private boolean repairReplacesInitialState;
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
    private int repairSlotsStaged;
    private int repairSlotsPreserved;
    private int repairSlotPatches;
    private int maxRepairGroupWidth;
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
        request.put("instructions", instructions());
        request.put("input", input);
        request.put("tools", tools);
        request.put("round", rounds + 1);
        request.put("semanticRepairs", semanticRepairs);
        return CompilerProtocolJson.encode(request);
    }

    private String instructions() {
        if (generation) {
            return forcedArtifact.equals("dealui")
                    ? DEAL_UI_GENERATION_SYSTEM_PROMPT
                    : DEAL_GENERATION_SYSTEM_PROMPT;
        }
        if (forcedArtifact.equals("deal")) return REFINEMENT_SYSTEM_PROMPT + DEAL_EDIT_CONTRACT;
        if (forcedArtifact.equals("dealui")) return REFINEMENT_SYSTEM_PROMPT + DEAL_UI_EDIT_CONTRACT;
        return REFINEMENT_SYSTEM_PROMPT;
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
            case "inspect_deal_change" -> inspectChange("deal", arguments);
            case "inspect_deal_ui_change" -> inspectChange("dealui", arguments);
            case "apply_deal_foundation" -> applyDealFoundation(arguments);
            case "append_deal_behavior" -> appendDealBehavior(arguments);
            case "add_deal_action_handler" -> addDealActionHandler(arguments);
            case "add_deal_supporting_declaration" -> addDealSupportingDeclaration(arguments);
            case "apply_deal_changes" -> applyDeal(arguments);
            case "finish_deal" -> finishDeal();
            case "replace_deal_ui_view" -> replaceDealUiView(arguments);
            case "apply_deal_ui_changes" -> applyDealUi(arguments);
            case "patch_repair_slot" -> patchRepairSlot(arguments);
            case "artifact_unchanged" -> artifactUnchanged(arguments);
            case "unchanged" -> unchanged();
            default -> throw new IllegalArgumentException("Unsupported streaming-compiler tool: " + name);
        }
    }

    private static boolean isReadOnlyQuery(String name) {
        return name.equals("query_deal_module")
                || name.equals("inspect_deal_change")
                || name.equals("inspect_deal_ui_change")
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
        result.put("repairMetrics", Map.of(
                "slotsStaged", repairSlotsStaged,
                "slotsPreserved", repairSlotsPreserved,
                "slotPatches", repairSlotPatches,
                "maxDependencyGroupWidth", maxRepairGroupWidth));
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
        if (repairWorkspace != null) {
            RepairSlot active = repairWorkspace.slots().stream()
                    .filter(value -> value.status() == RepairSlotStatus.REJECTED)
                    .findFirst().orElseThrow();
            context.put("repairWorkspace", Map.of(
                    "artifact", repairArtifact,
                    "round", repairWorkspace.repairRound(),
                    "activeSlot", Map.of(
                            "slot", active.slotId(),
                            "operation", active.operation(),
                            "target", alias(active.targetId()),
                            "payload", agentRepairPayload(active),
                            "diagnostics", compactDiagnostics(active.diagnostics())),
                    "preservedSlots", repairWorkspace.slots().stream()
                            .filter(value -> value.status() != RepairSlotStatus.REJECTED)
                            .map(value -> Map.of(
                                    "slot", value.slotId(),
                                    "status", value.status().name(),
                                    "payloadFingerprint", value.payloadFingerprint()))
                            .toList()));
        }
        if (!repairScopes.isEmpty()) context.put("repairScopes", compactRepairScopes());
        if (!repairDiagnostics.isEmpty()) {
            context.put("repairDirective", Map.of(
                    "instruction", "Change the rejected operation. The previous payload is excluded by the active tool schema; never resubmit it.",
                    "rejectedCandidateFingerprint", rejectedAttemptFingerprint,
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
        if (repairWorkspace != null) return List.of(repairSlotTool());
        if (generation && generationStage().equals("bootstrap")) unlockGreenfieldFoundation();
        List<Map<String, Object>> result = new ArrayList<>();
        boolean repairing = !repairScopes.isEmpty();
        boolean writeUnlocked = !dealGrants.isEmpty() || !dealUiGrants.isEmpty();
        if (!repairing && !writeUnlocked) {
            result.addAll(inspectChangeTools());
        }
        List<Map<String, Object>> dealOperations = repairMustFinishDeal ? List.of() : dealOperationSchemas();
        if (generation && generationStage().equals("bootstrap") && foundationReady()) {
            result.add(tool("apply_deal_foundation",
                    "Atomically declare supporting record types and replace the two bootstrap units.",
                    objectSchema(Map.of(
                            "supportingDeclarations", Map.of(
                                    "type", "array",
                                    "items", Map.of("type", "string", "description",
                                            "One complete unique field-only class declaration. Use int, not number, for integral fields and defaults. "
                                                    + "A record stored in an AppState array must include a stable unique id: int or key: string field for ForEach")),
                            "appStateDeclaration", Map.of("type", "string", "description",
                                    "Complete export class AppState declaration. Use int, not number, for integral fields and defaults"),
                            "initialStateBody", Map.of("type", "string", "description",
                                    "Statements only; omit signature and outer braces. Use int locals for integer literals and loops; only [] array literals")))));
        } else if (generation && generationStage().equals("declarations") && !repairMustFinishDeal) {
            result.add(dealBehaviorTool());
        } else if (!dealOperations.isEmpty()) {
            result.add(transactionTool("apply_deal_changes", "Apply one atomic DEAL ChangeSet.", dealOperations));
        }
        if (!generation && dealGrants.values().stream().anyMatch(value ->
                value.operation().equals(DealCompilerWorkspace.ADD_DECLARATION))) {
            result.add(dealActionHandlerTool());
            result.add(dealSupportingDeclarationTool());
        }
        if (generation && generationStage().equals("declarations")) {
            result.add(tool("finish_deal",
                    repairMustFinishDeal
                            ? "All rejected declarations already exist. Drop the duplicate ChangeSet and transition to Deal UI."
                            : "Current checked DEAL behavior satisfies every requested interaction. Transition to Deal UI without changing source.",
                    objectSchema(Map.of("reason", Map.of(
                            "type", "string",
                            "description", "Briefly name the accepted actions that satisfy the requested interactions")))));
        }
        List<Map<String, Object>> uiOperations = dealUiOperationSchemas();
        List<OperationDescriptor> replaceViewGrants = dealUiGrants.values().stream()
                .filter(value -> value.operation().equals(UiCompilerWorkspace.REPLACE_VIEW_BODY))
                .toList();
        if (!replaceViewGrants.isEmpty() && !generation) {
            result.add(replaceDealUiViewTool(replaceViewGrants));
        }
        if (!uiOperations.isEmpty()) {
            result.add(transactionTool(
                    "apply_deal_ui_changes", "Apply one atomic Deal UI ChangeSet.", uiOperations, generation));
        }
        if (!generation && !repairing && !forcedArtifact.isEmpty() && writeUnlocked) {
            result.add(tool("artifact_unchanged",
                    "Report that the inspected artifact needs no edit. This is valid only after inspecting compiler-owned evidence; it advances to the other artifact.",
                    objectSchema(Map.of(
                            "artifact", constantString(forcedArtifact),
                            "evidenceTargets", Map.of(
                                    "type", "array", "minItems", 1, "uniqueItems", true,
                                    "items", enumSchema(List.copyOf(queriedAliases))),
                            "reason", Map.of("type", "string")))));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> dealBehaviorTool() {
        Map<String, Object> actionHandler = objectSchema(Map.of(
                "actionDeclaration", Map.of(
                        "type", "string",
                        "description", "Exactly one complete unique field-only export class Action declaration"),
                "handlerDeclaration", Map.of(
                        "type", "string",
                        "description", "Exactly one complete export function for that Action. Put // @ui-update on the line immediately before export function")));
        return tool(
                "append_deal_behavior",
                "Bootstrap is committed. Atomically append complete action-handler pairs and optional supporting helpers; never emit an action without its handler. Set final=false when another bounded behavior batch is required, then set final=true on the last batch.",
                objectSchema(Map.of(
                        "supportingDeclarations", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string", "description",
                                        "Exactly one complete unique helper function or field-only record class")),
                        "actionHandlers", Map.of(
                                "type", "array", "minItems", 1,
                                "items", actionHandler),
                        "final", Map.of(
                                "type", "boolean",
                                "description", "False commits this batch and requests another compact behavior surface; true completes DEAL and advances to Deal UI"))));
    }

    private Map<String, Object> dealActionHandlerTool() {
        return tool(
                "add_deal_action_handler",
                "Atomically add one nominal Action and its required @ui-update handler. Use raw addDeclaration only for non-action records or helpers.",
                objectSchema(Map.of(
                        "actionDeclaration", Map.of(
                                "type", "string",
                                "description", "Exactly one complete unique field-only export class whose name ends in Action"),
                        "handlerDeclaration", Map.of(
                                "type", "string",
                                "description", "Exactly one complete handler for that Action, starting with // @ui-update immediately before export function"),
                        "final", Map.of("type", "boolean", "const", true))));
    }

    private Map<String, Object> dealSupportingDeclarationTool() {
        return tool(
                "add_deal_supporting_declaration",
                "Atomically add one non-action record or helper function. Classes whose names end in Action must use add_deal_action_handler instead.",
                objectSchema(Map.of(
                        "declaration", Map.of(
                                "type", "string",
                                "description", "Exactly one complete non-action field-only record class or helper function"),
                        "final", Map.of("type", "boolean"))));
    }

    private Map<String, Object> replaceDealUiViewTool(List<OperationDescriptor> grants) {
        return tool(
                "replace_deal_ui_view",
                "Atomically replace the body of one existing Deal UI view. Use this for screen modernization; never remove and re-add the root view.",
                objectSchema(Map.of(
                        "target", enumSchema(grants.stream().map(value -> alias(value.targetId())).toList()),
                        "body", Map.of(
                                "type", "string",
                                "description", "Declarative statements inside the existing view only; omit the view signature and outer braces"),
                        "final", Map.of("type", "boolean", "const", true))));
    }

    private boolean isAddDeclarationSchema(Map<String, Object> schema) {
        Object propertiesValue = schema.get("properties");
        if (!(propertiesValue instanceof Map<?, ?> properties)) return false;
        Object operationValue = properties.get("operation");
        if (!(operationValue instanceof Map<?, ?> operation)) return false;
        return DealCompilerWorkspace.ADD_DECLARATION.equals(operation.get("const"));
    }

    private void unlockGreenfieldFoundation() {
        SemanticId module = inspection.deal().moduleId();
        SemanticId appState = inspection.deal().symbols().stream()
                .filter(symbol -> symbol.name().equals("AppState"))
                .map(SymbolSnapshot::id).findFirst().orElseThrow();
        SemanticId initialBody = inspection.deal().nodes().stream()
                .filter(node -> inspection.deal().symbols().stream().anyMatch(symbol ->
                        symbol.id().equals(node.ownerId()) && symbol.name().equals("initialState")))
                .map(node -> node.id()).findFirst().orElseThrow();
        grant(dealGrants, CanonicalCompiler.queryDealModule(deal).allowedOperations());
        grant(dealGrants, CanonicalCompiler.queryDealSymbol(deal, appState).allowedOperations());
        grant(dealGrants, CanonicalCompiler.queryDealNode(deal, initialBody).allowedOperations());
        queriedAliases.add(alias(module));
        queriedAliases.add(alias(appState));
        queriedAliases.add(alias(initialBody));
    }

    private List<Map<String, Object>> inspectChangeTools() {
        List<String> artifacts = new ArrayList<>();
        if (!forcedArtifact.equals("dealui") && !unchangedArtifacts.contains("deal")) artifacts.add("deal");
        if (!forcedArtifact.equals("deal") && inspection.dealUi() != null
                && !unchangedArtifacts.contains("dealui")) artifacts.add("dealui");
        if (artifacts.isEmpty()) artifacts.add(forcedArtifact);
        return artifacts.stream().map(artifact -> tool(
                artifact.equals("deal") ? "inspect_deal_change" : "inspect_deal_ui_change",
                "Select " + (artifact.equals("deal") ? "DEAL" : "Deal UI")
                        + " anchors and operation kinds. The compiler derives the minimum dependency cone and writable surface.",
                inspectChangeParameters(artifact))).toList();
    }

    private Map<String, Object> inspectChangeParameters(String artifact) {
        List<String> targets = new ArrayList<>();
        List<String> operations = new ArrayList<>();
        if (artifact.equals("deal")) {
            targets.addAll(aliases("M"));
            targets.addAll(aliases("S"));
            targets.addAll(aliases("B"));
            operations.addAll(List.of(
                    DealCompilerWorkspace.ADD_DECLARATION,
                    DealCompilerWorkspace.REMOVE_DECLARATION,
                    DealCompilerWorkspace.REPLACE_DECLARATION,
                    DealCompilerWorkspace.REPLACE_FUNCTION_BODY,
                    DealCompilerWorkspace.REPLACE_BLOCK_BODY));
        } else {
            targets.addAll(aliases("D"));
            targets.addAll(aliases("V"));
            targets.addAll(aliases("U"));
            operations.addAll(List.of(
                    UiCompilerWorkspace.ADD_VIEW,
                    UiCompilerWorkspace.REMOVE_VIEW,
                    UiCompilerWorkspace.REPLACE_VIEW_BODY,
                    UiCompilerWorkspace.REPLACE_SUBTREE,
                    UiCompilerWorkspace.INSERT_CHILD,
                    UiCompilerWorkspace.REMOVE_NODE,
                    UiCompilerWorkspace.MOVE_NODE,
                    UiCompilerWorkspace.SET_PROPERTY));
        }
        return objectSchema(Map.of(
                "anchors", Map.of(
                        "type", "array", "minItems", 1, "uniqueItems", true,
                        "items", enumSchema(targets)),
                "requestedOperations", Map.of(
                        "type", "array", "minItems", 1, "uniqueItems", true,
                        "items", enumSchema(operations))));
    }

    private Map<String, Object> repairSlotTool() {
        List<RepairSlot> rejected = repairWorkspace.slots().stream()
                .filter(value -> value.status() == RepairSlotStatus.REJECTED).toList();
        if (rejected.isEmpty()) throw new IllegalStateException("Repair workspace has no rejected slot");
        RepairSlot active = rejected.get(0);
        Set<String> fields = new LinkedHashSet<>(active.payload().keySet());
        Map<String, Object> payloadProperties = new LinkedHashMap<>();
        fields.forEach(field -> payloadProperties.put(field, switch (field) {
            case "index" -> Map.of("type", "integer", "minimum", 0);
            case "newParentId" -> enumSchema(aliases("U"));
            default -> Map.of(
                    "type", "string",
                    "description", repairFieldDescription(active, field));
        }));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "object");
        payload.put("additionalProperties", false);
        payload.put("properties", payloadProperties);
        payload.put("required", List.copyOf(fields));
        return tool(
                "patch_repair_slot",
                "Patch the rejected " + active.operation() + " payload for compiler target "
                        + alias(active.targetId()) + ". The target and accepted siblings are immutable.",
                objectSchema(Map.of(
                        "slot", constantString(active.slotId()),
                        "payload", payload)));
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
            case "bootstrap" -> "Inspect the module and both bootstrap units. In one transaction add supporting field-only record types, replace AppState, and replace initialState; continue with final=false.";
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
            if (!generation && grant.operation().equals(DealCompilerWorkspace.ADD_DECLARATION)) return;
            Map<String, Object> extra = switch (grant.operation()) {
                case DealCompilerWorkspace.ADD_DECLARATION ->
                        Map.of("declaration", editableStringSchema(
                                (generation
                                        ? "Exactly one complete top-level class or function declaration with a unique name. "
                                        : "Exactly one complete non-action record or helper declaration. For a class ending in Action use add_deal_action_handler. ")
                                        + "Existing names: "
                                        + existingDealSymbolNames(), grant.operation(), grant.targetId(), "declaration"));
                case DealCompilerWorkspace.REPLACE_DECLARATION ->
                        Map.of("declaration", editableStringSchema(
                                "Exactly one declaration with the same name and kind as the target",
                                grant.operation(), grant.targetId(), "declaration"));
                case DealCompilerWorkspace.REPLACE_FUNCTION_BODY, DealCompilerWorkspace.REPLACE_BLOCK_BODY ->
                        Map.of("body", editableStringSchema(
                                "Statements only; omit declaration signature and outer braces",
                                grant.operation(), grant.targetId(), "body"));
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
            if (!generation && grant.operation().equals(UiCompilerWorkspace.REPLACE_VIEW_BODY)) return;
            if (grant.operation().equals(UiCompilerWorkspace.REMOVE_VIEW)
                    && inspection.dealUi().views().stream().anyMatch(view ->
                            view.id().equals(grant.targetId()) && view.root())) return;
            UiCompilerWorkspace.UiNodeSnapshot node = inspection.dealUi().nodes().stream()
                    .filter(value -> value.id().equals(grant.targetId())).findFirst().orElse(null);
            Map<String, Object> extra = switch (grant.operation()) {
                case UiCompilerWorkspace.ADD_VIEW -> Map.of("source", editableStringSchema(
                        "One complete new view declaration", grant.operation(), grant.targetId(), "source"));
                case UiCompilerWorkspace.REPLACE_VIEW_BODY -> Map.of("body", editableStringSchema(
                        "Declarative statements inside the existing view only", grant.operation(), grant.targetId(), "body"));
                case UiCompilerWorkspace.REPLACE_SUBTREE -> Map.of("source", editableStringSchema(
                        "One replacement Deal UI subtree", grant.operation(), grant.targetId(), "source"));
                case UiCompilerWorkspace.INSERT_CHILD -> Map.of(
                        "index", Map.of("type", "integer", "minimum", 0,
                                "maximum", node == null ? 0 : node.children().size()),
                        "source", editableStringSchema(
                                "One Deal UI child subtree", grant.operation(), grant.targetId(), "source"));
                case UiCompilerWorkspace.MOVE_NODE -> Map.of(
                        "newParent", enumSchema(queriedUiContainerAliases()),
                        "index", Map.of("type", "integer", "minimum", 0));
                case UiCompilerWorkspace.SET_PROPERTY -> Map.of(
                        "property", enumSchema(node == null ? List.of() : node.writableProperties()),
                        "expression", editableStringSchema(
                                "One typed Deal UI expression", grant.operation(), grant.targetId(), "expression"));
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

    private void inspectChange(String artifact, CanonicalJson.Obj arguments) {
        List<String> anchorAliases = stringArray(arguments, "anchors");
        List<String> requestedOperations = stringArray(arguments, "requestedOperations");
        if (!artifact.equals("deal") && !artifact.equals("dealui")) {
            throw new IllegalArgumentException("inspect artifact must be deal or dealui");
        }
        boolean wrongAlias = anchorAliases.stream().anyMatch(value -> artifact.equals("deal")
                ? !(value.startsWith("M") || value.startsWith("S") || value.startsWith("B"))
                : !(value.startsWith("D") || value.startsWith("V") || value.startsWith("U")));
        if (wrongAlias) throw new IllegalArgumentException("inspect anchor belongs to another artifact");
        List<SemanticId> anchors = anchorAliases.stream()
                .map(value -> resolveAlias(value, null)).toList();
        ChangeInspection change = artifact.equals("deal")
                ? CanonicalCompiler.inspectDealChange(
                        deal, inspection.deal().sourceDigest(), anchors, requestedOperations)
                : CanonicalCompiler.inspectDealUiChange(
                        deal, dealUi, pack, packSpecifier,
                        inspection.dealUi().sourceDigest(), anchors, requestedOperations);
        if (!change.diagnostics().isEmpty()) {
            throw new IllegalArgumentException("Compiler rejected inspect operation: "
                    + compactDiagnostics(change.diagnostics()));
        }
        if (artifact.equals("deal")) grant(dealGrants, change.allowedOperations());
        else grant(dealUiGrants, change.allowedOperations());
        queriedAliases.addAll(anchorAliases);
        addTranscript(artifact.equals("deal") ? "inspect_deal_change" : "inspect_deal_ui_change", Map.of(
                "artifact", artifact,
                "coneFingerprint", change.dependencyCone().fingerprint(),
                "anchors", anchorAliases,
                "context", change.editSlices().stream().map(slice -> Map.of(
                        "target", aliasesById.getOrDefault(slice.ownerId().value(), "dependency"),
                        "kind", slice.kind(),
                        "source", slice.source(),
                        "dependencies", slice.dependencies().stream()
                                .map(value -> aliasesById.getOrDefault(value.value(), "dependency"))
                                .toList())).toList(),
                "requiredDependencies", change.dependencyCone().members().stream()
                        .filter(value -> !value.exposure().equals("IMPACT_ONLY"))
                        .map(value -> Map.of(
                                "target", aliasesById.getOrDefault(value.id().value(), "dependency"),
                                "kind", value.kind(),
                                "exposure", value.exposure(),
                                "fingerprint", value.fingerprint()))
                        .toList()));
        forcedArtifact = artifact;
    }

    private void queryDealSymbol(String target) {
        SemanticId id = resolveAlias(target, "S");
        SemanticSlice slice = CanonicalCompiler.queryDealSymbol(deal, id);
        grant(dealGrants, slice.allowedOperations());
        grantModuleDeclarationInsertion();
        queriedAliases.add(target);
        addTranscript("query_deal_symbol", compactDealSlice(slice));
        forcedArtifact = "deal";
    }

    private void queryDealNode(String target) {
        SemanticId id = resolveAlias(target, "B");
        SemanticSlice slice = CanonicalCompiler.queryDealNode(deal, id);
        grant(dealGrants, slice.allowedOperations());
        grantModuleDeclarationInsertion();
        queriedAliases.add(target);
        addTranscript("query_deal_node", compactDealSlice(slice));
        forcedArtifact = "deal";
    }

    private void grantModuleDeclarationInsertion() {
        SemanticSlice module = CanonicalCompiler.queryDealModule(deal);
        module.allowedOperations().stream()
                .filter(value -> value.operation().equals(DealCompilerWorkspace.ADD_DECLARATION))
                .forEach(value -> dealGrants.put(grantKey(value.operation(), value.targetId()), value));
    }

    private void queryDealUiView(String target) {
        if (inspection.dealUi() == null) throw new IllegalStateException("Deal UI inspection is unavailable");
        SemanticId id = resolveAlias(target, "V");
        var slice = CanonicalCompiler.queryDealUiView(deal, dealUi, pack, packSpecifier, id);
        grant(dealUiGrants, slice.allowedOperations());
        var document = CanonicalCompiler.queryDealUiDocument(deal, dealUi, pack, packSpecifier);
        grant(dealUiGrants, document.allowedOperations());
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
        if (rejectRepeatedAttempt("deal", operations)) return;
        boolean finalChange = booleanField(arguments, "final");
        String beforeDigest = inspection.deal().sourceDigest();
        boolean replacesAppState = generation && operations.stream().anyMatch(this::replacesAppStateBootstrap);
        boolean replacesInitialState = generation && operations.stream().anyMatch(this::replacesInitialStateBootstrap);
        var precondition = new ChangeSetPrecondition(
                inspection.deal().sourceDigest(), fingerprints(dealGrants));
        var result = CanonicalCompiler.applyDealChangeChecked(deal, precondition, operations);
        if (!isPreconditionRejection(result.diagnostics())) {
            var shadow = CanonicalCompiler.applyDealChange(
                    deal, inspection.deal().sourceDigest(), operations);
            recordShadowParity("deal", shadow.accepted(), shadow.sourceDigest(), result.accepted(), result.sourceDigest());
        }
        if (!result.accepted()) {
            boolean duplicateOnly = generation && generationStage().equals("declarations")
                    && result.diagnostics().stream().allMatch(value -> value.code().equals("E2002"));
            if (!duplicateOnly) {
                ChangeInspection changeInspection = CanonicalCompiler.inspectDealChange(
                        deal, inspection.deal().sourceDigest(),
                        operations.stream().map(DealCompilerWorkspace.Operation::targetId).distinct().toList(),
                        operations.stream().map(CanonicalRefinementSession::operationName).distinct().toList());
                var staged = CanonicalCompiler.stageDealChange(
                        deal, precondition, changeInspection, operations);
                if (!staged.accepted() && staged.workspace().slots().stream()
                        .anyMatch(value -> value.status() == RepairSlotStatus.REJECTED)) {
                    beginRepairWorkspace("deal", staged.workspace(), result.diagnostics(), finalChange,
                            replacesAppState, replacesInitialState);
                    return;
                }
            }
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
        clearRejectedAttempt();
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
        if (!generation && result.impact().interfaceChanged()) {
            unlockRootViewAfterInterfaceChange();
        }
        if (inspection.valid() && finalChange && forcedArtifact.isEmpty()) status = Status.COMPLETE;
    }

    private void unlockRootViewAfterInterfaceChange() {
        if (inspection.dealUi() == null) return;
        UiCompilerWorkspace.UiViewSnapshot target = inspection.dealUi().views().stream()
                .filter(UiCompilerWorkspace.UiViewSnapshot::root)
                .findFirst()
                .orElseGet(() -> inspection.dealUi().views().stream().findFirst().orElse(null));
        if (target != null) queryDealUiView(alias(target.id()));
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

    private void appendDealBehavior(CanonicalJson.Obj arguments) {
        List<Map<String, Object>> operations = new ArrayList<>();
        String module = alias(inspection.deal().moduleId());
        grant(dealGrants, CanonicalCompiler.queryDealModule(deal).allowedOperations());
        CanonicalJson.Arr supporting = CompilerProtocolJson.requireArray(
                field(arguments, "supportingDeclarations"), "supportingDeclarations");
        for (CanonicalJson.Value value : supporting.items()) {
            if (!(value instanceof CanonicalJson.Str declaration)) {
                throw new IllegalArgumentException("supportingDeclarations must contain strings");
            }
            operations.add(Map.of(
                    "operation", DealCompilerWorkspace.ADD_DECLARATION,
                    "target", module,
                    "declaration", declaration.value()));
        }
        CanonicalJson.Arr pairs = CompilerProtocolJson.requireArray(
                field(arguments, "actionHandlers"), "actionHandlers");
        for (CanonicalJson.Value value : pairs.items()) {
            CanonicalJson.Obj pair = CompilerProtocolJson.requireObject(value, "action-handler pair");
            operations.add(Map.of(
                    "operation", DealCompilerWorkspace.ADD_DECLARATION,
                    "target", module,
                    "declaration", string(pair, "actionDeclaration")));
            operations.add(Map.of(
                    "operation", DealCompilerWorkspace.ADD_DECLARATION,
                    "target", module,
                    "declaration", string(pair, "handlerDeclaration")));
        }
        if (pairs.items().isEmpty()) throw new IllegalArgumentException("At least one action-handler pair is required");
        CanonicalJson.Obj transaction = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.decode(CompilerProtocolJson.encode(Map.of(
                        "operations", operations,
                        "final", booleanField(arguments, "final")))),
                "behavior transaction");
        applyDeal(transaction);
    }

    private void addDealActionHandler(CanonicalJson.Obj arguments) {
        String module = alias(inspection.deal().moduleId());
        grant(dealGrants, CanonicalCompiler.queryDealModule(deal).allowedOperations());
        CanonicalJson.Obj transaction = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.decode(CompilerProtocolJson.encode(Map.of(
                        "operations", List.of(
                                Map.of(
                                        "operation", DealCompilerWorkspace.ADD_DECLARATION,
                                        "target", module,
                                        "declaration", string(arguments, "actionDeclaration")),
                                Map.of(
                                        "operation", DealCompilerWorkspace.ADD_DECLARATION,
                                        "target", module,
                                        "declaration", string(arguments, "handlerDeclaration"))),
                        "final", booleanField(arguments, "final")))),
                "action-handler transaction");
        applyDeal(transaction);
    }

    private void addDealSupportingDeclaration(CanonicalJson.Obj arguments) {
        String declaration = string(arguments, "declaration");
        if (declaration.matches("(?s).*\\bclass\\s+[A-Za-z_][A-Za-z0-9_]*Action\\b.*")) {
            throw new IllegalArgumentException("Action declarations require add_deal_action_handler");
        }
        String module = alias(inspection.deal().moduleId());
        grant(dealGrants, CanonicalCompiler.queryDealModule(deal).allowedOperations());
        CanonicalJson.Obj transaction = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.decode(CompilerProtocolJson.encode(Map.of(
                        "operations", List.of(Map.of(
                                "operation", DealCompilerWorkspace.ADD_DECLARATION,
                                "target", module,
                                "declaration", declaration)),
                        "final", booleanField(arguments, "final")))),
                "supporting declaration transaction");
        applyDeal(transaction);
    }

    private void replaceDealUiView(CanonicalJson.Obj arguments) {
        SemanticId target = resolveAlias(string(arguments, "target"), "V");
        OperationDescriptor grant = dealUiGrants.get(grantKey(UiCompilerWorkspace.REPLACE_VIEW_BODY, target));
        if (grant == null) throw new IllegalArgumentException("View replacement is outside the compiler-owned change cone");
        CanonicalJson.Obj transaction = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.decode(CompilerProtocolJson.encode(Map.of(
                        "operations", List.of(Map.of(
                                "operation", UiCompilerWorkspace.REPLACE_VIEW_BODY,
                                "target", alias(target),
                                "body", string(arguments, "body"))),
                        "final", booleanField(arguments, "final")))),
                "view replacement transaction");
        applyDealUi(transaction);
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
        if (rejectRepeatedAttempt("dealui", operations)) return;
        boolean finalChange = booleanField(arguments, "final");
        var precondition = new ChangeSetPrecondition(
                inspection.dealUi().sourceDigest(), fingerprints(dealUiGrants));
        var result = CanonicalCompiler.applyDealUiChangeChecked(
                deal, dealUi, pack, packSpecifier, precondition, operations);
        if (!isPreconditionRejection(result.diagnostics())) {
            var shadow = CanonicalCompiler.applyDealUiChange(
                    deal, dealUi, pack, packSpecifier, inspection.dealUi().sourceDigest(), operations);
            recordShadowParity("dealui", shadow.accepted(), shadow.sourceDigest(), result.accepted(), result.sourceDigest());
        }
        if (!result.accepted()) {
            ChangeInspection changeInspection = CanonicalCompiler.inspectDealUiChange(
                    deal, dealUi, pack, packSpecifier, inspection.dealUi().sourceDigest(),
                    operations.stream().map(UiCompilerWorkspace.Operation::targetId).distinct().toList(),
                    operations.stream().map(CanonicalRefinementSession::operationName).distinct().toList());
            var staged = CanonicalCompiler.stageDealUiChange(
                    deal, dealUi, pack, packSpecifier, precondition, changeInspection, operations);
            if (!staged.accepted() && staged.workspace().slots().stream()
                    .anyMatch(value -> value.status() == RepairSlotStatus.REJECTED)) {
                beginRepairWorkspace("dealui", staged.workspace(), result.diagnostics(), finalChange, false, false);
                return;
            }
            reject("apply_deal_ui_changes", result.diagnostics(), "dealui", operations);
            return;
        }
        dealUi = result.source();
        dealUiSemanticRepairs = 0;
        repairMustFinishDeal = false;
        repairScopes = List.of();
        repairDiagnostics = List.of();
        clearRejectedAttempt();
        forcedArtifact = "";
        inspection = CanonicalCompiler.compileCanonicalApp(deal, dealUi, pack, packSpecifier);
        resetSurface();
        addTranscript("apply_deal_ui_changes", Map.of("accepted", true, "impact", result.impact()));
        if (inspection.valid() && finalChange) status = Status.COMPLETE;
    }

    private void beginRepairWorkspace(
            String artifact,
            RepairWorkspaceSnapshot workspace,
            List<StructuredDiagnostic> diagnostics,
            boolean finalChange,
            boolean replacesAppState,
            boolean replacesInitialState) {
        semanticRepairs++;
        int artifactRepairs = artifact.equals("deal")
                ? ++dealSemanticRepairs : ++dealUiSemanticRepairs;
        addTranscript("stage_change", Map.of(
                "accepted", false,
                "artifact", artifact,
                "diagnostics", compactDiagnostics(diagnostics),
                "slots", workspace.slots().stream().map(value -> Map.of(
                        "slot", value.slotId(),
                        "operation", value.operation(),
                        "target", alias(value.targetId()),
                        "status", value.status().name(),
                        "payloadFingerprint", value.payloadFingerprint())).toList()));
        if (artifactRepairs > maxSemanticRepairs) {
            status = Status.FAILED;
            deal = previousDeal;
            dealUi = previousDealUi;
            return;
        }
        repairWorkspace = workspace;
        repairSlotsStaged += workspace.slots().size();
        repairSlotsPreserved += (int) workspace.slots().stream()
                .filter(value -> value.status() == RepairSlotStatus.SEALED
                        || value.status() == RepairSlotStatus.STAGED).count();
        maxRepairGroupWidth = Math.max(maxRepairGroupWidth, workspace.groups().stream()
                .mapToInt(value -> value.slotIds().size()).max().orElse(0));
        repairArtifact = artifact;
        repairFinal = finalChange;
        repairReplacesAppState = replacesAppState;
        repairReplacesInitialState = replacesInitialState;
        repairScopes = List.of();
        repairDiagnostics = List.copyOf(diagnostics);
        forcedArtifact = artifact;
    }

    private void patchRepairSlot(CanonicalJson.Obj arguments) {
        if (repairWorkspace == null) throw new IllegalStateException("No active repair workspace");
        String slotId = string(arguments, "slot");
        RepairSlot slot = repairWorkspace.slots().stream()
                .filter(value -> value.slotId().equals(slotId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown repair slot " + slotId));
        CanonicalJson.Obj payloadObject = CompilerProtocolJson.requireObject(field(arguments, "payload"), "repair payload");
        Map<String, String> payload = new LinkedHashMap<>();
        payloadObject.entries().forEach(entry -> {
            if (entry.value() instanceof CanonicalJson.Str value) payload.put(entry.key(), value.value());
            else if (entry.value() instanceof CanonicalJson.Int value) payload.put(entry.key(), Integer.toString(value.value()));
            else throw new IllegalArgumentException("Repair payload fields must be strings or integers");
        });
        if (payload.containsKey("newParentId")) {
            payload.put("newParentId", resolveAlias(payload.get("newParentId"), "U").value());
        }
        if (payload.equals(slot.payload())) {
            addTranscript("compiler_no_progress", Map.of(
                    "artifact", repairArtifact,
                    "slot", slotId,
                    "payloadFingerprint", slot.payloadFingerprint()));
            return;
        }
        var patch = new SlotPatch(slotId, payload);
        repairSlotPatches++;
        var result = repairArtifact.equals("deal")
                ? CanonicalCompiler.patchDealRepairWorkspace(deal, repairWorkspace, List.of(patch))
                : CanonicalCompiler.patchDealUiRepairWorkspace(
                        deal, dealUi, pack, packSpecifier, repairWorkspace, List.of(patch));
        if (!result.accepted()) {
            semanticRepairs++;
            if (repairArtifact.equals("deal")) dealSemanticRepairs++;
            else dealUiSemanticRepairs++;
            repairWorkspace = result.workspace();
            repairDiagnostics = result.diagnostics();
            addTranscript("patch_repair_slot", Map.of(
                    "accepted", false,
                    "slot", slotId,
                    "diagnostics", compactDiagnostics(result.diagnostics())));
            if ((repairArtifact.equals("deal") ? dealSemanticRepairs : dealUiSemanticRepairs) > maxSemanticRepairs) {
                status = Status.FAILED;
                deal = previousDeal;
                dealUi = previousDealUi;
            }
            return;
        }
        if (repairArtifact.equals("deal")) completeDealRepair(result);
        else completeDealUiRepair(result);
    }

    private void completeDealRepair(deal.compiler.CompilerProtocol.RepairWorkspaceResult result) {
        var change = (deal.compiler.CompilerProtocol.ChangeResult) result.change();
        deal = result.source();
        appStateBootstrapReplaced |= repairReplacesAppState;
        initialStateBootstrapReplaced |= repairReplacesInitialState;
        boolean finalChange = repairFinal;
        clearRepairWorkspace();
        dealSemanticRepairs = 0;
        forcedArtifact = generation
                ? finalChange ? "dealui" : "deal"
                : change.impact().interfaceChanged() ? "dealui" : "";
        inspection = CanonicalCompiler.inspectCanonicalApp(deal, dealUi, pack, packSpecifier);
        resetSurface();
        if (generation && finalChange) transcript.clear();
        else addTranscript("patch_repair_slot", Map.of("accepted", true, "impact", change.impact()));
        if (!generation && change.impact().interfaceChanged()) unlockRootViewAfterInterfaceChange();
        if (inspection.valid() && finalChange && forcedArtifact.isEmpty()) status = Status.COMPLETE;
    }

    private void completeDealUiRepair(deal.compiler.CompilerProtocol.RepairWorkspaceResult result) {
        var change = (UiCompilerWorkspace.UiChangeResult) result.change();
        dealUi = result.source();
        boolean finalChange = repairFinal;
        clearRepairWorkspace();
        dealUiSemanticRepairs = 0;
        forcedArtifact = "";
        inspection = CanonicalCompiler.compileCanonicalApp(deal, dealUi, pack, packSpecifier);
        resetSurface();
        addTranscript("patch_repair_slot", Map.of("accepted", true, "impact", change.impact()));
        if (inspection.valid() && finalChange) status = Status.COMPLETE;
    }

    private void clearRepairWorkspace() {
        repairWorkspace = null;
        repairArtifact = "";
        repairFinal = false;
        repairReplacesAppState = false;
        repairReplacesInitialState = false;
        repairScopes = List.of();
        repairDiagnostics = List.of();
        clearRejectedAttempt();
    }

    private Map<String, String> agentRepairPayload(RepairSlot slot) {
        Map<String, String> result = new LinkedHashMap<>(slot.payload());
        if (result.containsKey("newParentId")) {
            result.put("newParentId", aliasesById.getOrDefault(result.get("newParentId"), "unavailable"));
        }
        return Map.copyOf(result);
    }

    private String repairFieldDescription(RepairSlot slot, String field) {
        String contract = slot.diagnostics().stream()
                .map(value -> value.expected().isBlank()
                        ? value.message()
                        : value.message() + "; required: " + value.expected() + "; rejected: " + value.actual())
                .distinct()
                .collect(java.util.stream.Collectors.joining(" | "));
        boolean numericStringMix = slot.diagnostics().stream()
                .anyMatch(value -> value.code().equals("E3010"));
        return "Complete replacement for field " + field + " of " + slot.operation()
                + " on compiler target " + alias(slot.targetId())
                + ". It must differ from the rejected payload"
                + (numericStringMix
                        ? ". Do not concatenate string and numeric values; preserve numeric state for typed UI formatting"
                        : "")
                + (contract.isBlank() ? "." : ". " + contract);
    }

    private void reject(
            String tool,
            List<StructuredDiagnostic> diagnostics,
            String artifact,
            Object attemptedOperations) {
        rejectedAttemptFingerprint = DealCompilerWorkspace.digest(
                artifact + "\u0000" + CompilerProtocolJson.encode(attemptedOperations));
        rejectedPayloads = rejectedPayloads(attemptedOperations);
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

    private boolean rejectRepeatedAttempt(String artifact, Object attemptedOperations) {
        if (rejectedAttemptFingerprint.isEmpty()) return false;
        String fingerprint = DealCompilerWorkspace.digest(
                artifact + "\u0000" + CompilerProtocolJson.encode(attemptedOperations));
        if (!fingerprint.equals(rejectedAttemptFingerprint)) return false;
        addTranscript("compiler_no_progress", Map.of(
                "artifact", artifact,
                "rejectedCandidateFingerprint", fingerprint,
                "instruction", "The operation is byte-identical to the rejected candidate; change only the scoped payload."));
        return true;
    }

    private void clearRejectedAttempt() {
        rejectedPayloads = Map.of();
        rejectedAttemptFingerprint = "";
    }

    private Map<String, String> rejectedPayloads(Object attemptedOperations) {
        Map<String, String> result = new LinkedHashMap<>();
        if (attemptedOperations instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof DealCompilerWorkspace.Operation operation) {
                    switch (operation) {
                        case DealCompilerWorkspace.AddDeclaration item -> result.put(
                                payloadKey(operationName(item), item.targetId(), "declaration"), item.declaration());
                        case DealCompilerWorkspace.ReplaceDeclaration item -> result.put(
                                payloadKey(operationName(item), item.targetId(), "declaration"), item.declaration());
                        case DealCompilerWorkspace.ReplaceFunctionBody item -> result.put(
                                payloadKey(operationName(item), item.targetId(), "body"), item.body());
                        case DealCompilerWorkspace.ReplaceBlockBody item -> result.put(
                                payloadKey(operationName(item), item.targetId(), "body"), item.body());
                        case DealCompilerWorkspace.RemoveDeclaration ignored -> { }
                    }
                } else if (value instanceof UiCompilerWorkspace.Operation operation) {
                    switch (operation) {
                        case UiCompilerWorkspace.AddView item -> result.put(
                                payloadKey(operationName(item), item.targetId(), "source"), item.source());
                        case UiCompilerWorkspace.ReplaceViewBody item -> result.put(
                                payloadKey(operationName(item), item.targetId(), "body"), item.body());
                        case UiCompilerWorkspace.ReplaceSubtree item -> result.put(
                                payloadKey(operationName(item), item.targetId(), "source"), item.source());
                        case UiCompilerWorkspace.InsertChild item -> result.put(
                                payloadKey(operationName(item), item.targetId(), "source"), item.source());
                        case UiCompilerWorkspace.SetProperty item -> result.put(
                                payloadKey(operationName(item), item.targetId(), "expression"), item.expression());
                        default -> { }
                    }
                }
            }
        }
        return Map.copyOf(result);
    }

    private Map<String, Object> editableStringSchema(
            String description, String operation, SemanticId target, String field) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        String rejected = rejectedPayloads.get(payloadKey(operation, target, field));
        schema.put("description", rejected == null
                ? description
                : description + ". Must differ from the compiler-rejected previous value");
        if (rejected != null) schema.put("not", Map.of("const", rejected));
        return Map.copyOf(schema);
    }

    private static String payloadKey(String operation, SemanticId target, String field) {
        return operation + ":" + target.value() + ":" + field;
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

    private void artifactUnchanged(CanonicalJson.Obj arguments) {
        String artifact = string(arguments, "artifact");
        if (!artifact.equals(forcedArtifact) || queriedAliases.isEmpty()) {
            throw new IllegalArgumentException("artifact_unchanged requires an inspected active artifact");
        }
        List<String> evidence = stringArray(arguments, "evidenceTargets");
        if (evidence.stream().anyMatch(value -> !queriedAliases.contains(value))) {
            throw new IllegalArgumentException("unchanged evidence must come from the inspected dependency cone");
        }
        unchangedArtifacts.add(artifact);
        addTranscript("artifact_unchanged", Map.of(
                "artifact", artifact,
                "evidenceTargets", evidence,
                "reason", string(arguments, "reason")));
        forcedArtifact = "";
        resetSurface();
        boolean allInspected = unchangedArtifacts.contains("deal")
                && (inspection.dealUi() == null || unchangedArtifacts.contains("dealui"));
        if (allInspected) status = Status.COMPLETE;
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
            SemanticId target = operationTarget(operation, name, dealGrants);
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
            SemanticId target = operationTarget(operation, name, dealUiGrants);
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

    private static String operationName(UiCompilerWorkspace.Operation operation) {
        return switch (operation) {
            case UiCompilerWorkspace.AddView ignored -> UiCompilerWorkspace.ADD_VIEW;
            case UiCompilerWorkspace.RemoveView ignored -> UiCompilerWorkspace.REMOVE_VIEW;
            case UiCompilerWorkspace.ReplaceViewBody ignored -> UiCompilerWorkspace.REPLACE_VIEW_BODY;
            case UiCompilerWorkspace.ReplaceSubtree ignored -> UiCompilerWorkspace.REPLACE_SUBTREE;
            case UiCompilerWorkspace.InsertChild ignored -> UiCompilerWorkspace.INSERT_CHILD;
            case UiCompilerWorkspace.RemoveNode ignored -> UiCompilerWorkspace.REMOVE_NODE;
            case UiCompilerWorkspace.MoveNode ignored -> UiCompilerWorkspace.MOVE_NODE;
            case UiCompilerWorkspace.SetProperty ignored -> UiCompilerWorkspace.SET_PROPERTY;
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

    private SemanticId operationTarget(
            CanonicalJson.Obj operation,
            String operationName,
            Map<String, OperationDescriptor> grants) {
        CanonicalJson.Value explicit = operation.entries().stream()
                .filter(entry -> entry.key().equals("target"))
                .map(CanonicalJson.Entry::value)
                .findFirst().orElse(null);
        if (explicit instanceof CanonicalJson.Str value) return resolveAlias(value.value(), null);
        List<SemanticId> candidates = grants.values().stream()
                .filter(grant -> grant.operation().equals(operationName))
                .filter(grant -> grants != dealGrants || !generation || allowedGreenfieldDealOperation(grant))
                .filter(grant -> repairScopes.isEmpty() || repairScopes.stream().anyMatch(scope ->
                        scope.operation().equals(operationName) && scope.ownerId().equals(grant.targetId())))
                .map(OperationDescriptor::targetId)
                .distinct()
                .toList();
        if (candidates.size() != 1) {
            throw new IllegalArgumentException(
                    "Operation " + operationName + " requires an explicit compiler target");
        }
        return candidates.get(0);
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
        return transactionTool(name, description, variants, false);
    }

    private static Map<String, Object> transactionTool(
            String name,
            String description,
            List<Map<String, Object>> variants,
            boolean finalRequired) {
        List<Map<String, Object>> compactVariants = omitUnambiguousTargets(variants);
        return tool(name, description, objectSchema(Map.of(
                "operations", Map.of("type", "array", "minItems", 1,
                        "items", Map.of("anyOf", compactVariants)),
                "final", finalRequired
                        ? Map.of("type", "boolean", "const", true)
                        : Map.of("type", "boolean"))));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> omitUnambiguousTargets(List<Map<String, Object>> variants) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> variant : variants) {
            Map<String, Object> properties = (Map<String, Object>) variant.get("properties");
            Map<String, Object> operation = (Map<String, Object>) properties.get("operation");
            counts.merge((String) operation.get("const"), 1, Integer::sum);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> variant : variants) {
            Map<String, Object> properties = (Map<String, Object>) variant.get("properties");
            Map<String, Object> operation = (Map<String, Object>) properties.get("operation");
            if (counts.get((String) operation.get("const")) != 1 || !properties.containsKey("target")) {
                result.add(variant);
                continue;
            }
            Map<String, Object> compactProperties = new LinkedHashMap<>(properties);
            compactProperties.remove("target");
            Map<String, Object> compact = new LinkedHashMap<>(variant);
            compact.put("properties", Map.copyOf(compactProperties));
            compact.put("required", List.copyOf(compactProperties.keySet()));
            result.add(Map.copyOf(compact));
        }
        return List.copyOf(result);
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

    private static List<String> stringArray(CanonicalJson.Obj object, String name) {
        CanonicalJson.Arr values = CompilerProtocolJson.requireArray(field(object, name), name);
        return values.items().stream().map(value -> {
            if (value instanceof CanonicalJson.Str text) return text.value();
            throw new IllegalArgumentException("Protocol field '" + name + "' must contain strings");
        }).toList();
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
