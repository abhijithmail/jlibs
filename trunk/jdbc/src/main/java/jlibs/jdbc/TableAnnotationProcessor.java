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

package jlibs.jdbc;

import jlibs.core.annotation.processing.AnnotationError;
import jlibs.core.annotation.processing.AnnotationProcessor;
import jlibs.core.annotation.processing.Printer;
import jlibs.core.lang.BeanUtil;
import jlibs.core.lang.ImpossibleException;
import jlibs.core.lang.StringUtil;
import jlibs.core.lang.model.ModelUtil;
import jlibs.jdbc.annotations.Column;
import jlibs.jdbc.annotations.Delete;
import jlibs.jdbc.annotations.Table;

import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.ElementFilter;
import javax.sql.DataSource;
import java.io.IOException;
import java.util.*;

import static jlibs.core.annotation.processing.Printer.MINUS;
import static jlibs.core.annotation.processing.Printer.PLUS;

/**
 * @author Santhosh Kumar T
 */
@SupportedAnnotationTypes("jlibs.jdbc.annotations.Table")
@SupportedSourceVersion(SourceVersion.RELEASE_6)
public class TableAnnotationProcessor extends AnnotationProcessor{
    private static final String SUFFIX = "DAO";
    public static final String FORMAT = "${package}._${class}"+SUFFIX;

