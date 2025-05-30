/*
 * Copyright (c) 1999, 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.tools.javac.parser;

import java.util.*;

import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticFlag;
import com.sun.tools.javac.util.List;

import static com.sun.tools.javac.util.ListBuffer.lb;

import com.sun.tools.javac.tree.JCTree.*;

import static com.sun.tools.javac.parser.Token.*;

/**
 * The parser maps a token sequence into an abstract syntax
 * tree. It operates by recursive descent, with code derived
 * systematically from an LL(1) grammar. For efficiency reasons, an
 * operator precedence scheme is used for parsing binary operation
 * expressions.
 *
 * <p><b>This is NOT part of any supported API.
 * If you write code that depends on this, you do so at your own risk.
 * This code and its internal interfaces are subject to change or
 * deletion without notice.</b>
 */
public class JavacParser implements Parser {

    /**
     * The number of precedence levels of infix operators.
     */
    private static final int infixPrecedenceLevels = 10;

    /**
     * The scanner used for lexical analysis.
     */
    private Lexer S;

    /**
     * The factory to be used for abstract syntax tree construction.
     */
    protected TreeMaker F;

    /**
     * The log to be used for error diagnostics.
     */
    private Log log;

    /**
     * The keyword table.
     */
    private Keywords keywords;

    /**
     * The Source language setting.
     */
    private Source source;

    /**
     * The name table.
     */
    private Names names;

    /**
     * Construct a parser from a given scanner, tree factory and log.
     */
    protected JavacParser(ParserFactory fac,
                          Lexer S,
                          boolean keepDocComments,
                          boolean keepLineMap) {
        this.S = S;
        S.nextToken(); // prime the pump
        this.F = fac.F;
        this.log = fac.log;
        this.names = fac.names;
        this.keywords = fac.keywords;
        this.source = fac.source;
        this.allowGenerics = source.allowGenerics();
        this.allowVarargs = source.allowVarargs();
        this.allowAsserts = source.allowAsserts();
        this.allowEnums = source.allowEnums();
        this.allowForeach = source.allowForeach();
        this.allowStaticImport = source.allowStaticImport();
        this.allowAnnotations = source.allowAnnotations();
        this.allowTWR = source.allowTryWithResources();
        this.allowDiamond = source.allowDiamond();
        this.allowMulticatch = source.allowMulticatch();
        this.keepDocComments = keepDocComments;
        if (keepDocComments)
            docComments = new HashMap<JCTree, String>();
        this.keepLineMap = keepLineMap;
        this.errorTree = F.Erroneous();
    }

    /**
     * Switch: Should generics be recognized?
     */
    boolean allowGenerics;

    /**
     * Switch: Should diamond operator be recognized?
     */
    boolean allowDiamond;

    /**
     * Switch: Should multicatch clause be accepted?
     */
    boolean allowMulticatch;

    /**
     * Switch: Should varargs be recognized?
     */
    boolean allowVarargs;

    /**
     * Switch: should we recognize assert statements, or just give a warning?
     */
    boolean allowAsserts;

    /**
     * Switch: should we recognize enums, or just give a warning?
     */
    boolean allowEnums;

    /**
     * Switch: should we recognize foreach?
     */
    boolean allowForeach;

    /**
     * Switch: should we recognize foreach?
     */
    boolean allowStaticImport;

    /**
     * Switch: should we recognize annotations?
     */
    boolean allowAnnotations;

    /**
     * Switch: should we recognize try-with-resources?
     */
    boolean allowTWR;

    /**
     * Switch: should we keep docComments?
     */
    boolean keepDocComments;

    /**
     * Switch: should we keep line table?
     */
    boolean keepLineMap;

    /**
     * When terms are parsed, the mode determines which is expected:
     * mode = EXPR        : an expression
     * mode = TYPE        : a type
     * mode = NOPARAMS    : no parameters allowed for type
     * mode = TYPEARG     : type argument
     */
     // 表达式
    static final int EXPR = 0x1;
    // 类型
    static final int TYPE = 0x2;
    // 允许类型不传递实际类型参数
    static final int NOPARAMS = 0x4;
    // 为类型传递了实际类型参数
    static final int TYPEARG = 0x8;
    // 辅助解释钻石语法
    static final int DIAMOND = 0x10;

    /**
     * The current mode.
     */
     // 保存了在解析当前项时的期望
    private int mode = 0;

    /**
     * The mode of the term that was parsed last.
     */
     // 保存来上一次被解析的项的期望
    private int lastmode = 0;

    /* ---------- error recovery -------------- */

    private JCErroneous errorTree;

    /**
     * Skip forward until a suitable stop token is found.
     */
    private void skip(boolean stopAtImport, boolean stopAtMemberDecl, boolean stopAtIdentifier, boolean stopAtStatement) {
        while (true) {
            switch (S.token()) {
                case SEMI:
                    S.nextToken();
                    return;
                case PUBLIC:
                case FINAL:
                case ABSTRACT:
                case MONKEYS_AT:
                case EOF:
                case CLASS:
                case INTERFACE:
                case ENUM:
                    return;
                case IMPORT:
                    if (stopAtImport)
                        return;
                    break;
                case LBRACE:
                case RBRACE:
                case PRIVATE:
                case PROTECTED:
                case STATIC:
                case TRANSIENT:
                case NATIVE:
                case VOLATILE:
                case SYNCHRONIZED:
                case STRICTFP:
                case LT:
                case BYTE:
                case SHORT:
                case CHAR:
                case INT:
                case LONG:
                case FLOAT:
                case DOUBLE:
                case BOOLEAN:
                case VOID:
                    if (stopAtMemberDecl)
                        return;
                    break;
                case IDENTIFIER:
                    if (stopAtIdentifier)
                        return;
                    break;
                case CASE:
                case DEFAULT:
                case IF:
                case FOR:
                case WHILE:
                case DO:
                case TRY:
                case SWITCH:
                case RETURN:
                case THROW:
                case BREAK:
                case CONTINUE:
                case ELSE:
                case FINALLY:
                case CATCH:
                    if (stopAtStatement)
                        return;
                    break;
            }
            S.nextToken();
        }
    }

    private JCErroneous syntaxError(int pos, String key, Token... args) {
        return syntaxError(pos, null, key, args);
    }

    private JCErroneous syntaxError(int pos, List<JCTree> errs, String key, Token... args) {
        setErrorEndPos(pos);
        reportSyntaxError(pos, key, (Object[]) args);
        return toP(F.at(pos).Erroneous(errs));
    }

    private int errorPos = Position.NOPOS;

    /**
     * Report a syntax error at given position using the given
     * argument unless one was already reported at the same position.
     */
    private void reportSyntaxError(int pos, String key, Object... args) {
        if (pos > S.errPos() || pos == Position.NOPOS) {
            if (S.token() == EOF)
                error(pos, "premature.eof");
            else
                error(pos, key, args);
        }
        S.errPos(pos);
        if (S.pos() == errorPos)
            S.nextToken(); // guarantee progress
        errorPos = S.pos();
    }


    /**
     * Generate a syntax error at current position unless one was already
     * reported at the same position.
     */
    private JCErroneous syntaxError(String key) {
        return syntaxError(S.pos(), key);
    }

    /**
     * Generate a syntax error at current position unless one was
     * already reported at the same position.
     */
    private JCErroneous syntaxError(String key, Token arg) {
        return syntaxError(S.pos(), key, arg);
    }

    /**
     * If next input token matches given token, skip it, otherwise report
     * an error.
     */
    public void accept(Token token) {
        if (S.token() == token) {
            S.nextToken();
        } else {
            setErrorEndPos(S.pos());
            reportSyntaxError(S.prevEndPos(), "expected", token);
        }
    }

    /**
     * Report an illegal start of expression/type error at given position.
     */
    JCExpression illegal(int pos) {
        setErrorEndPos(S.pos());
        if ((mode & EXPR) != 0)
            return syntaxError(pos, "illegal.start.of.expr");
        else
            return syntaxError(pos, "illegal.start.of.type");

    }

    /**
     * Report an illegal start of expression/type error at current position.
     */
    JCExpression illegal() {
        return illegal(S.pos());
    }

    /**
     * Diagnose a modifier flag from the set, if any.
     */
    void checkNoMods(long mods) {
        if (mods != 0) {
            long lowestMod = mods & -mods;
            error(S.pos(), "mod.not.allowed.here",
                    Flags.asFlagSet(lowestMod));
        }
    }

    /* ---------- doc comments --------- */

    /**
     * A hashtable to store all documentation comments
     * indexed by the tree nodes they refer to.
     * defined only if option flag keepDocComment is set.
     */
    Map<JCTree, String> docComments;

    /**
     * Make an entry into docComments hashtable,
     * provided flag keepDocComments is set and given doc comment is non-null.
     *
     * @param tree The tree to be used as index in the hashtable
     * @param dc   The doc comment to associate with the tree, or null.
     */
    void attach(JCTree tree, String dc) {
        if (keepDocComments && dc != null) {
//          System.out.println("doc comment = ");System.out.println(dc);//DEBUG
            docComments.put(tree, dc);
        }
    }

    /* -------- source positions ------- */

    private int errorEndPos = -1;

    private void setErrorEndPos(int errPos) {
        if (errPos > errorEndPos)
            errorEndPos = errPos;
    }

    protected int getErrorEndPos() {
        return errorEndPos;
    }

    /**
     * Store ending position for a tree.
     *
     * @param tree   The tree.
     * @param endpos The ending position to associate with the tree.
     */
    protected void storeEnd(JCTree tree, int endpos) {
    }

    /**
     * Store ending position for a tree.  The ending position should
     * be the ending position of the current token.
     *
     * @param t The tree.
     */
    protected <T extends JCTree> T to(T t) {
        return t;
    }

    /**
     * Store ending position for a tree.  The ending position should
     * be greater of the ending position of the previous token and errorEndPos.
     *
     * @param t The tree.
     */
    protected <T extends JCTree> T toP(T t) {
        return t;
    }

    /**
     * Get the start position for a tree node.  The start position is
     * defined to be the position of the first character of the first
     * token of the node's source text.
     *
     * @param tree The tree node
     */
    public int getStartPos(JCTree tree) {
        return TreeInfo.getStartPos(tree);
    }

    /**
     * Get the end position for a tree node.  The end position is
     * defined to be the position of the last character of the last
     * token of the node's source text.  Returns Position.NOPOS if end
     * positions are not generated or the position is otherwise not
     * found.
     *
     * @param tree The tree node
     */
    public int getEndPos(JCTree tree) {
        return Position.NOPOS;
    }



    /* ---------- parsing -------------- */

    /**
     * Ident = IDENTIFIER
     */
    Name ident() {
        if (S.token() == IDENTIFIER) {
            Name name = S.name();
            S.nextToken();
            return name;
        } else if (S.token() == ASSERT) {
            if (allowAsserts) {
                error(S.pos(), "assert.as.identifier");
                S.nextToken();
                return names.error;
            } else {
                warning(S.pos(), "assert.as.identifier");
                Name name = S.name();
                S.nextToken();
                return name;
            }
        } else if (S.token() == ENUM) {
            if (allowEnums) {
                error(S.pos(), "enum.as.identifier");
                S.nextToken();
                return names.error;
            } else {
                warning(S.pos(), "enum.as.identifier");
                Name name = S.name();
                S.nextToken();
                return name;
            }
        } else {
            accept(IDENTIFIER);
            return names.error;
        }
    }

    /**
     * Qualident = Ident { DOT Ident }
     */
    public JCExpression qualident() {
        // 解析为JCIdent语法节点
        JCExpression t = toP(F.at(S.pos()).Ident(ident()));
        // 如果package定义了多级目录, 比如 package test.run; 而不是 package test;
        //那么语法解析器就会通过循环迭代的方式将其解析为嵌套的JCFieldAccess语法节点
        while (S.token() == DOT) {
            int pos = S.pos();
            S.nextToken();
            // 解析为JCFieldAccess语法节点
            t = toP(F.at(pos).Select(t, ident()));
        }
        return t;
    }

