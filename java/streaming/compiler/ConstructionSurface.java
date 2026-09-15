package streaming.compiler;

import deal.compiler.DealConstruction;
import deal.semantic.ir.CanonicalJson;
import deal.ui.CanonicalConstruction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static deal.compiler.CompilerProtocolJson.*;

/** Adapts authorized semantic transactions to compiler construction calls, never source strings. */
final class ConstructionSurface {
    static final String ANDROID_HOST_EFFECTS_V1 = """
            The selected host profile supports only these optional declared platform capabilities:
            map.navigation, calendar.events.owned, calendar.open. Declare one only when the user explicitly requests it.
            All platform requests and host completions use one nominal action and one synchronous update:
            PlatformHostAction has exactly operation:string, requestId:string, status:string, message:string,
            originLatitudeE6:int, originLongitudeE6:int, destinationLatitudeE6:int, destinationLongitudeE6:int,
            title:string, startEpochMinute:int, durationMinutes:int, reminderMinutes:int, eventId:string,
            confirmed:boolean. Register PlatformHostAction and its onPlatformHostAction update in actionHandlers.
            Deal UI may bind it only with status "request". Android returns the same action with status "success",
            "cancelled", "unavailable", or "error". Set pending state on request and consume terminal status in the update.
            Operations are "map.navigate", "calendar.insert", "calendar.open", and "calendar.remove-owned".
            Map coordinates are signed millionths of a degree (E6), with explicit origin and destination. Derive origin
            from authoritative state such as the last reached item, not UI position. Calendar insert uses a positive
            absolute startEpochMinute and durationMinutes, a non-negative reminderMinutes, and retains the eventId
            returned on success. Calendar removal may pass one retained created eventId, or blank to remove all events
            in this app's host-owned ledger; set confirmed true only from an explicit confirmation action. Never
            synthesize event identity, expose completion status through UI bindings, or request broad calendar deletion.
            A declared platform capability also requires a reachable CapabilityNotice whose onRequest binds the same
            request action; query its compiler contract when it is not already in the selected UI surface.
            """;
    static final String PORTION_INSTRUCTIONS = """
            Build the requested application using only the granted compiler API tools. Never return source code or prose.
            A constructor transaction spans multiple responses. All staged handles remain available until finalization.
            stage_constructor_calls APPENDS new ids; it never changes an existing id. Every response MUST contain 1..16 calls.
            Stop after sixteen even when more constructors are needed; continue them in the next compiler-issued request.
            To modify or finish an existing constructor: inspect_staged_call, then replace_staged_call.
            Read up to sixteen needed constructors together. After inspection, perform the required write or finalize;
            do not repeatedly inspect unchanged data. A replacement retains the selected id and result kind.
            finish_constructor_calls supplies ONLY transaction arguments referencing staged handles. It compiles the accumulated graph.
            Do not restage existing calls when finishing. Rejected portions do not change the staged graph.
            Use the current ticket from the tool schema. Instructions about staged nodes refer to this transaction, not a previous app.

            Constructors form a flat dependency graph. Every computed expression is its own constructor with an id.
            Operands may be a handle string, inline integer/boolean, {"text":"literal"}, {"path":["variable","field"]},
            or {"emptyArray":true} where the language permits it. Never nest binary/call/record constructors inside operands.
            Use a binary constructor and refer to its id, not {"binary":...}. Names and path segments are identifiers, not expressions.
            A constructor id is not a program variable: local creates a statement, while {"path":["name"]} accesses its variable.
            Block statements must reference statement/block constructors, not values or declarations.
            returnRecord constructs a block returning a record; use its id directly as an initializer or handler body.
            if/while are statements with block bodies, not conditional values. For conditional values, use a fresh local and assignments.
            Explicit scalar types are int, number, boolean, string and bytes. Arrays use T[]; nominal types require declarations.
            Build arrays using a typed fresh local, emptyArray, index and assign. State/action are borrowed: return replacement state,
            preserving unchanged fields, and mutate only fresh local data. UI updates use declareUpdate with state and action parameters.
            Register helper declarations and action/handler pairs in the final transaction arguments. Unreferenced calls install nothing.
            Native controls emit only their documented payload. Buttons do not read sibling controls; editable drafts belong in state.

            For Deal UI, use only the exact supplied component pack and frozen AppInterface. Do not generate new state or handlers.
            component children reference UI nodes; text operands are property values, not visible UI nodes.
            Use IntText/IntStat for numbers, Text for strings. Bind all required actions and host capabilities through reachable nodes.
            UI constants are paths such as {"path":["ui","spaceMd"]}. Action properties can use
            {"action":{"name":"ExistingAction","fields":[]}}. Event data is {"path":["payload"]} where documented.
            Dynamic collections use forEach with a state collection path, declared item type, and item-rooted key path.
            Deal UI has no indexing, array literals, assignment or arbitrary calls. A condition must be boolean; comparisons use binary.
            A complete root has AppTheme containing Root and its content. A subtree is one UI node. Use native adaptive layout components.
            Omit unneeded optional properties. Staging is not semantic acceptance; final compiler diagnostics determine validity.
            A ? property may be omitted; component defaults are applied by the runtime. Defaults are not exhaustive allowed values.
            """;
    static final String UI_INSTRUCTIONS = """
            You are building ONLY Deal UI through the compiler API. DEAL is already compiled and frozen.
            Do not create state, initializers, records, functions or handlers. Bind existing actions from deal.interface.
            You MUST invoke the provided construct_* tool. A prose answer or source code is not consumed.
            Use {"path":["state","field"]} for state/member access. VALUE operands allow inline integers
            and booleans; strings remain handles. Use inline text operands for display strings. No indexing is permitted.
            Prefer inline VALUE operands to avoid redundant handles: {"text":"Ready"} is literal text,
            {"path":["state","count"]} is a state field, {"path":["payload"]} is event data.
            Use these objects directly as fields[].value. Never use bare state for a numeric property.
            Component contracts encode props as property-name:type maps. A trailing ? in the contract key
            marks an optional property; omit ? when binding it. Missing parent means any; missing events
            or capabilities means empty. All pack components remain available, not a selected subset.
            Properties marked ? may also have pack defaults. Omit them to keep those defaults.
            defaults contains compiler-extracted values, not an exhaustive list of allowed values.
            Tool arguments have two fields: calls (a flat compiler constructor batch) and arguments (the granted edit).
            Every call has an id and op. Operand strings name ids in this batch, never source expressions.
            Order is irrelevant. Do not reuse inspected symbol/node ids as construction operands.
            Prefer inline text/integer/boolean operands instead of separate scalar calls.
            A component named Text creates a visible label. Inline text is only its property value.
            Every children entry must point to a component, when or forEach, never a scalar value or text constructor.
            Use inline paths for both state data and exact UI constants from the pack.
            Token properties such as spacing require {"path":["ui","spaceMd"]}, NOT {"text":"spaceMd"}.
            action(name=<existing action type>,fields=[...]) creates an Action value, NOT a class declaration.
            Component action properties may inline {"action":{"name":"ExistingAction","fields":[]}} instead of a separate action handle.
            Action payload fields use ordinary VALUE operands, for example {"name":"value","value":{"path":["payload"]}}.
            component(name=<exact pack component>,fields=[{name:<property>,value:<VALUE id>}],children=[UI ids])
            constructs a UI node. Children is always present, even if empty. Use only declared properties.
            uiBody(children=[UI ids]) produces the result handle for replaceViewBody or a repair body slot.
            A single UI node is also a valid view body, equivalent to uiBody containing that one node.
            A subtree replacement uses a single component/when/forEach result instead of uiBody.
            when and forEach build conditional and dynamic children. forEach.collection and forEach.key accept inline paths.
            Deal UI expressions cannot index, assign, call arbitrary functions or construct arrays.
            JSON arrays in this API contain constructor records, path segments or child handles; they are not Deal UI array expressions.
            Minimal constructor example (API usage only, not an application template):
            {"calls":[{"id":"node","op":"component","name":"Text","fields":[{"name":"value","value":{"text":"Ready"}}],"children":[]},
            {"id":"surface","op":"component","name":"Root","fields":[],"children":["node"]},
            {"id":"theme","op":"component","name":"AppTheme","fields":[],"children":["surface"]},
            {"id":"root","op":"uiBody","children":["theme"]}],
            "arguments":{"operations":[{"operation":"replaceViewBody","body":"root"}],"final":true}}
            Use the actually granted operation and target schema, not invented setRoot operations.
            Build the requested UI, not the example. Display numeric state through IntText/IntStat, not Text.
            Every complete root view MUST have exactly ONE root UI node: AppTheme wrapping the content.
            AppTheme must contain one Root component, the adaptive application surface. Place content inside Root.
            Put layout containers inside AppTheme.children, not beside AppTheme in uiBody.children.
            Retain this root theme wrapper when repairing a whole root body.
            Creating a call does not attach it: every visible component, action binding and clock must be reachable
            through children from the operation's body handle. Bind EVERY action from deal.interface at least once.
            For parameterized actions use the compatible input event and its payload, not a hard-coded value.
            For required host capabilities attach their host component INSIDE the Root/Column children tree,
            never as an additional view root. A FrameClock call left unused in calls does not implement a clock.
            Use compact defaults: omit optional properties unless needed. Never fill unrelated properties with a label.
            Respect component types, available state paths and nominal actions. Compiler diagnostics are authoritative.
            During repair, change only the rejected slot; preservedSlots.payload is staged read-only context.
            Read activeSlot.diagnostics first. Fix every listed expected/actual mismatch in the returned reachable tree.
            Do not merely append a disconnected constructor, and do not resubmit the same rejected body.
            No source-code escape hatch is available. Submit compiler construction calls only.
            """;
    private static final Set<String> CODE_FIELDS = Set.of("appStateDeclaration", "initialStateBody", "actionDeclaration",
            "handlerDeclaration", "declaration", "body", "source", "expression", "supportingDeclarations");
    static final String INSTRUCTIONS = """
            Work only through compiler construction API calls, not source code. Source shown in read-only context
            is for inspection only. Never output code, expressions, signatures or declarations as strings.
            Each construct_* tool accepts a flat calls batch and transaction arguments. Each call has a unique id;
            Only text/path/integer/boolean and emptyArray operands may be inline objects. Every computation (binary, unary,
            call, record, array, etc.) must be a separate entry in calls with an id; consumers use that id as a string.
            Never nest {"binary":{...}} or {"op":"binary",...} inside an operand. This is a flat API, not a JSON AST.
            The top-level object MUST contain BOTH calls AND arguments. Defining calls alone installs nothing.
            For construct_apply_deal_batch the envelope is:
            {"calls":[...],"arguments":{"appStateDeclaration":"stateDecl","initialStateBody":"initBody",
            "supportingDeclarations":[],"actionHandlers":[{"actionDeclaration":"actionDecl","handlerDeclaration":"handlerDecl"}],
            "capabilities":[],"final":true}}.
            Define each referenced handle in calls. Put helper types/functions in supportingDeclarations;
            put nominal input actions and their declareUpdate handlers only in actionHandlers, not both lists.
            Do not submit a skeleton or only types. Include the actual transition bodies and register them.
            operands are ids of calls in the same batch. Order is irrelevant; cycles are rejected.
            These ids are temporary compiler value handles, not program variables or inspected symbol/node IDs.
            VALUE operands may be a handle string, an inline integer, or an inline boolean.
            Compact inline operands are preferred: {"text":"Ready"} for literal text including empty text,
            {"path":["state","count"]} for a field, {"path":["action","value"]} for action data.
            These objects replace redundant text/path calls and can be used in fields[].value or binary operands.
            Tagged scalars {"integer":1} and {"boolean":true} are also supported; bare 1 and true are shorter.
            Prefer inline numbers/booleans over separate calls. Display strings use {"text":"..."} or a text constructor;
            strings in operand positions are always handles, never expressions or literals.
            The empty string "" is also accepted as an empty text value because it cannot name a handle.
            Prefer path(parts=["state","count"]) over reference+field. Parts are identifiers, not source.
            returnRecord(fields=...) constructs a complete BLOCK returning a record; prefer it to record+return+block.
            Use a returnRecord id directly as initialStateBody or a handler body. block concatenates statement or block handles in order.
            text creates literal display data (never executable code); integer/boolean create literals. reference names
            one program variable; field selects a member. record assembles fields from value handles. binary computes a value.
            if is a STATEMENT, not a conditional value: then and else must be BLOCK handles.
            For a conditional computed value, declare a fresh local, assign it inside if branches,
            and include the local declaration in the enclosing block before those branches. A local id is a STATEMENT,
            never a value or assignment target: use {"path":["variableName"]} to read/write that variable.
            Use that reference in the returning record. Blocks contain statement or block handles, never VALUE handles.
            local/assign/return/if/while build statements; block groups statement handles. declareRecord and
            declareFunction build declarations; declareUpdate builds a framework update handler. Use explicit types.
            Scalar type names are int, number, boolean, string and bytes. void is not a built-in DEAL type.
            Helpers in this constructor protocol must return an explicitly typed value; do not introduce dummy void helpers.
            Nominal type names must name declared classes; use boolean, not bool. Array types use T[], for example int[] or Item[].
            Use emptyArray for an array value, record for a nominal object value, never a numeric placeholder or a declaration handle.
            Prefer {"emptyArray":true} for an empty array VALUE directly in fields[].value or local.value.
            This literal requires no handle or program variable. The separate emptyArray constructor remains valid.
            For collections, use a typed fresh local with emptyArray, populate via index+assign inside its block, then return its path.
            A declaration default must match its field type; array fields can default to emptyArray. Do not invent wrapper classes for arrays.
            In transaction arguments, all declaration/body/source/expression fields contain result handles, NOT code.
            Supporting declarations are declaration handles. Body slots need block or uiBody handles.
            An initialState body must contain a return statement for a record matching the proposed AppState.
            During repair, preservedSlots.payload describes staged changes; it supersedes the committed index.
            Only compiler dependency slots are expanded. Other preserved slots are summarized; query_repair_context
            can read them if needed, but never grants permission to change them.
            Framework behavior contract: every UI input has a nominal action record declared with declareRecord.
            Native form controls do not own application data. Store every editable draft value in AppState,
            with a typed change action/handler that replaces that value while preserving the other fields.
            A text input emits one string; an integer input emits one int. A button click has NO payload
            and cannot read sibling controls. A submit action reads the drafts from state, or receives
            explicit existing state paths. Do not require a button to manufacture a multi-field event.
            Deal UI runs only after DEAL is frozen: missing draft fields/setters cannot be added by the UI agent.
            Its handler MUST use declareUpdate, parameters state:AppState and action:YourActionType, returns:AppState.
            Read state fields through inline {"path":["state","fieldName"]}.
            State and action are borrowed: never assign to them. Construct and return replacement state instead,
            preserving unchanged fields. A void function or assignment to a bare state field is NOT an update.
            When the authorized tool accepts actionHandlers, supply each action/handler pair there using declaration handles.
            Creating an unused handler call does not install it. Empty actionHandlers cannot satisfy behavior.
            Complete valid transaction example (API illustration only; implement the user's concept instead):
            {"calls":[
            {"id":"s","op":"declareRecord","name":"AppState","fields":[{"name":"value","type":"int","value":0}]},
            {"id":"i","op":"returnRecord","fields":[{"name":"value","value":0}]},
            {"id":"a","op":"declareRecord","name":"SetValue","fields":[{"name":"value","type":"int","value":0}]},
            {"id":"v","op":"path","parts":["action","value"]},
            {"id":"b","op":"returnRecord","fields":[{"name":"value","value":"v"}]},
            {"id":"h","op":"declareUpdate","name":"onSetValue","parameters":[{"name":"state","type":"AppState"},{"name":"action","type":"SetValue"}],"returns":"AppState","body":"b"}],
            "arguments":{"appStateDeclaration":"s","initialStateBody":"i","supportingDeclarations":[],
            "actionHandlers":[{"actionDeclaration":"a","handlerDeclaration":"h"}],"capabilities":[],"final":true}}
            Build the user's requested transitions, not this illustration. Preserve every actual state field.
            declareFunction is for ordinary helper functions, not UI input handlers.
            For Deal UI, component names come from the supplied pack. fields bind named properties to value handles;
            children are earlier UI handles. action binds a nominal action to named value handles. forEach binds one
            item with a typed collection and key; when creates a conditional subtree. uiBody groups UI children.
            Build payload references with reference(name=payload); never replace event data with unrelated constants.
            Do not select an unrelated action simply because its type fits. If a required action does not exist,
            complete the required behavior first. Respect the original user request and accepted sibling units.
            All construction outputs are checked by the real compilers before publication. A tool call is not
            evidence of success; read its diagnostics. No source-code escape hatch is available.
            """;

