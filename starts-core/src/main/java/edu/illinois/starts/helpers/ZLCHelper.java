/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.helpers;

import edu.illinois.starts.constants.StartsConstants;
import edu.illinois.starts.data.ZLCData;
import edu.illinois.starts.util.ChecksumUtil;
import edu.illinois.starts.util.Logger;
import edu.illinois.starts.util.Macros;
import edu.illinois.starts.util.Pair;
import edu.illinois.starts.changelevel.StartsChangeTypes;
import edu.illinois.starts.changelevel.FineTunedBytecodeCleaner;
import static edu.illinois.starts.smethods.MethodLevelStaticDepsBuilder.*;

import org.ekstazi.asm.ClassReader;
import org.ekstazi.util.Types;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Utility methods for dealing with the .zlc format.
 */
public class ZLCHelper implements StartsConstants {
    public static final String zlcFile = "deps.zlc";
    public static final String STAR_FILE = "file:*";
    private static final Logger LOGGER = Logger.getGlobal();
    private static Map<String, ZLCData> zlcDataMap;
    private static final String NOEXISTING_ZLCFILE_FIRST_RUN = "@NoExistingZLCFile. First Run?";
//    private static List<String> allTests;
    private static Map<String, Set<String>> test2methods;
    private static Set<String> testClasses = new HashSet<>();
    public ZLCHelper() {
        zlcDataMap = new HashMap<>();
    }

// TODO: Uncomment and fix this method. The problem is that it does not track newly added tests correctly
//    public static void updateZLCFile(Map<String, Set<String>> testDeps, ClassLoader loader,
//                                     String artifactsDir, Set<String> changed) {
//        long start = System.currentTimeMillis();
//        File file = new File(artifactsDir, zlcFile);
//        if (! file.exists()) {
//            Set<ZLCData> zlc = createZLCData(testDeps, loader);
//            Writer.writeToFile(zlc, zlcFile, artifactsDir);
//        } else {
//            Set<ZLCData> zlcData = new HashSet<>();
//            if (zlcDataMap != null) {
//                for (ZLCData data : zlcDataMap.values()) {
//                    String extForm = data.getUrl().toExternalForm();
//                    if (changed.contains(extForm)) {
//                         we need to update tests for this zlcData before adding
//                        String fqn = Writer.toFQN(extForm);
//                        Set<String> tests = new HashSet<>();
//                        if (testDeps.keySet().contains(fqn)) {
//                             a test class changed, it affects on itself
//                            tests.add(fqn);
//                        }
//                        for (String test : testDeps.keySet()) {
//                            if (testDeps.get(test).contains(fqn)) tests.add(test);
//                        }
//                        if (tests.isEmpty()) {
//                             this dep no longer has ant tests depending on it???
//                            continue;
//                        }
//                        data.setTests(tests);
//                    }
//                    zlcData.add(data);
//                }
//            }
//            Writer.writeToFile(zlcData, zlcFile, artifactsDir);
//        }
//        long end = System.currentTimeMillis();
//        System.out.println(TIME_UPDATING_CHECKSUMS + (end - start) + MS);
//    }

    public static void updateZLCFile(Map<String, Set<String>> testDeps, ClassLoader loader,
                                     String artifactsDir, Set<String> unreached, boolean useThirdParty) {
        // TODO: Optimize this by only recomputing the checksum+tests for changed classes and newly added tests
        long start = System.currentTimeMillis();
        List<ZLCData> zlc = createZLCData(testDeps, loader, useThirdParty);
        Writer.writeToFile(zlc, zlcFile, artifactsDir);
        long end = System.currentTimeMillis();
        LOGGER.log(Level.FINE, "[PROFILE] updateForNextRun(updateZLCFile): " + Writer.millsToSeconds(end - start));
    }

//    public void updateZLCFile(Map<String, Set<String>> testDeps, ClassLoader loader,
//                              String artifactsDir, Set<String> unreached, boolean useThirdParty, List<String> allTests) {
//        // TODO: Optimize this by only recomputing the checksum+tests for changed classes and newly added tests
//        this.allTests = allTests;
//        long start = System.currentTimeMillis();
//        List<ZLCData> zlc = createZLCData(testDeps, loader, useThirdParty);
//        Writer.writeToFile(zlc, zlcFile, artifactsDir);
//        long end = System.currentTimeMillis();
//        LOGGER.log(Level.FINE, "[PROFILE] updateForNextRun(updateZLCFile): " + Writer.millsToSeconds(end - start));
//    }