    /**
     * Literal =
     * INTLITERAL
     * | LONGLITERAL
     * | FLOATLITERAL
     * | DOUBLELITERAL
     * | CHARLITERAL
     * | STRINGLITERAL
     * | TRUE
     * | FALSE
     * | NULL
     */
    JCExpression literal(Name prefix) {
        int pos = S.pos();
        JCExpression t = errorTree;
        switch (S.token()) {
            case INTLITERAL:
                try {
                    t = F.at(pos).Literal(
                            TypeTags.INT,
                            Convert.string2int(strval(prefix), S.radix()));
                } catch (NumberFormatException ex) {
                    error(S.pos(), "int.number.too.large", strval(prefix));
                }
                break;
            case LONGLITERAL:
                try {
                    t = F.at(pos).Literal(
                            TypeTags.LONG,
                            new Long(Convert.string2long(strval(prefix), S.radix())));
                } catch (NumberFormatException ex) {
                    error(S.pos(), "int.number.too.large", strval(prefix));
                }
                break;
            case FLOATLITERAL: {
                String proper = (S.radix() == 16 ? ("0x" + S.stringVal()) : S.stringVal());
                Float n;
                try {
                    n = Float.valueOf(proper);
                } catch (NumberFormatException ex) {
                    // error already reported in scanner
                    n = Float.NaN;
                }
                if (n.floatValue() == 0.0f && !isZero(proper))
                    error(S.pos(), "fp.number.too.small");
                else if (n.floatValue() == Float.POSITIVE_INFINITY)
                    error(S.pos(), "fp.number.too.large");
                else
                    t = F.at(pos).Literal(TypeTags.FLOAT, n);
                break;
            }
            case DOUBLELITERAL: {
                String proper = (S.radix() == 16 ? ("0x" + S.stringVal()) : S.stringVal());
                Double n;
                try {
                    n = Double.valueOf(proper);
                } catch (NumberFormatException ex) {
                    // error already reported in scanner
                    n = Double.NaN;
                }
                if (n.doubleValue() == 0.0d && !isZero(proper))
                    error(S.pos(), "fp.number.too.small");
                else if (n.doubleValue() == Double.POSITIVE_INFINITY)
                    error(S.pos(), "fp.number.too.large");
                else
                    t = F.at(pos).Literal(TypeTags.DOUBLE, n);
                break;
            }
            case CHARLITERAL:
                t = F.at(pos).Literal(
                        TypeTags.CHAR,
                        S.stringVal().charAt(0) + 0);
                break;
            case STRINGLITERAL:
                t = F.at(pos).Literal(
                        TypeTags.CLASS,
                        S.stringVal());
                break;
            case TRUE:
            case FALSE:
                t = F.at(pos).Literal(
                        TypeTags.BOOLEAN,
                        (S.token() == TRUE ? 1 : 0));
                break;
            case NULL:
                t = F.at(pos).Literal(
                        TypeTags.BOT,
                        null);
                break;
            default:
                Assert.error();
        }
        if (t == errorTree)
            t = F.at(pos).Erroneous();
        storeEnd(t, S.endPos());
        S.nextToken();
        return t;
    }

    //where
    boolean isZero(String s) {
        char[] cs = s.toCharArray();
        int base = ((cs.length > 1 && Character.toLowerCase(cs[1]) == 'x') ? 16 : 10);
        int i = ((base == 16) ? 2 : 0);
        while (i < cs.length && (cs[i] == '0' || cs[i] == '.')) i++;
        return !(i < cs.length && (Character.digit(cs[i], base) > 0));
    }

    String strval(Name prefix) {
        String s = S.stringVal();
        return prefix.isEmpty() ? s : prefix + s;
    }

    /**
     * terms can be either expressions or types.
     */
    public JCExpression parseExpression() {
        return term(EXPR);
    }

    public JCExpression parseType() {
        return term(TYPE);
    }

    JCExpression term(int newmode) {
        // 保存当前处理的实际类型
        int prevmode = mode;
        mode = newmode;
        JCExpression t = term();
        // 保存上一次处理的实际类型
        lastmode = mode;
        // 还原mode
        mode = prevmode;
        return t;
    }

    /**
     * Expression = Expression1 [ExpressionRest]
     * ExpressionRest = [AssignmentOperator Expression1]
     * AssignmentOperator = "=" | "+=" | "-=" | "*=" | "/=" |
     * "&=" | "|=" | "^=" |
     * "%=" | "<<=" | ">>=" | ">>>="
     * Type = Type1
     * TypeNoParams = TypeNoParams1
     * StatementExpression = Expression
     * ConstantExpression = Expression
     */
     // 处理赋值表达式
    JCExpression term() {
        JCExpression t = term1();
        if ((mode & EXPR) != 0 &&
                S.token() == EQ || PLUSEQ.compareTo(S.token()) <= 0 && S.token().compareTo(GTGTGTEQ) <= 0)
            return termRest(t);
        else
            return t;
    }

    JCExpression termRest(JCExpression t) {
        switch (S.token()) {
            case EQ: { // 解析赋值表达式右侧的表达式
                int pos = S.pos();
                S.nextToken();
                mode = EXPR;
                JCExpression t1 = term();
                return toP(F.at(pos).Assign(t, t1));
            }
            // 解析符合表达式右侧的表达式
            case PLUSEQ:
            case SUBEQ:
            case STAREQ:
            case SLASHEQ:
            case PERCENTEQ:
            case AMPEQ:
            case BAREQ:
            case CARETEQ:
            case LTLTEQ:
            case GTGTEQ:
            case GTGTGTEQ:
                int pos = S.pos();
                Token token = S.token();
                S.nextToken();
                mode = EXPR;
                JCExpression t1 = term();
                return F.at(pos).Assignop(optag(token), t, t1);
            default:
                return t;
        }
    }

    /**
     * Expression1   = Expression2 [Expression1Rest]
     * Type1         = Type2
     * TypeNoParams1 = TypeNoParams2
     */
     // 处理三元表达式
    JCExpression term1() {
        JCExpression t = term2();
        if ((mode & EXPR) != 0 && S.token() == QUES) {
            mode = EXPR;
            return term1Rest(t);
        } else {
            return t;
        }
    }

    /**
     * Expression1Rest = ["?" Expression ":" Expression1]
     */
    JCExpression term1Rest(JCExpression t) {
        if (S.token() == QUES) {
            int pos = S.pos();
            S.nextToken();
            JCExpression t1 = term();
            accept(COLON);
            JCExpression t2 = term1();
            return F.at(pos).Conditional(t, t1, t2);
        } else {
            return t;
        }
    }

    /**
     * Expression2   = Expression3 [Expression2Rest]
     * Type2         = Type3
     * TypeNoParams2 = TypeNoParams3
     */
     // 处理二元表达式
    JCExpression term2() {
        JCExpression t = term3();
        if ((mode & EXPR) != 0 && prec(S.token()) >= TreeInfo.orPrec) {
            mode = EXPR;
            return term2Rest(t, TreeInfo.orPrec);
        } else {
            return t;
        }
    }

    /*  Expression2Rest = {infixop Expression3}
     *                  | Expression3 instanceof Type
     *  infixop         = "||"
     *                  | "&&"
     *                  | "|"
     *                  | "^"
     *                  | "&"
     *                  | "==" | "!="
     *                  | "<" | ">" | "<=" | ">="
     *                  | "<<" | ">>" | ">>>"
     *                  | "+" | "-"
     *                  | "*" | "/" | "%"
     */
    JCExpression term2Rest(JCExpression t, int minprec) {
        List<JCExpression[]> savedOd = odStackSupply.elems;
        JCExpression[] odStack = newOdStack();
        List<Token[]> savedOp = opStackSupply.elems;
        Token[] opStack = newOpStack();
        List<int[]> savedPos = posStackSupply.elems;
        int[] posStack = newPosStack();
        // optimization, was odStack = new Tree[...]; opStack = new Tree[...];
        int top = 0;
        odStack[0] = t;
        int startPos = S.pos();
        Token topOp = ERROR;
        int topOpPos = Position.NOPOS;
        while (prec(S.token()) >= minprec) {
            posStack[top] = topOpPos;
            opStack[top] = topOp;
            top++;
            topOp = S.token();
            topOpPos = S.pos();
            S.nextToken();
            odStack[top] = (topOp == INSTANCEOF) ? parseType() : term3();
            while (top > 0 && prec(topOp) >= prec(S.token())) {
                odStack[top - 1] = makeOp(topOpPos, topOp, odStack[top - 1],
                        odStack[top]);
                top--;
                topOp = opStack[top];
                topOpPos = posStack[top];
            }
        }
        Assert.check(top == 0);
        t = odStack[0];

        if (t.getTag() == JCTree.PLUS) {
            StringBuffer buf = foldStrings(t);
            if (buf != null) {
                t = toP(F.at(startPos).Literal(TypeTags.CLASS, buf.toString()));
            }
        }

        odStackSupply.elems = savedOd; // optimization
        opStackSupply.elems = savedOp; // optimization
        posStackSupply.elems = savedPos; // optimization
        return t;
    }
//where

    /**
     * Construct a binary or type test node.
     */
    private JCExpression makeOp(int pos,
                                Token topOp,
                                JCExpression od1,
                                JCExpression od2) {
        if (topOp == INSTANCEOF) {
            return F.at(pos).TypeTest(od1, od2);
        } else {
            return F.at(pos).Binary(optag(topOp), od1, od2);
        }
    }

    /**
     * If tree is a concatenation of string literals, replace it
     * by a single literal representing the concatenated string.
     */
    protected StringBuffer foldStrings(JCTree tree) {
        List<String> buf = List.nil();
        while (true) {
            if (tree.getTag() == JCTree.LITERAL) {
                JCLiteral lit = (JCLiteral) tree;
                if (lit.typetag == TypeTags.CLASS) {
                    StringBuffer sbuf =
                            new StringBuffer((String) lit.value);
                    while (buf.nonEmpty()) {
                        sbuf.append(buf.head);
                        buf = buf.tail;
                    }
                    return sbuf;
                }
            } else if (tree.getTag() == JCTree.PLUS) {
                JCBinary op = (JCBinary) tree;
                if (op.rhs.getTag() == JCTree.LITERAL) {
                    JCLiteral lit = (JCLiteral) op.rhs;
                    if (lit.typetag == TypeTags.CLASS) {
                        buf = buf.prepend((String) lit.value);
                        tree = op.lhs;
                        continue;
                    }
                }
            }
            return null;
        }
    }

    /**
     * optimization: To save allocating a new operand/operator stack
     * for every binary operation, we use supplys.
     */
    ListBuffer<JCExpression[]> odStackSupply = new ListBuffer<JCExpression[]>();
    ListBuffer<Token[]> opStackSupply = new ListBuffer<Token[]>();
    ListBuffer<int[]> posStackSupply = new ListBuffer<int[]>();

    private JCExpression[] newOdStack() {
        if (odStackSupply.elems == odStackSupply.last)
            odStackSupply.append(new JCExpression[infixPrecedenceLevels + 1]);
        JCExpression[] odStack = odStackSupply.elems.head;
        odStackSupply.elems = odStackSupply.elems.tail;
        return odStack;
    }

    private Token[] newOpStack() {
        if (opStackSupply.elems == opStackSupply.last)
            opStackSupply.append(new Token[infixPrecedenceLevels + 1]);
        Token[] opStack = opStackSupply.elems.head;
        opStackSupply.elems = opStackSupply.elems.tail;
        return opStack;
    }

    private int[] newPosStack() {
        if (posStackSupply.elems == posStackSupply.last)
            posStackSupply.append(new int[infixPrecedenceLevels + 1]);
        int[] posStack = posStackSupply.elems.head;
        posStackSupply.elems = posStackSupply.elems.tail;
        return posStack;
    }

