/**
 * JLibs: Common Utilities for Java
 * Copyright (C) 2009  Santhosh Kumar T <santhosh.tekuri@gmail.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 */

package jlibs.nblr.codegen.java;

import jlibs.core.lang.StringUtil;
import jlibs.nblr.Syntax;
import jlibs.nblr.codegen.CodeGenerator;
import jlibs.nblr.editor.debug.Debugger;
import jlibs.nblr.matchers.Matcher;
import jlibs.nblr.rules.*;
import jlibs.nbp.NBParser;

import java.util.List;

import static jlibs.core.annotation.processing.Printer.MINUS;
import static jlibs.core.annotation.processing.Printer.PLUS;

/**
 * @author Santhosh Kumar T
 */
public class JavaCodeGenerator extends CodeGenerator{
    public JavaCodeGenerator(Syntax syntax){
        super(syntax);
    }

    @Override
    protected void printTitleComment(String title){
        printer.println("/*-------------------------------------------------[ "+title+" ]---------------------------------------------------*/");
    }

    @Override
    protected void startParser(){
        printer.printClassDoc();

        String className[] = className(parserName);
        if(className[0].length()>0){
            printer.printlns(
                "package "+className[0]+";",
                ""
            );
        }

        String extend = (debuggable ? DebuggableNBParser.class : NBParser.class).getName();
        printer.printlns(
            "public class "+className[1]+" extends "+extend+"{",
                PLUS
        );
    }

    private String[] className(String className){
        String pakage = "";
        String simpleName = className;
        int dot = simpleName.lastIndexOf('.');
        if(dot!=-1){
            pakage = simpleName.substring(0, dot);
            simpleName = simpleName.substring(dot+1);
        }
        return new String[]{ pakage, simpleName };
    }

    @Override
    protected void finishParser(int maxLookAhead){
        String className = className(parserName)[1];
        String debuggerArgs = "";
        if(debuggable)
            debuggerArgs = ", consumer";

        printer.printlns(
                "private final "+consumerName+" consumer;",
                "public "+className+"("+consumerName+" consumer){",
                    PLUS,
                    "super("+maxLookAhead+debuggerArgs+");",
                    "this.consumer = consumer;",
                    MINUS,
                "}",
                MINUS,
            "}"
        );
    }

    @Override
    protected void printMatcherMethod(Matcher matcher){
        printer.printlns(
            "private boolean "+matcher.name+"(char ch){",
                PLUS,
                "return "+matcher.javaCode("ch")+';',
                MINUS,
            "}"
        );
    }

    @Override
    protected void addRuleID(String name, int id){
        printer.println("public static final int RULE_"+name+" = "+id+';');
    }

    @Override
    protected void startRuleMethod(Rule rule){
        printer.printlns(
            "private int "+rule.name+"(char ch, boolean eof) throws java.text.ParseException{",
                PLUS,
                "switch(stateStack.peek()){",
                    PLUS
        );
    }

    @Override
    protected void startCase(int id){
        printer.printlns(
            "case "+id+":",
                PLUS
        );
    }

    @Override
    protected void endCase(){
        printer.printlns(
            MINUS
        );
    }

    @Override
    protected void finishRuleMethod(Rule rule){
        printer.printlns(
                    "default:",
                        PLUS,
                        "throw new Error(\"impossible\");",
                        MINUS,
                    MINUS,
                "}",
                MINUS,
            "}"
        );
    }

    @Override
    protected void startCallRuleMethod(){
        String prefix = debuggable ? "_" : "";
        printer.printlns(
            "@Override",
            "protected int "+prefix+"callRule(char ch, boolean eof) throws java.text.ParseException{",
                PLUS,
                "switch(ruleStack.peek()){",
                    PLUS
        );
    }

    @Override
    protected void callRuleMethod(String ruleName){
        printer.println("return "+ruleName+"(ch, eof);");
    }

    @Override
    protected void finishCallRuleMethod(){
        finishRuleMethod(null);
    }

    @Override
    protected void addRoutes(Routes routes){
        String expected = "expected(ch, eof, \""+ StringUtil.toLiteral(routes.toString(), false)+"\");";

        boolean lookAheadBufferReqd = routes.maxLookAhead>1;
        if(lookAheadBufferReqd)
            printer.printlns("lookAhead.add(ch, eof);");

        for(int lookAhead: routes.lookAheads()){
            if(lookAheadBufferReqd){
                printer.printlns(
                    "if(!eof && lookAhead.length()<"+lookAhead+")",
                        PLUS,
                        "return "+routes.fromNode.id+";",
                        MINUS
                );

                printer.printlns(
                    "if(lookAhead.length()=="+lookAhead+"){",
                        PLUS
                );
            }
            print(routes.determinateRoutes(lookAhead), lookAheadBufferReqd);
            if(lookAheadBufferReqd){
                printer.printlns(
                        MINUS,
                    "}"
                );
            }
        }

        if(routes.indeterminateRoute !=null){
            Path path = routes.indeterminateRoute.route()[0];
            Matcher matcher = path.matcher();
            startIf(matcher, true, 0);
            print(path, true);
            endIf(1);
        }

        if(routes.routeStartingWithEOF!=null)
            print(routes.routeStartingWithEOF, false);
        else
            printer.println(expected);
    }