    public static List<ZLCData> createZLCData(Map<String, Set<String>> testDeps, ClassLoader loader, boolean useJars) {
        long start = System.currentTimeMillis();
        List<ZLCData> zlcData = new ArrayList<>();
        Set<String> deps = new HashSet<>();
        ChecksumUtil checksumUtil = new ChecksumUtil(true);
        // merge all the deps for all tests into a single set
        for (String test : testDeps.keySet()) {
            deps.addAll(testDeps.get(test));
        }

        // for each dep, find it's url, checksum and tests that depend on it
        for (String dep : deps) {
            String klas = ChecksumUtil.toClassName(dep);
            if (Types.isIgnorableInternalName(klas)) {
                continue;
            }
            URL url = loader.getResource(klas);
            String extForm = url.toExternalForm();
            if (url == null || ChecksumUtil.isWellKnownUrl(extForm) || (!useJars && extForm.startsWith("jar:"))) {
                continue;
            }
            String checksum = checksumUtil.computeSingleCheckSum(url);
            Set<String> tests = new HashSet<>();
            for (String test : testDeps.keySet()) {
                if (testDeps.get(test).contains(dep)) {
                    tests.add(test);
                }
            }
            zlcData.add(new ZLCData(url, checksum, tests));
        }
        long end = System.currentTimeMillis();
        LOGGER.log(Level.FINEST, "[TIME]CREATING ZLC FILE: " + (end - start) + MILLISECOND);
        return zlcData;
    }

