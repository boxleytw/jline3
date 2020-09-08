/*
 * Copyright (c) 2002-2020, the original author or authors.
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.script;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.jline.builtins.Nano.SyntaxHighlighter;
import org.jline.builtins.Styles;
import org.jline.console.CmdDesc;
import org.jline.console.CmdLine;
import org.jline.console.ScriptEngine;
import org.jline.groovy.Utils;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.completer.AggregateCompleter;
import org.jline.reader.impl.completer.ArgumentCompleter;
import org.jline.reader.impl.completer.NullCompleter;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.utils.AttributedString;
import org.jline.utils.Log;
import org.jline.utils.OSUtils;
import org.jline.utils.StyleResolver;

import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.GroovyShell;
import groovy.lang.Script;

/**
 * Implements Groovy ScriptEngine.
 * You must be very careful when using GroovyEngine in a multithreaded environment. The Binding instance is not
 * thread safe, and it is shared by all scripts.
 *
 * @author <a href="mailto:matti.rintanikkola@gmail.com">Matti Rinta-Nikkola</a>
 */
public class GroovyEngine implements ScriptEngine {
    public enum Format {JSON, GROOVY, NONE}

    public static final String CANONICAL_NAMES = "canonicalNames";
    public static final String NANORC_SYNTAX = "nanorcSyntax";
    public static final String NANORC_VALUE = "nanorcValue";
    public static final String GROOVY_COLORS = "GROOVY_COLORS";
    public static final String NO_SYNTAX_CHECK = "noSyntaxCheck";
    public static final String RESTRICTED_COMPLETION = "restrictedCompletion";

    private static final String VAR_GROOVY_OPTIONS = "GROOVY_OPTIONS";
    private static final String REGEX_SYSTEM_VAR = "[A-Z]+[A-Z_]*";
    private static final String REGEX_VAR = "[a-zA-Z_]+[a-zA-Z0-9_]*";
    private static final Pattern PATTERN_FUNCTION_DEF = Pattern.compile("^def\\s+(" + REGEX_VAR + ")\\s*\\(([a-zA-Z0-9_ ,]*)\\)\\s*\\{(.*)?}(|\n)$"
                                                                     , Pattern.DOTALL);
    private static final Pattern PATTERN_CLASS_DEF = Pattern.compile("^class\\s+(" + REGEX_VAR + ") .*?\\{.*?}(|\n)$"
                                                                  , Pattern.DOTALL);
    private static final Pattern PATTERN_CLASS_NAME = Pattern.compile("(.*?)\\.([A-Z].*)");
    private static final List<String> defaultImports = Arrays.asList("java.lang.*", "java.util.*", "java.io.*"
                                                     , "java.net.*", "groovy.lang.*", "groovy.util.*"
                                                     , "java.math.BigInteger", "java.math.BigDecimal");
    private final Map<String,Class<?>> defaultNameClass = new HashMap<>();
    private final GroovyShell shell;
    protected Binding sharedData;
    private final Map<String,String> imports = new HashMap<>();
    private final Map<String,String> methods = new HashMap<>();
    private final Map<String,Class<?>> nameClass;
    private Cloner objectCloner = new ObjectCloner();

    public interface Cloner {
        Object clone(Object obj);
        void markCache();
        void purgeCache();
    }

    public GroovyEngine() {
        this.sharedData = new Binding();
        shell = new GroovyShell(sharedData);
        for (String s : defaultImports) {
            addToNameClass(s, defaultNameClass);
        }
        nameClass = new HashMap<>(defaultNameClass);
    }

    @Override
    public Completer getScriptCompleter() {
        return compileCompleter();
    }

    @Override
    public boolean hasVariable(String name) {
        return sharedData.hasVariable(name);
    }

    @Override
    public void put(String name, Object value) {
        sharedData.setProperty(name, value);
    }