    /**
     * Expression3    = PrefixOp Expression3
     * | "(" Expr | TypeNoParams ")" Expression3
     * | Primary {Selector} {PostfixOp}
     * Primary        = "(" Expression ")"
     * | Literal
     * | [TypeArguments] THIS [Arguments]
     * | [TypeArguments] SUPER SuperSuffix
     * | NEW [TypeArguments] Creator
     * | Ident { "." Ident }
     * [ "[" ( "]" BracketsOpt "." CLASS | Expression "]" )
     * | Arguments
     * | "." ( CLASS | THIS | [TypeArguments] SUPER Arguments | NEW [TypeArguments] InnerCreator )
     * ]
     * | BasicType BracketsOpt "." CLASS
     * PrefixOp       = "++" | "--" | "!" | "~" | "+" | "-"
     * PostfixOp      = "++" | "--"
     * Type3          = Ident { "." Ident } [TypeArguments] {TypeSelector} BracketsOpt
     * | BasicType
     * TypeNoParams3  = Ident { "." Ident } BracketsOpt
     * Selector       = "." [TypeArguments] Ident [Arguments]
     * | "." THIS
     * | "." [TypeArguments] SUPER SuperSuffix
     * | "." NEW [TypeArguments] InnerCreator
     * | "[" Expression "]"
     * TypeSelector   = "." Ident [TypeArguments]
     * SuperSuffix    = Arguments | "." Ident [Arguments]
     */
     // 处理基本表达式和含有一元运算符的表达式
    protected JCExpression term3() {
        int pos = S.pos();
        JCExpression t;
        List<JCExpression> typeArgs = typeArgumentsOpt(EXPR);
        switch (S.token()) {
            case QUES:
                if ((mode & TYPE) != 0 && (mode & (TYPEARG | NOPARAMS)) == TYPEARG) {
                    mode = TYPE;
                    return typeArgument();
                } else
                    return illegal();
            case PLUSPLUS:
            case SUBSUB:
            case BANG:
            case TILDE:
            case PLUS:
            case SUB:
                // 对负的十进制表示的整数类型字面量进行特殊处理
                if (typeArgs == null && (mode & EXPR) != 0) {
                    Token token = S.token();
                    S.nextToken();
                    mode = EXPR;
                    if (token == SUB &&
                            (S.token() == INTLITERAL || S.token() == LONGLITERAL) &&
                            S.radix() == 10) {
                        mode = EXPR;
                        t = literal(names.hyphen);
                    } else {
                        t = term3();
                        // unoptag会将token对象映射为JCTree类中定义的代表一元运算符的常量
                        return F.at(pos).Unary(unoptag(token), t);
                    }
                } else return illegal();
                break;
            case LPAREN:
                if (typeArgs == null && (mode & EXPR) != 0) {
                    S.nextToken();
                    mode = EXPR | TYPE | NOPARAMS;
                    // 第一个term3方法
                    t = term3();
                    if ((mode & TYPE) != 0 && S.token() == LT) {
                        // Could be a cast to a parameterized type
                        int op = JCTree.LT;
                        int pos1 = S.pos();
                        S.nextToken();
                        mode &= (EXPR | TYPE);
                        mode |= TYPEARG;
                        // 第二个term3方法
                        JCExpression t1 = term3();
                        if ((mode & TYPE) != 0 &&
                                (S.token() == COMMA || S.token() == GT)) {
                            // 解析参数化类型
                            mode = TYPE;
                            ListBuffer<JCExpression> args = new ListBuffer<JCExpression>();
                            args.append(t1);
                            while (S.token() == COMMA) {
                                S.nextToken();
                                args.append(typeArgument());
                            }
                            accept(GT);
                            t = toP(F.at(pos1).TypeApply(t, args.toList()));
                            checkGenerics();
                            while (S.token() == DOT) {
                                S.nextToken();
                                mode = TYPE;
                                t = toP(F.at(S.pos()).Select(t, ident()));
                                t = typeArgumentsOpt(t);
                            }
                            t = bracketsOpt(toP(t));
                        } else if ((mode & EXPR) != 0) {
                            // 左尖括号开头的是二元运算符的一部分, 生成二元表达式
                            mode = EXPR;
                            JCExpression e = term2Rest(t1, TreeInfo.shiftPrec);
                            t = F.at(pos1).Binary(op, t, e);
                            t = termRest(term1Rest(term2Rest(t, TreeInfo.orPrec)));
                        } else {
                            accept(GT);
                        }
                    } else {
                        // 当期望为表达式或当前token对象不代表左尖括号的时候, 相当于调用term()方法解析
                        t = termRest(term1Rest(term2Rest(t, TreeInfo.orPrec)));
                    }
                    accept(RPAREN);
                    lastmode = mode;
                    mode = EXPR;
                    if ((lastmode & EXPR) == 0) {
                        // 当括号中的项不为表达式, 只能为类型, 此时解析为强制类型转换表达式
                        JCExpression t1 = term3();
                        return F.at(pos).TypeCast(t, t1);
                    } else if ((lastmode & TYPE) != 0) {
                        // 括号中的项为类型的时候, 解析为强制类型转换表达式
                        switch (S.token()) {
                            /*case PLUSPLUS: case SUBSUB: */
                            case BANG:
                            case TILDE:
                            case LPAREN:
                            case THIS:
                            case SUPER:
                            case INTLITERAL:
                            case LONGLITERAL:
                            case FLOATLITERAL:
                            case DOUBLELITERAL:
                            case CHARLITERAL:
                            case STRINGLITERAL:
                            case TRUE:
                            case FALSE:
                            case NULL:
                            case NEW:
                            case IDENTIFIER:
                            case ASSERT:
                            case ENUM:
                            case BYTE:
                            case SHORT:
                            case CHAR:
                            case INT:
                            case LONG:
                            case FLOAT:
                            case DOUBLE:
                            case BOOLEAN:
                            case VOID:
                                JCExpression t1 = term3();
                                return F.at(pos).TypeCast(t, t1);
                        }
                    }
                } else return illegal();
                t = toP(F.at(pos).Parens(t));
                break;
            case THIS:
                if ((mode & EXPR) != 0) {
                    mode = EXPR;
                    t = to(F.at(pos).Ident(names._this));
                    S.nextToken();
                    if (typeArgs == null)
                        t = argumentsOpt(null, t);
                    else
                        t = arguments(typeArgs, t);
                    typeArgs = null;
                } else return illegal();
                break;
            case SUPER:
                if ((mode & EXPR) != 0) {
                    mode = EXPR;
                    t = to(F.at(pos).Ident(names._super));
                    t = superSuffix(typeArgs, t);
                    typeArgs = null;
                } else return illegal();
                break;
            case INTLITERAL:
            case LONGLITERAL:
            case FLOATLITERAL:
            case DOUBLELITERAL:
            case CHARLITERAL:
            case STRINGLITERAL:
            case TRUE:
            case FALSE:
            case NULL:
                if (typeArgs == null && (mode & EXPR) != 0) {
                    mode = EXPR;
                    t = literal(names.empty);
                } else return illegal();
                break;
            case NEW:
                if (typeArgs != null) return illegal();
                if ((mode & EXPR) != 0) {
                    mode = EXPR;
                    S.nextToken();
                    if (S.token() == LT) typeArgs = typeArguments(false);
                    t = creator(pos, typeArgs);
                    typeArgs = null;
                } else return illegal();
                break;
            case IDENTIFIER:
            case ASSERT:
            case ENUM:
                if (typeArgs != null) return illegal();
                t = toP(F.at(S.pos()).Ident(ident()));
                loop:
                while (true) {
                    pos = S.pos();
                    switch (S.token()) {
                        case LBRACKET:
                            S.nextToken();
                            if (S.token() == RBRACKET) {
                                S.nextToken();
                                t = bracketsOpt(t);
                                t = toP(F.at(pos).TypeArray(t));
                                t = bracketsSuffix(t);
                            } else {
                                if ((mode & EXPR) != 0) {
                                    mode = EXPR;
                                    JCExpression t1 = term();
                                    t = to(F.at(pos).Indexed(t, t1));
                                }
                                accept(RBRACKET);
                            }
                            break loop;
                        case LPAREN:
                            if ((mode & EXPR) != 0) {
                                mode = EXPR;
                                t = arguments(typeArgs, t);
                                typeArgs = null;
                            }
                            break loop;
                        case DOT:
                            S.nextToken();
                            int oldmode = mode;
                            mode &= ~NOPARAMS;
                            typeArgs = typeArgumentsOpt(EXPR);
                            mode = oldmode;
                            if ((mode & EXPR) != 0) {
                                switch (S.token()) {
                                    case CLASS:
                                        if (typeArgs != null) return illegal();
                                        mode = EXPR;
                                        t = to(F.at(pos).Select(t, names._class));
                                        S.nextToken();
                                        break loop;
                                    case THIS:
                                        if (typeArgs != null) return illegal();
                                        mode = EXPR;
                                        t = to(F.at(pos).Select(t, names._this));
                                        S.nextToken();
                                        break loop;
                                    case SUPER:
                                        mode = EXPR;
                                        t = to(F.at(pos).Select(t, names._super));
                                        t = superSuffix(typeArgs, t);
                                        typeArgs = null;
                                        break loop;
                                    case NEW:
                                        if (typeArgs != null) return illegal();
                                        mode = EXPR;
                                        int pos1 = S.pos();
                                        S.nextToken();
                                        if (S.token() == LT) typeArgs = typeArguments(false);
                                        t = innerCreator(pos1, typeArgs, t);
                                        typeArgs = null;
                                        break loop;
                                }
                            }
                            // typeArgs saved for next loop iteration.
                            t = toP(F.at(pos).Select(t, ident()));
                            break;
                        default:
                            break loop;
                    }
                }
                if (typeArgs != null) illegal();
                t = typeArgumentsOpt(t);
                break;
            case BYTE:
            case SHORT:
            case CHAR:
            case INT:
            case LONG:
            case FLOAT:
            case DOUBLE:
            case BOOLEAN:
                if (typeArgs != null) illegal();
                t = bracketsSuffix(bracketsOpt(basicType()));
                break;
            case VOID:
                if (typeArgs != null) illegal();
                if ((mode & EXPR) != 0) {
                    S.nextToken();
                    if (S.token() == DOT) {
                        JCPrimitiveTypeTree ti = toP(F.at(pos).TypeIdent(TypeTags.VOID));
                        t = bracketsSuffix(ti);
                    } else {
                        return illegal(pos);
                    }
                } else {
                    // Support the corner case of myMethodHandle.<void>invoke() by passing
                    // a void type (like other primitive types) to the next phase.
                    // The error will be reported in Attr.attribTypes or Attr.visitApply.
                    JCPrimitiveTypeTree ti = to(F.at(pos).TypeIdent(TypeTags.VOID));
                    S.nextToken();
                    return ti;
                    //return illegal();
                }
                break;
            default:
                return illegal();
        }
        if (typeArgs != null) illegal();
        while (true) {
            int pos1 = S.pos();
            if (S.token() == LBRACKET) {
                S.nextToken();
                if ((mode & TYPE) != 0) {
                    int oldmode = mode;
                    mode = TYPE;
                    if (S.token() == RBRACKET) {
                        S.nextToken();
                        t = bracketsOpt(t);
                        t = toP(F.at(pos1).TypeArray(t));
                        return t;
                    }
                    mode = oldmode;
                }
                if ((mode & EXPR) != 0) {
                    mode = EXPR;
                    JCExpression t1 = term();
                    t = to(F.at(pos1).Indexed(t, t1));
                }
                accept(RBRACKET);
            } else if (S.token() == DOT) {
                S.nextToken();
                typeArgs = typeArgumentsOpt(EXPR);
                if (S.token() == SUPER && (mode & EXPR) != 0) {
                    mode = EXPR;
                    t = to(F.at(pos1).Select(t, names._super));
                    S.nextToken();
                    t = arguments(typeArgs, t);
                    typeArgs = null;
                } else if (S.token() == NEW && (mode & EXPR) != 0) {
                    if (typeArgs != null) return illegal();
                    mode = EXPR;
                    int pos2 = S.pos();
                    S.nextToken();
                    if (S.token() == LT) typeArgs = typeArguments(false);
                    t = innerCreator(pos2, typeArgs, t);
                    typeArgs = null;
                } else {
                    t = toP(F.at(pos1).Select(t, ident()));
                    t = argumentsOpt(typeArgs, typeArgumentsOpt(t));
                    typeArgs = null;
                }
            } else {
                break;
            }
        }
        while ((S.token() == PLUSPLUS || S.token() == SUBSUB) && (mode & EXPR) != 0) {
            mode = EXPR;
            t = to(F.at(S.pos()).Unary(
                    S.token() == PLUSPLUS ? JCTree.POSTINC : JCTree.POSTDEC, t));
            S.nextToken();
        }
        return toP(t);
    }

    /**
     * SuperSuffix = Arguments | "." [TypeArguments] Ident [Arguments]
     */
    JCExpression superSuffix(List<JCExpression> typeArgs, JCExpression t) {
        S.nextToken();
        if (S.token() == LPAREN || typeArgs != null) {
            t = arguments(typeArgs, t);
        } else {
            int pos = S.pos();
            accept(DOT);
            typeArgs = (S.token() == LT) ? typeArguments(false) : null;
            t = toP(F.at(pos).Select(t, ident()));
            t = argumentsOpt(typeArgs, t);
        }
        return t;
    }

    /**
     * BasicType = BYTE | SHORT | CHAR | INT | LONG | FLOAT | DOUBLE | BOOLEAN
     */
    JCPrimitiveTypeTree basicType() {
        JCPrimitiveTypeTree t = to(F.at(S.pos()).TypeIdent(typetag(S.token())));
        S.nextToken();
        return t;
    }

    /**
     * ArgumentsOpt = [ Arguments ]
     */
    JCExpression argumentsOpt(List<JCExpression> typeArgs, JCExpression t) {
        if ((mode & EXPR) != 0 && S.token() == LPAREN || typeArgs != null) {
            mode = EXPR;
            return arguments(typeArgs, t);
        } else {
            return t;
        }
    }

    /**
     * Arguments = "(" [Expression { COMMA Expression }] ")"
     */
    List<JCExpression> arguments() {
        ListBuffer<JCExpression> args = lb();
        if (S.token() == LPAREN) {
            S.nextToken();
            if (S.token() != RPAREN) {
                args.append(parseExpression());
                while (S.token() == COMMA) {
                    S.nextToken();
                    args.append(parseExpression());
                }
            }
            accept(RPAREN);
        } else {
            syntaxError(S.pos(), "expected", LPAREN);
        }
        return args.toList();
    }

    JCMethodInvocation arguments(List<JCExpression> typeArgs, JCExpression t) {
        int pos = S.pos();
        List<JCExpression> args = arguments();
        return toP(F.at(pos).Apply(typeArgs, t, args));
    }

    /**
     * TypeArgumentsOpt = [ TypeArguments ]
     */
    JCExpression typeArgumentsOpt(JCExpression t) {
        if (S.token() == LT &&
                (mode & TYPE) != 0 &&
                (mode & NOPARAMS) == 0) {
            mode = TYPE;
            checkGenerics();
            return typeArguments(t, false);
        } else {
            return t;
        }
    }

    List<JCExpression> typeArgumentsOpt() {
        return typeArgumentsOpt(TYPE);
    }

