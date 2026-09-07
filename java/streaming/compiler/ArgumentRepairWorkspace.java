package streaming.compiler;

import deal.compiler.DealCompilerWorkspace;
import deal.semantic.ir.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static deal.compiler.CompilerProtocolJson.*;

/** Unapplied tool arguments. The engine, never the model, chooses the only writable JSON location. */
final class ArgumentRepairWorkspace {
    record Issue(List<Object> path, Map<?, ?> schema, CanonicalJson.Value actual, boolean remove) {}
    final String toolName;
    private final Map<?, ?> schema;
    private CanonicalJson.Obj candidate;
    private Issue issue;
    private int rounds;
    private int unchanged;
    private String rejectedPatch;
    private String rejectedDiagnostic;
    private int repeatedRejection;

    void validateTicket(CanonicalJson.Obj patch) {
        if (!ticket().equals(stringField(patch, "ticket")))
            throw new IllegalArgumentException("Stale argument repair ticket");
    }

    void rejectPatch(CanonicalJson.Obj patch, String diagnostic) {
        validateTicket(patch);
        String encoded = encode(patch);
        repeatedRejection = encoded.equals(rejectedPatch) ? repeatedRejection + 1 : 1;
        rejectedPatch = encoded;
        rejectedDiagnostic = diagnostic;
        if (++rounds > 8) throw new IllegalArgumentException("Argument repair budget exhausted");
        if (repeatedRejection >= 2)
            throw new IllegalArgumentException("Argument repair repeated the same invalid patch without progress: " + diagnostic);
    }

    ArgumentRepairWorkspace(String toolName, Map<?, ?> schema, CanonicalJson.Obj candidate) {
        this.toolName = toolName;
        this.schema = schema;
        this.candidate = candidate;
        if (schema.get("properties") instanceof Map<?, ?> props && props.containsKey("calls")
                && (!(optionalField(candidate, "calls") instanceof CanonicalJson.Arr calls) || calls.items().isEmpty()))
            throw new IllegalArgumentException("Incomplete tool envelope: calls must contain compiler operations before local repair");
        issue = repairIssue(candidate);
        requireLocal(issue);
    }

    CanonicalJson.Obj candidate() { return candidate; }
    boolean complete() { return issue == null; }
    int rounds() { return rounds; }
    String ticket() { return DealCompilerWorkspace.digest(encode(candidate) + encode(issue.path())); }

    Map<String, Object> tool() {
        var props = new LinkedHashMap<String, Object>();
        props.put("ticket", Map.of("type", "string", "const", ticket()));
        if (!issue.remove()) {
            var slots = repairSlots();
            var replacements = new LinkedHashMap<String, Object>();
            for (int i = 0; i < slots.size(); i++) replacements.put("R" + (i + 1), slots.get(i).schema());
            props.put("replacement", slots.size() == 1 ? issue.schema() : deal.compiler.DealConstruction.objectSchema(replacements));
        }
        if (constructorSlot()) props.put("dependencies", Map.of("type", "array", "items", callSchema(), "maxItems", 8));
        return Map.of("name", "patch_tool_argument", "description",
                issue.remove() ? "Confirm removal of the engine-selected unexpected property by returning its ticket only. All other data is immutable."
                        : "Replace only the engine-selected invalid argument. The rest of the pending tool call is immutable.",
                "parameters", deal.compiler.DealConstruction.objectSchema(props));
    }

