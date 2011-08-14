package com.grossbart.jslim;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.ErrorManager;
import com.google.javascript.jscomp.JSSourceFile;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

public class JSlim {

    private List<Node> m_vars = new ArrayList<Node>();
    private List<Call> m_calls = new ArrayList<Call>();
    private List<Call> m_examinedCalls = new ArrayList<Call>();
    
    private List<Node> m_funcs = new ArrayList<Node>();
    private List<Node> m_libFuncs = new ArrayList<Node>();
    private List<Node> m_allFuncs = new ArrayList<Node>();
    private List<Node> m_keepers = new ArrayList<Node>();
    
    private List<JSFile> m_files = new ArrayList<JSFile>();
    
    private ErrorManager m_errMgr;
    private int m_funcCount;
    
    public String addLib(String code)
    {
        return slim(code, true);
    }
    
    public void addSourceFile(JSFile file)
    {
        m_files.add(file);
    }
    
    public String prune()
    {
        StringBuffer sb = new StringBuffer();
        
        for (JSFile file : m_files) {
            if (file.isLib()) {
                sb.append(file.getContent() + "\n");
            } else {
                slim(file.getContent(), false);
            }
        }
        
        return addLib(sb.toString());
    }
    
    /**
     * @param code JavaScript source code to compile.
     * @return The compiled version of the code.
     */
    private String slim(String code, boolean isLib) {
        Compiler compiler = new Compiler();

        CompilerOptions options = new CompilerOptions();
        // Advanced mode is used here, but additional options could be set, too.
        CompilationLevel.SIMPLE_OPTIMIZATIONS.setOptionsForCompilationLevel(options);

        // To get the complete set of externs, the logic in
        // CompilerRunner.getDefaultExterns() should be used here.
        JSSourceFile extern[] = {JSSourceFile.fromCode("externs.js", "")};

        // The dummy input name "input.js" is used here so that any warnings or
        // errors will cite line numbers in terms of input.js.
        JSSourceFile input[] = {JSSourceFile.fromCode("input.js", code)};

        compiler.init(extern, input, options);

        compiler.parse();
        m_errMgr = compiler.getErrorManager();
        
        if (m_errMgr.getErrorCount() > 0) {
            /*
             Then there were errors parsing the file and we can't
             prune anything. 
             */
            return "";
        }

        Node node = compiler.getRoot();
        System.out.println("node.toString(): \n" + node.toStringTree());
        
        //System.out.println("node before change: " + compiler.toSource());
        
        System.out.println("starting process...");
        Node n = process(node, isLib);
        
        addExterns();
        
        System.out.println("Done processing...");
        System.out.println("m_calls: " + m_calls);
        
        m_funcCount = m_libFuncs.size();
        
        if (isLib) {
            System.out.println("Starting pruneTree phase 1.");
            pruneTree();
            
            System.out.println("Starting pruneTree phase 2.");
            pruneTree();
        }
        
        System.out.println("Removed " + (m_funcCount - m_keepers.size()) + " out of " + m_funcCount + " named functions.");
        
        //System.out.println("n: " + n.toStringTree());
        
        //System.out.println("n.toString(): \n" + n.toStringTree());
        
        // The compiler is responsible for generating the compiled code; it is not
        // accessible via the Result.
        return compiler.toSource();
    }
    
