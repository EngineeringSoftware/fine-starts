package edu.illinois.starts.smethods;

import org.ekstazi.asm.ClassReader;

import edu.illinois.starts.helpers.YasglHelper;
import edu.illinois.starts.smethods.ClassToMethodsCollectorCV;
import edu.illinois.starts.smethods.MethodCallCollectorCV;
import edu.illinois.yasgl.DirectedGraph;
import edu.illinois.yasgl.GraphUtils;
import edu.illinois.starts.smethods.ConstantPoolParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;



public class MethodLevelStaticDepsBuilder{
    // mvn exec:java -Dexec.mainClass=org.sekstazi.smethods.MethodLevelStaticDepsBuilder -Dmyproperty=/Users/liuyu/projects/finertsTest

    // for every class, get the methods it implements
    public static Map<String, Set<String>> class2ContainedMethodNames = new HashMap<>();
    // for every method, get the methods it invokes
    public static Map<String, Set<String>> methodName2MethodNames = new HashMap<>();
//    // for every method, get the methods that invoke it (the methods that will be affected by it)
//    public static Map<String, Set<String>> method2usage = new HashMap<>();
    // for every class, find its parents.
    public static Map<String, Set<String>> hierarchy_parents = new HashMap<>();
    // for every class, find its children.
    public static Map<String, Set<String>> hierarchy_children = new HashMap<>();
    // for every test class, find what method it depends on
    public static Map<String, Set<String>> test2methods = new HashMap<>();

    public static DirectedGraph<String> m2mGraph;

    public static void main(String... args) throws Exception {
        // We need at least the argument that points to the root
        // directory where the search for .class files will start.
//        if (args.length < 1) {
//            throw new RuntimeException("Incorrect arguments");
//        }
//        String pathToStartDir = args[0];
        String pathToStartDir = "/Users/liuyu/projects/ctaxonomy/_downloads/alibaba_fastjson_sekstazi";

        List<ClassReader> classReaderList = getClassReaders(pathToStartDir);

        // find the methods that each method calls
        findMethodsinvoked(classReaderList);

        // suppose that test classes have Test in their class name
        Set<String> testClasses = new HashSet<>();
        for (String method : methodName2MethodNames.keySet()){
            String className = method.split("#|\\$")[0];
            if (className.contains("Test")){
                testClasses.add(className);
            }
        }

        test2methods = getBFSDeps(methodName2MethodNames, testClasses);

        saveMap(methodName2MethodNames, "graph.txt");
        saveMap(hierarchy_parents, "hierarchy_parents.txt");
        saveMap(hierarchy_children, "hierarchy_children.txt");
        saveMap(class2ContainedMethodNames, "class2methods.txt");
        // save into a txt file ".ekstazi/methods.txt"
        saveMap(test2methods, "methods.txt");
    }

    //TODO: keeping all the classreaders would crash the memory
    public static List<ClassReader> getClassReaders(String directory) throws IOException {
        return Files.walk(Paths.get(directory))
                .sequential()
                .filter(x -> !x.toFile().isDirectory())
                .filter(x -> x.toFile().getAbsolutePath().endsWith(".class"))
                .map(new Function<Path, ClassReader>() {
                    @Override
                    public ClassReader apply(Path t) {
                        try {
                            return new ClassReader(new FileInputStream(t.toFile()));
                        } catch(IOException e) {
                            System.out.println("Cannot parse file: "+t);
                            return null;
                        }
                    }
                })
                .filter(x -> x != null)
                .collect(Collectors.toList());
    }

    public static void findMethodsinvoked(List<ClassReader> classReaderList){
        for (ClassReader classReader : classReaderList){
            ClassToMethodsCollectorCV visitor = new ClassToMethodsCollectorCV(class2ContainedMethodNames , hierarchy_parents, hierarchy_children);
            classReader.accept(visitor, ClassReader.SKIP_DEBUG);
        }
        for (ClassReader classReader : classReaderList){
//            Set<String> classesInConstantPool = ConstantPoolParser.getClassNames(ByteBuffer.wrap(classReader.b));
            //TODO: not keep methodName2MethodNames, hierarchies as fields
            MethodCallCollectorCV visitor = new MethodCallCollectorCV(methodName2MethodNames, hierarchy_parents, hierarchy_children, class2ContainedMethodNames);
            classReader.accept(visitor, ClassReader.SKIP_DEBUG);
        }

        // deal with test class in a special way, all the @test method in hierarchy should be considered
        for (String superClass : hierarchy_children.keySet()) {
            if (superClass.contains("Test")) {
                for (String subClass : hierarchy_children.getOrDefault(superClass, new HashSet<>())) {
                    for (String methodSig : class2ContainedMethodNames.getOrDefault(superClass, new HashSet<>())) {
                        String subClassKey = subClass + "#" + methodSig;
                        String superClassKey = superClass + "#" + methodSig;
                        methodName2MethodNames.computeIfAbsent(subClassKey, k -> new TreeSet<>()).add(superClassKey);

//                        method2usage.computeIfAbsent(superClassKey, k -> new TreeSet<>()).add(subClassKey);
                    }
                }
            }
        }
    }

