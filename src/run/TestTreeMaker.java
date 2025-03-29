package run;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTags;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.*;
// 手动构建抽象语法树
/**
* 输出的结果为
 * package chapter4;
 *
 * public class Test {
 *     public int a = 1;
 * }
*/
public class TestTreeMaker {

    static Names names;

    static TreeMaker F;

    public static void main(String[] args) {

        Context context = new Context();
        JavacFileManager.preRegister(context);
        F = TreeMaker.instance(context);

        names = Names.instance(context);

        // public int a = 1;
        JCTree.JCModifiers mods = F.Modifiers(Flags.PUBLIC);
        JCTree.JCPrimitiveTypeTree type = F.TypeIdent(TypeTags.INT);
        Name name = names.fromString("a");
        JCTree.JCLiteral init = F.Literal(TypeTags.INT, "1");
        JCTree.JCVariableDecl result = F.VarDef(mods, name, type,
                init);
        JCTree.JCModifiers mods1 = F.Modifiers(Flags.PUBLIC);
        Name name1 = names.fromString("Test");
        ListBuffer<JCTree> defs = new ListBuffer<JCTree>();
        defs.append(result);
        List<JCTree.JCTypeParameter> typarams = List.nil();
        List<JCTree.JCExpression> implementing = List.nil();
        JCTree.JCClassDecl jcc = F.ClassDef(mods1, name1, typarams,
                null, implementing,
                defs.toList());
        ListBuffer<JCTree> defsx = new ListBuffer<JCTree>();
        defsx.add(jcc);
        List<JCTree.JCAnnotation> packageAnnotations = List.nil();
        JCTree.JCIdent ifr = F.Ident(names.fromString("chapter4"));
        JCTree.JCExpression pid = ifr;
        JCTree.JCCompilationUnit toplevel =
                F.TopLevel(packageAnnotations, pid,
                        defsx.toList());
        System.out.println(toplevel.toString());

    }
}
