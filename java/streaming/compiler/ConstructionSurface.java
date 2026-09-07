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
    private static final Set<String> CODE_FIELDS = Set.of("appStateDeclaration", "initialStateBody", "actionDeclaration",
            "handlerDeclaration", "declaration", "body", "source", "expression", "supportingDeclarations");
    static final String INSTRUCTIONS = """
            Work only through compiler construction API calls, not source code. Source shown in read-only context
            is for inspection only. Never output code, expressions, signatures or declarations as strings.
            Each construct_* tool accepts a flat calls batch and transaction arguments. Each call has a unique id;
            operands are ids of calls in the same batch. Order is irrelevant; cycles are rejected.
            These ids are temporary compiler value handles, not program variables or inspected symbol/node IDs.
            Every fields[].value, including declareRecord default values, must name a VALUE call id in this batch.
            For a zero default, first issue integer with id zero and value 0, then use value zero in the field.
            Do not use the string 0 as an operand unless an earlier call actually has id 0.
            text creates literal display data (never executable code); integer/boolean create literals. reference names
            one program variable; field selects a member. record assembles fields from value handles. binary computes
            a value. local/assign/return/if/while build statements; block groups statement handles. declareRecord and
            declareFunction build declarations; declareUpdate builds a framework update handler. Use explicit types.
            In transaction arguments, all declaration/body/source/expression fields contain result handles, NOT code.
            Supporting declarations are declaration handles. Body slots need block or uiBody handles.
            An initialState body must contain a return statement for a record matching the proposed AppState.
            During repair, preservedSlots.payload describes staged changes; it supersedes the committed index.
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
        result.put("description", "Use compiler construction calls to perform the currently authorized " + original.get("name") + " transaction. No source text is accepted.");
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
