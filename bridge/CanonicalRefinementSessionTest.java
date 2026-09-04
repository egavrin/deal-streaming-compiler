package streaming.compiler;

import deal.compiler.CompilerProtocolJson;
import deal.semantic.ir.CanonicalJson;
import deal.ui.CanonicalCompiler;

import java.util.List;
import java.util.Map;

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
            export component Column(props: ColumnProps): View { children optional; }
            export component Text(props: TextProps): View;
            export component Button(props: ButtonProps): View { event onClick; }
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
        rejectedDealBodyNarrowsRepairAndRollsForward();
        unqueriedAliasCannotBeWritten();
        batchedQueriesConsumeOneProviderRound();
        uiOnlyChangeNeverTouchesDeal();
        uiDocumentQueryCanAddAView();
        greenfieldBuildsDealBeforeDealUi();
        greenfieldCompletesPartialBootstrapWithoutReopeningCommittedState();
        greenfieldFinalFalseKeepsBuildingDeal();
        acceptedChangeSetResetsTheLocalRepairBudget();
        greenfieldNoOpBecomesScopedRepair();
        stricterCheckedContractBecomesRepairInsteadOfShadowCrash();
        duplicateGreenfieldDeclarationsCanOnlyFinishDeal();
        System.out.println("CanonicalRefinementSessionTest: all tests passed");
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
        check(toolNames(initial).contains("query_deal_module"),
                "greenfield bootstrap must permit supporting nominal record declarations");
        String appState = dealSymbolAlias(initial, "AppState");
        String initialState = dealSymbolAlias(initial, "initialState");
        String initialBody = dealBodyAlias(initial, initialState);
        String foundationRequest = session.acceptToolCallsJson(CompilerProtocolJson.encode(List.of(
                Map.of("name", "query_deal_symbol", "arguments", Map.of("target", appState)),
                Map.of("name", "query_deal_node", "arguments", Map.of("target", initialBody)),
                Map.of("name", "query_deal_module", "arguments", Map.of("target", "M1")))));
        check(toolNames(foundationRequest).contains("apply_deal_foundation"),
                "bootstrap must collapse rich ChangeSet operations into one compact agent handle");
        check(!toolNames(foundationRequest).contains("apply_deal_changes"),
                "bootstrap must not expose the generic operation union");
        String declarations = session.acceptToolCallJson("apply_deal_foundation", CompilerProtocolJson.encode(Map.of(
                "supportingDeclarations", List.of(
                        "export class Item { id: int = 0; label: string = \"\"; }"),
                "appStateDeclaration", "export class AppState { count: int = 0; items: Item[] = []; }",
                "initialStateBody", "return {count: 0, items: []};")));
        session.acceptToolCallJson("query_deal_module", CompilerProtocolJson.encode(Map.of("target", "M1")));
        String dealAccepted = session.acceptToolCallJson("apply_deal_changes", operationArguments(List.of(
                Map.of("operation", "addDeclaration", "target", "M1",
                        "declaration", "export class IncrementAction {}"),
                Map.of("operation", "addDeclaration", "target", "M1",
                        "declaration", "// @ui-update\nexport function increment(state: AppState, action: IncrementAction): AppState { return {count: state.count + 1}; }")),
                true));
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
        check(dealAccepted.contains("ForEach(state.items, item: app.Item, key: item.id)"),
                "the Deal UI contract must publish the exact collection syntax");
        String view = uiViewAlias(dealAccepted, "App");
        String uiWrite = session.acceptToolCallJson(
                "query_deal_ui_view", CompilerProtocolJson.encode(Map.of("target", view)));
        String result = session.acceptToolCallJson("apply_deal_ui_changes", operationArguments(List.of(
                Map.of("operation", "replaceViewBody", "target", view,
                        "body", "ui.Column() { ui.Text(value: \"Counter\") ui.Button(text: \"Add\", onClick: action app.IncrementAction {}) }")),
                true));
        CanonicalJson.Obj object = object(result);
        check(booleanField(object, "accepted"), "greenfield canonical app must complete");
        check(stringField(object, "deal").contains("IncrementAction"), "generated DEAL must be retained");
        check(stringField(object, "dealUi").contains("ui.Button"), "generated Deal UI must be retained");
    }

    private static void greenfieldFinalFalseKeepsBuildingDeal() {
        var session = CanonicalRefinementSession.greenfield(
                PACK, "./ui.pack", "Create a complex app", 6, 2);
        String initial = session.nextRequestJson();
        String appState = dealSymbolAlias(initial, "AppState");
        String initialState = dealSymbolAlias(initial, "initialState");
        String initialBody = dealBodyAlias(initial, initialState);
        session.acceptToolCallsJson(CompilerProtocolJson.encode(List.of(
                Map.of("name", "query_deal_symbol", "arguments", Map.of("target", appState)),
                Map.of("name", "query_deal_node", "arguments", Map.of("target", initialBody)))));
        String next = session.acceptToolCallJson("apply_deal_changes", operationArguments(List.of(
                Map.of("operation", "replaceDeclaration", "target", appState,
                        "declaration", "export class AppState { count: int = 0; }"),
                Map.of("operation", "replaceFunctionBody", "target", initialBody,
                        "body", "return {count: 0};")), false));
        CanonicalJson.Obj input = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.decode(stringField(object(next), "input")), "input");
        check(stringField(input, "requiredArtifact").equals("deal"),
                "final=false must keep complex greenfield generation in DEAL");
        check(toolNames(next).contains("query_deal_module"),
                "the next DEAL revision must expose a fresh compiler surface");
        check(!toolNames(next).contains("query_deal_symbol"),
                "completed bootstrap declarations must become read-only index entries");
        check(!toolNames(next).contains("query_deal_node"),
                "completed bootstrap bodies must not distract later greenfield rounds");
        check(stringField(input, "generationStage").equals("declarations"),
                "the compact input must identify the declarations-only stage");
        check(stringField(input, "stageObjective").contains("committed"),
                "the compact input must state that bootstrap symbols are immutable");
        check(toolNames(next).contains("finish_deal"),
                "declarations stage must allow a source-free transition to Deal UI");
        String declarationsWrite = session.acceptToolCallJson(
                "query_deal_module", CompilerProtocolJson.encode(Map.of("target", "M1")));
        check(declarationsWrite.contains("Bootstrap is committed"),
                "the write tool must explain its narrowed generation stage");
        check(!next.contains("\"operation\":{\"const\":\"replaceDeclaration\"}"),
                "accepted AppState bootstrap must not be replaceable again");
        check(!next.contains("\"operation\":{\"const\":\"replaceFunctionBody\"}"),
                "accepted initialState bootstrap must not be replaceable again");
        check(!toolNames(next).contains("query_deal_ui_view"),
                "Deal UI must remain hidden until DEAL final=true");
        String ui = session.acceptToolCallJson(
                "finish_deal", CompilerProtocolJson.encode(Map.of("reason", "Behavior is complete")));
        check(toolNames(ui).contains("query_deal_ui_view"),
                "finish_deal must expose Deal UI without another DEAL mutation");
    }

    private static void greenfieldCompletesPartialBootstrapWithoutReopeningCommittedState() {
        var session = CanonicalRefinementSession.greenfield(
                PACK, "./ui.pack", "Create a complex app", 6, 2);
        String initial = session.nextRequestJson();
        String appState = dealSymbolAlias(initial, "AppState");
        session.acceptToolCallJson("query_deal_symbol", CompilerProtocolJson.encode(Map.of(
                "target", appState)));
        String next = session.acceptToolCallJson("apply_deal_changes", operationArguments(List.of(
                Map.of("operation", "replaceDeclaration", "target", appState,
                        "declaration", "export class AppState { title: string = \"\"; count: int = 0; }")), false));
        CanonicalJson.Obj input = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.decode(stringField(object(next), "input")), "input");
        check(stringField(input, "generationStage").equals("initial-state"),
                "partial bootstrap must name its missing unit");
        check(!toolNames(next).contains("query_deal_module"),
                "partial bootstrap must not unlock unrelated declarations");
        check(!next.contains("\"enum\":[\"" + appState + "\"]"),
                "committed AppState must not remain queryable");
        check(toolNames(next).contains("query_deal_node"),
                "the missing initialState body must remain queryable");
        check(stringField(input, "stageObjective").contains("initialState"),
                "partial bootstrap must identify the one missing unit");
    }

    private static void acceptedChangeSetResetsTheLocalRepairBudget() {
        var session = CanonicalRefinementSession.greenfield(
                PACK, "./ui.pack", "Create a complex app", 12, 1);
        String initial = session.nextRequestJson();
        String appState = dealSymbolAlias(initial, "AppState");
        String initialState = dealSymbolAlias(initial, "initialState");
        String initialBody = dealBodyAlias(initial, initialState);
        session.acceptToolCallsJson(CompilerProtocolJson.encode(List.of(
                Map.of("name", "query_deal_symbol", "arguments", Map.of("target", appState)),
                Map.of("name", "query_deal_node", "arguments", Map.of("target", initialBody)))));
        String declarations = session.acceptToolCallJson("apply_deal_changes", operationArguments(List.of(
                Map.of("operation", "replaceDeclaration", "target", appState,
                        "declaration", "export class AppState { title: string = \"\"; count: int = 0; }"),
                Map.of("operation", "replaceFunctionBody", "target", initialBody,
                        "body", "return {title: \"Ready\", count: 0};")), false));
        session.acceptToolCallJson("query_deal_module", CompilerProtocolJson.encode(Map.of("target", "M1")));
        String firstRepair = session.acceptToolCallJson("apply_deal_changes", operationArguments(List.of(
                Map.of("operation", "addDeclaration", "target", "M1",
                        "declaration", "export class One {}\nexport class Two {}")), false));
        check(stringField(object(firstRepair), "status").equals("request"),
                "the first invalid ChangeSet must request a local repair");
        String accepted = session.acceptToolCallJson("apply_deal_changes", operationArguments(List.of(
                Map.of("operation", "addDeclaration", "target", "M1", "declaration", "export class One {}"),
                Map.of("operation", "addDeclaration", "target", "M1", "declaration", "export class Two {}")), false));
        check(stringField(object(accepted), "status").equals("request"),
                "an accepted repair must continue greenfield generation");
        session.acceptToolCallJson("query_deal_module", CompilerProtocolJson.encode(Map.of("target", "M1")));
        String secondRepair = session.acceptToolCallJson("apply_deal_changes", operationArguments(List.of(
                Map.of("operation", "addDeclaration", "target", "M1",
                        "declaration", "export class Three {}\nexport class Four {}")), false));
        check(stringField(object(secondRepair), "status").equals("request"),
                "a later ChangeSet must receive a fresh local repair budget");
        CanonicalJson.Value repairCount = CompilerProtocolJson.field(object(secondRepair), "semanticRepairs");
        check(repairCount instanceof CanonicalJson.Int value && value.value() == 2,
                "global repair metrics must retain errors from both ChangeSets");
    }

    private static void greenfieldNoOpBecomesScopedRepair() {
        var session = CanonicalRefinementSession.greenfield(
                PACK, "./ui.pack", "Create a complex app", 6, 2);
        String initial = session.nextRequestJson();
        String appState = dealSymbolAlias(initial, "AppState");
        session.acceptToolCallJson("query_deal_symbol", CompilerProtocolJson.encode(Map.of(
                "target", appState)));
        String repair = session.acceptToolCallJson("apply_deal_changes", operationArguments(List.of(
                Map.of("operation", "replaceDeclaration", "target", appState,
                        "declaration", "export class AppState { title: string = \"\"; }")), false));
        check(stringField(object(repair), "status").equals("request"),
                "a greenfield no-op must request a repair");
        CanonicalJson.Obj input = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.decode(stringField(object(repair), "input")), "input");
        check(CompilerProtocolJson.field(input, "repairDirective") instanceof CanonicalJson.Obj,
                "a greenfield no-op must expose an explicit repair directive");
        CanonicalJson.Value repairCount = CompilerProtocolJson.field(object(repair), "semanticRepairs");
        check(repairCount instanceof CanonicalJson.Int value && value.value() == 1,
                "a no-op must consume the current ChangeSet repair budget");
    }

    private static void duplicateGreenfieldDeclarationsCanOnlyFinishDeal() {
        var session = CanonicalRefinementSession.greenfield(
                PACK, "./ui.pack", "Create an app", 8, 2);
        String initial = session.nextRequestJson();
        String appState = dealSymbolAlias(initial, "AppState");
        String initialState = dealSymbolAlias(initial, "initialState");
        String initialBody = dealBodyAlias(initial, initialState);
        session.acceptToolCallsJson(CompilerProtocolJson.encode(List.of(
                Map.of("name", "query_deal_symbol", "arguments", Map.of("target", appState)),
                Map.of("name", "query_deal_node", "arguments", Map.of("target", initialBody)))));
        session.acceptToolCallJson("apply_deal_changes", operationArguments(List.of(
                Map.of("operation", "replaceDeclaration", "target", appState,
                        "declaration", "export class AppState { title: string = \"Ready\"; }"),
                Map.of("operation", "replaceFunctionBody", "target", initialBody,
                        "body", "return {title: \"Ready\"};")), false));
        session.acceptToolCallJson("query_deal_module", CompilerProtocolJson.encode(Map.of("target", "M1")));
        session.acceptToolCallJson("apply_deal_changes", operationArguments(List.of(
                Map.of("operation", "addDeclaration", "target", "M1", "declaration", "export class RunAction {}"),
                Map.of("operation", "addDeclaration", "target", "M1", "declaration",
                        "// @ui-update\nexport function run(state: AppState, action: RunAction): AppState { return state; }")), false));
        session.acceptToolCallJson("query_deal_module", CompilerProtocolJson.encode(Map.of("target", "M1")));
        String repair = session.acceptToolCallJson("apply_deal_changes", operationArguments(List.of(
                Map.of("operation", "addDeclaration", "target", "M1", "declaration", "export class RunAction {}")), false));
        check(toolNames(repair).contains("finish_deal"),
                "duplicate-only greenfield repair must allow completion");
        check(!toolNames(repair).contains("apply_deal_changes"),
                "duplicate-only greenfield repair must not allow another addDeclaration");
    }

    private static void stricterCheckedContractBecomesRepairInsteadOfShadowCrash() {
        var session = CanonicalRefinementSession.greenfield(
                PACK, "./ui.pack", "Create an app", 8, 2);
        String initial = session.nextRequestJson();
        String appState = dealSymbolAlias(initial, "AppState");
        String initialState = dealSymbolAlias(initial, "initialState");
        String initialBody = dealBodyAlias(initial, initialState);
        session.acceptToolCallsJson(CompilerProtocolJson.encode(List.of(
                Map.of("name", "query_deal_symbol", "arguments", Map.of("target", appState)),
                Map.of("name", "query_deal_node", "arguments", Map.of("target", initialBody)))));
        session.acceptToolCallJson("apply_deal_changes", operationArguments(List.of(
                Map.of("operation", "replaceDeclaration", "target", appState,
                        "declaration", "export class AppState { title: string = \"Ready\"; }"),
                Map.of("operation", "replaceFunctionBody", "target", initialBody,
                        "body", "return {title: \"Ready\"};")), false));
        session.acceptToolCallJson("query_deal_module", CompilerProtocolJson.encode(Map.of("target", "M1")));
        String repair = session.acceptToolCallJson("apply_deal_changes", operationArguments(List.of(
                Map.of("operation", "addDeclaration", "target", "M1",
                        "declaration", "export class RunAction {}"),
                Map.of("operation", "addDeclaration", "target", "M1", "declaration", """
                        export function run(state: AppState, action: RunAction): AppState {
                          // @ui-update
                          return state;
                        }
                        """)), false));
        check(stringField(object(repair), "status").equals("request"),
                "a stricter checked-contract rejection must remain repairable");
        check(repair.contains("UI2050"),
                "the v2 framework diagnostic must reach the repair surface");
    }

    private static void rejectedDealBodyNarrowsRepairAndRollsForward() {
        var session = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Increment by two", 6, 2);
        String initial = session.nextRequestJson();
        requireTypedConstants(CompilerProtocolJson.decode(initial));
        String update = dealSymbolAlias(initial, "update");
        String body = dealBodyAlias(initial, update);
        check(!initial.contains("deal-node:"), "agent surface must not expose compiler NodeIds");
        String applyRequest = session.acceptToolCallJson("query_deal_node", CompilerProtocolJson.encode(Map.of(
                "target", body)));
        check(applyRequest.contains("Statements only; omit declaration signature and outer braces"),
                "query and write schema must state the function-body replacement contract");
        List<String> applyTools = toolNames(applyRequest);
        check(applyTools.contains("apply_deal_changes"), "query must advance to the DEAL apply phase");
        check(!applyRequest.contains("\"enum\":[\"" + body + "\"]"),
                "the loaded body alias must not be offered for repeated querying");
        String repairRequest = session.acceptToolCallJson("apply_deal_changes", operationArguments(Map.of(
                "operation", "replaceFunctionBody",
                "target", body,
                "body", "return missing;"), true));
        check(repairRequest.contains("apply_deal_changes"), "repair must retain the rejected transaction tool");
        check(!repairRequest.contains("query_deal_symbol"), "repair must hide unrelated query tools");
        check(repairRequest.contains("return missing"), "repair context must retain the rejected body");
        String result = session.acceptToolCallJson("apply_deal_changes", operationArguments(Map.of(
                "operation", "replaceFunctionBody",
                "target", body,
                "body", "return {title: state.title, count: state.count + 2};"), true));
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
        session.acceptToolCallJson("query_deal_ui_node", CompilerProtocolJson.encode(Map.of(
                "target", text)));
        String result = session.acceptToolCallJson("apply_deal_ui_changes", operationArguments(Map.of(
                "operation", "setProperty",
                "target", text,
                "property", "value",
                "expression", "\"Polished\""), true));
        CanonicalJson.Obj object = object(result);
        check(booleanField(object, "accepted"), "UI-only revision must be accepted");
        check(stringField(object, "deal").equals(DEAL), "UI-only revision must not touch DEAL");
        check(stringField(object, "dealUi").contains("value: \"Polished\""), "UI property must be changed");
    }

    private static void uiDocumentQueryCanAddAView() {
        var session = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Add a reusable detail view", 4, 1);
        String initial = session.nextRequestJson();
        check(toolNames(initial).contains("query_deal_ui_document"),
                "the compact surface must expose compiler-owned document inspection");
        String applyRequest = session.acceptToolCallJson(
                "query_deal_ui_document", CompilerProtocolJson.encode(Map.of("target", "D1")));
        check(toolNames(applyRequest).contains("apply_deal_ui_changes"),
                "document query must unlock the checked UI transaction");
        String result = session.acceptToolCallJson("apply_deal_ui_changes", operationArguments(Map.of(
                "operation", "addView",
                "target", "D1",
                "source", "export view Detail(state: app.AppState): View { ui.Text(value: state.title) }"), true));
        CanonicalJson.Obj object = object(result);
        check(booleanField(object, "accepted"), "a checked non-root view must be added");
        check(stringField(object, "dealUi").contains("export view Detail"),
                "the accepted canonical UI source must contain the added view");
        check(stringField(object, "deal").equals(DEAL), "adding a UI view must not touch DEAL");
    }

    private static void unqueriedAliasCannotBeWritten() {
        var session = new CanonicalRefinementSession(
                DEAL, UI, PACK, "./ui.pack", "Change only update", 4, 2);
        String initial = session.nextRequestJson();
        String update = dealSymbolAlias(initial, "update");
        String updateBody = dealBodyAlias(initial, update);
        String initialState = dealSymbolAlias(initial, "initialState");
        String unrelatedBody = dealBodyAlias(initial, initialState);
        session.acceptToolCallJson("query_deal_node", CompilerProtocolJson.encode(Map.of(
                "target", updateBody)));
        String rejected = session.acceptToolCallJson("apply_deal_changes", operationArguments(Map.of(
                "operation", "replaceFunctionBody",
                "target", unrelatedBody,
                "body", "return {title: \"Wrong\", count: 0};"), true));
        check(rejected.contains("CP1010"), "compiler must reject a write to an unqueried alias");
        check(rejected.contains(updateBody), "queried target must remain the only writable body");
    }

    private static void batchedQueriesConsumeOneProviderRound() {
        var session = new CanonicalRefinementSession(DEAL, UI, PACK, "./ui.pack", "Polish the interface", 2, 1);
        String initial = session.nextRequestJson();
        var nodes = uiNodeAliases(initial).stream().limit(2).toList();
        var calls = nodes.stream().map(node -> Map.of(
                "name", "query_deal_ui_node",
                "arguments", Map.of("target", node))).toList();
        String request = session.acceptToolCallsJson(CompilerProtocolJson.encode(calls));
        CanonicalJson.Obj object = object(request);
        check(CompilerProtocolJson.intField(object, "round") == 2,
                "one batched provider turn must advance the round exactly once");
        check(toolNames(request).contains("apply_deal_ui_changes"),
                "batched UI context must expose the targeted write transaction");
    }

    private static String operationArguments(Map<String, Object> operation, boolean finalChange) {
        return operationArguments(List.of(operation), finalChange);
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
