package spotlightextractor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;


public class Worker implements Runnable {

    private static final Logger logger = LogManager.getLogger(Worker.class);

    private static final String URL = "https://arc.msn.com/v3/Delivery/Cache?pid=%s&ctry=%s&lc=en&fmt=json&lo=%s";
    private static final List<String> PID_LIST = Arrays.asList("209567", "279978", "209562");
    private static final List<String> COUNTRIES_LIST = Arrays.asList("en", "de", "us");
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/75.0.3770.100 Safari/537.36";
    private static final Random randomGenerator = new Random();
    private static final String IMAGES_FOLDER = "images";
    private static final String JPG = "JPG";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static List<File> imagesList;
    private CloseableHttpClient httpclient;

    Worker(CloseableHttpClient httpclient) {
        this.httpclient = httpclient;
    }

    public static void initImageList() {
        imagesList = new ArrayList<>();
        File dir = new File(IMAGES_FOLDER);
        File[] directoryListing = dir.listFiles();
        if (directoryListing != null) {
            for (File file : directoryListing) {
                if (FilenameUtils.getExtension(file.getName()).toUpperCase().equals(JPG)) {
                    imagesList.add(file);
                }
            }
        }
    }

    public static List<File> getImageList() {
        return imagesList;
    }

    public void run() {
        fetchData();
    }

    private void fetchData() {
        for (String country : COUNTRIES_LIST) {
            try {
                ImageData imageData = fetchImageData(country);
                byte[] imageBytes = fetchImage(imageData);
                if (!saveImageIfNotExists(imageData, imageBytes)) {
                    logger.info(String.format("New image added : %s", imageData));
                } else {
                    logger.debug(String.format("File already exists : %s", imageData));
                }
            } catch (Exception e) {
                logger.error("Error while get image", e);
            }
        }
    }

    private ImageData fetchImageData(String country) throws IOException {
        CloseableHttpResponse response = null;
        String imageUrl = null;
        String imageDescription = null;
        String imageId = null;
        HttpEntity entity1;
        String body;
        try {
            HttpGet httpGet = new HttpGet(String.format(URL, PID_LIST.get(randomGenerator.nextInt(PID_LIST.size())), country, randomGenerator.nextInt(900000) + 100000));
            httpGet.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
            httpGet.setHeader(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate");
            response = httpclient.execute(httpGet);
            if (response.getStatusLine().getStatusCode() != 200) {
                logger.error(String.format("Fail to retrive new image data: %s", response.getStatusLine()));
            }
            entity1 = response.getEntity();
            body = IOUtils.toString(entity1.getContent(), "UTF-8");
            String item = null;
            try {
				item = OBJECT_MAPPER.readTree(body).get("batchrsp").get("items").get(0).get("item").asText();
			} catch (Exception e) {
				logger.error(String.format("Failed to parse json: %s", body));
				throw e;
			}				
            JsonNode jsonNode = OBJECT_MAPPER.readTree(item);
            imageUrl = jsonNode.get("ad").get("image_fullscreen_001_landscape").get("u").asText();
            if (imageUrl.lastIndexOf("?") != -1) {
                imageId = imageUrl.substring(imageUrl.lastIndexOf("/") + 1, imageUrl.lastIndexOf("?"));
            } else {
                imageId = imageUrl.substring(imageUrl.lastIndexOf("/") + 1);
            }
            jsonNode = jsonNode.get("ad").get("title_text");
            if (jsonNode != null) {
                imageDescription = jsonNode.get("tx").asText();
            } else {
                imageDescription = imageId;
            }
        } catch (IOException e) {
            logger.error("Error while fetching image data", e);
            throw e;
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    logger.error(String.format("Error while close response: %s", response.getStatusLine()));
                }
            }
        }
        return new ImageData(imageId, imageUrl, imageDescription);
    }

    private byte[] fetchImage(ImageData imageData) throws Exception {
        CloseableHttpResponse response = null;
        try {
            HttpGet httpGet = new HttpGet(imageData.getUrl());
            response = httpclient.execute(httpGet);
            if (response.getStatusLine().getStatusCode() != 200) {
                logger.error(String.format("Fail to retrive image content: %s", response.getStatusLine()));
            }
            HttpEntity entity = response.getEntity();
            return entity.getContent().readAllBytes();
        } catch (Exception e) {
            logger.error(String.format("Error while getting image: %s", imageData));
            throw e;
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    logger.error(String.format("Error while try close response: %s", response.getStatusLine()));
                }
            }
        }
    }

    private synchronized boolean saveImageIfNotExists(ImageData imageData, byte[] newImageBytes) throws IOException {
        for (int i = 0; i < imagesList.size(); i++) {
            FileInputStream fileInputStream = new FileInputStream(imagesList.get(i));
            byte[] imageBytes = IOUtils.toByteArray(fileInputStream);
            if (Arrays.equals(imageBytes, newImageBytes)) {
                fileInputStream.close();
                return true;
            }
            fileInputStream.close();
        }
        File nextFile = getNextFile(imageData.getDescription());
        Files.copy(new ByteArrayInputStream(newImageBytes), nextFile.toPath());
        imagesList.add(nextFile);
        Main.addWorkers();
        return false;
    }

    private File getNextFile(String filename) {
        File file = new File("images/" + filename + ".jpg");
        Integer fileNo = 1;
        while (file.exists()) {
            file = new File("images/" + filename + fileNo.toString() + ".jpg");
            fileNo++;
        }
        return file;
    }
}