    private Node process(Node node, boolean isLib) {
        Iterator<Node> nodes = node.children().iterator();
        
        while (nodes.hasNext()) {
            Node n = nodes.next();

            if (n.getType() == Token.VAR && n.getFirstChild().getType() == Token.NAME) {
                m_vars.add(n);
            } else if (n.getType() == Token.CALL || n.getType() == Token.NEW) {
                addCalls(n);
            } else if (n.getType() == Token.ASSIGN ||
                       n.getType() == Token.ASSIGN_BITOR  ||
                       n.getType() == Token.ASSIGN_BITXOR ||
                       n.getType() == Token.ASSIGN_BITAND ||
                       n.getType() == Token.ASSIGN_LSH ||
                       n.getType() == Token.ASSIGN_RSH ||
                       n.getType() == Token.ASSIGN_URSH ||
                       n.getType() == Token.ASSIGN_ADD ||
                       n.getType() == Token.ASSIGN_SUB ||
                       n.getType() == Token.ASSIGN_MUL ||
                       n.getType() == Token.ASSIGN_DIV ||
                       n.getType() == Token.ASSIGN_MOD) {
                /*
                 This is an assignment operator.  
                 */
                addAssign(n);
            } else if (isLib && n.getType() == Token.FUNCTION &&
                       isInterestingFunction(n)) {
                if (isLib) {
                    m_libFuncs.add(n);
                } else {
                    m_funcs.add(n);
                }
            }
            
            process(n, isLib);
        }
        
        return node;
    }
    
    /**
     * This method determines if the specified function is interesting.  In our case interesting
     * means it is a potentatial candidate for removal.  There are many reasons the function
     * might not be a good cadidate.  For example, anonymous functions are never removed since
     * they are almost always used and there is no way to track if they are used or not.
     * 
     * @param n      the function to check
     * 
     * @return true if the function is interesting and false otherwise
     */
    private boolean isInterestingFunction(Node n)
    {
        if (n.getType() != Token.FUNCTION) {
            /*
             If this node isn't a function then it definitely isn't an
             interesting function
             */
            return false;
        }
        
        if (n.getParent().getType() == Token.ASSIGN &&
            n.getParent().getParent().getType() == Token.RETURN) {
            /*
             Then this is a function getting returned from another
             function and that makes it really difficult to determine
             if the function is being called because it is never
             called directly by named
             */
            return false;
        }
        
        /*
         We need to check to make sure this is a named
         function.  If it is an anonymous function then
         it can't be called directly outside of scope and
         it is probably being called locally so we can't remove it.
         */
        if (n.getParent().getType() == Token.STRING ||
            (n.getFirstChild().getType() == Token.NAME &&
             n.getFirstChild().getString() != null &&
             n.getFirstChild().getString().length() > 0) ||
            n.getParent().getType() == Token.ASSIGN) {
            
            /*
             If this function is part of an object list that means
             it is named and getting passed to a function and most
             likely getting called without a direct function reference
             so we have to leave it there.
             */
            if (!(n.getParent().getParent().getType() == Token.OBJECTLIT &&
                  n.getParent().getParent().getParent().getType() == Token.CALL)) {
                
                /*
                 If the function doesn't have a name we can identify then it is anonymous and
                 we can't tell if anyone is calling it.
                 */
                if (getFunctionName(n) != null) {
                    //if ("_path2string".equals(getFunctionName(n))) {
                    //    return false;
                    //}
                    /*
                     If this function has a direct parent which is another function instead of
                     a block or a property then it is probably being created to get returned from
                     the functions and therefore only has a name in he scope of that function.
                     It might be possible to change the mapping to the parent function, but we
                     can understand that right now and there might me multiple functions within
                     this one specific function.
                     */
                    if (!(n.getParent().getType() == Token.BLOCK && n.getParent().getParent().getType() == Token.FUNCTION)) {
                        return true;
                    }
                }
            }
        }
        
        return false;
        
    }
    
    private void addAssign(Node assign)
    {
        addAssign(assign, m_calls);
    }
    
    private void addAssign(Node assign, List<Call> calls)
    {
        if (assign.getChildCount() < 2) {
            /*
             This means it was a simple assignment to a constant value
             like var a = "foo" or var b = 5
             */
            return;
        }
        
        if (assign.getLastChild().getType() == Token.NAME) {
            /*
             This means it was assignment to a variable and since all
             variable names might be functions we need to add them to
             our calls list.
             */
            
            addCall(assign.getLastChild().getString(), calls);
        } else if (assign.getFirstChild().getType() == Token.GETELEM &&
                   assign.getLastChild().getLastChild() != null &&
                   assign.getLastChild().getLastChild().getType() == Token.STRING) {
            /*
             This means it is an assignment to an array element like:
                 res[toString] = R._path2string;
             */
            addCall(assign.getLastChild().getLastChild().getString(), calls);
        }
    }
    
