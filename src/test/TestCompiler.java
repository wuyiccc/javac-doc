package test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * @author wuyiccc
 * @date 2025/3/23 10:16
 */
public class TestCompiler {

    public static void main(String[] args) {

        String path = "/Users/wuxingyu/work/code_learn/032-sourcecode/08-sourcecode-jdk/javac-doc/src/test/TestJavac.java";
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        int result = compiler.run(null, null, null, new String[] {
                "-d", "/Users/wuxingyu/work/code_learn/032-sourcecode/08-sourcecode-jdk/javac-doc/src/test",
                path
        });

        System.out.println("result code:" + result);
    }

}