    public static void saveMap(Map<String, Set<String>> mapToStore, String fileName) throws Exception {
        File directory = new File(".starts");
        directory.mkdir();

        File txtFile = new File(directory, fileName);
        PrintWriter pw = new PrintWriter(txtFile);

        for (Map.Entry<String, Set<String>> en : mapToStore.entrySet()) {
            String methodName = en.getKey();
            //invokedMethods saved in csv format
            String invokedMethods = String.join(",", mapToStore.get(methodName));
            pw.println(methodName + " " + invokedMethods);
            pw.println();
        }
        pw.flush();
        pw.close();
    }

    public static void saveSet(Set<String> setToStore, String fileName) throws Exception {
        File directory = new File(".starts");
        directory.mkdir();

        File txtFile = new File(directory, fileName);
        PrintWriter pw = new PrintWriter(txtFile);

        for (String s : setToStore) {
            pw.println(s);
        }
        pw.flush();
        pw.close();
    }

    public static Set<String> getBFSDepsHelper(Map<String, Set<String>> methodName2MethodNames, String testClass) {
        Set<String> visitedMethods = new TreeSet<>();
        //BFS
        ArrayDeque<String> queue = new ArrayDeque<>();

        //initialization
        for (String method : methodName2MethodNames.keySet()){
            if (method.startsWith(testClass+"#")){
                queue.add(method);
                visitedMethods.add(method);
            }
        }

        while (!queue.isEmpty()){
            String currentMethod = queue.pollFirst();
            for (String invokedMethod : methodName2MethodNames.getOrDefault(currentMethod, new HashSet<>())){
                if (!visitedMethods.contains(invokedMethod)) {
                    queue.add(invokedMethod);
                    visitedMethods.add(invokedMethod);
                }
            }
        }
        return visitedMethods;
    }