    List<JCExpression> typeArgumentsOpt(int useMode) {
        if (S.token() == LT) {
            checkGenerics();
            if ((mode & useMode) == 0 ||
                    (mode & NOPARAMS) != 0) {
                illegal();
            }
            mode = useMode;
            return typeArguments(false);
        }
        return null;
    }

    /**
     * TypeArguments  = "<" TypeArgument {"," TypeArgument} ">"
     */
    List<JCExpression> typeArguments(boolean diamondAllowed) {
        if (S.token() == LT) {
            S.nextToken();
            if (S.token() == GT && diamondAllowed) {
                checkDiamond();
                mode |= DIAMOND;
                S.nextToken();
                return List.nil();
            } else {
                ListBuffer<JCExpression> args = ListBuffer.lb();
                args.append(((mode & EXPR) == 0) ? typeArgument() : parseType());
                while (S.token() == COMMA) {
                    S.nextToken();
                    args.append(((mode & EXPR) == 0) ? typeArgument() : parseType());
                }
                switch (S.token()) {
                    case GTGTGTEQ:
                        S.token(GTGTEQ);
                        break;
                    case GTGTEQ:
                        S.token(GTEQ);
                        break;
                    case GTEQ:
                        S.token(EQ);
                        break;
                    case GTGTGT:
                        S.token(GTGT);
                        break;
                    case GTGT:
                        S.token(GT);
                        break;
                    default:
                        accept(GT);
                        break;
                }
                return args.toList();
            }
        } else {
            syntaxError(S.pos(), "expected", LT);
            return List.nil();
        }
    }

    /**
     * TypeArgument = Type
     * | "?"
     * | "?" EXTENDS Type {"&" Type}
     * | "?" SUPER Type
     */
    JCExpression typeArgument() {
        if (S.token() != QUES) return parseType();
        int pos = S.pos();
        S.nextToken();
        if (S.token() == EXTENDS) {
            TypeBoundKind t = to(F.at(pos).TypeBoundKind(BoundKind.EXTENDS));
            S.nextToken();
            JCExpression bound = parseType();
            return F.at(pos).Wildcard(t, bound);
        } else if (S.token() == SUPER) {
            TypeBoundKind t = to(F.at(pos).TypeBoundKind(BoundKind.SUPER));
            S.nextToken();
            JCExpression bound = parseType();
            return F.at(pos).Wildcard(t, bound);
        } else if (S.token() == IDENTIFIER) {
            //error recovery
            reportSyntaxError(S.prevEndPos(), "expected3",
                    GT, EXTENDS, SUPER);
            TypeBoundKind t = F.at(Position.NOPOS).TypeBoundKind(BoundKind.UNBOUND);
            JCExpression wc = toP(F.at(pos).Wildcard(t, null));
            JCIdent id = toP(F.at(S.pos()).Ident(ident()));
            return F.at(pos).Erroneous(List.<JCTree>of(wc, id));
        } else {
            TypeBoundKind t = toP(F.at(pos).TypeBoundKind(BoundKind.UNBOUND));
            return toP(F.at(pos).Wildcard(t, null));
        }
    }

    JCTypeApply typeArguments(JCExpression t, boolean diamondAllowed) {
        int pos = S.pos();
        List<JCExpression> args = typeArguments(diamondAllowed);
        return toP(F.at(pos).TypeApply(t, args));
    }

    /**
     * BracketsOpt = {"[" "]"}
     */
    private JCExpression bracketsOpt(JCExpression t) {
        if (S.token() == LBRACKET) {
            int pos = S.pos();
            S.nextToken();
            t = bracketsOptCont(t, pos);
            F.at(pos);
        }
        return t;
    }

    private JCArrayTypeTree bracketsOptCont(JCExpression t, int pos) {
        accept(RBRACKET);
        t = bracketsOpt(t);
        return toP(F.at(pos).TypeArray(t));
    }

    /**
     * BracketsSuffixExpr = "." CLASS
     * BracketsSuffixType =
     */
    JCExpression bracketsSuffix(JCExpression t) {
        if ((mode & EXPR) != 0 && S.token() == DOT) {
            mode = EXPR;
            int pos = S.pos();
            S.nextToken();
            accept(CLASS);
            if (S.pos() == errorEndPos) {
                // error recovery
                Name name = null;
                if (S.token() == IDENTIFIER) {
                    name = S.name();
                    S.nextToken();
                } else {
                    name = names.error;
                }
                t = F.at(pos).Erroneous(List.<JCTree>of(toP(F.at(pos).Select(t, name))));
            } else {
                t = toP(F.at(pos).Select(t, names._class));
            }
        } else if ((mode & TYPE) != 0) {
            mode = TYPE;
        } else {
            syntaxError(S.pos(), "dot.class.expected");
        }
        return t;
    }

    /**
     * Creator = Qualident [TypeArguments] ( ArrayCreatorRest | ClassCreatorRest )
     */
    JCExpression creator(int newpos, List<JCExpression> typeArgs) {
        switch (S.token()) {
            case BYTE:
            case SHORT:
            case CHAR:
            case INT:
            case LONG:
            case FLOAT:
            case DOUBLE:
            case BOOLEAN:
                if (typeArgs == null)
                    return arrayCreatorRest(newpos, basicType());
                break;
            default:
        }
        JCExpression t = qualident();
        int oldmode = mode;
        mode = TYPE;
        boolean diamondFound = false;
        if (S.token() == LT) {
            checkGenerics();
            t = typeArguments(t, true);
            diamondFound = (mode & DIAMOND) != 0;
        }
        while (S.token() == DOT) {
            if (diamondFound) {
                //cannot select after a diamond
                illegal(S.pos());
            }
            int pos = S.pos();
            S.nextToken();
            t = toP(F.at(pos).Select(t, ident()));
            if (S.token() == LT) {
                checkGenerics();
                t = typeArguments(t, true);
                diamondFound = (mode & DIAMOND) != 0;
            }
        }
        mode = oldmode;
        if (S.token() == LBRACKET) {
            JCExpression e = arrayCreatorRest(newpos, t);
            if (typeArgs != null) {
                int pos = newpos;
                if (!typeArgs.isEmpty() && typeArgs.head.pos != Position.NOPOS) {
                    // note: this should always happen but we should
                    // not rely on this as the parser is continuously
                    // modified to improve error recovery.
                    pos = typeArgs.head.pos;
                }
                setErrorEndPos(S.prevEndPos());
                reportSyntaxError(pos, "cannot.create.array.with.type.arguments");
                return toP(F.at(newpos).Erroneous(typeArgs.prepend(e)));
            }
            return e;
        } else if (S.token() == LPAREN) {
            return classCreatorRest(newpos, null, typeArgs, t);
        } else {
            reportSyntaxError(S.pos(), "expected2",
                    LPAREN, LBRACKET);
            t = toP(F.at(newpos).NewClass(null, typeArgs, t, List.<JCExpression>nil(), null));
            return toP(F.at(newpos).Erroneous(List.<JCTree>of(t)));
        }
    }

    /**
     * InnerCreator = Ident [TypeArguments] ClassCreatorRest
     */
    JCExpression innerCreator(int newpos, List<JCExpression> typeArgs, JCExpression encl) {
        JCExpression t = toP(F.at(S.pos()).Ident(ident()));
        if (S.token() == LT) {
            int oldmode = mode;
            checkGenerics();
            t = typeArguments(t, true);
            mode = oldmode;
        }
        return classCreatorRest(newpos, encl, typeArgs, t);
    }

    /**
     * ArrayCreatorRest = "[" ( "]" BracketsOpt ArrayInitializer
     * | Expression "]" {"[" Expression "]"} BracketsOpt )
     */
    JCExpression arrayCreatorRest(int newpos, JCExpression elemtype) {
        accept(LBRACKET);
        if (S.token() == RBRACKET) {
            accept(RBRACKET);
            elemtype = bracketsOpt(elemtype);
            if (S.token() == LBRACE) {
                return arrayInitializer(newpos, elemtype);
            } else {
                return syntaxError(S.pos(), "array.dimension.missing");
            }
        } else {
            ListBuffer<JCExpression> dims = new ListBuffer<JCExpression>();
            dims.append(parseExpression());
            accept(RBRACKET);
            while (S.token() == LBRACKET) {
                int pos = S.pos();
                S.nextToken();
                if (S.token() == RBRACKET) {
                    elemtype = bracketsOptCont(elemtype, pos);
                } else {
                    dims.append(parseExpression());
                    accept(RBRACKET);
                }
            }
            return toP(F.at(newpos).NewArray(elemtype, dims.toList(), null));
        }
    }

    /**
     * ClassCreatorRest = Arguments [ClassBody]
     */
    JCNewClass classCreatorRest(int newpos,
                                JCExpression encl,
                                List<JCExpression> typeArgs,
                                JCExpression t) {
        List<JCExpression> args = arguments();
        JCClassDecl body = null;
        if (S.token() == LBRACE) {
            int pos = S.pos();
            List<JCTree> defs = classOrInterfaceBody(names.empty, false);
            JCModifiers mods = F.at(Position.NOPOS).Modifiers(0);
            body = toP(F.at(pos).AnonymousClassDef(mods, defs));
        }
        return toP(F.at(newpos).NewClass(encl, typeArgs, t, args, body));
    }

    /**
     * ArrayInitializer = "{" [VariableInitializer {"," VariableInitializer}] [","] "}"
     */
    JCExpression arrayInitializer(int newpos, JCExpression t) {
        accept(LBRACE);
        ListBuffer<JCExpression> elems = new ListBuffer<JCExpression>();
        if (S.token() == COMMA) {
            S.nextToken();
        } else if (S.token() != RBRACE) {
            elems.append(variableInitializer());
            while (S.token() == COMMA) {
                S.nextToken();
                if (S.token() == RBRACE) break;
                elems.append(variableInitializer());
            }
        }
        accept(RBRACE);
        return toP(F.at(newpos).NewArray(t, List.<JCExpression>nil(), elems.toList()));
    }

    /**
     * VariableInitializer = ArrayInitializer | Expression
     */
    public JCExpression variableInitializer() {
        return S.token() == LBRACE ? arrayInitializer(S.pos(), null) : parseExpression();
    }

    /**
     * ParExpression = "(" Expression ")"
     */
    JCExpression parExpression() {
        accept(LPAREN);
        JCExpression t = parseExpression();
        accept(RPAREN);
        return t;
    }

    /**
     * Block = "{" BlockStatements "}"
     */
    JCBlock block(int pos, long flags) {
        accept(LBRACE);
        List<JCStatement> stats = blockStatements();
        JCBlock t = F.at(pos).Block(flags, stats);
        while (S.token() == CASE || S.token() == DEFAULT) {
            syntaxError("orphaned", S.token());
            switchBlockStatementGroups();
        }
        // the Block node has a field "endpos" for first char of last token, which is
        // usually but not necessarily the last char of the last token.
        t.endpos = S.pos();
        accept(RBRACE);
        return toP(t);
    }

    public JCBlock block() {
        return block(S.pos(), 0);
    }

