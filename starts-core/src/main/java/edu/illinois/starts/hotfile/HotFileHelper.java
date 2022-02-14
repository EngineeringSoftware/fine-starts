package edu.illinois.starts.hotfile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.nio.file.Paths;
import static java.util.Collections.reverseOrder;

public class HotFileHelper {

    public static final int DEP_HOTFILE = 0;
    public static final int CHANGE_FRE_HOTFILE = 1;
    public static final int SIZE_HOTFILE = 2;
    public static List<String> hotFiles;

    public static List<String> getHotFiles(int type) {
        List<String> hotFiles = new ArrayList<>();
        if (type == DEP_HOTFILE) {

        } else if (type == CHANGE_FRE_HOTFILE) {

        } else if (type == SIZE_HOTFILE) {
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
            } catch (IOException e) {
                e.printStackTrace();
            }
            // sort the files by size
            HashMap<String, Long> sortedFileToSize = fileToSize.entrySet().stream()
                    .sorted(reverseOrder(Entry.comparingByValue()))
                    .collect(Collectors.toMap(Entry::getKey, Entry::getValue,
                            (e1, e2) -> e1, LinkedHashMap::new));
            // get the top 30% of the files
            int topSize = (int) (sortedFileToSize.size() * 0.5);
            for (int i = 0; i < topSize; i++) {
                hotFiles.add(sortedFileToSize.keySet().toArray()[i].toString());
            }
        }
        return hotFiles;
    }

    public static void main(String[] args) {
        hotFiles = getHotFiles(SIZE_HOTFILE);
        hotFiles.forEach(System.out::println);
    }
}