    String request(Map<String, Object> base) {
        var input = new LinkedHashMap<String, Object>();
        input.put("requiredArtifact", base.getOrDefault("argumentArtifact", "deal"));
        input.put("tool", toolName);
        input.put("path", issue.path());
        input.put("actual", issue.actual());
        input.put("operation", issue.remove() ? "removeUnexpectedProperty" : "replaceArgument");
        input.put("ticket", ticket());
        if (rejectedPatch != null) {
            input.put("rejectedPatch", decode(rejectedPatch));
            input.put("patchDiagnostic", rejectedDiagnostic);
        }
        var invalid = locate(candidate, schema, List.of());
        if (invalid != null) {
            input.put("diagnostic", Map.of("path", invalid.path(), "actual", invalid.actual(),
                    "expected", invalid.schema(), "operation", invalid.remove() ? "removeUnexpectedProperty" : "replaceInvalidValue"));
        }
        if (constructorSlot()) input.put("scope", "Replace this constructor, retaining its id. You may add up to eight NEW dependency constructors. Existing sibling constructors are immutable.");
        var slots = repairSlots();
        if (slots.size() > 1) {
            var descriptions = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < slots.size(); i++) {
                var slot = slots.get(i);
                var diagnostic = locate(slot.actual(), callSchema(), slot.path());
                descriptions.add(Map.of("slot", "R" + (i + 1), "call", slot.actual(),
                        "diagnostic", Map.of("path", diagnostic.path(), "actual", diagnostic.actual(), "expected", diagnostic.schema())));
            }
            input.put("repairSlots", descriptions);
            input.put("scope", "Replace only the listed invalid slots, retaining each id. All other existing constructors are immutable.");
            input.remove("actual"); input.remove("diagnostic"); input.remove("path");
        }
        input.put("parent", constructorSlot() ? Map.of("collection", "calls")
                : at(candidate, issue.path().subList(0, issue.path().size() - 1)));
        input.put("progress", unchanged == 0 ? "Correct this argument only." : "NO_PROGRESS: do not repeat the rejected value.");
        var calls = optionalField(candidate, "calls");
        if (calls instanceof CanonicalJson.Arr array) input.put("availableHandles", array.items().stream()
                .filter(v -> v instanceof CanonicalJson.Obj).map(v -> {
                    var obj = (CanonicalJson.Obj) v;
                    var summary = new LinkedHashMap<String, Object>();
                    for (String key : List.of("id", "op", "name")) {
                        var value = optionalField(obj, key);
                        if (value != null) summary.put(key, value);
                    }
                    return summary;
                }).toList());
        String instructions = "Repair one invalid tool argument. No program has been changed. "
                + (issue.remove() ? "This property is forbidden. Call patch_tool_argument with the issued ticket ONLY to remove it. "
                        : "Use patch_tool_argument with the issued ticket and only the replacement value, not the whole batch. ")
                + "The replacement schema is authoritative. A reference to an available handle is its id as a STRING, not an object. "
                + "Do not invent handles or nest compiler operations. Do not output source code. "
                + "For a missing dependency that cannot be expressed here, do not replace it with unrelated data.";
        if (constructorSlot()) instructions = "Repair the selected compiler constructor via patch_tool_argument. "
                + "Return its complete replacement with the SAME id, plus dependencies: an array of NEW constructors (empty when unnecessary). "
                + "Do not replace unrelated existing constructors. Use index+assign for indexed writes, never array indexes inside path. "
                + "The supplied constructor schemas are authoritative. Handle operands are plain ids without embedded quotes. "
                + "Read diagnostic.path, actual and expected: fix that schema violation in replacement. "
                + "An operand object containing id/op is NOT a reference: use its existing handle id string, or add a NEW dependency and reference its id. "
                + "No raw source code. Fix all schema defects in this constructor, not only the first reported field.";
        if (slots.size() > 1) instructions += " There are multiple invalid slots. replacement is an object keyed by R1, R2, etc., with one complete constructor per issued slot. Fix the group in ONE call. Dependencies are shared across the group.";
        if (rejectedPatch != null) instructions += " The previous patch was NOT applied. Read rejectedPatch and patchDiagnostic; correct that error before resubmitting. Repeating the same rejected patch ends repair.";
        String encodedInput = encode(input);
        var tools = List.of(tool());
        String encodedTools = encode(tools);
        var result = new LinkedHashMap<String, Object>(base);
        result.put("instructions", instructions);
        result.put("input", encodedInput);
        result.put("tools", tools);
        result.put("maxOutputTokens", 8192);
        result.put("argumentRepairRound", rounds);
        result.put("surfaceDigest", DealCompilerWorkspace.digest(encodedInput + encodedTools));
        int size = (encodedInput + encodedTools + instructions).getBytes(StandardCharsets.UTF_8).length;
        result.put("surfaceMetrics", Map.of("inputBytes", encodedInput.getBytes(StandardCharsets.UTF_8).length,
                "toolSchemaBytes", encodedTools.getBytes(StandardCharsets.UTF_8).length,
                "instructionBytes", instructions.getBytes(StandardCharsets.UTF_8).length, "approxInputTokens", (size + 3) / 4));
        return encode(result);
    }

    void patch(CanonicalJson.Obj patch) {
        validatePatch(patch);
        if (++rounds > 8) throw new IllegalArgumentException("Argument repair budget exhausted");
        var next = candidate;
        var slots = repairSlots();
        for (int i = 0; i < slots.size(); i++) next = (CanonicalJson.Obj) replace(next, slots.get(i).path(), 0,
                issue.remove() ? CanonicalJson.Null.INSTANCE : replacementAt(patch, i, slots.size()), issue.remove());
        if (constructorSlot()) {
            var calls = new ArrayList<>(requireArray(field(next, "calls"), "calls").items());
            var ids = new HashSet<String>();
            for (var call : calls) ids.add(encode(field(requireObject(call, "call"), "id")));
            var dependencies = requireArray(field(patch, "dependencies"), "dependencies").items();
            if (dependencies.size() > 8) throw new IllegalArgumentException("Add at most eight dependency constructors");
            for (var dependency : dependencies) {
                if (!ids.add(encode(field(requireObject(dependency, "dependency"), "id")))) continue;
                calls.add(dependency);
            }
            if (calls.size() > 512) throw new IllegalArgumentException("Construction batch exceeds 512 calls");
            next = (CanonicalJson.Obj) replace(next, List.of("calls"), 0, CanonicalJson.arr(calls), false);
        }
        if (encode(next).equals(encode(candidate))) {
            if (++unchanged >= 2) throw new IllegalArgumentException("Argument repair made no progress twice");
        } else unchanged = 0;
        var nextIssue = repairIssue(next);
        if (nextIssue != null) requireLocal(nextIssue);
        candidate = next;
        issue = nextIssue;
        rejectedPatch = null;
        rejectedDiagnostic = null;
        repeatedRejection = 0;
    }

    void validatePatch(CanonicalJson.Obj patch) {
        var expected = (Map<?, ?>) tool().get("parameters");
        var invalid = locate(patch, expected, List.of());
        if (invalid != null) throw new IllegalArgumentException("Invalid argument patch: " + encode(Map.of(
                "path", invalid.path(), "actual", invalid.actual(), "expected", invalid.schema(),
                "operation", invalid.remove() ? "removeUnexpectedProperty" : "replaceInvalidValue")));
        CanonicalRefinementSession.validateSchema(patch, expected);
        if (constructorSlot()) {
            var slots = repairSlots();
            for (int i = 0; i < slots.size(); i++) {
                var owner = requireObject(slots.get(i).actual(), "constructor");
                var replacement = requireObject(replacementAt(patch, i, slots.size()), "replacement");
                if (!encode(field(owner, "id")).equals(encode(field(replacement, "id"))))
                    throw new IllegalArgumentException("Replacement must preserve selected constructor id");
            }
            var originals = new HashMap<String, CanonicalJson.Value>();
            for (var call : requireArray(field(candidate, "calls"), "calls").items())
                originals.put(encode(field(requireObject(call, "call"), "id")), call);
            var seen = new HashSet<String>();
            for (var call : requireArray(field(patch, "dependencies"), "dependencies").items()) {
                String id = encode(field(requireObject(call, "dependency"), "id"));
                if (!seen.add(id)) throw new IllegalArgumentException("Duplicate dependency id: " + id);
                if (originals.containsKey(id) && !encode(originals.get(id)).equals(encode(call)))
                    throw new IllegalArgumentException("Cannot change existing dependency " + id + "; reference its id or introduce a NEW id. Previous candidate unchanged.");
            }
        }
    }

    private static CanonicalJson.Value replacementAt(CanonicalJson.Obj patch, int index, int count) {
        var replacement = field(patch, "replacement");
        return count == 1 ? replacement : field(requireObject(replacement, "replacement slots"), "R" + (index + 1));
    }

    private List<Issue> repairSlots() {
        if (!constructorSlot()) return List.of(issue);
        var slots = new ArrayList<Issue>();
        var calls = requireArray(field(candidate, "calls"), "calls").items();
        for (int i = 0; i < calls.size() && slots.size() < 8; i++) {
            if (calls.get(i) instanceof CanonicalJson.Obj call
                    && optionalField(call, "id") instanceof CanonicalJson.Str
                    && optionalField(call, "op") instanceof CanonicalJson.Str
                    && locate(call, callSchema(), List.of()) != null)
                slots.add(new Issue(List.of("calls", i), ownerSchema(call), call, false));
        }
        return slots.isEmpty() ? List.of(issue) : List.copyOf(slots);
    }

    private Map<?, ?> callSchema() {
        return (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get("calls")).get("items");
    }

    private boolean constructorSlot() {
        return issue.path().size() == 2 && issue.path().getFirst().equals("calls")
                && issue.actual() instanceof CanonicalJson.Obj obj && optionalField(obj, "id") != null;
    }

    private Issue repairIssue(CanonicalJson.Obj value) {
        var found = locate(value, schema, List.of());
        if (found != null && found.path().size() >= 2 && found.path().getFirst().equals("calls")
                && found.path().get(1) instanceof Integer) {
            var path = found.path().subList(0, 2);
            var owner = at(value, path);
            if (owner instanceof CanonicalJson.Obj obj && optionalField(obj, "id") instanceof CanonicalJson.Str
                    && optionalField(obj, "op") instanceof CanonicalJson.Str)
                return new Issue(path, ownerSchema(obj), owner, false);
        }
        return found;
    }

    private Map<?, ?> ownerSchema(CanonicalJson.Obj owner) {
        if (callSchema().get("anyOf") instanceof List<?> alternatives) {
            for (Object raw : alternatives) {
                var alternative = (Map<?, ?>) raw;
                if (!(alternative.get("properties") instanceof Map<?, ?> properties)) continue;
                if (!(properties.get("op") instanceof Map<?, ?> op)
                        || !encode(op.get("const")).equals(encode(field(owner, "op")))) continue;
                var fixed = new LinkedHashMap<String, Object>();
                properties.forEach((key, value) -> fixed.put((String) key, value));
                fixed.put("id", Map.of("type", "string", "const", field(owner, "id")));
                return deal.compiler.DealConstruction.objectSchema(fixed);
            }
        }
        return callSchema();
    }

    private static void requireLocal(Issue issue) {
        if (issue == null || issue.path().isEmpty()
                || (issue.path().size() == 1 && issue.path().getFirst().equals("calls")))
            throw new IllegalArgumentException("No local argument repair available; batch replacement is not granted");
    }

    static Issue locate(CanonicalJson.Value value, Map<?, ?> schema, List<Object> path) {
        try { CanonicalRefinementSession.validateSchema(value, schema); return null; }
        catch (IllegalArgumentException invalid) { }
        Object variants = schema.containsKey("anyOf") ? schema.get("anyOf") : schema.get("oneOf");
        if (variants instanceof List<?> list) {
            var choices = list.stream().map(x -> (Map<?, ?>) x).toList();
            int best = choices.stream().mapToInt(s -> score(value, s)).max().orElse(0);
            var selected = choices.stream().filter(s -> score(value, s) == best).toList();
            if (selected.size() == 1 && best > 0) return locate(value, selected.getFirst(), path);
            return new Issue(path, schema, value, false);
        }
        if (value instanceof CanonicalJson.Obj object && schema.get("properties") instanceof Map<?, ?> props) {
            if (schema.get("required") instanceof List<?> required) for (var key : required) {
                if (optionalField(object, (String) key) == null)
                    return new Issue(append(path, key), (Map<?, ?>) props.get(key), CanonicalJson.Null.INSTANCE, false);
            }
            for (var entry : object.entries()) {
                if (!props.containsKey(entry.key())) return new Issue(append(path, entry.key()), Map.of(), entry.value(), true);
                var child = locate(entry.value(), (Map<?, ?>) props.get(entry.key()), append(path, entry.key()));
                if (child != null) return child;
            }
        }
        if (value instanceof CanonicalJson.Arr array && schema.get("items") instanceof Map<?, ?> items) {
            for (int i = 0; i < array.items().size(); i++) {
                var child = locate(array.items().get(i), items, append(path, i));
                if (child != null) return child;
            }
        }
        return new Issue(path, schema, value, false);
    }

    private static int score(CanonicalJson.Value value, Map<?, ?> schema) {
        if (!(value instanceof CanonicalJson.Obj object) || !(schema.get("properties") instanceof Map<?, ?> props)) return 0;
        int score = 0;
        for (var entry : props.entrySet()) {
            var actual = optionalField(object, (String) entry.getKey());
            var property = (Map<?, ?>) entry.getValue();
            if (actual != null && property.containsKey("const")) {
                if (!encode(actual).equals(encode(property.get("const")))) return -1000;
                score += 100;
            } else if (actual != null) score++;
        }
        return score;
    }

    private static List<Object> append(List<Object> path, Object part) {
        var next = new ArrayList<>(path); next.add(part); return List.copyOf(next);
    }
    private static CanonicalJson.Value optionalField(CanonicalJson.Obj obj, String key) {
        return obj.entries().stream().filter(e -> e.key().equals(key)).map(CanonicalJson.Entry::value).findFirst().orElse(null);
    }
    private static CanonicalJson.Value at(CanonicalJson.Value root, List<Object> path) {
        for (Object part : path) root = part instanceof Integer i ? ((CanonicalJson.Arr) root).items().get(i)
                : optionalField((CanonicalJson.Obj) root, (String) part);
        return root == null ? CanonicalJson.Null.INSTANCE : root;
    }
    private static CanonicalJson.Value replace(CanonicalJson.Value root, List<Object> path, int depth,
            CanonicalJson.Value value, boolean remove) {
        if (depth == path.size()) return value;
        Object part = path.get(depth);
        if (root instanceof CanonicalJson.Arr array) {
            var items = new ArrayList<>(array.items());
            int index = (Integer) part;
            if (remove && depth == path.size() - 1) items.remove(index);
            else items.set(index, replace(items.get(index), path, depth + 1, value, remove));
            return CanonicalJson.arr(items);
        }
        var object = (CanonicalJson.Obj) root;
        var entries = new ArrayList<CanonicalJson.Entry>();
        boolean found = false;
        for (var entry : object.entries()) {
            if (!entry.key().equals(part)) entries.add(entry);
            else {
                found = true;
                if (!(remove && depth == path.size() - 1)) entries.add(CanonicalJson.e(entry.key(),
                        replace(entry.value(), path, depth + 1, value, remove)));
            }
        }
        if (!found && !remove) entries.add(CanonicalJson.e((String) part, value));
        return CanonicalJson.obj(entries);
    }
}
