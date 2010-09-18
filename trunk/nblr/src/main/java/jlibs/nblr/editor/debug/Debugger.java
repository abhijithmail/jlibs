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

package jlibs.nblr.editor.debug;

import jlibs.core.annotation.processing.Printer;
import jlibs.core.io.FileUtil;
import jlibs.core.lang.ImpossibleException;
import jlibs.core.lang.StringUtil;
import jlibs.nblr.actions.BufferAction;
import jlibs.nblr.actions.EventAction;
import jlibs.nblr.actions.PublishAction;
import jlibs.nblr.codegen.JavaCodeGenerator;
import jlibs.nblr.editor.RuleScene;
import jlibs.nblr.editor.Util;
import jlibs.nblr.rules.Edge;
import jlibs.nblr.rules.Node;
import jlibs.nblr.rules.Rule;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Observable;
import java.util.Observer;

/**
 * @author Santhosh Kumar T
 */
public class Debugger extends JPanel implements Observer{
    private RuleScene scene;
    private JTextArea input = new JTextArea();
    private JList ruleStackList = new JList(new DefaultListModel());

    public Debugger(RuleScene scene){
        super(new BorderLayout(5, 5));
        this.scene = scene;
        scene.ruleObservable.addObserver(this);

        JToolBar toolbar = Util.toolbar(
            runAction,
            debugAction,
            null,
            stepAction,
            runToCursorAction,
            resumeAction,
            suspendAction
        );
        add(toolbar, BorderLayout.NORTH);

        input.setFont(Util.FIXED_WIDTH_FONT);
        add(new JScrollPane(input), BorderLayout.CENTER);

        ruleStackList.setFont(Util.FIXED_WIDTH_FONT);
        add(new JScrollPane(ruleStackList), BorderLayout.EAST);

        message.setFont(Util.FIXED_WIDTH_FONT);
        add(message, BorderLayout.SOUTH);

        updateActions();
    }

    private String compile(File file){
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        ArrayList<String> args = new ArrayList<String>();
        args.add("-d");
        args.add(file.getParentFile().getAbsolutePath());
        args.add("-s");
        args.add(file.getParentFile().getAbsolutePath());
        args.add(file.getAbsolutePath());
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        if(compiler.run(null, null, err, args.toArray(new String[args.size()]))==0)
            return null;
        return err.toString();
    }

