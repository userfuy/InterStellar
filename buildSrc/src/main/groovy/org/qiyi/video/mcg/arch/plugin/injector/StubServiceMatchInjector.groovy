package org.qiyi.video.mcg.arch.plugin.injector

import com.android.build.api.transform.JarInput
import javassist.ClassPool
import javassist.CtClass
import javassist.CtMethod
import org.apache.commons.io.FileUtils
import org.qiyi.video.mcg.arch.plugin.service.IServiceGenerator

import java.util.jar.JarEntry
import java.util.jar.JarFile
import org.qiyi.video.mcg.arch.plugin.utils.JarUtils
import org.qiyi.video.mcg.arch.plugin.service.StubServiceGenerator

public class StubServiceMatchInjector {

    private static final String STUB_SERVICE_MATCHER = "org.qiyi.video.mcg.arch.match.StubServiceMatcher"

    private static final String STUB_SERVICE_MATCHER_CLASS = "StubServiceMatcher.class"

    private static final String GET_TARGET_SERVICE = "getTargetService"

    private ClassPool classPool
    private String rootDirPath

    private IServiceGenerator serviceGenerator

    private Map<String, String> matchedServices
    private boolean found = false

    StubServiceMatchInjector(ClassPool classPool, IServiceGenerator serviceGenerator, String rootDirPath) {
        this.classPool = classPool
        this.serviceGenerator = serviceGenerator
        this.rootDirPath = rootDirPath
    }

    private void readMatchedServices(String dirPath, String fileName) {
        println "readMatchedServices()"
        File dir = new File(dirPath)
        if (!dir.exists()) {
            return
        }
        File matchFile = new File(dir, fileName)
        if (!matchFile.exists()) {
            return
        }
        BufferedInputStream ism = matchFile.newInputStream()
        BufferedReader reader = new BufferedReader(new InputStreamReader(ism))
        String content
        while ((content = reader.readLine()) != null) {
            String[] matchKeyValues = content.split(",")
            if (matchKeyValues != null) {
                println "read key:" + matchKeyValues[0] + ",value:" + matchKeyValues[1]
                matchedServices.put(matchKeyValues[0], matchKeyValues[1])
            }
        }
        reader.close()
        ism.close()
    }

    void injectMatchCode(JarInput jarInput) {
        if (found) {
            return
        }

        String filePath = jarInput.file.getAbsolutePath()
        //TODO 不能直接用"/"而是要用File.seperator
        if (filePath.endsWith(".jar") && !filePath.contains("com.android.support")
                && !filePath.contains("/com/android/support")) {

            JarFile jarFile = new JarFile(jarInput.file)
            Enumeration enumeration = jarFile.entries()

            while (enumeration.hasMoreElements()) {
                JarEntry jarEntry = (JarEntry) enumeration.nextElement()
                String entryName = jarEntry.getName()

                if (entryName.endsWith(STUB_SERVICE_MATCHER_CLASS)) {
                    prepareInjectMatchCode(filePath)
                    found = true
                    break
                }
            }

        }
    }
    //TODO 这里其实有优化的空间，就是可以直接从内存进行zipJar操作，而不必先写入到文件，然后又从文件读出进行zipJar操作
    private void prepareInjectMatchCode(String filePath) {

        println "prepareInjectMatchCode"

        File jarFile = new File(filePath)
        String jarDir = jarFile.getParent() + File.separator + jarFile.getName().replace('.jar', '')

        //解压jar包，解压之后就是.class文件
        List<String> classNameList = JarUtils.unzipJar(filePath, jarDir)

        //删除原来的jar包
        jarFile.delete()

        //注入代码
        //classPool.insertClassPath(jarDir)
        classPool.appendClassPath(jarDir)

        for (String className : classNameList) {
            if (className.endsWith(STUB_SERVICE_MATCHER_CLASS)) {
                doInjectMatchCode(jarDir)
                break
            }
        }

        //重新打包jar
        JarUtils.zipJar(jarDir, filePath)

        //删除目录
        FileUtils.deleteDirectory(new File(jarDir))

    }

    private void fetchServiceInfo() {
        matchedServices = serviceGenerator.getMatchServices()
        if (matchedServices == null) {
            this.matchedServices = new HashMap<>()
            readMatchedServices(rootDirPath + File.separator + StubServiceGenerator.MATCH_DIR, StubServiceGenerator.MATCH_FILE_NAME)
        }
    }

    //这个className含有.class,而实际上要获取CtClass的话只需要前面那部分，即"org.qiyi.video.svg.utils.StubServiceMatcher"而不是"org.qiyi.video.svg.utils.StubServiceMatcher.class"
    private void doInjectMatchCode(String path) {

        println "doInjectMatchCode"

        //首先获取服务信息
        fetchServiceInfo()

        CtClass ctClass = classPool.getCtClass(STUB_SERVICE_MATCHER)
        if (ctClass.isFrozen()) {
            ctClass.defrost()
        }
        CtMethod[] ctMethods = ctClass.getDeclaredMethods()
        CtMethod getTargetServiceMethod = null
        ctMethods.each {
            if (GET_TARGET_SERVICE.equals(it.getName())) {
                getTargetServiceMethod = it
            }
        }
        StringBuilder code = new StringBuilder()
        //注意:javassist的编译器不支持泛型
        code.append("{\njava.util.Map matchedServices=new java.util.HashMap();\n")
        matchedServices.each {
            code.append("matchedServices.put(\"" + it.getKey() + "\"," + it.getValue() + ".class);\n")
        }
        code.append('if(matchedServices.get($1)!=null)return matchedServices.get($1);\n}')
        getTargetServiceMethod.insertBefore(code.toString())

        ctClass.writeFile(path)
    }
}