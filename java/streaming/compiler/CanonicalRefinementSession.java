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
    private boolean constructionApi;
    private final Set<String> uiConstructionTools = new LinkedHashSet<>();

    /** Production agents use source-free construction. Text-edit API remains an internal compatibility adapter. */
    public CanonicalRefinementSession useConstructionApi() {
        if (rounds != 0) throw new IllegalStateException("Select construction API before issuing requests");
        constructionApi = true;
        return this;
    }
    private static final String AGENT_SURFACE_VERSION = "agent-surface-v10";
    private static final int MAX_FOUNDATION_RECORD_DECLARATIONS = 8;
    private static final int MAX_SUPPORTING_DECLARATIONS_PER_BATCH = 2;
    private static final int MAX_ACTION_HANDLERS_PER_BATCH = 4;
    private static final int MAX_BOOTSTRAP_DECLARATION_CHARS = 4_000;
    private static final int MAX_INITIAL_STATE_BODY_CHARS = 6_000;
    private List<String> hostCapabilities() {
        return inspection.componentPack().components().stream()
                .flatMap(component -> component.capabilities().stream())
                .filter(capability -> capability.startsWith("host."))
                .map(capability -> capability.substring(5)).distinct().sorted().toList();
    }

    private boolean hostContractReady() {
        return CanonicalCompiler.inspectHostRequirements(
                inspection.deal().appInterface(), inspection.componentPack()).stream()
                .allMatch(CanonicalCompiler.HostRequirement::ready);
    }

    private Map<String, Object> hostCapabilityItemSchema() {
        return hostCapabilities().isEmpty() ? Map.of("type", "string") : enumSchema(hostCapabilities());
    }
    private static final String REFINEMENT_SYSTEM_PROMPT = """
            You modernize one canonical DEAL application through a compact compiler agent surface.
            DEAL owns state and behavior. Deal UI owns declarative presentation. Inspect a short
            change through the artifact-specific inspect tool, then submit one small atomic transaction using only operations unlocked by
            the compiler-owned dependency cone. Never regenerate an unrelated unit. Compiler diagnostics and writable repair
            scopes are authoritative. Use no scenario templates. Set final to false only when another
            behavior or visual transaction is required. The surface has distinct inspect and edit
            phases. In the inspect phase call inspect_deal_change or inspect_deal_ui_change once with every required semantic anchor
            and operation kind. In the edit phase call the scoped write tool directly. For a UI subtree,
            query_ui_contracts may first request missing component and action contracts without changing
            the writable scope. Use lexicalBindings and bindingTypes for enclosing ForEach items.
            Emit exactly one tool call per response; batch dependent edits inside that tool.
            For Deal UI select exactly one node. To change several related descendants, select
            their smallest common ancestor and replace that subtree, preserving unrelated children.
            replaceFunctionBody and replaceBlockBody accept only statements inside the existing
            braces. Never include a function signature, declaration, or the outer braces in body.
            Inspecting a DEAL symbol or body also unlocks adding a new sibling declaration when the
            requested change needs a new record, action, helper or handler.
            Before setting final=true, compare every concrete requirement in the current instruction
            against the resulting compiler inspection. A successful compile proves structural validity,
            not request completion. Every requested behavior must be represented by reachable actions,
            handlers, state transitions or capabilities, and every requested presentation change by
            reachable UI nodes or theme properties. Never satisfy a missing requirement by changing an
            unrelated writable unit. If the current dependency cone is insufficient, finish the current
            transaction with final=false and inspect the next required cone.
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
            Select exactly one compiler-owned UI node, then use replace_deal_ui_subtree and preserve
            every sibling outside that node. The write phase exposes only that subtree, its immediate
            hierarchy and compatible bindings. Never request property, child, view or document edits.
            """;
    private static final String DEAL_GENERATION_SYSTEM_PROMPT = """
            Create the behavior of one complete canonical DEAL application through the compact
            compiler surface. Submit bootstrap and bounded behavior tools directly from the current
            compiler-owned stage; do not query declarations that the stage already publishes. Submit one
            cohesive atomic transaction. Emit exactly one write tool call; read-only queries may
            use a separate response. Set final=true only when behavior is complete. For a complex application,
            set final=false and continue with another small transaction.
            Never return prose.
            Obey generationStage. In bootstrap, replace AppState and initialState and add any
            field-only nominal record types referenced by AppState in the same atomic ChangeSet.
            Inspect the module together with both bootstrap units before that write. Every collection
            rendered as repeated UI must contain nominal items with a stable int or string id/key;
            shape nested visual data as keyed record collections, not primitive nested arrays. In
            initialState, return one complete AppState value on every path. The function has no state
            parameter, so never assign through state there. Give every empty local collection an explicit
            element type, for example `let items: Item[] = [];`, before appending with its length.
            Keep bootstrap compact. Represent recurring schedules as rules with interval and time-window
            fields; do not materialize every future occurrence in initialState. Derive occurrences in handlers.
            In app-state or initial-state, complete only the named missing bootstrap unit. In declarations, both
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
            Use int for integral values and defaults; a number default requires 0.0.
            Arithmetic operands must have the same numeric type: int * number and number + int
            are invalid. Multiplying an int by 1.0 is NOT a conversion. Keep integral counters,
            indices, elapsed milliseconds and values consumed by int UI properties as int.
            For number computations use number operands throughout; do not assume JavaScript
            coercion or invent conversion functions. consumerContract lists downstream property types.
            During repair, change the expressions causing each diagnostic, not unrelated setup.
            Construct
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
            AppInterface. The compiler already exposes the bootstrap root view; replace its body directly in one atomic UI
            transaction and mark it final. Emit exactly one write tool call; read-only queries may
            use a separate response. The replacement body contains only statements inside the existing view:
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
            Event arguments come from the event bindingRoot (payload). For a scalar payload use
            payload directly; for a record use only the fields declared in events.payloadTypes.
            An action field name does not rename a payload field. Bind that field to the matching
            typed payload path. PointerSurface must wrap the content that receives its gestures;
            it is not a separate empty control below the visual surface.
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
    private UiCompilerWorkspace.UiEditSurface uiEditSurface;
    private List<Map<String, Object>> issuedTools = List.of();
    private Map<String, Object> requestedUiContracts = Map.of();
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
    private int repairNoProgressAttempts;
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
        if (constructionApi) {
            uiConstructionTools.clear();
            tools = tools.stream().map(tool -> {
                String name = (String) tool.get("name");
                boolean ui = repairWorkspace != null ? repairArtifact.equals("dealui")
                        : forcedArtifact.equals("dealui") || name.contains("_ui_");
                var adapted = ConstructionSurface.tool(tool, ui);
                if (ui) uiConstructionTools.add((String) adapted.get("name"));
                return adapted;
            }).toList();
        }
        issuedTools = List.copyOf(tools);
        String encodedTools = CompilerProtocolJson.encode(tools);
        String surfaceDigest = DealCompilerWorkspace.digest(input + "\u0000" + encodedTools);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("status", "request");
        request.put("protocolVersion", CompilerProtocol.VERSION);
        request.put("surfaceVersion", AGENT_SURFACE_VERSION);
        request.put("constructionProtocol", constructionApi ? "compiler-construction-v1" : "source-edit-compatibility");
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
        if (constructionApi) return ConstructionSurface.INSTRUCTIONS;
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
        CanonicalJson.Obj arguments = CompilerProtocolJson.requireObject(
                decodeToolJson(argumentsJson), "tool arguments");
        validateIssuedCall(name, arguments);
        rounds++;
        if (!isReadOnlyQuery(name)) writeRounds++;
        issuedTools = List.of();
        acceptToolCall(name, arguments);
        return status == Status.REQUEST ? nextRequestJson() : resultJson();
    }

    /** Validates transport and current grants without consuming a round or changing the graph. */
    public String validateToolCallsJson(String callsJson) {
        try {
            checkedToolCalls(callsJson);
            return CompilerProtocolJson.encode(Map.of("valid", true));
        } catch (IllegalArgumentException failure) {
            return CompilerProtocolJson.encode(Map.of("valid", false, "error", failure.getMessage()));
        }
    }

    private static CanonicalJson.Value decodeToolJson(String json) {
        try { return CompilerProtocolJson.decode(json); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("Invalid tool JSON: " + failure.getMessage(), failure); }
    }

    private List<CanonicalJson.Obj> checkedToolCalls(String callsJson) {
        if (status != Status.REQUEST) throw new IllegalStateException("Refinement session is not requesting a tool");
        CanonicalJson.Arr calls = CompilerProtocolJson.requireArray(decodeToolJson(callsJson), "tool calls");
        List<CanonicalJson.Obj> values = calls.items().stream()
                .map(value -> CompilerProtocolJson.requireObject(value, "tool call")).toList();
        if (values.size() != 1) throw new IllegalArgumentException("The current agent surface requires exactly one tool call");
        for (CanonicalJson.Obj value : values) validateIssuedCall(string(value, "name"),
                CompilerProtocolJson.requireObject(field(value, "arguments"), "tool arguments"));
        return values;
    }

    /** Accepts exactly one currently granted tool per provider turn. */
    public String acceptToolCallsJson(String callsJson) {
        List<CanonicalJson.Obj> values = checkedToolCalls(callsJson);
        rounds++;
        if (values.stream().anyMatch(value -> !isReadOnlyQuery(string(value, "name")))) writeRounds++;
        issuedTools = List.of();
        for (CanonicalJson.Obj value : values) {
            acceptToolCall(
                    string(value, "name"),
                    CompilerProtocolJson.requireObject(field(value, "arguments"), "tool arguments"));
        }
        return status == Status.REQUEST ? nextRequestJson() : resultJson();
    }

    private void validateIssuedCall(String name, CanonicalJson.Obj arguments) {
        Map<String, Object> tool = issuedTools.stream().filter(value -> name.equals(value.get("name")))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Tool is not granted by the current surface: " + name));
        validateSchema(arguments, (Map<?, ?>) tool.get("parameters"));
        if (constructionApi && name.startsWith("construct_"))
            ConstructionSurface.lower(arguments, uiConstructionTools.contains(name),
                    repairWorkspace == null ? "" : activeRejectedRepairSlot().operation());
    }

    private static void validateSchema(CanonicalJson.Value value, Map<?, ?> schema) {
        if (schema.containsKey("const") && !CompilerProtocolJson.encode(value).equals(CompilerProtocolJson.encode(schema.get("const"))))
            throw new IllegalArgumentException("Tool argument violates const");
        if (schema.get("enum") instanceof List<?> options && options.stream().noneMatch(option ->
                CompilerProtocolJson.encode(value).equals(CompilerProtocolJson.encode(option))))
            throw new IllegalArgumentException("Tool argument is outside the granted enum");
        Object variants = schema.containsKey("oneOf") ? schema.get("oneOf") : schema.get("anyOf");
        if (variants instanceof List<?> alternatives) {
            int matches = 0;
            for (Object alternative : alternatives) {
                try { validateSchema(value, (Map<?, ?>) alternative); matches++; }
                catch (IllegalArgumentException ignored) { }
            }
            if (matches == 0 || schema.containsKey("oneOf") && matches != 1)
                throw new IllegalArgumentException("Tool argument does not match a granted operation");
        }
        Object type = schema.get("type");
        if ("object".equals(type)) {
            CanonicalJson.Obj object = CompilerProtocolJson.requireObject(value, "tool object");
            Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
            if (schema.get("required") instanceof List<?> required) for (Object key : required)
                if (object.entries().stream().noneMatch(entry -> entry.key().equals(key)))
                    throw new IllegalArgumentException("Missing tool argument: " + key);
            for (var entry : object.entries()) {
                if (!properties.containsKey(entry.key())) throw new IllegalArgumentException("Unexpected tool argument: " + entry.key());
                validateSchema(entry.value(), (Map<?, ?>) properties.get(entry.key()));
            }
        } else if ("array".equals(type)) {
            CanonicalJson.Arr array = CompilerProtocolJson.requireArray(value, "tool array");
            if (schema.get("minItems") instanceof Number min && array.items().size() < min.intValue()
                    || schema.get("maxItems") instanceof Number max && array.items().size() > max.intValue())
                throw new IllegalArgumentException("Tool array size is outside the grant");
            if (Boolean.TRUE.equals(schema.get("uniqueItems")) && array.items().stream().distinct().count() != array.items().size())
                throw new IllegalArgumentException("Tool array requires unique values");
            for (var item : array.items()) validateSchema(item, (Map<?, ?>) schema.get("items"));
        } else if ("string".equals(type)) {
            if (!(value instanceof CanonicalJson.Str string)) throw new IllegalArgumentException("Expected tool string");
            if (schema.get("maxLength") instanceof Number max && string.value().length() > max.intValue())
                throw new IllegalArgumentException("Tool string exceeds the grant");
        } else if ("boolean".equals(type) && !(value instanceof CanonicalJson.Bool)) {
            throw new IllegalArgumentException("Expected tool boolean");
        } else if ("integer".equals(type)) {
            if (!(value instanceof CanonicalJson.Int number)) throw new IllegalArgumentException("Expected tool integer");
            if (schema.get("minimum") instanceof Number min && number.value() < min.intValue())
                throw new IllegalArgumentException("Tool integer is below the grant");
        }
    }

    private void acceptToolCall(String name, CanonicalJson.Obj arguments) {
        if (constructionApi && name.startsWith("construct_")) {
            arguments = ConstructionSurface.lower(arguments, uiConstructionTools.contains(name),
                    repairWorkspace == null ? "" : activeRejectedRepairSlot().operation());
            name = name.substring("construct_".length());
        }
        switch (name) {
            case "query_deal_module" -> queryDealModule(string(arguments, "target"));
            case "query_deal_symbol" -> queryDealSymbol(string(arguments, "target"));
            case "query_deal_node" -> queryDealNode(string(arguments, "target"));
            case "query_deal_ui_view" -> queryDealUiView(string(arguments, "target"));
            case "query_deal_ui_document" -> queryDealUiDocument(string(arguments, "target"));
            case "query_deal_ui_node" -> queryDealUiNode(string(arguments, "target"));
            case "inspect_deal_change" -> inspectChange("deal", arguments);
            case "inspect_deal_ui_change" -> inspectChange("dealui", arguments);
            case "query_ui_contracts" -> {
                List<String> components = stringArray(arguments, "components");
                List<String> actions = stringArray(arguments, "actions");
                requestedUiContracts = Map.of(
                        "components", inspection.componentPack().components().stream().filter(value -> components.contains(value.name())).toList(),
                        "actions", inspection.deal().appInterface().actions().stream().filter(value -> actions.contains(value.name()))
                                .map(value -> Map.of("name", value.name(), "fields", value.fields())).toList());
            }
            case "apply_deal_foundation" -> applyDealFoundation(arguments);
            case "append_deal_behavior" -> appendDealBehavior(arguments);
            case "evolve_deal_state" -> evolveDealState(arguments);
            case "add_deal_action_handler" -> addDealActionHandler(arguments);
            case "add_deal_supporting_declaration" -> addDealSupportingDeclaration(arguments);
            case "apply_deal_changes" -> applyDeal(arguments);
            case "finish_deal" -> finishDeal(arguments);
            case "replace_deal_ui_view" -> replaceDealUiView(arguments);
            case "replace_deal_ui_subtree" -> replaceDealUiSubtree(arguments);
            case "apply_deal_ui_changes" -> applyDealUi(arguments);
            case "patch_repair_slot" -> patchRepairSlot(arguments);
            case "drop_repair_slot" -> dropRepairSlot(arguments);
            case "artifact_unchanged" -> artifactUnchanged(arguments);
            case "unchanged" -> unchanged();
            default -> throw new IllegalArgumentException("Unsupported streaming-compiler tool: " + name);
        }
    }

    private static boolean isReadOnlyQuery(String name) {
        return name.equals("query_deal_module")
                || name.equals("query_ui_contracts")
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
        result.put("surfaceVersion", AGENT_SURFACE_VERSION);
        result.put("protocolMode", "v2-with-v1-shadow");
        result.put("inspection", inspection);
        result.put("transcript", transcript);
        return CompilerProtocolJson.encode(result);
    }

    private String input() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("request", instruction);
        if (stateEvolutionAvailable()) {
            context.put("stateProducers", CanonicalCompiler.queryRootStateProducers(deal).stream()
                    .map(this::compactDealSlice).toList());
        }
        if (uiEditSurface != null) {
            context.put("uiEditSurface", compactUiEditSurface(uiEditSurface));
            if (!requestedUiContracts.isEmpty()) context.put("requestedContracts", requestedUiContracts);
        } else {
            context.put("deal", compactDealIndex());
            if (inspection.dealUi() != null) {
                context.put("dealUi", compactDealUiIndex());
            }
        }
        context.put("packVersion", inspection.packVersion());
        context.put("packDigest", inspection.packDigest());
        if (generation && forcedArtifact.equals("deal")) {
            context.put("hostRequirements", CanonicalCompiler.inspectHostRequirements(
                    inspection.deal().appInterface(), inspection.componentPack()));
            context.put("availableHostComponents", inspection.componentPack().components().stream()
                    .filter(component -> component.capabilities().stream().anyMatch(value -> value.startsWith("host.")))
                    .toList());
            context.put("consumerContract", inspection.componentPack().components().stream()
                    .filter(component -> component.properties().stream().anyMatch(property ->
                            property.type().equals("int") || property.type().equals("number"))
                            || !component.events().isEmpty())
                    .map(component -> Map.of("component", component.name(), "numericProperties",
                            component.properties().stream().filter(property -> property.type().equals("int")
                                    || property.type().equals("number")).toList(), "events", component.events())).toList());
        }
        if (generation && forcedArtifact.equals("dealui")) {
            context.put("componentPack", compactComponentPack());
        }
        if (uiEditSurface == null && repairWorkspace == null) context.put("previousToolResults", agentTranscript());
        if (repairWorkspace != null && !transcript.isEmpty()
                && transcript.getLast().get("tool").equals("compiler_no_progress")) {
            context.put("previousToolResults", List.of(transcript.getLast()));
        }
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
                    "instruction", "Change the rejected operation according to its diagnostics. Never resubmit the previous payload.",
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
        if (repairWorkspace != null) {
            List<Map<String, Object>> repairTools = new ArrayList<>();
            repairTools.add(repairSlotTool());
            RepairSlot active = activeRejectedRepairSlot();
            if (repairArtifact.equals("deal")
                    && active.operation().equals(DealCompilerWorkspace.ADD_DECLARATION)) {
                repairTools.add(tool(
                        "drop_repair_slot",
                        "Drop this rejected addDeclaration when it is unnecessary. Preserved sibling slots remain immutable and will still be validated.",
                        objectSchema(Map.of(
                                "slot", constantString(active.slotId()),
                                "reason", Map.of("type", "string", "description",
                                        "Why this declaration is unnecessary for the requested application")))));
            }
            return List.copyOf(repairTools);
        }
        if (generation && generationStage().equals("bootstrap")) unlockGreenfieldFoundation();
        if (generation && generationStage().equals("ui")) unlockGreenfieldRootView();
        List<Map<String, Object>> result = new ArrayList<>();
        boolean repairing = !repairScopes.isEmpty();
        boolean writeUnlocked = !dealGrants.isEmpty() || !dealUiGrants.isEmpty();
        boolean generationNeedsInspect = generation
                && (generationStage().equals("app-state") || generationStage().equals("initial-state"));
        if (!repairing && !writeUnlocked && (!generation || generationNeedsInspect)) {
            result.addAll(inspectChangeTools());
        }
        List<Map<String, Object>> dealOperations = repairMustFinishDeal ? List.of() : dealOperationSchemas();
        if (generation && generationStage().equals("bootstrap") && foundationReady()) {
            result.add(tool("apply_deal_foundation",
                    "Atomically declare supporting record types and replace the two bootstrap units.",
                    objectSchema(Map.of(
                            "supportingDeclarations", Map.of(
                                    "type", "array", "maxItems", MAX_FOUNDATION_RECORD_DECLARATIONS,
                                    "items", Map.of("type", "string", "description",
                                            "One complete unique field-only class declaration. Use int, not number, for integral fields and defaults. "
                                                    + "A record stored in an AppState array must include a stable unique id: int or key: string field for ForEach")),
                            "appStateDeclaration", Map.of("type", "string", "maxLength", MAX_BOOTSTRAP_DECLARATION_CHARS, "description",
                                    "Complete export class AppState declaration. Use int, not number, for integral fields and defaults"),
                            "capabilities", Map.of(
                                    "type", "array", "uniqueItems", true, "maxItems", hostCapabilities().size(),
                                    "items", hostCapabilityItemSchema(),
                                    "description", "Complete set of host capabilities required by this application"),
                            "initialStateBody", Map.of("type", "string", "maxLength", MAX_INITIAL_STATE_BODY_CHARS, "description",
                                    "Statements only; omit signature and outer braces. Return one complete AppState value on every path; initialState has no state parameter. "
                                            + "Use int locals for integer literals and loops. Only [] array literals are supported, and every empty local array must have an explicit element type, for example `let items: Item[] = [];`. "
                                            + "Store recurring schedule rules compactly; never enumerate every future occurrence")))));
        } else if (generation && generationStage().equals("declarations") && !repairMustFinishDeal) {
            result.add(dealBehaviorTool());
        } else if (stateEvolutionAvailable()) {
            result.add(dealStateEvolutionTool());
        } else if (!dealOperations.isEmpty()) {
            result.add(transactionTool("apply_deal_changes", "Apply one atomic DEAL ChangeSet.", dealOperations));
        }
        if (!generation && dealGrants.values().stream().anyMatch(value ->
                value.operation().equals(DealCompilerWorkspace.ADD_DECLARATION))) {
            result.add(dealActionHandlerTool());
            result.add(dealSupportingDeclarationTool());
        }
        if (generation && generationStage().equals("declarations") && hostContractReady()
                && !inspection.deal().appInterface().actions().isEmpty()) {
            List<String> actionNames = inspection.deal().appInterface().actions().stream()
                    .map(CompilerProtocol.TypeSnapshot::name)
                    .sorted()
                    .toList();
            result.add(tool("finish_deal",
                    repairMustFinishDeal
                            ? "All rejected declarations already exist. Drop the duplicate ChangeSet and transition to Deal UI."
                            : "Current checked DEAL behavior satisfies every requested interaction. Transition to Deal UI without changing source.",
                    objectSchema(Map.of(
                            "coveredActions", Map.of(
                                    "type", "array",
                                    "minItems", actionNames.size(),
                                    "maxItems", actionNames.size(),
                                    "uniqueItems", true,
                                    "items", enumSchema(actionNames),
                                    "description", "Every currently accepted action. Re-read the original request before claiming this set is complete."),
                            "reason", Map.of(
                                    "type", "string",
                                    "description", "Map the concrete requested interactions to the accepted actions; if any requirement lacks evidence, add it instead of finishing")))));
        }
        List<Map<String, Object>> uiOperations = dealUiOperationSchemas();
        List<OperationDescriptor> replaceViewGrants = dealUiGrants.values().stream()
                .filter(value -> value.operation().equals(UiCompilerWorkspace.REPLACE_VIEW_BODY))
                .toList();
        List<OperationDescriptor> replaceSubtreeGrants = dealUiGrants.values().stream()
                .filter(value -> value.operation().equals(UiCompilerWorkspace.REPLACE_SUBTREE))
                .toList();
        if (!replaceViewGrants.isEmpty() && !generation) {
            result.add(replaceDealUiViewTool(replaceViewGrants));
        }
        if (!replaceSubtreeGrants.isEmpty() && !generation) {
            result.add(replaceDealUiSubtreeTool(replaceSubtreeGrants));
            if (uiEditSurface != null && requestedUiContracts.isEmpty()) result.add(tool(
                    "query_ui_contracts", "Read missing component or action contracts before replacing the selected subtree. Does not grant other writes.",
                    objectSchema(Map.of(
                            "components", Map.of("type", "array", "maxItems", 8, "uniqueItems", true, "items", enumSchema(
                                    inspection.componentPack().components().stream().map(CanonicalCompiler.ComponentSnapshot::name).toList())),
                            "actions", Map.of("type", "array", "maxItems", 8, "uniqueItems", true, "items", enumSchema(
                                    inspection.deal().appInterface().actions().stream().map(CompilerProtocol.TypeSnapshot::name).toList()))))));
        }
        if (!uiOperations.isEmpty()) {
            result.add(transactionTool(
                    "apply_deal_ui_changes", "Apply one atomic Deal UI ChangeSet.", uiOperations, generation));
        }
        if (!generation && !repairing && uiEditSurface == null
                && !forcedArtifact.isEmpty() && writeUnlocked) {
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
        Map<String, Object> actionHandler = actionHandlerSchema();
        return tool(
                "append_deal_behavior",
                "Bootstrap is committed. Atomically append complete action-handler pairs and optional supporting helpers; never emit an action without its handler. Completion is a separate compiler-owned finish_deal step after this bounded batch.",
                objectSchema(Map.of(
                        "supportingDeclarations", Map.of(
                                "type", "array", "maxItems", MAX_SUPPORTING_DECLARATIONS_PER_BATCH,
                                "items", Map.of("type", "string", "description",
                                        "Exactly one complete unique helper function or field-only record class")),
                        "actionHandlers", Map.of(
                                "type", "array", "minItems", 1,
                                "maxItems", MAX_ACTION_HANDLERS_PER_BATCH,
                                "items", actionHandler),
                        "final", Map.of(
                                "type", "boolean",
                                "const", false,
                                "description", "Always false. The next compact surface audits request coverage before finish_deal"))));
    }

    private Map<String, Object> actionHandlerSchema() {
        return objectSchema(Map.of(
                "actionDeclaration", Map.of(
                        "type", "string",
                        "description", "Exactly one complete unique field-only export class Action declaration"),
                "handlerDeclaration", Map.of(
                        "type", "string",
                        "description", "Exactly one complete export function for that Action. Put // @ui-update on the line immediately before export function")));
    }

    private Map<String, Object> dealStateEvolutionTool() {
        Map<String, Object> producerBodies = new LinkedHashMap<>();
        for (SemanticSlice slice : CanonicalCompiler.queryRootStateProducers(deal)) {
            var operation = slice.allowedOperations().stream()
                    .filter(value -> value.operation().equals(DealCompilerWorkspace.REPLACE_FUNCTION_BODY))
                    .findFirst().orElseThrow();
            producerBodies.put(alias(operation.targetId()), Map.of("type", "string", "description",
                    "Complete body reviewed against the new state schema. Preserve all unrelated fields explicitly, including newly added fields. Return unchanged source only when no adjustment is needed."));
        }
        return tool(
                "evolve_deal_state",
                "Atomically complete one root-state schema evolution with its initializer, directly dependent records and up to four cohesive action-handler pairs. A successful transaction finishes DEAL for this refinement and advances to affected Deal UI.",
                objectSchema(Map.of(
                        "supportingDeclarations", Map.of(
                                "type", "array", "maxItems", MAX_FOUNDATION_RECORD_DECLARATIONS,
                                "items", Map.of("type", "string", "description",
                                        "One complete new field-only record class or helper required by the evolved state")),
                        "appStateDeclaration", Map.of(
                                "type", "string", "maxLength", MAX_BOOTSTRAP_DECLARATION_CHARS,
                                "description", "Complete replacement export class AppState declaration. Preserve every existing field unless the instruction explicitly removes it"),
                        "initialStateBody", Map.of(
                                "type", "string", "maxLength", MAX_INITIAL_STATE_BODY_CHARS,
                                "description", "Complete replacement statements for initialState. Return every field in the evolved AppState and preserve existing initialized behavior"),
                        "stateProducerBodies", objectSchema(producerBodies),
                        "actionHandlers", Map.of(
                                "maxItems", MAX_ACTION_HANDLERS_PER_BATCH,
                                "type", "array", "items", actionHandlerSchema()),
                        "capabilities", Map.of(
                                "type", "array",
                                "uniqueItems", true,
                                "maxItems", hostCapabilities().size(),
                                "items", hostCapabilityItemSchema(),
                                "description", "Complete host capability set after this change. Preserve existing entries and add only capabilities required by the instruction"),
                        "final", Map.of(
                                "type", "boolean", "const", true,
                                "description", "Always true. Additional behavior belongs to a later compiler-mediated refinement"))));
    }

    private boolean stateEvolutionAvailable() {
        if (generation) return false;
        SemanticId appState = inspection.deal().symbols().stream()
                .filter(symbol -> symbol.name().equals("AppState"))
                .map(SymbolSnapshot::id).findFirst().orElse(null);
        SemanticId initialState = inspection.deal().symbols().stream()
                .filter(symbol -> symbol.name().equals("initialState"))
                .map(SymbolSnapshot::id).findFirst().orElse(null);
        SemanticId initialBody = initialState == null ? null : inspection.deal().nodes().stream()
                .filter(node -> node.ownerId().equals(initialState) && node.kind().equals("function-body"))
                .map(node -> node.id()).findFirst().orElse(null);
        return appState != null && initialBody != null
                && dealGrants.containsKey(grantKey(DealCompilerWorkspace.REPLACE_DECLARATION, appState))
                && dealGrants.containsKey(grantKey(DealCompilerWorkspace.REPLACE_FUNCTION_BODY, initialBody));
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
                        "final", Map.of(
                                "type", "boolean",
                                "description", "False when another requested DEAL change remains; true only when this action-handler pair completes behavior"))));
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

    private Map<String, Object> replaceDealUiSubtreeTool(List<OperationDescriptor> grants) {
        return tool(
                "replace_deal_ui_subtree",
                "Atomically replace exactly one inspected Deal UI node. Preserve all parent and sibling nodes outside the target.",
                objectSchema(Map.of(
                        "target", enumSchema(grants.stream().map(value -> alias(value.targetId())).toList()),
                        "source", Map.of(
                                "type", "string",
                                "description", "Exactly one complete replacement subtree rooted at the target; omit unrelated siblings"),
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
                    DealCompilerWorkspace.REPLACE_BLOCK_BODY,
                    DealCompilerWorkspace.SET_CAPABILITIES));
        } else {
            targets.addAll(aliases("U"));
            operations.add(UiCompilerWorkspace.REPLACE_SUBTREE);
        }
        return objectSchema(Map.of(
                "anchors", Map.of(
                        "type", "array", "minItems", 1, "maxItems", artifact.equals("deal") ? targets.size() : 1, "uniqueItems", true,
                        "items", enumSchema(targets)),
                "requestedOperations", Map.of(
                        "type", "array", "minItems", 1, "uniqueItems", true,
                        "items", enumSchema(operations))));
    }

    private Map<String, Object> repairSlotTool() {
        RepairSlot active = activeRejectedRepairSlot();
        Set<String> fields = new LinkedHashSet<>(active.payload().keySet());
        Map<String, Object> payloadProperties = new LinkedHashMap<>();
        fields.forEach(field -> payloadProperties.put(field, switch (field) {
            case "index" -> Map.of("type", "integer", "minimum", 0);
            case "newParentId" -> enumSchema(aliases("U"));
            default -> repairStringSchema(active, field);
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

    private RepairSlot activeRejectedRepairSlot() {
        return repairWorkspace.slots().stream()
                .filter(value -> value.status() == RepairSlotStatus.REJECTED)
                .findFirst().orElseThrow(() -> new IllegalStateException("Repair workspace has no rejected slot"));
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
                case DealCompilerWorkspace.SET_CAPABILITIES -> Map.of(
                        "capabilities", Map.of(
                                "type", "array", "uniqueItems", true,
                                "maxItems", hostCapabilities().size(),
                                "items", hostCapabilityItemSchema()));
                default -> Map.of();
            };
            addIfAllowed(operations, grant.operation(), grant.targetId(), extra);
        });
        return operations;
    }

    private boolean allowedGreenfieldDealOperation(OperationDescriptor grant) {
        if (grant.operation().equals(DealCompilerWorkspace.ADD_DECLARATION)) return true;
        if (grant.operation().equals(DealCompilerWorkspace.SET_CAPABILITIES)) return !appStateBootstrapReplaced;
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
            if (!generation && grant.operation().equals(UiCompilerWorkspace.REPLACE_SUBTREE)) return;
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
        if (!generation && artifact.equals("dealui")
                && (anchorAliases.size() != 1
                || !requestedOperations.equals(List.of(UiCompilerWorkspace.REPLACE_SUBTREE)))) {
            throw new IllegalArgumentException(
                    "Deal UI refinement requires exactly one UI node and replaceSubtree");
        }
        List<SemanticId> anchors = anchorAliases.stream()
                .map(value -> resolveAlias(value, null)).toList();
        if (artifact.equals("deal") && selectsStateEvolution(anchors)) {
            LinkedHashSet<String> cohesiveOperations = new LinkedHashSet<>(requestedOperations);
            cohesiveOperations.add(DealCompilerWorkspace.REPLACE_DECLARATION);
            cohesiveOperations.add(DealCompilerWorkspace.REPLACE_FUNCTION_BODY);
            if (anchors.contains(inspection.deal().moduleId())) {
                cohesiveOperations.add(DealCompilerWorkspace.ADD_DECLARATION);
                cohesiveOperations.add(DealCompilerWorkspace.SET_CAPABILITIES);
            }
            requestedOperations = List.copyOf(cohesiveOperations);
        }
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
        boolean localUiReplacement = artifact.equals("dealui")
                && anchors.size() == 1
                && requestedOperations.equals(List.of(UiCompilerWorkspace.REPLACE_SUBTREE));
        if (localUiReplacement) {
            uiEditSurface = CanonicalCompiler.queryDealUiEditSurface(
                    deal, dealUi, pack, packSpecifier, anchors.get(0));
        } else if (artifact.equals("dealui")) {
            uiEditSurface = null;
        }
        Map<String, Object> inspectResult = Map.of(
                "artifact", artifact,
                "coneFingerprint", change.dependencyCone().fingerprint(),
                "anchors", anchorAliases,
                "requestedOperations", requestedOperations,
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
                        .toList());
        if (!localUiReplacement) {
            addTranscript(artifact.equals("deal") ? "inspect_deal_change" : "inspect_deal_ui_change", inspectResult);
        }
        forcedArtifact = artifact;
    }

    private boolean selectsStateEvolution(List<SemanticId> anchors) {
        SemanticId appState = inspection.deal().symbols().stream()
                .filter(symbol -> symbol.name().equals("AppState"))
                .map(SymbolSnapshot::id).findFirst().orElse(null);
        SemanticId initialState = inspection.deal().symbols().stream()
                .filter(symbol -> symbol.name().equals("initialState"))
                .map(SymbolSnapshot::id).findFirst().orElse(null);
        if (appState == null || initialState == null || !anchors.contains(appState)) return false;
        return anchors.contains(initialState) || inspection.deal().nodes().stream().anyMatch(node ->
                node.ownerId().equals(initialState) && anchors.contains(node.id()));
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
        if (!sourceChanged) {
            if (finishAcceptedRevision(finalChange)) return;
            SemanticId owner = operations.get(0).targetId();
            List<RepairScope> scopes = operations.stream()
                    .map(operation -> new RepairScope(operationName(operation), operation.targetId()))
                    .distinct()
                    .toList();
            reject("apply_deal_changes", List.of(new StructuredDiagnostic(
                    "SC1002",
                    "error",
                    "The accepted transaction made no source progress; submit a changed payload or use artifact_unchanged after compiler inspection",
                    null,
                    owner,
                    "a source-changing operation",
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
                : !finalChange ? "deal"
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

    private void unlockGreenfieldRootView() {
        if (inspection.dealUi() == null || !dealUiGrants.isEmpty()) return;
        UiCompilerWorkspace.UiViewSnapshot target = inspection.dealUi().views().stream()
                .filter(UiCompilerWorkspace.UiViewSnapshot::root)
                .findFirst()
                .orElseGet(() -> inspection.dealUi().views().stream().findFirst().orElse(null));
        if (target == null) return;
        grant(dealUiGrants, CanonicalCompiler.queryDealUiView(
                deal, dealUi, pack, packSpecifier, target.id()).allowedOperations());
    }

    private void applyDealFoundation(CanonicalJson.Obj arguments) {
        List<Map<String, Object>> operations = new ArrayList<>();
        CanonicalJson.Arr declarations = CompilerProtocolJson.requireArray(
                field(arguments, "supportingDeclarations"), "supportingDeclarations");
        if (declarations.items().size() > MAX_FOUNDATION_RECORD_DECLARATIONS) {
            rejectAgentSurface(
                    "apply_deal_foundation",
                    "SC2001",
                    "A bootstrap batch accepts at most " + MAX_FOUNDATION_RECORD_DECLARATIONS
                            + " mutually dependent record declarations. Merge presentation-only records or defer non-state helpers to behavior batches.");
            return;
        }
        String appStateDeclaration = string(arguments, "appStateDeclaration");
        String initialStateBody = string(arguments, "initialStateBody");
        if (appStateDeclaration.length() > MAX_BOOTSTRAP_DECLARATION_CHARS) {
            rejectAgentSurface(
                    "apply_deal_foundation",
                    "SC2002",
                    "AppState exceeds the compact bootstrap limit. Keep canonical inputs and derived summaries; remove duplicated presentation fields.");
            return;
        }
        if (initialStateBody.length() > MAX_INITIAL_STATE_BODY_CHARS) {
            rejectAgentSurface(
                    "apply_deal_foundation",
                    "SC2003",
                    "initialState exceeds the compact bootstrap limit. Store recurring schedule rules and a small current-day seed; generate occurrences in a helper or update instead of enumerating them.");
            return;
        }
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
                "declaration", appStateDeclaration));
        operations.add(Map.of(
                "operation", DealCompilerWorkspace.REPLACE_FUNCTION_BODY,
                "target", nodeAliases("initialState").get(0),
                "body", initialStateBody));
        operations.add(Map.of(
                "operation", DealCompilerWorkspace.SET_CAPABILITIES,
                "target", module,
                "capabilities", stringArrayOr(arguments, "capabilities", List.of())));
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
        if (supporting.items().size() > MAX_SUPPORTING_DECLARATIONS_PER_BATCH) {
            rejectAgentSurface(
                    "append_deal_behavior",
                    "SC2004",
                    "A behavior batch accepts at most " + MAX_SUPPORTING_DECLARATIONS_PER_BATCH
                            + " supporting declarations. Submit a smaller batch with final=false.");
            return;
        }
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
        if (pairs.items().size() > MAX_ACTION_HANDLERS_PER_BATCH) {
            rejectAgentSurface(
                    "append_deal_behavior",
                    "SC2005",
                    "A behavior batch accepts at most " + MAX_ACTION_HANDLERS_PER_BATCH
                            + " action-handler pairs. Commit a smaller batch with final=false and continue.");
            return;
        }
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
                        "final", false))),
                "behavior transaction");
        applyDeal(transaction);
    }

    private void evolveDealState(CanonicalJson.Obj arguments) {
        if (!stateEvolutionAvailable()) {
            throw new IllegalArgumentException("State evolution requires compiler grants for both AppState and initialState");
        }
        List<Map<String, Object>> operations = new ArrayList<>();
        String module = alias(inspection.deal().moduleId());
        grant(dealGrants, CanonicalCompiler.queryDealModule(deal).allowedOperations());
        CanonicalJson.Arr supporting = CompilerProtocolJson.requireArray(
                field(arguments, "supportingDeclarations"), "supportingDeclarations");
        if (supporting.items().size() > MAX_FOUNDATION_RECORD_DECLARATIONS) {
            rejectAgentSurface("evolve_deal_state", "SC2006",
                    "A state evolution accepts at most " + MAX_FOUNDATION_RECORD_DECLARATIONS
                            + " directly dependent declarations. Split unrelated behavior into a later transaction.");
            return;
        }
        for (CanonicalJson.Value value : supporting.items()) {
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
        CanonicalJson.Obj producers = CompilerProtocolJson.requireObject(
                field(arguments, "stateProducerBodies"), "stateProducerBodies");
        for (SemanticSlice slice : CanonicalCompiler.queryRootStateProducers(deal)) {
            grant(dealGrants, slice.allowedOperations());
            var operation = slice.allowedOperations().stream()
                    .filter(value -> value.operation().equals(DealCompilerWorkspace.REPLACE_FUNCTION_BODY))
                    .findFirst().orElseThrow();
            String target = alias(operation.targetId());
            operations.add(Map.of("operation", DealCompilerWorkspace.REPLACE_FUNCTION_BODY,
                    "target", target, "body", string(producers, target)));
        }
        operations.add(Map.of(
                "operation", DealCompilerWorkspace.SET_CAPABILITIES,
                "target", module,
                "capabilities", stringArrayOr(
                        arguments, "capabilities", inspection.deal().appInterface().capabilities())));
        CanonicalJson.Arr pairs = CompilerProtocolJson.requireArray(
                field(arguments, "actionHandlers"), "actionHandlers");
        if (pairs.items().size() > MAX_ACTION_HANDLERS_PER_BATCH) {
            rejectAgentSurface("evolve_deal_state", "SC2007",
                    "A state evolution accepts at most " + MAX_ACTION_HANDLERS_PER_BATCH
                            + " cohesive action-handler pairs. Continue unrelated behavior in a later transaction.");
            return;
        }
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
        CanonicalJson.Obj transaction = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.decode(CompilerProtocolJson.encode(Map.of(
                        "operations", operations,
                        "final", booleanField(arguments, "final")))),
                "state evolution transaction");
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

    private void replaceDealUiSubtree(CanonicalJson.Obj arguments) {
        SemanticId target = resolveAlias(string(arguments, "target"), "U");
        OperationDescriptor grant = dealUiGrants.get(grantKey(UiCompilerWorkspace.REPLACE_SUBTREE, target));
        if (grant == null) {
            throw new IllegalArgumentException("Subtree replacement is outside the compiler-owned change cone");
        }
        CanonicalJson.Obj transaction = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.decode(CompilerProtocolJson.encode(Map.of(
                        "operations", List.of(Map.of(
                                "operation", UiCompilerWorkspace.REPLACE_SUBTREE,
                                "target", alias(target),
                                "source", string(arguments, "source"))),
                        "final", booleanField(arguments, "final")))),
                "subtree replacement transaction");
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

    private void finishDeal(CanonicalJson.Obj arguments) {
        if (!hostContractReady()) {
            throw new IllegalArgumentException("DEAL host contract is not ready: "
                    + CompilerProtocolJson.encode(CanonicalCompiler.inspectHostRequirements(
                            inspection.deal().appInterface(), inspection.componentPack())));
        }
        if (!generation || !generationStage().equals("declarations") || !dealIsValid()) {
            throw new IllegalArgumentException("finish_deal requires valid generated DEAL after bootstrap");
        }
        if (inspection.deal().appInterface().actions().isEmpty()) {
            throw new IllegalArgumentException("finish_deal requires at least one reachable action for a generated mini-application");
        }
        Set<String> availableActions = inspection.deal().appInterface().actions().stream()
                .map(CompilerProtocol.TypeSnapshot::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        CanonicalJson.Arr covered = CompilerProtocolJson.requireArray(
                field(arguments, "coveredActions"), "coveredActions");
        Set<String> coveredActions = covered.items().stream()
                .map(value -> {
                    if (!(value instanceof CanonicalJson.Str action)) {
                        throw new IllegalArgumentException("coveredActions must contain action names");
                    }
                    return action.value();
                })
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!coveredActions.equals(availableActions)) {
            throw new IllegalArgumentException(
                    "finish_deal must account for every accepted action; expected " + availableActions);
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
        String beforeDigest = inspection.dealUi().sourceDigest();
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
        if (result.sourceDigest().equals(beforeDigest)) {
            if (finishAcceptedRevision(finalChange)) return;
            SemanticId owner = operations.get(0).targetId();
            List<RepairScope> scopes = operations.stream()
                    .map(operation -> new RepairScope(operationName(operation), operation.targetId()))
                    .distinct()
                    .toList();
            reject("apply_deal_ui_changes", List.of(new StructuredDiagnostic(
                    "SC1002",
                    "error",
                    "The accepted transaction made no source progress; submit a changed payload or use artifact_unchanged after compiler inspection",
                    null,
                    owner,
                    "a source-changing operation",
                    "unchanged source",
                    operations.stream().map(UiCompilerWorkspace.Operation::targetId).distinct().toList(),
                    scopes,
                    "queryDealUiNode(" + owner.value() + ")")), "dealui", operations);
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

    private boolean finishAcceptedRevision(boolean finalChange) {
        if (generation || !finalChange || (!repairScopes.isEmpty())
                || (deal.equals(previousDeal) && dealUi.equals(previousDealUi))) return false;
        var checked = CanonicalCompiler.compileCanonicalApp(deal, dealUi, pack, packSpecifier);
        if (!checked.valid()) return false;
        inspection = checked;
        forcedArtifact = "";
        resetSurface();
        addTranscript("finish_accepted_revision", Map.of("accepted", true, "sourceChanged", false));
        status = Status.COMPLETE;
        return true;
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
            repairNoProgressAttempts++;
            addTranscript("compiler_no_progress", Map.of(
                    "artifact", repairArtifact,
                    "slot", slotId,
                    "payloadFingerprint", slot.payloadFingerprint(),
                    "attempt", repairNoProgressAttempts,
                    "instruction", "The payload is byte-identical to the rejected slot. Submit a changed, smaller correction."));
            if (repairNoProgressAttempts >= 2) {
                fail("SC1002", "repair made no progress twice for compiler-owned slot " + slotId);
            }
            return;
        }
        repairNoProgressAttempts = 0;
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

    private void dropRepairSlot(CanonicalJson.Obj arguments) {
        if (repairWorkspace == null || !repairArtifact.equals("deal")) {
            throw new IllegalStateException("No active DEAL repair workspace");
        }
        String slotId = string(arguments, "slot");
        RepairSlot slot = activeRejectedRepairSlot();
        if (!slot.slotId().equals(slotId)
                || !slot.operation().equals(DealCompilerWorkspace.ADD_DECLARATION)) {
            throw new IllegalArgumentException("Only the active rejected addDeclaration slot may be dropped");
        }
        string(arguments, "reason");
        repairSlotPatches++;
        var result = CanonicalCompiler.patchDealRepairWorkspace(
                deal, repairWorkspace, List.of(SlotPatch.drop(slotId)));
        if (!result.accepted()) {
            semanticRepairs++;
            dealSemanticRepairs++;
            repairWorkspace = result.workspace();
            repairDiagnostics = result.diagnostics();
            addTranscript("drop_repair_slot", Map.of(
                    "accepted", false,
                    "slot", slotId,
                    "diagnostics", compactDiagnostics(result.diagnostics())));
            if (dealSemanticRepairs > maxSemanticRepairs) {
                status = Status.FAILED;
                deal = previousDeal;
                dealUi = previousDealUi;
            }
            return;
        }
        addTranscript("drop_repair_slot", Map.of("accepted", true, "slot", slotId));
        completeDealRepair(result);
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
                : !finalChange ? "deal"
                : change.impact().interfaceChanged() ? "dealui" : "";
        inspection = CanonicalCompiler.inspectCanonicalApp(deal, dealUi, pack, packSpecifier);
        resetSurface();
        if (generation && finalChange) transcript.clear();
        else addTranscript("patch_repair_slot", Map.of("accepted", true, "impact", change.impact()));
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
        repairNoProgressAttempts = 0;
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
        boolean untypedEmptyArray = slot.diagnostics().stream()
                .anyMatch(value -> value.code().equals("E3002"));
        boolean uiStringTypeMismatch = slot.diagnostics().stream()
                .anyMatch(value -> value.code().equals("UI2031")
                        && value.expected().contains("string")
                        && (value.actual().contains("int") || value.actual().contains("number")));
        boolean uiNumericStringOperation = slot.diagnostics().stream()
                .anyMatch(value -> value.code().equals("UI2020")
                        && (value.message().contains("string")
                                || value.expected().contains("string")
                                || value.actual().contains("string")));
        boolean functionBody = field.equals("body");
        return "Complete replacement for field " + field + " of " + slot.operation()
                + " on compiler target " + alias(slot.targetId())
                + ". It must differ from the rejected payload"
                + (functionBody
                        ? ". Supply statements only; never include a class or function declaration, signature, or outer braces"
                        : "")
                + (untypedEmptyArray
                        ? ". Give every empty local array an explicit element type, for example `let items: Item[] = [];`"
                        : "")
                + (numericStringMix
                        ? ". Do not concatenate string and numeric values; preserve numeric state for typed UI formatting"
                        : "")
                + (uiStringTypeMismatch
                        ? ". Fix the expression at the diagnostic range with a typed numeric component. For int state use ui.IntText or ui.IntStat instead of ui.Text, ui.Stat, or an empty string placeholder; preserve unrelated nodes byte-for-byte"
                        : "")
                + (uiNumericStringOperation
                        ? ". Scan the complete replacement for every numeric + string expression, not only the first reported range. Never concatenate numeric state into Text, labels, subtitles, suffixes, prefixes, or accessibility strings. Render each dynamic int with ui.IntText/ui.IntStat/ui.IntListItem, place adjacent static text in a separate ui.Text, and keep every unrelated node byte-for-byte"
                        : "")
                + (contract.isBlank() ? "." : ". " + contract);
    }

    private Map<String, Object> repairStringSchema(RepairSlot slot, String field) {
        return Map.of(
                "type", "string",
                "description", repairFieldDescription(slot, field));
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

    private void rejectAgentSurface(String tool, String code, String instruction) {
        addTranscript(tool, Map.of(
                "accepted", false,
                "category", "agent_surface_contract",
                "code", code,
                "instruction", instruction,
                "workspaceChanged", false,
                "semanticRepairConsumed", false));
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
                        case DealCompilerWorkspace.SetCapabilities item -> result.put(
                                payloadKey(operationName(item), item.targetId(), "capabilities"),
                                String.join("\n", item.capabilities()));
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

    static Map<String, Object> compactDiagnostics(List<StructuredDiagnostic> diagnostics) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Map<String, Object>> examples = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (StructuredDiagnostic diagnostic : diagnostics) {
            counts.merge(diagnostic.code(), 1, Integer::sum);
            String identity = CompilerProtocolJson.encode(diagnostic);
            if (!seen.add(identity) || examples.size() >= 6) continue;
            Map<String, Object> example = new LinkedHashMap<>();
            example.put("code", diagnostic.code());
            example.put("message", diagnostic.message());
            example.put("severity", diagnostic.severity());
            if (!diagnostic.expected().isBlank()) example.put("expected", diagnostic.expected());
            if (!diagnostic.actual().isBlank()) example.put("actual", diagnostic.actual());
            if (diagnostic.context() != null) example.put("context", Map.of(
                    "version", diagnostic.context().version(),
                    "excerpt", diagnostic.context().excerpt(),
                    "truncated", diagnostic.context().truncated()));
            if (!diagnostic.notes().isEmpty()) example.put("notes", diagnostic.notes().stream()
                    .map(note -> note.message()).toList());
            examples.add(Map.copyOf(example));
        }
        return Map.of("counts", counts, "examples", examples,
                "uniqueCount", seen.size(), "omittedCount", Math.max(0, seen.size() - examples.size()));
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
                case DealCompilerWorkspace.SET_CAPABILITIES -> new DealCompilerWorkspace.SetCapabilities(
                        target, stringArray(operation, "capabilities"));
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
        uiEditSurface = null;
        requestedUiContracts = Map.of();
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
            case DealCompilerWorkspace.SetCapabilities ignored -> DealCompilerWorkspace.SET_CAPABILITIES;
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
        List<Map<String, Object>> nodes = inspection.dealUi().nodes().stream().map(node -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("target", alias(node.id()));
            value.put("view", alias(node.ownerViewId()));
            if (node.parentId() != null) value.put("parent", alias(node.parentId()));
            value.put("kind", node.kind());
            value.put("component", node.component());
            value.put("childCount", node.children().size());
            value.put("state", node.statePaths());
            value.put("actions", node.actionBindings());
            return Map.copyOf(value);
        }).toList();
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
                        "parent", component.parent(),
                        "events", component.events(),
                        "capabilities", component.capabilities())).toList(),
                "tokens", snapshot.tokens());
    }

    private Map<String, Object> compactUiEditSurface(UiCompilerWorkspace.UiEditSurface surface) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revision", surface.revision().sourceDigest());
        result.put("target", alias(surface.targetId()));
        result.put("source", surface.source());
        result.put("node", compactUiEditNode(surface.node()));
        if (surface.parent() != null) result.put("parent", compactUiEditNode(surface.parent()));
        result.put("children", surface.children().stream().map(this::compactUiEditNode).toList());
        result.put("statePaths", surface.statePaths());
        result.put("lexicalBindings", surface.lexicalBindings());
        result.put("bindingTypes", surface.bindingTypes().stream().map(value -> Map.of("name", value.name(), "fields", value.fields())).toList());
        result.put("tokens", inspection.componentPack().tokens());
        result.put("compatibleActions", surface.compatibleActions().stream().map(action -> Map.of(
                "name", action.name(),
                "fields", action.fields())).toList());
        result.put("appTheme", surface.appThemeSource());
        result.put("componentContracts", surface.componentContracts().stream().map(component -> Map.of(
                "name", component.name(),
                "properties", component.properties(),
                "children", component.children(),
                "parent", component.parent(),
                "events", component.events(),
                "capabilities", component.capabilities())).toList());
        result.put("allowedOperation", Map.of(
                "operation", surface.allowedOperation().operation(),
                "target", alias(surface.allowedOperation().targetId()),
                "fields", surface.allowedOperation().requiredFields()));
        return Map.copyOf(result);
    }

    private Map<String, Object> compactUiEditNode(UiCompilerWorkspace.UiNodeSnapshot node) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", alias(node.id()));
        result.put("component", node.component());
        result.put("childCount", node.children().size());
        result.put("statePaths", node.statePaths());
        result.put("actionBindings", node.actionBindings());
        return Map.copyOf(result);
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
            if (slice.node().parentId() != null) {
                result.put("parent", alias(slice.node().parentId()));
                UiCompilerWorkspace.UiNodeSnapshot parent = inspection.dealUi().nodes().stream()
                        .filter(value -> value.id().equals(slice.node().parentId()))
                        .findFirst().orElse(null);
                if (parent != null) result.put("index", parent.children().indexOf(slice.node().id()));
            }
            result.put("component", slice.node().component());
            result.put("childCount", slice.node().children().size());
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

    private static List<String> stringArrayOr(
            CanonicalJson.Obj object, String name, List<String> fallback) {
        boolean present = object.entries().stream().anyMatch(entry -> entry.key().equals(name));
        return present ? stringArray(object, name) : List.copyOf(fallback);
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