    @Override
    public Object get(String name) {
        return sharedData.hasVariable(name) ? sharedData.getVariable(name) : null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String,Object> find(String name) {
        Map<String, Object> out = new HashMap<>();
        if (name == null) {
            out = sharedData.getVariables();
        } else {
            for (String v : internalFind(name)) {
                out.put(v, get(v));
            }
        }
        return out;
    }

    @Override
    public List<String> getSerializationFormats() {
        return Arrays.asList(Format.JSON.toString(), Format.NONE.toString());
    }

    @Override
    public List<String> getDeserializationFormats() {
        return Arrays.asList(Format.JSON.toString(), Format.GROOVY.toString(), Format.NONE.toString());
    }

    @Override
    public Object deserialize(String value, String formatStr) {
        Object out = value;
        Format format = formatStr != null && !formatStr.isEmpty() ? Format.valueOf(formatStr.toUpperCase()) : null;
        if (format == Format.NONE) {
            // do nothing
        } else if (format == Format.JSON) {
            out = Utils.toObject(value);
        } else if (format == Format.GROOVY) {
            try {
                out = execute(value);
            } catch (Exception e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        } else {
            value = value.trim();
            boolean hasCurly = value.contains("{") && value.contains("}");
            try {
                if (value.startsWith("[") && value.endsWith("]")) {
                    try {
                        if (hasCurly) {
                            out = Utils.toObject(value); // try json
                        } else {
                            out = execute(value);
                        }
                    } catch (Exception e) {
                        if (hasCurly) {
                            try {
                                out = execute(value);
                            } catch (Exception e2) {
                                // ignore
                            }
                        } else {
                            out = Utils.toObject(value); // try json
                        }
                    }
                } else if (value.startsWith("{") && value.endsWith("}")) {
                    out = Utils.toObject(value);
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return out;
    }

    @Override
    public void persist(Path file, Object object) {
        persist(file, object, getSerializationFormats().get(0));
    }

    @Override
    public void persist(Path file, Object object, String format) {
        Utils.persist(file, object, Format.valueOf(format.toUpperCase()));
    }

    @Override
    public Object execute(File script, Object[] args) throws Exception {
        sharedData.setProperty("_args", args);
        Script s = shell.parse(script);
        return s.run();
    }

    private static Set<Class<?>> classesForPackage(String pckgname) throws ClassNotFoundException {
        String name = pckgname;
        Matcher matcher = PATTERN_CLASS_NAME.matcher(name);
        if (matcher.matches()) {
            name = matcher.group(1) + ".**";
        }
        Set<Class<?>> out = new HashSet<>(PackageHelper.getClassesForPackage(name));
        if (out.isEmpty()) {
            out.addAll(JrtJavaBasePackages.getClassesForPackage(name));
        }
        return out;
    }

    private void addToNameClass(String name) {
        addToNameClass(name, nameClass);
    }

    private void addToNameClass(String name, Map<String,Class<?>> nameClass) {
        try {
            if (name.endsWith(".*")) {
                for (Class<?> c : classesForPackage(name)) {
                    nameClass.put(c.getSimpleName(), c);
                }
            } else {
                Matcher matcher = PATTERN_CLASS_NAME.matcher(name);
                if (matcher.matches()) {
                    String classname = matcher.group(2).replaceAll("\\.", "\\$");
                    int idx = classname.lastIndexOf("$");
                    String simpleName = classname.substring(idx + 1);
                    try {
                        nameClass.put(simpleName, Class.forName(matcher.group(1) + "." + classname));
                    } catch (ClassNotFoundException ex) {
                        if (Log.isDebugEnabled()) {
                            ex.printStackTrace();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    @Override
    public Object execute(String statement) throws Exception {
        Object out = null;
        if (statement.startsWith("import ")) {
            shell.evaluate(statement);
            String[] p = statement.split("\\s+", 2);
            String classname = p[1].replaceAll(";", "");
            imports.put(classname, statement);
            addToNameClass(classname);
        } else if (statement.equals("import")) {
            out = new ArrayList<>(imports.keySet());
        } else if (functionDef(statement)) {
            // do nothing
        } else if (statement.equals("def")) {
            out = methods;
        } else if (statement.matches("def\\s+" + REGEX_VAR)) {
            String name = statement.split("\\s+")[1];
            if (methods.containsKey(name)) {
                out = "def " + name + methods.get(name);
            }
        } else {
            StringBuilder e = new StringBuilder();
            for (Map.Entry<String, String> entry : imports.entrySet()) {
                e.append(entry.getValue()).append("\n");
            }
            e.append(statement);
            if (classDef(statement)) {
                e.append("; null");
            }
            out = shell.evaluate(e.toString());
        }
        return out;
    }

    @Override
    public Object execute(Object closure, Object... args) {
        if (!(closure instanceof Closure)) {
            throw new IllegalArgumentException();
        }
        return ((Closure<?>)closure).call(args);
    }

    @Override
    public String getEngineName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public List<String> getExtensions() {
        return Collections.singletonList("groovy");
    }

    @SuppressWarnings("unchecked")
    private List<String> internalFind(String var) {
        List<String> out = new ArrayList<>();
        if(!var.contains(".") && var.contains("*")) {
            var = var.replaceAll("\\*", ".*");
        }
        for (String v :  (Set<String>)sharedData.getVariables().keySet()) {
            if (v.matches(var)) {
                out.add(v);
            }
        }
        return out;
    }

    private boolean functionDef(String statement) throws Exception{
        boolean out = false;
        Matcher m = PATTERN_FUNCTION_DEF.matcher(statement);
        if (m.matches()) {
            out = true;
            put(m.group(1), execute("{" + m.group(2) + "->" + m.group(3) + "}"));
            methods.put(m.group(1), "(" + m.group(2) + ")" + "{" + m.group(3) + "}");
        }
        return out;
    }

    private boolean classDef(String statement) {
        return PATTERN_CLASS_DEF.matcher(statement).matches();
    }

    private void refreshNameClass() {
        nameClass.clear();
        nameClass.putAll(defaultNameClass);
        for (String name : imports.keySet()) {
            addToNameClass(name);
        }
    }

    private void del(String var) {
        if (var == null) {
            return;
        }
        if (imports.containsKey(var)) {
            imports.remove(var);
            if (var.endsWith(".*")) {
                refreshNameClass();
            } else {
                nameClass.remove(var.substring(var.lastIndexOf('.') + 1));
            }
        } else if (sharedData.hasVariable(var)) {
            sharedData.getVariables().remove(var);
            methods.remove(var);
        } else if (!var.contains(".") && var.contains("*")) {
            for (String v : internalFind(var)){
                if (sharedData.hasVariable(v) && !v.equals("_") && !v.matches(REGEX_SYSTEM_VAR)) {
                    sharedData.getVariables().remove(v);
                    methods.remove(v);
                }
            }
        }
    }

    @Override
    public void del(String... vars) {
        if (vars == null) {
            return;
        }
        for (String s: vars) {
            del(s);
        }
    }

    @Override
    public String toJson(Object obj) {
        return Utils.toJson(obj);
    }

    @Override
    public String toString(Object obj) {
        return Utils.toString(obj);
    }

    @Override
    public Map<String,Object> toMap(Object obj) {
        return Utils.toMap(obj);
    }

    public void setObjectCloner(Cloner objectCloner) {
        this.objectCloner = objectCloner;
    }

    public Cloner getObjectCloner() {
        return objectCloner;
    }

    public CmdDesc scriptDescription(CmdLine line) {
        return new Inspector(this).scriptDescription(line);
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> groovyOptions() {
        return hasVariable(VAR_GROOVY_OPTIONS) ? (Map<String, Object>) get(VAR_GROOVY_OPTIONS)
                                                       : new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    protected <T>T groovyOption(String option, T defval) {
        T out = defval;
        try {
            out = (T) groovyOptions().getOrDefault(option, defval);
        } catch (Exception e) {
            // ignore
        }
        return out;
    }

    private Completer compileCompleter() {
        List<Completer> completers = new ArrayList<>();
        completers.add(new ArgumentCompleter(new StringsCompleter("class", "print", "println"), NullCompleter.INSTANCE));
        completers.add(new ArgumentCompleter(new StringsCompleter("def"), new StringsCompleter(methods::keySet), NullCompleter.INSTANCE));
        completers.add(new ArgumentCompleter(new StringsCompleter("import")
                                           , new PackageCompleter(CandidateType.PACKAGE), NullCompleter.INSTANCE));
        completers.add(new MethodCompleter(this));
        return new AggregateCompleter(completers);
    }

    private enum CandidateType {CONSTRUCTOR, STATIC_METHOD, PACKAGE, METHOD, OTHER}

    private static class Helpers {

        private static Set<String> loadedPackages() {
            Set<String> out = new HashSet<>();
            for (Package p : Package.getPackages()) {
                out.add(p.getName());
            }
            return out;
        }

        private static Set<String> names(String domain) {
            Set<String> out = new HashSet<>();
            for (String p : loadedPackages()) {
                if (p.startsWith(domain)) {
                    int idx = p.indexOf('.', domain.length());
                    if (idx < 0) {
                        idx = p.length();
                    }
                    out.add(p.substring(domain.length(), idx));
                }
            }
            return out;
        }

        public static Set<String> getMethods(Class<?> clazz) {
            return getMethods(clazz, false);
        }

        public static Set<String> getStaticMethods(Class<?> clazz) {
            return getMethods(clazz, true);
        }

        private static Set<String> getMethods(Class<?> clazz, boolean statc) {
            Set<String> out = new HashSet<>();
            try {
                for (Method method : clazz.getMethods()) {
                    if ((statc && Modifier.isStatic(method.getModifiers()))
                            || (!statc && !Modifier.isStatic(method.getModifiers()))) {
                        out.add(method.getName());
                    }
                }
            } catch (NoClassDefFoundError e) {
                // ignore
            }
            return out;
        }

        public static Set<String> getFields(Class<?> clazz) {
            return getFields(clazz, false);
        }

        public static Set<String> getStaticFields(Class<?> clazz) {
            return getFields(clazz, true);
        }

        private static Set<String> getFields(Class<?> clazz, boolean statc) {
            Set<String> out = new HashSet<>();
            for (Field field : clazz.getFields()) {
                if ((statc && Modifier.isStatic(field.getModifiers()))
                        || (!statc && !Modifier.isStatic(field.getModifiers()))) {
                    out.add(field.getName());
                }
            }
            return out;
        }

        public static Set<String> nextDomain(String domain, CandidateType type) {
            Set<String> out = new HashSet<>();
            if (domain.isEmpty()) {
                for (String p : loadedPackages()) {
                    out.add(p.split("\\.")[0]);
                }
            } else if ((domain.split("\\.")).length < 2) {
                out = names(domain);
            } else {
                try {
                    for (Class<?> c : classesForPackage(domain)) {
                        try {
                            if (!Modifier.isPublic(c.getModifiers()) || c.getCanonicalName() == null) {
                                continue;
                            }
                            if ((type == CandidateType.CONSTRUCTOR && (c.getConstructors().length == 0
                                    || Modifier.isAbstract(c.getModifiers())))
                                    || (type == CandidateType.STATIC_METHOD && getStaticMethods(c).size() == 0
                                         && getStaticFields(c).size() == 0)){
                                continue;
                            }
                            String name = c.getCanonicalName();
                            Log.debug(name);
                            if (name.startsWith(domain)) {
                                int idx = name.indexOf('.', domain.length());
                                if (idx < 0) {
                                    idx = name.length();
                                }
                                out.add(name.substring(domain.length(), idx));
                            }
                        } catch (NoClassDefFoundError e) {
                            if (Log.isDebugEnabled()) {
                                e.printStackTrace();
                            }
                        }
                    }
                } catch (ClassNotFoundException e) {
                    if (Log.isDebugEnabled()) {
                        e.printStackTrace();
                    }
                    out = names(domain);
                }
            }
            return out;
        }

        public static void doCandidates(List<Candidate> candidates, Collection<String> fields, String curBuf, String hint,
                CandidateType type) {
            if (fields == null) {
                return;
            }
            for (String s : fields) {
                if (s == null || !s.startsWith(hint)) {
                    continue;
                }
                String postFix = "";
                if (type == CandidateType.CONSTRUCTOR) {
                    if (s.matches("[a-z]+.*")) {
                        postFix = ".";
                    } else if (s.matches("[A-Z]+.*")) {
                        postFix = "(";
                    }
                } else if (type == CandidateType.STATIC_METHOD) {
                    postFix = ".";
                } else if (type == CandidateType.PACKAGE) {
                    if (s.matches("[a-z]+.*")) {
                        postFix = ".";
                    }
                } else if (type == CandidateType.METHOD) {
                    postFix = "(";
                }
                candidates.add(new Candidate(AttributedString.stripAnsi(curBuf + s + postFix), s, null, null, null,
                        null, false));
            }
        }

        public static int statementBegin(String buffer, String wordbuffer, Brackets brackets) {
            int out =  -1;
            int idx = buffer.lastIndexOf(wordbuffer);
            if (idx > -1) {
                out = statementBegin(brackets.lastDelim() - idx
                                   , brackets.lastOpenRound() - idx
                                   , brackets.lastComma() - idx
                                   , brackets.lastOpenCurly() - idx
                                   , brackets.lastCloseCurly() - idx
                                   , brackets.lastSemicolon() - idx);
            }
            return out;
        }

        public static int statementBegin(Brackets brackets) {
            return statementBegin(brackets.lastDelim()
                                , brackets.lastOpenRound()
                                , brackets.lastComma()
                                , brackets.lastOpenCurly(), brackets.lastCloseCurly(), brackets.lastSemicolon());
        }

        private static int statementBegin(int lastDelim, int openRound, int comma, int openCurly, int closeCurly, int semicolon) {
            int out = lastDelim;
            if (openRound > out) {
                out = openRound;
            }
            if (comma > out) {
                out = comma;
            }
            if (openCurly > out) {
                out = openCurly;
            }
            if (closeCurly > out) {
                out = closeCurly;
            }
            if (semicolon > out) {
                out = semicolon;
            }
            return Math.max(out, -1);
        }

        public static boolean constructorStatement(String fragment) {
            return fragment.matches("(.*\\s+new|.*\\(new|.*\\{new|.*=new|.*,new|new)");
        }

    }

    private static class PackageCompleter implements Completer {
        private final CandidateType type;

        public PackageCompleter(CandidateType type) {
            this.type = type;
        }

        @Override
        public void complete(LineReader reader, ParsedLine commandLine, List<Candidate> candidates) {
            assert commandLine != null;
            assert candidates != null;
            String buffer = commandLine.word().substring(0, commandLine.wordCursor());
            String param = buffer;
            String curBuf = "";
            int lastDelim = buffer.lastIndexOf('.');
            if (lastDelim > -1) {
                param = buffer.substring(lastDelim + 1);
                curBuf = buffer.substring(0, lastDelim + 1);
            }
            Helpers.doCandidates(candidates, Helpers.nextDomain(curBuf, type), curBuf, param, type);
        }

    }

    private static class MethodCompleter implements Completer {
        private static final List<String> KEY_WORDS = Arrays.asList("print", "println");
        private final GroovyEngine groovyEngine;
        Inspector inspector;

        public MethodCompleter(GroovyEngine engine){
            this.groovyEngine = engine;
        }

        @Override
        public void complete(LineReader reader, ParsedLine commandLine, List<Candidate> candidates) {
            assert commandLine != null;
            assert candidates != null;
            boolean restrictedCompletion = groovyEngine.groovyOption(RESTRICTED_COMPLETION, false);
            String wordbuffer = commandLine.word();
            String buffer = commandLine.line().substring(0, commandLine.cursor());
            Brackets brackets;
            try {
                brackets = new Brackets(buffer);
            } catch (Exception e) {
                return;
            }
            if (brackets.openQuote() || (commandLine.wordIndex() > 0 && !commandLine.words().get(0).matches("(new|\\w+=new)")
                    && brackets.numberOfRounds() == 0 && !brackets.openRound() && !brackets.openCurly())) {
                return;
            }
            inspector = new Inspector(groovyEngine);
            inspector.loadStatementVars(buffer);
            int eqsep = Helpers.statementBegin(brackets);
            if (brackets.numberOfRounds() > 0 && brackets.lastCloseRound() > eqsep) {
                int varsep = buffer.lastIndexOf('.');
                if (varsep > 0 && varsep > brackets.lastCloseRound() && !restrictedCompletion) {
                    Class<?> clazz = inspector.evaluateClass(buffer.substring(eqsep + 1, varsep));
                    int vs = wordbuffer.lastIndexOf('.');
                    String curBuf = wordbuffer.substring(0, vs + 1);
                    String hint = wordbuffer.substring(vs + 1);
                    doMethodCandidates(candidates, clazz, curBuf, hint);
                }
            } else if (!wordbuffer.contains("(") &&
                      ((commandLine.wordIndex() == 1 && commandLine.words().get(0).matches("(new|\\w+=new)"))
                    || (commandLine.wordIndex() > 1 && Helpers.constructorStatement(commandLine.words().get(commandLine.wordIndex() - 1))))
                    ) {
                if (wordbuffer.matches("[a-z]+.*")) {
                    int idx = wordbuffer.lastIndexOf('.');
                    if (idx > 0 && wordbuffer.substring(idx + 1).matches("[A-Z]+.*")) {
                        try {
                            Class.forName(wordbuffer);
                            Helpers.doCandidates(candidates, Collections.singletonList("("), wordbuffer, "(", CandidateType.OTHER);
                        } catch (Exception e) {
                            String param = wordbuffer.substring(0, idx + 1);
                            Helpers.doCandidates(candidates
                                               , Helpers.nextDomain(param, CandidateType.CONSTRUCTOR)
                                               , param, wordbuffer.substring(idx + 1), CandidateType.CONSTRUCTOR);
                        }
                    } else {
                        new PackageCompleter(CandidateType.CONSTRUCTOR).complete(reader, commandLine, candidates);
                    }
                } else {
                    Helpers.doCandidates(candidates, retrieveConstructors(), "", wordbuffer, CandidateType.CONSTRUCTOR);
                }
            } else {
                boolean addKeyWords = eqsep == brackets.lastSemicolon() || eqsep == brackets.lastOpenCurly();
                int varsep = wordbuffer.lastIndexOf('.');
                eqsep = Helpers.statementBegin(buffer, wordbuffer, brackets);
                String param = wordbuffer.substring(eqsep + 1);
                if (varsep < 0 || varsep < eqsep) {
                    String curBuf = wordbuffer.substring(0, eqsep + 1);
                    if (param.trim().length() == 0) {
                        Helpers.doCandidates(candidates, Collections.singletonList(""), curBuf, param, CandidateType.OTHER);
                    } else {
                        if (addKeyWords) {
                            Helpers.doCandidates(candidates, KEY_WORDS, curBuf, param, CandidateType.METHOD);
                        }
                        Helpers.doCandidates(candidates, inspector.variables(), curBuf, param, CandidateType.OTHER);
                        Helpers.doCandidates(candidates, retrieveClassesWithStaticMethods(), curBuf, param,
                                CandidateType.STATIC_METHOD);
                    }
                } else {
                    boolean firstMethod = param.indexOf('.') == param.lastIndexOf('.');
                    String var = param.substring(0, param.indexOf('.'));
                    String curBuf = wordbuffer.substring(0, varsep + 1);
                    String p = wordbuffer.substring(varsep + 1);
                    if (inspector.nameClass().containsKey(var)) {
                        if (firstMethod) {
                            doStaticMethodCandidates(candidates, inspector.nameClass().get(var), curBuf, p);
                        } else if (!restrictedCompletion) {
                            Class<?> clazz = inspector.evaluateClass(wordbuffer.substring(eqsep + 1, varsep));
                            doMethodCandidates(candidates, clazz, curBuf, p);
                        }
                    } else if (inspector.hasVariable(var)) {
                        if (firstMethod) {
                            doMethodCandidates(candidates, inspector.getVariable(var).getClass(), curBuf, p);
                        } else if (!restrictedCompletion) {
                            Class<?> clazz = inspector.evaluateClass(wordbuffer.substring(eqsep + 1, varsep));
                            doMethodCandidates(candidates, clazz, curBuf, p);
                        }
                    } else {
                        try {
                            param = wordbuffer.substring(eqsep + 1, varsep);
                            doStaticMethodCandidates(candidates, Class.forName(param), curBuf, p);
                        } catch (Exception e) {
                            param = wordbuffer.substring(eqsep + 1, varsep + 1);
                            Helpers.doCandidates(candidates
                                    , Helpers.nextDomain(param, CandidateType.STATIC_METHOD)
                                    , curBuf, p, CandidateType.STATIC_METHOD);
                        }
                    }
                }
            }
        }

        private void doMethodCandidates(List<Candidate> candidates, Class<?> clazz, String curBuf, String hint) {
            if (clazz == null) {
                return;
            }
            Helpers.doCandidates(candidates, Helpers.getMethods(clazz), curBuf, hint, CandidateType.METHOD);
            Helpers.doCandidates(candidates, Helpers.getFields(clazz), curBuf, hint, CandidateType.OTHER);
        }

        private void doStaticMethodCandidates(List<Candidate> candidates, Class<?> clazz, String curBuf, String hint) {
            if (clazz == null) {
                return;
            }
            Helpers.doCandidates(candidates, Helpers.getStaticMethods(clazz), curBuf, hint, CandidateType.METHOD);
            Helpers.doCandidates(candidates, Helpers.getStaticFields(clazz), curBuf, hint, CandidateType.OTHER);
        }

        private Set<String> retrieveConstructors() {
            Set<String> out = new HashSet<>();
            for (Map.Entry<String, Class<?>> entry : inspector.nameClass().entrySet()) {
                Class<?> c = entry.getValue();
                if (c.getConstructors().length == 0 || Modifier.isAbstract(c.getModifiers())) {
                    continue;
                }
                out.add(entry.getKey());
            }
            return out;
        }

        private Set<String> retrieveClassesWithStaticMethods() {
            Set<String> out = new HashSet<>();
            for (Map.Entry<String, Class<?>> entry : inspector.nameClass().entrySet()) {
                Class<?> c = entry.getValue();
                if (Helpers.getStaticMethods(c).size() == 0 && Helpers.getStaticFields(c).size() == 0) {
                    continue;
                }
                out.add(entry.getKey());
            }
            return out;
        }
    }

    private static class Inspector {
        static final Pattern PATTERN_FOR = Pattern.compile("^for\\s*\\((.*?);.*");
        static final Pattern PATTERN_FOR_EACH = Pattern.compile("^for\\s*\\((.*?):(.*?)\\).*");
        static final Pattern LAMBDA_PATTERN = Pattern.compile(".*\\([(]*(.*?)[)]*->.*");
        static final Pattern PATTERN_FUNCTION_BODY = Pattern.compile("^\\s*\\(([a-zA-Z0-9_ ,]*)\\)\\s*\\{(.*)?}(|\n)$"
                                                                   , Pattern.DOTALL);
        static final String DEFAULT_NANORC_SYNTAX = "classpath:/org/jline/groovy/java.nanorc";
        static final String DEFAULT_GROOVY_COLORS = "ti=1;34:me=31";

        private final GroovyShell shell;
        protected Binding sharedData = new Binding();
        private final Map<String,String> imports;
        private final Map<String,Class<?>> nameClass;
        private PrintStream nullstream;
        private boolean canonicalNames = false;
        private final boolean noSyntaxCheck;
        private final boolean restrictedCompletion;
        private String[] equationLines;
        private int cuttedSize;
        private final String nanorcSyntax;
        private final String groovyColors;

        public Inspector(GroovyEngine groovyEngine) {
            this.imports = groovyEngine.imports;
            this.nameClass = groovyEngine.nameClass;
            this.canonicalNames = groovyEngine.groovyOption(CANONICAL_NAMES, canonicalNames);
            this.nanorcSyntax = groovyEngine.groovyOption(NANORC_SYNTAX, DEFAULT_NANORC_SYNTAX);
            this.noSyntaxCheck = groovyEngine.groovyOption(NO_SYNTAX_CHECK, false);
            this.restrictedCompletion = groovyEngine.groovyOption(RESTRICTED_COMPLETION, false);
            String gc = groovyEngine.groovyOption(GROOVY_COLORS, null);
            groovyColors = gc != null && Styles.isAnsiStylePattern(gc) ? gc : DEFAULT_GROOVY_COLORS;
            groovyEngine.getObjectCloner().markCache();
            for (Map.Entry<String, Object> entry : groovyEngine.find().entrySet()) {
                Object obj = groovyEngine.getObjectCloner().clone(entry.getValue());
                sharedData.setVariable(entry.getKey(), obj);
            }
            groovyEngine.getObjectCloner().purgeCache();
            shell = new GroovyShell(sharedData);
            try {
                File file = OSUtils.IS_WINDOWS ? new File("NUL") : new File("/dev/null");
                OutputStream outputStream = new FileOutputStream(file);
                nullstream = new PrintStream(outputStream);
            } catch (Exception e) {
                // ignore
            }
            for (Map.Entry<String,String> entry : groovyEngine.methods.entrySet()) {
                Matcher m = PATTERN_FUNCTION_BODY.matcher(entry.getValue());
                if (m.matches()) {
                    sharedData.setVariable(entry.getKey(), execute("{" + m.group(1) + "->" + m.group(2) + "}"));
                }
            }
        }

        public Class<?> evaluateClass(String objectStatement) {
            Class<?> out = null;
            try {
                out = execute(objectStatement).getClass();
            } catch (Exception e) {
                // ignore
            }
            try {
                if (out == null || out == Class.class) {
                    if (!objectStatement.contains(".") ) {
                        out = (Class<?>)execute(objectStatement + ".class");
                    } else {
                        out = Class.forName(objectStatement);
                    }
                }
            } catch (Exception e) {
                // ignore
            }
            return out;
        }

        private Object execute(String statement) {
            PrintStream origOut = System.out;
            PrintStream origErr = System.err;
            if (nullstream != null) {
                System.setOut(nullstream);
                System.setErr(nullstream);
            }
            Object out;
            try {
                StringBuilder e = new StringBuilder();
                for (Map.Entry<String, String> entry : imports.entrySet()) {
                    e.append(entry.getValue()).append("\n");
                }
                e.append(statement);
                out = shell.evaluate(e.toString());
            } finally {
                System.setOut(origOut);
                System.setErr(origErr);
            }
            return out;
        }

        private String stripVarType(String statement) {
            if (statement.matches("\\w+\\s+\\w+.*")) {
                int idx = statement.indexOf(' ');
                return statement.substring(idx + 1);
            }
            return statement;
        }

        public void loadStatementVars(String line) {
            if (restrictedCompletion) {
                return;
            }
            for (String s : line.split("\\r?\\n")) {
                String statement = s.trim();
                try {
                    Matcher forEachMatcher = PATTERN_FOR_EACH.matcher(statement);
                    Matcher forMatcher = PATTERN_FOR.matcher(statement);
                    Matcher lambdaMatcher = LAMBDA_PATTERN.matcher(statement);
                    if (statement.matches("^(if|while)\\s*\\(.*") || statement.matches("(}\\s*|^)else(\\s*\\{|$)")
                            || statement.matches("(}\\s*|^)else\\s+if\\s*\\(.*") || statement.matches("^break[;]+")
                            || statement.matches("^case\\s+.*:") || statement.matches("^default\\s+:")
                            || statement.matches("([{}])") || statement.length() == 0) {
                        continue;
                    } else if (forEachMatcher.matches()) {
                        statement = stripVarType(forEachMatcher.group(1).trim());
                        String cc = forEachMatcher.group(2);
                        statement += "=" + cc + " instanceof Map ? " + cc + ".entrySet()[0] : " + cc + "[0]";
                    } else if (forMatcher.matches()) {
                        statement = stripVarType(forMatcher.group(1).trim());
                        if (!statement.contains("=")) {
                            statement += " = null";
                        }
                    } else if (lambdaMatcher.matches()) {
                        String[] vars = lambdaMatcher.group(1).split(",");
                        statement = "";
                        for (String v : vars) {
                            statement += v + " = null; ";
                        }
                    } else if (statement.contains("=")) {
                        statement = stripVarType(statement);
                    }
                    Brackets br = new Brackets(statement);
                    if (statement.contains("=") && !br.openRound() && !br.openCurly() && !br.openSquare()) {
                        String st = statement.substring(statement.indexOf('=') + 1).trim();
                        if (!st.isEmpty() && !st.equals("new")) {
                            execute(statement);
                        }
                    }
                } catch (Exception e) {
                    if (Log.isDebugEnabled()) {
                        e.printStackTrace();
                    }
                }
            }
        }

        public Map<String,Class<?>> nameClass() {
            return nameClass;
        }

        @SuppressWarnings("unchecked")
        public Set<String> variables() {
            return sharedData.getVariables().keySet();
        }

        public boolean hasVariable(String name) {
            return sharedData.hasVariable(name);
        }

        public Object getVariable(String name) {
            return sharedData.hasVariable(name) ? sharedData.getVariable(name) : null;
        }

        public CmdDesc scriptDescription(CmdLine line) {
            CmdDesc out = null;
            try {
                switch (line.getDescriptionType()) {
                case COMMAND:
                    break;
                case METHOD:
                    out = methodDescription(line);
                    break;
                case SYNTAX:
                    if (!noSyntaxCheck) {
                        out = checkSyntax(line);
                    }
                    break;
                }
            } catch (Throwable e) {
                if (Log.isDebugEnabled()) {
                    e.printStackTrace();
                }
            }
            return out;
        }

        private String trimName(String name) {
            String out = name;
            int idx = name.lastIndexOf('(');
            if (idx > 0) {
                out = name.substring(0, idx);
            }
            return out;
        }

        private CmdDesc methodDescription(CmdLine line) {
            CmdDesc out = new CmdDesc();
            List<String> args = line.getArgs();
            boolean constructor = false;
            Class<?> clazz = null;
            String methodName = null;
            String buffer = line.getHead();
            int eqsep = Helpers.statementBegin(new Brackets(buffer));
            int varsep = buffer.lastIndexOf('.');
            if (varsep > 0 && varsep > eqsep) {
                loadStatementVars(buffer);
                methodName = buffer.substring(varsep + 1);
                int ior = Brackets.indexOfOpeningRound(buffer.substring(0, varsep));
                if (ior > 0 && ior < eqsep) {
                    eqsep = ior;
                }
                String st = buffer.substring(eqsep + 1, varsep);
                if (st.matches("[A-Z]+\\w+\\s*\\(.*")) {
                    st = "new " + st;
                }
                if (!restrictedCompletion || new Brackets(st).numberOfRounds() == 0) {
                    clazz = evaluateClass(st);
                }
            } else if (args.size() > 1 && Helpers.constructorStatement(args.get(args.size() - 2))
                    && args.get(args.size() - 1).matches("[A-Z]+\\w+\\s*\\(.*")
                    && new Brackets(args.get(args.size() - 1)).openRound()) {
                constructor = true;
                clazz = evaluateClass(trimName(args.get(args.size() - 1)));
            }
            List<AttributedString> mainDesc = new ArrayList<>();
            if (clazz != null) {
                SyntaxHighlighter java = SyntaxHighlighter.build(nanorcSyntax);
                mainDesc.add(java.highlight(clazz.toString()));
                if (constructor) {
                    for (Constructor<?> m : clazz.getConstructors()) {
                        StringBuilder sb = new StringBuilder();
                        String name = m.getName();
                        if (!canonicalNames) {
                            int idx = name.lastIndexOf('.');
                            name = name.substring(idx + 1);
                        }
                        sb.append(name);
                        sb.append("(");
                        boolean first = true;
                        for(Class<?> p: m.getParameterTypes()) {
                            if (!first) {
                                sb.append(", ");
                            }
                            sb.append(canonicalNames ? p.getTypeName() : p.getSimpleName());
                            first = false;
                        }
                        sb.append(")");
                        first = true;
                        for (Class<?> e: m.getExceptionTypes()) {
                            if (first) {
                                sb.append(" throws ");
                            } else {
                                sb.append(", ");
                            }
                            sb.append(canonicalNames ? e.getCanonicalName() : e.getSimpleName());
                            first = false;
                        }
                        mainDesc.add(java.highlight(trimMethodDescription(sb)));
                    }
                } else {
                    List<String> addedMethods = new ArrayList<>();
                    do {
                        for (Method m : clazz.getMethods()) {
                            if (!m.getName().equals(methodName)) {
                                continue;
                            }
                            StringBuilder sb = new StringBuilder();
                            if (Modifier.isFinal(m.getModifiers())) {
                                sb.append("final ");
                            }
                            if (Modifier.isStatic(m.getModifiers())) {
                                sb.append("static ");
                            }
                            sb.append(canonicalNames ?  m.getReturnType().getCanonicalName() : m.getReturnType().getSimpleName());
                            sb.append(" ");
                            sb.append(methodName);
                            sb.append("(");
                            boolean first = true;
                            for (Class<?> p : m.getParameterTypes()) {
                                if (!first) {
                                    sb.append(", ");
                                }
                                sb.append(canonicalNames ? p.getTypeName() : p.getSimpleName());
                                first = false;
                            }
                            sb.append(")");
                            first = true;
                            for (Class<?> e : m.getExceptionTypes()) {
                                if (first) {
                                    sb.append(" throws ");
                                } else {
                                    sb.append(", ");
                                }
                                sb.append(canonicalNames ? e.getCanonicalName() : e.getSimpleName());
                                first = false;
                            }
                            if (!addedMethods.contains(sb.toString())) {
                                addedMethods.add(sb.toString());
                                mainDesc.add(java.highlight(trimMethodDescription(sb)));
                            }
                        }
                        clazz = clazz.getSuperclass();
                    } while (clazz != null);
                }
                out.setMainDesc(mainDesc);
            }
            return out;
        }

        private String trimMethodDescription(StringBuilder sb) {
            String out = sb.toString();
            if (canonicalNames) {
                out = out.replaceAll("java\\.lang\\.", "");
            }
            return out;
        }

        private CmdDesc checkSyntax(CmdLine line) {
            CmdDesc out = new CmdDesc();
            int openingRound = Brackets.indexOfOpeningRound(line.getHead());
            if (openingRound == -1) {
                return out;
            }
            loadStatementVars(line.getHead());
            Brackets brackets = new Brackets(line.getHead().substring(0, openingRound));
            int eqsep = Helpers.statementBegin(brackets);
            int end = line.getHead().length();
            if (eqsep > 0 && Helpers.constructorStatement(line.getHead().substring(0, eqsep))) {
                eqsep = line.getHead().substring(0, eqsep).lastIndexOf("new") - 1;
            } else if (line.getHead().substring(eqsep + 1).matches("\\s*for\\s*\\(.*")
                    || line.getHead().substring(eqsep + 1).matches("\\s*while\\s*\\(.*")
                    || line.getHead().substring(eqsep + 1).matches("\\s*else\\s+if\\s*\\(.*")
                    || line.getHead().substring(eqsep + 1).matches("\\s*if\\s*\\(.*")) {
                eqsep = openingRound;
                end = end - 1;
            } else if (line.getHead().substring(eqsep + 1).matches("\\s*switch\\s*\\(.*")
                    || line.getHead().substring(eqsep + 1).matches("\\s*catch\\s*\\(.*")) {
                return out;
            }
            List<AttributedString> mainDesc = new ArrayList<>();
            String objEquation = line.getHead().substring(eqsep + 1, end).trim();
            equationLines = objEquation.split("\\r?\\n");
            cuttedSize = eqsep + 1;
            if (objEquation.matches("\\(\\s*\\w+\\s*[,\\s*\\w+]*\\)")
                    || objEquation.matches("\\(\\s*\\)")) {
                // do nothing
            } else {
                try {
                    execute(objEquation);
                } catch (groovy.lang.MissingPropertyException e) {
                    mainDesc.addAll(doExceptionMessage(e));
                    out.setErrorPattern(Pattern.compile("\\b" + e.getProperty() + "\\b"));
                } catch (java.util.regex.PatternSyntaxException e) {
                    mainDesc.addAll(doExceptionMessage(e));
                    int idx = line.getHead().lastIndexOf(e.getPattern());
                    if (idx >= 0) {
                        out.setErrorIndex(idx + e.getIndex());
                    }
                } catch (org.codehaus.groovy.control.MultipleCompilationErrorsException e) {
                    if (e.getErrorCollector().getErrors() != null) {
                        for (Object o : e.getErrorCollector().getErrors()) {
                            if (o instanceof SyntaxErrorMessage) {
                                SyntaxErrorMessage sem = (SyntaxErrorMessage) o;
                                out.setErrorIndex(errorIndex(e.getMessage(), sem.getCause()));
                            }
                        }
                    }
                    if (e.getErrorCollector().getWarnings() != null) {
                        for (Object o : e.getErrorCollector().getWarnings()) {
                            if (o instanceof SyntaxErrorMessage) {
                                SyntaxErrorMessage sem = (SyntaxErrorMessage) o;
                                out.setErrorIndex(errorIndex(e.getMessage(), sem.getCause()));
                            }
                        }
                    }
                    mainDesc.addAll(doExceptionMessage(e));
                } catch (NullPointerException e) {
                    // do nothing
                } catch (Exception e) {
                    mainDesc.addAll(doExceptionMessage(e));
                }
            }
            out.setMainDesc(mainDesc);
            return out;
        }

        private List<AttributedString> doExceptionMessage(Exception exception) {
            List<AttributedString> out = new ArrayList<>();
            SyntaxHighlighter java = SyntaxHighlighter.build(nanorcSyntax);
            StyleResolver resolver = style(groovyColors);
            Pattern header = Pattern.compile("^[a-zA-Z() ]{3,}:(\\s+|$)");
            out.add(java.highlight(exception.getClass().getCanonicalName()));
            if (exception.getMessage() != null) {
                for (String s: exception.getMessage().split("\\r?\\n")) {
                    if (s.trim().length() == 0) {
                        // do nothing
                    } else if (s.length() > 80) {
                        boolean doHeader = true;
                        int start = 0;
                        for (int i = 80; i < s.length(); i++) {
                            if ((s.charAt(i) == ' ' && i - start > 80 ) || i - start > 100) {
                                AttributedString as = new AttributedString(s.substring(start, i), resolver.resolve(".me"));
                                if (doHeader) {
                                    as = as.styleMatches(header, resolver.resolve(".ti"));
                                    doHeader = false;
                                }
                                out.add(as);
                                start = i;
                                if (s.length() - start < 80) {
                                    out.add(new AttributedString(s.substring(start), resolver.resolve(".me")));
                                    break;
                                }
                            }
                        }
                        if (doHeader) {
                            AttributedString as = new AttributedString(s, resolver.resolve(".me"));
                            as = as.styleMatches(header, resolver.resolve(".ti"));
                            out.add(as);
                        }
                    } else {
                        AttributedString as = new AttributedString(s, resolver.resolve(".me"));
                        as = as.styleMatches(header, resolver.resolve(".ti"));
                        out.add(as);
                    }
                }
            }
            return out;
        }

        private int errorIndex(String message, SyntaxException se) {
            int out;
            String line = null;
            String[] mlines = message.split("\n");
            for (int i = 0; i < mlines.length; i++) {
                if (mlines[i].matches(".*Script[0-9]+\\.groovy: .*")) {
                    line = mlines[i + 1].trim();
                    break;
                }
            }
            int tot = 0;
            if (line != null) {
                for (String l: equationLines) {
                    if (l.contains(line)) {
                        break;
                    }
                    tot += l.length() + 1;
                }
            }
            out = cuttedSize + tot + se.getStartColumn() - 1;
            return out;
        }

        private static StyleResolver style(String style) {
            Map<String, String> colors = Arrays.stream(style.split(":"))
                    .collect(Collectors.toMap(s -> s.substring(0, s.indexOf('=')),
                            s -> s.substring(s.indexOf('=') + 1)));
            return new StyleResolver(colors::get);
        }

    }

    private static class ObjectCloner implements Cloner {
        Map<String,Object> cache = new HashMap<>();
        Set<String> marked = new HashSet<>();

        public ObjectCloner() {

        }

        /**
         * Shallow copy of the object using java Cloneable clone() method.
         */
        public Object clone(Object obj) {
            if (obj == null || obj instanceof String || obj instanceof Integer || obj instanceof Exception || obj instanceof Closure) {
                return obj;
            }
            Object out;
            String key = cacheKey(obj);
            try {
                if (cache.containsKey(key)) {
                    marked.remove(key);
                    out = cache.get(key);
                } else {
                    Class<?> clazz = obj.getClass();
                    Method clone = clazz.getDeclaredMethod("clone");
                    out = clone.invoke(obj);
                    cache.put(key, out);
                }
            } catch (Exception e) {
                out = obj;
                cache.put(key, out);
            }
            return out;
        }

        public void markCache() {
            marked = new HashSet<>(cache.keySet());
        }

        public void purgeCache() {
            for (String k : marked) {
                cache.remove(k);
            }
        }

        private String cacheKey(Object obj) {
            return obj.getClass().getCanonicalName() + ":" + obj.hashCode();
        }

    }

    private static class Brackets {
        static final List<Character> DELIMS = Arrays.asList('+', '-', '*', '=', '/');
        static char[] quote = {'"', '\''};
        Deque<Integer> roundOpen = new ArrayDeque<>();
        Deque<Integer> curlyOpen = new ArrayDeque<>();
        Map<Integer,Integer> lastComma = new HashMap<>();
        int lastRoundClose = -1;
        int lastCurlyClose = -1;
        int lastSemicolon = -1;
        int lastBlanck = -1;
        int lastDelim = -1;
        int quoteId = -1;
        int round = 0;
        int curly = 0;
        int square = 0;
        int rounds = 0;
        int curlies = 0;

        public Brackets(String line) {
            int pos = -1;
            char prevChar = ' ';
            for (char ch : line.toCharArray()) {
                pos++;
                if (quoteId < 0) {
                    for (int i = 0; i < quote.length; i++) {
                        if (ch == quote[i]) {
                            quoteId = i;
                            break;
                        }
                    }
                } else {
                    if (ch == quote[quoteId]) {
                        quoteId = -1;
                    }
                    continue;
                }
                if (quoteId >= 0) {
                    continue;
                }
                if (ch == '(') {
                    round++;
                    roundOpen.add(pos);
                } else if (ch == ')') {
                    rounds++;
                    round--;
                    lastComma.remove(roundOpen.getLast());
                    roundOpen.removeLast();
                    lastRoundClose = pos;
                } else if (ch == '{') {
                    curly++;
                    curlyOpen.add(pos);
                } else if (ch == '}') {
                    curlies++;
                    curly--;
                    curlyOpen.removeLast();
                    lastCurlyClose = pos;
                } else if (ch == '[') {
                    square++;
                } else if (ch == ']') {
                    square--;
                } else if (ch == ',' && !roundOpen.isEmpty()) {
                    lastComma.put(roundOpen.getLast(), pos);
                } else if (ch == ';' || ch == '\n' || (ch == '>' && prevChar == '-')) {
                    lastSemicolon = pos;
                } else if (ch == ' ' && round == 0 && String.valueOf(prevChar).matches("\\w")) {
                    lastBlanck = pos;
                } else if (DELIMS.contains(ch)) {
                    lastDelim = pos;
                }
                prevChar = ch;
                if (round < 0 || curly < 0 || square < 0) {
                    throw new IllegalArgumentException();
                }
            }
        }

        public static int indexOfOpeningRound(String line) {
            int out = -1;
            if (!line.endsWith(")")) {
                return out;
            }
            int quoteId = -1;
            int round = 0;
            int curly = 0;
            char[] chars = line.toCharArray();
            for (int i = line.length() - 1; i >= 0; i--) {
                char ch = chars[i];
                if (quoteId < 0) {
                    for (int j = 0; j < quote.length; j++) {
                        if (ch == quote[j]) {
                            quoteId = j;
                            break;
                        }
                    }
                } else {
                    if (ch == quote[quoteId]) {
                        quoteId = -1;
                    }
                    continue;
                }
                if (quoteId >= 0) {
                    continue;
                }
                if (ch == '(') {
                    round++;
                } else if (ch == ')') {
                    round--;
                } else if (ch == '{') {
                    curly++;
                } else if (ch == '}') {
                    curly--;
                }
                if (curly == 0 && round == 0) {
                    out = i;
                    break;
                }
            }
            return out;
        }

        public boolean openRound() {
            return round > 0;
        }

        public boolean openCurly() {
            return curly > 0;
        }

        public boolean openSquare() {
            return square > 0;
        }

        public int numberOfRounds() {
            return rounds;
        }

        public int lastOpenRound() {
            return !roundOpen.isEmpty() ? roundOpen.getLast() : -1;
        }

        public int lastCloseRound() {
            return lastRoundClose;
        }

        public int lastOpenCurly() {
            return !curlyOpen.isEmpty() ? curlyOpen.getLast() : -1;
        }

        public int lastCloseCurly() {
            return lastCurlyClose;
        }

        public int lastComma() {
            int last = lastOpenRound();
            return lastComma.getOrDefault(last, -1);
        }

        public int lastSemicolon() {
            return lastSemicolon;
        }

        public int lastDelim() {
            return lastDelim;
        }

        public boolean openQuote() {
            return quoteId != -1;
        }

        public String toString() {
            return "rounds: " + rounds + "\n"
                 + "curlies: " + curlies + "\n"
                 + "lastOpenRound: " + lastOpenRound() + "\n"
                 + "lastCloseRound: " + lastRoundClose + "\n"
                 + "lastComma: " + lastComma() + "\n";
        }
    }

}
