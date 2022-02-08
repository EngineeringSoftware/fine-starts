/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.helpers;

import edu.illinois.starts.constants.StartsConstants;
import edu.illinois.starts.data.ZLCData;
import edu.illinois.starts.util.ChecksumUtil;
import edu.illinois.starts.util.Logger;
import edu.illinois.starts.util.Pair;
import edu.illinois.starts.changelevel.StartsChangeTypes;
import edu.illinois.starts.changelevel.FineTunedBytecodeCleaner;
import static edu.illinois.starts.smethods.MethodLevelStaticDepsBuilder.*;
import org.ekstazi.util.Types;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;
/**
 * Utility methods for dealing with the .zlc format.
 */
public class ZLCHelper implements StartsConstants {
    public static final String zlcFile = "deps.zlc";
    public static final String STAR_FILE = "file:*";
    private static final Logger LOGGER = Logger.getGlobal();
    private static Map<String, ZLCData> zlcDataMap;
    private static final String NOEXISTING_ZLCFILE_FIRST_RUN = "@NoExistingZLCFile. First Run?";

    private static Set<String> allClassesPaths = new HashSet<>();
    private static Set<String> oldClassesPaths = new HashSet<>();
    private static boolean initGraph = false;
    private static boolean initClassesPaths = false;
    private static Set<String> changedMethods = new HashSet<>();
    private static HashMap<String, Set<String>> clModifiedClassesMap = new HashMap<>();
    private static Set<String> affectedLines = new HashSet<>();
    private static Set<String> changedClassesPaths = new HashSet<>();
    private static long shouldTestRunTime = 0;
    private static long parseChangeTypeTime = 0;
    private static long fineRTSOverheadTime = 0;
    private static long methodAnalysisOverheadTime = 0;
    private static long changedMethodTime = 0;
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

