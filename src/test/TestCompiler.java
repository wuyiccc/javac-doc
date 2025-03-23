package test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * @author wuyiccc
 * @date 2025/3/23 10:16
 */
public class TestCompiler {

    public static void main(String[] args) {

        String path = "/root/work/javac-doc/src/test/TestJavac.java";
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        int result = compiler.run(null, null, null, new String[] {
                "-d", "/root/work/javac-doc/src/test",
                path
        });

        System.out.println("result code:" + result);
    }

}
