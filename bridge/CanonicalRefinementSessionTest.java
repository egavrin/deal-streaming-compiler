package streaming.compiler;

import deal.compiler.CompilerProtocolJson;
import deal.semantic.ir.CanonicalJson;
import deal.ui.CanonicalCompiler;

import java.util.List;
import java.util.Map;
import static deal.compiler.CompilerProtocolJson.field;
import static deal.compiler.CompilerProtocolJson.requireArray;

/** Executable regression test for the portable LLM-facing refinement state machine. */
public final class CanonicalRefinementSessionTest {
    private static final String DEAL = """
            export class AppState { title: string = "Ready"; count: int = 0; }
            export class IncrementAction {}
            export function initialState(): AppState { return {title: "Ready", count: 0}; }
            // @ui-update
            export function update(state: AppState, action: IncrementAction): AppState {
              return {title: state.title, count: state.count + 1};
            }
            """;
    private static final String PACK = """
            pack version "test-v1";
            export class ColumnProps {}
            export class TextProps { value: string; }
            export class ButtonProps { text: string; onClick?: Action; }
            export class ClockProps { onTick: Action; }
            export component Column(props: ColumnProps): View { children optional; }
            export component Text(props: TextProps): View;
            export component Button(props: ButtonProps): View { event onClick; }
            export component Clock(props: ClockProps): View { event onTick(payload: int); capability "host.clock.frame"; }
            """;
    private static final String UI = """
            import * as app from "./app.deal";
            import * as ui from "./ui.pack";
            // @ui-root
            export view App(state: app.AppState): View {
              ui.Column() {
                ui.Text(value: state.title)
                ui.Button(text: "Add", onClick: action app.IncrementAction {})
              }
            }
            """;

    private CanonicalRefinementSessionTest() {}

    public static void main(String[] args) {
        argumentRepairIsScopedAndTransactional();
        sourceFreeConstructionCompilesAndRepairs();
        greenfieldHostReadinessGatesUi();
        diagnosticCompactionPreservesLocationsAndEvidence();
        inspectChangeUnlocksCompilerOwnedCone();
        stateSchemaEvolutionUsesOneAtomicTool();
        refinementPromptDistinguishesValidityFromRequestCompletion();
        inspectChangeSchemaKeepsArtifactAnchorsDisjoint();
        unchangedRequiresCompilerEvidence();
        rejectedDealBodyNarrowsRepairAndRollsForward();
        unqueriedAliasCannotBeWritten();
        batchedQueriesConsumeOneProviderRound();
        writePhaseHidesAllQueryTools();
        dealBodyInspectionCanAddSiblingDeclarations();
        refinementActionInsertionCanContinueInDeal();
        rawDeclarationInsertionIsHiddenBehindSemanticTools();
        interfaceChangeRequestsMinimalUiInspection();
        interfaceChangeUsesMinimalChildInsertion();
        subtreeReplacementPreservesUnrelatedSiblings();
        subtreeWriteSurfaceOmitsCanonicalSourcesAndIndexes();
        missingUiContractsAreReadWithoutBroadeningGrants();
        uiOnlyChangeNeverTouchesDeal();
        uiViewQueryCanAddAView();
        greenfieldBuildsDealBeforeDealUi();
        greenfieldCompletesPartialBootstrapWithoutReopeningCommittedState();
        greenfieldFinalFalseKeepsBuildingDeal();
        acceptedChangeSetResetsTheLocalRepairBudget();
        greenfieldNoOpBecomesScopedRepair();
        refinementNoOpWritesBecomeScopedRepair();
        acceptedRevisionCanBeConfirmedWithoutRewriting();
        stricterCheckedContractBecomesRepairInsteadOfShadowCrash();
        duplicateGreenfieldDeclarationsCanOnlyFinishDeal();
        numericStringRepairExplainsTypedUiFormatting();
        numericUiConcatenationRequestsWholeSubtreeCleanup();
        emptyArrayRepairPublishesAConstrainedFunctionBodyContract();
        rejectedOptionalDeclarationCanBeDroppedWithoutLosingSiblings();
        greenfieldBehaviorBatchesAreCompilerBounded();
        oversizedFoundationRetriesWithoutCrashingOrConsumingRepair();
        System.out.println("CanonicalRefinementSessionTest: all tests passed");
    }

    private static void argumentRepairIsScopedAndTransactional() {
        var operand = Map.of("type", "string");
        var callSchema = deal.compiler.DealConstruction.objectSchema(Map.of(
                "id", operand, "value", operand));
        var schema = deal.compiler.DealConstruction.objectSchema(Map.of(
                "calls", Map.of("type", "array", "items", callSchema),
                "result", operand));
        var original = object("{\"calls\":[{\"id\":\"a\",\"value\":{\"id\":\"b\"}},"
                + "{\"id\":\"b\",\"value\":\"unchanged\"}],\"result\":17}");
        String originalBytes = CompilerProtocolJson.encode(original);
        var workspace = new ArgumentRepairWorkspace("construct_test", schema, original);
        String oldTicket = workspace.ticket();
        String request = workspace.request(Map.of("status", "request"));
        check(toolNames(request).equals(List.of("patch_tool_argument")), "only narrow argument repair is granted");
        check(request.contains("availableHandles"), "repair provides existing handle choices");
        expectRejected(() -> workspace.patch(object(CompilerProtocolJson.encode(Map.of(
                "ticket", "stale", "replacement", "b")))));
        check(workspace.rounds() == 0 && CompilerProtocolJson.encode(workspace.candidate()).equals(originalBytes),
                "stale ticket cannot mutate the candidate or consume a round");
        expectRejected(() -> workspace.patch(object(CompilerProtocolJson.encode(Map.of(
                "ticket", oldTicket, "replacement", "b", "path", List.of("result"))))));
        workspace.patch(object(CompilerProtocolJson.encode(Map.of("ticket", oldTicket, "replacement", "b"))));
        check(!workspace.complete(), "second schema defect remains staged");
        check(CompilerProtocolJson.encode(original).equals(originalBytes), "original tool arguments are immutable");
        check(CompilerProtocolJson.encode(requireArray(field(workspace.candidate(), "calls"), "calls").items().get(1))
                .equals(CompilerProtocolJson.encode(requireArray(field(original, "calls"), "calls").items().get(1))),
                "unrelated sibling retains identical canonical bytes");
        expectRejected(() -> workspace.patch(object(CompilerProtocolJson.encode(Map.of("ticket", oldTicket, "replacement", "a")))));
        workspace.patch(object(CompilerProtocolJson.encode(Map.of("ticket", workspace.ticket(), "replacement", "a"))));
        check(workspace.complete() && workspace.rounds() == 2, "two defects repaired separately");
        CanonicalRefinementSession.validateSchema(workspace.candidate(), schema);

        var extra = new ArgumentRepairWorkspace("construct_test", schema,
                object("{\"calls\":[{\"id\":\"a\",\"value\":\"b\",\"unexpected\":42}],\"result\":\"a\"}"));
        extra.patch(object(CompilerProtocolJson.encode(Map.of("ticket", extra.ticket()))));
        check(extra.complete(), "unexpected property may only be removed");
        var missing = new ArgumentRepairWorkspace("construct_test", schema,
                object("{\"calls\":[{\"id\":\"a\"}],\"result\":\"a\"}"));
        missing.patch(object(CompilerProtocolJson.encode(Map.of("ticket", missing.ticket(), "replacement", "b"))));
        check(missing.complete(), "missing leaf can be inserted");
        expectRejected(() -> new ArgumentRepairWorkspace("construct_test", schema,
                object("{\"calls\":\"wrong\",\"result\":\"a\"}")));
        expectRejected(() -> new ArgumentRepairWorkspace("construct_test", schema,
                object("{\"calls\":[],\"result\":\"a\"}")));
        expectRejected(() -> new ArgumentRepairWorkspace("construct_test", schema, object("{}")));
    }

