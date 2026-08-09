package org.acme.employeescheduling.rest;

import jakarta.annotation.security.RolesAllowed;
import ai.timefold.solver.core.api.solver.SolverManager;
import io.quarkus.runtime.Quarkus;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RolesAllowed("ADMIN")
@Path("/system-info")
@Produces(MediaType.APPLICATION_JSON)
public class SystemInfoResource {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();

    // Dedicated bounded executor for check-update fetches: blocking HTTP requests must NOT run
    // on the JVM's shared ForkJoinPool.commonPool (saturating it with about 16 slow tasks would
    // slow the entire process, and the endpoint is unauthenticated). Daemon threads do not hold
    // up shutdown.
    private static final ExecutorService UPDATE_POOL = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "check-updates");
        t.setDaemon(true);
        return t;
    });

    // Cache the /check-updates result: published versions do not change from one minute to the
    // next, avoiding 16 repeated fetches every time System Info is opened.
    private static final long CACHE_TTL_MS = 3_600_000L; // 1 hour
    private static volatile Map<String, String> cachedInput;
    private static volatile Map<String, UpdateInfo> cachedResult;
    private static volatile long cachedAt;

    // Fallback versions shown when the MANIFEST does not expose Implementation-Version (typical
    // in dev mode). They MUST remain aligned with pom.xml: SystemInfoVersionFallbackTest fails
    // the build if they diverge, so a dependency bump cannot leave them stale.
    static final String FALLBACK_TIMEFOLD = "1.33.0";
    static final String FALLBACK_QUARKUS = "3.37.4";
    // Hibernate has no <version> in the POM: its version comes from the Quarkus BOM
    // (dependencyManagement). Update this fallback when version.io.quarkus is bumped
    // (there is no POM anchor, so no consistency test is possible).
    static final String FALLBACK_HIBERNATE = "7.4.5.Final";

    @Inject
    ObjectMapper mapper;

    @Inject
    DataSource dataSource;

    @ConfigProperty(name = "quarkus.application.version")
    String applicationVersion;

    // Repository endpoints for update checks, overridable through application.properties/.env
    // for deployments behind corporate proxies, Nexus/Artifactory mirrors, or air-gapped networks.
    @ConfigProperty(name = "updates.npm.registry", defaultValue = "https://registry.npmjs.org/")
    String npmRegistry;

    @ConfigProperty(name = "updates.maven.repository", defaultValue = "https://repo1.maven.org/maven2/")
    String mavenRepository;

    @ConfigProperty(name = "updates.adoptium.api", defaultValue = "https://api.adoptium.net/v3/")
    String adoptiumApi;

    // Where a new application version is announced. Overridable like the others: an empty value
    // disables the check entirely for isolated networks or users who do not want the application
    // to contact the internet at startup.
    @ConfigProperty(name = "updates.app.releases-api",
            defaultValue = "https://api.github.com/repos/MirkoUgoliniDev/employee-scheduling/releases/latest")
    String appReleasesApi;

    private static volatile AppUpdate cachedAppUpdate;
    private static volatile long appUpdateCachedAt;

    /**
     * @brief Reports whether a published version is newer than the installed one.
     *
     * @details Fails silently: without a network, behind a proxy, or with an exhausted API quota,
     *          it responds "no update" instead of returning an error. An unavailable notice
     *          must not become a failure message.
     *
     *          The installed version is declared by jpackage in the package's .cfg file
     *          ({@code -Djpackage.app-version}); outside the package, the Maven version is used,
     *          where the comparison is always negative, as intended.
     */
    @GET
    @Path("/app-update")
    public AppUpdate appUpdate() {
        String current = installedAppVersion();
        if (appReleasesApi == null || appReleasesApi.isBlank())
            return new AppUpdate(current, null, false, null);

        AppUpdate cached = cachedAppUpdate;
        if (cached != null && current.equals(cached.current())
                && System.currentTimeMillis() - appUpdateCachedAt < CACHE_TTL_MS)
            return cached;

        AppUpdate result = new AppUpdate(current, null, false, null);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(appReleasesApi))
                    .header("Accept", "application/vnd.github+json")
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var node = mapper.readTree(response.body());
                String tag = node.path("tag_name").asText("");
                String url = node.path("html_url").asText("");
                if (!tag.isBlank()) {
                    String latest = normalizeVersion(tag);
                    result = new AppUpdate(current, latest, isNewerVersion(latest, current),
                            url.isBlank() ? null : url);
                }
            }
        } catch (Exception ignored) {
            // No network, proxy failure, or exhausted quota: the notice simply does not appear.
        }
        cachedAppUpdate = result;
        appUpdateCachedAt = System.currentTimeMillis();
        return result;
    }

    /**
     * @brief Closes the application (desktop package only).
     * @details Restricted to ADMIN users: stops the process after responding with 200.
     */
    @POST
    @Path("/exit")
    @RolesAllowed({"ADMIN", "CAPOSALA"})
    public Response exit() {
        new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            Quarkus.asyncExit(0);
        }, "ui-exit").start();
        return Response.ok(Map.of("exiting", true)).build();
    }

    /** @brief Running application version: the package version when available. */
    private String installedAppVersion() {
        String packaged = System.getProperty("jpackage.app-version");
        return packaged != null && !packaged.isBlank() ? packaged : applicationVersion;
    }

    /** @brief Removes a tag's initial "v" and everything after a hyphen or plus sign. */
    static String normalizeVersion(String version) {
        if (version == null) return "";
        String cleaned = version.trim();
        if (cleaned.startsWith("v") || cleaned.startsWith("V")) cleaned = cleaned.substring(1);
        int cut = cleaned.indexOf('-');
        if (cut < 0) cut = cleaned.indexOf('+');
        return cut >= 0 ? cleaned.substring(0, cut) : cleaned;
    }

    /**
     * @brief Numeric component comparison: 1.2 &gt; 1.1.9, with missing parts treated as 0.
     * @details Thus "1.1" and "1.1.0" are the same version, which is needed because the POM
     *          uses 1.1-SNAPSHOT while the package declares 1.1.0.
     */
    static boolean isNewerVersion(String candidate, String current) {
        int[] a = versionParts(candidate);
        int[] b = versionParts(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int left = i < a.length ? a[i] : 0;
            int right = i < b.length ? b[i] : 0;
            if (left != right) return left > right;
        }
        return false;
    }

    private static int[] versionParts(String version) {
        String cleaned = normalizeVersion(version);
        if (cleaned.isBlank()) return new int[0];
        String[] pieces = cleaned.split("\\.");
        int[] parts = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            try {
                parts[i] = Integer.parseInt(pieces[i].replaceAll("\\D", ""));
            } catch (NumberFormatException e) {
                parts[i] = 0;
            }
        }
        return parts;
    }

    @GET
    public SystemInfo get() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            var database = connection.getMetaData();
            String productName = database.getDatabaseProductName();
            return new SystemInfo(
                    applicationVersion,
                    packageVersion(SolverManager.class, FALLBACK_TIMEFOLD),
                    packageVersion(Quarkus.class, FALLBACK_QUARKUS),
                    packageVersion(org.hibernate.Session.class, FALLBACK_HIBERNATE),
                    Runtime.version().toString(),
                    productName,
                    database.getDatabaseProductVersion(),
                    database.getDriverName(),
                    database.getDriverVersion(),
                    databaseUpdateComponent(productName));
        }
    }

    /** Checks published versions only; downloads and changes nothing. */
    @POST
    @Path("/check-updates")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, UpdateInfo> checkUpdates(Map<String, String> installed) {
        if (installed == null) return new LinkedHashMap<>();
        // Cache: same input and still fresh → no network fetch.
        Map<String, UpdateInfo> cached = cachedResult;
        if (cached != null && installed.equals(cachedInput)
                && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
            return cached;
        }
        Map<String, CompletableFuture<UpdateInfo>> tasks = new LinkedHashMap<>();
        installed.forEach((component, current) -> tasks.put(component,
                CompletableFuture.supplyAsync(() -> checkOne(component, current), UPDATE_POOL)));
        Map<String, UpdateInfo> result = new LinkedHashMap<>();
        tasks.forEach((component, task) -> {
            try { result.put(component, task.join()); }
            catch (Exception e) { result.put(component, new UpdateInfo("UNAVAILABLE", null)); }
        });
        // Do not cache a total failure (network down) for an hour: store it only if at least one
        // component responded, so a transient failure can be retried.
        boolean anyOk = result.values().stream().anyMatch(u -> !"UNAVAILABLE".equals(u.status()));
        if (anyOk) {
            cachedInput = installed;
            cachedResult = result;
            cachedAt = System.currentTimeMillis();
        }
        return result;
    }

    private UpdateInfo checkOne(String component, String current) {
        try {
            String latest = switch (component) {
                case "timefold" -> latestMaven("ai.timefold.solver", "timefold-solver-core");
                case "quarkus" -> latestMaven("io.quarkus", "quarkus-core");
                case "hibernate" -> latestMaven("org.hibernate.orm", "hibernate-core");
                case "sqlite" -> latestMaven("org.xerial", "sqlite-jdbc");
                case "postgresql" -> latestMaven("org.postgresql", "postgresql");
                case "react", "typescript", "vite", "bootstrap", "jspdf", "i18next", "zustand" -> latestNpm(component);
                case "reactI18next" -> latestNpm("react-i18next");
                case "reactBootstrap" -> latestNpm("react-bootstrap");
                case "fontawesome" -> latestNpm("@fortawesome/free-solid-svg-icons");
                case "visTimeline" -> latestNpm("vis-timeline");
                case "reactHotToast" -> latestNpm("react-hot-toast");
                case "java" -> latestJavaLts();
                default -> null;
            };
            if (latest == null || latest.isBlank()) return new UpdateInfo("UNAVAILABLE", null);
            return new UpdateInfo(compareVersions(latest, current) > 0 ? "UPDATE_AVAILABLE" : "UP_TO_DATE", latest);
        } catch (Exception e) {
            return new UpdateInfo("UNAVAILABLE", null);
        }
    }

    private String latestNpm(String packageName) throws Exception {
        String encoded = packageName.startsWith("@") ? packageName.replace("/", "%2F") : packageName;
        return fetch(npmRegistry + encoded).path("dist-tags").path("latest").asText(null);
    }

    private String latestMaven(String group, String artifact) throws Exception {
        String path = group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml";
        String xml = fetchText(mavenRepository + path, "application/xml");
        Matcher release = Pattern.compile("<release>([^<]+)</release>").matcher(xml);
        return release.find() ? release.group(1) : null;
    }

    private String latestJavaLts() throws Exception {
        return fetch(adoptiumApi + "info/available_releases").path("most_recent_lts").asText(null);
    }

    private JsonNode fetch(String url) throws Exception {
        return mapper.readTree(fetchText(url, "application/json"));
    }

    private String fetchText(String url, String accept) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6))
                .header("Accept", accept).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("HTTP " + response.statusCode());
        return response.body();
    }

    private static int compareVersions(String a, String b) {
        String[] av = a.split("[^0-9]+");
        String[] bv = b.split("[^0-9]+");
        int length = Math.max(av.length, bv.length);
        for (int i = 0; i < length; i++) {
            int ai = i < av.length && !av[i].isEmpty() ? Integer.parseInt(av[i]) : 0;
            int bi = i < bv.length && !bv[i].isEmpty() ? Integer.parseInt(bv[i]) : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    private static String packageVersion(Class<?> type, String fallback) {
        String version = type.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? fallback : version;
    }

    private static String databaseUpdateComponent(String productName) {
        if ("PostgreSQL".equalsIgnoreCase(productName)) return "postgresql";
        if ("SQLite".equalsIgnoreCase(productName)) return "sqlite";
        return null;
    }

    public record SystemInfo(String backendVersion, String timefoldVersion, String quarkusVersion,
            String hibernateVersion, String javaVersion, String databaseProductName,
            String databaseProductVersion, String jdbcDriverName, String jdbcDriverVersion,
            String databaseUpdateComponent) {
    }

    public record UpdateInfo(String status, String latestVersion) {
    }

    /**
     * @brief Application-version check outcome.
     * @param current          running version
     * @param latest           latest published version, null if the check failed or is disabled
     * @param updateAvailable  true only if {@code latest} is actually greater than {@code current}
     * @param releaseUrl       download page, null if unavailable
     */
    public record AppUpdate(String current, String latest, boolean updateAvailable, String releaseUrl) {
    }
}
