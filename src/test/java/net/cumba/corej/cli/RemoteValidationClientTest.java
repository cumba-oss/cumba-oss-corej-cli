package net.cumba.corej.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link RemoteValidationClient}. Each REST call is exercised against a stub
 * {@link HttpServer} that records the requests it receives and replies with canned bodies / status
 * codes. The assertions pin down: the value extracted from each response (so the empty-return
 * mutants die), the status-code guard in {@code require} (so removing it or negating its
 * conditional fails), the {@code Authorization} header application, and the multipart request
 * layout.
 */
class RemoteValidationClientTest
{

    private HttpServer server;

    private final ConcurrentLinkedQueue<Recorded> requests = new ConcurrentLinkedQueue<>();

    private volatile int statusCode = 200;

    private volatile String responseBody = "{}";

    private volatile boolean gzipResponse;

    @BeforeEach
    void start() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/", new RecordingHandler());
        server.start();
    }


    @AfterEach
    void stop()
    {
        if (server != null)
        {
            server.stop(0);
        }
    }


    private String baseUrl()
    {
        return "http://localhost:" + server.getAddress().getPort();
    }


    private RemoteValidationClient client(String auth)
    {
        return new RemoteValidationClient(baseUrl(), auth);
    }


    @Test
    void createSession_returnsParsedSessionId_andPostsToSessions() throws Exception
    {
        statusCode = 201;
        responseBody = "{\"sessionId\":\"abc-123\"}";

        String id = client(null).createSession();

        assertEquals("abc-123", id, "createSession must return the parsed sessionId, not \"\"");
        Recorded r = requests.poll();
        assertNotNull(r);
        assertEquals("POST", r.method);
        assertEquals("/api/sessions", r.path);
    }


    @Test
    void createSession_wrongStatus_throwsWithCodeAndDetail()
    {
        statusCode = 500;
        responseBody = "{\"detail\":\"boom in session\"}";

        IOException ex = assertThrows(IOException.class, () -> client(null).createSession());
        assertTrue(ex.getMessage().contains("create session"), ex.getMessage());
        assertTrue(ex.getMessage().contains("HTTP 500"), ex.getMessage());
        // extractDetail must surface the "detail" field, appended after " - ".
        assertTrue(ex.getMessage().contains(" - boom in session"), ex.getMessage());
    }


    @Test
    void createSession_wrongStatusBlankBody_omitsDetailSuffix()
    {
        // A 201 is required; sending 200 with an empty body means extractDetail returns null and
        // no " - <detail>" suffix is appended. Pins the blank-body branch of extractDetail.
        statusCode = 200;
        responseBody = "";

        IOException ex = assertThrows(IOException.class, () -> client(null).createSession());
        String msg = ex.getMessage();
        assertTrue(msg.contains("HTTP 200"), msg);
        assertTrue(msg.endsWith("HTTP 200"), "no detail suffix expected for a blank body: " + msg);
    }


    @Test
    void createSession_wrongStatusNonJsonBody_omitsDetailSuffix()
    {
        // Non-JSON body → extractDetail's IOException catch returns null → no " - " suffix.
        statusCode = 503;
        responseBody = "not json at all";

        IOException ex = assertThrows(IOException.class, () -> client(null).createSession());
        String msg = ex.getMessage();
        assertTrue(msg.contains("HTTP 503"), msg);
        assertTrue(msg.endsWith("HTTP 503"), "unparseable body yields no detail: " + msg);
    }


    @Test
    void createSession_wrongStatusJsonWithoutDetail_omitsDetailSuffix()
    {
        // JSON present but no "detail" node → extractDetail returns null.
        statusCode = 400;
        responseBody = "{\"other\":\"x\"}";

        IOException ex = assertThrows(IOException.class, () -> client(null).createSession());
        assertTrue(ex.getMessage().endsWith("HTTP 400"),
                "missing 'detail' node yields no suffix: " + ex.getMessage());
    }


    @Test
    void createSession_wrongStatusNullDetail_omitsDetailSuffix()
    {
        // "detail": null → extractDetail's isNull() guard returns null.
        statusCode = 400;
        responseBody = "{\"detail\":null}";

        IOException ex = assertThrows(IOException.class, () -> client(null).createSession());
        assertTrue(ex.getMessage().endsWith("HTTP 400"),
                "null 'detail' node yields no suffix: " + ex.getMessage());
    }


    @Test
    void startCheck_returnsCheckRunId_andSendsJsonBody() throws Exception
    {
        statusCode = 201;
        responseBody = "{\"checkRunId\":\"run-77\"}";

        String runId = client(null).startCheck("sess-1",
                Map.of("standard", "sdtmig", "version", "3-4"));

        assertEquals("run-77", runId, "startCheck must return parsed checkRunId, not \"\"");
        Recorded r = requests.poll();
        assertNotNull(r);
        assertEquals("POST", r.method);
        assertEquals("/api/sessions/sess-1/checks", r.path);
        assertEquals("application/json", r.contentType);
        assertTrue(r.body.contains("\"standard\":\"sdtmig\""), r.body);
        assertTrue(r.body.contains("\"version\":\"3-4\""), r.body);
    }


    @Test
    void startCheck_wrongStatus_throws()
    {
        statusCode = 200; // expects 201
        responseBody = "{}";

        IOException ex = assertThrows(IOException.class,
                () -> client(null).startCheck("s", Map.of("k", "v")));
        assertTrue(ex.getMessage().contains("start check"), ex.getMessage());
        assertTrue(ex.getMessage().contains("HTTP 200"), ex.getMessage());
    }


    @Test
    void awaitStatus_returnsParsedNode_andRequestsStatusWithWaitSeconds() throws Exception
    {
        statusCode = 200;
        responseBody = "{\"status\":\"SUCCEEDED\",\"findingCount\":7}";

        JsonNode node = client(null).awaitStatus("run-9", 42);

        assertNotNull(node, "awaitStatus must return the parsed node, not an empty object");
        assertEquals("SUCCEEDED", node.path("status").asText());
        assertEquals(7, node.path("findingCount").asInt());
        Recorded r = requests.poll();
        assertNotNull(r);
        assertEquals("GET", r.method);
        assertEquals("/api/checks/run-9/status", r.path);
        assertEquals("waitSeconds=42", r.query, "awaitStatus must pass the wait window through");
    }


    @Test
    void awaitStatus_wrongStatus_throws()
    {
        statusCode = 500;
        responseBody = "{\"detail\":\"status blew up\"}";

        IOException ex = assertThrows(IOException.class, () -> client(null).awaitStatus("r", 1));
        assertTrue(ex.getMessage().contains("status"), ex.getMessage());
        assertTrue(ex.getMessage().contains("HTTP 500"), ex.getMessage());
        assertTrue(ex.getMessage().contains("status blew up"), ex.getMessage());
    }


    @Test
    void fetchReport_returnsRawBody() throws Exception
    {
        statusCode = 200;
        responseBody = "{\"Conformance_Details\":{\"Standard\":\"SDTMIG\"}}";

        String report = client(null).fetchReport("run-x");

        assertEquals(responseBody, report, "fetchReport must return the raw response body");
        Recorded r = requests.poll();
        assertNotNull(r);
        assertEquals("GET", r.method);
        assertEquals("/api/checks/run-x/report", r.path);
    }


    @Test
    void fetchReport_wrongStatus_throws()
    {
        statusCode = 404;
        responseBody = "{\"detail\":\"no such run\"}";

        IOException ex = assertThrows(IOException.class, () -> client(null).fetchReport("r"));
        assertTrue(ex.getMessage().contains("fetch report"), ex.getMessage());
        assertTrue(ex.getMessage().contains("HTTP 404"), ex.getMessage());
        assertTrue(ex.getMessage().contains("no such run"), ex.getMessage());
    }


    @Test
    void fetchReportV2_returnsRawBody_fromReportV2Endpoint() throws Exception
    {
        statusCode = 200;
        responseBody = "{\"Report_Version\":\"2.0\",\"Findings\":[]}";

        String report = client(null).fetchReportV2("run-x");

        assertEquals(responseBody, report, "fetchReportV2 must return the raw response body");
        Recorded r = requests.poll();
        assertNotNull(r);
        assertEquals("GET", r.method);
        assertEquals("/api/checks/run-x/report-v2", r.path);
    }


    @Test
    void fetchReportV2_wrongStatus_throws()
    {
        statusCode = 404;
        responseBody = "{\"detail\":\"no such run\"}";

        IOException ex = assertThrows(IOException.class, () -> client(null).fetchReportV2("r"));
        assertTrue(ex.getMessage().contains("fetch report v2"), ex.getMessage());
        assertTrue(ex.getMessage().contains("HTTP 404"), ex.getMessage());
        assertTrue(ex.getMessage().contains("no such run"), ex.getMessage());
    }


    @Test
    void fetchReport_decompressesGzipResponse_andRequestsGzip() throws Exception
    {
        statusCode = 200;
        responseBody = "{\"Conformance_Details\":{\"Standard\":\"SDTMIG\"},\"big\":\"payload\"}";
        gzipResponse = true;

        String report = client(null).fetchReport("run-gz");

        // The client must transparently gunzip a Content-Encoding: gzip body back to the original.
        assertEquals(responseBody, report, "gzip response must be decompressed to the raw JSON");
        Recorded r = requests.poll();
        assertNotNull(r);
        assertEquals("gzip", r.acceptEncoding, "client must advertise Accept-Encoding: gzip");
    }


    @Test
    void uploadFile_postsMultipartBody_withFilenameAndContent(@TempDir Path tmp) throws Exception
    {
        statusCode = 201;
        responseBody = "{}";
        Path file = tmp.resolve("dm.csv");
        Files.writeString(file, "STUDYID,USUBJID\nABC,1\n", StandardCharsets.UTF_8);

        client(null).uploadFile("sess-7", file, "dm.csv");

        Recorded r = requests.poll();
        assertNotNull(r);
        assertEquals("POST", r.method);
        assertEquals("/api/sessions/sess-7/files", r.path);
        assertTrue(r.contentType.startsWith("multipart/form-data; boundary=corejBoundary"),
                r.contentType);
        // The multipart body must carry both the "filename" form field and the file part with
        // its content. Each of the three ByteArrayOutputStream.write calls contributes a slice;
        // dropping any one removes one of these assertions' targets.
        assertTrue(r.body.contains("name=\"filename\""), r.body);
        assertTrue(r.body.contains("name=\"file\"; filename=\"dm.csv\""), r.body);
        assertTrue(r.body.contains("STUDYID,USUBJID"), "file content must be embedded: " + r.body);
        assertTrue(r.body.contains("Content-Type: application/octet-stream"), r.body);
        // Closing boundary appended by the trailing write.
        assertTrue(r.body.contains("--\r\n"), "missing closing boundary: " + r.body);
    }


    @Test
    void uploadFile_wrongStatus_throwsNamingTheFile(@TempDir Path tmp) throws Exception
    {
        statusCode = 500;
        responseBody = "{\"detail\":\"disk full\"}";
        Path file = tmp.resolve("ae.csv");
        Files.writeString(file, "x", StandardCharsets.UTF_8);

        IOException ex = assertThrows(IOException.class,
                () -> client(null).uploadFile("s", file, "ae.csv"));
        assertTrue(ex.getMessage().contains("upload ae.csv"), ex.getMessage());
        assertTrue(ex.getMessage().contains("HTTP 500"), ex.getMessage());
        assertTrue(ex.getMessage().contains("disk full"), ex.getMessage());
    }


    @Test
    void authHeader_appliedWhenPresent() throws Exception
    {
        statusCode = 201;
        responseBody = "{\"sessionId\":\"s\"}";

        client("Bearer xyz").createSession();

        Recorded r = requests.poll();
        assertNotNull(r);
        assertEquals("Bearer xyz", r.authorization, "Authorization header must be sent when set");
    }


    @Test
    void authHeader_absentWhenNull() throws Exception
    {
        statusCode = 201;
        responseBody = "{\"sessionId\":\"s\"}";

        client(null).createSession();

        Recorded r = requests.poll();
        assertNotNull(r);
        assertNull(r.authorization, "no Authorization header when auth is null");
    }

    private static final class Recorded
    {

        String method;

        String path;

        String query;

        String contentType;

        String authorization;

        String acceptEncoding;

        String body;
    }


    private final class RecordingHandler implements HttpHandler
    {

        @Override
        public void handle(HttpExchange ex) throws IOException
        {
            Recorded rec = new Recorded();
            rec.method = ex.getRequestMethod();
            rec.path = ex.getRequestURI().getPath();
            rec.query = ex.getRequestURI().getQuery();
            rec.contentType = ex.getRequestHeaders().getFirst("Content-Type");
            rec.authorization = ex.getRequestHeaders().getFirst("Authorization");
            rec.acceptEncoding = ex.getRequestHeaders().getFirst("Accept-Encoding");
            rec.body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(rec);

            byte[] b = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            if (gzipResponse && b.length > 0)
            {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (GZIPOutputStream gz = new GZIPOutputStream(bos))
                {
                    gz.write(b);
                }
                b = bos.toByteArray();
                ex.getResponseHeaders().add("Content-Encoding", "gzip");
            }
            if (b.length == 0)
            {
                ex.sendResponseHeaders(statusCode, -1);
            }
            else
            {
                ex.sendResponseHeaders(statusCode, b.length);
                try (OutputStream os = ex.getResponseBody())
                {
                    os.write(b);
                }
            }
            ex.close();
        }
    }
}
