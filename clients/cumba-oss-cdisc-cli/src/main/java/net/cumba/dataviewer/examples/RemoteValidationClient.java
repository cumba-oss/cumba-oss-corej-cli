package net.cumba.dataviewer.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.jspecify.annotations.Nullable;

/**
 * Thin HTTP client for the corej REST validation service, used by {@link CdiscValidate}'s
 * {@code --remote} mode. Wraps the JDK {@link HttpClient}; applies a resolved {@code Authorization}
 * header (basic or bearer) to every request; parses responses with Jackson.
 */
final class RemoteValidationClient
{

    private final String baseUrl;

    private final @Nullable String authHeader;

    private final HttpClient http;

    private final ObjectMapper json = new ObjectMapper();

    RemoteValidationClient(String baseUrl, @Nullable String authHeader)
    {
        this.baseUrl = baseUrl;
        this.authHeader = authHeader;
        this.http = HttpClient.newHttpClient();
    }


    String createSession() throws IOException, InterruptedException
    {
        HttpResponse<byte[]> r = send(authed(HttpRequest.newBuilder(uri("/api/sessions")))
                .header("Accept", "application/json").POST(BodyPublishers.noBody()));
        require(r, 201, "create session");
        return json.readTree(body(r)).path("sessionId").asText();
    }


    void uploadFile(String sessionId, Path file, String filename)
        throws IOException, InterruptedException
    {
        String boundary = "corejBoundary" + UUID.randomUUID();
        byte[] body = multipart(boundary, filename, Files.readAllBytes(file));
        HttpResponse<byte[]> r = send(
                authed(HttpRequest.newBuilder(uri("/api/sessions/" + sessionId + "/files")))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(BodyPublishers.ofByteArray(body)));
        require(r, 201, "upload " + filename);
    }


    String startCheck(String sessionId, Map<String, Object> request)
        throws IOException, InterruptedException
    {
        String requestJson = json.writeValueAsString(request);
        HttpResponse<byte[]> r = send(
                authed(HttpRequest.newBuilder(uri("/api/sessions/" + sessionId + "/checks")))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8)));
        require(r, 201, "start check");
        return json.readTree(body(r)).path("checkRunId").asText();
    }


    JsonNode awaitStatus(String runId, int waitSeconds) throws IOException, InterruptedException
    {
        HttpResponse<byte[]> r = send(authed(HttpRequest
                .newBuilder(uri("/api/checks/" + runId + "/status?waitSeconds=" + waitSeconds)))
                        .header("Accept", "application/json").GET());
        require(r, 200, "status");
        return json.readTree(body(r));
    }


    String fetchReport(String runId) throws IOException, InterruptedException
    {
        HttpResponse<byte[]> r = send(
                authed(HttpRequest.newBuilder(uri("/api/checks/" + runId + "/report")))
                        .header("Accept", "application/json").GET());
        require(r, 200, "fetch report");
        return body(r);
    }


    /**
     * Fetches the v2 combined-finding report ({@code GET /api/checks/{id}/report-v2}). Mirrors
     * {@link #fetchReport(String)}; the body is the v2 JSON document served verbatim by the server.
     *
     * @param runId
     *            the check run id
     * @return the v2 report JSON
     * @throws IOException
     *             on transport / non-200 response
     * @throws InterruptedException
     *             if interrupted while waiting for the response
     */
    String fetchReportV2(String runId) throws IOException, InterruptedException
    {
        HttpResponse<byte[]> r = send(
                authed(HttpRequest.newBuilder(uri("/api/checks/" + runId + "/report-v2")))
                        .header("Accept", "application/json").GET());
        require(r, 200, "fetch report v2");
        return body(r);
    }


    private HttpRequest.Builder authed(HttpRequest.Builder b)
    {
        return authHeader != null ? b.header("Authorization", authHeader) : b;
    }


    private URI uri(String path)
    {
        return URI.create(baseUrl + path);
    }


    private HttpResponse<byte[]> send(HttpRequest.Builder b)
        throws IOException, InterruptedException
    {
        // Ask the server to gzip large responses (reports can exceed 70 MiB). The JDK HttpClient
        // does not auto-decompress, so body(...) handles the Content-Encoding: gzip case.
        return http.send(b.header("Accept-Encoding", "gzip").build(), BodyHandlers.ofByteArray());
    }


    /**
     * Decodes a response body to a UTF-8 string, transparently gunzipping it when the server
     * answered with {@code Content-Encoding: gzip}.
     */
    private static String body(HttpResponse<byte[]> r) throws IOException
    {
        byte[] raw = r.body();
        if (raw == null || raw.length == 0)
        {
            return "";
        }
        String encoding = r.headers().firstValue("Content-Encoding").orElse("");
        if ("gzip".equalsIgnoreCase(encoding))
        {
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw)))
            {
                return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new String(raw, StandardCharsets.UTF_8);
    }


    private void require(HttpResponse<byte[]> r, int expected, String what) throws IOException
    {
        if (r.statusCode() != expected)
        {
            String detail = extractDetail(body(r));
            throw new IOException("Remote " + what + " failed: HTTP " + r.statusCode()
                    + (detail != null ? " - " + detail : ""));
        }
    }


    private @Nullable String extractDetail(String body)
    {
        if (body == null || body.isBlank())
        {
            return null;
        }
        try
        {
            JsonNode detail = json.readTree(body).get("detail");
            return detail != null && !detail.isNull() ? detail.asText() : null;
        }
        catch (IOException e)
        {
            return null;
        }
    }


    private static byte[] multipart(String boundary, String filename, byte[] content)
        throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"filename\"\r\n\r\n" + filename + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
