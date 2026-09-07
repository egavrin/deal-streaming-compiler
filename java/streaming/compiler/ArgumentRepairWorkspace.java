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

    ArgumentRepairWorkspace(String toolName, Map<?, ?> schema, CanonicalJson.Obj candidate) {
        this.toolName = toolName;
        this.schema = schema;
        this.candidate = candidate;
        issue = locate(candidate, schema, List.of());
        requireLocal(issue);
    }

    CanonicalJson.Obj candidate() { return candidate; }
    boolean complete() { return issue == null; }
    int rounds() { return rounds; }
    String ticket() { return DealCompilerWorkspace.digest(encode(candidate) + encode(issue.path())); }

    Map<String, Object> tool() {
        var props = new LinkedHashMap<String, Object>();
        props.put("ticket", Map.of("type", "string", "const", ticket()));
        if (issue.remove()) props.put("remove", Map.of("type", "boolean", "const", true));
        else props.put("replacement", issue.schema());
        return Map.of("name", "patch_tool_argument", "description",
                "Replace only the engine-selected invalid argument. The rest of the pending tool call is immutable.",
                "parameters", deal.compiler.DealConstruction.objectSchema(props));
    }

    String request(Map<String, Object> base) {
        var input = new LinkedHashMap<String, Object>();
        input.put("requiredArtifact", base.getOrDefault("argumentArtifact", "deal"));
        input.put("tool", toolName);
        input.put("path", issue.path());
        input.put("actual", issue.actual());
        input.put("parent", at(candidate, issue.path().subList(0, issue.path().size() - 1)));
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
                + "Use patch_tool_argument with the issued ticket and only the replacement value, not the whole batch. "
                + "The replacement schema is authoritative. A reference to an available handle is its id as a STRING, not an object. "
                + "Do not invent handles or nest compiler operations. Do not output source code. "
                + "For a missing dependency that cannot be expressed here, do not replace it with unrelated data.";
        String encodedInput = encode(input);
        var tools = List.of(tool());
        String encodedTools = encode(tools);
        var result = new LinkedHashMap<String, Object>(base);
        result.put("instructions", instructions);
        result.put("input", encodedInput);
        result.put("tools", tools);
        result.put("argumentRepairRound", rounds);
        result.put("surfaceDigest", DealCompilerWorkspace.digest(encodedInput + encodedTools));
        int size = (encodedInput + encodedTools + instructions).getBytes(StandardCharsets.UTF_8).length;
        result.put("surfaceMetrics", Map.of("inputBytes", encodedInput.getBytes(StandardCharsets.UTF_8).length,
                "toolSchemaBytes", encodedTools.getBytes(StandardCharsets.UTF_8).length,
                "instructionBytes", instructions.getBytes(StandardCharsets.UTF_8).length, "approxInputTokens", (size + 3) / 4));
        return encode(result);
    }

    void patch(CanonicalJson.Obj patch) {
        CanonicalRefinementSession.validateSchema(patch, (Map<?, ?>) tool().get("parameters"));
        if (++rounds > 8) throw new IllegalArgumentException("Argument repair budget exhausted");
        var next = (CanonicalJson.Obj) replace(candidate, issue.path(), 0,
                issue.remove() ? CanonicalJson.Null.INSTANCE : field(patch, "replacement"), issue.remove());
        if (encode(next).equals(encode(candidate))) {
            if (++unchanged >= 2) throw new IllegalArgumentException("Argument repair made no progress twice");
        } else unchanged = 0;
        var nextIssue = locate(next, schema, List.of());
        if (nextIssue != null) requireLocal(nextIssue);
        candidate = next;
        issue = nextIssue;
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