    static Map<String, Object> tool(Map<String, Object> original, boolean ui) {
        Map<?, ?> schema = (Map<?, ?>) original.get("parameters");
        if (!hasCode(schema, "", false)) return original;
        var result = new LinkedHashMap<String, Object>();
        result.put("name", "construct_" + original.get("name"));
        result.put("description", "Use compiler construction calls to perform the currently authorized " + original.get("name") + " transaction. No source text is accepted. " + original.get("description"));
        result.put("strict", true);
        var contract = ui && bodyOperationsOnly(schema) ? CanonicalConstruction.bodyContract() : CanonicalConstruction.contract(ui);
        var calls = ((Map<?, ?>) contract.get("properties")).get("calls");
        result.put("parameters", DealConstruction.objectSchema(Map.of("calls", calls,
                "arguments", adapt(schema, "", false))));
        return result;
    }

    private static boolean bodyOperationsOnly(Map<?, ?> schema) {
        if (!(schema.get("properties") instanceof Map<?, ?> properties)
                || !(properties.get("operations") instanceof Map<?, ?> operations)
                || !(operations.get("items") instanceof Map<?, ?> items)
                || !(items.get("anyOf") instanceof List<?> variants) || variants.isEmpty()) return false;
        return variants.stream().allMatch(raw -> {
            if (!(raw instanceof Map<?, ?> variant) || !(variant.get("properties") instanceof Map<?, ?> props)
                    || !(props.get("operation") instanceof Map<?, ?> operation)) return false;
            return Set.of("replaceViewBody", "replaceSubtree").contains(operation.get("const"));
        });
    }

