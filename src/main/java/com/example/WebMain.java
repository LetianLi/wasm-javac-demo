/*
 * Copyright (c) 2025, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at https://opensource.org/license/UPL.
 */


package com.example;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import com.example.JavacCompilerWrapper;
import com.example.JavacCompilerWrapper.FileContent;
import com.example.preload.PreLoadedFiles;
import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSBoolean;
import org.graalvm.webimage.api.JSNumber;
import org.graalvm.webimage.api.JSObject;
import org.graalvm.webimage.api.JSString;

import com.sun.tools.javap.JavapTask;

public class WebMain {
    private static boolean isDisabled = true;
    public static final JSObject COMPILE_BUTTON = getElementById("compile");
    public static final JSObject OUTPUT = getElementById("diagnostics");
    public static final JSObject DISASSEMBLY = getElementById("disassembly");
    public static final JSObject FILES = getElementById("files");
    public static final JSObject FILE_INPUTS = getElementById("file-inputs");
    public static final JSObject ADD_FILE_BUTTON = getElementById("add-file");

    public static void main(String[] args) {
        // Ensure file manager is initialized
        JavacCompilerWrapper.getFm();
        try {
            // TODO GR-62854 Here to ensure handleEvent and run is generated. Remove once objects
            // passed to @JS methods automatically have their SAM registered.
            sink(EventHandler.class.getDeclaredMethod("handleEvent", JSObject.class));
            sink(Runnable.class.getDeclaredMethod("run"));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }

        addEventListener(COMPILE_BUTTON, "click", e -> compileCallback());
        addEventListener(ADD_FILE_BUTTON, "click", e -> addNewFileInput());
        setDisabled(false);
    }

    private static void compileCallback() {
        setDisabled(true);
        runAsync(() -> {
            try {
                compile();
            } finally {
                setDisabled(false);
            }
        });
    }

    private static void addNewFileInput() {
        JSObject div = createElement("div");
        setAttribute(div, "class", "file-input-group");
        
        JSObject fileNameInput = createElement("input");
        setAttribute(fileNameInput, "type", "text");
        setAttribute(fileNameInput, "class", "filename-input");
        setAttribute(fileNameInput, "placeholder", "Enter file name (e.g. MyClass.java)");
        if (isDisabled) {
            setAttribute(fileNameInput, "disabled", JSBoolean.of(true));
        } else {
            removeAttribute(fileNameInput, "disabled");
        }
     
        JSObject pre = createElement("pre");
        setAttribute(pre, "class", "source-input");
        setAttribute(pre, "contenteditable", JSBoolean.of(!isDisabled));
        setAttribute(pre, "spellcheck", "false");
        pre.set("textContent", "\n");  // Empty content
        
        appendChild(div, fileNameInput);
        appendChild(div, pre);
        appendChild(FILE_INPUTS, div);
    }

    @JS("")
    private static native void sink(Object o);

    /**
     * Runs the given {@link Runnable} in {@code setTimeout} without delay.
     * <p>
     * Use this to let the browser repaint before running other code (otherwise repainting is
     * blocked until the Java code returns).
     */
    @JS.Coerce
    @JS("setTimeout(r, 0);")
    private static native void runAsync(Runnable r);

    private static void runWithUncaughtHandler(Runnable r) {
        try {
            r.run();
        } catch (Throwable e) {
            System.err.println("Uncaught exception in event listener");
            e.printStackTrace();
        }
    }

    @JS.Coerce
    @JS("o.addEventListener(event, (e) => handler(e));")
    static native void addEventListenerImpl(JSObject o, String event, EventHandler handler);

    static void addEventListener(JSObject o, String event, EventHandler handler) {
        addEventListenerImpl(o, event, e -> runWithUncaughtHandler(() -> handler.handleEvent(e)));
    }

    @JS.Coerce
    @JS("return document.getElementById(id);")
    public static native JSObject getElementById(String id);

    @JS.Coerce
    @JS("return document.createElement(tag);")
    public static native JSObject createElement(String tag);

    @JS.Coerce
    @JS("return document.createTextNode(text);")
    public static native JSObject createTextNode(String text);