    /**
     * BlockStatements = { BlockStatement }
     * BlockStatement  = LocalVariableDeclarationStatement
     * | ClassOrInterfaceOrEnumDeclaration
     * | [Ident ":"] Statement
     * LocalVariableDeclarationStatement
     * = { FINAL | '@' Annotation } Type VariableDeclarators ";"
     */
    @SuppressWarnings("fallthrough")
    List<JCStatement> blockStatements() {
//todo: skip to anchor on error(?)
        int lastErrPos = -1;
        ListBuffer<JCStatement> stats = new ListBuffer<JCStatement>();
        while (true) {
            int pos = S.pos();
            switch (S.token()) {
                case RBRACE:
                case CASE:
                case DEFAULT:
                case EOF:
                    // 返回空的语句集合
                    return stats.toList();
                case LBRACE:
                case IF:
                case FOR:
                case WHILE:
                case DO:
                case TRY:
                case SWITCH:
                case SYNCHRONIZED:
                case RETURN:
                case THROW:
                case BREAK:
                case CONTINUE:
                case SEMI:
                case ELSE:
                case FINALLY:
                case CATCH:
                    stats.append(parseStatement());
                    break;
                case MONKEYS_AT:
                case FINAL: {
                    String dc = S.docComment();
                    JCModifiers mods = modifiersOpt();
                    if (S.token() == INTERFACE ||
                            S.token() == CLASS ||
                            allowEnums && S.token() == ENUM) {
                        // 解析块中的类型
                        stats.append(classOrInterfaceOrEnumDeclaration(mods, dc));
                    } else {
                        // 解析块中的变量
                        JCExpression t = parseType();
                        stats.appendList(variableDeclarators(mods, t,
                                new ListBuffer<JCStatement>()));
                        // A "LocalVariableDeclarationStatement" subsumes the terminating semicolon
                        storeEnd(stats.elems.last(), S.endPos());
                        accept(SEMI);
                    }
                    break;
                }
                case ABSTRACT:
                case STRICTFP: {
                    // 解析块中的类型
                    String dc = S.docComment();
                    JCModifiers mods = modifiersOpt();
                    stats.append(classOrInterfaceOrEnumDeclaration(mods, dc));
                    break;
                }
                case INTERFACE:
                case CLASS:
                    // 解析块中的类型
                    stats.append(classOrInterfaceOrEnumDeclaration(modifiersOpt(),
                            S.docComment()));
                    break;
                case ENUM:
                case ASSERT:
                    // 解析块中的枚举类型
                    if (allowEnums && S.token() == ENUM) {
                        error(S.pos(), "local.enum");
                        stats.
                                append(classOrInterfaceOrEnumDeclaration(modifiersOpt(),
                                        S.docComment()));
                        break;
                    } else if (allowAsserts && S.token() == ASSERT) {
                        // 解析块中的断言语句
                        stats.append(parseStatement());
                        break;
                    }
                    /* fall through to default */
                default:
                    Name name = S.name();
                    JCExpression t = term(EXPR | TYPE);
                    if (S.token() == COLON && t.getTag() == JCTree.IDENT) {
                        // 解析有标记的语句
                        S.nextToken();
                        JCStatement stat = parseStatement();
                        stats.append(F.at(pos).Labelled(name, stat));
                    } else if ((lastmode & TYPE) != 0 &&
                            (S.token() == IDENTIFIER ||
                                    S.token() == ASSERT ||
                                    S.token() == ENUM)) {
                        // 解析变量
                        pos = S.pos();
                        JCModifiers mods = F.at(Position.NOPOS).Modifiers(0);
                        F.at(pos);
                        stats.appendList(variableDeclarators(mods, t,
                                new ListBuffer<JCStatement>()));
                        // A "LocalVariableDeclarationStatement" subsumes the terminating semicolon
                        storeEnd(stats.elems.last(), S.endPos());
                        accept(SEMI);
                    } else {
                        // 解析语句
                        // This Exec is an "ExpressionStatement"; it subsumes the terminating semicolon
                        stats.append(to(F.at(pos).Exec(checkExprStat(t))));
                        accept(SEMI);
                    }
            }

            // error recovery
            if (S.pos() == lastErrPos)
                return stats.toList();
            if (S.pos() <= errorEndPos) {
                skip(false, true, true, true);
                lastErrPos = S.pos();
            }

            // ensure no dangling /** @deprecated */ active
            S.resetDeprecatedFlag();
        }
    }

    /**
     * Statement =
     * Block
     * | IF ParExpression Statement [ELSE Statement]
     * | FOR "(" ForInitOpt ";" [Expression] ";" ForUpdateOpt ")" Statement
     * | FOR "(" FormalParameter : Expression ")" Statement
     * | WHILE ParExpression Statement
     * | DO Statement WHILE ParExpression ";"
     * | TRY Block ( Catches | [Catches] FinallyPart )
     * | TRY "(" ResourceSpecification ";"opt ")" Block [Catches] [FinallyPart]
     * | SWITCH ParExpression "{" SwitchBlockStatementGroups "}"
     * | SYNCHRONIZED ParExpression Block
     * | RETURN [Expression] ";"
     * | THROW Expression ";"
     * | BREAK [Ident] ";"
     * | CONTINUE [Ident] ";"
     * | ASSERT Expression [ ":" Expression ] ";"
     * | ";"
     * | ExpressionStatement
     * | Ident ":" Statement
     */
    @SuppressWarnings("fallthrough")
    public JCStatement parseStatement() {
        int pos = S.pos();
        switch (S.token()) {
            case LBRACE:
                // 解析块
                return block();
            case IF: {
                // 解析if语句
                S.nextToken();
                JCExpression cond = parExpression();
                JCStatement thenpart = parseStatement();
                JCStatement elsepart = null;
                if (S.token() == ELSE) {
                    S.nextToken();
                    elsepart = parseStatement();
                }
                return F.at(pos).If(cond, thenpart, elsepart);
            }
            case FOR: {
                // 解析for语句
                S.nextToken();
                accept(LPAREN);
                List<JCStatement> inits = S.token() == SEMI ? List.<JCStatement>nil() : forInit();
                if (inits.length() == 1 &&
                        inits.head.getTag() == JCTree.VARDEF &&
                        ((JCVariableDecl) inits.head).init == null &&
                        S.token() == COLON) {
                    checkForeach();
                    JCVariableDecl var = (JCVariableDecl) inits.head;
                    accept(COLON);
                    JCExpression expr = parseExpression();
                    accept(RPAREN);
                    JCStatement body = parseStatement();
                    return F.at(pos).ForeachLoop(var, expr, body);
                } else {
                    accept(SEMI);
                    JCExpression cond = S.token() == SEMI ? null : parseExpression();
                    accept(SEMI);
                    List<JCExpressionStatement> steps = S.token() == RPAREN ? List.<JCExpressionStatement>nil() : forUpdate();
                    accept(RPAREN);
                    JCStatement body = parseStatement();
                    return F.at(pos).ForLoop(inits, cond, steps, body);
                }
            }
            case WHILE: {
                // 解析while语句
                S.nextToken();
                JCExpression cond = parExpression();
                JCStatement body = parseStatement();
                return F.at(pos).WhileLoop(cond, body);
            }
            case DO: {
                // 解析do-while语句
                S.nextToken();
                JCStatement body = parseStatement();
                accept(WHILE);
                JCExpression cond = parExpression();
                JCDoWhileLoop t = to(F.at(pos).DoLoop(body, cond));
                accept(SEMI);
                return t;
            }
            case TRY: {
                // 解析try语句
                S.nextToken();
                List<JCTree> resources = List.<JCTree>nil();
                if (S.token() == LPAREN) {
                    checkTryWithResources();
                    S.nextToken();
                    resources = resources();
                    accept(RPAREN);
                }
                JCBlock body = block();
                ListBuffer<JCCatch> catchers = new ListBuffer<JCCatch>();
                JCBlock finalizer = null;
                if (S.token() == CATCH || S.token() == FINALLY) {
                    while (S.token() == CATCH) catchers.append(catchClause());
                    if (S.token() == FINALLY) {
                        S.nextToken();
                        finalizer = block();
                    }
                } else {
                    if (allowTWR) {
                        if (resources.isEmpty())
                            error(pos, "try.without.catch.finally.or.resource.decls");
                    } else
                        error(pos, "try.without.catch.or.finally");
                }
                return F.at(pos).Try(resources, body, catchers.toList(), finalizer);
            }
            case SWITCH: {
                // 解析switch语句
                S.nextToken();
                JCExpression selector = parExpression();
                accept(LBRACE);
                List<JCCase> cases = switchBlockStatementGroups();
                JCSwitch t = to(F.at(pos).Switch(selector, cases));
                accept(RBRACE);
                return t;
            }
            case SYNCHRONIZED: {
                // 解析同步块
                S.nextToken();
                JCExpression lock = parExpression();
                JCBlock body = block();
                return F.at(pos).Synchronized(lock, body);
            }
            case RETURN: {
                // 解析return语句
                S.nextToken();
                JCExpression result = S.token() == SEMI ? null : parseExpression();
                JCReturn t = to(F.at(pos).Return(result));
                accept(SEMI);
                return t;
            }
            case THROW: {
                // 解析throw语句
                S.nextToken();
                JCExpression exc = parseExpression();
                JCThrow t = to(F.at(pos).Throw(exc));
                accept(SEMI);
                return t;
            }
            case BREAK: {
                // 解析break
                S.nextToken();
                Name label = (S.token() == IDENTIFIER || S.token() == ASSERT || S.token() == ENUM) ? ident() : null;
                JCBreak t = to(F.at(pos).Break(label));
                accept(SEMI);
                return t;
            }
            case CONTINUE: {
                // 解析continue
                S.nextToken();
                Name label = (S.token() == IDENTIFIER || S.token() == ASSERT || S.token() == ENUM) ? ident() : null;
                JCContinue t = to(F.at(pos).Continue(label));
                accept(SEMI);
                return t;
            }
            case SEMI:
                // 解析只有一个分号的语句
                S.nextToken();
                return toP(F.at(pos).Skip());
            // 下面三个case对错误进行兼容处理
            case ELSE:
                return toP(F.Exec(syntaxError("else.without.if")));
            case FINALLY:
                return toP(F.Exec(syntaxError("finally.without.try")));
            case CATCH:
                return toP(F.Exec(syntaxError("catch.without.try")));
            case ASSERT: {
                // 解析assert语句
                if (allowAsserts && S.token() == ASSERT) {
                    S.nextToken();
                    JCExpression assertion = parseExpression();
                    JCExpression message = null;
                    if (S.token() == COLON) {
                        S.nextToken();
                        message = parseExpression();
                    }
                    JCAssert t = to(F.at(pos).Assert(assertion, message));
                    accept(SEMI);
                    return t;
                }
                /* else fall through to default case */
            }
            case ENUM:
            default:
                // 解析剩下可能出现的语句, 例如含有标记的语句
                Name name = S.name();
                JCExpression expr = parseExpression();
                if (S.token() == COLON && expr.getTag() == JCTree.IDENT) {
                    S.nextToken();
                    JCStatement stat = parseStatement();
                    return F.at(pos).Labelled(name, stat);
                } else {
                    // This Exec is an "ExpressionStatement"; it subsumes the terminating semicolon
                    JCExpressionStatement stat = to(F.at(pos).Exec(checkExprStat(expr)));
                    accept(SEMI);
                    return stat;
                }
        }
    }

    /**
     * CatchClause     = CATCH "(" FormalParameter ")" Block
     */
    JCCatch catchClause() {
        int pos = S.pos();
        accept(CATCH);
        accept(LPAREN);
        JCModifiers mods = optFinal(Flags.PARAMETER);
        List<JCExpression> catchTypes = catchTypes();
        JCExpression paramType = catchTypes.size() > 1 ?
                toP(F.at(catchTypes.head.getStartPosition()).TypeUnion(catchTypes)) :
                catchTypes.head;
        JCVariableDecl formal = variableDeclaratorId(mods, paramType);
        accept(RPAREN);
        JCBlock body = block();
        return F.at(pos).Catch(formal, body);
    }

    List<JCExpression> catchTypes() {
        ListBuffer<JCExpression> catchTypes = ListBuffer.lb();
        catchTypes.add(parseType());
        while (S.token() == BAR) {
            checkMulticatch();
            S.nextToken();
            catchTypes.add(qualident());
        }
        return catchTypes.toList();
    }

    /**
     * SwitchBlockStatementGroups = { SwitchBlockStatementGroup }
     * SwitchBlockStatementGroup = SwitchLabel BlockStatements
     * SwitchLabel = CASE ConstantExpression ":" | DEFAULT ":"
     */
    List<JCCase> switchBlockStatementGroups() {
        ListBuffer<JCCase> cases = new ListBuffer<JCCase>();
        while (true) {
            int pos = S.pos();
            switch (S.token()) {
                case CASE: {
                    S.nextToken();
                    JCExpression pat = parseExpression();
                    accept(COLON);
                    List<JCStatement> stats = blockStatements();
                    JCCase c = F.at(pos).Case(pat, stats);
                    if (stats.isEmpty())
                        storeEnd(c, S.prevEndPos());
                    cases.append(c);
                    break;
                }
                case DEFAULT: {
                    S.nextToken();
                    accept(COLON);
                    List<JCStatement> stats = blockStatements();
                    JCCase c = F.at(pos).Case(null, stats);
                    if (stats.isEmpty())
                        storeEnd(c, S.prevEndPos());
                    cases.append(c);
                    break;
                }
                case RBRACE:
                case EOF:
                    return cases.toList();
                default:
                    S.nextToken(); // to ensure progress
                    syntaxError(pos, "expected3",
                            CASE, DEFAULT, RBRACE);
            }
        }
    }

    /**
     * MoreStatementExpressions = { COMMA StatementExpression }
     */
    <T extends ListBuffer<? super JCExpressionStatement>> T moreStatementExpressions(int pos,
                                                                                     JCExpression first,
                                                                                     T stats) {
        // This Exec is a "StatementExpression"; it subsumes no terminating token
        stats.append(toP(F.at(pos).Exec(checkExprStat(first))));
        while (S.token() == COMMA) {
            S.nextToken();
            pos = S.pos();
            JCExpression t = parseExpression();
            // This Exec is a "StatementExpression"; it subsumes no terminating token
            stats.append(toP(F.at(pos).Exec(checkExprStat(t))));
        }
        return stats;
    }

    /**
     * ForInit = StatementExpression MoreStatementExpressions
     * |  { FINAL | '@' Annotation } Type VariableDeclarators
     */
    List<JCStatement> forInit() {
        ListBuffer<JCStatement> stats = lb();
        int pos = S.pos();
        if (S.token() == FINAL || S.token() == MONKEYS_AT) {
            return variableDeclarators(optFinal(0), parseType(), stats).toList();
        } else {
            JCExpression t = term(EXPR | TYPE);
            if ((lastmode & TYPE) != 0 &&
                    (S.token() == IDENTIFIER || S.token() == ASSERT || S.token() == ENUM))
                return variableDeclarators(modifiersOpt(), t, stats).toList();
            else
                return moreStatementExpressions(pos, t, stats).toList();
        }
    }

