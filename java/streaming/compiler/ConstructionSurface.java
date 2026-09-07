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
    static final String UI_INSTRUCTIONS = """
            You are building ONLY Deal UI through the compiler API. DEAL is already compiled and frozen.
            Do not create state, initializers, records, functions or handlers. Bind existing actions from deal.interface.
            You MUST invoke the provided construct_* tool. A prose answer or source code is not consumed.
            Prefer path(parts=["state","field"]) for state/member access. VALUE operands allow inline integers
            and booleans; strings remain handles. Use text calls for display strings. No indexing is permitted.
            Component contracts encode props as property-name:type maps. A trailing ? in the contract key
            marks an optional property; omit ? when binding it. Missing parent means any; missing events
            or capabilities means empty. All pack components remain available, not a selected subset.
            Tool arguments have two fields: calls (a flat compiler constructor batch) and arguments (the granted edit).
            Every call has an id and op. Operand strings name ids in this batch, never source expressions.
            Order is irrelevant. Do not reuse inspected symbol/node ids as construction operands.
            For text data use text; for numbers use integer; for booleans use boolean.
            reference(name=state) plus field(object=<state id>,name=<field>) binds state data.
            reference(name=ui) plus field binds an exact UI constant from the pack if its property needs one.
            action(name=<existing action type>,fields=[...]) creates an Action value, NOT a class declaration.
            component(name=<exact pack component>,fields=[{name:<property>,value:<VALUE id>}],children=[UI ids])
            constructs a UI node. Children is always present, even if empty. Use only declared properties.
            uiBody(children=[UI ids]) produces the result handle for replaceViewBody or a repair body slot.
            A subtree replacement uses a single component/when/forEach result instead of uiBody.
            when and forEach build conditional and dynamic children. No arrays, indexing, assignment or arbitrary calls.
            Minimal constructor example (API usage only, not an application template):
            {"calls":[{"id":"label","op":"text","value":"Ready"},
            {"id":"node","op":"component","name":"Text","fields":[{"name":"value","value":"label"}],"children":[]},
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
            Use compact defaults: omit optional properties unless needed. Never fill unrelated properties with a label.
            Respect component types, available state paths and nominal actions. Compiler diagnostics are authoritative.
            During repair, change only the rejected slot; preservedSlots.payload is staged read-only context.
            No source-code escape hatch is available. Submit compiler construction calls only.
            """;
    private static final Set<String> CODE_FIELDS = Set.of("appStateDeclaration", "initialStateBody", "actionDeclaration",
            "handlerDeclaration", "declaration", "body", "source", "expression", "supportingDeclarations");
    static final String INSTRUCTIONS = """
            Work only through compiler construction API calls, not source code. Source shown in read-only context
            is for inspection only. Never output code, expressions, signatures or declarations as strings.
            Each construct_* tool accepts a flat calls batch and transaction arguments. Each call has a unique id;
            operands are ids of calls in the same batch. Order is irrelevant; cycles are rejected.
            These ids are temporary compiler value handles, not program variables or inspected symbol/node IDs.
            VALUE operands may be a handle string, an inline integer, or an inline boolean.
            Prefer inline numbers/booleans over separate calls. Display strings still use the text constructor;
            strings in operand positions are always handles, never expressions or literals.
            Prefer path(parts=["state","count"]) over reference+field. Parts are identifiers, not source.
            returnRecord(fields=...) constructs a complete BLOCK returning a record; prefer it to record+return+block.
            Use a returnRecord id directly as initialStateBody or a handler body. Never place it in block.statements.
            text creates literal display data (never executable code); integer/boolean create literals. reference names
            one program variable; field selects a member. record assembles fields from value handles. binary computes
            a value. local/assign/return/if/while build statements; block groups statement handles. declareRecord and
            declareFunction build declarations; declareUpdate builds a framework update handler. Use explicit types.
            In transaction arguments, all declaration/body/source/expression fields contain result handles, NOT code.
            Supporting declarations are declaration handles. Body slots need block or uiBody handles.
            An initialState body must contain a return statement for a record matching the proposed AppState.
            During repair, preservedSlots.payload describes staged changes; it supersedes the committed index.
            Only compiler dependency slots are expanded. Other preserved slots are summarized; query_repair_context
            can read them if needed, but never grants permission to change them.
            Framework behavior contract: every UI input has a nominal action record declared with declareRecord.
            Its handler MUST use declareUpdate, parameters state:AppState and action:YourActionType, returns:AppState.
            Read state fields through reference(name=state) and field(object=<state handle>, name=<field name>).
            State and action are borrowed: never assign to them. Construct and return replacement state instead,
            preserving unchanged fields. A void function or assignment to a bare state field is NOT an update.
            When the authorized tool accepts actionHandlers, supply each action/handler pair there using declaration handles.
            Creating an unused handler call does not install it. Empty actionHandlers cannot satisfy behavior.
            Action registration example (only an API illustration for a state with one value:int field):
            calls=[{"id":"a","op":"declareRecord","name":"SetValue","fields":[{"name":"value","type":"int","value":0}]},
            {"id":"v","op":"path","parts":["action","value"]},
            {"id":"b","op":"returnRecord","fields":[{"name":"value","value":"v"}]},
            {"id":"h","op":"declareUpdate","name":"onSetValue","parameters":[{"name":"state","type":"AppState"},{"name":"action","type":"SetValue"}],"returns":"AppState","body":"b"}]
            is registered with actionHandlers=[{"actionDeclaration":"a","handlerDeclaration":"h"}].
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
        var calls = ((Map<?, ?>) CanonicalConstruction.contract(ui).get("properties")).get("calls");
        result.put("parameters", DealConstruction.objectSchema(Map.of("calls", calls,
                "arguments", adapt(schema, "", false))));
        return result;
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
        return requireObject(lowerValue(args, "", false, operation, calls, ui), "projected arguments");
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
            DealConstruction.Kind kind = producer || key.equals("body") || key.equals("initialStateBody")
                    ? DealConstruction.Kind.BLOCK : key.equals("expression") ? DealConstruction.Kind.VALUE
                    : key.equals("source") && !operation.equals("addView") ? DealConstruction.Kind.UI : DealConstruction.Kind.DECLARATION;
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
}