    private void addCall(String call, List<Call> calls)
    {
        Call c = getCall(call, calls);
        
        if (c == null) {
            c = new Call(call);
            calls.add(c);
        } else {
            /*
             If the call is already there then we just increment
             the count
             */
            c.incCount();
        }
    }
    
    private static Call getCall(String name, List<Call> calls)
    {
        for (Call call : calls) {
            if (call.getName().equals(name)) {
                return call;
            }
        }
        
        return null;
    }
    
    private void addCallsProp(Node getProp, List<Call> calls)
    {
        if (getProp.getLastChild().getType() == Token.STRING) {
            addCall(getProp.getLastChild().getString(), calls);
        }
        
        if (getProp.getFirstChild().getType() == Token.CALL) {
            /*
             Add the function name
             */
            addCall(getProp.getLastChild().getString(), calls);
            
            if (getProp.getFirstChild().getFirstChild().getType() == Token.NAME) {
                addCall(getProp.getFirstChild().getFirstChild().getString(), calls);
            }
        } else if (getProp.getFirstChild().getType() == Token.GETPROP) {
            addCallsProp(getProp.getFirstChild(), calls);
        }
        
        if (getProp.getNext() != null && getProp.getNext().getType() == Token.GETPROP) {
            addCallsProp(getProp.getNext(), calls);
        }
    }
    
    private void addCalls(Node call)
    {
        addCalls(call, m_calls);
    }
    
    private void addCalls(Node call, List<Call> calls)
    {
        //assert call.getType() == Token.CALL || call.getType() == Token.NEW;
        
        if (call.getType() == Token.GETPROP) {
            addCallsProp(call, calls);
        } else if (call.getFirstChild().getType() == Token.GETPROP) {
            addCallsProp(call.getFirstChild(), calls);
        } else if (call.getFirstChild().getType() == Token.NAME) {
            Node name = call.getFirstChild();
            addCall(name.getString(), calls);
            System.out.println("name.getString(): " + name.getString());
        }
    }
    
    private void pruneTree() {
        m_allFuncs.addAll(m_funcs);
        m_allFuncs.addAll(m_libFuncs);
        
        for (Call call : m_calls) {
            findKeepers(call);
        }
        
        System.out.println("m_keepers: " + m_keepers);
        
        for (int i = m_libFuncs.size() - 1; i > -1; i--) {
            Node func = m_libFuncs.get(i);
            
            if (getFunctionName(func).equals("isString")) {
                System.out.println("m_keepers.contains(func): " + m_keepers.contains(func));
            }
            
            if (!m_keepers.contains(func)) {
                removeCalledKeepers(func);
                removeFunction(func);
                m_libFuncs.remove(func);
            }
        }
        
        System.out.println("Keeping the following functions:");
        for (Node f : m_libFuncs) {
            System.out.println("func: " + getFunctionName(f));
        }
    }
    
    /**
     * If we're removing a function then all of the calls within that function to other
     * functions (and so on recursively) can be removed from our call count.  This method
     * finds all of them and does just that.
     * 
     * @param func
     */
    private void removeCalledKeepers(Node func)
    {
        Call calls[] = findCalls(func);
        for (Call call : calls) {
            Call orig = getCall(call.getName(), m_calls);
            if (call.getName().equals("isString")) {
                System.out.println("issString orig: " + orig);
                System.out.println("issString call: " + call);
            }
            
            orig.decCount(call.getCount());
            
            if (orig.getCount() < 1) {
                System.out.println("removing called keeper: " + orig);
                Node f = findFunction(orig.getName());
                if (f != null) {
                    m_keepers.remove(f);
                }
            }
        }
    }
    
    private Node findFunction(String name)
    {
        for (Node f : m_libFuncs) {
            if (getFunctionName(f).equals(name)) {
                return f;
            }
        }
        
        return null;
    }
    
