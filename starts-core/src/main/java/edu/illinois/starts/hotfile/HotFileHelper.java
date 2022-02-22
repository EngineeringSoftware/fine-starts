package edu.illinois.starts.hotfile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.nio.file.Paths;
import static java.util.Collections.reverseOrder;

public class HotFileHelper {

    public static final String DEP_HOTFILE = "dep";
    public static final String CHANGE_FRE_HOTFILE = "freq";
    public static final String SIZE_HOTFILE = "size";
    public static List<String> hotFiles;

    public static List<String> getHotFiles(String hotFileType, String percentage, String artifactsDir, String zlcFile) {
        List<String> hotFiles = new ArrayList<>();
        if (hotFileType.equals(DEP_HOTFILE)) {
            File zlc = new File(artifactsDir, zlcFile);
            if (!zlc.exists()){
                return hotFiles;
            }
            HashMap<String, Long> fileToDeps = new HashMap<>();
            try {
                List<String> zlcLines = Files.readAllLines(zlc.toPath(), Charset.defaultCharset());
                for (String line : zlcLines) {
                    String[] parts = line.split(" ");
                    String stringURL = parts[0];
                    List<String> tests = parts.length == 3 ? Arrays.asList(parts[2].split(",")) : new ArrayList<String>();
                    fileToDeps.put(stringURL, Long.valueOf(tests.size()));
                }
                return sortAndExtract(fileToDeps, percentage);
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        } else if (hotFileType.equals(CHANGE_FRE_HOTFILE)) {
            HashMap<String, Long> fileToFreq = new HashMap<>();
            int numOfCommits = 50;
            String command = "git log -" + String.valueOf(numOfCommits) + " --name-only --format=\"\"";
            Runtime r = Runtime.getRuntime();
            String[] commands = {"bash", "-c", command};
            try {
                Process p = r.exec(commands);
        
                p.waitFor();
                BufferedReader b = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = "";
        
                while ((line = b.readLine()) != null) {
                    if (line.length() > 0 && line.endsWith(".java")) {
                        String[] lineArray = line.split("/");
                        String fileName = lineArray[lineArray.length - 1];
                        fileName = fileName.substring(0, fileName.length() - ".java".length());
                        if (fileToFreq.containsKey(fileName)) {
                            fileToFreq.put(fileName, fileToFreq.get(fileName) + 1);
                        } else {
                            fileToFreq.put(fileName, 1L);
                        }
                    }
                }
                b.close();
            } catch (Exception e) {
                System.err.println("Failed to execute bash with command: " + command);
                e.printStackTrace();
            }
            return sortAndExtract(fileToFreq, percentage);
        } else if (hotFileType.equals(SIZE_HOTFILE)) {
            HashMap<String, Long> fileToSize = new HashMap<>();
            // check all the classes
            try {
                Files.walk(Paths.get("."))
                        .sequential()
                        .filter(x -> !x.toFile().isDirectory())
                        .filter(x -> x.toFile().getAbsolutePath().endsWith(".class"))
                        .forEach(p -> {
                            File classFile = p.toFile();
                            if (classFile.isFile()) {
                                fileToSize.put("file:" + classFile.toPath().normalize().toAbsolutePath().toString(), classFile.length());
                            }
                        });
                return sortAndExtract(fileToSize, percentage);
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
        return hotFiles;
    }

    public static List<String> sortAndExtract(Map<String, Long> fileToType, String percentage){
        List<String> hotFiles = new ArrayList<>();
        // sort the files by size
        HashMap<String, Long> sortedFileToSize = fileToType.entrySet().stream()
                .sorted(reverseOrder(Entry.comparingByValue()))
                .collect(Collectors.toMap(Entry::getKey, Entry::getValue,
                        (e1, e2) -> e1, LinkedHashMap::new));
        // get the top percentage of the files
        double percentageDouble = Double.parseDouble(percentage)/100.0;
        int topSize = (int) (sortedFileToSize.size() * percentageDouble);
        for (int i = 0; i < topSize; i++) {
            hotFiles.add(sortedFileToSize.keySet().toArray()[i].toString());
        }    
        return hotFiles;
    }

    public static void main(String[] args) {
        hotFiles = getHotFiles(SIZE_HOTFILE, "50", ".starts", "zlc.txt");
        hotFiles.forEach(System.out::println);
    }
}

