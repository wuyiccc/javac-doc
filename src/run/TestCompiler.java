package run;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * @author wuyiccc
 * @date 2025/3/23 10:16
 */
public class TestCompiler {

    public static void main(String[] args) {

//        String path = "/root/work/javac-doc/src/test/TestJavac.java";
//        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
//
//        int result = compiler.run(null, null, null, new String[] {
//                "-cp", "/root/work/javac-doc/lib/mysql-binlog-connector-java-0.25.3.jar",
//                "-d", "./target",
//                path
//        });

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int result = compiler.run(null, null, null, new String[]{
                "-cp", "/root/work/javac-doc/lib/mysql-binlog-connector-java-0.25.3.jar",
                "@/root/work/javac-doc/src/run/javaOptions.txt",
                "@/root/work/javac-doc/src/run/javaFiles.txt"
        });

        System.out.println("result code:" + result);
    }

}