    private void removeFunction(String func)
    {
        for (Node f : m_libFuncs) {
            if (getFunctionName(f).equals(func)) {
                removeFunction(f);
            }
        }
    }
    
    private void removeFunction(Node n)
    {
        System.out.println("removeFunction(" + getFunctionName(n) + ")");
        
        if (n.getParent() == null || n.getParent().getParent() == null) {
            /*
             This means the function has already been removed
             */
            return;
        }
        
        if (n.getParent().getType() == Token.STRING) {
            /*
             This is a closure style function like this:
                 myFunc: function()
             */
            //System.out.println("Removing function: " + n.getParent().getString());
            n.getParent().detachFromParent();
        } else if (n.getParent().getType() == Token.ASSIGN) {
            /*
             This is a property assignment function like:
                myObj.func1 = function()
             */
            Node expr = findExprOrVar(n);
            if (expr != null && expr.getType() == Token.EXPR_RESULT && expr.getParent() != null) {
                System.out.println("expr: " + expr);
                expr.detachFromParent();
            }
        } else {
            /*
             This is a standard type of function like this:
                function myFunc()
             */
            //System.out.println("n.toStringTree(): " + n.toStringTree());
            //System.out.println("Removing function: " + n.getFirstChild().getString());
            n.detachFromParent();
        }
    }
    
    private Node findExprOrVar(Node n)
    {
        if (n == null) {
            return null;
        } else if (n.getType() == Token.EXPR_RESULT ||
                   n.getType() == Token.VAR) {
            return n;
        } else {
            return findExprOrVar(n.getParent());
        }
    }
    
    /**
     * This method recurses all the functions and finds all the calls to actual functions 
     * and adds them to the list of keepers.
     * 
     * @param call
     */
    private void findKeepers(Call call)
    {
        if (getCall(call.getName(), m_examinedCalls) != null) {
            /*
             Then we've already examined this call and we can skip it.
             */
            return;
        }
        
        //call.incCount();
        
        System.out.println("findKeepers(" + call + ")");
        
        m_examinedCalls.add(call);
        
        
        Node funcs[] = findMatchingFunctions(call.getName());
            
        for (Node func : funcs) {
            m_keepers.add(func);
            System.out.println("func: " + getFunctionName(func));
            
            for (Call c : findCalls(func)) {
                System.out.println("c: " + c);
                findKeepers(c);
            }
        }
    }
    
    /**
     * Find all of the calls in the given function.
     * 
     * @param func   the function to look in
     * 
     * @return the list of calls
     */
    private Call[] findCalls(Node func)
    {
        ArrayList<Call> calls = new ArrayList<Call>();
        findCalls(func, calls);
        return calls.toArray(new Call[calls.size()]);
    }
    
    private void findCalls(Node node, List<Call> calls)
    {
        Iterator<Node> nodes = node.children().iterator();
        
        while (nodes.hasNext()) {
            Node n = nodes.next();
            if (n.getType() == Token.CALL || n.getType() == Token.NEW) {
                addCalls(n, calls);
            } else if (n.getType() == Token.ASSIGN ||
                       n.getType() == Token.ASSIGN_BITOR  ||
                       n.getType() == Token.ASSIGN_BITXOR ||
                       n.getType() == Token.ASSIGN_BITAND ||
                       n.getType() == Token.ASSIGN_LSH ||
                       n.getType() == Token.ASSIGN_RSH ||
                       n.getType() == Token.ASSIGN_URSH ||
                       n.getType() == Token.ASSIGN_ADD ||
                       n.getType() == Token.ASSIGN_SUB ||
                       n.getType() == Token.ASSIGN_MUL ||
                       n.getType() == Token.ASSIGN_DIV ||
                       n.getType() == Token.ASSIGN_MOD) {
                /*
                 This is an assignment operator.  
                 */
                addAssign(n, calls);
            } 
            
            findCalls(n, calls);
        }
    }
    