    private static boolean hasCode(Map<?, ?> schema, String key, boolean producer) {
        if ("string".equals(schema.get("type")) && (CODE_FIELDS.contains(key) || producer)) return true;
        if (schema.get("properties") instanceof Map<?, ?> props) for (var entry : props.entrySet())
            if (hasCode((Map<?, ?>) entry.getValue(), (String) entry.getKey(), key.equals("stateProducerBodies"))) return true;
        if (schema.get("items") instanceof Map<?, ?> items && hasCode(items, key, producer)) return true;
        for (String variants : List.of("oneOf", "anyOf")) if (schema.get(variants) instanceof List<?> list)
            for (var item : list) if (hasCode((Map<?, ?>) item, key, producer)) return true;
        return false;
    }

    private static Map<String, Object> adapt(Map<?, ?> schema, String key, boolean producer) {
        if ("string".equals(schema.get("type")) && (CODE_FIELDS.contains(key) || producer))
            return Map.of("type", "string", "description", "Compiler construction result handle for " + key + "; never source text");
        var result = new LinkedHashMap<String, Object>();
        schema.forEach((k, v) -> result.put((String) k, v));
        if (schema.get("properties") instanceof Map<?, ?> props) {
            var mapped = new LinkedHashMap<String, Object>();
            props.forEach((k, v) -> mapped.put((String) k, adapt((Map<?, ?>) v, (String) k, key.equals("stateProducerBodies"))));
            result.put("properties", mapped);
        }
        if (schema.get("items") instanceof Map<?, ?> items) result.put("items", adapt(items, key, producer));
        for (String variants : List.of("oneOf", "anyOf")) if (schema.get(variants) instanceof List<?> list)
            result.put(variants, list.stream().map(item -> adapt((Map<?, ?>) item, key, producer)).toList());
        return result;
    }