    @JS.Coerce
    @JS("elem.setAttribute(attribute, value);")
    public static native void setAttribute(JSObject elem, String attribute, Object value);

    @JS.Coerce
    @JS("elem.removeAttribute(attribute);")
    public static native void removeAttribute(JSObject elem, String attribute);

    @JS.Coerce
    @JS("parent.appendChild(child);")
    public static native void appendChild(JSObject parent, JSObject child);

    @JS.Coerce
    @JS("return document.querySelector(selector);")
    private static native JSObject querySelector(String selector);

    @JS.Coerce
    @JS("return Array.from(obj.querySelectorAll(selector));")
    private static native JSObject querySelectorAll(JSObject obj, String selector);

    @JS.Coerce
    @JS("return obj.querySelector(selector);")
    private static native JSObject querySelector(JSObject obj, String selector);

    @JS.Coerce
    @JS("return obj.removeChild(child);")
    private static native JSObject removeChild(JSObject obj, JSObject child);

    public static void resetOutput() {
        setDisabled(false);
        OUTPUT.set("innerHTML", "");
        FILES.set("innerHTML", "");
        DISASSEMBLY.set("innerHTML", "");
    }

    public static void appendOutput(String val) {
        OUTPUT.set("innerHTML", ((JSString) OUTPUT.get("innerHTML")).asString() + val + '\n');
    }

    private static void setDisabled(boolean state) {
        isDisabled = state;
        JSObject fileGroups = querySelectorAll(FILE_INPUTS, ".file-input-group");
        int length = ((JSNumber) fileGroups.get("length")).asInt();
        for (int i = 0; i < length; i++) {
            JSObject group = (JSObject) fileGroups.get(i);
            JSObject sourceInput = querySelector(group, ".source-input");
            JSObject fileNameInput = querySelector(group, ".filename-input");
            setAttribute(sourceInput, "contenteditable", JSBoolean.of(!state));
            if (state) {
                setAttribute(fileNameInput, "disabled", JSBoolean.of(true));
            } else {
                removeAttribute(fileNameInput, "disabled");
            }
        }
        COMPILE_BUTTON.set("disabled", JSBoolean.of(state));
        ADD_FILE_BUTTON.set("disabled", JSBoolean.of(state));
    }

    private static void appendFileDownload(FileContent content) {
        String fileName = content.name();
        String className = fileName.substring(0, fileName.length() - ".class".length());
        String base64Content = Base64.getEncoder().encodeToString(content.content());
        String uri = "data:application/java-vm;base64," + base64Content;
        JSObject item = createElement("li");
        JSObject link = createElement("a");
        JSObject disassembleButton = createElement("button");

        setAttribute(link, "href", uri);
        setAttribute(link, "download", fileName);
        link.set("textContent", fileName);

        disassembleButton.set("textContent", "Disassemble");
        addEventListener(disassembleButton, "click", e -> disassemble(className));

        appendChild(item, link);
        appendChild(item, createTextNode(" "));
        appendChild(item, disassembleButton);
        appendChild(FILES, item);
    }

