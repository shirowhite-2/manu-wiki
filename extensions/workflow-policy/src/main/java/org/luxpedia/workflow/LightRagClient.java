package org.luxpedia.workflow;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Minimal LightRAG API client. Document calls are refused for any workspace the wiki did not create. */
class LightRagClient {
    record Response(int status, Map<String, Object> body) {
        boolean ok() { return status >= 200 && status < 300; }
        String detail() { return AdminOperations.text(body.getOrDefault("detail", body.getOrDefault("message", ""))); }
    }
    final String base; final String apiKey; final Duration timeout; final Set<String> owned;
    final HttpClient http;

    LightRagClient(String base, String apiKey, int timeoutSeconds, Set<String> owned) {
        WorkflowPolicyListener.require(base != null && !base.isBlank(), "LightRAG address is not configured.");
        this.base = base.replaceAll("/+$", ""); this.apiKey = apiKey == null ? "" : apiKey;
        this.timeout = Duration.ofSeconds(timeoutSeconds); this.owned = owned;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    static String query(String workspace) { return "?workspace=" + URLEncoder.encode(workspace, StandardCharsets.UTF_8); }
    private void own(String workspace) {
        WorkflowPolicyListener.require(workspace != null && owned.contains(workspace), "Workspace is not owned by this wiki.");
    }

    Response call(String method, String path, Map<String, Object> json) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout).header("Accept", "application/json");
        if (!apiKey.isEmpty()) { builder.header("X-API-Key", apiKey); }
        if (json == null) { builder.method(method, HttpRequest.BodyPublishers.noBody()); }
        else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(AdminOperations.JSON.writeValueAsString(json), StandardCharsets.UTF_8));
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Map<String, Object> body = new LinkedHashMap<>();
        String raw = response.body() == null ? "" : response.body().strip();
        if (raw.startsWith("{")) {
            try { body.putAll(AdminOperations.JSON.readValue(raw, Map.class)); } catch (Exception malformed) { body.put("detail", "Malformed response"); }
        } else if (!raw.isEmpty()) { body.put("detail", raw.length() > 300 ? raw.substring(0, 300) : raw); }
        return new Response(response.statusCode(), body);
    }

    Response health() throws Exception { return call("GET", "/health", null); }

    /** Names only; used to detect a conflicting workspace before creating one, never to read documents. */
    List<String> workspaces() throws Exception {
        var response = call("GET", "/workspaces", null);
        WorkflowPolicyListener.require(response.ok() && response.body().get("workspaces") instanceof List<?>, "LightRAG workspace list is unavailable.");
        return ((List<?>) response.body().get("workspaces")).stream().map(AdminOperations::text).toList();
    }

    Response createWorkspace(String workspace) throws Exception {
        return call("POST", "/workspaces", Map.of("workspace", workspace));
    }

    Response insert(String workspace, String text, String fileSource) throws Exception {
        own(workspace);
        return call("POST", "/documents/text", Map.of("text", text, "file_source", fileSource, "workspace", workspace));
    }

    Response update(String workspace, String docId, String text, String fileSource) throws Exception {
        own(workspace);
        return call("POST", "/documents/update_document", Map.of("doc_id", docId, "content", text, "file_source", fileSource, "workspace", workspace));
    }

    Response delete(String workspace, String docId) throws Exception {
        own(workspace);
        return call("DELETE", "/documents/delete_document", Map.of("doc_ids", List.of(docId), "delete_file", true,
            "delete_llm_cache", false, "workspace", workspace));
    }

    Response pipeline(String workspace) throws Exception {
        own(workspace);
        return call("GET", "/documents/pipeline_status" + query(workspace), null);
    }

    Response track(String workspace, String trackId) throws Exception {
        own(workspace);
        return call("GET", "/documents/track_status/" + URLEncoder.encode(trackId, StandardCharsets.UTF_8).replace("+", "%20") + query(workspace), null);
    }
}