    static CanonicalJson.Obj lower(CanonicalJson.Obj envelope, boolean ui, String operation) {
        var calls = requireArray(field(envelope, "calls"), "calls");
        var args = requireObject(field(envelope, "arguments"), "arguments");
        var roots = new ArrayList<List<Object>>();
        var kinds = new LinkedHashMap<List<Object>, DealConstruction.Kind>();
        collectRootPaths(args, "", false, List.of(), roots, kinds, operation);
        deal.compiler.ConstructionRepairWorkspace.validateRoots(envelope, roots, kinds, new CanonicalConstruction(ui));
        return requireObject(lowerValue(args, "", false, operation, calls, ui), "projected arguments");
    }

    static void checkRootBody(CanonicalJson.Obj envelope, String deal, String ui, String pack, String specifier) {
        var arguments = requireObject(field(envelope, "arguments"), "arguments");
        var raw = arguments.entries().stream().filter(e -> e.key().equals("operations"))
                .map(CanonicalJson.Entry::value).findFirst().orElse(null);
        if (!(raw instanceof CanonicalJson.Arr operations) || operations.items().size() != 1) return;
        var operation = requireObject(operations.items().getFirst(), "operation");
        if (!stringField(operation, "operation").equals("replaceViewBody")
                || operation.entries().stream().anyMatch(e -> e.key().equals("target"))) return;
        var batch = requireObject(toValue(Map.of("calls", field(envelope, "calls"),
                "result", field(operation, "body"))), "construction");
        new CanonicalConstruction(true).checkRootBodyAst(batch, deal, ui, pack, specifier);
    }