    private Node[] findFunctions(Node parent)
    {
        ArrayList<Node> funcs = new ArrayList<Node>();
        findFunctions(parent, funcs);
        
        return funcs.toArray(new Node[funcs.size()]);
        
    }
    
    private Node findFunctions(Node node, List<Node> funcs)
    {
        Iterator<Node> nodes = node.children().iterator();
        
        while (nodes.hasNext()) {
            Node n = nodes.next();
            if (n.getType() == Token.FUNCTION && 
                isInterestingFunction(n)) {
                funcs.add(n);
            }
            
            findFunctions(n, funcs);
        }
        
        return node;
    }
    
    private List<String> getFunctionNames(Node n)
    {
        /*
         EXPR_RESULT 561 [source_file: input.js]
            ASSIGN 561 [source_file: input.js]
                GETPROP 561 [source_file: input.js]
                    NAME _ 561 [source_file: input.js]
                    STRING functions 561 [source_file: input.js]
                ASSIGN 561 [source_file: input.js]
                    GETPROP 561 [source_file: input.js]
                        NAME _ 561 [source_file: input.js]
                        STRING methods 561 [source_file: input.js]
                    FUNCTION  561 [source_file: input.js]
         */
        ArrayList<String> names = new ArrayList<String>();
        if (n.getType() == Token.FUNCTION) {
            names.add(getFunctionName(n));
        }
        
        if (n.getType() == Token.ASSIGN) {
            names.add(n.getFirstChild().getLastChild().getString());
        }
        
        if (n.getParent().getType() == Token.ASSIGN) {
            names.addAll(getFunctionNames(n.getParent()));
        }
        
        return names;
    }
    
    private String getFunctionName(Node n)
    {
        try {
            if (n.getParent().getType() == Token.ASSIGN) {
                if (n.getParent().getFirstChild().getChildCount() == 0) {
                    /*
                     This is a variable assignment of a function to a
                     variable in the globabl scope.  These functions are
                     just too big in scope so we ignore them.  Example:
                        myVar = function()
                     */
                    return null;
                } else if (n.getParent().getFirstChild().getType() == Token.GETELEM) {
                    /*
                     This is a property assignment function with an array
                     index like this: 
                        jQuery.fn[ "inner" + name ] = function()
     
                     These functions are tricky to remove since we can't
                     depend on just the name when removing them.  We're
                     just leaving them for now.
                     */
                    return null;
                } else {
                    /*
                     This is a property assignment function like:
                        myObj.func1 = function()
                     */
                    return n.getParent().getFirstChild().getLastChild().getString();
                }
            }
            
            if (n.getParent().getType() == Token.STRING) {
                /*
                 This is a closure style function like this:
                     myFunc: function()
                 */
                return n.getParent().getString();
            } else {
                /*
                 This is a standard type of function like this:
                    function myFunc()
                 */
                return n.getFirstChild().getString();
            }
        } catch (Exception e) {
            System.out.println("npe: " + n.toStringTree());
            e.printStackTrace();
            throw new RuntimeException("stop here...");
        }
    }
    
    /**
     * Find all of the functions with the specified name.
     * 
     * @param name   the name of the function to find
     * 
     * @return the functions with this matching name
     */
    private Node[] findMatchingFunctions(String name)
    {
        ArrayList<Node> matches = new ArrayList<Node>();
        
        for (Node n : m_allFuncs) {
            if (getFunctionNames(n).contains(name)) {
                matches.add(n);
            }
        }
        
        return matches.toArray(new Node[matches.size()]);
    }
    
    private void addExterns()
    {
        
    }
    
    public void addExtern(String extern)
    {
        m_calls.add(new Call(extern));
    }
    