    public static List<String> listFiles(String dir) {
        List<String> res = new ArrayList<>();
        try {
            List<Path> pathList =  Files.find(Paths.get(dir), 999, (p, bfa) -> bfa.isRegularFile())
                    .collect(Collectors.toList());
            for(Path filePath : pathList){
                if(!filePath.getFileName().toString().endsWith("class")){
                    continue;
                }
                String curClassPath = filePath.getParent().toString()+"/"+filePath.getFileName().toString();
                res.add(curClassPath);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return res;
    }

    public static Pair<Set<String>, Set<String>> getChangedData(String artifactsDir, boolean cleanBytes, boolean fineRTSOn) {
        long start = System.currentTimeMillis();
        File zlc = new File(artifactsDir, zlcFile);

        if (!zlc.exists()) {
            //TODO: first run
            if (fineRTSOn) {
                try {
                    // todo: save default ChangeTypes
//                    List<Path> classPaths = Files.walk(Paths.get(""))
//                            .filter(Files::isRegularFile)
//                            .filter(f -> f.toString().endsWith(".class"))
//                            .collect(Collectors.toList());
//                    for (Path filePath : classPaths){
//                        File classFile = filePath.toFile();
//                        StartsChangeTypes curStartsChangeTypes = FineTunedBytecodeCleaner.removeDebugInfo(FileUtil.readFile(classFile));
//                            String fileName = FileUtil.urlToSerFilePath(classFile.getAbsolutePath());
//                            StartsChangeTypes.toFile(fileName, curStartsChangeTypes);
//                    }

                    List<String> listOfFiles = listFiles(System.getProperty("user.dir") + "/target/classes");
                    for (String classFilePath : listOfFiles) {
//                    System.out.println("class file: " + classFilePath);
                        File classFile = new File(classFilePath);
                        if (classFile.isFile()) {
                            StartsChangeTypes curStartsChangeTypes = FineTunedBytecodeCleaner.removeDebugInfo(FileUtil.readFile(
                                    classFile));
                            String fileName = FileUtil.urlToSerFilePath(classFile.getAbsolutePath());
                            StartsChangeTypes.toFile(fileName, curStartsChangeTypes);
                        }
                    }
                    listOfFiles = listFiles(System.getProperty("user.dir") + "/target/test-classes");
                    for (String classFilePath : listOfFiles) {
                        File classFile = new File(classFilePath);
                        if (classFile.isFile()) {
                            StartsChangeTypes curStartsChangeTypes = FineTunedBytecodeCleaner.removeDebugInfo(FileUtil.readFile(
                                    classFile));
                            String fileName = FileUtil.urlToSerFilePath(classFile.getAbsolutePath());
                            StartsChangeTypes.toFile(fileName, curStartsChangeTypes);
                        }
                    }

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            LOGGER.log(Level.FINEST, NOEXISTING_ZLCFILE_FIRST_RUN);
            return null;
        }
        Set<String> changedClasses = new HashSet<>();
        Set<String> nonAffected = new HashSet<>();
        Set<String> affected = new HashSet<>();
        Set<String> starTests = new HashSet<>();
        ChecksumUtil checksumUtil = new ChecksumUtil(cleanBytes);
        try {
            List<String> zlcLines = Files.readAllLines(zlc.toPath(), Charset.defaultCharset());
            String firstLine = zlcLines.get(0);
            String space = WHITE_SPACE;

            // check whether the first line is for *
            if (firstLine.startsWith(STAR_FILE)) {
                String[] parts = firstLine.split(space);
                starTests = fromCSV(parts[2]);
                zlcLines.remove(0);
            }

            for (String line : zlcLines) {
                String[] parts = line.split(space);
                String stringURL = parts[0];
                String oldCheckSum = parts[1];
                Set<String> tests = parts.length == 3 ? fromCSV(parts[2]) : new HashSet<String>();
                nonAffected.addAll(tests);
                URL url = new URL(stringURL);
                String newCheckSum = checksumUtil.computeSingleCheckSum(url);
                if (!newCheckSum.equals(oldCheckSum)) {
                    //TODO: add checking ChangeType here
                    if (fineRTSOn) {
                        boolean finertsChanged = true;
                        String fileName = FileUtil.urlToSerFilePath(stringURL);
                        StartsChangeTypes curStartsChangeTypes = new StartsChangeTypes();
                        try {
                            StartsChangeTypes preStartsChangeTypes = StartsChangeTypes.fromFile(fileName);
                            curStartsChangeTypes = FineTunedBytecodeCleaner.removeDebugInfo(FileUtil.readFile(
                                    new File(stringURL.substring(stringURL.indexOf("/")))));
                            if (preStartsChangeTypes != null && preStartsChangeTypes.equals(curStartsChangeTypes)) {
                                finertsChanged =  false;
                            }
                        } catch (ClassNotFoundException | IOException e) {
                            throw new RuntimeException(e);
                        }

                        if (finertsChanged) {
                            if (test2methods == null){
                                List<ClassReader> classReaderList = getClassReaders(".");
                                // find the methods that each method calls
                                findMethodsinvoked(classReaderList);
                                // suppose that test classes have Test in their class name
                                for (String method : methodName2MethodNames.keySet()){
                                    String className = method.split("#")[0];
                                    if (className.contains("Test")){
                                        testClasses.add(className);
                                    }
                                }
                                test2methods = getDeps(methodName2MethodNames, testClasses);
                            }
                            Set<String> changedMethods = getChangedMethod(stringURL, testClasses);
                            Set<String> affectedTestSet = new HashSet<>();
                            for (String test : test2methods.keySet()) {
                                for (String changedMethod : changedMethods) {
                                    if (test2methods.get(test).contains(changedMethod)) {
                                        affectedTestSet.add(test);
                                        break;
                                    }
                                }
                            }
                            affectedTestSet = affectedTestSet.stream().map(s -> s.replace("/", ".")).collect(Collectors.toSet());
                            for(String test : tests) {
                                if (affectedTestSet.contains(test)) {
                                    affected.add(test);
                                }
                                if (affected.size()>0)
                                    changedClasses.add(stringURL);
                            }
                            StartsChangeTypes.toFile(fileName, curStartsChangeTypes);
                        }
                    }else{
                        affected.addAll(tests);
                        changedClasses.add(stringURL);
                    }
                }
                if (newCheckSum.equals("-1")) {
                    // a class was deleted or auto-generated, no need to track it in zlc
                    LOGGER.log(Level.FINEST, "Ignoring: " + url);
                    continue;
                }
                ZLCData data = new ZLCData(url, newCheckSum, tests);
                zlcDataMap.put(stringURL, data);
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        if (!changedClasses.isEmpty()) {
            // there was some change so we need to add all tests that reach star, if any
            affected.addAll(starTests);
        }
        nonAffected.removeAll(affected);
        long end = System.currentTimeMillis();
        LOGGER.log(Level.FINEST, TIME_COMPUTING_NON_AFFECTED + (end - start) + MILLISECOND);
        return new Pair<>(nonAffected, changedClasses);
    }

    private static Set<String> getChangedMethod(String urlExternalForm, Set<String> allTests){
        Set<String> res = new HashSet<>();

        try {
            String fileName = FileUtil.urlToSerFilePath(urlExternalForm);
            File preStartsChangeTypeFile = new File(urlExternalForm.substring(urlExternalForm.indexOf("/")));
            StartsChangeTypes curStartsChangeTypes = FineTunedBytecodeCleaner.removeDebugInfo(FileUtil.readFile(preStartsChangeTypeFile));
            if (!preStartsChangeTypeFile.exists()){
                // does not exist before
                Set<String> methods = new HashSet<>();
                curStartsChangeTypes.methodMap.keySet().forEach(m -> methods.add(curStartsChangeTypes.curClass + "#" +
                        m.substring(0, m.indexOf(")")+1)));
                curStartsChangeTypes.constructorsMap.keySet().forEach(m -> methods.add(curStartsChangeTypes.curClass + "#" +
                        m.substring(0, m.indexOf(")")+1)));
                res.addAll(methods);
//                    ChangeTypes.toFile(changeTypePath, curChangeTypes);
            }else {
                StartsChangeTypes preStartsChangeTypes = StartsChangeTypes.fromFile(fileName);

                if (!preStartsChangeTypes.equals(curStartsChangeTypes)) {
//                        ChangeTypes.toFile(changeTypePath, curChangeTypes);
                    res.addAll(getChangedMethodsPerChangeType(preStartsChangeTypes.methodMap,
                            curStartsChangeTypes.methodMap, curStartsChangeTypes.curClass, allTests));
                    res.addAll(getChangedMethodsPerChangeType(preStartsChangeTypes.constructorsMap,
                            curStartsChangeTypes.constructorsMap, curStartsChangeTypes.curClass, allTests));
                }
            }
        } catch (ClassNotFoundException | IOException e) {
            throw new RuntimeException(e);
        }
        return res;
    }

    static Set<String> getChangedMethodsPerChangeType(TreeMap<String, String> oldMethods, TreeMap<String, String> newMethods,
                                                      String className, Set<String> allTests){
        Set<String> res = new HashSet<>();
        //TODO: consider adding test class
        Set<String> methodSig = new HashSet<>(oldMethods.keySet());
        methodSig.addAll(newMethods.keySet());
        for (String sig : methodSig){
            if (oldMethods.containsKey(sig) && newMethods.containsKey(sig)){
                if (oldMethods.get(sig).equals(newMethods.get(sig))) {
                    oldMethods.remove(sig);
                    newMethods.remove(sig);
                }else{
                    res.add(className + "#" + sig.substring(0, sig.indexOf(")")+1));
                }
            } else if (oldMethods.containsKey(sig) && newMethods.containsValue(oldMethods.get(sig))){
                newMethods.values().remove(oldMethods.get(sig));
                oldMethods.remove(sig);
            } else if (newMethods.containsKey(sig) && oldMethods.containsValue(newMethods.get(sig))){
                oldMethods.values().remove(newMethods.get(sig));
                newMethods.remove(sig);
            }
        }
        // className is Test
        if (allTests.contains(className)){
            for (String sig : newMethods.keySet()){
                res.add(className + "#" + sig.substring(0, sig.indexOf(")")+1));
            }
        }
        return res;
    }

    private static Set<String> fromCSV(String tests) {
        return new HashSet<>(Arrays.asList(tests.split(COMMA)));
    }

    public static Set<String> getExistingClasses(String artifactsDir) {
        Set<String> existingClasses = new HashSet<>();
        long start = System.currentTimeMillis();
        File zlc = new File(artifactsDir, zlcFile);
        if (!zlc.exists()) {
            LOGGER.log(Level.FINEST, NOEXISTING_ZLCFILE_FIRST_RUN);
            return existingClasses;
        }
        try {
            List<String> zlcLines = Files.readAllLines(zlc.toPath(), Charset.defaultCharset());
            for (String line : zlcLines) {
                if (line.startsWith("file")) {
                    existingClasses.add(Writer.urlToFQN(line.split(WHITE_SPACE)[0]));
                }
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        long end = System.currentTimeMillis();
        LOGGER.log(Level.FINEST, "[TIME]COMPUTING EXISTING CLASSES: " + (end - start) + MILLISECOND);
        return existingClasses;
    }
}
