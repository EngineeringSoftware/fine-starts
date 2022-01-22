/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.helpers;

import edu.illinois.starts.constants.StartsConstants;
import edu.illinois.starts.data.ZLCData;
import edu.illinois.starts.util.ChecksumUtil;
import edu.illinois.starts.util.Logger;
import edu.illinois.starts.util.Pair;
import edu.illinois.yasgl.DirectedGraphBuilder;
import edu.illinois.starts.changelevel.StartsChangeTypes;
import edu.illinois.starts.changelevel.FineTunedBytecodeCleaner;
import static edu.illinois.starts.smethods.MethodLevelStaticDepsBuilder.*;
import static edu.illinois.starts.util.Macros.CHANGE_TYPES_DIR_NAME;
import static edu.illinois.starts.util.Macros.STARTS_ROOT_DIR_NAME;

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
    private static Set<String> allTestClasses = new HashSet<>();
    private static Set<String> allClassesPaths = new HashSet<>();
    private static boolean initGraph = false;
    private static boolean initClassesPaths = false;
    private static Set<String> changedMethods = new HashSet<>();
    // method level changed classes
    private static Set<String> mlChangedClasses = new HashSet<>();
    private static HashMap<String, Set<String>> clModifiedClassesMap = new HashMap<>( );
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

    public static Pair<Set<String>, Set<String>> getChangedData(String artifactsDir, boolean cleanBytes, boolean fineRTSOn, boolean mRTSOn, boolean saveMRTSOn) {
        long start = System.currentTimeMillis();
        File zlc = new File(artifactsDir, zlcFile);

        if (!zlc.exists()) {
            //TODO: first run
            if (saveMRTSOn && fineRTSOn) {
                try {
                    //todo: save default ChangeTypes
                    Files.walk(Paths.get("."))
                    .sequential()
                    .filter(x -> !x.toFile().isDirectory())
                    .filter(x -> x.toFile().getAbsolutePath().endsWith(".class"))
                    .forEach(t -> {
                            try {
                                File classFile = t.toFile();
                                if (classFile.isFile()) {   
                                    StartsChangeTypes curStartsChangeTypes = FineTunedBytecodeCleaner.removeDebugInfo(FileUtil.readFile(
                                            classFile));
                                    String fileName = FileUtil.urlToSerFilePath(classFile.getAbsolutePath());
                                    StartsChangeTypes.toFile(fileName, curStartsChangeTypes);
                                    // System.out.println("successfully saved starts change types for " + fileName);
                                }
                            } catch(IOException e) {
                                System.out.println("Cannot parse file: "+t);
                            }
                    });
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
//                  TODO: add checking ChangeType here
                    if (fineRTSOn) {
                        if (!initClassesPaths) {
                            // init
                            allClassesPaths = new HashSet<>(Files.walk(Paths.get("."))
                                    .filter(Files::isRegularFile)
                                    .filter(f -> f.toString().endsWith(".class"))
                                    .map(f -> f.normalize().toAbsolutePath().toString())
                                    .collect(Collectors.toList()));
                            initClassesPaths = true;
                        }
                        allClassesPaths.remove(url.getPath());
                        boolean finertsChanged = true;
                        String fileName = FileUtil.urlToSerFilePath(stringURL);
                        StartsChangeTypes curStartsChangeTypes = null;
                        try {
                            StartsChangeTypes preStartsChangeTypes = StartsChangeTypes.fromFile(fileName);
                            File curClassFile = new File(stringURL.substring(stringURL.indexOf("/")));

                            if (curClassFile.exists()) {
                                curStartsChangeTypes = FineTunedBytecodeCleaner.removeDebugInfo(FileUtil.readFile(
                                        curClassFile));
                                if (preStartsChangeTypes != null && preStartsChangeTypes.equals(curStartsChangeTypes)) {
                                    finertsChanged = false;
                                }
                            }
                        } catch (ClassNotFoundException | IOException e) {
                            throw new RuntimeException(e);
                        }

                        if (finertsChanged) {
                            if (mRTSOn) {
                                if (!initGraph) {
                                    List<ClassReader> classReaderList = getClassReaders(".");
                                    // find the methods that each method calls
                                    findMethodsinvoked(classReaderList);
                                    // find all the test classes
                                    for (ClassReader c : classReaderList) {
                                        if (c.getClassName().contains("Test")) {
                                            allTestClasses.add(c.getClassName().split("\\$")[0]);
                                        }
                                    }
                                    // build the graph
                                    DirectedGraphBuilder<String> builder = new DirectedGraphBuilder<>();
                                    for (String key : methodName2MethodNames.keySet()) {
                                        for (String dep : methodName2MethodNames.get(key)) {
                                            builder.addEdge(key, dep);
                                        }
                                    }
                                    m2mGraph = builder.build();
                                    
                                    test2methods = getDFS(methodName2MethodNames, allTestClasses);
                                    Map<String, Set<String>> test2methodsDFS = getDFSDeps(methodName2MethodNames, allTestClasses);
                                    // verify(test2methods, test2methodsDFS);
                                    changedMethods = getChangedMethods(allTestClasses);
//                                System.out .println("changedMethods: " + changedMethods);
                                    mlChangedClasses = new HashSet<>();
                                    for (String changedMethod : changedMethods) {
                                        mlChangedClasses.add(changedMethod.split("#")[0]);
                                    }
                                    initGraph = true;
                                }

                                for (String test : tests) {
                                    clModifiedClassesMap.computeIfAbsent(test.replace(".", "/"), k -> new HashSet<>()).add(FileUtil.urlToClassName(stringURL));
                                }
                            }
                            affected.addAll(tests);
                            changedClasses.add(stringURL);
                        }
                        if (saveMRTSOn && curStartsChangeTypes!=null) {
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

        if (fineRTSOn){
            if (mRTSOn) {
                affected.removeIf(affectedTest -> !shouldTestRun(affectedTest.replace(".", "/")));
            }
//            System.out.println("affected: " + affected);
            if (allClassesPaths!=null) {
                // update the newly add ChangeTyeps
                for (String remainingPath : allClassesPaths) {
                    try {
                        File remainingFile = new File(remainingPath);
                        String fileName = FileUtil.urlToSerFilePath(remainingFile.toURI().toURL().toExternalForm());
                        StartsChangeTypes curStartsChangeTypes = FineTunedBytecodeCleaner.removeDebugInfo(FileUtil.readFile(
                                remainingFile));
                        if (saveMRTSOn && curStartsChangeTypes != null)
                            StartsChangeTypes.toFile(fileName, curStartsChangeTypes);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        nonAffected.removeAll(affected);
        long end = System.currentTimeMillis();
        LOGGER.log(Level.FINEST, TIME_COMPUTING_NON_AFFECTED + (end - start) + MILLISECOND);
        return new Pair<>(nonAffected, changedClasses);
    }

    public static boolean shouldTestRun(String test){
//        System.out.println("test: " + test);
        Set<String> mlUsedClasses = new HashSet<>();
        Set<String> mlUsedMethods = test2methods.getOrDefault(test, new TreeSet<>());
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
                // todo
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

    public static Set<String> getChangedMethods(Set<String> allTests){
        Set<String> res = new HashSet<>();

        try {
            List<Path> classPaths = Files.walk(Paths.get("."))
                    .filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".class"))
                    .collect(Collectors.toList());

            Set<String> changeTypePaths = new HashSet<>();
            String serPath = STARTS_ROOT_DIR_NAME + "/" + CHANGE_TYPES_DIR_NAME;
            if (new File(serPath).exists()) {
                changeTypePaths = Files.walk(Paths.get(serPath))
                        .filter(Files::isRegularFile)
                        .map(Path::toAbsolutePath)
                        .map(Path::normalize)
                        .map(Path::toString)
                        .collect(Collectors.toSet());
            }

            for (Path classPath : classPaths){
                byte[] array = Files.readAllBytes(classPath);
                StartsChangeTypes curChangeTypes = FineTunedBytecodeCleaner.removeDebugInfo(array);

                String changeTypePath = FileUtil.urlToSerFilePath(classPath.toUri().toURL().toExternalForm());
                File preChangeTypeFile = new File(changeTypePath);

                if (!preChangeTypeFile.exists()){
                    // does not exist before
                    if (allTests.contains(curChangeTypes.curClass)) {
                        Set<String> methods = new HashSet<>();
                        curChangeTypes.methodMap.keySet().forEach(m -> methods.add(curChangeTypes.curClass + "#" +
                                m.substring(0, m.indexOf(")") + 1)));
                        curChangeTypes.constructorsMap.keySet().forEach(m -> methods.add(curChangeTypes.curClass + "#" +
                                m.substring(0, m.indexOf(")") + 1)));
                        res.addAll(methods);
                    }
                }else {
                    changeTypePaths.remove(changeTypePath);
                    StartsChangeTypes preChangeTypes = StartsChangeTypes.fromFile(changeTypePath);

                    if (!preChangeTypes.equals(curChangeTypes)) {
                        res.addAll(getChangedMethodsPerChangeType(preChangeTypes.methodMap,
                                curChangeTypes.methodMap, curChangeTypes.curClass, allTests));
                        res.addAll(getChangedMethodsPerChangeType(preChangeTypes.constructorsMap,
                                curChangeTypes.constructorsMap, curChangeTypes.curClass, allTests));
                    }
                }
            }
            for(String preChangeTypePath : changeTypePaths){
                new File(preChangeTypePath).delete();
            }
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }

        return res;
    }


    private static Set<String> getChangedMethod(String urlExternalForm, Set<String> allTests){
        Set<String> res = new HashSet<>();

        try {
            String fileName = FileUtil.urlToSerFilePath(urlExternalForm);
            File preStartsChangeTypeFile = new File(urlExternalForm.substring(urlExternalForm.indexOf("/")));
            StartsChangeTypes curStartsChangeTypes = FineTunedBytecodeCleaner.removeDebugInfo(FileUtil.readFile(preStartsChangeTypeFile));
            if (!preStartsChangeTypeFile.exists()){
                // does not exist before
                if (allTests.contains(curStartsChangeTypes.curClass)) {
                    Set<String> methods = new HashSet<>();
                    curStartsChangeTypes.methodMap.keySet().forEach(m -> methods.add(curStartsChangeTypes.curClass + "#" +
                            m.substring(0, m.indexOf(")") + 1)));
                    curStartsChangeTypes.constructorsMap.keySet().forEach(m -> methods.add(curStartsChangeTypes.curClass + "#" +
                            m.substring(0, m.indexOf(")") + 1)));
                    res.addAll(methods);
                }
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
        String outerClassName = className.split("\\$")[0];
        if (allTests.contains(outerClassName)){
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