    private static void collectRootPaths(CanonicalJson.Value value, String key, boolean producer,
            List<Object> path, List<List<Object>> roots, Map<List<Object>, DealConstruction.Kind> kinds, String operation) {
        if (value instanceof CanonicalJson.Str && (CODE_FIELDS.contains(key) || producer)) {
            roots.add(path); kinds.put(path, resultKind(key, producer, operation));
        } else if (value instanceof CanonicalJson.Obj object) {
            var op = object.entries().stream().filter(e -> e.key().equals("operation"))
                    .map(CanonicalJson.Entry::value).findFirst().orElse(null);
            String current = op instanceof CanonicalJson.Str s ? s.value() : operation;
            for (var e : object.entries()) {
                var next = new ArrayList<Object>(path); next.add(e.key());
                collectRootPaths(e.value(), e.key(), key.equals("stateProducerBodies"), next, roots, kinds, current);
            }
        } else if (value instanceof CanonicalJson.Arr array) for (int i = 0; i < array.items().size(); i++) {
            var next = new ArrayList<Object>(path); next.add(i);
            collectRootPaths(array.items().get(i), key, producer, next, roots, kinds, operation);
        }
    }

    static void validateHandleSyntax(CanonicalJson.Value value, String key, boolean producer) {
        if (value instanceof CanonicalJson.Str s && (CODE_FIELDS.contains(key) || producer))
            DealConstruction.validateHandle(s.value());
        else if (value instanceof CanonicalJson.Obj object)
            for (var e : object.entries()) validateHandleSyntax(e.value(), e.key(), key.equals("stateProducerBodies"));
        else if (value instanceof CanonicalJson.Arr array)
            for (var item : array.items()) validateHandleSyntax(item, key, producer);
    }

