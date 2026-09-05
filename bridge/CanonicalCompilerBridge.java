package prototype;

import deal.compiler.CompilerProtocol.ChangeSetPrecondition;
import deal.compiler.CompilerProtocol.SemanticId;
import deal.compiler.CompilerProtocolJson;
import deal.compiler.DealCompilerWorkspace;
import deal.semantic.ir.CanonicalJson;
import deal.ui.CanonicalCompiler;
import deal.ui.UiCompilerWorkspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Process adapter for the platform-neutral transpiler API. */
public final class CanonicalCompilerBridge {
    private CanonicalCompilerBridge() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Missing compiler command");
        Object response = switch (args[0]) {
            case "handshake" -> CanonicalCompiler.handshake();
            case "inspect-deal" -> inspectDeal(args);
            case "query-deal-module" -> queryDealModule(args);
            case "query-deal-symbol" -> queryDealSymbol(args);
            case "query-deal-node" -> queryDealNode(args);
            case "apply-deal" -> applyDeal(args);
            case "apply-deal-checked" -> applyDealChecked(args);
            case "inspect-app", "compile-app" -> inspectApp(args);
            case "query-ui-document" -> queryUiDocument(args);
            case "query-ui-view" -> queryUiView(args);
            case "query-ui-node" -> queryUiNode(args);
            case "apply-ui" -> applyUi(args);
            case "apply-ui-checked" -> applyUiChecked(args);
            default -> throw new IllegalArgumentException("Unknown compiler command: " + args[0]);
        };
        System.out.print(CompilerProtocolJson.encode(response));
    }

    private static Object inspectDeal(String[] args) throws Exception {
        requireArgs(args, 2);
        return CanonicalCompiler.inspectCanonicalApp(
                read(args[1]), minimalUi(), minimalPack(), "./platform-ui.dealui-pack").deal();
    }

    private static Object applyDeal(String[] args) throws Exception {
        requireArgs(args, 4);
        return CanonicalCompiler.applyDealChange(
                read(args[1]), args[2], dealOperations(read(args[3])));
    }

    private static Object queryDealModule(String[] args) throws Exception {
        requireArgs(args, 2);
        return CanonicalCompiler.queryDealModule(read(args[1]));
    }

    private static Object queryDealSymbol(String[] args) throws Exception {
        requireArgs(args, 3);
        return CanonicalCompiler.queryDealSymbol(read(args[1]), new SemanticId(args[2]));
    }

    private static Object queryDealNode(String[] args) throws Exception {
        requireArgs(args, 3);
        return CanonicalCompiler.queryDealNode(read(args[1]), new SemanticId(args[2]));
    }

    private static Object applyDealChecked(String[] args) throws Exception {
        requireArgs(args, 5);
        return CanonicalCompiler.applyDealChangeChecked(
                read(args[1]),
                new ChangeSetPrecondition(args[2], stringMap(read(args[3]))),
                dealOperations(read(args[4])));
    }

    private static Object inspectApp(String[] args) throws Exception {
        requireArgs(args, 5);
        return CanonicalCompiler.compileCanonicalApp(
                read(args[1]), read(args[2]), read(args[3]), args[4]);
    }

    private static Object applyUi(String[] args) throws Exception {
        requireArgs(args, 7);
        return CanonicalCompiler.applyDealUiChange(
                read(args[1]), read(args[2]), read(args[3]), args[4], args[5], uiOperations(read(args[6])));
    }

    private static Object queryUiView(String[] args) throws Exception {
        requireArgs(args, 6);
        return CanonicalCompiler.queryDealUiView(
                read(args[1]), read(args[2]), read(args[3]), args[4], new SemanticId(args[5]));
    }

    private static Object queryUiDocument(String[] args) throws Exception {
        requireArgs(args, 5);
        return CanonicalCompiler.queryDealUiDocument(
                read(args[1]), read(args[2]), read(args[3]), args[4]);
    }

    private static Object queryUiNode(String[] args) throws Exception {
        requireArgs(args, 6);
        return CanonicalCompiler.queryDealUiNode(
                read(args[1]), read(args[2]), read(args[3]), args[4], new SemanticId(args[5]));
    }

    private static Object applyUiChecked(String[] args) throws Exception {
        requireArgs(args, 8);
        return CanonicalCompiler.applyDealUiChangeChecked(
                read(args[1]), read(args[2]), read(args[3]), args[4],
                new ChangeSetPrecondition(args[5], stringMap(read(args[6]))),
                uiOperations(read(args[7])));
    }

    private static Map<String, String> stringMap(String source) {
        CanonicalJson.Obj object = CompilerProtocolJson.requireObject(
                CompilerProtocolJson.decode(source), "fingerprints");
        Map<String, String> result = new LinkedHashMap<>();
        object.entries().forEach(entry -> {
            if (!(entry.value() instanceof CanonicalJson.Str value)) {
                throw new IllegalArgumentException("Fingerprint values must be strings");
            }
            result.put(entry.key(), value.value());
        });
        return Map.copyOf(result);
    }

    private static List<DealCompilerWorkspace.Operation> dealOperations(String source) {
        CanonicalJson.Arr values = CompilerProtocolJson.requireArray(
                CompilerProtocolJson.decode(source), "DEAL operations");
        List<DealCompilerWorkspace.Operation> result = new ArrayList<>();
        for (CanonicalJson.Value value : values.items()) {
            CanonicalJson.Obj operation = CompilerProtocolJson.requireObject(value, "DEAL operation");
            String name = CompilerProtocolJson.stringField(operation, "operation");
            SemanticId target = new SemanticId(CompilerProtocolJson.stringField(operation, "targetId"));
            result.add(switch (name) {
                case DealCompilerWorkspace.ADD_DECLARATION ->
                        new DealCompilerWorkspace.AddDeclaration(
                                target, CompilerProtocolJson.stringField(operation, "declaration"));
                case DealCompilerWorkspace.REMOVE_DECLARATION ->
                        new DealCompilerWorkspace.RemoveDeclaration(target);
                case DealCompilerWorkspace.REPLACE_DECLARATION ->
                        new DealCompilerWorkspace.ReplaceDeclaration(
                                target, CompilerProtocolJson.stringField(operation, "declaration"));
                case DealCompilerWorkspace.REPLACE_FUNCTION_BODY ->
                        new DealCompilerWorkspace.ReplaceFunctionBody(
                                target, CompilerProtocolJson.stringField(operation, "body"));
                case DealCompilerWorkspace.REPLACE_BLOCK_BODY ->
                        new DealCompilerWorkspace.ReplaceBlockBody(
                                target, CompilerProtocolJson.stringField(operation, "body"));
                case DealCompilerWorkspace.SET_CAPABILITIES ->
                        new DealCompilerWorkspace.SetCapabilities(
                                target, stringArray(operation, "capabilities"));
                default -> throw new IllegalArgumentException("Unsupported DEAL operation: " + name);
            });
        }
        return List.copyOf(result);
    }

    private static List<String> stringArray(CanonicalJson.Obj object, String field) {
        CanonicalJson.Value value = object.entries().stream()
                .filter(entry -> entry.key().equals(field))
                .map(CanonicalJson.Entry::value)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Missing field: " + field));
        return CompilerProtocolJson.requireArray(value, field).items().stream()
                .map(item -> {
                    if (!(item instanceof CanonicalJson.Str string)) {
                        throw new IllegalArgumentException(field + " must contain strings");
                    }
                    return string.value();
                }).toList();
    }

    private static List<UiCompilerWorkspace.Operation> uiOperations(String source) {
        CanonicalJson.Arr values = CompilerProtocolJson.requireArray(
                CompilerProtocolJson.decode(source), "Deal UI operations");
        List<UiCompilerWorkspace.Operation> result = new ArrayList<>();
        for (CanonicalJson.Value value : values.items()) {
            CanonicalJson.Obj operation = CompilerProtocolJson.requireObject(value, "Deal UI operation");
            String name = CompilerProtocolJson.stringField(operation, "operation");
            SemanticId target = new SemanticId(CompilerProtocolJson.stringField(operation, "targetId"));
            result.add(switch (name) {
                case UiCompilerWorkspace.ADD_VIEW -> new UiCompilerWorkspace.AddView(
                        target, CompilerProtocolJson.stringField(operation, "source"));
                case UiCompilerWorkspace.REMOVE_VIEW -> new UiCompilerWorkspace.RemoveView(target);
                case UiCompilerWorkspace.REPLACE_VIEW_BODY -> new UiCompilerWorkspace.ReplaceViewBody(
                        target, CompilerProtocolJson.stringField(operation, "body"));
                case UiCompilerWorkspace.REPLACE_SUBTREE -> new UiCompilerWorkspace.ReplaceSubtree(
                        target, CompilerProtocolJson.stringField(operation, "source"));
                case UiCompilerWorkspace.INSERT_CHILD -> new UiCompilerWorkspace.InsertChild(
                        target, CompilerProtocolJson.intField(operation, "index"),
                        CompilerProtocolJson.stringField(operation, "source"));
                case UiCompilerWorkspace.REMOVE_NODE -> new UiCompilerWorkspace.RemoveNode(target);
                case UiCompilerWorkspace.MOVE_NODE -> new UiCompilerWorkspace.MoveNode(
                        target,
                        new SemanticId(CompilerProtocolJson.stringField(operation, "newParentId")),
                        CompilerProtocolJson.intField(operation, "index"));
                case UiCompilerWorkspace.SET_PROPERTY -> new UiCompilerWorkspace.SetProperty(
                        target,
                        CompilerProtocolJson.stringField(operation, "property"),
                        CompilerProtocolJson.stringField(operation, "expression"));
                default -> throw new IllegalArgumentException("Unsupported Deal UI operation: " + name);
            });
        }
        return List.copyOf(result);
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void requireArgs(String[] args, int count) {
        if (args.length != count) {
            throw new IllegalArgumentException("Expected " + (count - 1) + " arguments for " + args[0]);
        }
    }

    private static String minimalUi() {
        return "import * as app from \"./app.deal\";\n"
                + "import * as ui from \"./platform-ui.dealui-pack\";\n"
                + "// @ui-root\nexport view App(state: app.AppState): View { ui.Text(value: \"\") }\n";
    }

    private static String minimalPack() {
        return "pack version \"inspect-only\";\n"
                + "export class TextProps { value: string = \"\"; }\n"
                + "export component Text(props: TextProps): View;\n";
    }
}