    public static void compile() {
        List<FileContent> sourceFiles = new ArrayList<>();
        JSObject fileGroups = querySelectorAll(FILE_INPUTS, ".file-input-group");
        
        int length = ((JSNumber) fileGroups.get("length")).asInt();
        for (int i = length - 1; i >= 0; i--) {
            JSObject group = (JSObject) fileGroups.get(i);
            JSObject fileNameInput = querySelector(group, ".filename-input");
            JSObject sourceInput = querySelector(group, ".source-input");
            
            String fileName = ((JSString) fileNameInput.get("value")).asString().trim();
            String source = ((JSString) sourceInput.get("innerText")).asString().trim();
            
            if (fileName.isEmpty() || source.isEmpty()) {
                removeChild(FILE_INPUTS, group);
                continue;
            }
            
            byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
            sourceFiles.add(new FileContent(fileName, sourceBytes));
        }

        long start = System.nanoTime();
        JavacCompilerWrapper.Result r;
        try {
            r = JavacCompilerWrapper.compileFiles(List.of("-Xlint:all", "-parameters"), 
                sourceFiles.toArray(new FileContent[0]));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        long nanos = System.nanoTime() - start;
        double millis = nanos / 1e6;

        resetOutput();

        if (r.success()) {
            appendOutput("SUCCESS " + millis + "ms");
        } else {
            appendOutput("ERROR " + millis + "ms");
        }

        appendOutput("");

        // Highlight all source inputs
        fileGroups = querySelectorAll(FILE_INPUTS, ".file-input-group");
        length = ((JSNumber) fileGroups.get("length")).asInt();
        for (int i = 0; i < length; i++) {
            JSObject group = (JSObject) fileGroups.get(i);
            JSObject sourceInput = querySelector(group, ".source-input");
            String source = ((JSString) sourceInput.get("innerText")).asString();
            
            Highlighter highlighter = new Highlighter(r.diagnostics());
            source.chars().forEach(c -> highlighter.append((char) c));
            String highlightedCode = highlighter.finish();
            sourceInput.set("innerHTML", JSString.of(highlightedCode));
        }

        for (Diagnostic<? extends JavaFileObject> d : r.diagnostics()) {
            appendOutput(d.toString());
        }

        for (FileContent file : r.files()) {
            appendFileDownload(file);
        }
    }

    private static void disassemble(String className) {
        JavapTask t = new JavapTask();
        StringWriter stringWriter = new StringWriter();
        t.setLog(stringWriter);
        try {
            t.handleOptions(new String[]{"-cp", PreLoadedFiles.OUTPUT_PATH, "-l", "-s", "-c", "-p", className});
        } catch (JavapTask.BadArgs e) {
            throw new RuntimeException(e);
        }

        int exitCode = t.run();

        String outputString = stringWriter.toString();

        if (exitCode != 0) {
            outputString = "javap exited with error code " + exitCode + ":\n\n" + outputString;
        }

        DISASSEMBLY.set("innerText", outputString);
    }
}

class Highlighter {
    private final List<Diagnostic<? extends JavaFileObject>> diagnostics;
    private final StringBuilder sb;
    private int position = 0;

    private String currentColor = null;

    Highlighter(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        this.diagnostics = diagnostics;
        this.sb = new StringBuilder();
    }

    private Diagnostic<?> getWorstDiagnostic() {
        Diagnostic<?> worst = null;
        for (Diagnostic<?> diagnostic : diagnostics) {
            if ((position >= diagnostic.getStartPosition() && position < diagnostic.getEndPosition()) || diagnostic.getPosition() == position) {
                if (worst == null || diagnostic.getKind().ordinal() < worst.getKind().ordinal()) {
                    worst = diagnostic;
                }

                if (worst.getKind() == Diagnostic.Kind.ERROR) {
                    break;
                }
            }
        }

        return worst;
    }

    private String diagnosticToColor(Diagnostic<?> d) {
        if (d == null) {
            return null;
        }

        return switch (d.getKind()) {
            case ERROR -> "red";
            case WARNING, MANDATORY_WARNING -> "orange";
            case NOTE, OTHER -> "blue";
        };
    }

    private void updateColor(Diagnostic<?> newDiagnostic) {
        String newColor = diagnosticToColor(newDiagnostic);
        if (!Objects.equals(newColor, currentColor)) {
            if (currentColor != null) {
                sb.append("</span>");
            }

            if (newColor != null) {
                sb.append("<span style='text-decoration:underline wavy ").append(newColor).append(";'>");
            }

            currentColor = newColor;
        }
    }

    public void append(char c) {
        Diagnostic<?> worstDiagnostic = getWorstDiagnostic();
        updateColor(worstDiagnostic);
        if (c == '\n' && worstDiagnostic != null && worstDiagnostic.getStartPosition() == worstDiagnostic.getEndPosition()) {
            // Add an additional space before newlines if the diagnostic is exactly on the newline,
            // otherwise the underline will not be seen
            sb.append(' ');
        }

        switch (c) {
            case '<' -> sb.append("&lt;");
            case '>' -> sb.append("&gt;");
            default -> sb.append(c);
        }

        position++;
    }

    public String finish() {
        updateColor(null);
        String string = sb.toString();
        sb.setLength(0);
        position = 0;
        return string;
    }
}

@FunctionalInterface
interface EventHandler {
    void handleEvent(JSObject event);
}