    public static String plainCompile(String code) {
        Compiler compiler = new Compiler();
        
        CompilerOptions options = new CompilerOptions();
        // Advanced mode is used here, but additional options could be set, too.
        CompilationLevel.SIMPLE_OPTIMIZATIONS.setOptionsForCompilationLevel(options);
        
        // To get the complete set of externs, the logic in
        // CompilerRunner.getDefaultExterns() should be used here.
        JSSourceFile extern = JSSourceFile.fromCode("externs.js", "function alert(x) {}");
        
        // The dummy input name "input.js" is used here so that any warnings or
        // errors will cite line numbers in terms of input.js.
        JSSourceFile input = JSSourceFile.fromCode("input.js", code);
    
        // compile() returns a Result, but it is not needed here.
        compiler.compile(extern, input, options);
    
        // The compiler is responsible for generating the compiled code; it is not
        // accessible via the Result.
        return compiler.toSource();
    }
    
    /**
     * Get the list of kept functions after the pruning operation.
     * 
     * @return an array of the names of all the functions which were kept after the prune operation
     */
    public String[] getKeptFunctions()
    {
        ArrayList<String> funcs = new ArrayList<String>();
        for (Node n : m_keepers) {
            funcs.add(getFunctionName(n));
        }
        
        return funcs.toArray(new String[funcs.size()]);
    }
    
    /**
     * This method returns the total number of named or "interesting" functions found in the 
     * library files.  This count ignores anonymous functions and other functions this tool
     * doesn't analyze.
     * 
     * @return the total number of functions.
     */
    public int getTotalFunctionCount()
    {
        return m_funcCount;
    }
    
    /**
     * Get the error manager for this compilation.  The error manager is never null, but it
     * can return a zero error count.
     * 
     * @return the error manager
     */
    public ErrorManager getErrorManager()
    {
        return m_errMgr;
    }
    
    private static void writeGzip(String contents, File file)
        throws IOException
    {
        System.out.println("writeGzip(" + file + ")");
        FileUtils.writeStringToFile(file, contents);
        
        FileOutputStream out = new FileOutputStream(new File(file.getParentFile(), file.getName() + ".gz"));
        
        try {
            GZIPOutputStream zipOut = new GZIPOutputStream(out);
            OutputStreamWriter out2 = new OutputStreamWriter(new BufferedOutputStream(zipOut), "UTF-8");
    
            IOUtils.write(contents, out2);
        } finally {
            if (out != null) {
                out.close();
            }
        }
        
    }

    public static void main(String[] args) {
        try {
            JSlim slim = new JSlim();
            
            File in = new File("libs/prototype/main.js");
            
            String mainJS = FileUtils.readFileToString(in, "UTF-8");
            //String mainJS = FileUtils.readFileToString(new File("libs/easing/easing.js"), "UTF-8");
            //slim.slim(mainJS, false);
            
            //String libJS = FileUtils.readFileToString(new File("libs/jquery-ui-1.8.14.custom.min.js"), "UTF-8");
            //String libJS = FileUtils.readFileToString(new File("libs/jquery.min.js"), "UTF-8");
            //String libJS = FileUtils.readFileToString(new File("lib.js"), "UTF-8");
            //String libJS = FileUtils.readFileToString(new File("libs/jquery-1.6.2.js"), "UTF-8");
            //String libJS = FileUtils.readFileToString(new File("libs/easing/raphael.js"), "UTF-8");
            //String libJS = FileUtils.readFileToString(new File("libs/chart/raphael.js"), "UTF-8");
            //System.out.println("compiled code: " + slim.addLib(libJS));
            
            slim.addSourceFile(new JSFile("main.js", mainJS, false));

            //slim.addSourceFile(new JSFile("jquery-1.6.2.js", FileUtils.readFileToString(new File("libs/jquery-1.6.2.js"), "UTF-8"), true));
            //slim.addSourceFile(new JSFile("underscore.js", FileUtils.readFileToString(new File("libs/underscore.js"), "UTF-8"), true));
            
            slim.addSourceFile(new JSFile("modernizr-2.0.6.js", FileUtils.readFileToString(new File("libs/modernizr/modernizr-2.0.6.js"), "UTF-8"), true));
            
            File out = new File("libs/modernizr/out.js");
            JSlim.writeGzip(plainCompile(slim.prune()), out);
            //FileUtils.writeStringToFile(new File("out.js"), plainCompile(libJS));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