    private static CanonicalJson.Value lowerValue(CanonicalJson.Value value, String key, boolean producer,
            String operation, CanonicalJson.Arr calls, boolean ui) {
        if (value instanceof CanonicalJson.Str s && (CODE_FIELDS.contains(key) || producer)) {
            DealConstruction.Kind kind = resultKind(key, producer, operation);
            var batch = requireObject(toValue(Map.of("calls", calls, "result", s.value())), "construction");
            return CanonicalJson.str(new CanonicalConstruction(ui).build(batch, kind));
        }
        if (value instanceof CanonicalJson.Obj object) {
            var op = object.entries().stream().filter(e -> e.key().equals("operation"))
                    .map(CanonicalJson.Entry::value).findFirst().orElse(null);
            String current = op instanceof CanonicalJson.Str s ? s.value() : operation;
            return CanonicalJson.obj(object.entries().stream().map(e -> CanonicalJson.e(e.key(),
                    lowerValue(e.value(), e.key(), key.equals("stateProducerBodies"), current, calls, ui))).toList());
        }
        if (value instanceof CanonicalJson.Arr array) return CanonicalJson.arr(array.items().stream()
                .map(item -> lowerValue(item, key, producer, operation, calls, ui)).toList());
        return value;
    }

    private static DealConstruction.Kind resultKind(String key, boolean producer, String operation) {
        return producer || key.equals("body") || key.equals("initialStateBody")
                ? DealConstruction.Kind.BLOCK : key.equals("expression") ? DealConstruction.Kind.VALUE
                : key.equals("source") && !operation.equals("addView") ? DealConstruction.Kind.UI : DealConstruction.Kind.DECLARATION;
    }
}