    private Map<ExecutableElement, AnnotationMirror> methods = new HashMap<ExecutableElement, AnnotationMirror>();
    private Map<String, String> properties = new HashMap<String, String>();
    
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv){
        for(TypeElement annotation: annotations){
            for(Element elem: roundEnv.getElementsAnnotatedWith(annotation)){
                try{
                    methods.clear();
                    properties.clear();
                    
                    TypeElement c = (TypeElement)elem;
                    while(c!=null && !c.getQualifiedName().contentEquals(Object.class.getName())){
                        process(c);
                        c = ModelUtil.getSuper(c);
                    }
                    c = (TypeElement)elem;

                    Printer pw = null;
                    try{
                        pw = Printer.get(c, Table.class, FORMAT);
                        generateClass(pw);
                    }catch(IOException ex){
                        throw new RuntimeException(ex);
                    }finally{
                        if(pw!=null)
                            pw.close();
                    }
                }catch(AnnotationError error){
                    error.report();
                }
            }
        }
        return true;
    }

    private void process(TypeElement c){
        for(ExecutableElement method: ElementFilter.methodsIn(c.getEnclosedElements())){
            AnnotationMirror mirror = ModelUtil.getAnnotationMirror(method, Column.class);
            if(mirror!=null){
                methods.put(method, mirror);
                String columnName = ModelUtil.getAnnotationValue(method, mirror, "value");
                if(properties.put(propertyName(method), columnName)!=null)
                    throw new AnnotationError(method, mirror, "duplicate column: "+columnName);
            }
        }
    }

    public String propertyName(ExecutableElement method){
        String methodName = method.getSimpleName().toString();
        methodName = methodName.substring(methodName.startsWith("get") ? 3 : 2);
        switch(methodName.length()){
            case 0:
                return methodName;
            case 1:
                return methodName.toLowerCase();
            default:
                return Character.toLowerCase(methodName.charAt(0))+methodName.substring(1);
        }
    }

    public Set<String> columnNames(){
        Set<String> columns = new LinkedHashSet<String>();
        for(Map.Entry<ExecutableElement, AnnotationMirror> entry: methods.entrySet()){
            String column = ModelUtil.getAnnotationValue(entry.getKey(), entry.getValue(), "value");
            if(!columns.add(column))
                throw new AnnotationError(entry.getKey(), "duplicate column: "+column);
        }
        return columns;
    }

    public List<Boolean> primaries(){
        List<Boolean> primaries = new ArrayList<Boolean>();
        for(Map.Entry<ExecutableElement, AnnotationMirror> entry: methods.entrySet()){
            Boolean primary = ModelUtil.getAnnotationValue(entry.getKey(), entry.getValue(), "primary");
            primaries.add(primary);
        }
        return primaries;
    }

    private void generateClass(Printer printer){
        printer.printPackage();

        printer.importClass(ImpossibleException.class);
        printer.importClass(DAO.class);
        printer.importClass(DataSource.class);
        printer.println();

        printer.printClassDoc();
        TypeElement extendClass = (TypeElement)((DeclaredType)ModelUtil.getAnnotationValue(printer.clazz, Table.class, "extend")).asElement();
        printer.print("public class "+printer.generatedClazz +" extends "+extendClass.getQualifiedName());
        if(extendClass.getQualifiedName().contentEquals(DAO.class.getName()))
            printer.println("<"+printer.clazz.getSimpleName()+'>');
        printer.println("{");
        printer.indent++;

        generateConstructor(printer);
        printer.println();
        generateNewRow(printer);
        printer.println();

        generateGetColumnValue(printer, methods);
        printer.println();
        generateSetColumnValue(printer, methods);

        for(ExecutableElement method: ElementFilter.methodsIn(extendClass.getEnclosedElements())){
            AnnotationMirror delete = ModelUtil.getAnnotationMirror(method, Delete.class);
            if(delete!=null)
                generateDeleteMethod(printer, method);
        }
        printer.indent--;
        printer.println("}");
    }

    private void generateConstructor(Printer printer){
        String tableName = ModelUtil.getAnnotationValue(printer.clazz, Table.class, "value");
        printer.printlns(
            "public "+printer.generatedClazz+"(DataSource dataSource){",
                PLUS,
                "super(dataSource, \""+tableName+"\", "+columnsArray()+", "+primariesArray()+");",
                MINUS,
            "}"
        );
    }

    private void generateNewRow(Printer printer){
        printer.printlns(
            "@Override",
            "public "+printer.clazz.getSimpleName()+" newRow(){",
                PLUS,
                "return new "+printer.clazz.getSimpleName()+"();",
                MINUS,
            "}"
        );
    }

    private String columnsArray(){
        StringBuilder buff = new StringBuilder("new String[]{ ");
        int i = 0;
        for(String column: columnNames()){
            if(i>0)
                buff.append(", ");
            buff.append('"').append(StringUtil.toLiteral(column, false)).append('"');
            i++;
        }
        buff.append(" }");
        return buff.toString();
    }

    private String primariesArray(){
        StringBuilder buff = new StringBuilder("new boolean[]{ ");
        int i = 0;
        for(Boolean primary: primaries()){
            if(i>0)
                buff.append(", ");
            buff.append(primary);
            i++;
        }
        buff.append(" }");
        return buff.toString();
    }

    private void addDefaultCase(Printer printer){
        printer.printlns(
                "default:",
                    PLUS,
                    "throw new ImpossibleException();",
                    MINUS,
                    MINUS,
                "}",
             MINUS,
            "}"
        );
    }
    
    private void generateGetColumnValue(Printer printer, Map<ExecutableElement, AnnotationMirror> methods){
        printer.printlns(
            "@Override",
            "public Object getColumnValue(int i, "+printer.clazz.getSimpleName()+" record){",
                PLUS,
                "switch(i){",
                    PLUS
        );
        int i = 0;
        for(ExecutableElement method: methods.keySet()){
            printer.printlns(
                "case "+i+":",
                    PLUS,
                    "return record."+method.getSimpleName()+"();",
                    MINUS
            );
            i++;
        }
        addDefaultCase(printer);
    }

    private void generateSetColumnValue(Printer printer, Map<ExecutableElement, AnnotationMirror> methods){
        printer.printlns(
            "@Override",
            "public void setColumnValue(int i, "+printer.clazz.getSimpleName()+" record, Object value){",
                PLUS,
                "switch(i){",
                    PLUS
        );
        int i = 0;
        for(ExecutableElement method: methods.keySet()){
            String propertyName = propertyName(method);
            String returnType = ModelUtil.toString(method.getReturnType(), true);
            printer.printlns(
                "case "+i+":",
                    PLUS,
                    "record.set"+ BeanUtil.firstLetterToUpperCase(propertyName)+"(("+returnType+")value);",
                    "break;",
                    MINUS
            );
            i++;
        }
        addDefaultCase(printer);
    }

    private void generateDeleteMethod(Printer printer, ExecutableElement method){
        StringBuilder condition = new StringBuilder("where ");
        StringBuilder params = new StringBuilder();
        int i = 0;
        for(VariableElement param : method.getParameters()){
            String paramName = param.getSimpleName().toString();
            params.append(", ");
            if(i>0){
                condition.append(" and ");
            }
            params.append(paramName);
            String columnName = properties.get(paramName);
            if(columnName==null)
                throw new AnnotationError(method, "invalid column property: "+paramName);
            condition.append(columnName).append("=?");
            i++;
        }
        printer.printlns(
            "",
            "@Override",
            ModelUtil.signature(method, true)+"{",
                PLUS
        );

        boolean noException = method.getThrownTypes().size() == 0;
        if(noException){
            printer.printlns(
                "try{",
                    PLUS
            );
        }
        printer.println("return delete(\""+StringUtil.toLiteral(condition.toString(), false)+'"'+params+");");
        if(noException){
            printer.printlns(
                    MINUS,
                "}catch(java.sql.SQLException ex){",
                    PLUS,
                    "throw new "+JDBCException.class.getName()+"(ex);",
                    MINUS,
                "}"
            );
        }
        printer.printlns(
                MINUS,
            "}"
        );
    }
}
