package eu.hinsch.thebrick;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Slf4j
@Component
public class DocumentDownloader implements ApplicationRunner {

    private static final String DOCUMENTS_URL = "https://serviceportal.wentzel-dr.de/api/v1/communities/113921/documents?archived";

    private static final String LOGIN_URL = "https://serviceportal.wentzel-dr.de/login";
    private static final String API_VERSION = "1.5.0";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("The Brick Portal Document Downloader by LH");

        String username = getOption(args, "username");
        String password = getOption(args, "password");
        String baseFolder = getOption(args, "folder");

        if (username == null || password == null || baseFolder == null) {
            log.info("""
                                        
                    Usage:
                                        
                      java -jar WenzelDrDownloader.jar --username=<username> --password=<password> --folder=<folder>
                      
                      username/password: the username and password used to log into serviceportal.wentzel-dr.de
                      folder: the folder where the documents will be stored (does not have to exist, but if it does it must be empty)
                                        
                    """);
            System.exit(1);
        }

        log.info("To begin downloading all documents for account '{}' and storing them in folder '{}' press ENTER", username, baseFolder);
        System.in.read();

        createFolderIfNotExists(baseFolder);
        if (!isEmpty(Path.of(baseFolder))) {
            log.error("Folder {} is not empty! Aborting", baseFolder);
            System.exit(1);
        }

        Document[] documents = getDocumentList(getSession(username, password));
        processDocuments(documents, baseFolder);

        log.info("Finished downloading {} documents", documents.length);
    }

    public boolean isEmpty(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> entries = Files.list(path)) {
                return entries.findFirst().isEmpty();
            }
        }

        return false;
    }

    private static String getOption(ApplicationArguments args, String optionName) {
        List<String> argList = args.getOptionValues(optionName);
        if (argList == null || argList.size() != 1) {
            log.error("{} is missing, aborting.", optionName);
            return null;
        }
        return argList.get(0);
    }

    private Document[] getDocumentList(String session) throws URISyntaxException, IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(new URI(DOCUMENTS_URL))
                .header("Cookie", session)
                .header("API-Version", API_VERSION)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            log.error("Error downloading document list, status code {}", response.statusCode());
            System.exit(1);
        }
        return objectMapper.readValue(response.body(), Document[].class);
    }

    private String getSession(String username, String password) throws URISyntaxException, IOException, InterruptedException {
        log.info("Logging in as {}", username);
        HttpRequest request = HttpRequest.newBuilder(new URI(LOGIN_URL))
                .POST(HttpRequest.BodyPublishers.ofString(" {\"username\":\"" + username + "\",\"password\":\"" + password + "\",\"keepLoggedIn\":true}"))
                .header("API-Version", API_VERSION)
                .header("Accept", "application/json")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("App-Version", "33.46.0")
                .header("Content-Type", "application/json")
                .header("Cookie", "last_account=881")
                .header("Origin", "https://serviceportal.wentzel-dr.de")
                .header("Referer", "https://serviceportal.wentzel-dr.de/app/login")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/105.0.0.0 Safari/537.36")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("csrf-token", "null")
                .header("sec-ch-ua", "\"Google Chrome\";v=\"105\", \"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"105\"")
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"macOS\"")
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            log.error("Error logging in: {} - {}", response.statusCode(), response.body());
            System.exit(1);
        }
        String setCookie = response.headers().firstValue("set-cookie").orElseThrow();
        String session = setCookie.split("; ")[0];
        Assert.isTrue(session.contains("user_session="), "user_session not found at expected place in cookie");
        log.info("Login successful");
        return session;
    }

    private void processDocuments(Document[] documents, String baseFolder) throws URISyntaxException, IOException, InterruptedException {
        log.info("About to download {} documents", documents.length);
        for (int i = 0; i < documents.length; i++) {
            Document document = documents[i];
            String folder = document.type() != null ? File.separator + document.type().title() : "";
            String typeFolder = baseFolder + folder;
            createFolderIfNotExists(typeFolder);

            String docName = "[" + i + "] " + document.name();
            docName = docName.replace("/", "_");
            log.info("Downloading file {}/{} ({})", folder, docName, document.linkToDocument());
            Path target = Paths.get(typeFolder, docName);

            downloadDocument(document, target);
            setFileDates(document, target);
        }
    }

    private void downloadDocument(Document document, Path target) throws URISyntaxException, IOException, InterruptedException {
        HttpRequest fileRequest = HttpRequest.newBuilder(new URI(document.linkToDocument()))
                .GET()
                .build();
        HttpResponse<Path> fileResponse = client.send(fileRequest, HttpResponse.BodyHandlers.ofFile(target));
        if (fileResponse.statusCode() >= 400) {
            log.error("Error downloading {}", fileResponse.statusCode());
        }
    }

    private static void setFileDates(Document document, Path target) throws IOException {
        Instant created = Instant.parse(document.createdAt());
        BasicFileAttributeView fileAttributeView = Files.getFileAttributeView(target, BasicFileAttributeView.class);
        FileTime fileTime = FileTime.from(created);
        fileAttributeView.setTimes(fileTime, fileTime, fileTime);
    }

    private static void createFolderIfNotExists(String pathname) {
        File baseDir = new File(pathname);
        if (!baseDir.exists()) {
            log.info("Creating folder {}", pathname);
            if (!baseDir.mkdirs()) {
                log.warn("Could not create folder {}", pathname);
            }
        }
    }

    private record Document(
            String id,
            String title,
            int filesize,
            String name,
            String extension,
            Type type,
            String createdAt,
            String downloadedAt,
            String sharedWithLocalized,
            String linkToDocument,
            String sharedIn,
            boolean isArchived
    ) {}

    private record Type(int id, String title) {}
}