    public static Map<String, Set<String>> getBFSDeps(Map<String, Set<String>> methodName2MethodNames, Set<String> testClasses) {
        Map<String, Set<String>> test2methods = new ConcurrentSkipListMap<>();
        ExecutorService service = null;
        try {
            service = Executors.newFixedThreadPool(16);
            for (final String testClass : testClasses)
            {
                service.submit(() -> {
                    Set<String> invokedMethods = getBFSDepsHelper(methodName2MethodNames, testClass);
                    test2methods.put(testClass, invokedMethods);
                });
            }
            service.shutdown();
            service.awaitTermination(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return test2methods;
    }

        // simple DFS
        public static void getDFSDepsHelper(String methodName, Map<String, Set<String>> methodName2MethodNames, Set<String> visitedMethods){
            if (methodName2MethodNames.containsKey(methodName)){
                for (String method : methodName2MethodNames.get(methodName)){
                    if (!visitedMethods.contains(method)){
                        visitedMethods.add(method);
                        getDFSDepsHelper(method, methodName2MethodNames, visitedMethods);
                    }
                }
            }
        }

    public static Map<String, Set<String>> getDFSDeps(Map<String, Set<String>> methodName2MethodNames, Set<String> testClasses){
        Map<String, Set<String>> test2methods = new HashMap<>();
        for (String testClass : testClasses){
            // DFS
            Set<String> methodDeps = new HashSet<>();
            HashSet<String> visited = new HashSet<>();
            for (String method : methodName2MethodNames.keySet()){
                if (method.startsWith(testClass+"#")){
                    visited.add(method);
                    getDFSDepsHelper(method, methodName2MethodNames, visited);
                    methodDeps.addAll(visited);
                }
            }
            testClass = testClass.split("\\$")[0];
            Set<String> existedDeps = test2methods.getOrDefault(testClass, new HashSet<>());
            existedDeps.addAll(methodDeps);
            test2methods.put(testClass, existedDeps);
        }
        return test2methods;
    }

    public static Map<String, Set<String>> getDepsTC(DirectedGraph<String> m2mGraph, Set<String> tests){
        Map<String, Set<String>> mPerTest = new HashMap<>();
        Map<String, Set<String>> tc = GraphUtils.computeTransitiveClosure(m2mGraph);
        for (String test : tests) {
            Set<String> tcMethods = new HashSet<>();
            for (String method : methodName2MethodNames.keySet()){
                if (method.startsWith(test+"#")){
                    if (tc.containsKey(method)){
                        tcMethods.addAll(tc.get(method)); 
                    }
                    tcMethods.add(method);
                }
            }
            mPerTest.put(test, tcMethods);            
        }
        return mPerTest;    
    }

    public static Map<String, Set<String>> getDeps(DirectedGraph<String> m2mGraph, Set<String> tests){
        Map<String, Set<String>> mPerTest = new HashMap<>();
        for (String test : tests) {
            Set<String> testMethods = new HashSet<>();
            for (String method : methodName2MethodNames.keySet()){
                if (method.startsWith(test+"#")){
                    testMethods.add(method);
                }
            }
            Set<String> deps = YasglHelper.computeReachabilityFromChangedClasses(
                testMethods, m2mGraph);
            deps.addAll(testMethods);
            mPerTest.computeIfAbsent(test, k -> new HashSet<>()).addAll(deps);
        }
        return mPerTest;
    }

//    public static Map<String, Set<String>> getDeps(Map<String, Set<String>> methodName2MethodNames, Set<String> testClasses){
//        Map<String, Set<String>> test2methods = new HashMap<>();
//        for (String testClass : testClasses){
//            Set<String> visitedMethods = new TreeSet<>();
//            //BFS
//            ArrayDeque<String> queue = new ArrayDeque<>();
//
//            //initialization
//            for (String method : methodName2MethodNames.keySet()){
//                if (method.startsWith(testClass+"#")){
//                    queue.add(method);
//                    visitedMethods.add(method);
//                }
//            }
//
//            while (!queue.isEmpty()){
//                String currentMethod = queue.pollFirst();
//                for (String invokedMethod : methodName2MethodNames.getOrDefault(currentMethod, new HashSet<>())){
//                    if (!visitedMethods.contains(invokedMethod)) {
//                        queue.add(invokedMethod);
//                        visitedMethods.add(invokedMethod);
//                    }
//                }
//            }
//            testClass = testClass.split("\\$")[0];
//            test2methods.put(testClass, visitedMethods);
//        }
//        return test2methods;
//    }

    public static Set<String> getMethodsFromHierarchies(String currentMethod, Map<String, Set<String>> hierarchies){
        Set<String> res = new HashSet<>();
        // consider the superclass/subclass, do not have to consider the constructors
        // TODO: and fields of superclass/subclass
        // TODO: regular expression is expensive
        String currentMethodSig = currentMethod.split("#")[1];
        if (!currentMethodSig.startsWith("<init>") && !currentMethodSig.startsWith("<clinit>")) {
            String currentClass = currentMethod.split("#")[0];
            for (String hClass : hierarchies.getOrDefault(currentClass, new HashSet<>())) {
                String hMethod = hClass + "#" + currentMethodSig;
                res.addAll(getMethodsFromHierarchies(hMethod, hierarchies));
                res.add(hMethod);
            }
        }
        return res;
    }

    public static void verify(Map<String, Set<String>> test2methods, Map<String, Set<String>> test2methodsPrime){
        boolean verified = true;
        for (String test : test2methods.keySet()) {
            if (!test2methodsPrime.containsKey(test)){
                System.out.println("test: " + test);
                System.out.println("test2methods and test2methodsPrime should contain same test");
                // throw new RuntimeException("test2methods and test2methodsPrime should contain same test");
                verified = false;
            }
            if (test2methods.get(test).size() != test2methodsPrime.get(test).size()) {
                System.out.println("[test2methods]: " + test + " " + test2methods.get(test).size() + " " + test2methodsPrime.get(test).size());
                verified = false;
                Set<String> diffSet = test2methodsPrime.get(test);
                diffSet.removeAll(test2methods.get(test));
                System.out.println("[diffSet]: " + diffSet);
                // throw new RuntimeException("[test2methods]: " + test + " " + test2methods.get(test).size() + " " + test2methodsPrime.get(test).size());   
            }
        }
        if (verified) {
            System.out.println("verified");
        }
    }

}