    private NBParser parser;
    private int inputIndex;
    private void start(){
        try{
            File file = new File("temp/DebuggableNBParser.java").getAbsoluteFile();
            FileUtil.mkdirs(file.getParentFile());
            
            Printer printer = new Printer(new PrintWriter(new FileWriter(file)));
            JavaCodeGenerator codeGenerator = new JavaCodeGenerator(scene.getSyntax(), printer);
            codeGenerator.setDebuggable();
            codeGenerator.generateCode();
            printer.close();
            
            String error = compile(file);
            if(error!=null){
                JOptionPane.showMessageDialog(this, error);
                return;
            }

            URLClassLoader classLoader = new URLClassLoader(new URL[]{FileUtil.toURL(file.getParentFile())});
            Class clazz = classLoader.loadClass("DebuggableNBParser");
            parser = (NBParser)clazz.getConstructor(getClass()).newInstance(this);
            parser.startParsing(scene.getRule().id);
        }catch(Exception ex){
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, ex.getMessage());
        }
    }

    private void step(){
        try{
            if(inputIndex<input.getDocument().getLength()){
                char ch = input.getDocument().getText(inputIndex, 1).charAt(0);
                parser.consume(ch);
                inputIndex++;
                updateGuardedBlock();
            }else{
                parser.eof();
                stop(null, "Input Matched");
            }
        }catch(BadLocationException ex){
            throw new ImpossibleException(ex);
        }catch(Exception ex){
            stop(ex, null);
        }
    }

    private void stop(Exception ex, String message){
        parser = null;
        inputIndex = 0;
        if(ex==null){
            clearGuardedBlock();
            showMessage(message);
        }else
            showError(ex);
    }

    /*-------------------------------------------------[ Message ]---------------------------------------------------*/
    
    private JLabel message = new JLabel();

    private void showMessage(String msg){
        message.setForeground(Color.BLUE);
        message.setText(msg);
    }

    private void showError(Exception ex){
        String text = ex.getMessage();
        if(!(ex instanceof ParseException)){
            ex.printStackTrace();
            text = "[BUG] "+text;
        }
        message.setForeground(Color.RED);
        message.setText(text);
    }

    /*-------------------------------------------------[ GuardBlock ]---------------------------------------------------*/
    
    private Highlighter.HighlightPainter highlightPainter = new GuardBlockHighlightPainter(Color.LIGHT_GRAY);
    private void updateGuardedBlock() throws BadLocationException{
        input.getHighlighter().removeAllHighlights();
        input.getHighlighter().addHighlight(1, inputIndex-1, highlightPainter);
        input.repaint();
    }

    private void clearGuardedBlock(){
        input.getHighlighter().removeAllHighlights();
        input.repaint();
    }

    /*-------------------------------------------------[ Actions ]---------------------------------------------------*/

    private void updateActions(){
        if(scene.getSyntax()!=null){
            String lengthyRuleName = "XXXXXXXXX";
            for(Rule rule: scene.getSyntax().rules.values()){
                if(rule.name.length()>lengthyRuleName.length())
                    lengthyRuleName = rule.name;

            }
            ruleStackList.setPrototypeCellValue(lengthyRuleName);
        }
        JScrollPane scroll = (JScrollPane)ruleStackList.getParent().getParent();
        scroll.setVisible(parser!=null);
        DefaultListModel model = (DefaultListModel)ruleStackList.getModel();
        model.clear();
        if(parser!=null){
            Rule rules[] = scene.getSyntax().rules.values().toArray(new Rule[scene.getSyntax().rules.values().size()]);
            for(int i: parser.getRuleStack())
                model.insertElementAt(rules[i], 0);
            ruleStackList.setSelectedIndex(model.size()-1);
        }
        scroll.revalidate();
        doLayout();
        input.revalidate();

        runAction.setEnabled(parser==null);
        debugAction.setEnabled(parser==null);
        stepAction.setEnabled(parser!=null);
        resumeAction.setEnabled(parser!=null);
        suspendAction.setEnabled(parser!=null);
        runToCursorAction.setEnabled(parser!=null && inputIndex<input.getCaretPosition());
    }
    
    private ImageIcon icon(String name){
        return new ImageIcon(getClass().getResource(name));
    }

    private Action runAction = new AbstractAction("Run", icon("run.png")){
        public void actionPerformed(ActionEvent ae){
            start();
            while(parser!=null)
                step();
            updateActions();
        }
    };

    private Action debugAction = new AbstractAction("Debug", icon("debug.png")){
        public void actionPerformed(ActionEvent ae){
            start();
            updateActions();
        }
    };

    private Action stepAction = new AbstractAction("Step", icon("step.png")){
        public void actionPerformed(ActionEvent ae){
            step();
            updateActions();
        }
    };

    private Action runToCursorAction = new AbstractAction("Run to Cursor", icon("runToCursor.png")){
        public void actionPerformed(ActionEvent ae){
            while(parser!=null && inputIndex<input.getCaretPosition())
                step();
            updateActions();
        }
    };

    private Action resumeAction = new AbstractAction("Resume", icon("resume.png")){
        public void actionPerformed(ActionEvent ae){
            while(parser!=null)
                step();
            updateActions();
        }
    };

    private Action suspendAction = new AbstractAction("Stop", icon("suspend.png")){
        public void actionPerformed(ActionEvent ae){
            stop(null, "");
            updateActions();
        }
    };

    @Override
    public void update(Observable o, Object rule){
        ListModel model = ruleStackList.getModel();
        for(int i= model.getSize()-1; i>=0; i--){
            if(model.getElementAt(i)==rule){
                ruleStackList.setSelectedIndex(i);
                return;
            }
        }
        ruleStackList.clearSelection();
    }

    /*-------------------------------------------------[ Consumer ]---------------------------------------------------*/

    private java.util.List<Node> nodes = new ArrayList<Node>();
    private java.util.List<Edge> edges = new ArrayList<Edge>();
    public void currentRule(int id){
//        System.out.println("currentRule("+id+")");
        Rule rule = (Rule)scene.getSyntax().rules.values().toArray()[id];
        nodes.clear();
        edges.clear();
        rule.computeIDS(nodes, edges, rule.node);
        scene.setRule(scene.getSyntax(), rule);
    }

    public void hitNode(int id, String data){
//        System.out.println("hitNode("+id+")");
        Node node = nodes.get(id);
        if(node.action== BufferAction.INSTANCE)
            System.out.println("BUFFERRING");
        else if(node.action instanceof PublishAction){
            PublishAction action = (PublishAction)node.action;
            System.out.println(action.name+"(\""+ StringUtil.toLiteral(data, false)+"\")");
        }else if(node.action instanceof EventAction){
            EventAction action = (EventAction)node.action;
            System.out.println(action.name+"()");
        }
        scene.executing(node);
    }

    public void currentNode(int id){
//        System.out.println("currentNode("+id+")");
//        System.out.println("--------------------------");
        Node node = nodes.get(id);
        scene.executing(node);
    }
}