    /**
     * ForUpdate = StatementExpression MoreStatementExpressions
     */
    List<JCExpressionStatement> forUpdate() {
        return moreStatementExpressions(S.pos(),
                parseExpression(),
                new ListBuffer<JCExpressionStatement>()).toList();
    }

    /**
     * AnnotationsOpt = { '@' Annotation }
     */
    List<JCAnnotation> annotationsOpt() {
        if (S.token() != MONKEYS_AT) return List.nil(); // optimization
        ListBuffer<JCAnnotation> buf = new ListBuffer<JCAnnotation>();
        while (S.token() == MONKEYS_AT) {
            int pos = S.pos();
            S.nextToken();
            buf.append(annotation(pos));
        }
        return buf.toList();
    }

    /**
     * ModifiersOpt = { Modifier }
     * Modifier = PUBLIC | PROTECTED | PRIVATE | STATIC | ABSTRACT | FINAL
     * | NATIVE | SYNCHRONIZED | TRANSIENT | VOLATILE | "@"
     * | "@" Annotation
     */
    JCModifiers modifiersOpt() {
        return modifiersOpt(null);
    }

    JCModifiers modifiersOpt(JCModifiers partial) {
        long flags;
        ListBuffer<JCAnnotation> annotations = new ListBuffer<JCAnnotation>();
        int pos;
        if (partial == null) {
            flags = 0;
            pos = S.pos();
        } else {
            flags = partial.flags;
            annotations.appendList(partial.annotations);
            pos = partial.pos;
        }
        if (S.deprecatedFlag()) {
            flags |= Flags.DEPRECATED;
            S.resetDeprecatedFlag();
        }
        int lastPos = Position.NOPOS;
        loop:
        while (true) {
            long flag;
            switch (S.token()) {
                case PRIVATE:
                    flag = Flags.PRIVATE;
                    break;
                case PROTECTED:
                    flag = Flags.PROTECTED;
                    break;
                case PUBLIC:
                    flag = Flags.PUBLIC;
                    break;
                case STATIC:
                    flag = Flags.STATIC;
                    break;
                case TRANSIENT:
                    flag = Flags.TRANSIENT;
                    break;
                case FINAL:
                    flag = Flags.FINAL;
                    break;
                case ABSTRACT:
                    flag = Flags.ABSTRACT;
                    break;
                case NATIVE:
                    flag = Flags.NATIVE;
                    break;
                case VOLATILE:
                    flag = Flags.VOLATILE;
                    break;
                case SYNCHRONIZED:
                    flag = Flags.SYNCHRONIZED;
                    break;
                case STRICTFP:
                    flag = Flags.STRICTFP;
                    break;
                case MONKEYS_AT:
                    flag = Flags.ANNOTATION;
                    break;
                default:
                    break loop;
            }
            if ((flags & flag) != 0) error(S.pos(), "repeated.modifier");
            lastPos = S.pos();
            S.nextToken();
            if (flag == Flags.ANNOTATION) {
                checkAnnotations();
                if (S.token() != INTERFACE) {
                    JCAnnotation ann = annotation(lastPos);
                    // if first modifier is an annotation, set pos to annotation's.
                    if (flags == 0 && annotations.isEmpty())
                        pos = ann.pos;
                    annotations.append(ann);
                    lastPos = ann.pos;
                    flag = 0;
                }
            }
            flags |= flag;
        }
        switch (S.token()) {
            case ENUM:
                flags |= Flags.ENUM;
                break;
            case INTERFACE:
                flags |= Flags.INTERFACE;
                break;
            default:
                break;
        }

        /* A modifiers tree with no modifier tokens or annotations
         * has no text position. */
        if ((flags & (Flags.ModifierFlags | Flags.ANNOTATION)) == 0 && annotations.isEmpty())
            pos = Position.NOPOS;

        JCModifiers mods = F.at(pos).Modifiers(flags, annotations.toList());
        if (pos != Position.NOPOS)
            storeEnd(mods, S.prevEndPos());
        return mods;
    }

    /**
     * Annotation              = "@" Qualident [ "(" AnnotationFieldValues ")" ]
     *
     * @param pos position of "@" token
     */
    JCAnnotation annotation(int pos) {
        // accept(AT); // AT consumed by caller
        checkAnnotations();
        JCTree ident = qualident();
        List<JCExpression> fieldValues = annotationFieldValuesOpt();
        JCAnnotation ann = F.at(pos).Annotation(ident, fieldValues);
        storeEnd(ann, S.prevEndPos());
        return ann;
    }

    List<JCExpression> annotationFieldValuesOpt() {
        return (S.token() == LPAREN) ? annotationFieldValues() : List.<JCExpression>nil();
    }

    /**
     * AnnotationFieldValues   = "(" [ AnnotationFieldValue { "," AnnotationFieldValue } ] ")"
     */
    List<JCExpression> annotationFieldValues() {
        accept(LPAREN);
        ListBuffer<JCExpression> buf = new ListBuffer<JCExpression>();
        if (S.token() != RPAREN) {
            buf.append(annotationFieldValue());
            while (S.token() == COMMA) {
                S.nextToken();
                buf.append(annotationFieldValue());
            }
        }
        accept(RPAREN);
        return buf.toList();
    }

    /**
     * AnnotationFieldValue    = AnnotationValue
     * | Identifier "=" AnnotationValue
     */
    JCExpression annotationFieldValue() {
        if (S.token() == IDENTIFIER) {
            mode = EXPR;
            JCExpression t1 = term1();
            if (t1.getTag() == JCTree.IDENT && S.token() == EQ) {
                int pos = S.pos();
                accept(EQ);
                JCExpression v = annotationValue();
                return toP(F.at(pos).Assign(t1, v));
            } else {
                return t1;
            }
        }
        return annotationValue();
    }

    /* AnnotationValue          = ConditionalExpression
     *                          | Annotation
     *                          | "{" [ AnnotationValue { "," AnnotationValue } ] [","] "}"
     */
    JCExpression annotationValue() {
        int pos;
        switch (S.token()) {
            case MONKEYS_AT:
                pos = S.pos();
                S.nextToken();
                return annotation(pos);
            case LBRACE:
                pos = S.pos();
                accept(LBRACE);
                ListBuffer<JCExpression> buf = new ListBuffer<JCExpression>();
                if (S.token() != RBRACE) {
                    buf.append(annotationValue());
                    while (S.token() == COMMA) {
                        S.nextToken();
                        if (S.token() == RBRACE) break;
                        buf.append(annotationValue());
                    }
                }
                accept(RBRACE);
                return toP(F.at(pos).NewArray(null, List.<JCExpression>nil(), buf.toList()));
            default:
                mode = EXPR;
                return term1();
        }
    }

    /**
     * VariableDeclarators = VariableDeclarator { "," VariableDeclarator }
     */
    public <T extends ListBuffer<? super JCVariableDecl>> T variableDeclarators(JCModifiers mods,
                                                                                JCExpression type,
                                                                                T vdefs) {
        return variableDeclaratorsRest(S.pos(), mods, type, ident(), false, null, vdefs);
    }

    /**
     * VariableDeclaratorsRest = VariableDeclaratorRest { "," VariableDeclarator }
     * ConstantDeclaratorsRest = ConstantDeclaratorRest { "," ConstantDeclarator }
     *
     * @param reqInit Is an initializer always required?
     * @param dc      The documentation comment for the variable declarations, or null.
     */
    <T extends ListBuffer<? super JCVariableDecl>> T variableDeclaratorsRest(int pos,
                                                                             JCModifiers mods,
                                                                             JCExpression type,
                                                                             Name name,
                                                                             boolean reqInit,
                                                                             String dc,
                                                                             T vdefs) {
        vdefs.append(variableDeclaratorRest(pos, mods, type, name, reqInit, dc));
        while (S.token() == COMMA) {
            // All but last of multiple declarators subsume a comma
            storeEnd((JCTree) vdefs.elems.last(), S.endPos());
            S.nextToken();
            vdefs.append(variableDeclarator(mods, type, reqInit, dc));
        }
        return vdefs;
    }

    /**
     * VariableDeclarator = Ident VariableDeclaratorRest
     * ConstantDeclarator = Ident ConstantDeclaratorRest
     */
    JCVariableDecl variableDeclarator(JCModifiers mods, JCExpression type, boolean reqInit, String dc) {
        return variableDeclaratorRest(S.pos(), mods, type, ident(), reqInit, dc);
    }

    /**
     * VariableDeclaratorRest = BracketsOpt ["=" VariableInitializer]
     * ConstantDeclaratorRest = BracketsOpt "=" VariableInitializer
     *
     * @param reqInit Is an initializer always required?
     * @param dc      The documentation comment for the variable declarations, or null.
     */
    JCVariableDecl variableDeclaratorRest(int pos, JCModifiers mods, JCExpression type, Name name,
                                          boolean reqInit, String dc) {
        type = bracketsOpt(type);
        JCExpression init = null;
        if (S.token() == EQ) {
            S.nextToken();
            init = variableInitializer();
        } else if (reqInit) syntaxError(S.pos(), "expected", EQ);
        JCVariableDecl result =
                toP(F.at(pos).VarDef(mods, name, type, init));
        attach(result, dc);
        return result;
    }

    /**
     * VariableDeclaratorId = Ident BracketsOpt
     */
    JCVariableDecl variableDeclaratorId(JCModifiers mods, JCExpression type) {
        int pos = S.pos();
        Name name = ident();
        if ((mods.flags & Flags.VARARGS) != 0 &&
                S.token() == LBRACKET) {
            log.error(S.pos(), "varargs.and.old.array.syntax");
        }
        type = bracketsOpt(type);
        return toP(F.at(pos).VarDef(mods, name, type, null));
    }

    /**
     * Resources = Resource { ";" Resources }
     */
    List<JCTree> resources() {
        ListBuffer<JCTree> defs = new ListBuffer<JCTree>();
        defs.append(resource());
        while (S.token() == SEMI) {
            // All but last of multiple declarators must subsume a semicolon
            storeEnd(defs.elems.last(), S.endPos());
            int semiColonPos = S.pos();
            S.nextToken();
            if (S.token() == RPAREN) { // Optional trailing semicolon
                // after last resource
                break;
            }
            defs.append(resource());
        }
        return defs.toList();
    }

    /**
     * Resource = VariableModifiersOpt Type VariableDeclaratorId = Expression
     */
    JCTree resource() {
        return variableDeclaratorRest(S.pos(), optFinal(Flags.FINAL),
                parseType(), ident(), true, null);
    }

    /**
     * CompilationUnit = [ { "@" Annotation } PACKAGE Qualident ";"] {ImportDeclaration} {TypeDeclaration}
     */
    public JCTree.JCCompilationUnit parseCompilationUnit() {
        int pos = S.pos();
        JCExpression pid = null;
        String dc = S.docComment();
        JCModifiers mods = null;
        List<JCAnnotation> packageAnnotations = List.nil();
        // 解析访问修饰符
        if (S.token() == MONKEYS_AT) // 解析包上的注解
            mods = modifiersOpt(); // 读取注解
        // 解析package关键字
        if (S.token() == PACKAGE) { // 解析包
            if (mods != null) {
                checkNoMods(mods.flags);
                packageAnnotations = mods.annotations;
                mods = null;
            }
            S.nextToken();
            pid = qualident(); // 解析包名
            accept(SEMI);
        }
        ListBuffer<JCTree> defs = new ListBuffer<JCTree>();
        boolean checkForImports = true;
        while (S.token() != EOF) {
            if (S.pos() <= errorEndPos) {
                // error recovery
                skip(checkForImports, false, false, false);
                if (S.token() == EOF)
                    break;
            }
            // 解析import关键字
            if (checkForImports && mods == null && S.token() == IMPORT) {
                // 解析导入声明
                defs.append(importDeclaration());
            } else {
                // 解析主体信息并转换为语法树
                // 解析类型声明
                JCTree def = typeDeclaration(mods);
                if (keepDocComments && dc != null && docComments.get(def) == dc) {
                    // If the first type declaration has consumed the first doc
                    // comment, then don't use it for the top level comment as well.
                    dc = null;
                }
                if (def instanceof JCExpressionStatement)
                    def = ((JCExpressionStatement) def).expr;
                defs.append(def);
                if (def instanceof JCClassDecl)
                    checkForImports = false;
                mods = null;
            }
        }
        // 创建一个JCCompilationUnit对象topLevel, 这就是抽象语法树的根节点
        JCTree.JCCompilationUnit toplevel = F.at(pos).TopLevel(packageAnnotations, pid, defs.toList());
        attach(toplevel, dc);
        if (defs.elems.isEmpty())
            storeEnd(toplevel, S.prevEndPos());
        if (keepDocComments)
            toplevel.docComments = docComments;
        if (keepLineMap)
            toplevel.lineMap = S.getLineMap();
        return toplevel;
    }