    private static void refinementActionInsertionCanContinueInDeal() {
        var session = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Add pause and restart actions", 4, 1);
        session.nextRequestJson();
        String edit = session.acceptToolCallJson(
                "inspect_deal_change", CompilerProtocolJson.encode(Map.of("anchors", List.of("M1"), "requestedOperations", List.of("addDeclaration"))));
        check(toolNames(edit).contains("add_deal_action_handler"),
                "module inspection must expose semantic action insertion");
        String next = session.acceptToolCallJson("add_deal_action_handler", CompilerProtocolJson.encode(Map.of(
                "actionDeclaration", "export class PauseAction {}",
                "handlerDeclaration", "// @ui-update\nexport function pause(state: AppState, action: PauseAction): AppState { return state; }",
                "final", false)));
        CanonicalJson.Obj input = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.decode(stringField(object(next), "input")), "input");
        check(stringField(input, "requiredArtifact").equals("deal")
                        && toolNames(next).contains("inspect_deal_change"),
                "a partial semantic insertion must keep refinement in DEAL for the next local cone");
    }

    private static void refinementPromptDistinguishesValidityFromRequestCompletion() {
        var session = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Add a pause action and control", 3, 1);
        String request = session.nextRequestJson();
        check(request.contains("A successful compile proves structural validity")
                        && request.contains("Never satisfy a missing requirement")
                        && request.contains("unrelated writable unit"),
                "the Agent Surface must distinguish compiler validity from instruction fidelity");
    }

    private static void oversizedFoundationRetriesWithoutCrashingOrConsumingRepair() {
        var session = CanonicalRefinementSession.greenfield(PACK, "./ui.pack", "Counter", 8, 2);
        String initial = session.nextRequestJson();
        expectRejected(() -> session.acceptToolCallJson("apply_deal_foundation", CompilerProtocolJson.encode(Map.of("supportingDeclarations", List.of(), "capabilities", List.of(), "appStateDeclaration", "export class AppState { title: string = \"\"; count: int = 0; }", "initialStateBody", " ".repeat(7000)))));
        check(session.nextRequestJson().equals(initial), "schema rejection preserves grants and budgets");
        check(toolNames(counterFoundation(session)).contains("append_deal_behavior"), "valid retry uses same grant");
    }

    private static void greenfieldBehaviorBatchesAreCompilerBounded() {
        var session = CanonicalRefinementSession.greenfield(
                PACK, "./ui.pack", "Create a multi-control application", 8, 2);
        String foundation = session.nextRequestJson();
        check(foundation.contains("\"maxItems\":8"),
                "foundation must admit a compact mutually dependent record group");
        String behavior = session.acceptToolCallJson("apply_deal_foundation", CompilerProtocolJson.encode(Map.of(
                "supportingDeclarations", List.of(),
                "capabilities", List.of(),
                "appStateDeclaration", "export class AppState { title: string = \"\"; count: int = 0; }",
                "initialStateBody", "return {title: \"Ready\", count: 0};")));
        check(behavior.contains("\"maxItems\":4"),
                "the compiler surface must cap action and helper batches before provider generation: " + behavior);

        var pair = Map.of(
                "actionDeclaration", "export class IncrementAction {}",
                "handlerDeclaration", "// @ui-update\n"
                        + "export function increment(state: AppState, action: IncrementAction): AppState { "
                        + "return {title: state.title, count: state.count + 1}; }");
        expectRejected(() -> session.acceptToolCallJson("append_deal_behavior", CompilerProtocolJson.encode(Map.of(
                "supportingDeclarations", List.of(),
                "actionHandlers", List.of(pair, pair, pair, pair, pair),
                "final", false))));
        check(session.nextRequestJson().equals(behavior), "oversized batch preserves source, grants and budgets");
    }

    private static void inspectChangeSchemaKeepsArtifactAnchorsDisjoint() {
        var session = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Add an undo control and update its behavior", 4, 1);
        CanonicalJson.Obj request = object(session.nextRequestJson());
        CanonicalJson.Arr tools = CompilerProtocolJson.requireArray(
                CompilerProtocolJson.field(request, "tools"), "tools");
        Map<String, String> schemas = new java.util.LinkedHashMap<>();
        tools.items().stream()
                .map(value -> CompilerProtocolJson.requireObject(value, "tool"))
                .filter(value -> stringField(value, "name").startsWith("inspect_"))
                .forEach(value -> schemas.put(
                        stringField(value, "name"),
                        CompilerProtocolJson.encode(CompilerProtocolJson.field(value, "parameters"))));
        check(schemas.keySet().equals(java.util.Set.of("inspect_deal_change", "inspect_deal_ui_change")),
                "mixed refinement must publish separate DEAL and Deal UI inspect tools: " + schemas.keySet());
        check(!schemas.get("inspect_deal_change").contains("\"V1\"")
                        && !schemas.get("inspect_deal_change").contains("\"U1\""),
                "DEAL inspect must not admit Deal UI anchors");
        check(!schemas.get("inspect_deal_ui_change").contains("\"S1\"")
                        && !schemas.get("inspect_deal_ui_change").contains("\"B1\""),
                "Deal UI inspect must not admit DEAL anchors");
    }

    private static void unchangedRequiresCompilerEvidence() {
        var session = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Make the application clearer", 6, 1);
        String initial = session.nextRequestJson();
        check(!toolNames(initial).contains("unchanged") && !toolNames(initial).contains("artifact_unchanged"),
                "a refinement must not claim no-op before compiler inspection");
        String inspected = session.acceptToolCallJson("inspect_deal_change", CompilerProtocolJson.encode(Map.of(
                "anchors", List.of(dealSymbolAlias(initial, "AppState")),
                "requestedOperations", List.of("replaceDeclaration"))));
        check(toolNames(inspected).contains("artifact_unchanged"),
                "an inspected artifact may report no-op with compiler-owned evidence");
    }

    private static void numericStringRepairExplainsTypedUiFormatting() {
        var session = CanonicalRefinementSession.greenfield(
                PACK, "./ui.pack", "Create an interactive progress counter", 6, 2);
        String initial = session.nextRequestJson();
        String foundation = session.acceptToolCallJson("apply_deal_foundation", CompilerProtocolJson.encode(Map.of(
                "supportingDeclarations", List.of(),
                "capabilities", List.of(),
                "appStateDeclaration", "export class AppState { label: string = \"\"; count: int = 0; }",
                "initialStateBody", "return {label: \"Ready\", count: 0};")));
        check(toolNames(foundation).contains("append_deal_behavior"),
                "valid foundation must advance to behavior");
        String repair = session.acceptToolCallJson("append_deal_behavior", CompilerProtocolJson.encode(Map.of(
                "supportingDeclarations", List.of(),
                "actionHandlers", List.of(Map.of(
                        "actionDeclaration", "export class IncrementAction {}",
                        "handlerDeclaration", "// @ui-update\n"
                                + "export function increment(state: AppState, action: IncrementAction): AppState { "
                                + "return {label: \"Count: \" + (state.count + 1), count: state.count + 1}; }")),
                "final", false)));
        check(toolNames(repair).contains("patch_repair_slot"),
                "mixed numeric/string behavior must enter a narrow repair slot");
        check(repair.contains("preserve numeric state for typed UI formatting")
                        && repair.contains("no implicit coercion"),
                "repair surface must explain the supported representation instead of repeating E3010: " + repair);
    }

    private static void numericUiConcatenationRequestsWholeSubtreeCleanup() {
        var session = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Show a compact count summary", 5, 2);
        String initial = session.nextRequestJson();
        String column = uiNodeAlias(initial, "ui.Column");
        String write = session.acceptToolCallJson("inspect_deal_ui_change", CompilerProtocolJson.encode(Map.of(
                "anchors", List.of(column),
                "requestedOperations", List.of("replaceSubtree"))));
        String repair = session.acceptToolCallJson("replace_deal_ui_subtree", CompilerProtocolJson.encode(Map.of(
                "target", column,
                "source", "ui.Column() { ui.Text(value: state.count + \" items\") "
                        + "ui.Button(text: \"Add\", onClick: action app.IncrementAction {}) }",
                "final", true)));
        check(toolNames(repair).contains("patch_repair_slot"),
                "numeric UI concatenation must enter compiler-owned repair");
        check(repair.contains("Scan the complete replacement for every numeric + string expression")
                        && repair.contains("Never concatenate numeric state")
                        && repair.contains("ui.IntText"),
                "the repair contract must prevent one-error-at-a-time subtree retries: " + repair);
    }

    private static void emptyArrayRepairPublishesAConstrainedFunctionBodyContract() {
        var session = CanonicalRefinementSession.greenfield(
                PACK, "./ui.pack", "Create a schedule with repeated items", 6, 2);
        String initial = session.nextRequestJson();
        check(initial.contains("every empty local array must have an explicit element type")
                        && initial.contains("Return one complete AppState value"),
                "greenfield bootstrap must publish the collection and return contracts");
        String repair = session.acceptToolCallJson("apply_deal_foundation", CompilerProtocolJson.encode(Map.of(
                "capabilities", List.of(),
                "supportingDeclarations", List.of(
                        "export class Item { id: string = \"\"; label: string = \"\"; }"),
                "appStateDeclaration",
                        "export class AppState { title: string = \"\"; items: Item[] = []; }",
                "initialStateBody", "let items = []; return {title: \"Health\", items: items};")));
        check(toolNames(repair).contains("patch_repair_slot"),
                "an untyped empty array must enter compiler-owned repair");
        check(repair.contains("Give every empty local array an explicit element type")
                        && repair.contains("never include a class or function declaration"),
                "repair tool must teach the constrained body contract: " + repair);
        String repaired = session.acceptToolCallJson("patch_repair_slot", CompilerProtocolJson.encode(Map.of(
                "slot", "R3",
                "payload", Map.of("body",
                        "let items: Item[] = []; return {title: \"Health\", items: items};"))));
        check(toolNames(repaired).contains("append_deal_behavior"),
                "a typed local collection repair must preserve the staged bootstrap siblings");
    }

    private static void rejectedOptionalDeclarationCanBeDroppedWithoutLosingSiblings() {
        var session = CanonicalRefinementSession.greenfield(
                PACK, "./ui.pack", "Create an interactive counter", 8, 2);
        session.nextRequestJson();
        String behavior = session.acceptToolCallJson("apply_deal_foundation", CompilerProtocolJson.encode(Map.of(
                "supportingDeclarations", List.of(),
                "capabilities", List.of(),
                "appStateDeclaration", "export class AppState { title: string = \"\"; count: int = 0; }",
                "initialStateBody", "return {title: \"Counter\", count: 0};")));
        check(toolNames(behavior).contains("append_deal_behavior"), "foundation must advance to behavior");
        check(!toolNames(behavior).contains("finish_deal"),
                "a generated mini-application cannot finish with an empty action interface");
        String repair = session.acceptToolCallJson("append_deal_behavior", CompilerProtocolJson.encode(Map.of(
                "supportingDeclarations", List.of(
                        "export function placeholder(state: AppState): void { return state; }"),
                "actionHandlers", List.of(Map.of(
                        "actionDeclaration", "export class IncrementAction {}",
                        "handlerDeclaration", "// @ui-update\n"
                                + "export function increment(state: AppState, action: IncrementAction): AppState { "
                                + "return {title: state.title, count: state.count + 1}; }")),
                "final", false)));
        check(toolNames(repair).containsAll(List.of("patch_repair_slot", "drop_repair_slot")),
                "an optional rejected declaration must be patchable or droppable");
        String completionAudit = session.acceptToolCallJson("drop_repair_slot", CompilerProtocolJson.encode(Map.of(
                "slot", "R1", "reason", "The placeholder is not required by the requested behavior")));
        check(toolNames(completionAudit).contains("finish_deal")
                        && completionAudit.contains("IncrementAction")
                        && !completionAudit.contains("placeholder(state"),
                "dropping the optional slot must preserve accepted behavior for completion audit: " + completionAudit);
        String ui = session.acceptToolCallJson("finish_deal", CompilerProtocolJson.encode(Map.of(
                "coveredActions", List.of("IncrementAction"),
                "reason", "IncrementAction covers the requested counter interaction")));
        check(toolNames(ui).contains("apply_deal_ui_changes"),
                "audited behavior must advance to Deal UI");
    }

    private static void inspectChangeUnlocksCompilerOwnedCone() {
        var session = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Increment by two", 4, 1);
        String initial = session.nextRequestJson();
        check(toolNames(initial).contains("inspect_deal_change")
                        && toolNames(initial).contains("inspect_deal_ui_change")
                        && toolNames(initial).stream().noneMatch(value -> value.startsWith("query_")),
                "the initial agent surface must expose compiler-owned change inspection without query tools");
        String update = dealSymbolAlias(initial, "update");
        String body = dealBodyAlias(initial, update);
        String write = session.acceptToolCallJson("inspect_deal_change", CompilerProtocolJson.encode(Map.of(
                "anchors", List.of(body),
                "requestedOperations", List.of("replaceFunctionBody"))));
        check(toolNames(write).contains("apply_deal_changes"),
                "inspect_change must unlock the operation issued by the compiler cone");
        check(!toolNames(write).contains("inspect_deal_change")
                        && !toolNames(write).contains("inspect_deal_ui_change"),
                "write phase must not retain the inspection tool");
        check(write.contains("coneFingerprint"),
                "the compact context must include the compiler cone fingerprint");
        String result = session.acceptToolCallJson("apply_deal_changes", operationArguments(Map.of(
                "operation", "replaceFunctionBody",
                "body", "return {title: state.title, count: state.count + 2};"), true));
        check(booleanField(object(result), "accepted"),
                "a uniquely targeted operation must infer its compiler target and compile");
    }

    private static void stateSchemaEvolutionUsesOneAtomicTool() {
        var session = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Add a pause state and toggle action", 6, 2);
        String initial = session.nextRequestJson();
        String appState = dealSymbolAlias(initial, "AppState");
        String initialState = dealSymbolAlias(initial, "initialState");
        String initialBody = dealBodyAlias(initial, initialState);
        String write = session.acceptToolCallJson("inspect_deal_change", CompilerProtocolJson.encode(Map.of(
                "anchors", List.of("M1", appState, initialState),
                "requestedOperations", List.of("addDeclaration", "replaceFunctionBody"))));
        check(toolNames(write).contains("evolve_deal_state"),
                "a root-state schema cone must expose one cohesive state-evolution tool");
        check(!toolNames(write).contains("apply_deal_changes"),
                "the state-evolution cone must hide the broad transaction that permits half-applied schemas");
        check(write.contains("stateProducers") && write.contains("state.count + 1"),
                "schema evolution must show existing state producer bodies, not only the initializer");
        String next = session.acceptToolCallJson("evolve_deal_state", CompilerProtocolJson.encode(Map.of(
                "supportingDeclarations", List.of(),
                "appStateDeclaration", "export class AppState { title: string = \"Ready\"; count: int = 0; paused: boolean = false; }",
                "initialStateBody", "return {title: \"Ready\", count: 0, paused: false};",
                "stateProducerBodies", Map.of(dealBodyAlias(initial, dealSymbolAlias(initial, "update")),
                        "return {title: state.title, count: state.count + 1, paused: state.paused};"),
                "capabilities", List.of("clock.frame"),
                "actionHandlers", List.of(Map.of(
                        "actionDeclaration", "export class TogglePauseAction {}",
                        "handlerDeclaration", "// @ui-update\nexport function togglePause(state: AppState, action: TogglePauseAction): AppState { return {title: state.title, count: state.count, paused: !state.paused}; }")),
                "final", true)));
        check(stringField(object(next), "input").contains("TogglePauseAction")
                        && stringField(object(next), "input").contains("paused")
                        && stringField(object(next), "input").contains("clock.frame"),
                "the compiler must commit state, actions, and host capabilities as one interface evolution: " + next);
    }

    private static void greenfieldBuildsDealBeforeDealUi() {
        var session = CanonicalRefinementSession.greenfield(
                PACK, "./ui.pack", "Create a counter", 6, 2);
        String initial = session.nextRequestJson();
        check(stringField(CompilerProtocolJson.requireObject(
                        CompilerProtocolJson.decode(stringField(object(initial), "input")), "input"),
                        "requiredArtifact").equals("deal"),
                "greenfield generation must start with DEAL");
        check(!toolNames(initial).contains("query_deal_ui_view"),
                "Deal UI must stay hidden until DEAL is accepted");
        check(toolNames(initial).contains("apply_deal_foundation")
                        && toolNames(initial).stream().noneMatch(value -> value.startsWith("inspect_")),
                "greenfield bootstrap must expose compiler-owned foundation slots directly");
        String appState = dealSymbolAlias(initial, "AppState");
        String initialState = dealSymbolAlias(initial, "initialState");
        String initialBody = dealBodyAlias(initial, initialState);
        String foundationRequest = initial;
        check(toolNames(foundationRequest).contains("apply_deal_foundation"),
                "bootstrap must collapse rich ChangeSet operations into one compact agent handle");
        check(foundationRequest.contains("stable unique id"),
                "bootstrap record declarations must expose the cross-artifact ForEach key contract");
        check(!toolNames(foundationRequest).contains("apply_deal_changes"),
                "bootstrap must not expose the generic operation union");
        String declarations = session.acceptToolCallJson("apply_deal_foundation", CompilerProtocolJson.encode(Map.of(
                "supportingDeclarations", List.of(
                        "export class Item { id: int = 0; label: string = \"\"; }"),
                "capabilities", List.of(),
                "appStateDeclaration", "export class AppState { count: int = 0; items: Item[] = []; }",
                "initialStateBody", "return {count: 0, items: []};")));
        check(toolNames(declarations).contains("append_deal_behavior")
                        && !toolNames(declarations).contains("apply_deal_changes"),
                "greenfield declarations must expose complete action-handler pairs, not raw operations");
        String moreBehavior = session.acceptToolCallJson("append_deal_behavior", CompilerProtocolJson.encode(Map.of(
                "supportingDeclarations", List.of(),
                "actionHandlers", List.of(Map.of(
                        "actionDeclaration", "export class IncrementAction {}",
                        "handlerDeclaration", "// @ui-update\nexport function increment(state: AppState, action: IncrementAction): AppState { return {count: state.count + 1, items: state.items}; }")),
                "final", false)));
        check(toolNames(moreBehavior).contains("append_deal_behavior"),
                "a non-final behavior batch must commit and expose another compact behavior surface");
        check(moreBehavior.contains("IncrementAction") && moreBehavior.contains("increment"),
                "the next surface must inspect the committed behavior without asking the model to resend it");
        String completionAudit = session.acceptToolCallJson("append_deal_behavior", CompilerProtocolJson.encode(Map.of(
                "supportingDeclarations", List.of(),
                "actionHandlers", List.of(Map.of(
                        "actionDeclaration", "export class ResetAction {}",
                        "handlerDeclaration", "// @ui-update\nexport function reset(state: AppState, action: ResetAction): AppState { return {count: 0, items: state.items}; }")),
                "final", false)));
        check(toolNames(completionAudit).contains("finish_deal")
                        && !toolNames(completionAudit).contains("apply_deal_ui_changes"),
                "a bounded behavior write must enter an explicit completion audit");
        String dealAccepted = session.acceptToolCallJson("finish_deal", CompilerProtocolJson.encode(Map.of(
                "coveredActions", List.of("IncrementAction", "ResetAction"),
                "reason", "IncrementAction and ResetAction cover both requested controls")));
        CanonicalJson.Obj dealAcceptedInput = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.decode(stringField(object(dealAccepted), "input")), "input");
        check(stringField(dealAcceptedInput, "requiredArtifact").equals("dealui"),
                "accepted greenfield DEAL must force the Deal UI stage: " + dealAccepted);
        check(dealAcceptedInput.entries().stream().anyMatch(entry -> entry.key().equals("componentPack")),
                "the UI stage must receive the compiler-owned component manifest");
        check(!dealAccepted.contains("apply_deal_changes"),
                "Deal UI surface must not retain DEAL transaction history: " + dealAccepted);
        check(!dealAccepted.contains("replaceDeclaration"),
                "Deal UI surface must not expose DEAL operation schemas: " + dealAccepted);
        check(dealAccepted.contains("Properties use colon, never equals"),
                "Deal UI surface must state its compact call syntax: " + dealAccepted);
        check(dealAccepted.contains("state.score"),
                "Deal UI surface must identify the root state path: " + dealAccepted);
        check(dealAccepted.contains("IncrementAction") && dealAccepted.contains("ResetAction"),
                "all independently committed behavior batches must reach the final AppInterface");
        check(dealAccepted.contains("ForEach(state.items, item: app.Item, key: item.id)"),
                "the Deal UI contract must publish the exact collection syntax");
        check(initial.contains("Do not finish an interactive request with zero actions"),
                "the generation surface must keep request fidelity explicit at completion");
        check(initial.contains("never place the\\nmarker after the opening brace"),
                "the surface must publish exact framework marker placement");
        String view = uiViewAlias(dealAccepted, "App");
        String result = session.acceptToolCallJson("apply_deal_ui_changes", operationArguments(List.of(
                Map.of("operation", "replaceViewBody",
                        "body", "ui.Column() { ui.Text(value: \"Counter\") ui.Button(text: \"Add\", onClick: action app.IncrementAction {}) ui.Button(text: \"Reset\", onClick: action app.ResetAction {}) }")),
                true));
        CanonicalJson.Obj object = object(result);
        check(booleanField(object, "accepted"), "greenfield canonical app must complete");
        check(stringField(object, "deal").contains("IncrementAction"), "generated DEAL must be retained");
        check(stringField(object, "dealUi").contains("ui.Button"), "generated Deal UI must be retained");
    }

    private static void greenfieldHostReadinessGatesUi() {
        var session = CanonicalRefinementSession.greenfield(PACK, "./ui.pack", "Build an interactive timer", 12, 3);
        String initial = session.nextRequestJson();
        check(!initial.contains("storage.private") && !initial.contains("camera.capture"),
                "capability schema must come from the actual pack");
        session.acceptToolCallJson("apply_deal_foundation", CompilerProtocolJson.encode(Map.of(
                "supportingDeclarations", List.of(), "capabilities", List.of("clock.frame"),
                "appStateDeclaration", "export class AppState { count: int = 0; }",
                "initialStateBody", "return {count: 0};")));
        String missing = session.acceptToolCallJson("append_deal_behavior", CompilerProtocolJson.encode(Map.of(
                "supportingDeclarations", List.of(), "actionHandlers", List.of(Map.of(
                        "actionDeclaration", "export class LabelAction { text: string = \"\"; }",
                        "handlerDeclaration", "// @ui-update\nexport function label(state: AppState, action: LabelAction): AppState { return {count: state.count}; }")),
                "final", false)));
        check(!toolNames(missing).contains("finish_deal") && missing.contains("missingEvents"),
                "unconstructible host binding must stay in DEAL and expose compiler facts");
        String ready = session.acceptToolCallJson("append_deal_behavior", CompilerProtocolJson.encode(Map.of(
                "supportingDeclarations", List.of(), "actionHandlers", List.of(Map.of(
                        "actionDeclaration", "export class TickAction { elapsed: int = 0; }",
                        "handlerDeclaration", "// @ui-update\nexport function tick(state: AppState, action: TickAction): AppState { return {count: state.count + action.elapsed}; }")),
                "final", false)));
        check(toolNames(ready).contains("finish_deal"), "adding compatible handler must unblock completion");
    }

    private static Map<String, Object> cc(String id, String op, Map<String, Object> args) {
        var result = new java.util.LinkedHashMap<String, Object>(args);
        result.put("id", id); result.put("op", op); return result;
    }

    private static String construction(List<Map<String, Object>> calls, Map<String, Object> args) {
        return CompilerProtocolJson.encode(Map.of("calls", calls, "arguments", args));
    }

    private static void sourceFreeConstructionCompilesAndRepairs() {
        var scalarChildEnvelope = object(CompilerProtocolJson.encode(Map.of("calls", List.of(
                cc("label", "text", Map.of("value", "Title")),
                cc("content", "component", Map.of("name", "Column", "fields", List.of(), "children", List.of("label")))),
                "arguments", Map.of("body", "content"))));
        try {
            new deal.ui.CanonicalConstruction(true).build(object(CompilerProtocolJson.encode(Map.of(
                    "calls", field(scalarChildEnvelope, "calls"), "result", "content"))), deal.compiler.DealConstruction.Kind.UI);
            throw new AssertionError("scalar child must be rejected");
        } catch (deal.compiler.DealConstruction.Failure failure) {
            check(failure.ownerId.equals("content") && failure.getMessage().contains("NEW component constructor"),
                    "wrong-kind diagnostic must explain the required UI wrapper and target the consuming node");
            var workspace = new deal.compiler.ConstructionRepairWorkspace(scalarChildEnvelope, failure);
            var patchCalls = List.of(
                    cc("labelNode", "component", Map.of("name", "Text", "fields", List.of(Map.of("name", "value", "value", "label")), "children", List.of())),
                    cc("content", "component", Map.of("name", "Column", "fields", List.of(), "children", List.of("labelNode"))));
            var patch = object(CompilerProtocolJson.encode(Map.of("calls", patchCalls)));
            workspace.patch(requireArray(field(patch, "calls"), "calls"));
            String fixed = new deal.ui.CanonicalConstruction(true).build(object(CompilerProtocolJson.encode(Map.of(
                    "calls", field(workspace.envelope(), "calls"), "result", "content"))), deal.compiler.DealConstruction.Kind.UI);
            check(fixed.contains("ui.Text(value: \"Title\")"), "repair attaches a new node without rewriting the original scalar");
        }
        var groupedEnvelope = object(CompilerProtocolJson.encode(Map.of("calls", List.of(
                cc("bad", "return", Map.of("value", "missing")),
                cc("body", "block", Map.of("statements", List.of("bad"))),
                cc("unrelated", "integer", Map.of("value", 9))), "arguments", Map.of("body", "body"))));
        var grouped = new deal.compiler.ConstructionRepairWorkspace(groupedEnvelope,
                new deal.compiler.DealConstruction.Failure("bad", "missing operand"));
        check(CompilerProtocolJson.encode(grouped.snapshot().get("editable")).contains("body"),
                "repair must expose enclosing consumers needed for local introduction");
        var groupedPatch = List.of(
                cc("bad", "return", Map.of("value", Map.of("path", List.of("n")))),
                cc("local", "local", Map.of("name", "n", "type", "int", "value", 1)),
                cc("body", "block", Map.of("statements", List.of("local", "bad"))));
        grouped.patch(CompilerProtocolJson.requireArray(CompilerProtocolJson.field(
                object(CompilerProtocolJson.encode(Map.of("calls", groupedPatch))), "calls"), "calls"));
        check(new deal.compiler.DealConstruction().build(object(CompilerProtocolJson.encode(Map.of(
                "calls", CompilerProtocolJson.field(grouped.envelope(), "calls"), "result", "body"))),
                deal.compiler.DealConstruction.Kind.BLOCK).contains("let n: int = 1;\nreturn n;"),
                "consumer-group repair must permit a local declaration before its use");
        var manyCalls = new java.util.ArrayList<Map<String, Object>>();
        manyCalls.add(cc("bad", "return", Map.of("value", "missing")));
        for (int i = 0; i < 24; i++) manyCalls.add(cc("unchanged" + i, "integer", Map.of("value", i)));
        var manyWorkspace = new deal.compiler.ConstructionRepairWorkspace(object(CompilerProtocolJson.encode(Map.of("calls", manyCalls))),
                new deal.compiler.DealConstruction.Failure("bad", "missing operand"));
        manyCalls.set(0, cc("bad", "return", Map.of("value", 1)));
        manyWorkspace.patch(CompilerProtocolJson.requireArray(CompilerProtocolJson.field(
                object(CompilerProtocolJson.encode(Map.of("calls", manyCalls))), "calls"), "calls"));
        check(CompilerProtocolJson.encode(manyWorkspace.envelope()).contains("unchanged23"),
                "unchanged echoes must not consume the changed-call budget");
        String inlineAction = new deal.ui.CanonicalConstruction(true).build(object(CompilerProtocolJson.encode(Map.of(
                "calls", List.of(cc("button", "component", Map.of("name", "Button", "children", List.of(), "fields", List.of(
                        Map.of("name", "onClick", "value", Map.of("action", Map.of("name", "Increment", "fields", List.of()))))))),
                "result", "button"))), deal.compiler.DealConstruction.Kind.UI);
        check(inlineAction.equals("ui.Button(onClick: action app.Increment {  })"), "inline action must use the same checked action projection");
        String singleUiBody = new deal.ui.CanonicalConstruction(true).build(object(CompilerProtocolJson.encode(Map.of(
                "calls", List.of(cc("theme", "component", Map.of("name", "AppTheme", "children", List.of(), "fields", List.of()))),
                "result", "theme"))), deal.compiler.DealConstruction.Kind.BLOCK);
        check(singleUiBody.equals("ui.AppTheme()"), "single UI node must be a valid body without an administrative wrapper");
        expectRejected(() -> new deal.ui.CanonicalConstruction(true).build(object(CompilerProtocolJson.encode(Map.of(
                "calls", List.of(cc("button", "component", Map.of("name", "Button", "children", List.of(), "fields", List.of(
                        Map.of("name", "onClick", "value", Map.of("action", Map.of("name", "Bad()", "fields", List.of()))))))),
                "result", "button"))), deal.compiler.DealConstruction.Kind.UI));
        var unknownField = deal.compiler.DealCompilerWorkspace.inspect(
                "export class State { enabled: MissingType = false; }\nexport function initial(): State { return {enabled: false}; }", "/app.deal");
        check(unknownField.diagnostics().stream().anyMatch(d -> d.code().equals("E3004") && d.message().contains("MissingType")),
                "late-resolved field type errors must reach repair, not only secondary expected-error mismatches");
        String composedBody = new deal.compiler.DealConstruction().build(object(CompilerProtocolJson.encode(Map.of(
                "calls", List.of(cc("r", "returnRecord", Map.of("fields", List.of(Map.of("name", "value", "value", 1)))),
                        cc("b", "block", Map.of("statements", List.of("r")))), "result", "b"))), deal.compiler.DealConstruction.Kind.BLOCK);
        check(composedBody.equals("return {value: 1};"), "block composition must preserve statement order and semantics");
        String emptyText = new deal.compiler.DealConstruction().build(object(CompilerProtocolJson.encode(Map.of(
                "calls", List.of(cc("r", "return", Map.of("value", ""))), "result", "r"))), deal.compiler.DealConstruction.Kind.STATEMENT);
        check(emptyText.equals("return \"\";"), "empty text is unambiguous because handle ids cannot be empty");
        String taggedInteger = new deal.compiler.DealConstruction().build(object(CompilerProtocolJson.encode(Map.of(
                "calls", List.of(cc("r", "return", Map.of("value", Map.of("integer", 1500)))), "result", "r"))), deal.compiler.DealConstruction.Kind.STATEMENT);
        check(taggedInteger.equals("return 1500;"), "tagged scalar must project the same literal as its constructor");
        String demanded = new deal.compiler.DealConstruction().build(object(CompilerProtocolJson.encode(Map.of(
                "calls", List.of(cc("unused", "return", Map.of("value", "unresolved")),
                        cc("result", "return", Map.of("value", 7))), "result", "result"))), deal.compiler.DealConstruction.Kind.STATEMENT);
        check(demanded.equals("return 7;"), "unpublished construction nodes must not contaminate a requested result");
        String inline = new deal.compiler.DealConstruction().build(object(CompilerProtocolJson.encode(Map.of(
                "calls", List.of(cc("body", "returnRecord", Map.of("fields", List.of(
                        Map.of("name", "label", "value", Map.of("text", "Привет\n\"")),
                        Map.of("name", "count", "value", Map.of("path", List.of("state", "count"))))))),
                "result", "body"))), deal.compiler.DealConstruction.Kind.BLOCK);
        check(inline.contains("state.count") && inline.contains("\\n\\\""), "inline operands must preserve literal escaping and typed paths");
        expectRejected(() -> new deal.compiler.DealConstruction().build(object(CompilerProtocolJson.encode(Map.of(
                "calls", List.of(cc("body", "return", Map.of("value", Map.of("path", List.of("state.count + 1"))))),
                "result", "body"))), deal.compiler.DealConstruction.Kind.STATEMENT));
        try {
            new deal.compiler.DealConstruction().build(object(CompilerProtocolJson.encode(Map.of(
                    "calls", List.of(cc("n", "integer", Map.of("value", 1)),
                            cc("f", "declareFunction", Map.of("name", "f", "parameters", List.of(), "returns", "int", "body", "n"))),
                    "result", "f"))), deal.compiler.DealConstruction.Kind.DECLARATION);
            throw new AssertionError("a value is not a function body");
        } catch (deal.compiler.DealConstruction.Failure failure) {
            check(failure.ownerId.equals("f"), "repair must target the consumer, not corrupt a shared value");
        }
        try {
            new deal.compiler.DealConstruction().build(object(CompilerProtocolJson.encode(Map.of(
                    "calls", List.of(cc("l", "local", Map.of("name", "amount", "type", "int", "value", 0)),
                            cc("r", "return", Map.of("value", "l"))), "result", "r"))), deal.compiler.DealConstruction.Kind.STATEMENT);
            throw new AssertionError("a local declaration is not a value");
        } catch (deal.compiler.DealConstruction.Failure failure) {
            check(failure.ownerId.equals("r") && failure.getMessage().contains("{\"path\":[\"amount\"]}")
                    && failure.getMessage().contains("before use"), "local diagnostics must expose the actual reference operand and scope requirement");
        }
        var session = CanonicalRefinementSession.greenfield(PACK, "./ui.pack", "Build an interactive counter", 12, 3).useConstructionApi();
        String request = session.nextRequestJson();
        check(toolNames(request).equals(List.of("construct_apply_deal_batch")), "one source-free batch must replace bootstrap rounds");
        check(stringField(object(request), "reasoningEffort").equals("low"), "baseline reasoning must stay explicit");
        var fastSession = CanonicalRefinementSession.greenfield(PACK, "./ui.pack", "Counter", 8, 2)
                .useConstructionApi().withReasoningEffort("none", "none");
        check(stringField(object(fastSession.nextRequestJson()), "reasoningEffort").equals("none"), "reasoning ablation must be configurable");
        expectRejected(() -> session.acceptToolCallJson("apply_deal_foundation", "{}"));
        var foundation = List.of(
                cc("zero", "integer", Map.of("value", 0)),
                cc("initialTitle", "text", Map.of("value", "Counter")),
                cc("stateType", "declareRecord", Map.of("name", "AppState", "fields", List.of(
                        Map.of("name", "count", "type", "int", "value", "zero"), Map.of("name", "title", "type", "string", "value", "initialTitle")))),
                cc("initialRecord", "record", Map.of("fields", List.of(Map.of("name", "count", "value", "zero"), Map.of("name", "title", "value", "initialTitle")))),
                cc("initialReturn", "return", Map.of("value", "initialRecord")), cc("init", "block", Map.of("statements", List.of("initialReturn"))));
        expectRejected(() -> session.acceptToolCallJson("construct_apply_deal_batch", construction(foundation, Map.of(
                "supportingDeclarations", List.of(), "capabilities", List.of(), "appStateDeclaration", "export class AppState {}", "initialStateBody", "init",
                "actionHandlers", List.of(), "final", true))));
        check(request.equals(session.nextRequestJson()), "malformed constructor must not mutate the workspace");
        var behavior = List.of(
                cc("actionType", "declareRecord", Map.of("name", "IncrementAction", "fields", List.of())),
                cc("state", "reference", Map.of("name", "state")),
                cc("count", "field", Map.of("object", "state", "name", "count")),
                cc("title", "field", Map.of("object", "state", "name", "title")),
                cc("one", "integer", Map.of("value", 1)),
                cc("sum", "binary", Map.of("operator", "+", "left", "count", "right", "one")),
                cc("updated", "record", Map.of("fields", List.of(Map.of("name", "count", "value", "sum"), Map.of("name", "title", "value", "title")))),
                cc("ret", "return", Map.of("value", "updated")), cc("body", "block", Map.of("statements", List.of("ret"))),
                cc("handler", "declareUpdate", Map.of("name", "increment", "parameters", List.of(
                        Map.of("name", "state", "type", "AppState"), Map.of("name", "action", "type", "IncrementAction")), "returns", "AppState", "body", "body")));
        var batch = new java.util.ArrayList<Map<String, Object>>(foundation);
        batch.addAll(behavior);
        var batchArguments = Map.<String, Object>of("supportingDeclarations", List.of(), "capabilities", List.of(),
                "appStateDeclaration", "stateType", "initialStateBody", "init",
                "actionHandlers", List.of(Map.of("actionDeclaration", "actionType", "handlerDeclaration", "handler")), "final", true);
        String ui = session.acceptToolCallJson("construct_apply_deal_batch", construction(batch, batchArguments));
        var argumentSession = CanonicalRefinementSession.greenfield(PACK, "./ui.pack", "Counter", 12, 3).useConstructionApi();
        String beforeArguments = argumentSession.nextRequestJson();
        var invalidArguments = new java.util.ArrayList<Map<String, Object>>(batch);
        invalidArguments.set(4, cc("initialReturn", "return", Map.of("value", Map.of("id", "initialRecord"))));
        String pendingArguments = argumentSession.acceptToolCallJson("construct_apply_deal_batch", construction(invalidArguments, batchArguments));
        check(toolNames(pendingArguments).equals(List.of("patch_tool_argument")), "schema failure narrows to one argument instead of replaying batch");
        check(CompilerProtocolJson.intField(object(pendingArguments), "semanticRepairs") == 0,
                "argument staging consumes no semantic repair");
        check(field(object(beforeArguments), "revision").equals(field(object(pendingArguments), "revision")),
                "argument staging does not modify canonical revision");
        var grantedPatch = CompilerProtocolJson.requireObject(requireArray(field(object(pendingArguments), "tools"), "tools").items().getFirst(), "tool");
        var patchTool = CompilerProtocolJson.requireObject(field(grantedPatch, "parameters"), "parameters");
        var patchProps = CompilerProtocolJson.requireObject(field(patchTool, "properties"), "properties");
        String ticket = stringField(CompilerProtocolJson.requireObject(field(patchProps, "ticket"), "ticket"), "const");
        String afterArguments = argumentSession.acceptToolCallJson("patch_tool_argument",
                CompilerProtocolJson.encode(Map.of("ticket", ticket, "replacement", "initialRecord")));
        check(toolNames(afterArguments).contains("construct_apply_deal_ui_changes"), "repaired original batch compiles and advances to UI");
        check(CompilerProtocolJson.intField(object(afterArguments), "semanticRepairs") == 0,
                "argument repair remains distinct from semantic repair");
        var constructorSession = CanonicalRefinementSession.greenfield(PACK, "./ui.pack", "Build an interactive counter", 12, 3).useConstructionApi();
        constructorSession.nextRequestJson();
        var wrongKind = new java.util.ArrayList<Map<String, Object>>(batch);
        wrongKind.set(foundation.size(), cc("actionType", "record", Map.of("fields", List.of())));
        String wrongEnvelope = construction(wrongKind, batchArguments);
        String validation = constructorSession.validateToolCallsJson(CompilerProtocolJson.encode(List.of(Map.of(
                "name", "construct_apply_deal_batch", "arguments", object(wrongEnvelope)))));
        check(validation.contains("\"valid\":true"), "constructor type mismatch is not a transport failure");
        String constructorRepair = constructorSession.acceptToolCallJson("construct_apply_deal_batch", wrongEnvelope);
        check(toolNames(constructorRepair).equals(List.of("construct_repair_call")), "only narrow constructor repair may be offered");
        check(stringField(object(constructorRepair), "input").contains("DECLARATION"), "typed diagnostic must reach the model");
        expectRejected(() -> constructorSession.acceptToolCallJson("construct_repair_call", CompilerProtocolJson.encode(Map.of(
                "calls", List.of(behavior.get(0), cc("stateType", "declareRecord", Map.of("name", "AppState", "fields", List.of())))))));
        check(constructorRepair.equals(constructorSession.nextRequestJson()), "unauthorized sibling edits must not change repair state");
        check(!stringField(object(constructorRepair), "input").contains("consumerContract"), "constructor repair must not replay unrelated pack contracts");
        check(stringField(object(stringField(object(constructorRepair), "input")), "requiredArtifact").equals("deal"), "narrow repair must preserve artifact/model routing");
        String repeated = constructorSession.acceptToolCallJson("construct_repair_call", CompilerProtocolJson.encode(Map.of(
                "calls", List.of(wrongKind.get(foundation.size())))));
        check(stringField(object(repeated), "input").contains("NO_PROGRESS"), "identical repairs must not silently repeat identical model context");
        String constructorFixed = constructorSession.acceptToolCallJson("construct_repair_call", CompilerProtocolJson.encode(Map.of("calls", List.of(behavior.get(0), foundation.get(2)))));
        check(toolNames(constructorFixed).contains("construct_apply_deal_ui_changes"), "a single repaired declaration must resume the original batch");
        check(CompilerProtocolJson.encode(CompilerProtocolJson.field(object(constructorFixed), "revision")).equals(
                CompilerProtocolJson.encode(CompilerProtocolJson.field(object(ui), "revision"))), "constructor repair must preserve every sibling and original transaction argument");
        check(!toolNames(ui).contains("finish_deal"), "final batch must transition without a completion round");
        check(toolNames(ui).contains("construct_apply_deal_ui_changes"), "UI must have no source fallback");
        var sharedDeclarationSession = CanonicalRefinementSession.greenfield(PACK, "./ui.pack", "Build an interactive counter", 12, 3).useConstructionApi();
        sharedDeclarationSession.nextRequestJson();
        var sharedArguments = new java.util.LinkedHashMap<String, Object>(batchArguments);
        sharedArguments.put("supportingDeclarations", List.of("actionType", "handler"));
        var sharedCalls = new java.util.ArrayList<Map<String, Object>>(foundation);
        sharedCalls.addAll(behavior);
        String sharedResult = sharedDeclarationSession.acceptToolCallJson("construct_apply_deal_batch", construction(sharedCalls, sharedArguments));
        check(toolNames(sharedResult).contains("construct_apply_deal_ui_changes"), "shared declaration handles must be installed once, not trigger duplicate-symbol repair");
        check(stringField(object(ui), "reasoningEffort").equals("none"), "UI reasoning must remain independent");
        var uiInput = object(stringField(object(ui), "input"));
        var packContract = CompilerProtocolJson.requireObject(CompilerProtocolJson.field(uiInput, "componentPack"), "componentPack");
        var components = CompilerProtocolJson.requireArray(CompilerProtocolJson.field(packContract, "components"), "components").items();
        var expectedComponents = CanonicalCompiler.inspectCanonicalApp(DEAL, UI, PACK, "./ui.pack").componentPack().components();
        check(components.size() == expectedComponents.size(), "compact surface must retain every component");
        for (var expected : expectedComponents) {
            var actual = components.stream().map(value -> CompilerProtocolJson.requireObject(value, "component"))
                    .filter(value -> stringField(value, "name").equals(expected.name())).findFirst().orElseThrow();
            var props = CompilerProtocolJson.requireObject(CompilerProtocolJson.field(actual, "props"), "props");
            check(props.entries().size() == expected.properties().size(), "compact surface must retain every property");
            for (var property : expected.properties()) check(stringField(props,
                    property.name() + (property.optional() ? "?" : "")).equals(property.type()), "property type and optionality must round-trip");
            check(stringField(actual, "children").equals(expected.children()), "typed-child contract must remain exact");
        }
        var repairSession = CanonicalRefinementSession.greenfield(PACK, "./ui.pack", "Counter", 12, 3).useConstructionApi();
        repairSession.nextRequestJson();
        var invalidBatch = new java.util.ArrayList<Map<String, Object>>(batch);
        invalidBatch.set(foundation.size() + 5, cc("sum", "reference", Map.of("name", "missing")));
        String repair = repairSession.acceptToolCallJson("construct_apply_deal_batch", construction(invalidBatch, batchArguments));
        check(toolNames(repair).contains("construct_patch_repair_slot")
                && !toolNames(repair).contains("construct_apply_deal_batch"), "batch failure must expose scoped repair only: " + repair);
        var repairInput = object(stringField(object(repair), "input"));
        check(!stringField(object(repair), "instructions").contains("ONE construct_apply_deal_batch"),
                "scoped repair instructions must not tell the model to regenerate the full app");
        var omittedSlots = CompilerProtocolJson.requireArray(CompilerProtocolJson.field(repairInput, "otherPreservedSlots"), "omitted slots");
        check(!omittedSlots.items().isEmpty(), "unrelated slots must not be expanded into repair context");
        String omittedId = stringField(CompilerProtocolJson.requireObject(omittedSlots.items().get(0), "slot"), "slot");
        String queried = repairSession.acceptToolCallJson("query_repair_context", CompilerProtocolJson.encode(Map.of("slots", List.of(omittedId))));
        check(CompilerProtocolJson.intField(object(queried), "semanticRepairs") == CompilerProtocolJson.intField(object(repair), "semanticRepairs"),
                "read-only repair context query must not consume repair budget");
        check(!toolNames(queried).contains("construct_apply_deal_batch"), "context expansion must not grant sibling writes");
        String repaired = repairSession.acceptToolCallJson("construct_patch_repair_slot", construction(batch,
                Map.of("slot", "R5", "payload", Map.of("declaration", "handler"))));
        check(toolNames(repaired).contains("construct_apply_deal_ui_changes"), "repaired final batch must advance directly to UI: " + repaired);
        var nodes = new java.util.ArrayList<Map<String, Object>>(List.of(
                cc("label", "integer", Map.of("value", 7)),
                cc("text", "component", Map.of("name", "Text", "fields", List.of(Map.of("name", "value", "value", "label")), "children", List.of())),
                cc("action", "action", Map.of("name", "IncrementAction", "fields", List.of())),
                cc("buttonText", "text", Map.of("value", "Add")),
                cc("button", "component", Map.of("name", "Button", "fields", List.of(Map.of("name", "text", "value", "buttonText"), Map.of("name", "onClick", "value", "action")), "children", List.of())),
                cc("column", "component", Map.of("name", "Column", "fields", List.of(), "children", List.of("text", "button"))),
                cc("body", "uiBody", Map.of("children", List.of("column")))));
        String rejected = session.acceptToolCallJson("construct_apply_deal_ui_changes", construction(nodes, Map.of(
                "operations", List.of(Map.of("operation", "replaceViewBody", "body", "body")), "final", true)));
        check(rejected.contains("UI2031") && toolNames(rejected).contains("construct_patch_repair_slot"),
                "typed construction must still run semantic validation and expose source-free repair: " + rejected);
        nodes.set(0, cc("label", "text", Map.of("value", "Counter")));
        String complete = session.acceptToolCallJson("construct_patch_repair_slot", construction(nodes, Map.of(
                "slot", "R1", "payload", Map.of("body", "body"))));
        check(booleanField(object(complete), "accepted"), "correct construction must commit a canonical pair: " + complete);
        check(stringField(object(complete), "deal").contains("state.count + 1"), "compiler must emit the expression");
        var constructor = new deal.ui.CanonicalConstruction(false);
        String compact = constructor.build(object(CompilerProtocolJson.encode(Map.of("calls", List.of(
                cc("count", "path", Map.of("parts", List.of("state", "count"))),
                cc("sum", "binary", Map.of("left", "count", "operator", "+", "right", 1)),
                cc("out", "returnRecord", Map.of("fields", List.of(Map.of("name", "count", "value", "sum"), Map.of("name", "enabled", "value", true))))),
                "result", "out"))), deal.compiler.DealConstruction.Kind.BLOCK);
        check(compact.equals("return {count: (state.count + 1), enabled: true};"), "compact operands must preserve core projection");
        expectRejected(() -> new deal.ui.CanonicalConstruction(true).build(object(CompilerProtocolJson.encode(Map.of("calls", List.of(
                cc("p", "path", Map.of("parts", List.of("state", "items[0]")))), "result", "p"))), deal.compiler.DealConstruction.Kind.VALUE));
        expectRejected(() -> new deal.ui.CanonicalConstruction(true).build(object(CompilerProtocolJson.encode(Map.of("calls", List.of(
                cc("p", "returnRecord", Map.of("fields", List.of()))), "result", "p"))), deal.compiler.DealConstruction.Kind.BLOCK));
        String forward = constructor.build(object(CompilerProtocolJson.encode(Map.of("calls", List.of(
                cc("b", "block", Map.of("statements", List.of("r"))),
                cc("r", "return", Map.of("value", "v")),
                cc("v", "integer", Map.of("value", 7))), "result", "b"))), deal.compiler.DealConstruction.Kind.BLOCK);
        check(forward.equals("return 7;"), "construction dependency order must not matter");
        expectRejected(() -> constructor.build(object(CompilerProtocolJson.encode(Map.of("calls", List.of(
                cc("x", "field", Map.of("object", "y", "name", "n")),
                cc("y", "field", Map.of("object", "x", "name", "n"))), "result", "x"))), deal.compiler.DealConstruction.Kind.VALUE));
        expectRejected(() -> constructor.build(object(CompilerProtocolJson.encode(Map.of("calls", List.of(
                cc("bad", "reference", Map.of("name", "state; return 5;"))), "result", "bad"))), deal.compiler.DealConstruction.Kind.VALUE));
        expectRejected(() -> constructor.build(object(CompilerProtocolJson.encode(Map.of("calls", List.of(
                cc("x", "integer", Map.of("value", 1)), cc("x", "integer", Map.of("value", 2))), "result", "x"))), deal.compiler.DealConstruction.Kind.VALUE));
    }

    private static void greenfieldFinalFalseKeepsBuildingDeal() {
        var session = CanonicalRefinementSession.greenfield(PACK, "./ui.pack", "Counter", 8, 2);
        session.nextRequestJson(); counterFoundation(session);
        String behavior = appendRun(session, "Run", "return state;");
        check(toolNames(behavior).contains("finish_deal"), "behavior requires explicit completion");
        String ui = session.acceptToolCallJson("finish_deal", CompilerProtocolJson.encode(Map.of("coveredActions", List.of("RunAction"), "reason", "Run covers interaction")));
        check(toolNames(ui).contains("apply_deal_ui_changes"), "completion exposes UI");
    }

    private static void greenfieldCompletesPartialBootstrapWithoutReopeningCommittedState() {
        var session = CanonicalRefinementSession.greenfield(PACK, "./ui.pack", "Counter", 8, 2);
        String initial = session.nextRequestJson();
        expectRejected(() -> session.acceptToolCallJson("apply_deal_foundation", CompilerProtocolJson.encode(Map.of("appStateDeclaration", "export class AppState {}"))));
        check(session.nextRequestJson().equals(initial), "incomplete foundation cannot partially commit");
        String next = counterFoundation(session);
        expectRejected(() -> counterFoundation(session));
        check(session.nextRequestJson().equals(next), "accepted foundation cannot be reopened");
    }

    private static void acceptedChangeSetResetsTheLocalRepairBudget() {
        var session = CanonicalRefinementSession.greenfield(PACK, "./ui.pack", "Controls", 12, 1);
        session.nextRequestJson(); counterFoundation(session);
        String first = appendRun(session, "One", "return missing;");
        check(toolNames(first).contains("patch_repair_slot"), "handler enters repair");
        session.acceptToolCallJson("patch_repair_slot", CompilerProtocolJson.encode(Map.of("slot", "R2", "payload", Map.of("declaration", "// @ui-update\nexport function onOne(state: AppState, action: OneAction): AppState { return state; }"))));
        String second = appendRun(session, "Two", "return missing;");
        check(toolNames(second).contains("patch_repair_slot"), "new transaction gets fresh repair budget");
        check(CompilerProtocolJson.intField(object(second), "semanticRepairs") == 2, "metrics retain both repairs");
    }

    private static void greenfieldNoOpBecomesScopedRepair() {
        var session = CanonicalRefinementSession.greenfield(PACK, "./ui.pack", "Counter", 8, 2);
        session.nextRequestJson(); String next = counterFoundation(session);
        expectRejected(() -> counterFoundation(session));
        check(session.nextRequestJson().equals(next), "foundation replay cannot consume semantic repair");
    }

    private static void acceptedRevisionCanBeConfirmedWithoutRewriting() {
        var session = new CanonicalRefinementSession(DEAL, UI, PACK, "./ui.pack", "Increase by two", 8, 2);
        String request = session.nextRequestJson();
        String body = "return {title: state.title, count: state.count + 2};";
        for (boolean finish : List.of(false, true)) {
            String target = dealBodyAlias(request, dealSymbolAlias(request, "update"));
            session.acceptToolCallJson("inspect_deal_change", CompilerProtocolJson.encode(Map.of(
                    "anchors", List.of(target), "requestedOperations", List.of("replaceFunctionBody"))));
            request = session.acceptToolCallJson("apply_deal_changes", operationArguments(Map.of(
                    "operation", "replaceFunctionBody", "body", body), finish));
        }
        check(booleanField(object(request), "accepted"), "final confirmation must keep the accepted revision: " + request);
        try {
            session.acceptToolCallJson("unchanged", "{}");
            throw new AssertionError("completed session accepted another call");
        } catch (IllegalStateException expected) { }
    }

    private static void refinementNoOpWritesBecomeScopedRepair() {
        var dealSession = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Improve scoring", 4, 2);
        String initial = dealSession.nextRequestJson();
        String appState = dealSymbolAlias(initial, "AppState");
        dealSession.acceptToolCallJson("inspect_deal_change", CompilerProtocolJson.encode(Map.of(
                "anchors", List.of(appState),
                "requestedOperations", List.of("replaceDeclaration"))));
        String dealRepair = dealSession.acceptToolCallJson("apply_deal_changes", operationArguments(Map.of(
                "operation", "replaceDeclaration",
                "declaration", "export class AppState { title: string = \"Ready\"; count: int = 0; }"), true));
        check(dealRepair.contains("\"tools\""), "DEAL no-op must remain repairable: " + dealRepair);
        check(toolNames(dealRepair).contains("apply_deal_changes") && dealRepair.contains("SC1002")
                        && dealRepair.contains("Must differ from the compiler-rejected previous value"),
                "a final refinement write with unchanged DEAL source must become scoped repair: " + dealRepair);

        var uiSession = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Improve the title", 4, 2);
        String uiInitial = uiSession.nextRequestJson();
        String text = uiNodeAlias(uiInitial, "ui.Text");
        uiSession.acceptToolCallJson("inspect_deal_ui_change", CompilerProtocolJson.encode(Map.of(
                "anchors", List.of(text),
                "requestedOperations", List.of("replaceSubtree"))));
        String uiRepair = uiSession.acceptToolCallJson("replace_deal_ui_subtree", CompilerProtocolJson.encode(Map.of(
                "target", text,
                "source", "ui.Text(value: state.title)",
                "final", true)));
        check(uiRepair.contains("\"tools\""), "Deal UI no-op must remain repairable: " + uiRepair);
        check(toolNames(uiRepair).contains("replace_deal_ui_subtree") && uiRepair.contains("SC1002")
                        && uiRepair.contains("Never resubmit the previous payload"),
                "a final refinement write with unchanged Deal UI source must become scoped repair: " + uiRepair);
    }

    private static void duplicateGreenfieldDeclarationsCanOnlyFinishDeal() {
        var session = CanonicalRefinementSession.greenfield(PACK, "./ui.pack", "Controls", 10, 2);
        session.nextRequestJson(); counterFoundation(session); appendRun(session, "Run", "return state;");
        String duplicate = appendRun(session, "Run", "return state;");
        check(!CompilerProtocolJson.encode(CompilerProtocolJson.field(object(duplicate), "tools")).contains("replaceFunctionBody"), "duplicate repair must not unlock unrelated bodies");
        check(duplicate.contains("finish_deal") || duplicate.contains("patch_repair_slot"), "duplicate stays locally repairable");
        var missingHandler = CanonicalRefinementSession.greenfield(PACK, "./ui.pack", "Controls", 10, 2);
        missingHandler.nextRequestJson();
        missingHandler.acceptToolCallJson("apply_deal_foundation", CompilerProtocolJson.encode(Map.of(
                "appStateDeclaration", "export class AppState { count: int = 0; }",
                "initialStateBody", "return {count: 0};", "capabilities", List.of(),
                "supportingDeclarations", List.of("export class Increment {}"))));
        String retry = missingHandler.acceptToolCallJson("append_deal_behavior", CompilerProtocolJson.encode(Map.of(
                "supportingDeclarations", List.of(), "final", false, "actionHandlers", List.of(Map.of(
                        "actionDeclaration", "export class Increment {}",
                        "handlerDeclaration", "// @ui-update\nexport function increment(state: AppState, action: Increment): AppState { return state; }")))));
        check(toolNames(retry).contains("apply_deal_changes"), "duplicate action without accepted handler must expose repair, not empty tools");
        check(!toolNames(retry).contains("finish_deal"), "missing handler must not be considered complete");
    }

    private static void stricterCheckedContractBecomesRepairInsteadOfShadowCrash() {
        var session = CanonicalRefinementSession.greenfield(PACK, "./ui.pack", "Controls", 10, 2);
        session.nextRequestJson(); counterFoundation(session);
        String repair = appendRun(session, "Run", "state.count = 1; return state;");
        check(toolNames(repair).contains("patch_repair_slot") && repair.contains("UI2050"), "borrowed mutation belongs to handler slot");
        String fixed = session.acceptToolCallJson("patch_repair_slot", CompilerProtocolJson.encode(Map.of("slot", "R2", "payload", Map.of("declaration", "// @ui-update\nexport function onRun(state: AppState, action: RunAction): AppState { return {title: state.title, count: 1}; }"))));
        check(!toolNames(fixed).contains("patch_repair_slot") && fixed.contains("RunAction"), "action survives handler repair");
    }

    private static void rejectedDealBodyNarrowsRepairAndRollsForward() {
        var session = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Increment by two", 6, 2);
        String initial = session.nextRequestJson();
        requireTypedConstants(CompilerProtocolJson.decode(initial));
        String update = dealSymbolAlias(initial, "update");
        String body = dealBodyAlias(initial, update);
        check(!initial.contains("deal-node:"), "agent surface must not expose compiler NodeIds");
        String applyRequest = session.acceptToolCallJson("inspect_deal_change", CompilerProtocolJson.encode(Map.of(
                "anchors", List.of(body), "requestedOperations", List.of("replaceFunctionBody"))));
        check(applyRequest.contains("Statements only; omit declaration signature and outer braces"),
                "query and write schema must state the function-body replacement contract");
        List<String> applyTools = toolNames(applyRequest);
        check(applyTools.contains("apply_deal_changes"), "query must advance to the DEAL apply phase");
        check(!applyTools.contains("query_deal_node"),
                "the loaded body alias must not be offered for repeated querying");
        String repairRequest = session.acceptToolCallJson("apply_deal_changes", operationArguments(Map.of(
                "operation", "replaceFunctionBody",
                "body", "return missing;"), true));
        check(repairRequest.contains("patch_repair_slot"), "repair must expose only the rejected slot tool");
        check(!repairRequest.contains("apply_deal_changes"), "repair must hide the broad transaction tool");
        check(!repairRequest.contains("query_deal_symbol"), "repair must hide unrelated query tools");
        check(repairRequest.contains("return missing"), "repair context must retain the rejected body");
        check(repairRequest.contains("activeSlot") && repairRequest.contains("R1"),
                "repair context must use a compiler-owned slot identity");
        CanonicalJson.Obj repairInput = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.decode(stringField(object(repairRequest), "input")), "repair input");
        CanonicalJson.Obj repairWorkspace = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.field(repairInput, "repairWorkspace"), "repair workspace");
        CanonicalJson.Obj activeSlot = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.field(repairWorkspace, "activeSlot"), "active repair slot");
        CanonicalJson.Obj diagnostics = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.field(activeSlot, "diagnostics"), "slot diagnostics");
        CanonicalJson.Arr examples = CompilerProtocolJson.requireArray(
                CompilerProtocolJson.field(diagnostics, "examples"), "diagnostic examples");
        CanonicalJson.Obj evidence = CompilerProtocolJson.requireObject(CompilerProtocolJson.field(
                CompilerProtocolJson.requireObject(examples.items().get(0), "diagnostic"), "context"), "evidence");
        check(stringField(evidence, "excerpt").contains("return missing"),
                "slot evidence must refer to rejected candidate, not previous accepted body");
        check(repairRequest.contains("compiler target " + body)
                        && stringField(activeSlot, "target").equals(body),
                "the narrow repair surface must identify the immutable compiler target");
        String repeated = session.acceptToolCallJson("patch_repair_slot", CompilerProtocolJson.encode(Map.of(
                "slot", "R1", "payload", Map.of("body", "return missing;"))));
        CanonicalJson.Value repairCount = CompilerProtocolJson.field(object(repeated), "semanticRepairs");
        check(repairCount instanceof CanonicalJson.Int value && value.value() == 1,
                "a byte-identical retry must not consume another semantic repair");
        check(repeated.contains("compiler_no_progress"),
                "an identical retry must receive an explicit compiler-owned no-progress result");
        String result = session.acceptToolCallJson("patch_repair_slot", CompilerProtocolJson.encode(Map.of(
                "slot", "R1", "payload", Map.of(
                        "body", "return {title: state.title, count: state.count + 2};"))));
        CanonicalJson.Obj object = object(result);
        check(booleanField(object, "accepted"), "repaired canonical revision must be accepted");
        check(stringField(object, "deal").contains("count + 2"), "accepted source must contain local repair");
        check(stringField(object, "dealUi").equals(UI), "unrelated Deal UI must remain byte-identical");
    }

    private static void uiOnlyChangeNeverTouchesDeal() {
        var session = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Use a static polished headline", 4, 1);
        String initial = session.nextRequestJson();
        String text = uiNodeAlias(initial, "ui.Text");
        session.acceptToolCallJson("inspect_deal_ui_change", CompilerProtocolJson.encode(Map.of(
                "anchors", List.of(text),
                "requestedOperations", List.of("replaceSubtree"))));
        String result = session.acceptToolCallJson("replace_deal_ui_subtree", CompilerProtocolJson.encode(Map.of(
                "target", text,
                "source", "ui.Text(value: \"Polished\")",
                "final", true)));
        CanonicalJson.Obj object = object(result);
        check(booleanField(object, "accepted"), "UI-only revision must be accepted");
        check(stringField(object, "deal").equals(DEAL), "UI-only revision must not touch DEAL");
        check(stringField(object, "dealUi").contains("value: \"Polished\""), "UI property must be changed");
    }

    private static void interfaceChangeUsesMinimalChildInsertion() {
        var session = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Add an undo action and control", 6, 2);
        String initial = session.nextRequestJson();
        String module = "M1";
        session.acceptToolCallJson("inspect_deal_change", CompilerProtocolJson.encode(Map.of(
                "anchors", List.of(module),
                "requestedOperations", List.of("addDeclaration"))));
        String uiRequest = session.acceptToolCallJson("add_deal_action_handler", CompilerProtocolJson.encode(Map.of(
                "actionDeclaration", "export class UndoAction {}",
                "handlerDeclaration", "// @ui-update\nexport function undo(state: AppState, action: UndoAction): AppState { return {title: state.title, count: state.count}; }",
                "final", true)));
        check(toolNames(uiRequest).contains("inspect_deal_ui_change"),
                "an interface change must ask the model to identify the minimal affected UI cone");
        check(!toolNames(uiRequest).contains("replace_deal_ui_view"),
                "an interface change must not automatically unlock a whole-view rewrite");
        String column = uiNodeAlias(uiRequest, "ui.Column");
        String write = session.acceptToolCallJson("inspect_deal_ui_change", CompilerProtocolJson.encode(Map.of(
                "anchors", List.of(column),
                "requestedOperations", List.of("replaceSubtree"))));
        check(toolNames(write).equals(List.of("replace_deal_ui_subtree", "query_ui_contracts")),
                "an unreachable new action must expose only replacement of the selected container subtree");
        String result = session.acceptToolCallJson("replace_deal_ui_subtree", CompilerProtocolJson.encode(Map.of(
                "target", column,
                "source", "ui.Column() {\n"
                        + "  ui.Text(value: state.title)\n"
                        + "  ui.Button(text: \"Undo\", onClick: action app.UndoAction {})\n"
                        + "  ui.Button(text: \"Add\", onClick: action app.IncrementAction {})\n"
                        + "}",
                "final", true)));
        check(booleanField(object(result), "accepted"),
                "minimal container subtree replacement must produce a checked canonical revision");
        check(stringField(object(result), "dealUi").contains("UndoAction"),
                "accepted Deal UI must contain the new binding");
    }

    private static void subtreeReplacementPreservesUnrelatedSiblings() {
        var session = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Make the title concise", 4, 1);
        String initial = session.nextRequestJson();
        String text = uiNodeAlias(initial, "ui.Text");
        String write = session.acceptToolCallJson("inspect_deal_ui_change", CompilerProtocolJson.encode(Map.of(
                "anchors", List.of(text),
                "requestedOperations", List.of("replaceSubtree"))));
        check(write.contains("parent") && write.contains("childCount"),
                "the compact UI cone must expose enough hierarchy to choose a local target");
        String result = session.acceptToolCallJson("replace_deal_ui_subtree", CompilerProtocolJson.encode(Map.of(
                "target", text,
                "source", "ui.Text(value: \"Score\")",
                "final", true)));
        CanonicalJson.Obj object = object(result);
        check(booleanField(object, "accepted"), "a local subtree replacement must compile");
        check(stringField(object, "dealUi").contains("ui.Text(value: \"Score\")"),
                "the target subtree must be replaced");
        check(stringField(object, "dealUi").contains("ui.Button(text: \"Add\""),
                "a sibling outside the target must remain byte-for-byte present");
        check(stringField(object, "deal").equals(DEAL),
                "a UI subtree replacement must not touch DEAL");
    }

    private static void subtreeWriteSurfaceOmitsCanonicalSourcesAndIndexes() {
        var session = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Make the title more prominent", 4, 1);
        String initial = session.nextRequestJson();
        check(initial.contains("replaceSubtree")
                        && !initial.contains("setProperty")
                        && !initial.contains("replaceViewBody")
                        && !initial.contains("insertChild"),
                "the UI inspect surface must allow only one-node subtree replacement");
        String text = uiNodeAlias(initial, "ui.Text");
        String write = session.acceptToolCallJson("inspect_deal_ui_change", CompilerProtocolJson.encode(Map.of(
                "anchors", List.of(text),
                "requestedOperations", List.of("replaceSubtree"))));
        CanonicalJson.Obj input = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.decode(stringField(object(write), "input")), "input");
        check(input.entries().stream().noneMatch(entry -> entry.key().equals("deal"))
                        && input.entries().stream().noneMatch(entry -> entry.key().equals("dealUi")),
                "a subtree write round must omit the complete DEAL and Deal UI indexes");
        CanonicalJson.Obj surface = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.field(input, "uiEditSurface"), "uiEditSurface");
        check(stringField(surface, "source").equals("ui.Text(value: state.title)"),
                "the write surface must expose only the selected canonical subtree");
        check(CompilerProtocolJson.encode(surface).contains("state.title")
                        && CompilerProtocolJson.encode(surface).contains("lexicalBindings")
                        && CompilerProtocolJson.encode(surface).contains("replaceSubtree"),
                "the write surface must retain state, compatible actions and its sole operation");
        check(!CompilerProtocolJson.encode(surface).contains("ui.Button(text: \\\"Add\\\"")
                        && !CompilerProtocolJson.encode(surface).contains("export class AppState"),
                "the write surface must not leak siblings or full canonical sources");
        check(toolNames(write).equals(List.of("replace_deal_ui_subtree", "query_ui_contracts")),
                "the selected subtree round must expose exactly one write tool: " + toolNames(write));
    }

    private static void rawDeclarationInsertionIsHiddenBehindSemanticTools() {
        var session = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Add a helper and an undo action", 6, 2);
        session.nextRequestJson();
        String write = session.acceptToolCallJson("inspect_deal_change", CompilerProtocolJson.encode(Map.of(
                "anchors", List.of("M1"),
                "requestedOperations", List.of("addDeclaration"))));
        check(toolNames(write).contains("add_deal_action_handler")
                        && toolNames(write).contains("add_deal_supporting_declaration"),
                "declaration insertion must be split into compact semantic tools");
        check(!write.contains("\"const\":\"addDeclaration\""),
                "the LLM must not see raw addDeclaration during refinement");
    }

    private static void missingUiContractsAreReadWithoutBroadeningGrants() {
        var session = new CanonicalRefinementSession(DEAL, UI, PACK, "./ui.pack", "Make the title a button", 5, 1);
        String initial = session.nextRequestJson();
        String target = uiNodeAlias(initial, "ui.Text");
        String selected = session.acceptToolCallJson("inspect_deal_ui_change", CompilerProtocolJson.encode(Map.of(
                "anchors", List.of(target), "requestedOperations", List.of("replaceSubtree"))));
        expectRejected(() -> session.acceptToolCallJson("replace_deal_ui_subtree", "{bad"));
        check(!booleanField(object(session.validateToolCallsJson("{bad")), "valid"), "transport preflight rejects malformed JSON");
        check(!booleanField(object(session.validateToolCallsJson(CompilerProtocolJson.encode(List.of(Map.of(
                "name", "query_deal_ui_document", "arguments", Map.of()))))), "valid"), "transport preflight rejects hidden tools");
        check(session.nextRequestJson().equals(selected), "malformed JSON cannot consume grants or rounds");
        String enriched = session.acceptToolCallJson("query_ui_contracts", CompilerProtocolJson.encode(Map.of(
                "components", List.of("Button"), "actions", List.of("IncrementAction"))));
        check(enriched.contains("requestedContracts") && enriched.contains("onClick"), "missing component event contract is available");
        check(toolNames(enriched).equals(List.of("replace_deal_ui_subtree")), "contract reads grant only the original subtree write");
        check(!stringField(object(enriched), "input").contains("ui.Button(text: \"Add\""), "contract read cannot reveal sibling source");
        String result = session.acceptToolCallJson("replace_deal_ui_subtree", CompilerProtocolJson.encode(Map.of(
                "target", target, "source", "ui.Button(text: state.title, onClick: action app.IncrementAction {})", "final", true)));
        check(booleanField(object(result), "accepted"), "new component substitution compiles against requested contract");
        check(stringField(object(result), "deal").equals(DEAL), "contract lookup and UI replacement preserve DEAL");
    }

    private static void uiViewQueryCanAddAView() {
        var session = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Add a reusable detail view", 4, 1);
        String initial = session.nextRequestJson();
        check(!toolNames(initial).contains("query_deal_ui_document"),
                "the compact surface must hide rich document inspection");
        String appView = uiViewAlias(initial, "App");
        expectRejected(() -> session.acceptToolCallJson(
                "query_deal_ui_view", CompilerProtocolJson.encode(Map.of("target", appView))));
        expectRejected(() -> session.acceptToolCallJson("apply_deal_ui_changes", operationArguments(Map.of(
                "operation", "addView", "target", "D1",
                "source", "export view Detail(state: app.AppState): View { ui.Text(value: state.title) }"), true)));
        check(session.nextRequestJson().equals(initial), "hidden view operations must not modify the session");
    }

    private static void unqueriedAliasCannotBeWritten() {
        var session = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Change only update", 4, 2);
        String initial = session.nextRequestJson();
        String update = dealSymbolAlias(initial, "update");
        String updateBody = dealBodyAlias(initial, update);
        String initialState = dealSymbolAlias(initial, "initialState");
        String unrelatedBody = dealBodyAlias(initial, initialState);
        session.acceptToolCallJson("inspect_deal_change", CompilerProtocolJson.encode(Map.of(
                "anchors", List.of(updateBody), "requestedOperations", List.of("replaceFunctionBody"))));
        String before = session.nextRequestJson();
        expectRejected(() -> session.acceptToolCallJson("apply_deal_changes", operationArguments(Map.of(
                "operation", "replaceFunctionBody",
                "target", unrelatedBody,
                "body", "return {title: \"Wrong\", count: 0};"), true)));
        check(session.nextRequestJson().equals(before), "ungranted target must not change the surface or budgets");
    }

    private static void batchedQueriesConsumeOneProviderRound() {
        var session = new CanonicalRefinementSession(DEAL, UI, PACK, "./ui.pack", "Polish the interface", 2, 1);
        String initial = session.nextRequestJson();
        var nodes = uiNodeAliases(initial).stream().limit(2).toList();
        var calls = nodes.stream().map(node -> Map.of(
                "name", "query_deal_ui_node",
                "arguments", Map.of("target", node))).toList();
        expectRejected(() -> session.acceptToolCallsJson(CompilerProtocolJson.encode(calls)));
        check(session.nextRequestJson().equals(initial), "ungranted query batch is rejected before any grant or budget changes");
    }

    private static void writePhaseHidesAllQueryTools() {
        var session = new CanonicalRefinementSession(DEAL, UI, PACK, "./ui.pack", "Polish the interface", 2, 1);
        String initial = session.nextRequestJson();
        String target = uiNodeAliases(initial).get(0);
        String request = session.acceptToolCallJson("inspect_deal_ui_change", CompilerProtocolJson.encode(Map.of(
                "anchors", List.of(target), "requestedOperations", List.of("replaceSubtree"))));
        check(toolNames(request).contains("replace_deal_ui_subtree"), "inspection grants subtree replacement");
        expectRejected(() -> session.acceptToolCallJson("query_deal_ui_node", CompilerProtocolJson.encode(Map.of("target", target))));
        check(session.nextRequestJson().equals(request), "hidden query cannot broaden the surface");
    }

    private static void dealBodyInspectionCanAddSiblingDeclarations() {
        var session = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Add a pause action and update the existing handler", 3, 1);
        String initial = session.nextRequestJson();
        String update = dealSymbolAlias(initial, "update");
        String body = dealBodyAlias(initial, update);
        String request = session.acceptToolCallJson("inspect_deal_change", CompilerProtocolJson.encode(Map.of(
                "anchors", List.of(body, "M1"), "requestedOperations", List.of("replaceFunctionBody", "addDeclaration"))));
        check(request.contains("add_deal_action_handler"),
                "body inspection must unlock a sibling declaration without exposing a second read phase");
        check(request.contains("replaceFunctionBody"),
                "body inspection must retain the targeted body replacement");
        check(toolNames(request).stream().noneMatch(name -> name.startsWith("query_")),
                "the combined write surface must still hide all query tools");
    }

    private static void interfaceChangeRequestsMinimalUiInspection() {
        var session = new CanonicalRefinementSession(DEAL, UI, PACK, "./ui.pack", "Add reset behavior", 3, 1);
        session.nextRequestJson();
        String dealEdit = session.acceptToolCallJson(
                "inspect_deal_change", CompilerProtocolJson.encode(Map.of("anchors", List.of("M1"), "requestedOperations", List.of("addDeclaration"))));
        check(dealEdit.contains("constructors, methods"),
                "a DEAL edit surface must publish the relevant language subset");
        check(toolNames(dealEdit).contains("add_deal_action_handler"),
                "a writable module must expose the atomic action-handler Agent Surface");
        String request = session.acceptToolCallJson("add_deal_action_handler", CompilerProtocolJson.encode(Map.of(
                "actionDeclaration", "export class ResetAction {}",
                "handlerDeclaration", "// @ui-update\nexport function reset(state: AppState, action: ResetAction): AppState { return {title: state.title, count: 0}; }",
                "final", true)));
        check(toolNames(request).contains("inspect_deal_ui_change"),
                "an interface change must expose compiler-owned UI change inspection");
        check(!toolNames(request).contains("apply_deal_ui_changes")
                        && !toolNames(request).contains("replace_deal_ui_view"),
                "an interface change must not pre-authorize a broad UI write");
        check(request.contains("childCount") && request.contains("parent"),
                "the UI index must expose compact hierarchy for selecting the smallest cone");
        check(request.contains("declarative and read-only"),
                "the UI inspection phase must publish the Deal UI contract");
    }

    private static void diagnosticCompactionPreservesLocationsAndEvidence() {
        var diagnostics = new java.util.ArrayList<deal.compiler.CompilerProtocol.StructuredDiagnostic>();
        for (int line = 1; line <= 7; line++) {
            diagnostics.add(new deal.compiler.CompilerProtocol.StructuredDiagnostic("E1015", "error", "Expected ')'",
                    new deal.compiler.CompilerProtocol.SourceRange("app.deal", line, 2, line, 3),
                    new deal.compiler.CompilerProtocol.SemanticId("test"), ")", "unexpected",
                    List.of(), List.of(), "", null,
                    List.of(new deal.diagnostics.DiagnosticNote("expected/found evidence", null)))
                    .withSourceContext("bad\n".repeat(8)));
        }
        diagnostics.add(diagnostics.get(0));
        var compact = CanonicalRefinementSession.compactDiagnostics(diagnostics);
        check(compact.get("uniqueCount").equals(7) && compact.get("omittedCount").equals(1),
                "same code/message at different locations must not silently collapse");
        var encoded = CompilerProtocolJson.encode(compact);
        check(encoded.contains("diagnostic-context-v1") && encoded.contains("expected/found evidence")
                        && encoded.contains("severity"), "evidence and severity must reach the agent");
        check(!encoded.contains("sourceDigest") && !encoded.contains("startLine")
                        && !encoded.contains("ownerId"),
                "rich diagnostic coordinates and identities must stay outside the agent surface");
    }

    private static String operationArguments(Map<String, Object> operation, boolean finalChange) {
        return operationArguments(List.of(operation), finalChange);
    }

    private static void expectRejected(Runnable call) {
        try { call.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("An ungranted or malformed call was accepted");
    }

    private static String counterFoundation(CanonicalRefinementSession session) {
        return session.acceptToolCallJson("apply_deal_foundation", CompilerProtocolJson.encode(Map.of(
                "supportingDeclarations", List.of(), "capabilities", List.of(),
                "appStateDeclaration", "export class AppState { title: string = \"\"; count: int = 0; }",
                "initialStateBody", "return {title: \"Ready\", count: 0};")));
    }

    private static String appendRun(CanonicalRefinementSession session, String name, String body) {
        return session.acceptToolCallJson("append_deal_behavior", CompilerProtocolJson.encode(Map.of(
                "supportingDeclarations", List.of(), "actionHandlers", List.of(Map.of(
                        "actionDeclaration", "export class " + name + "Action {}",
                        "handlerDeclaration", "// @ui-update\nexport function on" + name + "(state: AppState, action: " + name + "Action): AppState { " + body + " }")),
                "final", false)));
    }

    private static String operationArguments(List<Map<String, Object>> operations, boolean finalChange) {
        return CompilerProtocolJson.encode(Map.of("operations", operations, "final", finalChange));
    }

    private static String dealSymbolAlias(String request, String name) {
        CanonicalJson.Obj deal = inputObject(request, "deal");
        CanonicalJson.Arr symbols = CompilerProtocolJson.requireArray(
                CompilerProtocolJson.field(deal, "symbols"), "symbols");
        return symbols.items().stream()
                .map(value -> CompilerProtocolJson.requireObject(value, "symbol"))
                .filter(value -> stringField(value, "name").equals(name))
                .map(value -> stringField(value, "target"))
                .findFirst().orElseThrow();
    }

    private static String dealBodyAlias(String request, String ownerAlias) {
        CanonicalJson.Obj deal = inputObject(request, "deal");
        CanonicalJson.Arr nodes = CompilerProtocolJson.requireArray(
                CompilerProtocolJson.field(deal, "nodes"), "nodes");
        return nodes.items().stream()
                .map(value -> CompilerProtocolJson.requireObject(value, "node"))
                .filter(value -> stringField(value, "owner").equals(ownerAlias))
                .filter(value -> stringField(value, "kind").equals("function-body"))
                .map(value -> stringField(value, "target"))
                .findFirst().orElseThrow();
    }

    private static String uiNodeAlias(String request, String component) {
        CanonicalJson.Obj dealUi = inputObject(request, "dealUi");
        CanonicalJson.Arr nodes = CompilerProtocolJson.requireArray(
                CompilerProtocolJson.field(dealUi, "nodes"), "nodes");
        return nodes.items().stream()
                .map(value -> CompilerProtocolJson.requireObject(value, "node"))
                .filter(value -> stringField(value, "component").equals(component))
                .map(value -> stringField(value, "target"))
                .findFirst().orElseThrow();
    }

    private static String uiViewAlias(String request, String name) {
        CanonicalJson.Obj dealUi = inputObject(request, "dealUi");
        CanonicalJson.Arr views = CompilerProtocolJson.requireArray(
                CompilerProtocolJson.field(dealUi, "views"), "views");
        return views.items().stream()
                .map(value -> CompilerProtocolJson.requireObject(value, "view"))
                .filter(value -> stringField(value, "name").equals(name))
                .map(value -> stringField(value, "target"))
                .findFirst().orElseThrow();
    }

    private static List<String> uiNodeAliases(String request) {
        CanonicalJson.Obj dealUi = inputObject(request, "dealUi");
        CanonicalJson.Arr nodes = CompilerProtocolJson.requireArray(
                CompilerProtocolJson.field(dealUi, "nodes"), "nodes");
        return nodes.items().stream()
                .map(value -> CompilerProtocolJson.requireObject(value, "node"))
                .map(value -> stringField(value, "target"))
                .toList();
    }

    private static CanonicalJson.Obj inputObject(String request, String field) {
        String input = stringField(object(request), "input");
        CanonicalJson.Obj context = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.decode(input), "agent input");
        return CompilerProtocolJson.requireObject(CompilerProtocolJson.field(context, field), field);
    }

    private static CanonicalJson.Obj object(String source) {
        return CompilerProtocolJson.requireObject(CompilerProtocolJson.decode(source), "result");
    }

    private static String stringField(CanonicalJson.Obj object, String name) {
        return CompilerProtocolJson.stringField(object, name);
    }

    private static boolean booleanField(CanonicalJson.Obj object, String name) {
        CanonicalJson.Value value = CompilerProtocolJson.field(object, name);
        return value instanceof CanonicalJson.Bool flag && flag.value();
    }

    private static List<String> toolNames(String request) {
        CanonicalJson.Arr tools = CompilerProtocolJson.requireArray(
                CompilerProtocolJson.field(object(request), "tools"), "tools");
        return tools.items().stream()
                .map(value -> CompilerProtocolJson.requireObject(value, "tool"))
                .map(tool -> CompilerProtocolJson.stringField(tool, "name"))
                .toList();
    }

    private static void requireTypedConstants(CanonicalJson.Value value) {
        if (value instanceof CanonicalJson.Obj object) {
            check(object.entries().stream().noneMatch(entry -> entry.key().equals("oneOf")),
                    "DeepSeek tool schemas require anyOf rather than oneOf");
            boolean hasConst = object.entries().stream().anyMatch(entry -> entry.key().equals("const"));
            boolean hasType = object.entries().stream().anyMatch(entry -> entry.key().equals("type"));
            check(!hasConst || hasType, "provider tool-schema constants require an explicit type");
            object.entries().forEach(entry -> requireTypedConstants(entry.value()));
        } else if (value instanceof CanonicalJson.Arr array) {
            array.items().forEach(CanonicalRefinementSessionTest::requireTypedConstants);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