    public static Pair<Set<String>, Set<String>> getChangedData(String artifactsDir, boolean cleanBytes, boolean fineRTSOn, boolean mRTSOn, boolean saveMRTSOn, boolean mMultithreadOn) {
        long start = System.currentTimeMillis();
        File zlc = new File(artifactsDir, zlcFile);
        String space = WHITE_SPACE;

        if (!zlc.exists()) {
            // first run
            if (saveMRTSOn && fineRTSOn) {
                try {
                    // save default ChangeTypes
                    Files.walk(Paths.get("."))
                    .sequential()
                    .filter(x -> !x.toFile().isDirectory())
                    .filter(x -> x.toString().endsWith(".class") && x.toString().contains("target"))
                    .forEach(t -> {
                            allClassesPaths.add(t.normalize().toAbsolutePath().toString());
                            try {
                                File classFile = t.toFile();
                                if (classFile.isFile()) {   
                                    StartsChangeTypes curStartsChangeTypes = FineTunedBytecodeCleaner.removeDebugInfo(org.ekstazi.util.FileUtil.readFile(
                                            classFile));
                                    String fileName = FileUtil.urlToSerFilePath(classFile.getAbsolutePath());
                                    StartsChangeTypes.toFile(fileName, curStartsChangeTypes);
                                    // System.out.println("successfully saved starts change types for " + fileName);
                                }
                            } catch(IOException e) {
                                // System.out.println("Cannot parse file: "+t);
                            }
                    });
                    // save method level dependency
                    findMethodsInvoked(allClassesPaths);
                } catch (IOException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
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
                if (fineRTSOn){
                    oldClassesPaths.add(url.getPath());
                }
                String newCheckSum = checksumUtil.computeSingleCheckSum(url);
                if (!newCheckSum.equals(oldCheckSum)) {
                    if(fineRTSOn && stringURL.contains("target")){
                        affectedLines.add(line);
                        changedClassesPaths.add(url.getPath());
                    }else{
                        affected.addAll(tests);
                        changedClasses.add(stringURL);
                    }
                }
                if (newCheckSum.equals("-1")) {
                    // a class was deleted or auto-generated, no need to track it in zlc
                    // delete useless classes
                    if (fineRTSOn){
                        try{
                            // System.out.println("Deleting " + stringURL);
                            String preChangeTypePath = FileUtil.urlToSerFilePath(url.toExternalForm());
                            new File(preChangeTypePath).delete();
                        }catch (Exception e){
                            LOGGER.log(Level.WARNING, "Failed to delete file: " + url.toExternalForm());
                        }
                    }
                    LOGGER.log(Level.FINEST, "Ignoring: " + url);
                    continue;
                }
                ZLCData data = new ZLCData(url, newCheckSum, tests);
                zlcDataMap.put(stringURL, data);
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
            throw new RuntimeException(ioe);
        }

        if (fineRTSOn && affectedLines.size() > 0){
            long fineRTSOverheadStart = System.currentTimeMillis();
            for (String line : affectedLines){
                String[] parts = line.split(space);
                String stringURL = parts[0];
                Set<String> tests = parts.length == 3 ? fromCSV(parts[2]) : new HashSet<String>();
                if (!initClassesPaths) {
                    // init
                    long findAllClassesStart = System.currentTimeMillis();
                    allClassesPaths = org.ekstazi.util.FileUtil.getClassPaths();
                    initClassesPaths = true;
                    long findAllClassesEnd = System.currentTimeMillis();
                    LOGGER.log(Level.FINEST, "FineSTARTSfindAllClasses: " + (findAllClassesEnd - findAllClassesStart));
                    // init hierarchy graph
                    StartsChangeTypes.initHierarchyGraph(allClassesPaths);
                }
                boolean finertsChanged = true;
            
                long parseChangeTypeStart = System.currentTimeMillis();
                String fileName = FileUtil.urlToSerFilePath(stringURL);
                StartsChangeTypes curStartsChangeTypes = null;
                try {
                    File curClassFile = new File(stringURL.substring(stringURL.indexOf("/")));
                    if (curClassFile.exists()) {
                        StartsChangeTypes preStartsChangeTypes = StartsChangeTypes.fromFile(fileName);
                        curStartsChangeTypes = FineTunedBytecodeCleaner.removeDebugInfo(org.ekstazi.util.FileUtil.readFile(
                                curClassFile));
                        if (preStartsChangeTypes != null && preStartsChangeTypes.equals(curStartsChangeTypes)) {
                            finertsChanged = false;
                        }                  
                        
                        if(mRTSOn){
                            long getChangedMethodStart = System.currentTimeMillis();
                            Set<String> curChangedMethods = getChangedMethods(preStartsChangeTypes, curStartsChangeTypes);
                            // System.out.println(url);
                            // System.out.println("curChangedMethods: " + curChangedMethods);
                            changedMethods.addAll(curChangedMethods);
                            long getChangedMethodEnd = System.currentTimeMillis();
                            changedMethodTime += (getChangedMethodEnd - getChangedMethodStart);
                        }
                    }
                } catch (ClassNotFoundException | IOException e) {
                    throw new RuntimeException(e);
                }
                long parseChangeTypeEnd = System.currentTimeMillis();
                parseChangeTypeTime += parseChangeTypeEnd - parseChangeTypeStart;

                if (finertsChanged) {
                    if (mRTSOn) {
                        long methodLevelAnalysisOverheadStart = System.currentTimeMillis();
                        if (!initGraph) {
                            long buildGraphStart = System.currentTimeMillis();
                            // find the methods that each method calls
                            // read previous dependency graph
                            HashSet<String> newClassesPaths = new HashSet<String>(allClassesPaths);
                            newClassesPaths.removeAll(oldClassesPaths);
                            changedClassesPaths.addAll(newClassesPaths);
                            findMethodsInvoked(changedClassesPaths, allClassesPaths);
                            long buildGraphEnd = System.currentTimeMillis();
                            LOGGER.log(Level.FINEST, "FineSTARTSBuildGraph: " + (buildGraphEnd - buildGraphStart));                                       
                            initGraph = true;
                        }

                        for (String test : tests) {
                            clModifiedClassesMap.computeIfAbsent(test.replace(".", "/"), k -> new HashSet<>()).add(FileUtil.urlToClassName(stringURL));
                        }
                        long methodLevelAnalysisOverheadEnd = System.currentTimeMillis();
                        methodAnalysisOverheadTime += methodLevelAnalysisOverheadEnd - methodLevelAnalysisOverheadStart;
                    }
                    affected.addAll(tests);
                    changedClasses.add(stringURL);
                }
                if (saveMRTSOn && curStartsChangeTypes!=null) {
                    StartsChangeTypes.toFile(fileName, curStartsChangeTypes);
                }  
            }
            long fineRTSOverheadEnd = System.currentTimeMillis();
            fineRTSOverheadTime += fineRTSOverheadEnd - fineRTSOverheadStart;
            if (mRTSOn) {
                // get test to methods mapping with multi threading
                long test2methodsStart = System.currentTimeMillis();
                Set<String> affectedSplitWithSlash = new HashSet<>();
                affected.forEach(t -> affectedSplitWithSlash.add(t.replace(".", "/")));
                Map<String, Set<String>> test2methods;
                if (mMultithreadOn){
                    test2methods = getDepsMultiThread(affectedSplitWithSlash);
                }else{
                    test2methods = getDepsSingleThread(affectedSplitWithSlash);
                }
                long test2methodsEnd = System.currentTimeMillis();
                LOGGER.log(Level.FINEST, "FineSTARTSTC: " + (test2methodsEnd - test2methodsStart));
                // LOGGER.log(Level.FINEST, "FineSTARTSNumMethodNodes: " + numMethodDepNodes.size()); 
         
                long shouldTestRunStart = System.currentTimeMillis();
                affected.removeIf(affectedTest -> !shouldTestRun(affectedTest.replace(".", "/"), test2methods));
                long shouldTestRunEnd = System.currentTimeMillis();
                shouldTestRunTime += shouldTestRunEnd - shouldTestRunStart;
                methodAnalysisOverheadTime += shouldTestRunEnd - shouldTestRunStart + test2methodsEnd - test2methodsStart;
            }
//            System.out.println("affected: " + affected);
            fineRTSOverheadStart = System.currentTimeMillis();
            if (allClassesPaths.size() != 0) {
                allClassesPaths.removeAll(oldClassesPaths);
                // System.out.println("new class paths: " + newClassesPaths);
                // update the newly add ChangeTyeps
                for (String remainingPath : allClassesPaths) {
                    try {
                        long parseChangeTypeStart = System.currentTimeMillis();
                        File remainingFile = new File(remainingPath);      
                        StartsChangeTypes curStartsChangeTypes = FineTunedBytecodeCleaner.removeDebugInfo(org.ekstazi.util.FileUtil.readFile(
                                remainingFile));
                        long parseChangeTypeEnd = System.currentTimeMillis();
                        parseChangeTypeTime += parseChangeTypeEnd - parseChangeTypeStart;
                        if (saveMRTSOn && curStartsChangeTypes != null){
                            String fileName = FileUtil.urlToSerFilePath(remainingFile.toURI().toURL().toExternalForm());
                            StartsChangeTypes.toFile(fileName, curStartsChangeTypes);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            fineRTSOverheadEnd = System.currentTimeMillis();
            fineRTSOverheadTime += fineRTSOverheadEnd - fineRTSOverheadStart;
        }

        if (!changedClasses.isEmpty()) {
            // there was some change so we need to add all tests that reach star, if any
            affected.addAll(starTests);
        }

        nonAffected.removeAll(affected);
        long end = System.currentTimeMillis();
        LOGGER.log(Level.FINEST, TIME_COMPUTING_NON_AFFECTED + (end - start));
        LOGGER.log(Level.FINEST, "FineSTARTSSaveChangeTypes: " + StartsChangeTypes.saveChangeTypes);
        LOGGER.log(Level.FINEST, "FineSTARTSNumChangeTypes: " + StartsChangeTypes.numChangeTypes);
        LOGGER.log(Level.FINEST, "FineSTARTSSizeChangeTypes: " + StartsChangeTypes.sizeChangeTypes);
        LOGGER.log(Level.FINEST, "FineSTARTSShouldTestRunTime: " + shouldTestRunTime);
        LOGGER.log(Level.FINEST, "FineSTARTSparseChangeTypeTime: " + parseChangeTypeTime);
        LOGGER.log(Level.FINEST, "FineSTARTSOverheadTime: " + (fineRTSOverheadTime - methodAnalysisOverheadTime));
        LOGGER.log(Level.FINEST, "FineSTARTSMethodAnalysisOverheadTime: " + methodAnalysisOverheadTime);
        LOGGER.log(Level.FINEST, "FineSTARTSChangedMethods: " + changedMethodTime);
        return new Pair<>(nonAffected, changedClasses);
    }

    public static boolean shouldTestRun(String test, Map<String, Set<String>> test2methods){
//        System.out.println("test: " + test);
        Set<String> mlUsedClasses = new HashSet<>();
        Set<String> mlUsedMethods = test2methods.getOrDefault(test, new TreeSet<>());
        // without multithreading:
        // Set<String> mlUsedMethods = getDeps(test);
        for (String mulUsedMethod: mlUsedMethods){
            mlUsedClasses.add(mulUsedMethod.split("#")[0]);
        }
        Set<String> clModifiedClasses = clModifiedClassesMap.get(test);
//        System.out.println();
//        System.out.println("mlUsedMethods: " + mlUsedClasses);
//        System.out.println("clModifieldClasses: " + clModifiedClasses);
//        System.out.println();
        if (mlUsedClasses.containsAll(clModifiedClasses)){
            // method level
            for (String clModifiedClass : clModifiedClasses){
                for (String method : changedMethods){
                    if (method.startsWith(clModifiedClass) && mlUsedMethods.contains(method)){
                        return true;
                    }
                }
            }
            return false;
        }else{
            // imprecision due to static field
//            System.out.println("imprecision due to static field");
            return true;
        }
    }

    public static Set<String> getAffectedTests(Set<String> changedMethods, Map<String, Set<String>> methodName2MethodNames, Set<String> testClasses){
        Set<String> affectedTests = new HashSet<>();
        // BFS, starting with the changed methods
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.addAll(changedMethods);
        Set<String> visitedMethods = new TreeSet<>();
        while (!queue.isEmpty()){
            String currentMethod = queue.pollFirst();
            String currentClass = currentMethod.split("#|\\$")[0];
            if (testClasses.contains(currentClass)){
                affectedTests.add(currentClass);
            }
            for (String invokedMethod : methodName2MethodNames.getOrDefault(currentMethod, new HashSet<>())){
                if (!visitedMethods.contains(invokedMethod)) {
                    queue.add(invokedMethod);
                    visitedMethods.add(invokedMethod);
                }
            }
        }
        return affectedTests;
    }

    public static Set<String> getChangedMethods(StartsChangeTypes preChangeTypes, StartsChangeTypes curChangeTypes){
        Set<String> res = new HashSet<>();
        if (preChangeTypes == null){
            // does not exist before
            if (curChangeTypes.curClass.contains("Test")) {
                Set<String> methods = new HashSet<>();
                curChangeTypes.instanceMethodMap.keySet().forEach(m -> methods.add(curChangeTypes.curClass + "#" +
                        m.substring(0, m.indexOf(")") + 1)));
                curChangeTypes.staticMethodMap.keySet().forEach(m -> methods.add(curChangeTypes.curClass + "#" +
                        m.substring(0, m.indexOf(")") + 1)));
                curChangeTypes.constructorsMap.keySet().forEach(m -> methods.add(curChangeTypes.curClass + "#" +
                        m.substring(0, m.indexOf(")") + 1)));
                res.addAll(methods);
            }
        }else {
            if (!preChangeTypes.equals(curChangeTypes)) {
                res.addAll(getChangedMethodsPerChangeType(preChangeTypes.instanceMethodMap,
                        curChangeTypes.instanceMethodMap, curChangeTypes.curClass));
                res.addAll(getChangedMethodsPerChangeType(preChangeTypes.staticMethodMap,
                        curChangeTypes.staticMethodMap, curChangeTypes.curClass));
                res.addAll(getChangedMethodsPerChangeType(preChangeTypes.constructorsMap,
                        curChangeTypes.constructorsMap, curChangeTypes.curClass));
            }
        }    
        return res;
    }

    static Set<String> getChangedMethodsPerChangeType(TreeMap<String, String> oldMethodsPara, TreeMap<String, String> newMethodsPara,
                                                      String className){
        Set<String> res = new HashSet<>();
        TreeMap<String, String> oldMethods = new TreeMap<>(oldMethodsPara);
        TreeMap<String, String> newMethods = new TreeMap<>(newMethodsPara);
        // consider adding test class
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
        String outerClassName = className.split("\\$")[0];
        if (outerClassName.contains("Test")){
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
