/*
 * Copyright (c) 2002, 2010, Oracle and/or its affiliates. All rights reserved.
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

import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

import static com.sun.tools.javac.parser.Token.*;

/**
 * Map from Name to Token and Token to String.
 *
 * <p><b>This is NOT part of any supported API.
 * If you write code that depends on this, you do so at your own risk.
 * This code and its internal interfaces are subject to change or
 * deletion without notice.</b>
 */
public class Keywords {
    public static final Context.Key<Keywords> keywordsKey =
        new Context.Key<Keywords>();

    public static Keywords instance(Context context) {
        Keywords instance = context.get(keywordsKey);
        if (instance == null)
            instance = new Keywords(context);
        return instance;
    }

    private final Names names;

    protected Keywords(Context context) {
        context.put(keywordsKey, this);
        names = Names.instance(context);

        // 循环所有的token对象, 如果name的值不为空, 则调用enterKeyword方法 建立 token对象到name对象的映射,
        // 如果name值为空, 将tokenName数组中调用t.ordinal()方法获取的下标处的值设置为null
        for (Token t : Token.values()) {
            if (t.name != null)
                enterKeyword(t.name, t);
            else
                tokenName[t.ordinal()] = null;
        }

        // 初始化key数组
        key = new Token[maxKey+1];
        // 先默认初始化为identifier
        for (int i = 0; i <= maxKey; i++) key[i] = IDENTIFIER;
        for (Token t : Token.values()) {
            if (t.name != null)
                key[tokenName[t.ordinal()].getIndex()] = t;
        }
    }


    public Token key(Name name) {
        return (name.getIndex() > maxKey) ? IDENTIFIER : key[name.getIndex()];
    }

    /**
     * Keyword array. Maps name indices to Token.
     */
    // 由于一般都是通过具体的NameImpl找到对应的Token对象, 所以我们这里还需要建立Name->token对象的映射关系
    // 这里index存储的肯定是不连续的
    private final Token[] key;

    /**  The number of the last entered keyword.
     */
    private int maxKey = 0;

    /** The names of all tokens.
     */
     // 根据下标(token枚举序号)保存了token枚举中各个对象到Name对象的映射关系
     // 例如 Name[0]=null, Name[3]=NameImpl("abstract")
    private Name[] tokenName = new Name[Token.values().length];

    private void enterKeyword(String s, Token token) {
        Name n = names.fromString(s);
        tokenName[token.ordinal()] = n;
        // 这里保存的是index的最大值(是字符数组在bytes中的下标)
        if (n.getIndex() > maxKey) maxKey = n.getIndex();
    }
}