    /**
     * ImportDeclaration = IMPORT [ STATIC ] Ident { "." Ident } [ "." "*" ] ";"
     */
    JCTree importDeclaration() {
        int pos = S.pos();
        S.nextToken();
        boolean importStatic = false;
        // 解析静态导入声明
        if (S.token() == STATIC) {
            checkStaticImports();
            importStatic = true;
            S.nextToken();
        }
        // 根据name对象解析出一个JCIdent语法节点
        JCExpression pid = toP(F.at(S.pos()).Ident(ident()));
        do {
            int pos1 = S.pos();
            accept(DOT);
            // 根据name对象解析出一个JCFieldAccess语法节点
            if (S.token() == STAR) {
                pid = to(F.at(pos1).Select(pid, names.asterisk));
                S.nextToken();
                break;
            } else {
                pid = toP(F.at(pos1).Select(pid, ident()));
            }
        } while (S.token() == DOT);
        accept(SEMI);
        // 将JCIdent和JCFieldAccess语法节点整合为一个JCImport语法树
        return toP(F.at(pos).Import(pid, importStatic));
    }

    /**
     * TypeDeclaration = ClassOrInterfaceOrEnumDeclaration
     * | ";"
     */
    JCTree typeDeclaration(JCModifiers mods) {
        int pos = S.pos();
        if (mods == null && S.token() == SEMI) {
            S.nextToken();
            return toP(F.at(pos).Skip());
        } else {
            String dc = S.docComment();
            // 解析类和接口
            return classOrInterfaceOrEnumDeclaration(modifiersOpt(mods), dc);
        }
    }

    /**
     * ClassOrInterfaceOrEnumDeclaration = ModifiersOpt
     * (ClassDeclaration | InterfaceDeclaration | EnumDeclaration)
     *
     * @param mods Any modifiers starting the class or interface declaration
     * @param dc   The documentation comment for the class, or null.
     */
     // class, enum, interface都是解析为JCClassDecl语法树
    JCStatement classOrInterfaceOrEnumDeclaration(JCModifiers mods, String dc) {
        // 匹配Token.CLASS
        if (S.token() == CLASS) {// 解析类
            // 将类型解析为一颗JCClassDecl语法树
            return classDeclaration(mods, dc);
        } else if (S.token() == INTERFACE) {// 解析接口和注解(特殊的接口)
            // 将接口类型解析为一颗JCClassDecl语法树
            return interfaceDeclaration(mods, dc);
        } else if (allowEnums) {
            // 匹配Token.ENUM
            if (S.token() == ENUM) { // 解析枚举类
                // 将枚举类型解析为一颗JCClassDecl语法树
                return enumDeclaration(mods, dc);
            } else {
                int pos = S.pos();
                List<JCTree> errs;
                if (S.token() == IDENTIFIER) {
                    errs = List.<JCTree>of(mods, toP(F.at(pos).Ident(ident())));
                    setErrorEndPos(S.pos());
                } else {
                    errs = List.<JCTree>of(mods);
                }
                return toP(F.Exec(syntaxError(pos, errs, "expected3",
                        CLASS, INTERFACE, ENUM)));
            }
        } else {
            // 匹配Token.ENUM
            if (S.token() == ENUM) {
                error(S.pos(), "enums.not.supported.in.source", source.name);
                allowEnums = true;
                // 将枚举类型解析为一颗JCClassDecl语法树
                return enumDeclaration(mods, dc);
            }
            int pos = S.pos();
            List<JCTree> errs;
            if (S.token() == IDENTIFIER) {
                errs = List.<JCTree>of(mods, toP(F.at(pos).Ident(ident())));
                setErrorEndPos(S.pos());
            } else {
                errs = List.<JCTree>of(mods);
            }
            return toP(F.Exec(syntaxError(pos, errs, "expected2",
                    CLASS, INTERFACE)));
        }
    }

    /**
     * ClassDeclaration = CLASS Ident TypeParametersOpt [EXTENDS Type]
     * [IMPLEMENTS TypeList] ClassBody
     *
     * @param mods The modifiers starting the class declaration
     * @param dc   The documentation comment for the class, or null.
     */
    JCClassDecl classDeclaration(JCModifiers mods, String dc) {
        int pos = S.pos();
        accept(CLASS);
        // 解析类名
        Name name = ident();
        // 解析形式类型参数
        List<JCTypeParameter> typarams = typeParametersOpt();

        JCExpression extending = null;
        if (S.token() == EXTENDS) {
            // 解析父类
            S.nextToken();
            extending = parseType();
        }
        List<JCExpression> implementing = List.nil();
        if (S.token() == IMPLEMENTS) {
            // 解析实现接口
            S.nextToken();
            implementing = typeList();
        }
        // 解析类的body体
        // 解析类中的所有成员信息, 并存储在集合中
        List<JCTree> defs = classOrInterfaceBody(name, false);
        //将类中所有的成员信息整合为一颗JCClassDecl语法树
        JCClassDecl result = toP(F.at(pos).ClassDef(
                mods, name, typarams, extending, implementing, defs));
        attach(result, dc);
        return result;
    }

    /**
     * InterfaceDeclaration = INTERFACE Ident TypeParametersOpt
     * [EXTENDS TypeList] InterfaceBody
     *
     * @param mods The modifiers starting the interface declaration
     * @param dc   The documentation comment for the interface, or null.
     */
    JCClassDecl interfaceDeclaration(JCModifiers mods, String dc) {
        int pos = S.pos();
        accept(INTERFACE);
        Name name = ident();

        List<JCTypeParameter> typarams = typeParametersOpt();

        List<JCExpression> extending = List.nil();
        if (S.token() == EXTENDS) {
            S.nextToken();
            extending = typeList();
        }
        List<JCTree> defs = classOrInterfaceBody(name, true);
        JCClassDecl result = toP(F.at(pos).ClassDef(
                mods, name, typarams, null, extending, defs));
        attach(result, dc);
        return result;
    }

    /**
     * EnumDeclaration = ENUM Ident [IMPLEMENTS TypeList] EnumBody
     *
     * @param mods The modifiers starting the enum declaration
     * @param dc   The documentation comment for the enum, or null.
     */
    JCClassDecl enumDeclaration(JCModifiers mods, String dc) {
        int pos = S.pos();
        accept(ENUM);
        Name name = ident();

        List<JCExpression> implementing = List.nil();
        if (S.token() == IMPLEMENTS) {
            S.nextToken();
            implementing = typeList();
        }

        List<JCTree> defs = enumBody(name);
        mods.flags |= Flags.ENUM;
        JCClassDecl result = toP(F.at(pos).
                ClassDef(mods, name, List.<JCTypeParameter>nil(),
                        null, implementing, defs));
        attach(result, dc);
        return result;
    }

    /**
     * EnumBody = "{" { EnumeratorDeclarationList } [","]
     * [ ";" {ClassBodyDeclaration} ] "}"
     */
    List<JCTree> enumBody(Name enumName) {
        accept(LBRACE);
        ListBuffer<JCTree> defs = new ListBuffer<JCTree>();
        if (S.token() == COMMA) {
            S.nextToken();
        } else if (S.token() != RBRACE && S.token() != SEMI) {
            defs.append(enumeratorDeclaration(enumName));
            while (S.token() == COMMA) {
                S.nextToken();
                if (S.token() == RBRACE || S.token() == SEMI) break;
                defs.append(enumeratorDeclaration(enumName));
            }
            if (S.token() != SEMI && S.token() != RBRACE) {
                defs.append(syntaxError(S.pos(), "expected3",
                        COMMA, RBRACE, SEMI));
                S.nextToken();
            }
        }
        if (S.token() == SEMI) {
            S.nextToken();
            while (S.token() != RBRACE && S.token() != EOF) {
                defs.appendList(classOrInterfaceBodyDeclaration(enumName,
                        false));
                if (S.pos() <= errorEndPos) {
                    // error recovery
                    skip(false, true, true, false);
                }
            }
        }
        accept(RBRACE);
        return defs.toList();
    }

    /**
     * EnumeratorDeclaration = AnnotationsOpt [TypeArguments] IDENTIFIER [ Arguments ] [ "{" ClassBody "}" ]
     */
    JCTree enumeratorDeclaration(Name enumName) {
        String dc = S.docComment();
        int flags = Flags.PUBLIC | Flags.STATIC | Flags.FINAL | Flags.ENUM;
        if (S.deprecatedFlag()) {
            flags |= Flags.DEPRECATED;
            S.resetDeprecatedFlag();
        }
        int pos = S.pos();
        List<JCAnnotation> annotations = annotationsOpt();
        JCModifiers mods = F.at(annotations.isEmpty() ? Position.NOPOS : pos).Modifiers(flags, annotations);
        List<JCExpression> typeArgs = typeArgumentsOpt();
        int identPos = S.pos();
        Name name = ident();
        int createPos = S.pos();
        List<JCExpression> args = (S.token() == LPAREN)
                ? arguments() : List.<JCExpression>nil();
        JCClassDecl body = null;
        if (S.token() == LBRACE) {
            JCModifiers mods1 = F.at(Position.NOPOS).Modifiers(Flags.ENUM | Flags.STATIC);
            List<JCTree> defs = classOrInterfaceBody(names.empty, false);
            body = toP(F.at(identPos).AnonymousClassDef(mods1, defs));
        }
        if (args.isEmpty() && body == null)
            createPos = identPos;
        JCIdent ident = F.at(identPos).Ident(enumName);
        JCNewClass create = F.at(createPos).NewClass(null, typeArgs, ident, args, body);
        if (createPos != identPos)
            storeEnd(create, S.prevEndPos());
        ident = F.at(identPos).Ident(enumName);
        JCTree result = toP(F.at(pos).VarDef(mods, name, ident, create));
        attach(result, dc);
        return result;
    }

    /**
     * TypeList = Type {"," Type}
     */
    List<JCExpression> typeList() {
        ListBuffer<JCExpression> ts = new ListBuffer<JCExpression>();
        ts.append(parseType());
        while (S.token() == COMMA) {
            S.nextToken();
            ts.append(parseType());
        }
        return ts.toList();
    }

    /**
     * ClassBody     = "{" {ClassBodyDeclaration} "}"
     * InterfaceBody = "{" {InterfaceBodyDeclaration} "}"
     */
    List<JCTree> classOrInterfaceBody(Name className, boolean isInterface) {
        accept(LBRACE);
        if (S.pos() <= errorEndPos) {
            // error recovery
            skip(false, true, false, false);
            if (S.token() == LBRACE)
                S.nextToken();
        }
        ListBuffer<JCTree> defs = new ListBuffer<JCTree>();
        // 解释body
        while (S.token() != RBRACE && S.token() != EOF) {
            defs.appendList(classOrInterfaceBodyDeclaration(className, isInterface));
            if (S.pos() <= errorEndPos) {
                // error recovery
                skip(false, true, true, false);
            }
        }
        accept(RBRACE);
        return defs.toList();
    }

    /**
     * ClassBodyDeclaration =
     * ";"
     * | [STATIC] Block
     * | ModifiersOpt
     * ( Type Ident
     * ( VariableDeclaratorsRest ";" | MethodDeclaratorRest )
     * | VOID Ident MethodDeclaratorRest
     * | TypeParameters (Type | VOID) Ident MethodDeclaratorRest
     * | Ident ConstructorDeclaratorRest
     * | TypeParameters Ident ConstructorDeclaratorRest
     * | ClassOrInterfaceOrEnumDeclaration
     * )
     * InterfaceBodyDeclaration =
     * ";"
     * | ModifiersOpt Type Ident
     * ( ConstantDeclaratorsRest | InterfaceMethodDeclaratorRest ";" )
     */
    List<JCTree> classOrInterfaceBodyDeclaration(Name className, boolean isInterface) {
        if (S.token() == SEMI) {
            S.nextToken();
            return List.<JCTree>nil();
        } else {
            String dc = S.docComment();
            int pos = S.pos();
            // 获取modifier
            JCModifiers mods = modifiersOpt();
            if (S.token() == CLASS ||
                S.token() == INTERFACE ||
                allowEnums && S.token() == ENUM) { // 解释成员类型
                return List.<JCTree>of(classOrInterfaceOrEnumDeclaration(mods, dc));
            } else if (S.token() == LBRACE && !isInterface &&
                       (mods.flags & Flags.StandardFlags & ~Flags.STATIC) == 0 &&
                       mods.annotations.isEmpty()) { // 解释匿名块
                return List.<JCTree>of(block(pos, mods.flags));
            } else {
                pos = S.pos();
                // 解释成员变量或成员方法
                List<JCTypeParameter> typarams = typeParametersOpt();
                // if there are type parameters but no modifiers, save the start
                // position of the method in the modifiers.
                if (typarams.nonEmpty() && mods.pos == Position.NOPOS) {
                    mods.pos = pos;
                    storeEnd(mods, pos);
                }
                Name name = S.name();
                pos = S.pos();
                JCExpression type;
                boolean isVoid = S.token() == VOID;
                if (isVoid) {
                    type = to(F.at(pos).TypeIdent(TypeTags.VOID));
                    S.nextToken();
                } else {
                    type = parseType();
                }
                if (S.token() == LPAREN && !isInterface && type.getTag() == JCTree.IDENT) {
                    if (isInterface || name != className)
                        error(pos, "invalid.meth.decl.ret.type.req");
                    return List.of(methodDeclaratorRest(
                            pos, mods, null, names.init, typarams,
                            isInterface, true, dc));
                } else {
                    pos = S.pos();
                    name = ident();
                    if (S.token() == LPAREN) {
                        return List.of(methodDeclaratorRest(
                                pos, mods, type, name, typarams,
                                isInterface, isVoid, dc));
                    } else if (!isVoid && typarams.isEmpty()) {
                        List<JCTree> defs =
                                variableDeclaratorsRest(pos, mods, type, name, isInterface, dc,
                                        new ListBuffer<JCTree>()).toList();
                        storeEnd(defs.last(), S.endPos());
                        accept(SEMI);
                        return defs;
                    } else {
                        pos = S.pos();
                        List<JCTree> err = isVoid
                                ? List.<JCTree>of(toP(F.at(pos).MethodDef(mods, name, type, typarams,
                                List.<JCVariableDecl>nil(), List.<JCExpression>nil(), null, null)))
                                : null;
                        return List.<JCTree>of(syntaxError(S.pos(), err, "expected", LPAREN));
                    }
                }
            }
        }
    }