    private void print(List<Path> routes, boolean consumeLookAhead){
        for(Path route: routes){
            int ifCount = 0;
            Path[] paths = route.route();
            for(int ipath=0; ipath<paths.length; ipath++){
                Matcher matcher = paths[ipath].matcher();
                if(matcher!=null){
                    startIf(matcher, route.depth>1, ipath);
                    ifCount++;
                }
            }
            print(paths[0], consumeLookAhead);
            endIf(ifCount);
        }
    }
    
    private void startIf(Matcher matcher, boolean useLookAheadBuffer, int lookAheadIndex){
        String ch = "ch";
        String eof = "eof";
        if(useLookAheadBuffer){
            ch = "lookAhead.charAt("+lookAheadIndex+')';
            eof = "lookAhead.isEOF("+lookAheadIndex+')';
        }

        String condition = matcher._javaCode(ch);
        if(matcher.name==null)
            condition = '('+condition+')';
        printer.printlns(
            "if(!"+eof+" && "+condition+"){",
                PLUS
        );
    }

    private void endIf(int count){
        while(count-->0){
            printer.printlns(
                    MINUS,
                "}"
            );
        }
    }

    private StringBuilder nodesToBeExecuted = new StringBuilder();
    private void println(String line){
        if(nodesToBeExecuted.length()>0){
            printer.println("consumer.execute("+nodesToBeExecuted+");");
            nodesToBeExecuted.setLength(0);
        }
        printer.println(line);
    }
    private void print(Path path, boolean consumeLookAhead){
        nodesToBeExecuted.setLength(0);
        
        int nextState = -1;
        boolean wasNode = false;
        for(Object obj: path){
            if(obj instanceof Node){
                if(wasNode)
                    println("pop();");
                wasNode = true;

                Node node = (Node)obj;
                if(debuggable){
                    if(nodesToBeExecuted.length()>0)
                        nodesToBeExecuted.append(", ");
                    nodesToBeExecuted.append(node.id);
                }else if(node.action!=null)
                    printer.println(node.action.javaCode()+';');
            }else if(obj instanceof Edge){
                wasNode = false;
                Edge edge = (Edge)obj;
                if(edge.ruleTarget!=null)
                    println("push(RULE_"+edge.ruleTarget.rule.name+", "+edge.target.id+", "+edge.ruleTarget.node().id+");");
                else if(edge.matcher!=null){
                    nextState = edge.target.id;
                    if(consumeLookAhead)
                        println("consumed();");
                    break;
                }
            }
        }
        println("return "+nextState+';');
    }
    
    /*-------------------------------------------------[ Consumer ]---------------------------------------------------*/

    protected void startConsumer(){
        printer.printClassDoc();

        String className[] = className(consumerName);
        if(className[0].length()>0){
            printer.printlns(
                "package "+className[0]+";",
                ""
            );
        }

        String keyWord = consumerClass ? "class" : "interface";
        printer.printlns(
            "public "+keyWord+" "+className[1]+"{",
                PLUS
        );
    }

    protected void addPublishMethod(String name){
        if(consumerClass){
            printer.printlns(
                "public void "+name+"(String data){",
                    PLUS,
                        "System.out.println(\""+name+"(\\\"\"+data+\"\\\")\");",
                    MINUS,
                "}"
            );
        }else
            printer.println("public void "+name+"(String data);");
    }

    protected void addEventMethod(String name){
        if(consumerClass){
            printer.printlns(
                "public void "+name+"(){",
                    PLUS,
                        "System.out.println(\""+name+"\");",
                    MINUS,
                "}"
            );
        }else
            printer.println("public void "+name+"();");
    }

    protected void finishConsumer(){
        printer.printlns(
                MINUS,
            "}"
        );
    }

    /*-------------------------------------------------[ Customization ]---------------------------------------------------*/

    private String parserName = "UntitledParser";
    public void setParserName(String parserName){
        this.parserName = parserName;
    }

    private String consumerName = "Consumer";
    private boolean consumerClass = false;
    public void setConsumerName(String consumerName, boolean isClass){
        this.consumerName = consumerName;
        this.consumerClass = isClass;
    }

    @Override
    public void setDebuggable(){
        super.setDebuggable();
        consumerName = Debugger.class.getName();
        consumerClass = true;
    }
}