    /** MethodDeclaratorRest =
     *      FormalParameters BracketsOpt [Throws TypeList] ( MethodBody | [DEFAULT AnnotationValue] ";")
     *  VoidMethodDeclaratorRest =
     *      FormalParameters [Throws TypeList] ( MethodBody | ";")
     *  InterfaceMethodDeclaratorRest =
     *      FormalParameters BracketsOpt [THROWS TypeList] ";"
     *  VoidInterfaceMethodDeclaratorRest =
     *      FormalParameters [THROWS TypeList] ";"
     *  ConstructorDeclaratorRest =
     *      "(" FormalParameterListOpt ")" [THROWS TypeList] MethodBody
     */
    JCTree methodDeclaratorRest(int pos,
                                JCModifiers mods,
                                JCExpression type,
                                Name name,
                                List<JCTypeParameter> typarams,
                                boolean isInterface, boolean isVoid,
                                String dc) {
        // 解析方法中的形式参数
        List<JCVariableDecl> params = formalParameters();
        if (!isVoid) type = bracketsOpt(type);
        List<JCExpression> thrown = List.nil();
        // 解析方法中抛出的异常
        if (S.token() == THROWS) {
            S.nextToken();
            thrown = qualidentList();
        }
        JCBlock body = null;
        JCExpression defaultValue;
        // 解析方法的body体
        if (S.token() == LBRACE) {
            body = block();
            defaultValue = null;
        } else {
            if (S.token() == DEFAULT) {
                accept(DEFAULT);
                // 注解类型的接口
                defaultValue = annotationValue();
            } else {
                defaultValue = null;
            }
            accept(SEMI);
            if (S.pos() <= errorEndPos) {
                // error recovery
                skip(false, true, false, false);
                // 解析方法的body体
                if (S.token() == LBRACE) {
                    body = block();
                }
            }
        }

        JCMethodDecl result =
                toP(F.at(pos).MethodDef(mods, name, type, typarams,
                        params, thrown,
                        body, defaultValue));
        attach(result, dc);
        return result;
    }

    /**
     * QualidentList = Qualident {"," Qualident}
     */
    List<JCExpression> qualidentList() {
        ListBuffer<JCExpression> ts = new ListBuffer<JCExpression>();
        ts.append(qualident());
        while (S.token() == COMMA) {
            S.nextToken();
            ts.append(qualident());
        }
        return ts.toList();
    }

    /**
     * TypeParametersOpt = ["<" TypeParameter {"," TypeParameter} ">"]
     */
    List<JCTypeParameter> typeParametersOpt() {
        if (S.token() == LT) {
            checkGenerics();
            ListBuffer<JCTypeParameter> typarams = new ListBuffer<JCTypeParameter>();
            S.nextToken();
            typarams.append(typeParameter());
            // 循环对多个形式类型参数进行解释
            while (S.token() == COMMA) {
                S.nextToken();
                typarams.append(typeParameter());
            }
            accept(GT);
            return typarams.toList();
        } else {
            return List.nil();
        }
    }

    /**
     * TypeParameter = TypeVariable [TypeParameterBound]
     * TypeParameterBound = EXTENDS Type {"&" Type}
     * TypeVariable = Ident
     */
    JCTypeParameter typeParameter() {
        int pos = S.pos();
        Name name = ident();
        ListBuffer<JCExpression> bounds = new ListBuffer<JCExpression>();
        // 解释类型参数中声明的上界
        if (S.token() == EXTENDS) {
            S.nextToken();
            bounds.append(parseType());
            // 上界可能有多个, 需要循环调用进行分析
            while (S.token() == AMP) {
                S.nextToken();
                bounds.append(parseType());
            }
        }
        return toP(F.at(pos).TypeParameter(name, bounds.toList()));
    }

    /**
     * FormalParameters = "(" [ FormalParameterList ] ")"
     * FormalParameterList = [ FormalParameterListNovarargs , ] LastFormalParameter
     * FormalParameterListNovarargs = [ FormalParameterListNovarargs , ] FormalParameter
     */
    List<JCVariableDecl> formalParameters() {
        ListBuffer<JCVariableDecl> params = new ListBuffer<JCVariableDecl>();
        JCVariableDecl lastParam = null;
        accept(LPAREN);
        if (S.token() != RPAREN) {
            params.append(lastParam = formalParameter());
            while ((lastParam.mods.flags & Flags.VARARGS) == 0 && S.token() == COMMA) {
                S.nextToken();
                params.append(lastParam = formalParameter());
            }
        }
        accept(RPAREN);
        return params.toList();
    }

    JCModifiers optFinal(long flags) {
        JCModifiers mods = modifiersOpt();
        checkNoMods(mods.flags & ~(Flags.FINAL | Flags.DEPRECATED));
        mods.flags |= flags;
        return mods;
    }

    /**
     * FormalParameter = { FINAL | '@' Annotation } Type VariableDeclaratorId
     * LastFormalParameter = { FINAL | '@' Annotation } Type '...' Ident | FormalParameter
     */
    JCVariableDecl formalParameter() {
        JCModifiers mods = optFinal(Flags.PARAMETER);
        JCExpression type = parseType();
        if (S.token() == ELLIPSIS) {
            checkVarargs();
            mods.flags |= Flags.VARARGS;
            type = to(F.at(S.pos()).TypeArray(type));
            S.nextToken();
        }
        return variableDeclaratorId(mods, type);
    }

    /* ---------- auxiliary methods -------------- */

    void error(int pos, String key, Object... args) {
        log.error(DiagnosticFlag.SYNTAX, pos, key, args);
    }

    void warning(int pos, String key, Object... args) {
        log.warning(pos, key, args);
    }

    /**
     * Check that given tree is a legal expression statement.
     */
    protected JCExpression checkExprStat(JCExpression t) {
        switch (t.getTag()) {
            case JCTree.PREINC:
            case JCTree.PREDEC:
            case JCTree.POSTINC:
            case JCTree.POSTDEC:
            case JCTree.ASSIGN:
            case JCTree.BITOR_ASG:
            case JCTree.BITXOR_ASG:
            case JCTree.BITAND_ASG:
            case JCTree.SL_ASG:
            case JCTree.SR_ASG:
            case JCTree.USR_ASG:
            case JCTree.PLUS_ASG:
            case JCTree.MINUS_ASG:
            case JCTree.MUL_ASG:
            case JCTree.DIV_ASG:
            case JCTree.MOD_ASG:
            case JCTree.APPLY:
            case JCTree.NEWCLASS:
            case JCTree.ERRONEOUS:
                return t;
            default:
                error(t.pos, "not.stmt");
                return F.at(t.pos).Erroneous(List.<JCTree>of(t));
        }
    }

    /**
     * Return precedence of operator represented by token,
     * -1 if token is not a binary operator. @see TreeInfo.opPrec
     */
    static int prec(Token token) {
        int oc = optag(token);
        return (oc >= 0) ? TreeInfo.opPrec(oc) : -1;
    }

    /**
     * Return the lesser of two positions, making allowance for either one
     * being unset.
     */
    static int earlier(int pos1, int pos2) {
        if (pos1 == Position.NOPOS)
            return pos2;
        if (pos2 == Position.NOPOS)
            return pos1;
        return (pos1 < pos2 ? pos1 : pos2);
    }

    /**
     * Return operation tag of binary operator represented by token,
     * -1 if token is not a binary operator.
     */
    static int optag(Token token) {
        switch (token) {
            case BARBAR:
                return JCTree.OR;
            case AMPAMP:
                return JCTree.AND;
            case BAR:
                return JCTree.BITOR;
            case BAREQ:
                return JCTree.BITOR_ASG;
            case CARET:
                return JCTree.BITXOR;
            case CARETEQ:
                return JCTree.BITXOR_ASG;
            case AMP:
                return JCTree.BITAND;
            case AMPEQ:
                return JCTree.BITAND_ASG;
            case EQEQ:
                return JCTree.EQ;
            case BANGEQ:
                return JCTree.NE;
            case LT:
                return JCTree.LT;
            case GT:
                return JCTree.GT;
            case LTEQ:
                return JCTree.LE;
            case GTEQ:
                return JCTree.GE;
            case LTLT:
                return JCTree.SL;
            case LTLTEQ:
                return JCTree.SL_ASG;
            case GTGT:
                return JCTree.SR;
            case GTGTEQ:
                return JCTree.SR_ASG;
            case GTGTGT:
                return JCTree.USR;
            case GTGTGTEQ:
                return JCTree.USR_ASG;
            case PLUS:
                return JCTree.PLUS;
            case PLUSEQ:
                return JCTree.PLUS_ASG;
            case SUB:
                return JCTree.MINUS;
            case SUBEQ:
                return JCTree.MINUS_ASG;
            case STAR:
                return JCTree.MUL;
            case STAREQ:
                return JCTree.MUL_ASG;
            case SLASH:
                return JCTree.DIV;
            case SLASHEQ:
                return JCTree.DIV_ASG;
            case PERCENT:
                return JCTree.MOD;
            case PERCENTEQ:
                return JCTree.MOD_ASG;
            case INSTANCEOF:
                return JCTree.TYPETEST;
            default:
                return -1;
        }
    }

    /**
     * Return operation tag of unary operator represented by token,
     * -1 if token is not a binary operator.
     */
    static int unoptag(Token token) {
        switch (token) {
            case PLUS:
                return JCTree.POS;
            case SUB:
                return JCTree.NEG;
            case BANG:
                return JCTree.NOT;
            case TILDE:
                return JCTree.COMPL;
            case PLUSPLUS:
                return JCTree.PREINC;
            case SUBSUB:
                return JCTree.PREDEC;
            default:
                // 如果token对象中没有对应的一元运算符常量, 则返回-1
                return -1;
        }
    }

    /**
     * Return type tag of basic type represented by token,
     * -1 if token is not a basic type identifier.
     */
    static int typetag(Token token) {
        switch (token) {
            case BYTE:
                return TypeTags.BYTE;
            case CHAR:
                return TypeTags.CHAR;
            case SHORT:
                return TypeTags.SHORT;
            case INT:
                return TypeTags.INT;
            case LONG:
                return TypeTags.LONG;
            case FLOAT:
                return TypeTags.FLOAT;
            case DOUBLE:
                return TypeTags.DOUBLE;
            case BOOLEAN:
                return TypeTags.BOOLEAN;
            default:
                return -1;
        }
    }

    void checkGenerics() {
        if (!allowGenerics) {
            error(S.pos(), "generics.not.supported.in.source", source.name);
            allowGenerics = true;
        }
    }

    void checkVarargs() {
        if (!allowVarargs) {
            error(S.pos(), "varargs.not.supported.in.source", source.name);
            allowVarargs = true;
        }
    }

    void checkForeach() {
        if (!allowForeach) {
            error(S.pos(), "foreach.not.supported.in.source", source.name);
            allowForeach = true;
        }
    }

    void checkStaticImports() {
        if (!allowStaticImport) {
            error(S.pos(), "static.import.not.supported.in.source", source.name);
            allowStaticImport = true;
        }
    }

    void checkAnnotations() {
        if (!allowAnnotations) {
            error(S.pos(), "annotations.not.supported.in.source", source.name);
            allowAnnotations = true;
        }
    }

    void checkDiamond() {
        if (!allowDiamond) {
            error(S.pos(), "diamond.not.supported.in.source", source.name);
            allowDiamond = true;
        }
    }

    void checkMulticatch() {
        if (!allowMulticatch) {
            error(S.pos(), "multicatch.not.supported.in.source", source.name);
            allowMulticatch = true;
        }
    }

    void checkTryWithResources() {
        if (!allowTWR) {
            error(S.pos(), "try.with.resources.not.supported.in.source", source.name);
            allowTWR = true;
        }
    }
}
